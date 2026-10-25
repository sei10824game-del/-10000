package com.rlclones.ai;

import com.rlclones.clone.Bases;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

/**
 * In a dug staircase with melee monsters coming down it from above: one block is put into the head cell of the next step up, so the step is one block
 * high (a zombie does not fit) and only the feet of whatever comes show; the usual fight takes them there. When nothing hostile has been about for a
 * while the block is broken again. ponytail: the block goes in the cell above the next step's feet, no check that anything is left to hit from here
 * (the fight gives up on what it cannot reach); skeletons and creepers are left to the fight / flee layers.
 */
public final class StairGuard {
    private final ClonePlayer self;
    private final Motor motor;
    private BlockPos block;
    private long quietSince;
    private long cooldownUntil;
    /** Blocks put in the way / taken out again (tests, soak). */
    public int placed;
    public int removed;

    public StairGuard(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    private ServerLevel level() {
        return self.serverLevel();
    }

    private boolean melee(LivingEntity e) {
        return e instanceof Monster && !(e instanceof Creeper) && !(e instanceof net.minecraft.world.entity.monster.AbstractSkeleton)
                && !(e instanceof net.minecraft.world.entity.monster.Ghast) && e.isAlive();
    }

    private int comingDown(BlockPos feet, Direction up) {
        int n = 0;
        for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, new AABB(feet).inflate(7, 6, 7), this::melee)) {
            double dx = e.getX() - self.getX();
            double dz = e.getZ() - self.getZ();
            if (e.getY() > self.getY() + 0.8 && dx * up.getStepX() + dz * up.getStepZ() > 0.5) {
                n++; // above us and up the stairs
            }
        }
        return n;
    }

    /** True while busy with the block (the caller leaves the clone to it). */
    public boolean tick(long now) {
        BlockPos feet = self.blockPosition();
        if (block != null) {
            if (!level().getBlockState(block).isSolid() || block.distSqr(feet) > 100) {
                block = null;
                return false;
            }
            boolean hostile = !level().getEntitiesOfClass(LivingEntity.class, new AABB(block).inflate(10), this::melee).isEmpty();
            if (hostile) {
                quietSince = now;
                return false;
            }
            if (now - quietSince < 120) {
                return false;
            }
            Equipment.select(self, Equipment.bestToolSlot(self, level().getBlockState(block)));
            if (motor.mine(block)) {
                removed++;
                block = null;
            }
            return true; // (stands still while it breaks it)
        }
        if (now < cooldownUntil || (now + self.getId()) % 5 != 0 || level().canSeeSky(feet.above()) || !self.onGround()
                || !com.rlclones.Config.get(com.rlclones.Config.ALLOW_BLOCK_PLACING, true)) {
            return false;
        }
        Bases.Staircase s = Bases.get(self.getServer()).nearestStaircase(level().dimension(), self.position(), 6);
        if (s == null || feet.getY() > s.top.getY() - 2) {
            return false;
        }
        Direction up = s.dir.getOpposite();
        if (comingDown(feet, up) < 1) {
            return false;
        }
        BlockPos cell = feet.relative(up).above(2);
        int slot = Equipment.pillarBlockSlot(self);
        if (slot < 0 || !level().getBlockState(cell).isAir()) {
            cooldownUntil = now + 100;
            return false;
        }
        Equipment.select(self, slot);
        if (motor.placeBlockAt(cell)) {
            block = cell.immutable();
            quietSince = now;
            placed++;
            cooldownUntil = now + 40;
        } else {
            cooldownUntil = now + 40;
        }
        return false; // the fight carries on (and hits the feet)
    }

    @Nullable
    public BlockPos block() {
        return block;
    }
}
