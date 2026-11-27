package com.rlclones.ai.brain;

import com.rlclones.Config;
import com.rlclones.ai.combat.CombatAction;
import com.rlclones.ai.combat.CombatState;
import com.rlclones.ai.strategy.Option;
import com.rlclones.ai.strategy.StrategyState;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * All learned state of one clone (or of the whole hive when clones are linked with X):
 * one combat Q-table per enemy type, a strategy Q-table, learned enemy knowledge and a replay memory.
 * Learning is off-policy Q-learning, so experience from watching others is valid training data too.
 */
public final class Brain {
    public static final String STRATEGY = "#strategy";

    private final Map<String, QTable> combat = new HashMap<>();
    private final Map<String, EnemyKnowledge> knowledge = new HashMap<>();
    private final QTable strategy = new QTable(Option.COUNT);
    private final ReplayBuffer replay = new ReplayBuffer(4096);
    private final Random random = new Random();

    public final RecoveryLearning ground = new RecoveryLearning();
    public final RecoveryLearning bowAim = new RecoveryLearning();
    public final RecoveryLearning retreat = new RecoveryLearning();
    public final RecoveryLearning recovery = new RecoveryLearning();
    public final RecoveryLearning oreWork = new RecoveryLearning();
    public final RecoveryLearning workflow = new RecoveryLearning(), roles = new RecoveryLearning();
    public final LearningEvidence evidence = new LearningEvidence();
    public final TrialMemory itemTrials = new TrialMemory();
    public RecoveryLearning methodTable(int domain) {
        return switch(domain){case 0->recovery;case 1->oreWork;case 2->workflow;case 3->roles;default->throw new IllegalArgumentException("domain");};
    }
    public int chooseMethod(int domain,int state,int mask,java.util.Random random) {
        if(domain<0||domain>=LearningEvidence.DOMAINS||state<0||state>=8||(mask&15)==0)return -1;
        var table=methodTable(domain);int suggested=evidence.suggested(domain,state)&mask;
        int untried=0;for(int a=0;a<4;a++)if((suggested&(1<<a))!=0&&table.samples(state,a)==0)untried|=1<<a;
        return table.choose(state,untried!=0?untried:mask,random);
    }
    public void recordMethod(int domain,int state,int method,float reward,boolean success) {
        if(!LearningEvidence.valid(domain,state,method)||!Float.isFinite(reward))return;
        var table=methodTable(domain);long before=table.trials();table.record(state,method,reward,success);
        if(table.trials()>before)evidence.record(domain,success);
    }

    public long ownUpdates;
    public long imitationUpdates;
    public long replayUpdates;
    private long pendingReplay;
    public long pendingReplay() { return pendingReplay; }
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

    public boolean hasFlag(String f) {
        return flags.contains(f);
    }

