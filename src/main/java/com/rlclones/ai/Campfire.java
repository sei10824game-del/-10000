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
 * campfire is preferred to a furnace for food (nothing to burn). Empty slots are refilled while raw food remains;
 * actual cooking completion is followed by a short pickup interval. Threats always interrupt cooking.
 */
public final class Campfire {
    private enum Stage {IDLE, GO, LOAD, WAIT}

    private final ClonePlayer self;
    private final Motor motor;
    private Stage stage = Stage.IDLE;
    private BlockPos fire;
    private int ticks;
    private int loaded;
    private int fails;
    private long waitUntil;
    private long cooldownUntil;
    /** Items laid on a fire (tests, soak). */
    public int cooked;
    public String debug = "";

    /** For /rlclone debug: where the cooking stands (stage, how many were loaded, the last note). */
    public String report() {
        return stage + " loaded=" + loaded + " raw in bag=" + (rawSlot() >= 0) + " " + debug;
    }

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
        return nearest(level(), self.blockPosition());
    }

    @Nullable
    public static BlockPos nearest(ServerLevel level, BlockPos c) {
        com.rlclones.ai.util.SectionPresence sections = null;
        int checked = 0;
        var cursor = new BlockPos.MutableBlockPos();
        for (var offset : FIRE_OFFSETS) {
            cursor.set(c.getX() + offset.x(), c.getY() + offset.y(), c.getZ() + offset.z());
            // Nearby fires need no palette setup. The expensive absent/distant case benefits from filtering.
            if (checked++ >= 32) {
                if (sections == null) sections = new com.rlclones.ai.util.SectionPresence(level,
                        c.offset(-16, -4, -16), c.offset(16, 4, 16),
                        state -> state.getBlock() instanceof CampfireBlock && state.getValue(CampfireBlock.LIT));
                if (!sections.mayContain(cursor)) continue;
            }
            var st = level.getBlockState(cursor);
            if (st.getBlock() instanceof CampfireBlock && st.getValue(CampfireBlock.LIT)) return cursor.immutable();
        }
        return null;
    }

    private static final java.util.List<com.rlclones.ai.util.NearbyOffsets.Offset> FIRE_OFFSETS =
            com.rlclones.ai.util.NearbyOffsets.ordered(16, 4);

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
            if (now < cooldownUntil || threatened || (!hurry && (now + self.getId()) % 10 != 0) || !self.onGround() || self.isInWater() || rawSlot() < 0) {
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
            waitUntil = 0;
            fails = 0;
        }
        var state = level().getBlockState(fire);
        if (threatened || (++ticks > 900 && stage != Stage.WAIT) || !(state.getBlock() instanceof CampfireBlock)
                || !state.getValue(CampfireBlock.LIT)) {
            stage = Stage.IDLE;
            cooldownUntil = now + 100; // (a threat passes: the fire is gone back to soon)
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
                ticks = 0;
            }
            case LOAD -> {
                motor.stop();
                int slot = rawSlot();
                int onFire = 0;
                if (level().getBlockEntity(fire) instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity be) {
                    for (ItemStack st : be.getItems()) {
                        if (!st.isEmpty()) {
                            onFire++;
                        }
                    }
                }
                if (slot < 0 || onFire >= 4) {
                    debug = "load done slot=" + slot + " loaded=" + loaded + " onFire=" + onFire;
                    waitUntil = Math.max(waitUntil, now + 700);
                    ticks = 0;
                    stage = Stage.WAIT;
                    return true;
                }
                if (ticks % 6 != 0) {
                    return true;
                }
                Equipment.select(self, slot);
                motor.lookAt(c.add(0, 0.3, 0));
                if (self.getEyePosition().distanceTo(c) > 3.2) {
                    motor.navigate(Vec3.atBottomCenterOf(fire), 1.5, false); // (pushed off the fire's side while loading: back to it)
                    return true;
                }
                var hand = self.getMainHandItem();
                int before = hand.getCount();
                int cookingTime = level().getRecipeManager().getRecipeFor(RecipeType.CAMPFIRE_COOKING,
                        new net.minecraft.world.SimpleContainer(hand.copy()), level())
                        .map(net.minecraft.world.item.crafting.CampfireCookingRecipe::getCookingTime).orElse(600);
                self.gameMode.useItemOn(self, level(), hand, InteractionHand.MAIN_HAND, new BlockHitResult(c.add(0, 0.4, 0), Direction.UP, fire, false));
                self.swing(InteractionHand.MAIN_HAND);
                if (self.getInventory().getItem(self.getInventory().selected).getCount() < before || self.getInventory().getItem(self.getInventory().selected).isEmpty()) {
                    loaded++;
                    cooked++;
                    fails = 0;
                    ticks = 0;
                    waitUntil = Math.max(waitUntil, now + cookingTime + 100L);
                } else if (++fails >= 4) {
                    debug = "refused " + hand.getItem() + " onFire=" + onFire;
                    stage = Stage.IDLE;
                    cooldownUntil = now + 20; // retry actual empty slots; refusal does not mean four items were loaded.
                    return false;
                }
            }
            case WAIT -> {
                int onFire = 0;
                if (level().getBlockEntity(fire) instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity be)
                    for (ItemStack stack : be.getItems()) if (!stack.isEmpty()) onFire++;
                if (onFire < 4 && rawSlot() >= 0) {
                    stage = Stage.LOAD;
                    ticks = 0;
                    fails = 0;
                    return true;
                }
                if (self.getEyePosition().distanceTo(c) > 2.2) {
                    motor.navigate(Vec3.atBottomCenterOf(fire), 1.5, false);
                } else {
                    motor.stop();
                }
                // Stay for actual cooking, then allow nearby drops to be picked up.
                if (onFire > 0) {
                    ticks = 0;
                } else if (ticks >= 40) {
                    stage = Stage.IDLE;
                    cooldownUntil = now + 40;
                    return false;
                }
                if (onFire > 0 && now >= waitUntil) {
                    stage = Stage.IDLE;
                    cooldownUntil = now + 100;
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
        if (!level().canSeeSky(feet.above())) {
            return null; // out in the open only: a fire put down in a cave or a tunnel is in somebody's way
        }
        for (BlockPos cell : new BlockPos[]{feet.relative(face, 2), feet.relative(face.getClockWise(), 2), feet.relative(face.getCounterClockWise(), 2)}) {
            if (self.getServer() != null && com.rlclones.clone.Bases.get(self.getServer()).isStairSpace(level().dimension(), cell, true)) {
                continue; // not in a staircase or its walls: the stair work walks through it and burns (two soak deaths at one campfire in a shaft)
            }
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
