package com.rlclones.ai.brain;

import com.rlclones.Config;
import com.rlclones.ai.combat.CombatAction;
import com.rlclones.ai.combat.CombatState;
import com.rlclones.ai.strategy.LongTermGoal;
import com.rlclones.ai.strategy.GoalState;
import com.rlclones.ai.strategy.LearnedSkill;
import com.rlclones.ai.strategy.Option;
import com.rlclones.ai.strategy.SkillState;
import com.rlclones.ai.strategy.StrategyState;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * All learned state of one clone (or of the whole hive when clones are linked with X):
 * one combat Q-table per enemy type, a strategy Q-table, learned enemy knowledge and a replay memory.
 * Learning is off-policy Q-learning, so experience from watching others is valid training data too.
 */
public final class Brain {
    public static final String STRATEGY = "#strategy";
    public static final String GOALS = "#goals";
    public static final String SKILL_INITIATION = "#skill_init";
    public static final String SKILL_TERMINATION = "#skill_stop";
    public static final String ROUTE_CHOICES = "#route_choices";
    public static final int ROUTE_DIRECT = 0;
    public static final int ROUTE_RISK_AWARE = 1;
    public static final String REPORT_VERIFICATION = "#report_verification";
    public static final int REPORT_DEFER = 0;
    public static final int REPORT_VERIFY = 1;

    public static final int ROUTE_NIGHT = 1;
    public static final int ROUTE_DARK = 1 << 1;
    public static final int ROUTE_WATER = 1 << 2;
    public static final int ROUTE_HOSTILE = 1 << 3;
    public static final int ROUTE_ROUGH = 1 << 4;
    private static final int ROUTE_CONTEXT_MASK = 31;

    private final Map<String, QTable> combat = new HashMap<>();
    private final Map<String, EnemyKnowledge> knowledge = new HashMap<>();
    private final QTable strategy = new QTable(Option.COUNT);
    private final QTable goals = new QTable(LongTermGoal.COUNT);
    private final QTable skillInitiation = new QTable(SkillState.BOUNDARY_ACTIONS);
    private final QTable skillTermination = new QTable(SkillState.BOUNDARY_ACTIONS);
    private final QTable routeChoices = new QTable(2);
    private final QTable reportVerification = new QTable(2);
    private final ReplayBuffer replay = new ReplayBuffer(4096);
    private final Random random = new Random();

    public long ownUpdates;
    public long imitationUpdates;
    public long replayUpdates;
    public long counterfactualUpdates;
    public long decisions;
    public long kills;
    public long deaths;
    public int members = 1;

    /** Blocks learned to hurt on contact (magma, cactus, modded infection blocks...): block id -> evidence count. */
    private final it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<String> harmfulBlocks = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<>();

    public boolean isHarmful(String blockId) {
        return harmfulBlocks.getInt(blockId) > 0;
    }

    public void learnHarmful(String blockId) {
        harmfulBlocks.mergeInt(blockId, 1, Integer::sum);
        safeBlocks.remove(blockId);
    }

    /** Blocks once thought harmful that were touched for a long while without any harm: corrected, and not believed again on hearsay. */
    private final Set<String> safeBlocks = new java.util.HashSet<>();
    /** Independent direct observations used to verify teammate reports (hearsay alone never confirms a report). */
    private final Set<String> confirmedHarmfulBlocks = new java.util.HashSet<>();
    private final Set<String> confirmedSafeBlocks = new java.util.HashSet<>();

