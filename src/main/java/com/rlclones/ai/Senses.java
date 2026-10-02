package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.ai.brain.Brain;
import com.rlclones.ai.brain.EnemyKnowledge;
import com.rlclones.ai.combat.CombatAction;
import com.rlclones.ai.combat.CombatState;
import com.rlclones.ai.strategy.Option;
import com.rlclones.ai.strategy.StrategyState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns what an observer perceives into discrete RL states. The same functions are used for the clone itself
 * and for any other agent it watches, so observed experience maps 1:1 onto its own state space.
 */
public final class Senses {
    /** Box gap at which a player's 3-block reach still connects (eye is 0.3 inside the own box). */
    public static final double REACH_GAP = 2.6;
    private static final Set<EntityType<?>> FOOD_ANIMALS = Set.of(EntityType.COW, EntityType.PIG, EntityType.CHICKEN,
            EntityType.SHEEP, EntityType.RABBIT, EntityType.MOOSHROOM, EntityType.COD, EntityType.SALMON);

    private Senses() {
    }

    public static boolean isAgent(Entity e) {
        return e instanceof ServerPlayer p && !p.isSpectator() && p.isAlive();
    }

    /** A player or clone on our side (same team, and no battle royale going on). */
    public static boolean isAllyOf(Entity e, Entity agent) {
        return isAgent(e) && !rivals(e, agent);
    }

    public static String teamOf(Entity p) {
        return p instanceof com.rlclones.clone.ClonePlayer c ? c.cloneTeam() : com.rlclones.clone.ClonePlayer.DEFAULT_TEAM;
    }

    /** Two players / clones that fight each other: different clone teams, or everybody during a battle royale. */
    public static boolean rivals(Entity a, Entity b) {
        if (!(a instanceof Player pa) || !(b instanceof Player pb) || a == b || !pa.isAlive() || pa.isSpectator() || pb.isSpectator()) {
            return false;
        }
        if (pa.isCreative() || pb.isCreative()) {
            return false; // creative players / helpers are not part of the fight
        }
        if (com.rlclones.clone.CloneManager.battleRoyaleOn()) {
            return true;
        }
        return !teamOf(pa).equals(teamOf(pb));
    }

    /** Hostile = monster that is not neutral, or any mob currently targeting a player/clone or that hurt the agent. */
    public static boolean isHostileTo(Entity e, LivingEntity agent) {
        if (e instanceof Player) {
            return agent != null && rivals(e, agent);
        }
        if (!(e instanceof Mob m) || !m.isAlive() || m.isRemoved()) {
            return false;
        }
        if (m.getTarget() instanceof Player) {
            return true;
        }
        if (agent != null && agent.getLastHurtByMob() == m) {
            return true;
        }
        if (e instanceof NeutralMob) {
            return false;
        }
        return e instanceof Enemy;
    }

    public static boolean isFoodAnimal(Entity e) {
        // never hunt someone's named or leashed animals
        return e instanceof Mob m && m.isAlive() && !m.isBaby() && !m.hasCustomName() && !m.isLeashed() && FOOD_ANIMALS.contains(e.getType())
                && !(e instanceof net.minecraft.world.entity.TamableAnimal t && t.isTame())
                && !Animals.protectedAnimal(e); // the last two of a pen stay alive
    }

    public static double gap(Entity a, Entity b) {
        return gap(a.getBoundingBox(), b.getBoundingBox());
    }