    public void setFlag(String f) {
        flags.add(f);
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
        float best = Math.max(parkQ[g][0], parkQ[g][1]);
        if (best > 0.2f && parkQ[g][1] != parkQ[g][0]) {
            return rnd.nextInt(20) == 0 ? rnd.nextInt(2) : (parkQ[g][1] > parkQ[g][0] ? 1 : 0); // it worked: it is used (the other way is tried one time in twenty)
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
        if (reward > 0f) {
            parkQ[g][how] = Math.max(parkQ[g][how], 0.3f); // one jump that worked is enough to trust: earlier misses do not outweigh it
        }
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
        QTable have = combat.get(type);
        if (have != null) {
            return have;
        }
        QTable fresh = new QTable(CombatAction.COUNT);
        QTable donor = donorFor(type);
        if (donor != null) {
            // a type never met (a modded mob...) starts from what was learned against its likes - lightly, so its own experience takes over fast
            fresh.mergeFrom(donor);
            for (var e : fresh.entries()) {
                for (int a = 0; a < e.getValue().n.length; a++) {
                    e.getValue().n[a] = Math.min(e.getValue().n[a], 2);
                    e.getValue().demo[a] = 0;
                }
                e.getValue().visits = Math.min(e.getValue().visits, 2);
            }
        }
        combat.put(type, fresh);
        return fresh;
    }

    /** "ranged" / "boom" / "melee", and big or small: which enemies are alike for the purpose of fighting them. */
    private String archetype(String type) {
        EnemyKnowledge k = knowledge(type);
        return (k.isExplosive() ? "boom" : k.isRanged() ? "ranged" : "melee") + (k.health.get() >= 60 ? "-big" : "-small");
    }

    /** The best-practised combat table of a type of the same archetype (or null). */
    @javax.annotation.Nullable
    private QTable donorFor(String type) {
        String want = archetype(type);
        QTable best = null;
        for (Map.Entry<String, QTable> e : combat.entrySet()) {
            if (!e.getKey().equals(type) && e.getValue().size() >= 20 && (best == null || e.getValue().size() > best.size())
                    && archetype(e.getKey()).equals(want)) {
                best = e.getValue();
            }
        }
        return best;
    }

    public QTable strategyTable() {
        return strategy;
    }

    private QTable table(String name) {
        return STRATEGY.equals(name) ? strategy : combatTable(name);
    }

    private QTable.Prior prior(String name) {
        if (STRATEGY.equals(name)) {
            return StrategyState::prior;
        }
        return knowledge(name).combatPrior;
    }

    public int chooseCombat(String type, int state, int mask) {
        return choose(type, state, mask);
    }

    public int chooseStrategy(int state, int mask) {
        return choose(STRATEGY, state, mask);
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
            for (int bits = mask & QTable.actionMask(e.demo.length); bits != 0; bits &= bits - 1) {
                demoTotal += e.demo[Integer.numberOfTrailingZeros(bits)];
            }
            if (demoTotal > 0 && random.nextDouble() < 0.6) {
                int pick = random.nextInt(demoTotal);
                for (int bits = mask & QTable.actionMask(e.demo.length); bits != 0; bits &= bits - 1) {
                    int a = Integer.numberOfTrailingZeros(bits);
                    pick -= e.demo[a];
                    if (pick < 0) return a;
                }
            }
            int count = Integer.bitCount(mask);
            int pick = random.nextInt(count);
            for (int bits = mask & QTable.actionMask(e.q.length); bits != 0; bits &= bits - 1) {
                if (pick-- == 0) return Integer.numberOfTrailingZeros(bits);
            }
        }
        return QTable.argmax(e, mask, random);
    }

