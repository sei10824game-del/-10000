package com.rlclones.ai;

import com.rlclones.clone.ClonePlayer;
import com.rlclones.clone.CloneManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;

import javax.annotation.Nullable;

/**
 * Where a clone comes back after a death: with respawn ON it goes to a bed near the base and right-clicks it (a player's way of setting the
 * respawn point, daytime too), once; it does not sleep (hundreds of clones cannot all go to bed at night). With respawn OFF there is no use for it.
 * ponytail: a bed within 16 blocks of where the clone is; the nearest, the head part; walking there is the ordinary navigation; no sleeping.
 */
public final class Bedding {
    private final ClonePlayer self;
    private final Motor motor;
    private BlockPos bed;
    private int ticks;
    private long cooldownUntil;
    /** Beds the respawn point was set at (tests, soak). */
    public int set;

    public Bedding(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    private boolean respawnOn() {
        CloneManager m = CloneManager.peek();
        return m != null && m.isRespawnEnabled();
    }

    @Nullable
    private BlockPos findBed() {
        ServerLevel level = self.serverLevel();
        BlockPos c = self.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-16, -3, -16), c.offset(16, 3, 16))) {
            var st = level.getBlockState(p);
            if (st.is(BlockTags.BEDS) && st.getBlock() instanceof BedBlock && st.getValue(BedBlock.PART) == BedPart.HEAD) {
                BlockPos at = p.immutable();
                BlockPos spawn = self.getRespawnPosition();
                if (spawn != null && spawn.distManhattan(at) <= 2 && self.getRespawnDimension() == level.dimension()) {
                    return null; // already set here
                }
                return at;
            }
        }
        return null;
    }

    private boolean bedItemInBag() {
        for (var st : self.getInventory().items) {
            if (st.is(net.minecraft.tags.ItemTags.BEDS)) {
                return true;
            }
        }
        return false;
    }

    private boolean woolInBag() {
        int n = 0;
        for (var st : self.getInventory().items) {
            if (st.is(net.minecraft.tags.ItemTags.WOOL)) {
                n += st.getCount();
            }
        }
        return n >= 3;
    }

    private boolean bedAround() {
        BlockPos c = self.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-16, -3, -16), c.offset(16, 3, 16))) {
            if (self.serverLevel().getBlockState(p).is(BlockTags.BEDS)) {
                return true;
            }
        }
        return false;
    }

    /** Respawn on, a base close by with no bed about, three wool in the bag and no bed yet: the crafting makes one. */
    public boolean bedWanted() {
        return respawnOn() && !bedItemInBag() && woolInBag() && nearBase() && !bedAround();
    }

    private boolean nearBase() {
        return com.rlclones.clone.Bases.get(self.getServer()).nearest(self.level().dimension(), self.position(), 24) != null;
    }

    private long placeCooldown;

    /** A bed from the bag put down on the ground two blocks ahead (the respawn point is set at it by the usual walk). */
    private boolean placeBed(long now) {
        if (now < placeCooldown || !bedItemInBag() || !respawnOn() || !nearBase() || bedAround() || !self.onGround()
                || !com.rlclones.Config.get(com.rlclones.Config.ALLOW_BLOCK_PLACING, true)) {
            return false;
        }
        placeCooldown = now + 600;
        int slot = -1;
        for (int i = 0; i < self.getInventory().items.size(); i++) {
            if (self.getInventory().items.get(i).is(net.minecraft.tags.ItemTags.BEDS)) {
                slot = i;
                break;
            }
        }
        Direction face = self.getDirection();
        for (Direction d : new Direction[]{face, face.getClockWise(), face.getCounterClockWise(), face.getOpposite()}) {
            BlockPos floor = self.blockPosition().relative(d, 2).below();
            BlockPos cell = floor.above();
            if (self.serverLevel().getBlockState(floor).isFaceSturdy(self.serverLevel(), floor, Direction.UP) && self.serverLevel().getBlockState(cell).isAir()
                    && self.serverLevel().getBlockState(cell.relative(d)).isAir()) {
                Equipment.select(self, slot);
                motor.lookAt(Vec3.atCenterOf(floor).add(0, 0.5, 0));
                self.setYRot(d.toYRot()); // (a bed lies the way the placer faces)
                self.gameMode.useItemOn(self, self.serverLevel(), self.getMainHandItem(), InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));
                return true;
            }
        }
        return false;
    }

    /** True while busy with a bed. */
    public boolean tick(long now, boolean threatened) {
        if (bed == null && !threatened && placeBed(now)) {
            cooldownUntil = 0; // (the walk to the new bed follows)
            return true;
        }
        if (bed == null) {
            if (now < cooldownUntil || threatened || (now + self.getId()) % 200 != 0 || !respawnOn()) {
                return false;
            }
            bed = findBed();
            if (bed == null) {
                cooldownUntil = now + 2400;
                return false;
            }
            ticks = 0;
        }
        if (threatened || ++ticks > 400 || !self.serverLevel().getBlockState(bed).is(BlockTags.BEDS)) {
            bed = null;
            cooldownUntil = now + 2400;
            return false;
        }
        Vec3 c = Vec3.atCenterOf(bed);
        if (self.getEyePosition().distanceTo(c) > 3.0) {
            motor.navigate(c, 1.5, false);
            if (motor.stuckCount() > 6) {
                bed = null;
                cooldownUntil = now + 2400;
                return false;
            }
            return true;
        }
        motor.stop();
        motor.lookAt(c);
        // right-click it: setting the respawn point comes first (the answer, "you can only sleep at night", is of no interest)
        self.gameMode.useItemOn(self, self.serverLevel(), self.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(c, Direction.UP, bed, false));
        self.swing(InteractionHand.MAIN_HAND);
        if (self.isSleeping()) {
            self.stopSleeping(); // (at night it could not be helped: out again)
        }
        set++;
        bed = null;
        cooldownUntil = now + 24000;
        return false;
    }
}