    public void confirmHarmfulEvidence(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return;
        }
        confirmedSafeBlocks.remove(blockId);
        if (confirmedHarmfulBlocks.size() < 512) {
            confirmedHarmfulBlocks.add(blockId);
        }
        learnHarmful(blockId);
    }

    public void confirmSafeEvidence(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return;
        }
        confirmedHarmfulBlocks.remove(blockId);
        if (confirmedSafeBlocks.size() < 512) {
            confirmedSafeBlocks.add(blockId);
        }
        unlearnHarmful(blockId);
    }

    public boolean hasConfirmedHarmfulEvidence(String blockId) {
        return confirmedHarmfulBlocks.contains(blockId);
    }

    public boolean hasConfirmedSafeEvidence(String blockId) {
        return confirmedSafeBlocks.contains(blockId);
    }

    public void unlearnHarmful(String blockId) {
        harmfulBlocks.removeInt(blockId);
        safeBlocks.add(blockId);
    }

    public boolean provenSafe(String blockId) {
        return safeBlocks.contains(blockId);
    }

    // ---------------------------------------------------------------- effects on ourselves (learned from experience)

    /** Effect id (or "cloud:<colour>") -> learned value: how much better (+) or worse (-) things went while it was on us. */
    private final it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap<String> effectValues = new it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap<>();
    private final it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<String> effectSamples = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<>();

    public void learnEffect(String id, float reward) {
        int n = effectSamples.getInt(id) + 1;
        effectSamples.put(id, n);
        float v = effectValues.getFloat(id);
        effectValues.put(id, v + (reward - v) * Math.max(0.1f, 1f / n));
    }

    public float effectValue(String id) {
        return effectValues.getFloat(id);
    }

    public int effectSamples(String id) {
        return effectSamples.getInt(id);
    }

    /** Learned well enough (a few samples) to act on. */
    public boolean knowsEffect(String id) {
        return effectSamples.getInt(id) >= 3;
    }

    public Set<String> harmfulBlocks() {
        return Collections.unmodifiableSet(harmfulBlocks.keySet());
    }

    // ---------------------------------------------------------------- items, blocks and what they are good for

    /** Item key (item id, potions with "#potion") -> 1 = obtained itself, 2 = only heard about it. */
    private final it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<String> knownItems = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<>();
    /** Item key -> facts learned about it (";" separated tokens, see Discovery). */
    private final Map<String, String> itemFacts = new HashMap<>();
    /** Block ids the clone has examined (mined / obtained / been told about). */
    private final Set<String> knownBlocks = new java.util.HashSet<>();
    /** Things learned once and for all: "visited:<dimension>", "noboat:<entity type>"... */
    private final Set<String> flags = new java.util.HashSet<>();

    // ---------------------------------------------------------------- place- and action-specific episodic reward memory

    private static final long EXPERIENCE_TTL = 48_000L;
    private static final int EXPERIENCE_LIMIT = 256;

    private static final class SiteExperience {
        final String dimension;
        final int chunkX;
        final int chunkZ;
        final int action;
        float value;
        int samples;
        long lastTick;
        String cause = "";

        SiteExperience(String dimension, int chunkX, int chunkZ, int action) {
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.action = action;
        }
    }

    /** LRU-bounded online reward estimates keyed by dimension, chunk and strategy action. Action -1 is a general hazard. */
    private final LinkedHashMap<String, SiteExperience> siteExperiences = new LinkedHashMap<>(32, 0.75f, true);

    public boolean hasFlag(String f) {
        return flags.contains(f);
    }

    public void setFlag(String f) {
        flags.add(f);
    }

    /** Add one bounded-reward outcome to the persistent memory for this place and action. */
    public void learnSiteExperience(String dimension, int chunkX, int chunkZ, int action, float reward, String cause, long now) {
        if (dimension == null || dimension.isBlank() || !Float.isFinite(reward)) {
            return;
        }
        String key = experienceKey(dimension, chunkX, chunkZ, action);
        SiteExperience experience = siteExperiences.get(key);
        if (experience == null) {
            experience = new SiteExperience(dimension, chunkX, chunkZ, action);
            siteExperiences.put(key, experience);
        }
        long age = Math.max(0, now - experience.lastTick);
        float sample = Math.max(-1f, Math.min(1f, reward / 5f));
        if (experience.samples == 0 || age >= EXPERIENCE_TTL) {
            experience.value = sample;
            experience.samples = 1;
        } else {
            int n = Math.min(8, experience.samples + 1);
            experience.value += (sample - experience.value) / n;
            experience.samples = n;
        }
        experience.lastTick = now;
        if (cause != null && !cause.isBlank()) {
            experience.cause = cause.length() > 96 ? cause.substring(0, 96) : cause;
        }
        trimSiteExperiences();
    }

    private static String experienceKey(String dimension, int chunkX, int chunkZ, int action) {
        return dimension + "|" + chunkX + "|" + chunkZ + "|" + action;
    }

    /** Decayed estimate for repeating {@code action} here; falls back to general hazards at this chunk. */
    public float siteExperienceValue(String dimension, int chunkX, int chunkZ, int action, long now) {
        SiteExperience experience = siteExperiences.get(experienceKey(dimension, chunkX, chunkZ, action));
        if (experience == null) {
            experience = siteExperiences.get(experienceKey(dimension, chunkX, chunkZ, -1));
        }
        if (experience == null) {
            return 0f;
        }
        long age = Math.max(0, now - experience.lastTick);
        if (age >= EXPERIENCE_TTL) {
            return 0f;
        }
        return experience.value * (1f - age / (float) EXPERIENCE_TTL);
    }

    public int siteExperienceSamples(String dimension, int chunkX, int chunkZ, int action, long now) {
        SiteExperience experience = siteExperiences.get(experienceKey(dimension, chunkX, chunkZ, action));
        if (experience == null) {
            experience = siteExperiences.get(experienceKey(dimension, chunkX, chunkZ, -1));
        }
        if (experience == null || Math.max(0, now - experience.lastTick) >= EXPERIENCE_TTL) {
            return 0;
        }
        return experience.samples;
    }

    /** 0 = unknown, 1 = mostly positive, 2 = mostly negative, 3 = mixed outcomes at this chunk. */
    public int siteExperienceBand(String dimension, int chunkX, int chunkZ, long now) {
        boolean positive = false;
        boolean negative = false;
        for (SiteExperience experience : siteExperiences.values()) {
            if (!experience.dimension.equals(dimension) || experience.chunkX != chunkX || experience.chunkZ != chunkZ) {
                continue;
            }
            long age = Math.max(0, now - experience.lastTick);
            if (age >= EXPERIENCE_TTL) {
                continue;
            }
            float value = experience.value * (1f - age / (float) EXPERIENCE_TTL);
            positive |= value > 0.15f;
            negative |= value < -0.15f;
        }
        return positive && negative ? 3 : negative ? 2 : positive ? 1 : 0;
    }

    public int siteExperienceCount() {
        return siteExperiences.size();
    }

    private void trimSiteExperiences() {
        while (siteExperiences.size() > EXPERIENCE_LIMIT) {
            String eldest = siteExperiences.keySet().iterator().next();
            siteExperiences.remove(eldest);
        }
    }

    // ---------------------------------------------------------------- social reputation / trust memory

    private static final long SOCIAL_TTL = 96_000L;
    private static final int SOCIAL_LIMIT = 128;

    private static final class SocialExperience {
        final UUID partner;
        float trust;
        int samples;
        int positive;
        int negative;
        long lastTick;
        String cause = "";

        SocialExperience(UUID partner) {
            this.partner = partner;
        }
    }

    /** Bounded persistent estimate of whether a partner reciprocates aid and shared resources. */
    private final LinkedHashMap<UUID, SocialExperience> socialExperiences = new LinkedHashMap<>(16, 0.75f, true);

    public void learnSocial(UUID partner, float outcome, String cause, long now) {
        if (partner == null || !Float.isFinite(outcome)) {
            return;
        }
        float sample = Math.max(-1f, Math.min(1f, outcome));
        SocialExperience experience = socialExperiences.computeIfAbsent(partner, SocialExperience::new);
        long age = Math.max(0, now - experience.lastTick);
        if (experience.samples == 0 || age >= SOCIAL_TTL) {
            experience.trust = sample;
            experience.samples = 1;
        } else {
            int n = Math.min(16, experience.samples + 1);
            experience.trust += (sample - experience.trust) / n;
            experience.samples = n;
        }
        if (sample >= 0.1f) {
            if (experience.positive < Integer.MAX_VALUE) {
                experience.positive++;
            }
        } else if (sample <= -0.1f && experience.negative < Integer.MAX_VALUE) {
            experience.negative++;
        }
        experience.lastTick = now;
        if (cause != null && !cause.isBlank()) {
            experience.cause = cause.length() > 64 ? cause.substring(0, 64) : cause;
        }
        trimSocialExperiences();
    }

    /** Decayed trust in [-1,1]; expired relationships become neutral rather than permanent grudges. */
    public float socialTrust(UUID partner, long now) {
        SocialExperience experience = socialExperiences.get(partner);
        if (experience == null) {
            return 0f;
        }
        long age = Math.max(0, now - experience.lastTick);
        if (age >= SOCIAL_TTL) {
            socialExperiences.remove(partner);
            return 0f;
        }
        return experience.trust * (1f - age / (float) SOCIAL_TTL);
    }

    public int socialSamples(UUID partner) {
        SocialExperience experience = socialExperiences.get(partner);
        return experience == null ? 0 : experience.samples;
    }

    public int socialExperienceCount() {
        return socialExperiences.size();
    }

    private void trimSocialExperiences() {
        while (socialExperiences.size() > SOCIAL_LIMIT) {
            socialExperiences.remove(socialExperiences.keySet().iterator().next());
        }
    }

    private static int saturatingAdd(int a, int b) {
        return (int) Math.min(Integer.MAX_VALUE, (long) a + b);
    }

    // ---------------------------------------------------------------- team contribution / withdrawal ledger

    private static final long LEDGER_TTL = 192_000L;
    private static final int LEDGER_LIMIT = 128;

    private static final class PartnerLedger {
        final UUID partner;
        float contributed;
        float withdrawn;
        int contributionSamples;
        int withdrawalSamples;
        long lastTick;
        String lastCause = "";

        PartnerLedger(UUID partner) {
            this.partner = partner;
        }
    }

    /** What this clone believes each partner has put into, or taken from, their shared relationship/team. */
    private final LinkedHashMap<UUID, PartnerLedger> partnerLedger = new LinkedHashMap<>(16, 0.75f, true);

    public void recordContribution(UUID partner, float value, String cause, long now) {
        recordLedgerEvent(partner, value, cause, now, true);
    }

    public void recordWithdrawal(UUID partner, float value, String cause, long now) {
        recordLedgerEvent(partner, value, cause, now, false);
    }

    private void recordLedgerEvent(UUID partner, float value, String cause, long now, boolean contribution) {
        if (partner == null || !Float.isFinite(value) || value <= 0f) {
            return;
        }
        PartnerLedger ledger = partnerLedger.computeIfAbsent(partner, PartnerLedger::new);
        long age = Math.max(0, now - ledger.lastTick);
        if ((long) ledger.contributionSamples + ledger.withdrawalSamples > 0 && age >= LEDGER_TTL) {
            ledger.contributed = 0f;
            ledger.withdrawn = 0f;
            ledger.contributionSamples = 0;
            ledger.withdrawalSamples = 0;
        }
        float amount = Math.min(100f, value);
        if (contribution) {
            ledger.contributed = Math.min(1_000_000f, ledger.contributed + amount);
            ledger.contributionSamples = saturatingAdd(ledger.contributionSamples, 1);
        } else {
            ledger.withdrawn = Math.min(1_000_000f, ledger.withdrawn + amount);
            ledger.withdrawalSamples = saturatingAdd(ledger.withdrawalSamples, 1);
        }
        ledger.lastTick = now;
        if (cause != null && !cause.isBlank()) {
            ledger.lastCause = cause.length() > 64 ? cause.substring(0, 64) : cause;
        }
        trimPartnerLedger();
    }

    public float partnerContribution(UUID partner, long now) {
        PartnerLedger ledger = partnerLedger.get(partner);
        return ledger == null ? 0f : decayLedger(ledger.contributed, ledger.lastTick, now);
    }

    public float partnerWithdrawal(UUID partner, long now) {
        PartnerLedger ledger = partnerLedger.get(partner);
        return ledger == null ? 0f : decayLedger(ledger.withdrawn, ledger.lastTick, now);
    }

    /** Positive means this partner has contributed more than they have drawn from this clone/team. */
    public float partnerBalance(UUID partner, long now) {
        return partnerContribution(partner, now) - partnerWithdrawal(partner, now);
    }

    public int partnerLedgerCount() {
        return partnerLedger.size();
    }

    /** Stable coarse value for machine-readable item transfer reports. */
    public static float resourceValue(String itemId, int count) {
        if (itemId == null || itemId.isBlank() || count <= 0) {
            return 0f;
        }
        String id = itemId.toLowerCase(Locale.ROOT);
        float unit = id.contains("netherite") ? 14f
                : id.contains("ancient_debris") ? 12f
                : id.contains("diamond") ? 8f
                : id.contains("emerald") ? 4f
                : id.contains("iron") ? 2f
                : id.contains("gold") ? 1.5f
                : id.contains("food") || id.contains("bread") || id.contains("cooked") || id.contains("stew") ? 0.6f
                : id.contains("log") || id.contains("planks") || id.contains("wood") ? 0.2f
                : 0.25f;
        return Math.min(100f, unit * Math.min(64, count));
    }

    private static float decayLedger(float value, long lastTick, long now) {
        long age = Math.max(0, now - lastTick);
        return age >= LEDGER_TTL ? 0f : value * (1f - age / (float) LEDGER_TTL);
    }

    private void trimPartnerLedger() {
        while (partnerLedger.size() > LEDGER_LIMIT) {
            partnerLedger.remove(partnerLedger.keySet().iterator().next());
        }
    }

    // ---------------------------------------------------------------- partner report reliability

    private static final long REPORT_TTL = 96_000L;
    private static final int REPORT_PROFILE_LIMIT = 128;
    private static final int STRUCTURE_REPORT_PROFILE_LIMIT = 512;
    private static final int STRUCTURE_REPORT_DISTANCE_BANDS = 4;
    private static final int REPORT_CLAIM_LIMIT = 256;
    private static final float DEFAULT_REPORT_RELIABILITY = 0.67f;

    private static final class ReportExperience {
        final UUID partner;
        final String type;
        int correct;
        int incorrect;
        long lastTick;

        ReportExperience(UUID partner, String type) {
            this.partner = partner;
            this.type = type;
        }
    }

    /** Reliability conditioned on the exact structure kind and how far its reporter was from the claim. */
    private static final class StructureReportExperience {
        final UUID reporter;
        final String subject;
        final int distanceBand;
        int correct;
        int incorrect;
        long lastTick;

        StructureReportExperience(UUID reporter, String subject, int distanceBand) {
            this.reporter = reporter;
            this.subject = subject;
            this.distanceBand = distanceBand;
        }
    }

    private static final class PendingReport {
        final UUID reporter;
        final String type;
        final String subject;
        final String dimension;
        final int x;
        final int y;
        final int z;
        int distanceBand;
        long tick;

        PendingReport(UUID reporter, String type, String subject, String dimension, int x, int y, int z, int distanceBand, long tick) {
            this.reporter = reporter;
            this.type = type;
            this.subject = subject;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.distanceBand = Math.max(0, Math.min(STRUCTURE_REPORT_DISTANCE_BANDS - 1, distanceBand));
            this.tick = tick;
        }
    }

    /** Snapshot exposed to exploration code; the mutable pending-claim ledger remains encapsulated. */
    public record PendingReportView(UUID reporter, String type, String subject, String dimension,
                                    int x, int y, int z, long tick, int distanceBand, float reliability) {
    }

    private final LinkedHashMap<String, ReportExperience> reportExperiences = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<String, StructureReportExperience> structureReportExperiences = new LinkedHashMap<>(32, 0.75f, true);
    private final LinkedHashMap<String, PendingReport> pendingReports = new LinkedHashMap<>(32, 0.75f, true);

    public void recordReportClaim(UUID reporter, String type, String subject, String dimension, int x, int y, int z, long now) {
        recordReportClaim(reporter, type, subject, dimension, x, y, z, now, 0.0);
    }

    /** For STRUCT claims, distance is how far the reporter was from the reported location when speaking. */
    public void recordReportClaim(UUID reporter, String type, String subject, String dimension, int x, int y, int z,
                                  long now, double reporterDistance) {
        if (reporter == null || type == null || type.isBlank() || subject == null || subject.isBlank()) {
            return;
        }
        expirePendingReports(now);
        String safeType = type.length() > 32 ? type.substring(0, 32) : type;
        String safeSubject = safeReportSubject(subject);
        String safeDimension = dimension == null ? "" : dimension;
        int distanceBand = "structure".equals(safeType) ? structureReportDistanceBand(reporterDistance) : 0;
        String key = reportClaimKey(reporter, safeType, safeSubject, safeDimension, x, y, z);
        PendingReport pending = pendingReports.get(key);
        if (pending == null) {
            pending = new PendingReport(reporter, safeType, safeSubject, safeDimension, x, y, z, distanceBand, now);
            pendingReports.put(key, pending);
        } else {
            pending.tick = now;
            pending.distanceBand = distanceBand;
        }
        while (pendingReports.size() > REPORT_CLAIM_LIMIT) {
            pendingReports.remove(pendingReports.keySet().iterator().next());
        }
    }

    /** Band the source's claim distance so report accuracy can be calibrated without storing raw coordinates. */
    public static int structureReportDistanceBand(double distance) {
        if (!Double.isFinite(distance) || distance < 0.0) {
            return 0;
        }
        if (distance < 16.0) {
            return 0;
        }
        if (distance < 64.0) {
            return 1;
        }
        if (distance < 256.0) {
            return 2;
        }
        return 3;
    }

    private void expirePendingReports(long now) {
        pendingReports.entrySet().removeIf(e -> now - e.getValue().tick >= REPORT_TTL);
    }

    public void verifyReportClaim(UUID reporter, String type, String subject, boolean accurate, long now) {
        expirePendingReports(now);
        if (reporter == null || type == null || subject == null) {
            return;
        }
        Iterator<Map.Entry<String, PendingReport>> it = pendingReports.entrySet().iterator();
        while (it.hasNext()) {
            PendingReport report = it.next().getValue();
            if (report.reporter.equals(reporter) && report.type.equals(type) && report.subject.equals(subject)) {
                learnReportAccuracy(report, accurate, now);
                it.remove();
            }
        }
    }

    public void verifyReportClaims(String type, String subject, boolean accurate, long now) {
        expirePendingReports(now);
        if (type == null || subject == null) {
            return;
        }
        Iterator<Map.Entry<String, PendingReport>> it = pendingReports.entrySet().iterator();
        while (it.hasNext()) {
            PendingReport report = it.next().getValue();
            if (report.type.equals(type) && report.subject.equals(subject)) {
                learnReportAccuracy(report, accurate, now);
                it.remove();
            }
        }
    }

    public void verifyReportClaimsNear(String type, String dimension, String subject, int x, int y, int z, int radius,
                                       boolean accurate, long now) {
        expirePendingReports(now);
        if (type == null || dimension == null || subject == null) {
            return;
        }
        long radiusSquared = (long) radius * radius;
        Iterator<Map.Entry<String, PendingReport>> it = pendingReports.entrySet().iterator();
        while (it.hasNext()) {
            PendingReport report = it.next().getValue();
            long dx = (long) report.x - x;
            long dy = (long) report.y - y;
            long dz = (long) report.z - z;
            if (report.type.equals(type) && report.subject.equals(subject) && report.dimension.equals(dimension)
                    && dx * dx + dy * dy + dz * dz <= radiusSquared && Math.abs(dy) <= 32) {
                learnReportAccuracy(report, accurate, now);
                it.remove();
            }
        }
    }

    public float reportReliability(UUID reporter, String type, long now) {
        if (reporter == null || type == null) {
            return DEFAULT_REPORT_RELIABILITY;
        }
        ReportExperience experience = reportExperiences.get(reportKey(reporter, type));
        if (experience == null) {
            return DEFAULT_REPORT_RELIABILITY;
        }
        long age = Math.max(0, now - experience.lastTick);
        float evidence = (2f + experience.correct) / (3f + experience.correct + experience.incorrect);
        float freshness = age >= REPORT_TTL ? 0f : 1f - age / (float) REPORT_TTL;
        return DEFAULT_REPORT_RELIABILITY + (evidence - DEFAULT_REPORT_RELIABILITY) * freshness;
    }

    /**
     * Structure-specific calibration shrinks sparse subtype/range evidence toward the reporter's general STRUCT score.
     * Distance is binned from the reporter to the claimed structure when the report was made.
     */
    public float structureReportReliability(UUID reporter, String subject, int distanceBand, long now) {
        float global = reportReliability(reporter, "structure", now);
        if (reporter == null || subject == null || subject.isBlank()) {
            return global;
        }
        int band = Math.max(0, Math.min(STRUCTURE_REPORT_DISTANCE_BANDS - 1, distanceBand));
        StructureReportExperience experience = structureReportExperiences.get(structureReportKey(reporter, safeReportSubject(subject), band));
        if (experience == null) {
            return global;
        }
        long age = Math.max(0, now - experience.lastTick);
        if (age >= REPORT_TTL) {
            return global;
        }
        long samples = (long) experience.correct + experience.incorrect;
        if (samples == 0) {
            return global;
        }
        float evidence = (2f + experience.correct) / (3f + samples);
        float support = samples / (samples + 4f);
        float freshness = 1f - age / (float) REPORT_TTL;
        return Math.max(0f, Math.min(1f, global + (evidence - global) * support * freshness));
    }

    public int reportSamples(UUID reporter, String type) {
        ReportExperience experience = reportExperiences.get(reportKey(reporter, type));
        return experience == null ? 0 : experience.correct + experience.incorrect;
    }

    public int pendingReportCount() {
        return pendingReports.size();
    }

    private void learnReportAccuracy(UUID reporter, String type, boolean accurate, long now) {
        String key = reportKey(reporter, type);
        ReportExperience experience = reportExperiences.computeIfAbsent(key, k -> new ReportExperience(reporter, type));
        if ((long) experience.correct + experience.incorrect > 0 && now - experience.lastTick >= REPORT_TTL) {
            experience.correct = 0;
            experience.incorrect = 0;
        }
        if (accurate) {
            experience.correct = saturatingAdd(experience.correct, 1);
        } else {
            experience.incorrect = saturatingAdd(experience.incorrect, 1);
        }
        long total = (long) experience.correct + experience.incorrect;
        if (total > 256) {
            experience.correct /= 2;
            experience.incorrect /= 2;
        }
        experience.lastTick = now;
        while (reportExperiences.size() > REPORT_PROFILE_LIMIT) {
            reportExperiences.remove(reportExperiences.keySet().iterator().next());
        }
    }

    public int structureReportSamples(UUID reporter, String subject, int distanceBand) {
        if (reporter == null || subject == null) {
            return 0;
        }
        StructureReportExperience experience = structureReportExperiences.get(structureReportKey(reporter, safeReportSubject(subject),
                Math.max(0, Math.min(STRUCTURE_REPORT_DISTANCE_BANDS - 1, distanceBand))));
        return experience == null ? 0 : experience.correct + experience.incorrect;
    }

    private void learnReportAccuracy(PendingReport report, boolean accurate, long now) {
        learnReportAccuracy(report.reporter, report.type, accurate, now);
        if (!"structure".equals(report.type)) {
            return;
        }
        int band = Math.max(0, Math.min(STRUCTURE_REPORT_DISTANCE_BANDS - 1, report.distanceBand));
        String key = structureReportKey(report.reporter, report.subject, band);
        StructureReportExperience experience = structureReportExperiences.computeIfAbsent(key,
                k -> new StructureReportExperience(report.reporter, report.subject, band));
        if ((long) experience.correct + experience.incorrect > 0 && now - experience.lastTick >= REPORT_TTL) {
            experience.correct = 0;
            experience.incorrect = 0;
        }
        if (accurate) {
            experience.correct = saturatingAdd(experience.correct, 1);
        } else {
            experience.incorrect = saturatingAdd(experience.incorrect, 1);
        }
        long total = (long) experience.correct + experience.incorrect;
        if (total > 256) {
            experience.correct /= 2;
            experience.incorrect /= 2;
        }
        experience.lastTick = now;
        trimStructureReportExperiences();
    }

    private void trimStructureReportExperiences() {
        while (structureReportExperiences.size() > STRUCTURE_REPORT_PROFILE_LIMIT) {
            structureReportExperiences.remove(structureReportExperiences.keySet().iterator().next());
        }
    }

    private static String reportKey(UUID reporter, String type) {
        return reporter + "|" + type;
    }

    private static String safeReportSubject(String subject) {
        return subject.length() > 128 ? subject.substring(0, 128) : subject;
    }

    private static String structureReportKey(UUID reporter, String subject, int distanceBand) {
        return reporter + "|" + safeReportSubject(subject) + "|" + distanceBand;
    }

    private static String reportClaimKey(UUID reporter, String type, String subject, String dimension, int x, int y, int z) {
        return reporter + "|" + type + "|" + subject + "|" + dimension + "|" + x + "|" + y + "|" + z;
    }

    // ---------------------------------------------------------------- risk-aware route memory

    private static final long ROUTE_TTL = 96_000L;
    private static final int ROUTE_LIMIT = 512;

    private static final class RouteExperience {
        final String dimension;
        final int chunkX;
        final int chunkZ;
        final int context;
        float risk;
        int samples;
        long lastTick;

        RouteExperience(String dimension, int chunkX, int chunkZ, int context) {
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.context = context;
        }
    }

    /** Per-chunk hazard estimates conditioned on night, darkness, water, hostiles and rough elevation. */
    private final LinkedHashMap<String, RouteExperience> routeExperiences = new LinkedHashMap<>(32, 0.75f, true);

    public void learnRouteRisk(String dimension, int chunkX, int chunkZ, int context, float risk, long now) {
        if (dimension == null || dimension.isBlank() || !Float.isFinite(risk)) {
            return;
        }
        int safeContext = context & ROUTE_CONTEXT_MASK;
        String key = routeKey(dimension, chunkX, chunkZ, safeContext);
        RouteExperience experience = routeExperiences.computeIfAbsent(key, k -> new RouteExperience(dimension, chunkX, chunkZ, safeContext));
        long age = Math.max(0, now - experience.lastTick);
        float sample = Math.max(0f, Math.min(1f, risk));
        if (experience.samples == 0 || age >= ROUTE_TTL) {
            experience.risk = sample;
            experience.samples = 1;
        } else {
            int n = Math.min(16, experience.samples + 1);
            experience.risk += (sample - experience.risk) / n;
            experience.samples = n;
        }
        experience.lastTick = now;
        trimRouteExperiences();
    }

    /** Context-similar risk with linear time decay. Unknown chunks have no learned penalty. */
    public float routeRisk(String dimension, int chunkX, int chunkZ, int context, long now) {
        int safeContext = context & ROUTE_CONTEXT_MASK;
        RouteExperience exact = routeExperiences.get(routeKey(dimension, chunkX, chunkZ, safeContext));
        if (exact != null) {
            long age = Math.max(0, now - exact.lastTick);
            if (age < ROUTE_TTL) {
                return exact.risk * (1f - age / (float) ROUTE_TTL);
            }
        }
        double weightedRisk = 0;
        double totalWeight = 0;
        for (RouteExperience experience : routeExperiences.values()) {
            if (!experience.dimension.equals(dimension) || experience.chunkX != chunkX || experience.chunkZ != chunkZ) {
                continue;
            }
            long age = Math.max(0, now - experience.lastTick);
            if (age >= ROUTE_TTL) {
                continue;
            }
            int differentConditions = Integer.bitCount(safeContext ^ experience.context);
            double similarity = 1.0 / (1 + differentConditions);
            double decay = 1.0 - age / (double) ROUTE_TTL;
            double weight = similarity * decay * Math.min(16, Math.max(1, experience.samples));
            weightedRisk += experience.risk * weight;
            totalWeight += weight;
        }
        return totalWeight == 0 ? 0f : (float) (weightedRisk / totalWeight);
    }

    public int routeExperienceCount() {
        return routeExperiences.size();
    }

    private static String routeKey(String dimension, int chunkX, int chunkZ, int context) {
        return dimension + "|" + chunkX + "|" + chunkZ + "|" + context;
    }

    private void trimRouteExperiences() {
        while (routeExperiences.size() > ROUTE_LIMIT) {
            routeExperiences.remove(routeExperiences.keySet().iterator().next());
        }
    }

    // ---------------------------------------------------------------- how to use an item (learned by trying)

    /** Ways of using an item on an enemy: swing it, right click once, hold right click briefly / long and let go. */
    public static final int USE_MODES = 4;
    private final Map<String, float[]> useQ = new HashMap<>();
    private final Map<String, int[]> useN = new HashMap<>();

    public float itemUseValue(String item, int mode) {
        float[] q = useQ.get(item);
        return q == null ? 0f : q[mode];
    }

    public int itemUseTries(String item, int mode) {
        int[] n = useN.get(item);
        return n == null ? 0 : n[mode];
    }

    public int itemUseTries(String item) {
        int[] n = useN.get(item);
        return n == null ? 0 : java.util.Arrays.stream(n).sum();
    }

    /** Untried ways first, then mostly the best one so far (one time in six another, to keep learning). */
    public int chooseItemUse(String item, net.minecraft.util.RandomSource rnd) {
        int[] n = useN.computeIfAbsent(item, k -> new int[USE_MODES]);
        List<Integer> untried = new java.util.ArrayList<>();
        for (int m = 0; m < USE_MODES; m++) {
            if (n[m] == 0) {
                untried.add(m);
            }
        }
        if (!untried.isEmpty()) {
            return untried.get(rnd.nextInt(untried.size()));
        }
        if (rnd.nextInt(6) == 0) {
            return rnd.nextInt(USE_MODES);
        }
        return bestItemUse(item);
    }

    public int bestItemUse(String item) {
        float[] q = useQ.get(item);
        if (q == null) {
            return -1;
        }
        int best = 0;
        for (int m = 1; m < USE_MODES; m++) {
            if (q[m] > q[best]) {
                best = m;
            }
        }
        return best;
    }

    public void learnItemUse(String item, int mode, float reward) {
        float[] q = useQ.computeIfAbsent(item, k -> new float[USE_MODES]);
        int[] n = useN.computeIfAbsent(item, k -> new int[USE_MODES]);
        n[mode]++;
        q[mode] += (reward - q[mode]) / Math.min(n[mode], 12);
    }

    public Set<String> itemUseKeys() {
        return Collections.unmodifiableSet(useQ.keySet());
    }

    // ---------------------------------------------------------------- advancements and what unlocks them

    private final Map<String, String> advancementFacts = new HashMap<>();

    /** What we understood unlocks an advancement ("obtain minecraft:lava_bucket", "kill minecraft:zombie"...). */
    public String advancementFact(String id) {
        return advancementFacts.get(id);
    }

    public void learnAdvancement(String id, String condition) {
        advancementFacts.put(id, condition);
    }

    public Map<String, String> advancementFacts() {
        return Collections.unmodifiableMap(advancementFacts);
    }

    /** Parkour: value of jumping a gap of 1..4 blocks by walking up to it (0) or with a sprinting run-up (1). */
    public static final int PARKOUR_GAPS = 5;
    private final float[][] parkQ = new float[PARKOUR_GAPS][2];
    private final int[][] parkN = new int[PARKOUR_GAPS][2];

    public float parkourValue(int gap, int how) {
        return parkQ[Math.min(gap, PARKOUR_GAPS - 1)][how];
    }

    public int parkourTries(int gap, int how) {
        return parkN[Math.min(gap, PARKOUR_GAPS - 1)][how];
    }

    /** Untried first, then mostly the better one (10 % of the time the other, to keep learning). */
    public int chooseParkour(int gap, net.minecraft.util.RandomSource rnd) {
        int g = Math.min(gap, PARKOUR_GAPS - 1);
        for (int a = 0; a < 2; a++) {
            if (parkN[g][a] == 0) {
                return a;
            }
        }
        if (rnd.nextInt(5) == 0 || parkQ[g][1] == parkQ[g][0]) {
            return rnd.nextInt(2); // keep trying both now and then
        }
        return parkQ[g][1] > parkQ[g][0] ? 1 : 0;
    }

    public void learnParkour(int gap, int how, float reward) {
        int g = Math.min(gap, PARKOUR_GAPS - 1);
        parkN[g][how]++;
        parkQ[g][how] += (reward - parkQ[g][how]) / Math.min(parkN[g][how], 10);
    }

    public boolean knowsItem(String key) {
        return knownItems.containsKey(key);
    }

    public boolean obtained(String key) {
        return knownItems.getInt(key) == 1;
    }

    public void learnItem(String key, String facts, boolean obtained) {
        int prev = knownItems.getInt(key);
        knownItems.put(key, obtained || prev == 1 ? 1 : 2);
        if (facts != null && !facts.isEmpty()) {
            itemFacts.put(key, facts);
        }
    }

    public String facts(String key) {
        return itemFacts.getOrDefault(key, "");
    }

    public Set<String> knownItems() {
        return Collections.unmodifiableSet(knownItems.keySet());
    }

    public boolean knowsBlock(String id) {
        return knownBlocks.contains(id);
    }

    public void learnBlock(String id) {
        knownBlocks.add(id);
    }

    public int knownBlockCount() {
        return knownBlocks.size();
    }

    // ---------------------------------------------------------------- look-around speed (a small bandit per situation)

    /** Turning speeds (degrees per tick) a clone can choose from when it stops to look around. */
    public static final float[] LOOK_SPEEDS = {3f, 6f, 12f, 20f, 32f};
    public static final int LOOK_CONTEXTS = 4;
    private final float[][] lookQ = new float[LOOK_CONTEXTS][LOOK_SPEEDS.length];
    private final int[][] lookN = new int[LOOK_CONTEXTS][LOOK_SPEEDS.length];
    public long lookUpdates;

    /** Pick a look-around speed for this situation: mostly the best so far, sometimes another one to try. */
    public int chooseLook(int ctx, double exploration) {
        ctx = Math.floorMod(ctx, LOOK_CONTEXTS);
        if (random.nextDouble() < Math.max(0.15, exploration)) {
            return random.nextInt(LOOK_SPEEDS.length);
        }
        int best = 0;
        double bestV = -Double.MAX_VALUE;
        for (int a = 0; a < LOOK_SPEEDS.length; a++) {
            double v = lookQ[ctx][a] + 1.0 / (1 + lookN[ctx][a]); // untried speeds look attractive
            if (v > bestV) {
                bestV = v;
                best = a;
            }
        }
        return best;
    }

    /** Reward = things newly noticed per second while looking around at that speed. */
    public void learnLook(int ctx, int arm, float reward) {
        ctx = Math.floorMod(ctx, LOOK_CONTEXTS);
        int n = ++lookN[ctx][arm];
        lookQ[ctx][arm] += (reward - lookQ[ctx][arm]) / Math.min(n, 20);
        lookUpdates++;
    }

    public float lookValue(int ctx, int arm) {
        return lookQ[Math.floorMod(ctx, LOOK_CONTEXTS)][arm];
    }

    public int lookVisits(int ctx, int arm) {
        return lookN[Math.floorMod(ctx, LOOK_CONTEXTS)][arm];
    }

    public EnemyKnowledge knowledge(String type) {
        return knowledge.computeIfAbsent(type, EnemyKnowledge::new);
    }

    public EnemyKnowledge knowledgeIfPresent(String type) {
        return knowledge.get(type);
    }

    public Map<String, EnemyKnowledge> allKnowledge() {
        return Collections.unmodifiableMap(knowledge);
    }

    public Set<String> combatTypes() {
        return Collections.unmodifiableSet(combat.keySet());
    }

    public QTable combatTable(String type) {
        return combat.computeIfAbsent(type, t -> new QTable(CombatAction.COUNT));
    }

    public QTable strategyTable() {
        return strategy;
    }

    public QTable longTermGoalTable() {
        return goals;
    }

    public QTable skillInitiationTable() {
        return skillInitiation;
    }

    public QTable skillTerminationTable() {
        return skillTermination;
    }

    public QTable routeChoiceTable() {
        return routeChoices;
    }

    public QTable reportVerificationTable() {
        return reportVerification;
    }

    private QTable table(String name) {
        if (STRATEGY.equals(name)) {
            return strategy;
        }
        if (GOALS.equals(name)) {
            return goals;
        }
        if (SKILL_INITIATION.equals(name)) {
            return skillInitiation;
        }
        if (SKILL_TERMINATION.equals(name)) {
            return skillTermination;
        }
        if (ROUTE_CHOICES.equals(name)) {
            return routeChoices;
        }
        if (REPORT_VERIFICATION.equals(name)) {
            return reportVerification;
        }
        return combatTable(name);
    }

    private QTable.Prior prior(String name) {
        if (STRATEGY.equals(name)) {
            return StrategyState::prior;
        }
        if (GOALS.equals(name)) {
            return GoalState::prior;
        }
        if (SKILL_INITIATION.equals(name)) {
            return SkillState::initiationPrior;
        }
        if (SKILL_TERMINATION.equals(name)) {
            return SkillState::terminationPrior;
        }
        if (ROUTE_CHOICES.equals(name)) {
            return (s, q) -> {
                q[ROUTE_DIRECT] = 0f;
                q[ROUTE_RISK_AWARE] = 0f;
            };
        }
        if (REPORT_VERIFICATION.equals(name)) {
            return (s, q) -> {
                int state = Math.max(0, Math.min(31, s));
                int reliabilityBin = state / 8;
                int distanceBin = state % 8;
                q[REPORT_DEFER] = 0f;
                q[REPORT_VERIFY] = 0.18f + (3 - reliabilityBin) * 0.035f - distanceBin * 0.035f;
            };
        }
        EnemyKnowledge k = knowledge(name);
        return (s, q) -> CombatState.prior(s, k.isExplosive(), k.isRanged(), q);
    }

    public int chooseCombat(String type, int state, int mask) {
        return choose(type, state, mask);
    }

    public int chooseStrategy(int state, int mask) {
        return choose(STRATEGY, state, mask);
    }

    /** The abstract goal policy uses its own persistent Q-table and feasibility mask. */
    public int chooseGoal(int state, int mask) {
        return choose(GOALS, state, mask);
    }

    public int chooseSkillInitiation(int state) {
        return choose(SKILL_INITIATION, state, (1 << SkillState.BOUNDARY_ACTIONS) - 1);
    }

    public int chooseSkillTermination(int state) {
        return choose(SKILL_TERMINATION, state, (1 << SkillState.BOUNDARY_ACTIONS) - 1);
    }

    public int routeChoiceState(int context, int distanceBin) {
        return Math.floorMod(context, 32) * 8 + Math.max(0, Math.min(7, distanceBin));
    }

    public int chooseRouteChoice(int context, int distanceBin) {
        return choose(ROUTE_CHOICES, routeChoiceState(context, distanceBin), 3);
    }

    /**
     * Low-weight model-based replay from two candidate actions. It is deliberately capped below ordinary experience
     * and stored in the same replay buffer, so later observed outcomes can correct a bad counterfactual estimate.
     */
    public void learnCounterfactual(String tableName, int state, int action, float reward, int nextState, boolean terminal,
                                    float gamma, int nextMask, float confidence) {
        if (action < 0 || !Float.isFinite(reward) || !Float.isFinite(confidence)) {
            return;
        }
        QTable selected = table(tableName);
        if (action >= selected.actions()) {
            return;
        }
        float weight = Math.max(0.01f, Math.min(0.2f, confidence));
        float boundedReward = Math.max(-20f, Math.min(20f, reward));
        apply(tableName, state, action, boundedReward, nextState, terminal, gamma, nextMask, weight, true);
        counterfactualUpdates++;
        replay.add(tableName, state, action, boundedReward, nextState, terminal, gamma, nextMask, weight);
    }

    /** Compare direct travel against a risk-aware alternative without pretending either route was physically taken. */
    public void learnRouteCounterfactual(int context, int distanceBin, float directCost, float riskAwareCost, float confidence) {
        int state = routeChoiceState(context, distanceBin);
        float directReward = -Math.max(0f, Math.min(20f, directCost));
        float safeReward = -Math.max(0f, Math.min(20f, riskAwareCost));
        learnCounterfactual(ROUTE_CHOICES, state, ROUTE_DIRECT, directReward, state, true, 0f, 0, confidence);
        learnCounterfactual(ROUTE_CHOICES, state, ROUTE_RISK_AWARE, safeReward, state, true, 0f, 0, confidence);
    }

    public int reportVerificationState(float reliability, double distance) {
        int reliabilityBin = Math.max(0, Math.min(3, (int) (Math.max(0f, Math.min(1f, reliability)) * 4f)));
        int distanceBin = Math.max(0, Math.min(7, (int) Math.max(0.0, distance) / 16));
        return reliabilityBin * 8 + distanceBin;
    }

    public int chooseReportVerification(int state) {
        return choose(REPORT_VERIFICATION, Math.max(0, Math.min(31, state)), 3);
    }

    public void learnReportVerification(int state, int action, float reward) {
        if (action < 0 || action >= 2) {
            return;
        }
        learn(REPORT_VERIFICATION, Math.max(0, Math.min(31, state)), action, reward,
                0, true, 0f, 0, 1f, false);
    }

    /** Unresolved claims that are suitable for spatial follow-up, in nearest-first-friendly snapshots. */
    public List<PendingReportView> pendingReportCandidates(String type, String dimension, long now) {
        expirePendingReports(now);
        ArrayList<PendingReportView> candidates = new ArrayList<>();
        for (PendingReport report : pendingReports.values()) {
            if (report.type.equals(type) && report.dimension.equals(dimension)) {
                float reliability = "structure".equals(report.type)
                        ? structureReportReliability(report.reporter, report.subject, report.distanceBand, now)
                        : reportReliability(report.reporter, report.type, now);
                candidates.add(new PendingReportView(report.reporter, report.type, report.subject, report.dimension,
                        report.x, report.y, report.z, report.tick, report.distanceBand, reliability));
            }
        }
        return List.copyOf(candidates);
    }

    /** Epsilon-greedy with per-state decaying epsilon; exploration prefers actions others were seen doing. */
    private int choose(String tableName, int state, int mask) {
        if (mask == 0) {
            return -1;
        }
        QTable table = table(tableName);
        QTable.Entry e = table.get(state, prior(tableName));
        decisions++;
        double eps = Math.max(0.03, Config.get(Config.EXPLORATION, 0.35) / Math.sqrt(1.0 + e.visits / 8.0));
        e.visits++;
        if (random.nextDouble() < eps) {
            int demoTotal = 0;
            for (int a = 0; a < e.demo.length; a++) {
                if ((mask & (1 << a)) != 0) {
                    demoTotal += e.demo[a];
                }
            }
            if (demoTotal > 0 && random.nextDouble() < 0.6) {
                int pick = random.nextInt(demoTotal);
                for (int a = 0; a < e.demo.length; a++) {
                    if ((mask & (1 << a)) != 0) {
                        pick -= e.demo[a];
                        if (pick < 0) {
                            return a;
                        }
                    }
                }
            }
            int count = Integer.bitCount(mask);
            int pick = random.nextInt(count);
            for (int a = 0; a < e.q.length; a++) {
                if ((mask & (1 << a)) != 0 && pick-- == 0) {
                    return a;
                }
            }
        }
        return QTable.argmax(e, mask, random);
    }

    /** Greedy action without exploration or bookkeeping (used for display). */
    public int greedy(String tableName, int state, int mask) {
        QTable.Entry e = table(tableName).peek(state);
        if (e == null) {
            float[] q = new float[table(tableName).actions()];
            prior(tableName).fill(state, q);
            QTable.Entry tmp = new QTable(q.length).get(state, (s, out) -> System.arraycopy(q, 0, out, 0, q.length));
            return QTable.argmax(tmp, mask, random);
        }
        return QTable.argmax(e, mask, random);
    }

    /** Read a learned value with the same context prior used by action selection. */
    public float estimateValue(String tableName, int state, int action) {
        QTable selected = table(tableName);
        if (action < 0 || action >= selected.actions()) {
            return 0f;
        }
        QTable.Entry entry = selected.peek(state);
        if (entry != null) {
            return entry.q[action];
        }
        float[] q = new float[selected.actions()];
        prior(tableName).fill(state, q);
        return q[action];
    }

    /**
     * One Q-learning step: Q(s,a) += alpha * (r + gamma * max_a' Q(s',a') - Q(s,a)), followed by replay of past transitions.
     *
     * @param gamma    discount for this transition (already raised to the number of elapsed steps for options)
     * @param nextMask actions available in s' (0 = all)
     * @param weight   1 for own experience, lower for imitation
     * @param imitated true if the transition was observed on another agent
     */
    public void learn(String tableName, int s, int a, float r, int s2, boolean terminal, float gamma, int nextMask, float weight, boolean imitated) {
        if (a < 0) {
            return;
        }
        apply(tableName, s, a, r, s2, terminal, gamma, nextMask, weight, true);
        if (imitated) {
            imitationUpdates++;
        } else {
            ownUpdates++;
        }
        replay.add(tableName, s, a, r, s2, terminal, gamma, nextMask, weight);
        int n = Config.get(Config.REPLAY_UPDATES, 4);
        for (int i = 0; i < n; i++) {
            ReplayBuffer.Transition t = replay.sample(random);
            if (t == null) {
                break;
            }
            apply(t.table, t.s, t.a, t.r, t.s2, t.terminal, t.gamma, t.nextMask, t.weight * 0.5f, false);
            replayUpdates++;
        }
    }

    /** Remember that a (successful) demonstrator chose action a in state s; biases exploration. */
    public void recordDemo(String tableName, int s, int a) {
        if (a < 0) {
            return;
        }
        QTable.Entry e = table(tableName).get(s, prior(tableName));
        if (e.demo[a] < Integer.MAX_VALUE) {
            e.demo[a]++;
        }
    }

    private void apply(String tableName, int s, int a, float r, int s2, boolean terminal, float gamma, int nextMask, float weight, boolean count) {
        QTable table = table(tableName);
        QTable.Prior prior = prior(tableName);
        QTable.Entry e = table.get(s, prior);
        if (a >= e.q.length) {
            return;
        }
        float target = r;
        if (!terminal) {
            int mask = nextMask == 0 ? (1 << table.actions()) - 1 : nextMask;
            target += gamma * QTable.max(table.get(s2, prior), mask);
        }
        double base = Config.get(Config.LEARNING_RATE, 0.25);
        float alpha = (float) Math.max(0.02, base / Math.sqrt(1.0 + e.n[a] * 0.05)) * weight;
        e.q[a] += alpha * (target - e.q[a]);
        if (count && e.n[a] < Integer.MAX_VALUE) {
            e.n[a]++;
        }
    }

    // ---------------------------------------------------------------- persistence / linking

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", 6);
        tag.putByteArray("strategy", strategy.toBytes());
        tag.putByteArray("goals", goals.toBytes());
        tag.putByteArray("skillInitiation", skillInitiation.toBytes());
        tag.putByteArray("skillTermination", skillTermination.toBytes());
        tag.putByteArray("routeChoices", routeChoices.toBytes());
        tag.putByteArray("reportVerification", reportVerification.toBytes());
        CompoundTag c = new CompoundTag();
        combat.forEach((k, v) -> c.putByteArray(k, v.toBytes()));
        tag.put("combat", c);
        CompoundTag kn = new CompoundTag();
        knowledge.forEach((k, v) -> kn.put(k, v.save()));
        tag.put("knowledge", kn);
        tag.putByteArray("replay", replay.toBytes());
        tag.putLong("ownUpdates", ownUpdates);
        tag.putLong("imitationUpdates", imitationUpdates);
        tag.putLong("replayUpdates", replayUpdates);
        tag.putLong("counterfactualUpdates", counterfactualUpdates);
        tag.putLong("decisions", decisions);
        tag.putLong("kills", kills);
        tag.putLong("deaths", deaths);
        tag.putInt("members", members);
        CompoundTag hb = new CompoundTag();
        harmfulBlocks.object2IntEntrySet().forEach(e -> hb.putInt(e.getKey(), e.getIntValue()));
        tag.put("harmfulBlocks", hb);
        net.minecraft.nbt.ListTag sb = new net.minecraft.nbt.ListTag();
        safeBlocks.forEach(f -> sb.add(net.minecraft.nbt.StringTag.valueOf(f)));
        tag.put("safeBlocks", sb);
        net.minecraft.nbt.ListTag confirmedHarmful = new net.minecraft.nbt.ListTag();
        confirmedHarmfulBlocks.forEach(f -> confirmedHarmful.add(net.minecraft.nbt.StringTag.valueOf(f)));
        tag.put("confirmedHarmfulBlocks", confirmedHarmful);
        net.minecraft.nbt.ListTag confirmedSafe = new net.minecraft.nbt.ListTag();
        confirmedSafeBlocks.forEach(f -> confirmedSafe.add(net.minecraft.nbt.StringTag.valueOf(f)));
        tag.put("confirmedSafeBlocks", confirmedSafe);
        CompoundTag ev = new CompoundTag();
        effectValues.object2FloatEntrySet().forEach(e -> ev.putFloat(e.getKey(), e.getFloatValue()));
        tag.put("effectValues", ev);
        CompoundTag es = new CompoundTag();
        effectSamples.object2IntEntrySet().forEach(e -> es.putInt(e.getKey(), e.getIntValue()));
        tag.put("effectSamples", es);
        CompoundTag ki = new CompoundTag();
        knownItems.object2IntEntrySet().forEach(e -> ki.putInt(e.getKey(), e.getIntValue()));
        tag.put("knownItems", ki);
        CompoundTag fa = new CompoundTag();
        itemFacts.forEach(fa::putString);
        tag.put("itemFacts", fa);
        net.minecraft.nbt.ListTag kb = new net.minecraft.nbt.ListTag();
        knownBlocks.forEach(b -> kb.add(net.minecraft.nbt.StringTag.valueOf(b)));
        tag.put("knownBlocks", kb);
        net.minecraft.nbt.ListTag fl = new net.minecraft.nbt.ListTag();
        flags.forEach(f -> fl.add(net.minecraft.nbt.StringTag.valueOf(f)));
        tag.put("flags", fl);
        net.minecraft.nbt.ListTag experiences = new net.minecraft.nbt.ListTag();
        for (SiteExperience experience : siteExperiences.values()) {
            CompoundTag e = new CompoundTag();
            e.putString("dimension", experience.dimension);
            e.putInt("chunkX", experience.chunkX);
            e.putInt("chunkZ", experience.chunkZ);
            e.putInt("action", experience.action);
            e.putFloat("value", experience.value);
            e.putInt("samples", experience.samples);
            e.putLong("lastTick", experience.lastTick);
            e.putString("cause", experience.cause);
            experiences.add(e);
        }
        tag.put("siteExperiences", experiences);
        net.minecraft.nbt.ListTag social = new net.minecraft.nbt.ListTag();
        for (SocialExperience experience : socialExperiences.values()) {
            CompoundTag e = new CompoundTag();
            e.putString("partner", experience.partner.toString());
            e.putFloat("trust", experience.trust);
            e.putInt("samples", experience.samples);
            e.putInt("positive", experience.positive);
            e.putInt("negative", experience.negative);
            e.putLong("lastTick", experience.lastTick);
            e.putString("cause", experience.cause);
            social.add(e);
        }
        tag.put("socialExperiences", social);
        net.minecraft.nbt.ListTag routes = new net.minecraft.nbt.ListTag();
        for (RouteExperience experience : routeExperiences.values()) {
            CompoundTag e = new CompoundTag();
            e.putString("dimension", experience.dimension);
            e.putInt("chunkX", experience.chunkX);
            e.putInt("chunkZ", experience.chunkZ);
            e.putInt("context", experience.context);
            e.putFloat("risk", experience.risk);
            e.putInt("samples", experience.samples);
            e.putLong("lastTick", experience.lastTick);
            routes.add(e);
        }
        tag.put("routeExperiences", routes);
        net.minecraft.nbt.ListTag ledger = new net.minecraft.nbt.ListTag();
        for (PartnerLedger partner : partnerLedger.values()) {
            CompoundTag e = new CompoundTag();
            e.putString("partner", partner.partner.toString());
            e.putFloat("contributed", partner.contributed);
            e.putFloat("withdrawn", partner.withdrawn);
            e.putInt("contributionSamples", partner.contributionSamples);
            e.putInt("withdrawalSamples", partner.withdrawalSamples);
            e.putLong("lastTick", partner.lastTick);
            e.putString("cause", partner.lastCause);
            ledger.add(e);
        }
        tag.put("teamLedger", ledger);
        net.minecraft.nbt.ListTag reportProfile = new net.minecraft.nbt.ListTag();
        for (ReportExperience experience : reportExperiences.values()) {
            CompoundTag e = new CompoundTag();
            e.putString("partner", experience.partner.toString());
            e.putString("type", experience.type);
            e.putInt("correct", experience.correct);
            e.putInt("incorrect", experience.incorrect);
            e.putLong("lastTick", experience.lastTick);
            reportProfile.add(e);
        }
        tag.put("reportExperiences", reportProfile);
        net.minecraft.nbt.ListTag structureReportProfile = new net.minecraft.nbt.ListTag();
        for (StructureReportExperience experience : structureReportExperiences.values()) {
            CompoundTag e = new CompoundTag();
            e.putString("reporter", experience.reporter.toString());
            e.putString("subject", experience.subject);
            e.putInt("distanceBand", experience.distanceBand);
            e.putInt("correct", experience.correct);
            e.putInt("incorrect", experience.incorrect);
            e.putLong("lastTick", experience.lastTick);
            structureReportProfile.add(e);
        }
        tag.put("structureReportExperiences", structureReportProfile);
        net.minecraft.nbt.ListTag reportClaims = new net.minecraft.nbt.ListTag();
        for (PendingReport report : pendingReports.values()) {
            CompoundTag e = new CompoundTag();
            e.putString("reporter", report.reporter.toString());
            e.putString("type", report.type);
            e.putString("subject", report.subject);
            e.putString("dimension", report.dimension);
            e.putInt("x", report.x);
            e.putInt("y", report.y);
            e.putInt("z", report.z);
            e.putInt("distanceBand", report.distanceBand);
            e.putLong("tick", report.tick);
            reportClaims.add(e);
        }
        tag.put("pendingReports", reportClaims);
        int[] pq = new int[PARKOUR_GAPS * 2];
        int[] pn = new int[PARKOUR_GAPS * 2];
        for (int g = 0; g < PARKOUR_GAPS; g++) {
            for (int a = 0; a < 2; a++) {
                pq[g * 2 + a] = Float.floatToIntBits(parkQ[g][a]);
                pn[g * 2 + a] = parkN[g][a];
            }
        }
        tag.putIntArray("parkQ", pq);
        tag.putIntArray("parkN", pn);
        int[] lq = new int[LOOK_CONTEXTS * LOOK_SPEEDS.length];
        int[] ln = new int[lq.length];
        for (int ctx = 0; ctx < LOOK_CONTEXTS; ctx++) {
            for (int a = 0; a < LOOK_SPEEDS.length; a++) {
                lq[ctx * LOOK_SPEEDS.length + a] = Float.floatToIntBits(lookQ[ctx][a]);
                ln[ctx * LOOK_SPEEDS.length + a] = lookN[ctx][a];
            }
        }
        tag.putIntArray("lookQ", lq);
        tag.putIntArray("lookN", ln);
        tag.putLong("lookUpdates", lookUpdates);
        CompoundTag iu = new CompoundTag();
        useQ.forEach((k, q) -> {
            int[] n = useN.getOrDefault(k, new int[USE_MODES]);
            int[] packed = new int[USE_MODES * 2];
            for (int m = 0; m < USE_MODES; m++) {
                packed[m] = Float.floatToIntBits(q[m]);
                packed[USE_MODES + m] = n[m];
            }
            iu.putIntArray(k, packed);
        });
        tag.put("itemUse", iu);
        CompoundTag af = new CompoundTag();
        advancementFacts.forEach(af::putString);
        tag.put("advancements", af);
        return tag;
    }

    public static Brain load(CompoundTag tag) {
        Brain b = new Brain();
        b.strategy.mergeFrom(QTable.fromBytes(tag.getByteArray("strategy"), Option.COUNT));
        b.goals.mergeFrom(QTable.fromBytes(tag.getByteArray("goals"), LongTermGoal.COUNT));
        b.skillInitiation.mergeFrom(QTable.fromBytes(tag.getByteArray("skillInitiation"), SkillState.BOUNDARY_ACTIONS));
        b.skillTermination.mergeFrom(QTable.fromBytes(tag.getByteArray("skillTermination"), SkillState.BOUNDARY_ACTIONS));
        b.routeChoices.mergeFrom(QTable.fromBytes(tag.getByteArray("routeChoices"), 2));
        b.reportVerification.mergeFrom(QTable.fromBytes(tag.getByteArray("reportVerification"), 2));
        CompoundTag c = tag.getCompound("combat");
        for (String k : c.getAllKeys()) {
            b.combatTable(k).mergeFrom(QTable.fromBytes(c.getByteArray(k), CombatAction.COUNT));
        }
        CompoundTag kn = tag.getCompound("knowledge");
        for (String k : kn.getAllKeys()) {
            b.knowledge.put(k, EnemyKnowledge.load(k, kn.getCompound(k)));
        }
        b.replay.readFrom(tag.getByteArray("replay"));
        b.ownUpdates = tag.getLong("ownUpdates");
        b.imitationUpdates = tag.getLong("imitationUpdates");
        b.replayUpdates = tag.getLong("replayUpdates");
        b.counterfactualUpdates = tag.getLong("counterfactualUpdates");
        b.decisions = tag.getLong("decisions");
        b.kills = tag.getLong("kills");
        b.deaths = tag.getLong("deaths");
        b.members = Math.max(1, tag.getInt("members"));
        CompoundTag hb = tag.getCompound("harmfulBlocks");
        for (String k : hb.getAllKeys()) {
            b.harmfulBlocks.put(k, hb.getInt(k));
        }
        net.minecraft.nbt.ListTag sb = tag.getList("safeBlocks", net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < sb.size(); i++) {
            b.safeBlocks.add(sb.getString(i));
        }
        net.minecraft.nbt.ListTag confirmedHarmful = tag.getList("confirmedHarmfulBlocks", net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < confirmedHarmful.size() && i < 512; i++) {
            b.confirmedHarmfulBlocks.add(confirmedHarmful.getString(i));
        }
        net.minecraft.nbt.ListTag confirmedSafe = tag.getList("confirmedSafeBlocks", net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < confirmedSafe.size() && i < 512; i++) {
            b.confirmedSafeBlocks.add(confirmedSafe.getString(i));
        }
        CompoundTag ev = tag.getCompound("effectValues");
        for (String k : ev.getAllKeys()) {
            b.effectValues.put(k, ev.getFloat(k));
        }
        CompoundTag es = tag.getCompound("effectSamples");
        for (String k : es.getAllKeys()) {
            b.effectSamples.put(k, es.getInt(k));
        }
        CompoundTag ki = tag.getCompound("knownItems");
        for (String k : ki.getAllKeys()) {
            b.knownItems.put(k, ki.getInt(k));
        }
        CompoundTag fa = tag.getCompound("itemFacts");
        for (String k : fa.getAllKeys()) {
            b.itemFacts.put(k, fa.getString(k));
        }
        net.minecraft.nbt.ListTag kb = tag.getList("knownBlocks", net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < kb.size(); i++) {
            b.knownBlocks.add(kb.getString(i));
        }
        net.minecraft.nbt.ListTag fl = tag.getList("flags", net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < fl.size(); i++) {
            b.flags.add(fl.getString(i));
        }
        net.minecraft.nbt.ListTag experiences = tag.getList("siteExperiences", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < experiences.size(); i++) {
            CompoundTag e = experiences.getCompound(i);
            String dimension = e.getString("dimension");
            float value = e.getFloat("value");
            if (dimension.isBlank() || !Float.isFinite(value)) {
                continue;
            }
            SiteExperience experience = new SiteExperience(dimension, e.getInt("chunkX"), e.getInt("chunkZ"), e.getInt("action"));
            experience.value = Math.max(-1f, Math.min(1f, value));
            experience.samples = Math.max(1, Math.min(8, e.getInt("samples")));
            experience.lastTick = e.getLong("lastTick");
            experience.cause = e.getString("cause");
            b.siteExperiences.put(experienceKey(dimension, experience.chunkX, experience.chunkZ, experience.action), experience);
        }
        b.trimSiteExperiences();
        net.minecraft.nbt.ListTag social = tag.getList("socialExperiences", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < social.size(); i++) {
            CompoundTag e = social.getCompound(i);
            try {
                UUID partner = UUID.fromString(e.getString("partner"));
                float trust = e.getFloat("trust");
                if (!Float.isFinite(trust)) {
                    continue;
                }
                SocialExperience experience = new SocialExperience(partner);
                experience.trust = Math.max(-1f, Math.min(1f, trust));
                experience.samples = Math.max(1, Math.min(16, e.getInt("samples")));
                experience.positive = Math.max(0, e.getInt("positive"));
                experience.negative = Math.max(0, e.getInt("negative"));
                experience.lastTick = e.getLong("lastTick");
                experience.cause = e.getString("cause");
                b.socialExperiences.put(partner, experience);
            } catch (IllegalArgumentException ignored) {
                // An invalid partner id is not a relationship.
            }
        }
        b.trimSocialExperiences();
        net.minecraft.nbt.ListTag routes = tag.getList("routeExperiences", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < routes.size(); i++) {
            CompoundTag e = routes.getCompound(i);
            String dimension = e.getString("dimension");
            float risk = e.getFloat("risk");
            if (dimension.isBlank() || !Float.isFinite(risk)) {
                continue;
            }
            int x = e.getInt("chunkX");
            int z = e.getInt("chunkZ");
            int context = e.getInt("context") & ROUTE_CONTEXT_MASK;
            RouteExperience experience = new RouteExperience(dimension, x, z, context);
            experience.risk = Math.max(0f, Math.min(1f, risk));
            experience.samples = Math.max(1, Math.min(16, e.getInt("samples")));
            experience.lastTick = e.getLong("lastTick");
            b.routeExperiences.put(routeKey(dimension, x, z, context), experience);
        }
        b.trimRouteExperiences();
        net.minecraft.nbt.ListTag ledger = tag.getList("teamLedger", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < ledger.size(); i++) {
            CompoundTag e = ledger.getCompound(i);
            try {
                UUID partnerId = UUID.fromString(e.getString("partner"));
                PartnerLedger partner = new PartnerLedger(partnerId);
                partner.contributed = Math.max(0f, Math.min(1_000_000f, e.getFloat("contributed")));
                partner.withdrawn = Math.max(0f, Math.min(1_000_000f, e.getFloat("withdrawn")));
                partner.contributionSamples = Math.max(0, e.getInt("contributionSamples"));
                partner.withdrawalSamples = Math.max(0, e.getInt("withdrawalSamples"));
                partner.lastTick = e.getLong("lastTick");
                partner.lastCause = e.getString("cause");
                b.partnerLedger.put(partnerId, partner);
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed UUIDs from old or edited save data.
            }
        }
        b.trimPartnerLedger();
        net.minecraft.nbt.ListTag reportProfile = tag.getList("reportExperiences", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < reportProfile.size(); i++) {
            CompoundTag e = reportProfile.getCompound(i);
            try {
                UUID reporter = UUID.fromString(e.getString("partner"));
                String type = e.getString("type");
                if (type.isBlank()) {
                    continue;
                }
                ReportExperience experience = new ReportExperience(reporter, type);
                experience.correct = Math.max(0, Math.min(256, e.getInt("correct")));
                experience.incorrect = Math.max(0, Math.min(256, e.getInt("incorrect")));
                experience.lastTick = e.getLong("lastTick");
                b.reportExperiences.put(reportKey(reporter, type), experience);
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed partner ids.
            }
        }
        while (b.reportExperiences.size() > REPORT_PROFILE_LIMIT) {
            b.reportExperiences.remove(b.reportExperiences.keySet().iterator().next());
        }
        net.minecraft.nbt.ListTag structureReportProfile = tag.getList("structureReportExperiences", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < structureReportProfile.size() && i < STRUCTURE_REPORT_PROFILE_LIMIT; i++) {
            CompoundTag e = structureReportProfile.getCompound(i);
            try {
                UUID reporter = UUID.fromString(e.getString("reporter"));
                String subject = safeReportSubject(e.getString("subject"));
                if (subject.isBlank()) {
                    continue;
                }
                int band = Math.max(0, Math.min(STRUCTURE_REPORT_DISTANCE_BANDS - 1, e.getInt("distanceBand")));
                StructureReportExperience experience = new StructureReportExperience(reporter, subject, band);
                experience.correct = Math.max(0, Math.min(256, e.getInt("correct")));
                experience.incorrect = Math.max(0, Math.min(256, e.getInt("incorrect")));
                experience.lastTick = e.getLong("lastTick");
                b.structureReportExperiences.put(structureReportKey(reporter, subject, band), experience);
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed reporter ids.
            }
        }
        net.minecraft.nbt.ListTag reportClaims = tag.getList("pendingReports", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < reportClaims.size(); i++) {
            CompoundTag e = reportClaims.getCompound(i);
            try {
                UUID reporter = UUID.fromString(e.getString("reporter"));
                String type = e.getString("type");
                String subject = safeReportSubject(e.getString("subject"));
                if (type.isBlank() || subject.isBlank()) {
                    continue;
                }
                String dimension = e.getString("dimension");
                PendingReport report = new PendingReport(reporter, type, subject, dimension, e.getInt("x"), e.getInt("y"),
                        e.getInt("z"), e.getInt("distanceBand"), e.getLong("tick"));
                b.pendingReports.put(reportClaimKey(reporter, type, subject, dimension, report.x, report.y, report.z), report);
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed reporter ids.
            }
        }
        while (b.pendingReports.size() > REPORT_CLAIM_LIMIT) {
            b.pendingReports.remove(b.pendingReports.keySet().iterator().next());
        }
        int[] pq = tag.getIntArray("parkQ");
        int[] pn = tag.getIntArray("parkN");
        if (pq.length == PARKOUR_GAPS * 2 && pn.length == pq.length) {
            for (int g = 0; g < PARKOUR_GAPS; g++) {
                for (int a = 0; a < 2; a++) {
                    b.parkQ[g][a] = Float.intBitsToFloat(pq[g * 2 + a]);
                    b.parkN[g][a] = pn[g * 2 + a];
                }
            }
        }
        int[] lq = tag.getIntArray("lookQ");
        int[] ln = tag.getIntArray("lookN");
        if (lq.length == LOOK_CONTEXTS * LOOK_SPEEDS.length && ln.length == lq.length) {
            for (int ctx = 0; ctx < LOOK_CONTEXTS; ctx++) {
                for (int a = 0; a < LOOK_SPEEDS.length; a++) {
                    b.lookQ[ctx][a] = Float.intBitsToFloat(lq[ctx * LOOK_SPEEDS.length + a]);
                    b.lookN[ctx][a] = ln[ctx * LOOK_SPEEDS.length + a];
                }
            }
        }
        b.lookUpdates = tag.getLong("lookUpdates");
        CompoundTag iu = tag.getCompound("itemUse");
        for (String k : iu.getAllKeys()) {
            int[] packed = iu.getIntArray(k);
            if (packed.length == USE_MODES * 2) {
                float[] q = new float[USE_MODES];
                int[] n = new int[USE_MODES];
                for (int m = 0; m < USE_MODES; m++) {
                    q[m] = Float.intBitsToFloat(packed[m]);
                    n[m] = packed[USE_MODES + m];
                }
                b.useQ.put(k, q);
                b.useN.put(k, n);
            }
        }
        CompoundTag af = tag.getCompound("advancements");
        for (String k : af.getAllKeys()) {
            b.advancementFacts.put(k, af.getString(k));
        }
        return b;
    }

    public Brain copy() {
        Brain b = load(save());
        b.members = 1;
        return b;
    }

    /** Hive-mind merge: Q values are averaged weighted by visit counts, knowledge estimators by sample counts. */
    public static Brain merge(Collection<Brain> brains) {
        Brain merged = new Brain();
        merged.members = 0;
        Set<Brain> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Brain b : brains) {
            if (b != null && seen.add(b)) {
                merged.absorb(b);
            }
        }
        merged.members = Math.max(1, merged.members);
        return merged;
    }

    public void absorb(Brain b) {
        strategy.mergeFrom(b.strategy);
        goals.mergeFrom(b.goals);
        skillInitiation.mergeFrom(b.skillInitiation);
        skillTermination.mergeFrom(b.skillTermination);
        routeChoices.mergeFrom(b.routeChoices);
        reportVerification.mergeFrom(b.reportVerification);
        b.combat.forEach((k, v) -> combatTable(k).mergeFrom(v));
        b.knowledge.forEach((k, v) -> knowledge(k).merge(v));
        replay.addAll(b.replay);
        ownUpdates += b.ownUpdates;
        imitationUpdates += b.imitationUpdates;
        replayUpdates += b.replayUpdates;
        counterfactualUpdates += b.counterfactualUpdates;
        decisions += b.decisions;
        kills += b.kills;
        deaths += b.deaths;
        members += b.members;
        b.harmfulBlocks.object2IntEntrySet().forEach(e -> harmfulBlocks.mergeInt(e.getKey(), e.getIntValue(), Integer::sum));
        for (String id : b.confirmedHarmfulBlocks) {
            if (confirmedHarmfulBlocks.size() < 512) {
                confirmedHarmfulBlocks.add(id);
                confirmedSafeBlocks.remove(id);
            }
        }
        for (String id : b.confirmedSafeBlocks) {
            if (confirmedSafeBlocks.size() < 512 && !confirmedHarmfulBlocks.contains(id)) {
                confirmedSafeBlocks.add(id);
            }
        }
        b.effectSamples.object2IntEntrySet().forEach(e -> {
            int mine = effectSamples.getInt(e.getKey());
            int theirs = e.getIntValue();
            effectValues.put(e.getKey(), (effectValues.getFloat(e.getKey()) * mine + b.effectValues.getFloat(e.getKey()) * theirs) / Math.max(1, mine + theirs));
            effectSamples.put(e.getKey(), mine + theirs);
        });
        b.knownItems.object2IntEntrySet().forEach(e -> knownItems.mergeInt(e.getKey(), e.getIntValue(), Math::min));
        b.itemFacts.forEach(itemFacts::putIfAbsent);
        knownBlocks.addAll(b.knownBlocks);
        flags.addAll(b.flags);
        for (SiteExperience source : b.siteExperiences.values()) {
            String key = experienceKey(source.dimension, source.chunkX, source.chunkZ, source.action);
            SiteExperience target = siteExperiences.get(key);
            if (target == null) {
                target = new SiteExperience(source.dimension, source.chunkX, source.chunkZ, source.action);
                target.value = source.value;
                target.samples = source.samples;
                target.lastTick = source.lastTick;
                target.cause = source.cause;
                siteExperiences.put(key, target);
            } else {
                int n1 = Math.max(1, target.samples);
                int n2 = Math.max(1, source.samples);
                target.value = (target.value * n1 + source.value * n2) / (float) (n1 + n2);
                target.samples = Math.min(8, n1 + n2);
                if (source.lastTick >= target.lastTick) {
                    target.lastTick = source.lastTick;
                    target.cause = source.cause;
                }
            }
        }
        trimSiteExperiences();
        for (SocialExperience source : b.socialExperiences.values()) {
            SocialExperience target = socialExperiences.get(source.partner);
            if (target == null) {
                target = new SocialExperience(source.partner);
                target.trust = source.trust;
                target.samples = source.samples;
                target.positive = source.positive;
                target.negative = source.negative;
                target.lastTick = source.lastTick;
                target.cause = source.cause;
                socialExperiences.put(source.partner, target);
            } else {
                int n1 = Math.max(1, target.samples);
                int n2 = Math.max(1, source.samples);
                target.trust = (target.trust * n1 + source.trust * n2) / (float) (n1 + n2);
                target.samples = Math.min(16, n1 + n2);
                target.positive = saturatingAdd(target.positive, source.positive);
                target.negative = saturatingAdd(target.negative, source.negative);
                if (source.lastTick >= target.lastTick) {
                    target.lastTick = source.lastTick;
                    target.cause = source.cause;
                }
            }
        }
        trimSocialExperiences();
        for (PartnerLedger source : b.partnerLedger.values()) {
            PartnerLedger target = partnerLedger.computeIfAbsent(source.partner, PartnerLedger::new);
            target.contributed = Math.min(1_000_000f, target.contributed + source.contributed);
            target.withdrawn = Math.min(1_000_000f, target.withdrawn + source.withdrawn);
            target.contributionSamples = saturatingAdd(target.contributionSamples, source.contributionSamples);
            target.withdrawalSamples = saturatingAdd(target.withdrawalSamples, source.withdrawalSamples);
            if (source.lastTick >= target.lastTick) {
                target.lastTick = source.lastTick;
                target.lastCause = source.lastCause;
            }
        }
        trimPartnerLedger();
        for (ReportExperience source : b.reportExperiences.values()) {
            String key = reportKey(source.partner, source.type);
            ReportExperience target = reportExperiences.computeIfAbsent(key, k -> new ReportExperience(source.partner, source.type));
            target.correct = saturatingAdd(target.correct, source.correct);
            target.incorrect = saturatingAdd(target.incorrect, source.incorrect);
            long total = (long) target.correct + target.incorrect;
            if (total > 256) {
                target.correct /= 2;
                target.incorrect /= 2;
            }
            target.lastTick = Math.max(target.lastTick, source.lastTick);
        }
        while (reportExperiences.size() > REPORT_PROFILE_LIMIT) {
            reportExperiences.remove(reportExperiences.keySet().iterator().next());
        }
        for (StructureReportExperience source : b.structureReportExperiences.values()) {
            String key = structureReportKey(source.reporter, source.subject, source.distanceBand);
            StructureReportExperience target = structureReportExperiences.computeIfAbsent(key,
                    k -> new StructureReportExperience(source.reporter, source.subject, source.distanceBand));
            target.correct = saturatingAdd(target.correct, source.correct);
            target.incorrect = saturatingAdd(target.incorrect, source.incorrect);
            long total = (long) target.correct + target.incorrect;
            if (total > 256) {
                target.correct /= 2;
                target.incorrect /= 2;
            }
            target.lastTick = Math.max(target.lastTick, source.lastTick);
        }
        trimStructureReportExperiences();
        for (PendingReport source : b.pendingReports.values()) {
            String key = reportClaimKey(source.reporter, source.type, source.subject, source.dimension, source.x, source.y, source.z);
            PendingReport target = pendingReports.get(key);
            if (target == null) {
                pendingReports.put(key, new PendingReport(source.reporter, source.type, source.subject, source.dimension,
                        source.x, source.y, source.z, source.distanceBand, source.tick));
            } else if (source.tick > target.tick) {
                target.tick = source.tick;
                target.distanceBand = source.distanceBand;
            }
        }
        while (pendingReports.size() > REPORT_CLAIM_LIMIT) {
            pendingReports.remove(pendingReports.keySet().iterator().next());
        }
        for (RouteExperience source : b.routeExperiences.values()) {
            String key = routeKey(source.dimension, source.chunkX, source.chunkZ, source.context);
            RouteExperience target = routeExperiences.get(key);
            if (target == null) {
                target = new RouteExperience(source.dimension, source.chunkX, source.chunkZ, source.context);
                target.risk = source.risk;
                target.samples = source.samples;
                target.lastTick = source.lastTick;
                routeExperiences.put(key, target);
            } else {
                int n1 = Math.max(1, target.samples);
                int n2 = Math.max(1, source.samples);
                target.risk = (target.risk * n1 + source.risk * n2) / (float) (n1 + n2);
                target.samples = Math.min(16, n1 + n2);
                target.lastTick = Math.max(target.lastTick, source.lastTick);
            }
        }
        trimRouteExperiences();
        for (int g = 0; g < PARKOUR_GAPS; g++) {
            for (int a = 0; a < 2; a++) {
                int n = parkN[g][a] + b.parkN[g][a];
                if (n > 0) {
                    parkQ[g][a] = (parkQ[g][a] * parkN[g][a] + b.parkQ[g][a] * b.parkN[g][a]) / n;
                }
                parkN[g][a] = n;
            }
        }
        for (int c = 0; c < LOOK_CONTEXTS; c++) {
            for (int a = 0; a < LOOK_SPEEDS.length; a++) {
                int n = lookN[c][a] + b.lookN[c][a];
                if (n > 0) {
                    lookQ[c][a] = (lookQ[c][a] * lookN[c][a] + b.lookQ[c][a] * b.lookN[c][a]) / n;
                }
                lookN[c][a] = n;
            }
        }
        lookUpdates += b.lookUpdates;
        b.useQ.forEach((k, bq) -> {
            float[] q = useQ.computeIfAbsent(k, x -> new float[USE_MODES]);
            int[] n = useN.computeIfAbsent(k, x -> new int[USE_MODES]);
            int[] bn = b.useN.getOrDefault(k, new int[USE_MODES]);
            for (int m = 0; m < USE_MODES; m++) {
                int t = n[m] + bn[m];
                if (t > 0) {
                    q[m] = (q[m] * n[m] + bq[m] * bn[m]) / t;
                }
                n[m] = t;
            }
        });
        b.advancementFacts.forEach(advancementFacts::putIfAbsent);
    }

    // ---------------------------------------------------------------- introspection

    public int totalStates() {
        int n = strategy.size() + goals.size() + skillInitiation.size() + skillTermination.size() + routeChoices.size() + reportVerification.size();
        for (QTable t : combat.values()) {
            n += t.size();
        }
        return n;
    }

    public List<String> summary() {
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "states=%d enemyTypes=%d knownTypes=%d own=%d imitated=%d replayed=%d counterfactual=%d kills=%d deaths=%d members=%d",
                totalStates(), combat.size(), knowledge.size(), ownUpdates, imitationUpdates, replayUpdates, counterfactualUpdates, kills, deaths, members));
        lines.add(String.format(Locale.ROOT, "teamLedger=%d reportProfiles=%d structureContexts=%d pendingReports=%d verificationStates=%d",
                partnerLedger.size(), reportExperiences.size(), structureReportExperiences.size(), pendingReports.size(), reportVerification.size()));
        return lines;
    }

    /** Learned combat policy against one enemy type: best action per distance band, fresh vs. charging enemy. */
    public List<String> describePolicy(String type) {
        List<String> lines = new ArrayList<>();
        QTable t = combat.get(type);
        if (t == null) {
            lines.add("no combat experience");
            return lines;
        }
        int visited = 0;
        long updates = 0;
        for (Int2ObjectMap.Entry<QTable.Entry> e : t.entries()) {
            visited++;
            for (int n : e.getValue().n) {
                updates += n;
            }
        }
        lines.add(String.format(Locale.ROOT, "states=%d updates=%d", visited, updates));
        for (int dist = 0; dist < CombatState.DIST; dist++) {
            int calm = CombatState.encode(dist, 2, 1, 2, 0, 0, 1);
            int charged = CombatState.encode(dist, 0, 2, 2, 0, 0, 1);
            int windup = CombatState.encode(dist, 2, 2, 2, 1, 0, 1);
            int mask = CombatAction.ALL & ~CombatAction.SHOOT.bit() & ~CombatAction.BLOCK.bit() & ~CombatAction.PILLAR.bit() & ~CombatAction.USE_ITEM.bit();
            lines.add(String.format(Locale.ROOT, "%-11s ready:%-13s recharging:%-13s enemy-windup:%s",
                    CombatState.DIST_NAMES[dist],
                    CombatAction.VALUES[greedy(type, calm, mask)].key(),
                    CombatAction.VALUES[greedy(type, charged, mask)].key(),
                    CombatAction.VALUES[greedy(type, windup, mask)].key()));
        }
        return lines;
    }
}
