package com.rlclones.ai;

import com.rlclones.ai.brain.Brain;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.brewing.BrewingRecipeRegistry;

import javax.annotation.Nullable;

/**
 * Brewing like a player: uses a brewing stand nearby (or places its own), fills glass bottles with water, puts in
 * blaze powder, bottles and one ingredient, comes back when the brew is done and takes the potions out. It keeps
 * brewing potions it has never had until it has them (then {@link Discovery} learns what they do).
 */
public final class Brewing {
    public enum Status {WORKING, DONE, FAILED}

    private static final int BREW_TICKS = 400;

    private final ClonePlayer self;
    private final Motor motor;
    private final Perception perception;

    @Nullable
    private BlockPos stand;
    private long readyAt = -1;
    private int stage;
    private int ticks;
    private int tries;

    public int brews;
    public int collected;
    public int bottlesFilled;
    public int standsPlaced;

    public Brewing(ClonePlayer self, Motor motor, Perception perception) {
        this.self = self;
        this.motor = motor;
        this.perception = perception;
    }

    // ================================================================== planning

    private static boolean isBottle(ItemStack s) {
        return s.getItem() instanceof PotionItem;
    }

    private int count(net.minecraft.world.item.Item item) {
        return self.getInventory().countItem(item);
    }

    /** {bottle slot, ingredient slot} of a brew giving a potion this clone never had, or null. */
    @Nullable
    public int[] plan() {
        Brain b = self.getCloneBrain();
        if (b == null) {
            return null;
        }
        Inventory inv = self.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack bottle = inv.items.get(i);
            if (!isBottle(bottle)) {
                continue;
            }
            for (int j = 0; j < inv.items.size(); j++) {
                ItemStack ing = inv.items.get(j);
                if (j == i || ing.isEmpty() || isBottle(ing) || !BrewingRecipeRegistry.isValidIngredient(ing)) {
                    continue;
                }
                ItemStack out = BrewingRecipeRegistry.getOutput(bottle, ing);
                if (!out.isEmpty() && !b.obtained(Discovery.keyOf(out))) {
                    return new int[]{i, j};
                }
            }
        }
        return null;
    }

    @Nullable
    private BlockPos nearestStand() {
        return Senses.nearestBlock(perception, self, Perception.BlockKind.BREWING, 24);
    }

    private boolean standAvailable() {
        return nearestStand() != null || count(Items.BREWING_STAND) > 0;
    }

    private boolean hasFuel() {
        if (count(Items.BLAZE_POWDER) > 0) {
            return true;
        }
        BlockPos s = nearestStand();
        if (s != null && self.level().getBlockEntity(s) instanceof Container c) {
            return !c.getItem(4).isEmpty();
        }
        return false;
    }

    private boolean needWater() {
        for (ItemStack s : self.getInventory().items) {
            if (isBottle(s)) {
                return false;
            }
        }
        return count(Items.GLASS_BOTTLE) > 0;
    }

    public boolean brewing() {
        return readyAt > 0;
    }

    public boolean hasWork(long now) {
        if (readyAt > 0) {
            return now >= readyAt && stand != null;
        }
        if (!standAvailable()) {
            return false;
        }
        if (needWater()) {
            return waterSource(12) != null;
        }
        return plan() != null && hasFuel();
    }

    // ================================================================== doing it

    public void begin() {
        stage = 0;
        ticks = 0;
        tries = 0;
        motor.resetStuck();
    }

    public void reset() {
        if (self.containerMenu instanceof BrewingStandMenu) {
            self.closeContainer();
        }
    }

    public Status tick(long now) {
        if (++ticks > 2400) {
            reset();
            return Status.FAILED;
        }
        switch (stage) {
            case 0 -> {
                if (readyAt > 0 && now >= readyAt && stand != null) {
                    stage = 3;
                } else if (needWater()) {
                    stage = 5;
                } else if (plan() == null || !hasFuel()) {
                    return Status.DONE;
                } else {
                    stand = nearestStand();
                    stage = stand == null ? 4 : 1;
                }
                tries = 0;
                return Status.WORKING;
            }
            case 4 -> {
                return placeStand();
            }
            case 1, 3 -> {
                return openAndWork(now);
            }
            case 5 -> {
                return fillBottles();
            }
            default -> {
                return Status.DONE;
            }
        }
    }

    private Status placeStand() {
        int slot = -1;
        Inventory inv = self.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).is(Items.BREWING_STAND)) {
                slot = i;
            }
        }
        if (slot < 0 || !self.onGround() || self.isInWater()) {
            return Status.FAILED;
        }
        Equipment.select(self, slot);
        ServerLevel level = self.serverLevel();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos spot = self.blockPosition().relative(d);
            if (level.getBlockState(spot).canBeReplaced() && level.getBlockState(spot.above()).canBeReplaced()
                    && !level.getBlockState(spot.below()).getCollisionShape(level, spot.below()).isEmpty()) {
                motor.lookAt(Vec3.atCenterOf(spot));
                if (motor.useOnTopFace(spot.below()) && level.getBlockState(spot).is(net.minecraft.world.level.block.Blocks.BREWING_STAND)) {
                    perception.noteBlock(spot);
                    stand = spot;
                    standsPlaced++;
                    stage = 1;
                    return Status.WORKING;
                }
            }
        }
        return Status.FAILED;
    }

    private int menuSlot(BrewingStandMenu menu, int inventoryIndex) {
        for (Slot s : menu.slots) {
            if (s.container == self.getInventory() && s.getContainerSlot() == inventoryIndex) {
                return s.index;
            }
        }
        return -1;
    }

    private Status openAndWork(long now) {
        if (stand == null || !(self.level().getBlockEntity(stand) instanceof Container)) {
            perception.forgetBlock(stand);
            stand = null;
            readyAt = -1;
            return Status.FAILED;
        }
        if (self.containerMenu instanceof BrewingStandMenu menu) {
            if (stage == 3) {
                // take the potions (and the leftover ingredient) out
                for (int i = 0; i <= 3; i++) {
                    if (menu.getSlot(i).hasItem()) {
                        menu.clicked(i, 0, ClickType.QUICK_MOVE, self);
                    }
                }
                collected++;
                readyAt = -1;
                self.closeContainer();
                return Status.DONE;
            }
            int[] p = plan();
            if (p == null) {
                self.closeContainer();
                return Status.DONE;
            }
            Inventory inv = self.getInventory();
            if (menu.getFuel() <= 0 && !menu.getSlot(4).hasItem()) {
                for (int i = 0; i < inv.items.size(); i++) {
                    if (inv.items.get(i).is(Items.BLAZE_POWDER)) {
                        menu.clicked(menuSlot(menu, i), 0, ClickType.QUICK_MOVE, self);
                        break;
                    }
                }
            }
            // up to three bottles of the planned kind
            ItemStack kind = inv.items.get(p[0]).copy();
            int ingredientIndex = p[1];
            for (int i = 0; i < inv.items.size(); i++) {
                ItemStack s = inv.items.get(i);
                if (isBottle(s) && ItemStack.isSameItemSameTags(s, kind)) {
                    boolean free = !menu.getSlot(0).hasItem() || !menu.getSlot(1).hasItem() || !menu.getSlot(2).hasItem();
                    if (free) {
                        menu.clicked(menuSlot(menu, i), 0, ClickType.QUICK_MOVE, self);
                    }
                }
            }
            // exactly one ingredient: pick the stack up, drop one into the ingredient slot, put the rest back
            if (!menu.getSlot(3).hasItem() && !inv.items.get(ingredientIndex).isEmpty()) {
                int from = menuSlot(menu, ingredientIndex);
                menu.clicked(from, 0, ClickType.PICKUP, self);
                menu.clicked(3, 1, ClickType.PICKUP, self);
                menu.clicked(from, 0, ClickType.PICKUP, self);
            }
            boolean started = menu.getSlot(3).hasItem() && (menu.getSlot(0).hasItem() || menu.getSlot(1).hasItem() || menu.getSlot(2).hasItem());
            self.closeContainer();
            if (!started) {
                return Status.FAILED;
            }
            brews++;
            readyAt = now + BREW_TICKS + 20;
            return Status.DONE;
        }
        Vec3 center = Vec3.atCenterOf(stand);
        if (self.getEyePosition().distanceTo(center) > Motor.BLOCK_REACH - 0.5) {
            motor.navigate(center, 2.0, false);
            return motor.stuckCount() > 6 ? Status.FAILED : Status.WORKING;
        }
        motor.stop();
        motor.lookAt(center);
        Direction face = Direction.getNearest(self.getX() - center.x, self.getEyeY() - center.y, self.getZ() - center.z);
        Vec3 hit = center.add(face.getStepX() * 0.4, face.getStepY() * 0.4, face.getStepZ() * 0.4);
        self.gameMode.useItemOn(self, self.serverLevel(), self.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, stand, false));
        if (!(self.containerMenu instanceof BrewingStandMenu) && ++tries > 40) {
            return Status.FAILED;
        }
        return Status.WORKING;
    }

    @Nullable
    private BlockPos waterSource(int radius) {
        ServerLevel level = self.serverLevel();
        Vec3 eye = self.getEyePosition();
        BlockPos feet = self.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-radius, -3, -radius), feet.offset(radius, 2, radius))) {
            if (!level.getFluidState(p).is(FluidTags.WATER) || !level.getFluidState(p).isSource() || !level.getBlockState(p.above()).isAir()) {
                continue;
            }
            Vec3 top = Vec3.atCenterOf(p).add(0, 0.4, 0);
            double d = eye.distanceTo(top);
            if (d >= bestD) {
                continue;
            }
            BlockHitResult hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(p), ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, self));
            if (hit.getType() == HitResult.Type.BLOCK && level.getFluidState(hit.getBlockPos()).is(FluidTags.WATER) && level.getFluidState(hit.getBlockPos()).isSource()) {
                bestD = d;
                best = hit.getBlockPos().immutable();
            }
        }
        return best;
    }

    private Status fillBottles() {
        if (count(Items.GLASS_BOTTLE) == 0 || !needWaterOrMore()) {
            stage = 0;
            return Status.WORKING;
        }
        BlockPos water = waterSource(12);
        if (water == null) {
            return Status.FAILED;
        }
        Vec3 top = Vec3.atCenterOf(water).add(0, 0.2, 0);
        if (self.getEyePosition().distanceTo(top) > Motor.BLOCK_REACH - 0.5) {
            motor.navigate(Vec3.atBottomCenterOf(water.above()), 2.0, false);
            return motor.stuckCount() > 6 ? Status.FAILED : Status.WORKING;
        }
        motor.stop();
        Inventory inv = self.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).is(Items.GLASS_BOTTLE)) {
                Equipment.select(self, i);
                break;
            }
        }
        Vec3 eye = self.getEyePosition();
        float yaw = (float) Math.toDegrees(Mth.atan2(top.z - eye.z, top.x - eye.x)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Mth.atan2(top.y - eye.y, Math.sqrt((top.x - eye.x) * (top.x - eye.x) + (top.z - eye.z) * (top.z - eye.z))));
        self.setYRot(yaw);
        self.setYHeadRot(yaw);
        self.setXRot(pitch);
        if (motor.useHeldItem(InteractionHand.MAIN_HAND)) {
            bottlesFilled++;
        }
        return Status.WORKING;
    }

    private boolean needWaterOrMore() {
        int bottles = 0;
        for (ItemStack s : self.getInventory().items) {
            if (isBottle(s)) {
                bottles++;
            }
        }
        return bottles < 3;
    }
}
