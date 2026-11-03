package com.rlclones.ai;

import com.rlclones.clone.BodyTraits;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * A clone mob whose body burns in sunlight (zombie, skeleton...) or is hurt by rain (enderman...) goes under a roof while that lasts.
 * ponytail: it does not take the exploring options off the menu in the meantime (plan.md 73.2); it just walks back under cover each time.
 */
public final class Shade {
    private final ClonePlayer self;
    private final Motor motor;
    private BlockPos goal;
    private long nextSearch;

    public Shade(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    /** Whether the sky is hurting this body here. */
    public static boolean exposed(ClonePlayer p) {
        BodyTraits t = BodyTraits.of(p);
        if (t == BodyTraits.PLAYER || !(p.level() instanceof ServerLevel l) || p.isInWater()) {
            return false;
        }
        net.minecraft.world.entity.Mob body = com.rlclones.clone.CloneMobs.bodyOf(p);
        if (t.sun() && body != null && !body.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty()) {
            return false; // a helmet: the sun does nothing to it (for the water-hurt: still rain)
        }
        BlockPos head = p.blockPosition().above((int) Math.ceil(p.getBbHeight()) - 1);
        return l.canSeeSky(head) && ((t.sun() && l.isDay() && !l.isRaining()) || (t.water() && l.isRaining()));
    }

    /** Walk to cover while the sky hurts; true = busy with it this tick. */
    public boolean tick(long now) {
        if (!exposed(self)) {
            goal = null;
            return false;
        }
        ServerLevel level = self.serverLevel();
        if (goal == null && now >= nextSearch) {
            nextSearch = now + 100;
            goal = find(level);
        }
        if (goal == null) {
            return false;
        }
        if (self.blockPosition().distSqr(goal) <= 2 || motor.stuckCount() > 8) {
            goal = null;
            return false;
        }
        motor.navigate(Vec3.atBottomCenterOf(goal), 1.0, true);
        return true;
    }

    private BlockPos find(ServerLevel level) {
        BlockPos me = self.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(me.offset(-12, -2, -12), me.offset(12, 2, 12))) {
            double d = p.distSqr(me);
            if (d >= bestD || level.canSeeSky(p.above(1)) || !level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                    || !level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                    || level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty() || !level.getFluidState(p).isEmpty()) {
                continue;
            }
            best = p.immutable();
            bestD = d;
        }
        return best;
    }
}