    public static double gap(AABB a, AABB b) {
        double dx = Math.max(0, Math.max(a.minX - b.maxX, b.minX - a.maxX));
        double dy = Math.max(0, Math.max(a.minY - b.maxY, b.minY - a.maxY));
        double dz = Math.max(0, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static boolean isWindingUp(Entity enemy) {
        if (enemy instanceof Creeper c) {
            return c.getSwellDir() > 0 || c.isIgnited();
        }
        return enemy instanceof LivingEntity le && le.isUsingItem();
    }

    // ------------------------------------------------------------------ combat

    public static int combatState(Player agent, Entity enemy, EnemyKnowledge k, long ticksSinceEnemyAttack, int crowd) {
        double g = gap(agent, enemy);
        double rm = Math.max(0.2, k.meleeRange.get());
        double ranged = k.has(EnemyKnowledge.RANGED) ? Math.max(6.0, k.rangedRange.get()) : 6.0;
        int dist;
        if (g <= rm * 0.6) {
            dist = 0;
        } else if (g <= rm) {
            dist = 1;
        } else if (g <= Math.max(rm, REACH_GAP)) {
            dist = 2;
        } else if (g <= ranged) {
            dist = 3;
        } else {
            dist = 4;
        }
        float strength = agent.getAttackStrengthScale(0.5F);
        int own = strength < 0.5F ? 0 : strength < 0.9F ? 1 : 2;
        double cd = Math.max(5.0, k.cooldown());
        int enemyReady = ticksSinceEnemyAttack < cd * 0.5 ? 0 : ticksSinceEnemyAttack < cd ? 1 : 2;
        float hpFrac = agent.getHealth() / Math.max(1f, agent.getMaxHealth());
        int hp = hpFrac <= 0.35f ? 0 : hpFrac <= 0.7f ? 1 : 2;
        int windup = isWindingUp(enemy) ? 1 : 0;
        int crowdBin = crowd <= 1 ? 0 : crowd <= 3 ? 1 : 2;
        double dy = enemy.getY() - agent.getY();
        int elev = dy < -1.5 ? 0 : dy > 1.5 ? 2 : 1;
        return CombatState.encode(dist, own, enemyReady, hp, windup, crowdBin, elev);
    }

    /** Number of hostiles the observer can see within 6 blocks of the agent. */
    public static int crowd(Perception observer, Player agent) {
        int n = 0;
        for (Perception.Seen s : observer.visible()) {
            if (s.entity != agent && isHostileTo(s.entity, agent) && gap(agent, s.entity) <= 6.0) {
                n++;
            }
        }
        return Math.max(1, n);
    }

    public static int combatMask(Player agent) {
        int mask = CombatAction.ALL;
        if (!Equipment.hasShield(agent)) {
            mask &= ~CombatAction.BLOCK.bit();
        }
        if (Equipment.rangedSlot(agent) < 0) {
            mask &= ~CombatAction.SHOOT.bit();
        }
        if (Equipment.usableSlot(agent, x -> true) < 0) {
            mask &= ~CombatAction.USE_ITEM.bit();
        }
        boolean headroom = agent.level().getBlockState(agent.blockPosition().above(2)).getCollisionShape(agent.level(), agent.blockPosition().above(2)).isEmpty();
        if (!Config.get(Config.ALLOW_BLOCK_PLACING, true) || Equipment.pillarBlockSlot(agent) < 0 || !agent.onGround() || !headroom) {
            mask &= ~CombatAction.PILLAR.bit();
        }
        return mask;
    }

    // ------------------------------------------------------------------ strategy

    public static List<Perception.Seen> threats(Perception observer, Player agent, long now, double radius) {
        List<Perception.Seen> out = new ArrayList<>();
        for (Perception.Seen s : observer.remembered()) {
            if (now - s.lastSeen > 60 || !s.alive() || s.entity == agent) {
                continue;
            }
            if (isHostileTo(s.entity, agent) && s.pos.distanceTo(agent.position()) <= radius) {
                out.add(s);
            }
        }
        return out;
    }

    public static int threatLevel(Perception observer, Player agent, Brain brain, long now) {
        List<Perception.Seen> threats = threats(observer, agent, now, 24);
        if (threats.isEmpty()) {
            return 0;
        }
        double danger = 0;
        for (Perception.Seen s : threats) {
            EnemyKnowledge k = brain.knowledgeIfPresent(s.typeId);
            double dps = k != null ? k.dps() : 2.0;
            double g = gap(agent, s.entity);
            danger += dps / Math.max(1.0, g / 4.0);
            if (isWindingUp(s.entity) && g < 5 && k != null && k.isExplosive()) {
                danger += 6;
            }
        }
        float hpFrac = agent.getHealth() / Math.max(1f, agent.getMaxHealth());
        double capacity = (Equipment.attackDamage(agent.getMainHandItem()) + agent.getArmorValue() / 4.0) * (0.5 + hpFrac);
        if (threats.size() >= 4 || danger > capacity * 2.0 || hpFrac <= 0.35f) {
            return 2;
        }
        return 1;
    }

    public static boolean hasFood(Player agent) {
        for (ItemStack s : agent.getInventory().items) {
            if (Equipment.foodScore(agent, s) > 0 || s.is(net.minecraft.world.item.Items.CAKE)) {
                return true;
            }
        }
        return cakeNearby(agent, 5) != null;
    }

    /** A cake standing within {@code r} blocks. */
    public static net.minecraft.core.BlockPos cakeNearby(Player agent, int r) {
        net.minecraft.core.BlockPos feet = agent.blockPosition();
        for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(feet.offset(-r, -1, -r), feet.offset(r, 1, r))) {
            if (agent.level().getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.CakeBlock) {
                return p.immutable();
            }
        }
        return null;
    }

    /** Nearest thing to pick up: dropped items, and thrown tridents / arrows that can be collected. */
    public static Entity nearestItem(Perception observer, Player agent, long now, double radius) {
        Entity best = null;
        double bestD = radius;
        for (Perception.Seen s : observer.remembered()) {
            boolean pickable = s.entity instanceof ItemEntity item ? !item.hasPickUpDelay() : Perception.isRetrievable(s.entity);
            if (pickable && !s.entity.isRemoved() && now - s.lastSeen <= 100) {
                Entity item = s.entity;
                double d = s.pos.distanceTo(agent.position());
                if (d < bestD) {
                    bestD = d;
                    best = item;
                }
            }
        }
        return best;
    }

    public static Perception.Seen nearestAlly(Perception observer, Player agent, Player observerSelf, long now, double radius) {
        Perception.Seen best = null;
        double bestD = radius;
        for (Perception.Seen s : observer.remembered()) {
            if (s.entity == agent || !isAllyOf(s.entity, agent) || now - s.lastSeen > 100) {
                continue;
            }
            double d = s.pos.distanceTo(agent.position());
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    public static Perception.Seen nearestAnimal(Perception observer, Player agent, long now, double radius) {
        Perception.Seen best = null;
        double bestD = radius;
        for (Perception.Seen s : observer.remembered()) {
            if (!isFoodAnimal(s.entity) || now - s.lastSeen > 60) {
                continue;
            }
            double d = s.pos.distanceTo(agent.position());
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    public static BlockPos nearestBlock(Perception observer, Player agent, Perception.BlockKind kind, double radius) {
        return nearestBlock(observer, agent, kind, radius, p -> true);
    }

    public static BlockPos nearestBlock(Perception observer, Player agent, Perception.BlockKind kind, double radius,
                                        java.util.function.Predicate<BlockPos> allowed) {
        BlockPos best = null;
        double bestD = radius * radius;
        boolean breaking = Config.get(Config.ALLOW_BLOCK_BREAKING, true);
        if (!breaking && (kind == Perception.BlockKind.LOG || kind == Perception.BlockKind.ORE)) {
            return null;
        }
        for (Map.Entry<BlockPos, Perception.BlockKind> e : observer.blocks().entrySet()) {
            if (e.getValue() != kind) {
                continue;
            }
            BlockPos p = e.getKey();
            double d = p.distToCenterSqr(agent.position());
            if (kind == Perception.BlockKind.STONE && p.getY() < agent.getBlockY()) {
                d += 64; // rock at eye level before digging into the ground under us
            }
            if (d >= bestD || !allowed.test(p)) {
                continue;
            }
            if (kind == Perception.BlockKind.ORE && !Equipment.canHarvest(agent, agent.level().getBlockState(p))) {
                continue;
            }
            bestD = d;
            best = p;
        }
        return best;
    }

    public static boolean isDark(Player agent) {
        return agent.level().getMaxLocalRawBrightness(agent.blockPosition()) < 7;
    }

    /**
     * @param observerSelf the observing clone; when describing another agent the observer itself counts as an ally
     */
    public static int strategyState(Perception observer, Player agent, Player observerSelf, Brain brain, long now) {
        float hpFrac = agent.getHealth() / Math.max(1f, agent.getMaxHealth());
        int hp = hpFrac <= 0.35f ? 0 : hpFrac <= 0.7f ? 1 : 2;
        int foodLevel = agent.getFoodData().getFoodLevel();
        int food = foodLevel <= 6 ? 0 : foodLevel <= 16 ? 1 : 2;
        int threat = threatLevel(observer, agent, brain, now);
        boolean items = nearestItem(observer, agent, now, 16) != null;
        boolean resource = nearestBlock(observer, agent, Perception.BlockKind.LOG, 32) != null
                || nearestBlock(observer, agent, Perception.BlockKind.ORE, 24) != null;
        boolean ally = nearestAlly(observer, agent, observerSelf, now, 48) != null
                || (observerSelf != agent && observerSelf.distanceTo(agent) < 48);
        boolean animals = nearestAnimal(observer, agent, now, 24) != null;
        return StrategyState.encode(hp, food, threat, hasFood(agent), items, resource, ally, isDark(agent), Equipment.isArmed(agent), animals);
    }

    public static int strategyMask(Perception observer, Player agent, Player observerSelf, long now) {
        int mask = Option.EXPLORE.bit() | Option.REST.bit();
        if (!threats(observer, agent, now, 24).isEmpty()) {
            mask |= Option.FIGHT.bit() | Option.FLEE.bit();
        }
        if (hasFood(agent) && agent.getFoodData().getFoodLevel() < 20) {
            mask |= Option.EAT.bit();
        }
        if (nearestItem(observer, agent, now, 16) != null) {
            mask |= Option.COLLECT.bit();
        }
        Perception.Seen ally = nearestAlly(observer, agent, observerSelf, now, 64);
        if (ally != null && ally.pos.distanceTo(agent.position()) > 5) {
            mask |= Option.FOLLOW.bit();
        }
        if (nearestBlock(observer, agent, Perception.BlockKind.LOG, 32) != null) {
            mask |= Option.GATHER_WOOD.bit();
        }
        if (nearestBlock(observer, agent, Perception.BlockKind.ORE, 24) != null) {
            mask |= Option.MINE.bit();
        }
        if (nearestAnimal(observer, agent, now, 24) != null) {
            mask |= Option.HUNT.bit();
        }
        // never while swimming: a crafting table cannot be put on water
        boolean craft = agent.onGround() && !agent.isInWater() && (agent instanceof com.rlclones.clone.ClonePlayer c ? c.controller().crafting().hasWork()
                : agent instanceof ServerPlayer sp && Crafting.plan(sp) != null);
        if (craft) {
            mask |= Option.CRAFT.bit();
        }
        if (agent instanceof com.rlclones.clone.ClonePlayer c) {
            mask |= c.controller().extraOptions(now);
        }
        return mask;
    }
}
