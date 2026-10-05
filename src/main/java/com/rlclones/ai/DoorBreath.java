package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.Vec3;

/**
 * R-23: low on air with the surface too far: put a door down on the floor next to us. A door does not hold water, so its
 * two cells are air; step in, breathe, break it again from inside and take it back.
 */
public final class DoorBreath {
    private static final int PLACE = 1, INSIDE = 2, BREAK = 4, PICKUP = 5;
    private final ClonePlayer self;
    private final Motor motor;
    private int stage;
    private BlockPos door;
    private int ticks;
    private long cooldownUntil;
    /** Doors put down for a breath (tests). */
    public int doorsPlaced;

    public DoorBreath(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    public boolean busy() {
        return stage != 0;
    }

    private int doorSlot() {
        Inventory inv = self.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).is(ItemTags.WOODEN_DOORS)) {
                return i;
            }
        }
        return -1;
    }

    /** Water above the head: how far is the surface? */
    private int depth() {
        ServerLevel level = self.serverLevel();
        BlockPos p = BlockPos.containing(self.getEyePosition());
        int d = 0;
        while (d < 40 && level.getFluidState(p.above(d)).is(net.minecraft.tags.FluidTags.WATER)) {
            d++;
        }
        return d;
    }

    /** @return true while it has the clone's hands. */
    public boolean tick(long now) {
        ServerLevel level = self.serverLevel();
        if (stage == 0) {
            if (now < cooldownUntil || !self.isInWater() || self.isPassenger() || self.isCreative() || doorSlot() < 0
                    || self.getAirSupply() >= self.getMaxAirSupply() * 0.4 || self.hasEffect(MobEffects.WATER_BREATHING)
                    || !Config.get(Config.ALLOW_BLOCK_PLACING, true) || depth() <= self.getAirSupply() / 8) {
                return false;
            }
            stage = PLACE;
            ticks = 0;
        }
        if (++ticks > 400) {
            reset(now);
            return false;
        }
        BlockPos feet = self.blockPosition();
        switch (stage) {
            case PLACE -> {
                if (!self.onGround()) {
                    motor.diveHard();
                    return true;
                }
                if (door == null) {
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos c = feet.relative(d);
                        if (level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP)
                                && level.getBlockState(c).canBeReplaced() && level.getBlockState(c.above()).canBeReplaced()) {
                            int before = self.getInventory().selected;
                            Equipment.select(self, doorSlot());
                            self.setYRot(d.toYRot());
                            self.setYHeadRot(d.toYRot());
                            motor.useOnTopFace(c.below());
                            if (before < Inventory.getSelectionSize()) {
                                self.getInventory().selected = before;
                            }
                            if (level.getBlockState(c).is(net.minecraft.tags.BlockTags.DOORS)) {
                                door = c;
                                doorsPlaced++;
                                stage = INSIDE;
                            }
                            return true;
                        }
                    }
                    reset(now); // no flat floor beside us
                    return false;
                }
            }
            case INSIDE -> {
                motor.diveHard();
                motor.moveToward(Vec3.atBottomCenterOf(door));
                if (self.getAirSupply() >= self.getMaxAirSupply() * 0.9) {
                    stage = BREAK; // broken from inside, in the dry: out in the water it takes 5x as long
                }
            }
            case BREAK -> {
                if (level.getBlockState(door).isAir() || !level.getBlockState(door).is(net.minecraft.tags.BlockTags.DOORS)) {
                    motor.resetMining();
                    stage = PICKUP;
                    ticks = 340; // a few seconds to pick it up
                } else {
                    motor.mine(door);
                }
            }
            default -> {
                ItemEntity item = level.getEntitiesOfClass(ItemEntity.class, self.getBoundingBox().inflate(4),
                        e -> e.getItem().is(ItemTags.WOODEN_DOORS)).stream().findFirst().orElse(null);
                if (item == null || ticks > 395) {
                    reset(now);
                    return false;
                }
                motor.diveHard();
                motor.moveToward(item.position());
            }
        }
        return true;
    }

    private void reset(long now) {
        motor.resetMining();
        stage = 0;
        door = null;
        cooldownUntil = now + 200;
    }
}
