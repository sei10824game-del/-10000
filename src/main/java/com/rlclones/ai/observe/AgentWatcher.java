package com.rlclones.ai.observe;

import com.rlclones.Config;
import com.rlclones.ai.Perception;
import com.rlclones.ai.Senses;
import com.rlclones.ai.brain.Brain;
import com.rlclones.ai.combat.CombatAction;
import com.rlclones.ai.strategy.Option;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Observational (imitation) learning. For every agent in view - another clone or a real player, which are
 * deliberately indistinguishable - the observer rebuilds that agent's situation in its OWN state space,
 * infers which action the agent took from what it could see (movement, swings, blocking, eating...),
 * scores it with the same reward function it uses for itself and feeds the transition into its Q-tables.
 */
public final class AgentWatcher {
    private static final class Trace {
        long tick;
        Vec3 pos;
        int enemyId = -1;
        String enemyType;
        int combatState = -1;
        long stratTick;
        int stratState = -1;
        Vec3 stratPos;
        float stratHp;
        int stratFood;
    }

    private final ServerPlayer self;
    private final Perception perception;
    private final Supplier<Brain> brain;
    private final ToLongFunction<Entity> sinceEnemyAttack;
    private final Map<UUID, Trace> traces = new HashMap<>();
    public long observedTransitions;

    public AgentWatcher(ServerPlayer self, Perception perception, Supplier<Brain> brain, ToLongFunction<Entity> sinceEnemyAttack) {
        this.self = self;
        this.perception = perception;
        this.brain = brain;
        this.sinceEnemyAttack = sinceEnemyAttack;
    }

    public int watching() {
        return traces.size();
    }

    public void update(long now) {
        Set<UUID> seen = new HashSet<>();
        float weight = (float) Config.get(Config.IMITATION_WEIGHT, 0.8);
        for (Perception.Seen s : perception.visible()) {
            if (!(s.entity instanceof ServerPlayer agent) || agent == self || agent.isSpectator()) {
                continue;
            }
            seen.add(agent.getUUID());
            Trace t = traces.get(agent.getUUID());
            if (t == null) {
                t = new Trace();
                t.tick = now;
                t.pos = agent.position();
                startStrategy(t, agent, now);
                observeCombat(t, agent, now);
                traces.put(agent.getUUID(), t);
                continue;
            }
            if (weight > 0) {
                learnCombat(t, agent, now, weight);
                if (now - t.stratTick >= 40 || !agent.isAlive()) {
                    learnStrategy(t, agent, now, weight);
                    startStrategy(t, agent, now);
                }
            }
            observeCombat(t, agent, now);
            t.tick = now;
            t.pos = agent.position();
        }
        traces.keySet().retainAll(seen);
    }

    // ------------------------------------------------------------------ combat imitation

    private Entity focusEnemy(Player agent, List<AgentEvents.Event> recent) {
        for (int i = recent.size() - 1; i >= 0; i--) {
            AgentEvents.Event e = recent.get(i);
            if (e.kind() == AgentEvents.Kind.ATTACK) {
                Entity target = agent.level().getEntity(e.targetId());
                if (target != null && perception.isVisible(target) && (Senses.isHostileTo(target, agent) || Senses.isFoodAnimal(target))) {
                    return target;
                }
            }
        }
        Entity best = null;
        double bestGap = 10.0;
        for (Perception.Seen s : perception.visible()) {
            if (s.entity == agent || !Senses.isHostileTo(s.entity, agent)) {
                continue;
            }
            double g = Senses.gap(agent, s.entity);
            if (g < bestGap) {
                bestGap = g;
                best = s.entity;
            }
        }
        return best;
    }

    private void observeCombat(Trace t, Player agent, long now) {
        if (!agent.isAlive()) {
            t.combatState = -1;
            return;
        }
        List<AgentEvents.Event> recent = AgentEvents.between(agent.getUUID(), now - 20, now);
        Entity enemy = focusEnemy(agent, recent);
        if (enemy == null) {
            t.combatState = -1;
            t.enemyId = -1;
            return;
        }
        t.enemyId = enemy.getId();
        t.enemyType = Perception.typeId(enemy);
        t.combatState = Senses.combatState(agent, enemy, brain.get().knowledge(t.enemyType), sinceEnemyAttack.applyAsLong(enemy), Senses.crowd(perception, agent));
    }

