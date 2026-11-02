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
        return b instanceof net.minecraft.world.entity.monster.Guardian ? "beam" : b instanceof net.minecraft.world.entity.monster.Ghast ? "ghast"
                : b instanceof net.minecraft.world.entity.monster.warden.Warden ? "sonic" : b instanceof Creeper ? "explode" : b instanceof Blaze ? "fireball" : b instanceof EnderMan ? "teleport" : b instanceof Evoker ? "fangs"
                : b instanceof RangedAttackMob ? "ranged" : null;
    }

    private static String key(int slot) {
        return "rlclones_sp" + slot;
    }

    /**
     * Worth using right now: the attack is ready (or already charging), the target is in its reach and in view. Not for the creeper's
     * fuse or the enderman's jump (those are for the learning to place).
     */
    public static boolean ready(Player soul, Entity target, int slot) {
        Mob b = CloneMobs.bodyOf(soul);
        String kind = b == null ? null : kind(b, slot);
        if (kind == null || kind.equals("explode") || kind.equals("teleport") || !(target instanceof LivingEntity t)
                || !(CHARGES.containsKey(b.getUUID()) || available(soul, slot))) {
            return false;
        }
        double d = b.distanceTo(t);
        double max = switch (kind) {
            case "beam", "sonic" -> 15;
            case "ghast" -> 30;
            case "fireball", "ranged" -> 16;
            case "fangs" -> 12;
            default -> 24;
        };
        return d <= max && d >= (kind.equals("ranged") ? 3 : 0) && (kind.equals("vex") || b.hasLineOfSight(t));
    }

    /** This body has the attack and it is not cooling down. */
    public static boolean available(Player p, int slot) {
        Mob b = CloneMobs.bodyOf(p);
        return b != null && kind(b, slot) != null && p.level().getGameTime() >= b.getPersistentData().getLong(key(slot));
    }

    public static final int NO = 0;
    public static final int DONE = 1;
    /** A charging attack (guardian beam, ghast fireball, warden sonic boom) is under way: stand and keep facing the target. */
    public static final int BUSY = 2;

    /** Charging attacks in progress: body -> (target, start tick). */
    private static final java.util.Map<java.util.UUID, long[]> CHARGES = new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID, java.util.UUID> CHARGE_TARGET = new java.util.HashMap<>();

    /** Ticks a charging attack needs: guardian beam 80 (the vanilla attack duration), ghast fireball 20, warden sonic boom 34; range. */
    private static int windup(String kind) {
        return kind.equals("beam") ? 80 : kind.equals("ghast") ? 20 : 34;
    }

    private static double reach(String kind) {
        return kind.equals("beam") ? 15 : kind.equals("ghast") ? 30 : 15;
    }

    /**
     * The attack in {@code slot} on {@code target}: {@link #DONE} (fired), {@link #BUSY} (charging: call again every tick while the
     * target stays in view and in reach) or {@link #NO} (not possible from here).
     */
    public static int act(com.rlclones.clone.ClonePlayer soul, Entity target, int slot) {
        Mob b = CloneMobs.bodyOf(soul);
        if (b == null || !(target instanceof LivingEntity t) || !(b.level() instanceof ServerLevel level) || kind(b, slot) == null) {
            return NO;
        }
        String kind = kind(b, slot);
        if (!kind.equals("beam") && !kind.equals("ghast") && !kind.equals("sonic")) {
            return use(soul, target, slot) ? DONE : NO;
        }
        java.util.UUID id = b.getUUID();
        long now = level.getGameTime();
        if (!CHARGES.containsKey(id) && !available(soul, slot)) {
            return NO;
        }
        if (b.distanceTo(t) > reach(kind) || !b.hasLineOfSight(t) || (CHARGE_TARGET.containsKey(id) && !CHARGE_TARGET.get(id).equals(t.getUUID()))) {
            CHARGES.remove(id);
            CHARGE_TARGET.remove(id);
            return NO; // out of reach / out of sight / another target: the charge is lost
        }
        long[] start = CHARGES.computeIfAbsent(id, k -> new long[]{now});
        CHARGE_TARGET.put(id, t.getUUID());
        if (now - start[0] == 1) {
            level.playSound(null, b.blockPosition(), kind.equals("sonic") ? net.minecraft.sounds.SoundEvents.WARDEN_SONIC_CHARGE : kind.equals("ghast")
                    ? net.minecraft.sounds.SoundEvents.GHAST_WARN : net.minecraft.sounds.SoundEvents.GUARDIAN_ATTACK, b.getSoundSource(), 1f, 1f);
        }
        if (now - start[0] < windup(kind)) {
            return BUSY;
        }
        CHARGES.remove(id);
        CHARGE_TARGET.remove(id);
        long cooldown = 100;
        switch (kind) {
            case "beam" -> {
                float f = 1f + (level.getDifficulty() == net.minecraft.world.Difficulty.HARD ? 2f : 0f) + (b instanceof net.minecraft.world.entity.monster.ElderGuardian ? 2f : 0f);
                t.hurt(b.damageSources().indirectMagic(b, b), f);
                t.hurt(b.damageSources().mobAttack(b), (float) b.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE));
                cooldown = 60;
            }
            case "ghast" -> {
                Vec3 dir = t.getEyePosition().subtract(b.getEyePosition()).normalize();
                var fb = new net.minecraft.world.entity.projectile.LargeFireball(level, b, dir.x, dir.y, dir.z, 1);
                fb.setPos(b.getX() + dir.x * 2, b.getY(0.5) + 0.5, b.getZ() + dir.z * 2);
                level.addFreshEntity(fb);
                cooldown = 60;
            }
            default -> { // the warden's sonic boom: 10 damage that armour does not stop, and a push
                t.hurt(b.damageSources().sonicBoom(b), 10f);
                Vec3 away = t.position().subtract(b.position()).normalize();
                t.push(away.x * 2.5, 0.5, away.z * 2.5);
                level.playSound(null, b.blockPosition(), net.minecraft.sounds.SoundEvents.WARDEN_SONIC_BOOM, b.getSoundSource(), 3f, 1f);
                cooldown = 80;
            }
        }
        b.getPersistentData().putLong(key(slot), now + cooldown);
        return DONE;
    }

    /** Use an instant attack on {@code target}; false = not possible from here (too far, no line of sight...). */
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
