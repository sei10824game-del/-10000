package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;

/**
 * Spawn-proofing: where the block light is 0 - in caves, inside, around a base - monsters can spawn, so the clone puts a
 * torch down where it stands. A torch lights about 13 blocks around, so walking on and doing it again wherever it is
 * dark lights a cave up step by step.
 */
public final class Lighting {
    private final ClonePlayer self;
    private final Motor motor;
    public int torchesPlaced;

    public Lighting(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    public static int torchSlot(ClonePlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).is(Items.TORCH)) {
                return i;
            }
        }
        return -1;
    }

    /** Would a monster be able to spawn right here (dark, under a roof or by a base)? */
    public boolean darkSpot() {
        ServerLevel level = self.serverLevel();
        BlockPos feet = self.blockPosition();
        if (level.getBrightness(LightLayer.BLOCK, feet) > 0) {
            return false;
        }
        boolean covered = level.getBrightness(LightLayer.SKY, feet) < 8;
        boolean home = com.rlclones.clone.Bases.get(self.getServer()).nearest(level.dimension(), self.position(), 24) != null;
        return covered || home;
    }

    /** Called every second when the clone is free: place a torch at the feet if it is dark here. */
    public boolean tick() {
        if (!self.onGround() || self.isInWater() || self.isPassenger() || !Config.get(Config.ALLOW_BLOCK_PLACING, true)) {
            return false;
        }
        int slot = torchSlot(self);
        if (slot < 0 || !darkSpot()) {
            return false;
        }
        ServerLevel level = self.serverLevel();
        BlockPos feet = self.blockPosition();
        BlockPos below = feet.below();
        if (!level.getBlockState(feet).canBeReplaced() || !level.getFluidState(feet).isEmpty()
                || !level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return false;
        }
        int before = self.getInventory().selected;
        Equipment.select(self, slot);
        if (motor.useOnTopFace(below) && level.getBlockState(feet).is(net.minecraft.world.level.block.Blocks.TORCH)) {
            torchesPlaced++;
        }
        if (before < Inventory.getSelectionSize()) {
            self.getInventory().selected = before;
        }
        return true;
    }

    private BlockPos proofSpot;

    /** R-26: more than 16 spare torches and resting by a base: walk to a dark spot round it (the tick above then puts one down). True while walking. */
    public boolean proofTick(long now) {
        if (torchSlot(self) < 0 || self.getInventory().countItem(Items.TORCH) <= 16 || !self.onGround() || !Config.get(Config.ALLOW_BLOCK_PLACING, true)) {
            proofSpot = null;
            return false;
        }
        ServerLevel level = self.serverLevel();
        if (proofSpot != null && level.getBrightness(LightLayer.BLOCK, proofSpot) > 0) {
            proofSpot = null; // lit meanwhile
        }
        if (proofSpot == null) {
            if ((now + self.getId()) % 100 != 0 || com.rlclones.clone.Bases.get(self.getServer()).nearest(level.dimension(), self.position(), 12) == null) {
                return false;
            }
            BlockPos feet = self.blockPosition();
            for (BlockPos p : BlockPos.betweenClosed(feet.offset(-8, -1, -8), feet.offset(8, 1, 8))) {
                if (level.getBrightness(LightLayer.BLOCK, p) == 0 && level.getBlockState(p).isAir() && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)
                        && level.getBrightness(LightLayer.SKY, p) < 8) {
                    proofSpot = p.immutable();
                    break;
                }
            }
            if (proofSpot == null) {
                return false;
            }
        }
        if (self.blockPosition().distSqr(proofSpot) <= 2) {
            proofSpot = null; // there: tick() puts the torch down
            return false;
        }
        motor.navigate(net.minecraft.world.phys.Vec3.atBottomCenterOf(proofSpot), 1.0, false);
        return true;
    }
}
