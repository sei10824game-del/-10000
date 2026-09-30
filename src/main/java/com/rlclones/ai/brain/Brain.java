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

    public long ownUpdates;
    public long imitationUpdates;
    public long replayUpdates;
    public long decisions;
    public long kills;
    public long deaths;
    public int members = 1;

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

    private QTable table(String name) {
        return STRATEGY.equals(name) ? strategy : combatTable(name);
    }

    private QTable.Prior prior(String name) {
        if (STRATEGY.equals(name)) {
            return StrategyState::prior;
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
        tag.putInt("version", 1);
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
        tag.putLong("decisions", decisions);
        tag.putLong("kills", kills);
        tag.putLong("deaths", deaths);
        tag.putInt("members", members);
        return tag;
    }

    public static Brain load(CompoundTag tag) {
        Brain b = new Brain();
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
        b.decisions = tag.getLong("decisions");
        b.kills = tag.getLong("kills");
        b.deaths = tag.getLong("deaths");
        b.members = Math.max(1, tag.getInt("members"));
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
        b.combat.forEach((k, v) -> combatTable(k).mergeFrom(v));
        b.knowledge.forEach((k, v) -> knowledge(k).merge(v));
        replay.addAll(b.replay);
        ownUpdates += b.ownUpdates;
        imitationUpdates += b.imitationUpdates;
        replayUpdates += b.replayUpdates;
        decisions += b.decisions;
        kills += b.kills;
        deaths += b.deaths;
        members += b.members;
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
            int mask = CombatAction.ALL & ~CombatAction.SHOOT.bit() & ~CombatAction.BLOCK.bit() & ~CombatAction.PILLAR.bit();
            lines.add(String.format(Locale.ROOT, "%-11s ready:%-13s recharging:%-13s enemy-windup:%s",
                    CombatState.DIST_NAMES[dist],
                    CombatAction.VALUES[greedy(type, calm, mask)].key(),
                    CombatAction.VALUES[greedy(type, charged, mask)].key(),
                    CombatAction.VALUES[greedy(type, windup, mask)].key()));
        }
        return lines;
    }
}