    private void learnCombat(Trace t, Player agent, long now, float weight) {
        if (t.combatState < 0 || t.enemyType == null) {
            return;
        }
        Entity enemy = agent.level().getEntity(t.enemyId);
        List<AgentEvents.Event> events = AgentEvents.between(agent.getUUID(), t.tick, now);
        int action = inferAction(agent, t, enemy, events);
        if (action < 0) {
            return;
        }
        float reward = combatReward(events) - 0.01f * (now - t.tick);
        boolean agentDied = !agent.isAlive();
        boolean enemyGone = enemy == null || !enemy.isAlive() || enemy.isRemoved();
        Brain b = brain.get();
        if (agentDied || enemyGone) {
            b.learn(t.enemyType, t.combatState, action, reward, 0, true, 0f, 0, weight, true);
        } else if (perception.isVisible(enemy)) {
            int s2 = Senses.combatState(agent, enemy, b.knowledge(t.enemyType), sinceEnemyAttack.applyAsLong(enemy), Senses.crowd(perception, agent));
            b.learn(t.enemyType, t.combatState, action, reward, s2, false, (float) Config.get(Config.DISCOUNT, 0.9), Senses.combatMask(agent), weight, true);
        } else {
            return;
        }
        observedTransitions++;
        if (reward >= 0) {
            b.recordDemo(t.enemyType, t.combatState, action);
        }
    }

    private static float combatReward(List<AgentEvents.Event> events) {
        float r = 0;
        for (AgentEvents.Event e : events) {
            switch (e.kind()) {
                case DEALT -> r += e.amount();
                case HURT -> r -= e.amount() * 1.5f;
                case KILL_HOSTILE -> r += 8f;
                case KILL_ANIMAL -> r += 4f;
                case DEATH -> r -= 25f;
                case BLOCKED -> r += e.amount() * 0.5f;
                default -> {
                }
            }
        }
        return r;
    }

