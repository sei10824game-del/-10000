package com.rlclones.ai;

import com.rlclones.ai.brain.Brain;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

/**
 * Reflexes with items, the way an experienced player uses them: a totem goes into the off hand when death is near,
 * potions are drunk / thrown when their (learned) effect fits the situation, milk cures bad effects, a water bucket
 * breaks a long fall or puts out fire (and is scooped up again), buckets get filled, cows milked, and ender pearls
 * get the clone out of pits and away from danger.
 */
public final class Consumables {
    private static final Set<MobEffect> BAD = Set.of(MobEffects.POISON, MobEffects.WITHER, MobEffects.HUNGER, MobEffects.WEAKNESS,
            MobEffects.MOVEMENT_SLOWDOWN, MobEffects.DIG_SLOWDOWN, MobEffects.CONFUSION, MobEffects.BLINDNESS, MobEffects.DARKNESS,
            MobEffects.LEVITATION);
    private static final double PEARL_SPEED = 1.5;
    private static final double PEARL_GRAVITY = 0.03;

    private final ClonePlayer self;
    private final Motor motor;
    private final Perception perception;

    @Nullable
    private BlockPos placedWater;
    private int pickupTicks;
    private int mlgTicks;
    private long lastPearl = -1000;
    private long lastChore = -1000;
    private int throwTicks;

    public int totemSwaps;
    public int potionsUsed;
    public int milkDrunk;
    public int waterPlaced;
    public int bucketsFilled;
    public int cowsMilked;
    public int pearlsThrown;

    public Consumables(ClonePlayer self, Motor motor, Perception perception) {
        this.self = self;
        this.motor = motor;
        this.perception = perception;
    }