    /** Greedy action without exploration or bookkeeping (used for display). */
    public int greedy(String tableName, int state, int mask) {
        QTable table = table(tableName);
        QTable.Entry e = table.peek(state);
        if (e == null) {
            float[] q = new float[table.actions()];
            prior(tableName).fill(state, q);
            return QTable.argmax(q, mask, random);
        }
        return QTable.argmax(e, mask, random);
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
        if (a < 0 || a >= (STRATEGY.equals(tableName) ? Option.COUNT : CombatAction.COUNT)
                || !Float.isFinite(r) || !Float.isFinite(gamma) || gamma < 0f || gamma > 1f
                || !Float.isFinite(weight) || weight <= 0f) {
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
        pendingReplay = Math.min(Long.MAX_VALUE - n, pendingReplay) + n;
        var cm = com.rlclones.clone.CloneManager.peek();
        if (cm == null) replaySome(n); // standalone callers retain immediate behavior
        else cm.scheduleReplay(this);
    }

    /** Direct updates are never delayed. Only historical rehearsal uses the shared tick budget. */
    public int replaySome(int budget) {
        int done=0;
        while (done < Math.max(0,budget) && pendingReplay > 0) {
            ReplayBuffer.Transition t = replay.sample(random);
            if (t == null) break;
            apply(t.table, t.s, t.a, t.r, t.s2, t.terminal, t.gamma, t.nextMask, t.weight * 0.5f, false);
            replayUpdates++; pendingReplay--; done++;
        }
        return done;
    }

    /** Remember that a (successful) demonstrator chose action a in state s; biases exploration. */
    public void recordDemo(String tableName, int s, int a) {
        if (a < 0) {
            return;
        }
        QTable.Entry e = table(tableName).get(s, prior(tableName));
        if (a >= e.demo.length) {
            return;
        }
        if (e.demo[a] < Integer.MAX_VALUE) {
            e.demo[a]++;
        }
    }

    private void apply(String tableName, int s, int a, float r, int s2, boolean terminal, float gamma, int nextMask, float weight, boolean count) {
        QTable table = table(tableName);
        QTable.Prior prior = prior(tableName);
        QTable.Entry e = table.get(s, prior);
        if (a < 0 || a >= e.q.length) {
            return;
        }
        float target = r;
        if (!terminal) {
            int mask = nextMask == 0 ? (1 << table.actions()) - 1 : nextMask;
            target += gamma * QTable.max(s2 == s ? e : table.get(s2, prior), mask);
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
        tag.putInt("version", 1);
        tag.putByteArray("ground",ground.save());
        tag.putByteArray("bowAim",bowAim.save());
        tag.putByteArray("retreat", retreat.save());
        tag.putByteArray("recovery", recovery.save());
        tag.putByteArray("oreWork", oreWork.save());
        tag.putByteArray("workflow", workflow.save());tag.putByteArray("roles", roles.save());tag.putByteArray("learningEvidence",evidence.save());tag.put("itemTrials",itemTrials.save());
        tag.putByteArray("strategy", strategy.toBytes());
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
        tag.putLong("pendingReplay", pendingReplay);
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
        b.ground.merge(RecoveryLearning.load(tag.getByteArray("ground")));
        b.bowAim.merge(RecoveryLearning.load(tag.getByteArray("bowAim")));
        b.retreat.merge(RecoveryLearning.load(tag.getByteArray("retreat")));
        b.recovery.merge(RecoveryLearning.load(tag.getByteArray("recovery")));
        b.oreWork.merge(RecoveryLearning.load(tag.getByteArray("oreWork")));
        b.workflow.merge(RecoveryLearning.load(tag.getByteArray("workflow")));b.roles.merge(RecoveryLearning.load(tag.getByteArray("roles")));
        var savedEvidence=LearningEvidence.load(tag.getByteArray("learningEvidence"));b.evidence.mergeHints(savedEvidence);b.evidence.copyHistoryFrom(savedEvidence);b.itemTrials.merge(TrialMemory.load(tag.getCompound("itemTrials")));
        b.strategy.mergeFrom(QTable.fromBytes(tag.getByteArray("strategy"), Option.COUNT));
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
        b.pendingReplay = Math.max(0, tag.getLong("pendingReplay"));
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
        if (b == this) {
            return;
        }
        ground.merge(b.ground);
        bowAim.merge(b.bowAim);
        retreat.merge(b.retreat);
        recovery.merge(b.recovery);
        oreWork.merge(b.oreWork);
        workflow.merge(b.workflow);roles.merge(b.roles);evidence.mergeHints(b.evidence);itemTrials.merge(b.itemTrials);
        strategy.mergeFrom(b.strategy);
        b.combat.forEach((k, v) -> combatTable(k).mergeFrom(v));
        b.knowledge.forEach((k, v) -> knowledge(k).merge(v));
        replay.addAll(b.replay);
        ownUpdates += b.ownUpdates;
        imitationUpdates += b.imitationUpdates;
        replayUpdates += b.replayUpdates;
        pendingReplay = Math.min(Long.MAX_VALUE-b.pendingReplay,pendingReplay)+b.pendingReplay;
        decisions += b.decisions;
        kills += b.kills;
        deaths += b.deaths;
        members += b.members;
        b.harmfulBlocks.object2IntEntrySet().forEach(e -> harmfulBlocks.mergeInt(e.getKey(), e.getIntValue(), Integer::sum));
        // A direct observation of harm overrides an older correction; otherwise retain
        // corrections so that linking/unlinking cannot reintroduce disproven hearsay.
        safeBlocks.addAll(b.safeBlocks);
        safeBlocks.removeAll(harmfulBlocks.keySet());
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
        int n = strategy.size();
        for (QTable t : combat.values()) {
            n += t.size();
        }
        return n;
    }

    public List<String> summary() {
        List<String> lines = new ArrayList<>();
        lines.add(itemTrials.report());
        lines.add(ground.report("戦闘の地形",new String[]{"遮蔽","高所","狭所","開地"}));
        lines.add(bowAim.report("弓の偏差射撃",new String[]{"現在位置","控えめ","標準","大きめ"}));
        lines.add(retreat.report("撤退の生還学習",new String[]{"離脱","遮蔽","合流","高所"}));
        lines.add(recovery.report()+" "+evidence.report(0));
        lines.add(workflow.report("採掘前の手順",new String[]{"直接","照明","倉庫整理","道具準備"})+" "+evidence.report(2));
        lines.add(roles.report("集落の役割",new String[]{"食料","資源","製作","配送"})+" "+evidence.report(3));
        lines.add(oreWork.report("鉱石の取りかかり", new String[]{"近距離","高低差","まとまり","明るさ"})+" "+evidence.report(1));
        lines.add(String.format(Locale.ROOT, "states=%d enemyTypes=%d knownTypes=%d own=%d imitated=%d replayed=%d kills=%d deaths=%d members=%d",
                totalStates(), combat.size(), knowledge.size(), ownUpdates, imitationUpdates, replayUpdates, kills, deaths, members));
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