    /** Infer the discrete combat action from what was visible during the interval. */
    private int inferAction(Player agent, Trace t, Entity enemy, List<AgentEvents.Event> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            AgentEvents.Event e = events.get(i);
            if (e.kind() == AgentEvents.Kind.ATTACK) {
                if ((e.flags() & AgentEvents.FLAG_CRIT) != 0) {
                    return CombatAction.CRIT_ATTACK.ordinal();
                }
                if ((e.flags() & AgentEvents.FLAG_SPRINT) != 0) {
                    return CombatAction.SPRINT_ATTACK.ordinal();
                }
                return CombatAction.ATTACK.ordinal();
            }
        }
        for (AgentEvents.Event e : events) {
            if (e.kind() == AgentEvents.Kind.PILLAR) {
                return CombatAction.PILLAR.ordinal();
            }
            if (e.kind() == AgentEvents.Kind.SHOOT) {
                return CombatAction.SHOOT.ordinal();
            }
        }
        if (agent.isBlocking()) {
            return CombatAction.BLOCK.ordinal();
        }
        if (agent.isUsingItem() && agent.getUseItem().getItem() instanceof BowItem) {
            return CombatAction.SHOOT.ordinal();
        }
        if (enemy == null) {
            return -1;
        }
        Vec3 d = agent.position().subtract(t.pos);
        double dx = d.x;
        double dz = d.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.15) {
            return CombatAction.HOLD.ordinal();
        }
        Vec3 toEnemy = enemy.position().subtract(t.pos);
        double ex = toEnemy.x;
        double ez = toEnemy.z;
        double el = Math.sqrt(ex * ex + ez * ez);
        if (el < 1e-4) {
            return CombatAction.HOLD.ordinal();
        }
        ex /= el;
        ez /= el;
        double radial = dx * ex + dz * ez;
        double lateral = dx * ez - dz * ex; // positive = moved to the agent's left while facing the enemy
        if (radial > 0.5 * len) {
            return CombatAction.APPROACH.ordinal();
        }
        if (radial < -0.5 * len) {
            return CombatAction.RETREAT.ordinal();
        }
        return lateral > 0 ? CombatAction.STRAFE_LEFT.ordinal() : CombatAction.STRAFE_RIGHT.ordinal();
    }

    // ------------------------------------------------------------------ strategy imitation

    private void startStrategy(Trace t, Player agent, long now) {
        t.stratTick = now;
        t.stratPos = agent.position();
        t.stratHp = agent.getHealth();
        t.stratFood = agent.getFoodData().getFoodLevel();
        t.stratState = agent.isAlive() ? Senses.strategyState(perception, agent, self, brain.get(), now) : -1;
    }

    private void learnStrategy(Trace t, Player agent, long now, float weight) {
        if (t.stratState < 0) {
            return;
        }
        List<AgentEvents.Event> events = AgentEvents.between(agent.getUUID(), t.stratTick, now);
        Option option = inferOption(agent, t, events, now);
        long dt = Math.max(1, now - t.stratTick);
        float reward = strategyReward(events) - 0.005f * dt;
        float dh = agent.getHealth() - t.stratHp;
        if (dh > 0) {
            reward += dh * 0.3f;
        }
        int food = agent.getFoodData().getFoodLevel();
        if (food > t.stratFood) {
            reward += (food - t.stratFood) * (t.stratFood <= 14 ? 1.0f : 0.2f);
        }
        boolean died = !agent.isAlive();
        Brain b = brain.get();
        int s2 = died ? 0 : Senses.strategyState(perception, agent, self, b, now);
        int mask2 = died ? 0 : Senses.strategyMask(perception, agent, self, now);
        b.learn(Brain.STRATEGY, t.stratState, option.ordinal(), reward, s2, died, (float) Math.pow(0.995, dt), mask2, weight, true);
        observedTransitions++;
        if (reward >= 0) {
            b.recordDemo(Brain.STRATEGY, t.stratState, option.ordinal());
        }
    }

    private static float strategyReward(List<AgentEvents.Event> events) {
        float r = 0;
        for (AgentEvents.Event e : events) {
            switch (e.kind()) {
                case HURT -> r -= e.amount();
                case DEALT -> r += (e.flags() & AgentEvents.FLAG_HOSTILE) != 0 ? e.amount() * 0.2f : 0f;
                case KILL_HOSTILE -> r += 6f;
                case KILL_ANIMAL -> r += 2f;
                case DEATH -> r -= 30f;
                case PICKUP -> r += Math.min(e.amount(), 5f) * 0.2f;
                case BREAK_LOG -> r += 0.5f;
                case BREAK_ORE -> r += 0.8f;
                default -> {
                }
            }
        }
        return r;
    }

    private Option inferOption(Player agent, Trace t, List<AgentEvents.Event> events, long now) {
        boolean fought = false;
        boolean hunted = false;
        boolean ate = false;
        boolean picked = false;
        boolean logs = false;
        boolean ores = false;
        for (AgentEvents.Event e : events) {
            switch (e.kind()) {
                case ATTACK, DEALT, KILL_HOSTILE -> {
                    if (e.kind() == AgentEvents.Kind.KILL_HOSTILE || (e.flags() & AgentEvents.FLAG_HOSTILE) != 0) {
                        fought = true;
                    } else {
                        hunted = true;
                    }
                }
                case KILL_ANIMAL -> hunted = true;
                case EAT -> ate = true;
                case PICKUP -> picked = true;
                case BREAK_LOG -> logs = true;
                case BREAK_ORE -> ores = true;
                default -> {
                }
            }
        }
        if (ate || (agent.isUsingItem() && agent.getUseItem().isEdible())) {
            return Option.EAT;
        }
        if (fought) {
            return Option.FIGHT;
        }
        if (hunted) {
            return Option.HUNT;
        }
        if (logs) {
            return Option.GATHER_WOOD;
        }
        if (ores) {
            return Option.MINE;
        }
        if (picked) {
            return Option.COLLECT;
        }
        Vec3 moved = agent.position().subtract(t.stratPos);
        double dist = Math.sqrt(moved.x * moved.x + moved.z * moved.z);
        List<Perception.Seen> threats = Senses.threats(perception, agent, now, 24);
        if (!threats.isEmpty() && dist > 2) {
            Vec3 away = Vec3.ZERO;
            for (Perception.Seen s : threats) {
                away = away.add(t.stratPos.subtract(s.pos).normalize());
            }
            if (away.lengthSqr() > 1e-4 && moved.normalize().dot(away.normalize()) > 0.5) {
                return Option.FLEE;
            }
        }
        Perception.Seen ally = Senses.nearestAlly(perception, agent, self, now, 64);
        if (ally != null && dist > 2 && ally.pos.distanceTo(agent.position()) + 2 < ally.pos.distanceTo(t.stratPos)) {
            return Option.FOLLOW;
        }
        if (dist > 3) {
            return Option.EXPLORE;
        }
        return Option.REST;
    }
}
