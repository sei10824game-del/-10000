package com.rlclones.ai;

import com.rlclones.clone.CloneMobs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.phys.Vec3;

/**
 * A clone mob's own attacks (plan.md 73.3): the combat actions SPECIAL / SPECIAL2 (learned like any other, in the combat Q table) call these.
 * ponytail: the Q table is shared by body kind (plan: one table per body kind), and arrows / fireballs / fangs are not yet credited back to the soul's reward.
 */
public final class Specials {
    private Specials() {
    }

    /** The attack in this slot for this body (null = none). Slot 0: the main one; slot 1: the evoker's vexes. */
    private static String kind(Mob b, int slot) {
        if (slot == 1) {
            return b instanceof Evoker ? "vex" : null;
        }
        return b instanceof Creeper ? "explode" : b instanceof Blaze ? "fireball" : b instanceof EnderMan ? "teleport" : b instanceof Evoker ? "fangs"
                : b instanceof RangedAttackMob ? "ranged" : null;
    }

    private static String key(int slot) {
        return "rlclones_sp" + slot;
    }

    /** This body has the attack and it is not cooling down. */
    public static boolean available(Player p, int slot) {
        Mob b = CloneMobs.bodyOf(p);
        return b != null && kind(b, slot) != null && p.level().getGameTime() >= b.getPersistentData().getLong(key(slot));
    }

    /** Use it on {@code target}; false = not possible from here (too far, no line of sight...). */
    public static boolean use(com.rlclones.clone.ClonePlayer soul, Entity target, int slot) {
        Mob b = CloneMobs.bodyOf(soul);
        if (b == null || !(target instanceof LivingEntity t) || !available(soul, slot) || !(b.level() instanceof ServerLevel level)) {
            return false;
        }
        String kind = kind(b, slot);
        double d = b.distanceTo(t);
        long now = level.getGameTime();
        long cooldown;
        switch (kind) {
            case "ranged" -> {
                if (d < 3 || d > 16 || !b.hasLineOfSight(t)) {
                    return false;
                }
                ((RangedAttackMob) b).performRangedAttack(t, 1f);
                cooldown = 40;
            }
            case "explode" -> {
                if (d > 3) {
                    return false;
                }
                ((Creeper) b).ignite();
                cooldown = 100000;
            }
            case "fireball" -> {
                if (d > 16 || !b.hasLineOfSight(t)) {
                    return false;
                }
                Vec3 dir = t.getEyePosition().subtract(b.getEyePosition()).normalize();
                for (int i = 0; i < 3; i++) {
                    SmallFireball f = new SmallFireball(level, b, dir.x + level.random.nextGaussian() * 0.05, dir.y, dir.z + level.random.nextGaussian() * 0.05);
                    f.setPos(f.getX(), b.getY(0.5) + 0.5, f.getZ());
                    level.addFreshEntity(f);
                }
                cooldown = 100;
            }
            case "teleport" -> {
                if (d < 4 || d > 24 || b.isInWater()) {
                    return false;
                }
                Vec3 behind = t.position().subtract(t.getLookAngle().multiply(1, 0, 1).normalize().scale(2));
                BlockPos p = BlockPos.containing(behind);
                if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty() || !level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                        || level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty()) {
                    return false;
                }
                soul.teleportTo(level, behind.x, (double) p.getY(), behind.z, soul.getYRot(), 0f);
                cooldown = 60;
            }
            case "fangs" -> {
                if (d > 12) {
                    return false;
                }
                Vec3 step = t.position().subtract(b.position()).multiply(1, 0, 1).normalize();
                for (int i = 1; i <= 5; i++) {
                    Vec3 at = b.position().add(step.scale(i * 1.5));
                    BlockPos g = BlockPos.containing(at);
                    level.addFreshEntity(new EvokerFangs(level, at.x, g.getY(), at.z, (float) Math.atan2(step.z, step.x), i * 2, b));
                }
                cooldown = 100;
            }
            case "vex" -> {
                long mine = level.getEntitiesOfClass(Vex.class, b.getBoundingBox().inflate(32), v -> v.getOwner() == b).size();
                for (int i = 0; i < 3 && mine + i < 6; i++) {
                    Vex v = EntityType.VEX.create(level);
                    v.moveTo(b.getX() + level.random.nextInt(5) - 2, b.getY() + 0.5, b.getZ() + level.random.nextInt(5) - 2, 0f, 0f);
                    v.setOwner(b);
                    v.setBoundOrigin(b.blockPosition());
                    v.setLimitedLife(20 * (30 + level.random.nextInt(90)));
                    level.addFreshEntity(v);
                }
                cooldown = 340;
            }
            default -> {
                return false;
            }
        }
        b.getPersistentData().putLong(key(slot), now + cooldown);
        return true;
    }
}