    private int slotOf(java.util.function.Predicate<ItemStack> p) {
        Inventory inv = self.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (!inv.items.get(i).isEmpty() && p.test(inv.items.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean drinkOrThrow(ItemStack s) {
        return s.getItem() instanceof PotionItem || s.is(Items.MILK_BUCKET);
    }

    /** Drinking / scooping water back up etc.: the rest of the mind waits. */
    public boolean busy() {
        return (self.isUsingItem() && drinkOrThrow(self.getUseItem())) || mlgTicks > 0 || placedWater != null || throwTicks > 0;
    }

    /**
     * Run the item reflexes. Returns true if this tick was spent on one.
     *
     * @param enemy the current opponent when fighting, else null
     */
    public boolean tick(long now, boolean threatened, @Nullable Entity enemy) {
        totem();
        if (throwTicks > 0) {
            throwTicks--;
            return true;
        }
        if (self.isUsingItem()) {
            return drinkOrThrow(self.getUseItem());
        }
        if (fallReflex()) {
            return true;
        }
        if (scoopWater()) {
            return true;
        }
        if (extinguish()) {
            return true;
        }
        if (drinkForSituation(enemy)) {
            return true;
        }
        if (enemy != null && throwAtEnemy(enemy)) {
            return true;
        }
        if (!threatened && now - lastChore > 40) {
            lastChore = now;
            return chores();
        }
        return false;
    }

    // ------------------------------------------------------------------ totem

    private void totem() {
        float hp = self.getHealth();
        ItemStack off = self.getOffhandItem();
        Inventory inv = self.getInventory();
        if (hp <= Math.max(6f, self.getMaxHealth() * 0.3f) && !off.is(Items.TOTEM_OF_UNDYING)) {
            int t = slotOf(s -> s.is(Items.TOTEM_OF_UNDYING));
            if (t >= 0) {
                // close to death: the totem goes into the left hand
                ItemStack totem = inv.items.get(t);
                inv.items.set(t, off.copy());
                self.setItemSlot(EquipmentSlot.OFFHAND, totem.copy());
                totemSwaps++;
            }
        } else if (hp >= self.getMaxHealth() * 0.7f && off.is(Items.TOTEM_OF_UNDYING)) {
            int shield = slotOf(s -> s.getItem() instanceof ShieldItem);
            if (shield >= 0) {
                ItemStack sh = inv.items.get(shield);
                inv.items.set(shield, off.copy());
                self.setItemSlot(EquipmentSlot.OFFHAND, sh.copy());
            }
        }
    }

    // ------------------------------------------------------------------ water bucket: falls and fire

    /** Distance from the feet down to the first solid block below (or -1). */
    private double groundBelow(BlockPos[] ground) {
        Vec3 feet = self.position();
        BlockHitResult hit = self.level().clip(new ClipContext(feet, feet.add(0, -40, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, self));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return -1;
        }
        ground[0] = hit.getBlockPos();
        return feet.y - hit.getLocation().y;
    }

    private boolean fallReflex() {
        if (mlgTicks > 0) {
            mlgTicks--;
        }
        if (self.onGround() || self.isInWater() || self.isFallFlying() || self.getAbilities().flying || self.isPassenger()
                || self.fallDistance < 3.5f || self.getDeltaMovement().y > -0.3 || self.hasEffect(MobEffects.SLOW_FALLING)) {
            return false;
        }
        int bucket = slotOf(s -> s.is(Items.WATER_BUCKET));
        if (bucket < 0) {
            return false;
        }
        BlockPos[] ground = new BlockPos[1];
        double d = groundBelow(ground);
        if (d < 0 || !self.level().getFluidState(ground[0]).isEmpty()) {
            return false; // landing in water anyway
        }
        double expected = self.fallDistance + d;
        if (expected < 4.5) {
            return false; // would not even hurt
        }
        Equipment.select(self, bucket);
        self.setXRot(90f); // look straight down
        mlgTicks = 3;
        if (d <= 2.6 && d > 0.1) {
            if (motor.useHeldItem(InteractionHand.MAIN_HAND) || self.level().getFluidState(ground[0].above()).is(FluidTags.WATER)) {
                placedWater = ground[0].above();
                pickupTicks = 80;
                waterPlaced++;
            }
        }
        return true;
    }

    private boolean extinguish() {
        if (!self.isOnFire() || self.isInWater() || self.isInLava() || !self.onGround() || self.hasEffect(MobEffects.FIRE_RESISTANCE)) {
            return false;
        }
        int bucket = slotOf(s -> s.is(Items.WATER_BUCKET));
        if (bucket < 0) {
            return false;
        }
        BlockPos below = self.blockPosition().below();
        if (self.level().getBlockState(below).getCollisionShape(self.level(), below).isEmpty()) {
            return false;
        }
        Equipment.select(self, bucket);
        self.setXRot(90f);
        if (motor.useHeldItem(InteractionHand.MAIN_HAND)) {
            placedWater = self.blockPosition();
            pickupTicks = 60;
            waterPlaced++;
        }
        return true;
    }

    /** Take the water we placed back into the bucket. */
    private boolean scoopWater() {
        if (placedWater == null) {
            return false;
        }
        if (--pickupTicks <= 0 || !self.level().getFluidState(placedWater).isSource()) {
            placedWater = null;
            return false;
        }
        if (!self.onGround() && !self.isInWater()) {
            return true; // still falling into it
        }
        int bucket = slotOf(s -> s.is(Items.BUCKET));
        if (bucket < 0) {
            placedWater = null;
            return false;
        }
        Vec3 c = Vec3.atCenterOf(placedWater);
        if (self.getEyePosition().distanceTo(c) > Motor.BLOCK_REACH - 0.5) {
            motor.navigate(Vec3.atBottomCenterOf(placedWater), 1.0, false);
            return true;
        }
        Equipment.select(self, bucket);
        lookAtNow(c.add(0, 0.3, 0));
        if (motor.useHeldItem(InteractionHand.MAIN_HAND) && !self.level().getFluidState(placedWater).isSource()) {
            placedWater = null;
            bucketsFilled++;
        }
        return true;
    }

    private void lookAtNow(Vec3 p) {
        Vec3 eye = self.getEyePosition();
        double dx = p.x - eye.x;
        double dy = p.y - eye.y;
        double dz = p.z - eye.z;
        float yaw = (float) Math.toDegrees(Mth.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        self.setYRot(yaw);
        self.setYHeadRot(yaw);
        self.setXRot(pitch);
    }

    // ------------------------------------------------------------------ potions & milk

    private Brain brain() {
        return self.getCloneBrain();
    }

    private boolean hasEffectId(String facts, MobEffect... wanted) {
        for (String[] e : Discovery.effects(facts)) {
            MobEffect me = ForgeRegistries.MOB_EFFECTS.getValue(new ResourceLocation(e[0]));
            for (MobEffect w : wanted) {
                if (me == w) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A potion (drinkable or splash) whose learned effects include one of {@code effects}. */
    private int potionSlot(boolean splash, MobEffect... effects) {
        Brain b = brain();
        if (b == null) {
            return -1;
        }
        return slotOf(s -> s.getItem() instanceof PotionItem && (s.getItem() instanceof ThrowablePotionItem) == splash
                && hasEffectId(b.facts(Discovery.keyOf(s)), effects));
    }

    private boolean drink(int slot) {
        if (slot < 0) {
            return false;
        }
        Equipment.select(self, slot);
        motor.stop();
        if (self.getMainHandItem().getItem() instanceof ThrowablePotionItem) {
            self.setXRot(90f); // splash at our own feet
            motor.useHeldItem(InteractionHand.MAIN_HAND);
            throwTicks = 4;
        } else {
            motor.useHeldItem(InteractionHand.MAIN_HAND);
        }
        potionsUsed++;
        return true;
    }

    private boolean drinkForSituation(@Nullable Entity enemy) {
        float hp = self.getHealth() / self.getMaxHealth();
        for (MobEffect bad : BAD) {
            if (self.hasEffect(bad)) {
                int milk = slotOf(s -> s.is(Items.MILK_BUCKET));
                if (milk >= 0) {
                    Equipment.select(self, milk);
                    motor.useHeldItem(InteractionHand.MAIN_HAND);
                    milkDrunk++;
                    return true;
                }
                break;
            }
        }
        if (hp <= 0.4f) {
            int s = potionSlot(false, MobEffects.HEAL, MobEffects.REGENERATION, MobEffects.ABSORPTION);
            if (s < 0) {
                s = potionSlot(true, MobEffects.HEAL, MobEffects.REGENERATION);
            }
            if (drink(s)) {
                return true;
            }
        }
        if ((self.isOnFire() || self.isInLava()) && !self.hasEffect(MobEffects.FIRE_RESISTANCE) && drink(potionSlot(false, MobEffects.FIRE_RESISTANCE))) {
            return true;
        }
        if (self.isUnderWater() && self.getAirSupply() < self.getMaxAirSupply() * 0.4 && !self.hasEffect(MobEffects.WATER_BREATHING)
                && drink(potionSlot(false, MobEffects.WATER_BREATHING))) {
            return true;
        }
        if (enemy != null && enemy.distanceTo(self) < 10 && !self.hasEffect(MobEffects.DAMAGE_BOOST)
                && drink(potionSlot(false, MobEffects.DAMAGE_BOOST, MobEffects.MOVEMENT_SPEED, MobEffects.DAMAGE_RESISTANCE))) {
            return true;
        }
        if (enemy == null && Senses.isDark(self) && !self.hasEffect(MobEffects.NIGHT_VISION) && drink(potionSlot(false, MobEffects.NIGHT_VISION))) {
            return true;
        }
        return false;
    }

    private boolean throwAtEnemy(Entity enemy) {
        double d = enemy.distanceTo(self);
        if (d < 3 || d > 8) {
            return false;
        }
        int s = potionSlot(true, MobEffects.HARM, MobEffects.POISON, MobEffects.WEAKNESS, MobEffects.MOVEMENT_SLOWDOWN);
        if (s < 0) {
            return false;
        }
        Equipment.select(self, s);
        lookAtNow(enemy.getBoundingBox().getCenter().add(0, d * 0.08, 0));
        motor.useHeldItem(InteractionHand.MAIN_HAND);
        throwTicks = 6;
        potionsUsed++;
        return true;
    }

    // ------------------------------------------------------------------ chores: fill buckets, milk cows

    private boolean chores() {
        int empty = slotOf(s -> s.is(Items.BUCKET));
        if (empty < 0) {
            return false;
        }
        boolean hasWater = slotOf(s -> s.is(Items.WATER_BUCKET)) >= 0;
        if (!hasWater) {
            BlockPos water = visibleWaterSource();
            if (water != null) {
                Equipment.select(self, empty);
                lookAtNow(Vec3.atCenterOf(water).add(0, 0.2, 0));
                if (motor.useHeldItem(InteractionHand.MAIN_HAND)) {
                    bucketsFilled++;
                }
                return true;
            }
        }
        boolean hasMilk = slotOf(s -> s.is(Items.MILK_BUCKET)) >= 0;
        if (!hasMilk && (hasWater || self.getInventory().countItem(Items.BUCKET) >= 2)) {
            List<Cow> cows = self.level().getEntitiesOfClass(Cow.class, self.getBoundingBox().inflate(3), c -> c.isAlive() && !c.isBaby() && perception.isVisible(c));
            if (!cows.isEmpty()) {
                Equipment.select(self, empty);
                lookAtNow(cows.get(0).getEyePosition());
                self.interactOn(cows.get(0), InteractionHand.MAIN_HAND);
                if (slotOf(s -> s.is(Items.MILK_BUCKET)) >= 0) {
                    cowsMilked++;
                }
                return true;
            }
        }
        return false;
    }

    @Nullable
    private BlockPos visibleWaterSource() {
        ServerLevel level = self.serverLevel();
        Vec3 eye = self.getEyePosition();
        BlockPos feet = self.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-4, -3, -4), feet.offset(4, 1, 4))) {
            if (!level.getFluidState(p).is(FluidTags.WATER) || !level.getFluidState(p).isSource()) {
                continue;
            }
            Vec3 top = Vec3.atCenterOf(p).add(0, 0.4, 0);
            if (eye.distanceTo(top) > Motor.BLOCK_REACH - 0.3) {
                continue;
            }
            // aim into the water (the surface is a little below the block top)
            BlockHitResult hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(p), ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, self));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(p)) {
                return p.immutable();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ ender pearls

    public boolean hasPearl() {
        return slotOf(s -> s.is(Items.ENDER_PEARL)) >= 0 && !self.getCooldowns().isOnCooldown(Items.ENDER_PEARL);
    }

    /** Where would a pearl thrown with these angles land? (simulates the projectile like the game does) */
    @Nullable
    private Vec3 simulate(float yaw, float pitch) {
        ServerLevel level = self.serverLevel();
        Vec3 pos = self.getEyePosition().subtract(0, 0.1, 0);
        Vec3 v = Vec3.directionFromRotation(pitch, yaw).scale(PEARL_SPEED);
        for (int i = 0; i < 120; i++) {
            Vec3 next = pos.add(v);
            BlockHitResult hit = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, self));
            if (hit.getType() == HitResult.Type.BLOCK) {
                if (!level.getFluidState(hit.getBlockPos()).isEmpty() && level.getFluidState(hit.getBlockPos()).is(FluidTags.LAVA)) {
                    return null;
                }
                return hit.getLocation();
            }
            pos = next;
            v = v.scale(0.99).subtract(0, PEARL_GRAVITY, 0);
            if (pos.y < level.getMinBuildHeight()) {
                return null;
            }
        }
        return null;
    }

    private boolean standable(Vec3 landing) {
        BlockPos at = BlockPos.containing(landing.x, landing.y + 0.05, landing.z);
        ServerLevel level = self.serverLevel();
        return level.getBlockState(at).getCollisionShape(level, at).isEmpty() && level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty()
                && level.getFluidState(at).isEmpty();
    }

    private void throwPearl(float yaw, float pitch) {
        Equipment.select(self, slotOf(s -> s.is(Items.ENDER_PEARL)));
        self.setYRot(yaw);
        self.setYHeadRot(yaw);
        self.setXRot(pitch);
        motor.useHeldItem(InteractionHand.MAIN_HAND);
        lastPearl = self.level().getGameTime();
        pearlsThrown++;
        throwTicks = 20; // wait for the landing
    }

    public String pearlDebug = "";

    /** Stuck in a pit without blocks to pillar with: throw a pearl over the edge. */
    public boolean pearlOutOfPit() {
        if (!hasPearl() || self.level().getGameTime() - lastPearl < 40) {
            pearlDebug = "noPearl/cooldown";
            return false;
        }
        StringBuilder dbg = new StringBuilder();
        Vec3 start = self.position();
        Vec3 best = null;
        float bestYaw = 0;
        float bestPitch = 0;
        for (int y = 0; y < 8; y++) {
            float yaw = y * 45f;
            // nearly straight up for a short hop over the rim, flatter for longer throws
            for (float pitch = -89f; pitch <= -25f; pitch += pitch < -80f ? 1f : 5f) {
                Vec3 land = simulate(yaw, pitch);
                if (y == 0 && dbg.length() < 400) {
                    dbg.append(pitch).append("->").append(land == null ? "null" : String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f", land.x - start.x, land.y - start.y, land.z - start.z)).append(' ');
                }
                // anywhere out of the pit, at most a few blocks lower (the pearl resets the fall)
                if (land == null || Motor.horizontalDistance(land, start) < 1.8 || land.y < start.y - 3.0 || !standable(land)) {
                    continue;
                }
                if (best == null || land.y > best.y || (land.y == best.y && Motor.horizontalDistance(land, start) < Motor.horizontalDistance(best, start))) {
                    best = land;
                    bestYaw = yaw;
                    bestPitch = pitch;
                }
            }
        }
        pearlDebug = (best == null ? "none " : "throw ") + dbg;
        if (best == null) {
            return false;
        }
        throwPearl(bestYaw, bestPitch);
        return true;
    }

    /** Fleeing in bad shape: pearl away from the danger, along {@code away} (horizontal direction). */
    public boolean pearlAway(Vec3 away) {
        if (!hasPearl() || self.level().getGameTime() - lastPearl < 60) {
            return false;
        }
        float baseYaw = (float) Math.toDegrees(Mth.atan2(away.z, away.x)) - 90.0F;
        Vec3 start = self.position();
        for (float dy : new float[]{0f, 15f, -15f, 30f, -30f}) {
            for (float pitch = -30f; pitch <= -5f; pitch += 5f) {
                float yaw = baseYaw + dy;
                Vec3 land = simulate(yaw, pitch);
                if (land != null && Motor.horizontalDistance(land, start) >= 8 && Math.abs(land.y - start.y) < 6 && standable(land)) {
                    throwPearl(yaw, pitch);
                    return true;
                }
            }
        }
        return false;
    }

    /** Facing direction helper for callers. */
    public static Vec3 horizontal(Vec3 v) {
        Vec3 h = new Vec3(v.x, 0, v.z);
        return h.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : h.normalize();
    }
}
