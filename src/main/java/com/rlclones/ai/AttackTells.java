package com.rlclones.ai;

import com.rlclones.ai.brain.Brain;
import com.rlclones.ai.brain.EnemyKnowledge;
import com.rlclones.clone.CloneManager;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reading an attack before it lands. Every enemy shows something before it strikes: a skeleton (or a rival clone)
 * draws its bow, a creeper swells, a modded boss plays its wind-up animation (entity data synced to the clients that
 * changes just before the blow). A clone watches the enemies it sees, notes which of those visible cues came shortly
 * before an attack and how long before, and once a cue has proved itself it raises the shield in time whenever that
 * cue shows up again. What was learned is kept per enemy type in the brain ("tell" traits: shared and saved).
 */
public final class AttackTells {
    /** An attack this long after a cue appeared counts for that cue. */
    private static final int WINDOW = 50;
    /** A cue unseen for this long is over (the next sighting is a new appearance). */
    private static final int GONE = 10;
    private static final int MIN_HITS = 3;
    private static final double MIN_RATE = 0.6;
    /** A shield takes 5 ticks to come up: raise it this many ticks before the blow is due. */
    private static final int LEAD = 10;

    private static final class Track {
        final String type;
        /** cue -> {first seen, last seen} of its current appearance */
        final Map<String, long[]> cues = new HashMap<>();
        final Set<String> credited = new HashSet<>();
        long lastSample = Long.MIN_VALUE;

        Track(String type) {
            this.type = type;
        }
    }

    private final ClonePlayer self;
    private final Map<Integer, Track> tracks = new HashMap<>();
    @Nullable
    private Entity blockFrom;
    private long blockStart;
    private long blockEnd;
    private boolean blocking;

    public int tellsSeen;
    public int tellBlocks;
    public int learned;
    public String debug = "";

    public AttackTells(ClonePlayer self) {
        this.self = self;
    }

    /** What can be seen of an entity right now that could announce an attack. */
    public static List<String> cues(Entity e) {
        List<String> out = new ArrayList<>(4);
        if (e instanceof LivingEntity le && le.isUsingItem()) {
            out.add("use:" + le.getUseItem().getUseAnimation().name().toLowerCase(Locale.ROOT));
        }
        Pose p = e.getPose();
        if (p != Pose.STANDING && p != Pose.CROUCHING && p != Pose.SWIMMING && p != Pose.FALL_FLYING && p != Pose.DYING && p != Pose.SLEEPING) {
            out.add("pose:" + p.name().toLowerCase(Locale.ROOT));
        }
        if (e instanceof Mob m) {
            if (m.isAggressive()) {
                out.add("aggr");
            }
            // the mob's own synced data (ids from 16 up: what vanilla and modded mobs animate their attacks with)
            List<SynchedEntityData.DataValue<?>> data = e.getEntityData().getNonDefaultValues();
            if (data != null) {
                for (SynchedEntityData.DataValue<?> v : data) {
                    Object val = v.value();
                    if (v.id() >= 16 && out.size() < 8 && (val instanceof Boolean || val instanceof Byte || val instanceof Integer i && Math.abs(i) <= 64)) {
                        out.add("d" + v.id() + "=" + val);
                    }
                }
            }
        }
        return out;
    }

    private static int hits(EnemyKnowledge k, String cue) {
        return k.traits.getInt("tell+:" + cue);
    }

    /** Has this cue proved to come before this kind's attacks? */
    public static boolean isTell(EnemyKnowledge k, String cue) {
        int hits = hits(k, cue);
        return hits >= MIN_HITS && hits >= MIN_RATE * k.traits.getInt("tell#:" + cue);
    }

    /** Average ticks from the cue showing to the attack. */
    public static int delay(EnemyKnowledge k, String cue) {
        int hits = Math.max(1, hits(k, cue));
        return k.traits.getInt("telld:" + cue) / hits;
    }

    /** Watch an enemy in view (every other tick): new cues are counted, known tells schedule the shield. */
    public void watch(Entity e, long now, Brain brain, boolean hostile) {
        Track t = tracks.computeIfAbsent(e.getId(), k -> new Track(Perception.typeId(e)));
        if (t.lastSample == now) {
            return;
        }
        t.lastSample = now;
        EnemyKnowledge k = brain.knowledge(t.type);
        for (String c : cues(e)) {
            long[] v = t.cues.get(c);
            if (v == null || now - v[1] > GONE) {
                v = new long[]{now, now};
                t.cues.put(c, v);
                t.credited.remove(c);
                k.traits.mergeInt("tell#:" + c, 1, Integer::sum);
            } else {
                v[1] = now;
            }
            if (hostile && isTell(k, c) && now - v[0] <= WINDOW) {
                int d = delay(k, c);
                long start = v[0] + Math.max(0, d - LEAD);
                long end = v[0] + d + 8;
                if (end >= now && (blockFrom == null || !blockFrom.isAlive() || now > blockEnd || start < blockStart)) {
                    if (blockFrom != e || blockStart != start) {
                        tellsSeen++;
                        debug = t.type + " " + c + " due in " + (v[0] + d - now);
                    }
                    blockFrom = e;
                    blockStart = start;
                    blockEnd = end;
                }
            }
        }
        t.cues.values().removeIf(v -> now - v[1] > WINDOW);
    }

    /** {@code attacker} struck (a blow, a shot, a blast): the cues it showed just before count as its tells. */
    public void onAttack(Entity attacker, long now, Brain brain, boolean shot) {
        Track t = tracks.get(attacker.getId());
        if (t == null) {
            return;
        }
        EnemyKnowledge k = brain.knowledge(t.type);
        for (Map.Entry<String, long[]> en : t.cues.entrySet()) {
            long[] v = en.getValue();
            if (now - v[1] > GONE || now - v[0] > WINDOW || !t.credited.add(en.getKey())) {
                continue;
            }
            k.traits.mergeInt("tell+:" + en.getKey(), 1, Integer::sum);
            k.traits.mergeInt("telld:" + en.getKey(), (int) (now - v[0]), Integer::sum);
            learned++;
        }
        if (attacker == blockFrom && now >= blockStart) {
            // it came: the shield can go down again - once a shot has had time to arrive
            blockEnd = shot ? Math.max(blockEnd, now + 4 + (long) (attacker.distanceTo(self) / 1.5)) : Math.min(blockEnd, now + 2);
        }
    }

    /** Every clone that has been watching {@code attacker} learns from its attack. */
    public static void attacked(Entity attacker) {
        attacked(attacker, false);
    }

    public static void attacked(Entity attacker, boolean shot) {
        CloneManager m = CloneManager.peek();
        if (m == null || attacker == null) {
            return;
        }
        long now = attacker.level().getGameTime();
        for (ClonePlayer c : m.clones()) {
            if (c != attacker && c.isAlive() && c.level() == attacker.level() && c.getCloneBrain() != null) {
                c.controller().tells().onAttack(attacker, now, c.getCloneBrain(), shot);
            }
        }
    }

    /** A known tell is showing and its blow is about to land: the shield should be up now. */
    @Nullable
    public Entity blockNow(long now) {
        if (blockFrom == null || !blockFrom.isAlive() || now < blockStart || now > blockEnd) {
            return null;
        }
        return blockFrom;
    }

    public boolean blocking() {
        return blocking;
    }

    public void setBlocking(boolean b) {
        blocking = b;
    }

    /** Forget entities that are gone. */
    public void prune() {
        tracks.entrySet().removeIf(en -> self.level().getEntity(en.getKey()) == null);
    }
}
