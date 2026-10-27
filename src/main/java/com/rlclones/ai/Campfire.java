package com.rlclones.ai;

import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Raw food in the bag and a campfire (it needs no fuel): laid on it, four at a time, and the clone stays by it until it is done. With no campfire about
 * but one in the bag, it is put down on the spot; the crafting makes one (3 sticks, coal, 3 logs) when there is raw food and nothing to cook it on. A
 * campfire is preferred to a furnace for food (nothing to burn). ponytail: a campfire within 16 blocks; four items per round, 700 ticks of waiting, the
 * cooked food falls out by the fire and is picked up as the clone stands there; add a furnace fallback for ore if it ever matters.
 */
public final class Campfire {
    private enum Stage {IDLE, GO, LOAD, WAIT}

    private final ClonePlayer self;
    private final Motor motor;
    private Stage stage = Stage.IDLE;
    private BlockPos fire;
    private int ticks;
    private int loaded;
    private long waitUntil;
    private long cooldownUntil;
    /** Items laid on a fire (tests, soak). */
    public int cooked;
    public String debug = "";

    public Campfire(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    private ServerLevel level() {
        return self.serverLevel();
    }

    /** Something a campfire cooks: raw meat / fish / potato. */
    public static boolean rawFood(net.minecraft.world.entity.player.Player p, ItemStack s) {
        if (s.isEmpty()) {
            return false;
        }
        return p.level().getRecipeManager().getRecipeFor(RecipeType.CAMPFIRE_COOKING, new net.minecraft.world.SimpleContainer(s.copy()), p.level()).isPresent();
    }

    public static boolean hasRaw(net.minecraft.world.entity.player.Player p) {
        for (ItemStack s : p.getInventory().items) {
            if (rawFood(p, s)) {
                return true;
            }
        }
        return false;
    }

    private int rawSlot() {
        for (int i = 0; i < self.getInventory().items.size(); i++) {
            if (rawFood(self, self.getInventory().items.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** A lit campfire within 16 blocks (nearest), else null. */
    @Nullable
    public BlockPos existing() {
        BlockPos c = self.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-16, -4, -16), c.offset(16, 4, 16))) {
            var st = level().getBlockState(p);
            if (st.getBlock() instanceof CampfireBlock && st.getValue(CampfireBlock.LIT)) {
                double d = p.distSqr(c);
                if (d < bestD) {
                    bestD = d;
                    best = p.immutable();
                }
            }
        }
        return best;
    }

    private boolean hurry;

    /** Hungry with nothing but raw food in the bag: no waiting for the next look - the fire (put down from the bag, or the one near) is used now. */
    public void hurry() {
        hurry = true;
        cooldownUntil = 0;
    }

    /** A campfire in the bag, or a lit one within 16 blocks. */
    public boolean available() {
        for (ItemStack st : self.getInventory().items) {
            if (st.is(Items.CAMPFIRE)) {
                return true;
            }
        }
        return existing() != null;
    }

    /** True while busy with a fire. */
    public boolean tick(long now, boolean threatened) {
        if (stage == Stage.IDLE) {
            if (now < cooldownUntil || threatened || (!hurry && (now + self.getId()) % 40 != 0) || !self.onGround() || self.isInWater() || rawSlot() < 0) {
                return false;
            }
            fire = existing();
            if (fire == null) {
                fire = putDown();
                if (fire == null) {
                    cooldownUntil = now + 400; // nothing to lay a fire with (the crafting makes one when it can)
                    return false;
                }
            }
            stage = Stage.GO;
            hurry = false;
            ticks = 0;
            loaded = 0;
        }
        if (threatened || ++ticks > 900 || !(level().getBlockState(fire).getBlock() instanceof CampfireBlock)) {
            stage = Stage.IDLE;
            cooldownUntil = now + 600;
            return false;
        }
        Vec3 c = Vec3.atCenterOf(fire);
        switch (stage) {
            case GO -> {
                if (self.getEyePosition().distanceTo(c) > 3.2) {
                    motor.navigate(Vec3.atBottomCenterOf(fire), 2.0, false);
                    if (motor.stuckCount() > 6) {
                        stage = Stage.IDLE;
                        cooldownUntil = now + 1200;
                        return false;
                    }
                    return true;
                }
                stage = Stage.LOAD;
            }
            case LOAD -> {
                motor.stop();
                int slot = rawSlot();
                if (slot < 0 || loaded >= 4) {
                    waitUntil = now + 700;
                    stage = Stage.WAIT;
                    return true;
                }
                if (ticks % 6 != 0) {
                    return true;
                }
                Equipment.select(self, slot);
                motor.lookAt(c.add(0, 0.3, 0));
                var hand = self.getMainHandItem();
                int before = hand.getCount();
                self.gameMode.useItemOn(self, level(), hand, InteractionHand.MAIN_HAND, new BlockHitResult(c.add(0, 0.4, 0), Direction.UP, fire, false));
                self.swing(InteractionHand.MAIN_HAND);
                if (self.getInventory().getItem(self.getInventory().selected).getCount() < before || self.getInventory().getItem(self.getInventory().selected).isEmpty()) {
                    loaded++;
                    cooked++;
                } else {
                    loaded = 4; // would not take it (full)
                }
            }
            case WAIT -> {
                if (self.getEyePosition().distanceTo(c) > 2.2) {
                    motor.navigate(Vec3.atBottomCenterOf(fire), 1.5, false);
                } else {
                    motor.stop();
                }
                if (now >= waitUntil) {
                    stage = Stage.IDLE;
                    cooldownUntil = now + 200;
                    return false;
                }
            }
            default -> {
            }
        }
        return true;
    }

    /** A campfire from the bag put down on the ground ahead; its position, or null. */
    @Nullable
    private BlockPos putDown() {
        int slot = -1;
        for (int i = 0; i < self.getInventory().items.size(); i++) {
            if (self.getInventory().items.get(i).is(Items.CAMPFIRE)) {
                slot = i;
                break;
            }
        }
        if (slot < 0 || !com.rlclones.Config.get(com.rlclones.Config.ALLOW_BLOCK_PLACING, true)) {
            return null;
        }
        Direction face = self.getDirection();
        BlockPos feet = self.blockPosition();
        for (BlockPos cell : new BlockPos[]{feet.relative(face, 2), feet.relative(face.getClockWise(), 2), feet.relative(face.getCounterClockWise(), 2)}) {
            if (level().getBlockState(cell).isAir() && level().getBlockState(cell.below()).isFaceSturdy(level(), cell.below(), Direction.UP)) {
                Equipment.select(self, slot);
                if (motor.placeBlockAt(cell) && level().getBlockState(cell).getBlock() instanceof CampfireBlock) {
                    return cell;
                }
            }
        }
        return null;
    }
}
