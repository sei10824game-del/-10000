package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.Bases;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Animals: taming (wolves with bones, cats with fish, parrots with seeds), breeding the clone's own tamed animals and
 * penned chickens when they can breed and there is food for them, and building a small village-style chicken pen
 * (fences + a gate) when there is spare wood, throwing eggs into it. At least two chickens of a pen are never eaten.
 */
public final class Animals {
    public enum Status {WORKING, DONE, FAILED}

    private enum Job {TAME, BREED, PEN, EGGS, RIDE, STAND, LIVESTOCK}

    public static final int PEN_FENCES = 15;

    private final ClonePlayer self;
    private final Motor motor;
    private final Perception perception;

    private Job job;
    @Nullable
    private Animal target;
    @Nullable
    private Animal mate;
    private int ticks;
    private int stage;
    private int cooldown;
    private int tries;
    @Nullable
    private BlockPos penOrigin;
    private final List<BlockPos> penPlan = new ArrayList<>();

    public int tamed;
    public int fed;
    public int pensBuilt;
    public int eggsThrown;
    public int fencesPlaced;
    public int mountAttempts;
    public int horsesRidden;
    @Nullable
    private net.minecraft.world.entity.animal.horse.AbstractHorse horse;

    public Animals(ClonePlayer self, Motor motor, Perception perception) {
        this.self = self;
        this.motor = motor;
        this.perception = perception;
    }

    private int slotOf(Predicate<ItemStack> p) {
        Inventory inv = self.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (!inv.items.get(i).isEmpty() && p.test(inv.items.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private int count(Predicate<ItemStack> p) {
        int n = 0;
        for (ItemStack s : self.getInventory().items) {
            if (!s.isEmpty() && p.test(s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    // ================================================================== what can be done

    /** The item that tames this animal, or null if it is not (or no longer) tameable. */
    @Nullable
    public static Predicate<ItemStack> tamingFood(Entity e) {
        if (e instanceof Wolf w && !w.isTame() && !w.isAngry() && !w.isBaby()) {
            return s -> s.is(Items.BONE);
        }
        if (e instanceof Cat c && !c.isTame()) {
            return s -> s.is(Items.COD) || s.is(Items.SALMON);
        }
        if (e instanceof Parrot p && !p.isTame()) {
            return s -> s.is(Items.WHEAT_SEEDS) || s.is(Items.MELON_SEEDS) || s.is(Items.PUMPKIN_SEEDS) || s.is(Items.BEETROOT_SEEDS);
        }
        return null;
    }

    @Nullable
    public Animal tameCandidate() {
        Animal best = null;
        double bestD = 24;
        long now = self.level().getGameTime();
        for (Perception.Seen s : perception.remembered()) {
            if (!(s.entity instanceof Animal a) || !a.isAlive() || now - s.lastSeen > 100) {
                continue;
            }
            Predicate<ItemStack> food = tamingFood(a);
            double d = a.distanceTo(self);
            if (food != null && d < bestD && slotOf(food) >= 0) {
                bestD = d;
                best = a;
            }
        }
        return best;
    }

    private boolean ours(Animal a) {
        if (a instanceof TamableAnimal t) {
            return t.isTame() && t.isOwnedBy(self);
        }
        return Bases.get(self.getServer()).penAt(self.level().dimension(), a.position()) != null;
    }

    /** Two of our own animals of one kind that can breed now, and food for both. */
    @Nullable
    private Animal[] breedPair() {
        List<Animal> ready = new ArrayList<>();
        for (Perception.Seen s : perception.remembered()) {
            if (s.entity instanceof Animal a && a.isAlive() && a.distanceTo(self) < 16 && a.getAge() == 0 && a.canFallInLove()
                    && !a.isInLove() && ours(a)) {
                ready.add(a);
            }
        }
        for (int i = 0; i < ready.size(); i++) {
            for (int j = i + 1; j < ready.size(); j++) {
                Animal a = ready.get(i);
                Animal b = ready.get(j);
                if (a.getType() == b.getType() && count(a::isFood) >= 2) {
                    return new Animal[]{a, b};
                }
            }
        }
        return null;
    }

    private int woodPlanks() {
        return count(s -> s.is(ItemTags.PLANKS)) + count(s -> s.is(ItemTags.LOGS)) * 4;
    }

    private boolean penNearby(double radius) {
        return Bases.get(self.getServer()).nearestPen(self.level().dimension(), self.position(), radius) != null;
    }

    /** Spare wood and eggs (or chickens around), no pen yet: worth building one. */
    public boolean penWanted() {
        if (!Config.get(Config.ALLOW_BLOCK_PLACING, true) || penNearby(48)) {
            return false;
        }
        boolean chickens = count(s -> s.is(Items.EGG)) > 0;
        boolean materials = count(s -> s.is(ItemTags.WOODEN_FENCES)) >= PEN_FENCES && count(s -> s.is(ItemTags.FENCE_GATES)) >= 1;
        return chickens && (materials || woodPlanks() >= 40);
    }

    /** Crafting asks this: fences / a gate still to be made for the pen. */
    private boolean livestockReady() {
        return count(s -> s.is(ItemTags.WOODEN_FENCES)) >= PEN_FENCES && count(s -> s.is(ItemTags.FENCE_GATES)) >= 1 && livestockWanted() != null;
    }

    public boolean needsFences() {
        return (penWanted() || livestockWanted() != null) && count(s -> s.is(ItemTags.WOODEN_FENCES)) < PEN_FENCES;
    }

    public boolean needsGate() {
        return (penWanted() || livestockWanted() != null) && count(s -> s.is(ItemTags.FENCE_GATES)) < 1;
    }

    private boolean canBuildPen() {
        return penWanted() && !needsFences() && !needsGate() && self.onGround() && !self.isInWater();
    }

    private boolean eggsForPen() {
        return count(s -> s.is(Items.EGG)) > 0 && penNearby(32);
    }

    public boolean hasWork() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        return tameCandidate() != null || breedPair() != null || canBuildPen() || eggsForPen() || rideCandidate() != null
                || sittingPet() != null || livestockReady();
    }

    /** A horse / donkey / mule to ride: a wild one (we carry a saddle) or our own saddled one standing about. */
    @Nullable
    private net.minecraft.world.entity.animal.horse.AbstractHorse rideCandidate() {
        if (self.isPassenger()) {
            return null;
        }
        boolean saddle = slotOf(s -> s.is(Items.SADDLE)) >= 0;
        net.minecraft.world.entity.animal.horse.AbstractHorse best = null;
        for (Perception.Seen s : perception.remembered()) {
            if (!(s.entity instanceof net.minecraft.world.entity.animal.horse.AbstractHorse h) || !h.isAlive() || h.isBaby() || h.isVehicle()
                    || h.distanceTo(self) > 24 || !(h instanceof net.minecraft.world.entity.animal.horse.Horse
                    || h instanceof net.minecraft.world.entity.animal.horse.Donkey || h instanceof net.minecraft.world.entity.animal.horse.Mule)) {
                continue;
            }
            boolean ours = h.isTamed() && self.getUUID().equals(h.getOwnerUUID());
            if (h.isTamed() && !ours) {
                continue; // somebody else's horse
            }
            if ((saddle || ours && h.isSaddled() || !h.isTamed()) && (best == null || h.distanceTo(self) < best.distanceTo(self))) {
                best = h;
            }
        }
        return best;
    }

    /** Nothing in the main hand (a wild horse only lets an empty hand climb on). */
    private void emptyHand() {
        Inventory inv = self.getInventory();
        if (inv.getSelected().isEmpty()) {
            return;
        }
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (inv.items.get(i).isEmpty()) {
                inv.selected = i;
                return;
            }
        }
        for (int i = Inventory.getSelectionSize(); i < inv.items.size(); i++) {
            if (inv.items.get(i).isEmpty()) {
                inv.items.set(i, inv.getSelected());
                inv.items.set(inv.selected, ItemStack.EMPTY);
                return;
            }
        }
    }

    /** Climb on (again and again while it throws us off, until it is tamed), saddle it, ride it. */
    private Status rideTick() {
        var h = horse;
        if (h == null || !h.isAlive()) {
            return Status.FAILED;
        }
        if (self.getVehicle() == h) {
            if (h.isTamed()) {
                if (h.isSaddled()) {
                    horsesRidden++;
                    return Status.DONE; // our horse now
                }
                self.stopRiding(); // off for a moment to put the saddle on
                if (slotOf(s -> s.is(Items.SADDLE)) < 0) {
                    tamed++;
                    return Status.DONE; // tamed - the saddle can come later
                }
                return Status.WORKING;
            }
            motor.stop(); // holding on while it bucks
            return Status.WORKING;
        }
        if (self.isPassenger()) {
            return Status.FAILED;
        }
        if (h.distanceTo(self) > 2.5) {
            motor.navigate(h.position(), 1.5, false);
            return motor.stuckCount() > 10 ? Status.FAILED : Status.WORKING;
        }
        motor.stop();
        motor.lookAt(h);
        if (ticks % 10 != 0) {
            return Status.WORKING;
        }
        if (h.isTamed() && !h.isSaddled()) {
            int sad = slotOf(s -> s.is(Items.SADDLE));
            if (sad < 0) {
                return Status.FAILED;
            }
            Equipment.select(self, sad);
            self.interactOn(h, InteractionHand.MAIN_HAND);
            return Status.WORKING;
        }
        emptyHand();
        self.interactOn(h, InteractionHand.MAIN_HAND);
        mountAttempts++;
        return Status.WORKING;
    }

    /** Keep at least two chickens (or whatever is penned) in every pen: those are not to be eaten. */
    public static boolean protectedAnimal(Entity e) {
        if (!(e instanceof Animal a) || e.getServer() == null) {
            return false;
        }
        Bases.Pen pen = Bases.get(e.getServer()).penAt(e.level().dimension(), e.position());
        if (pen == null) {
            return false;
        }
        int same = e.level().getEntitiesOfClass(Animal.class, new net.minecraft.world.phys.AABB(pen.origin()).inflate(0, 2, 0).expandTowards(5, 0, 5),
                o -> o.getType() == a.getType() && o.isAlive() && !o.isBaby() && pen.contains(o.position())).size();
        return same <= 2;
    }

    // ================================================================== doing it

    public void begin() {
        job = null;
        target = null;
        mate = null;
        ticks = 0;
        stage = 0;
        motor.resetStuck();
        Animal[] pair = breedPair();
        Animal t = tameCandidate();
        var steed = rideCandidate();
        TamableAnimal sitting = sittingPet();
        Livestock stock = livestockWanted();
        if (sitting != null) {
            job = Job.STAND;
            target = sitting;
        } else if (steed != null) {
            job = Job.RIDE;
            horse = steed;
        } else if (t != null) {
            job = Job.TAME;
            target = t;
        } else if (pair != null) {
            job = Job.BREED;
            target = pair[0];
            mate = pair[1];
        } else if (canBuildPen()) {
            job = Job.PEN;
        } else if (stock != null && count(s -> s.is(ItemTags.WOODEN_FENCES)) >= PEN_FENCES && count(s -> s.is(ItemTags.FENCE_GATES)) >= 1
                && self.onGround()) {
            job = Job.LIVESTOCK;
            livestock = stock;
        } else if (eggsForPen()) {
            job = Job.EGGS;
            Bases.Pen pen = Bases.get(self.getServer()).nearestPen(self.level().dimension(), self.position(), 32);
            penOrigin = pen == null ? null : pen.origin();
        }
    }

    public Status tick() {
        if (job == null || ++ticks > 2400) {
            return Status.DONE;
        }
        return switch (job) {
            case TAME -> tameTick();
            case BREED -> breedTick();
            case PEN -> penTick();
            case EGGS -> eggsTick();
            case RIDE -> rideTick();
            case STAND -> standTick();
            case LIVESTOCK -> livestockTick();
        };
    }

    /** Walk up to an animal and use {@code food} on it. Returns true once used this tick. */
    private boolean feed(Animal a, Predicate<ItemStack> food) {
        if (a.distanceTo(self) > 3.0) {
            motor.navigate(a.position(), 2.0, false);
            return false;
        }
        int slot = slotOf(food);
        if (slot < 0) {
            return false;
        }
        motor.stop();
        motor.lookAt(a.getEyePosition());
        Equipment.select(self, slot);
        self.interactOn(a, InteractionHand.MAIN_HAND);
        self.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private Status tameTick() {
        if (target == null || !target.isAlive() || target.distanceTo(self) > 24) {
            return Status.FAILED;
        }
        if (target instanceof TamableAnimal t && t.isTame()) {
            if (t.isOwnedBy(self) && t.isOrderedToSit()) {
                standUp(t); // the game sits a freshly tamed pet down: up and come along instead
                return Status.WORKING;
            }
            if (t.isOwnedBy(self)) {
                tamed++;
            }
            cooldown = 20;
            return Status.DONE;
        }
        Predicate<ItemStack> food = tamingFood(target);
        if (food == null || slotOf(food) < 0) {
            cooldown = 200;
            return Status.FAILED;
        }
        if (ticks % 10 == 0 || target.distanceTo(self) > 3.0) {
            feed(target, food); // one try every half second, like a player clicking
        }
        return Status.WORKING;
    }

    /** Our own pet sitting: an empty-handed click makes it stand up and follow. */
    private void standUp(TamableAnimal t) {
        if (t.distanceTo(self) > 3.0) {
            motor.navigate(t.position(), 2.0, false);
            return;
        }
        motor.stop();
        motor.lookAt(t.getEyePosition());
        if (ticks % 5 == 0) {
            emptyHand();
            self.interactOn(t, InteractionHand.MAIN_HAND);
            self.swing(InteractionHand.MAIN_HAND);
            if (!t.isOrderedToSit()) {
                stoodUp++;
            }
        }
    }

    /** Pets stood up again (diagnostics, tests). */
    public int stoodUp;

    @Nullable
    private TamableAnimal sittingPet() {
        for (Perception.Seen s : perception.remembered()) {
            if (s.entity instanceof TamableAnimal t && t.isAlive() && t.isTame() && t.isOwnedBy(self) && t.isOrderedToSit()
                    && t.distanceTo(self) < 16 && !t.isInWater()) {
                return t;
            }
        }
        return null;
    }

    private Status standTick() {
        if (!(target instanceof TamableAnimal t) || !t.isAlive() || t.distanceTo(self) > 24) {
            return Status.FAILED;
        }
        if (!t.isOrderedToSit()) {
            cooldown = 20;
            return Status.DONE;
        }
        standUp(t);
        return ticks > 400 ? Status.FAILED : Status.WORKING;
    }

    private Status breedTick() {
        if (target == null || mate == null || !target.isAlive() || !mate.isAlive()) {
            return Status.FAILED;
        }
        Animal next = !target.isInLove() ? target : !mate.isInLove() ? mate : null;
        if (next == null) {
            fed += 2;
            cooldown = 100;
            return Status.DONE;
        }
        if (ticks % 5 == 0 || next.distanceTo(self) > 3.0) {
            feed(next, next::isFood);
        }
        return Status.WORKING;
    }

    // ---------------------------------------------------------------- the chicken pen

    @Nullable
    private BlockPos findPenSite() {
        ServerLevel level = self.serverLevel();
        BlockPos feet = self.blockPosition();
        Bases bases = Bases.get(self.getServer());
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                BlockPos origin = feet.offset(dx - 2, 0, dz - 2);
                double d = origin.offset(2, 0, 2).distSqr(feet);
                if (d >= bestD || bases.nearest(level.dimension(), Vec3.atCenterOf(origin), 10) != null) {
                    continue;
                }
                boolean ok = true;
                for (int x = 0; x < 5 && ok; x++) {
                    for (int z = -1; z < 5 && ok; z++) {
                        BlockPos g = origin.offset(x, -1, z);
                        ok = level.getFluidState(g).isEmpty() && level.getBlockState(g).isCollisionShapeFullBlock(level, g)
                                && level.getBlockState(g.above()).canBeReplaced() && level.getFluidState(g.above()).isEmpty()
                                && level.getBlockState(g.above(2)).canBeReplaced();
                    }
                }
                if (ok) {
                    bestD = d;
                    best = origin;
                }
            }
        }
        return best;
    }

    private Status penTick() {
        ServerLevel level = self.serverLevel();
        switch (stage) {
            case 0 -> {
                penOrigin = findPenSite();
                if (penOrigin == null) {
                    cooldown = 600;
                    return Status.FAILED;
                }
                penPlan.clear();
                for (int x = 0; x < 5; x++) {
                    for (int z = 0; z < 5; z++) {
                        boolean edge = x == 0 || z == 0 || x == 4 || z == 4;
                        if (edge && !(x == 2 && z == 0)) {
                            penPlan.add(penOrigin.offset(x, 0, z));
                        }
                    }
                }
                stage = 1;
            }
            case 1 -> {
                // fences all around, standing in the middle
                Vec3 center = Vec3.atBottomCenterOf(penOrigin.offset(2, 0, 2));
                if (Motor.horizontalDistance(self.position(), center) > 0.6) {
                    motor.navigate(center, 0.3, false);
                    if (Motor.horizontalDistance(self.position(), center) < 1.2) {
                        motor.moveToward(center);
                    }
                    return motor.stuckCount() > 6 ? Status.FAILED : Status.WORKING;
                }
                motor.stop();
                if (ticks % 3 != 0) {
                    return Status.WORKING;
                }
                BlockPos next = null;
                for (BlockPos p : penPlan) {
                    if (level.getBlockState(p).canBeReplaced()) {
                        next = p;
                        break;
                    }
                }
                if (next == null) {
                    stage = 2;
                    return Status.WORKING;
                }
                int slot = slotOf(s -> s.is(ItemTags.WOODEN_FENCES));
                if (slot < 0) {
                    return Status.FAILED;
                }
                Equipment.select(self, slot);
                if (motor.placeBlockAt(next)) {
                    fencesPlaced++;
                    tries = 0;
                } else if (++tries > 15) {
                    penPlan.remove(next); // something in the way: leave that spot
                    tries = 0;
                }
            }
            case 2 -> {
                // eggs go in while we are still inside
                return throwEggsHere(3);
            }
            case 3 -> {
                return leaveAndClose(true);
            }
            default -> {
                return Status.DONE;
            }
        }
        return Status.WORKING;
    }

    /** Throw the eggs down next to us (one every few ticks); then go on with {@code nextStage}. */
    private Status throwEggsHere(int nextStage) {
        int egg = slotOf(s -> s.is(Items.EGG));
        if (egg < 0) {
            stage = nextStage;
            return Status.WORKING;
        }
        if (ticks % 6 == 0) {
            Equipment.select(self, egg);
            float yaw = (eggsThrown * 73) % 360;
            self.setYRot(yaw);
            self.setYHeadRot(yaw);
            self.setXRot(65f); // down, so it lands inside the fence
            motor.useHeldItem(InteractionHand.MAIN_HAND);
            eggsThrown++;
        }
        motor.stop();
        return Status.WORKING;
    }

    /** Walk out through the gateway and put the gate in (new pen) / shut it (existing pen). */
    private Status leaveAndClose(boolean newPen) {
        ServerLevel level = self.serverLevel();
        BlockPos gate = penOrigin.offset(2, 0, 0);
        Vec3 outside = Vec3.atBottomCenterOf(penOrigin.offset(2, 0, -1)); // right in front of the gateway
        // all the way out: a gate cannot go where we still stand
        boolean out = self.getZ() < penOrigin.getZ() - 0.35 && Motor.horizontalDistance(self.position(), outside) < 1.2;
        if (!out) {
            motor.navigate(outside, 0.4, false);
            if (Motor.horizontalDistance(self.position(), outside) < 3.0) {
                motor.moveToward(outside);
            }
            if (motor.stuckCount() > 8 && Motor.horizontalDistance(self.position(), outside) > 1.5) {
                return Status.FAILED;
            }
            if (motor.stuckCount() <= 8) {
                return Status.WORKING;
            }
        }
        motor.stop();
        BlockState st = level.getBlockState(gate);
        if (newPen && st.canBeReplaced()) {
            int slot = slotOf(s -> s.is(ItemTags.FENCE_GATES));
            if (slot < 0) {
                return Status.FAILED;
            }
            Equipment.select(self, slot);
            if (!motor.placeBlockAt(gate)) {
                return ++stage > 40 ? Status.FAILED : Status.WORKING;
            }
            st = level.getBlockState(gate);
        }
        if (st.getBlock() instanceof FenceGateBlock && st.getValue(BlockStateProperties.OPEN)) {
            Vec3 c = Vec3.atCenterOf(gate);
            motor.lookAt(c);
            self.gameMode.useItemOn(self, level, self.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(c, Direction.NORTH, gate, false));
        }
        if (newPen) {
            Bases.get(self.getServer()).addPen(level.dimension(), penOrigin);
            pensBuilt++;
        }
        return Status.DONE;
    }

    private Status eggsTick() {
        if (penOrigin == null) {
            return Status.FAILED;
        }
        switch (stage) {
            case 0 -> {
                // in through the gate (walking into it opens it)
                Vec3 center = Vec3.atBottomCenterOf(penOrigin.offset(2, 0, 2));
                if (Motor.horizontalDistance(self.position(), center) > 0.8) {
                    motor.navigate(center, 0.5, false);
                    if (Motor.horizontalDistance(self.position(), center) < 3.0) {
                        motor.moveToward(center);
                    }
                    return motor.stuckCount() > 8 ? Status.FAILED : Status.WORKING;
                }
                stage = 1;
            }
            case 1 -> {
                return throwEggsHere(2);
            }
            default -> {
                return leaveAndClose(false);
            }
        }
        return Status.WORKING;
    }

    // ---------------------------------------------------------------- pens for cows, pigs, sheep... fed from the field

    /** Two animals of one kind that eat a crop we have plenty of: worth a pen of their own. */
    public record Livestock(String kind, Item crop, List<Animal> animals) {
    }

    private static final Item[] CROPS = {Items.WHEAT, Items.CARROT, Items.POTATO, Items.BEETROOT};
    public static final int CROP_SURPLUS = 10;
    @Nullable
    private Livestock livestock;
    public int livestockPens;
    public int lured;

    public String livestockDebug = "";

    @Nullable
    public Livestock livestockWanted() {
        if (!Config.get(Config.ALLOW_BLOCK_PLACING, true) || count(s -> s.is(ItemTags.WOODEN_FENCES)) < PEN_FENCES
                && woodPlanks() < 40 || count(s -> s.is(ItemTags.FENCE_GATES)) < 1 && woodPlanks() < 40 + 8) {
            livestockDebug = "no fences";
            return null;
        }
        livestockDebug = "no animals for a crop";
        for (Item crop : CROPS) {
            if (count(s -> s.is(crop)) < CROP_SURPLUS) {
                continue;
            }
            java.util.Map<String, List<Animal>> byKind = new java.util.HashMap<>();
            for (Perception.Seen s : perception.remembered()) {
                if (s.entity instanceof Animal a && a.isAlive() && !a.isBaby() && !(a instanceof TamableAnimal)
                        && !(a instanceof net.minecraft.world.entity.animal.Chicken) && !(a instanceof net.minecraft.world.entity.animal.horse.AbstractHorse)
                        && a.distanceTo(self) < 24 && a.isFood(new ItemStack(crop))
                        && Bases.get(self.getServer()).penAt(self.level().dimension(), a.position()) == null) {
                    byKind.computeIfAbsent(Perception.typeId(a), k -> new ArrayList<>()).add(a);
                }
            }
            for (var e : byKind.entrySet()) {
                livestockDebug = e.getKey() + " x" + e.getValue().size();
                if (e.getValue().size() >= 2 && Bases.get(self.getServer()).nearestPen(self.level().dimension(), self.position(), 48, e.getKey()) == null) {
                    return new Livestock(e.getKey(), crop, e.getValue().subList(0, 2));
                }
            }
        }
        return null;
    }

    /**
     * Fence a pen (gate and all), open the gate, walk out holding the crop - the animals follow a held favourite
     * food - lead them in, put the crop away and slip out, shutting the gate behind us.
     */
    private Status livestockTick() {
        ServerLevel level = self.serverLevel();
        Livestock ls = livestock;
        if (ls == null) {
            return Status.FAILED;
        }
        livestockDebug = "stage " + stage + " t=" + ticks;
        switch (stage) {
            case 0 -> {
                penOrigin = findPenSite();
                if (penOrigin == null) {
                    cooldown = 600;
                    return Status.FAILED;
                }
                penPlan.clear();
                for (int x = 0; x < 5; x++) {
                    for (int z = 0; z < 5; z++) {
                        if ((x == 0 || z == 0 || x == 4 || z == 4) && !(x == 2 && z == 0)) {
                            penPlan.add(penOrigin.offset(x, 0, z));
                        }
                    }
                }
                stage = 1;
            }
            case 1, 2 -> {
                // stage 1: the fences, stage 2 (reused from the chicken pen): the gate, from outside
                if (stage == 1) {
                    Status st = penTick();
                    if (stage == 2) {
                        stage = 3; // fences done (penTick moves on to 2: eggs) -> our own next step
                    }
                    return st == Status.FAILED ? Status.FAILED : Status.WORKING;
                }
            }
            case 3 -> {
                // out through the gateway, put the gate in and open it
                Status st = leaveAndClose(false);
                BlockPos gate = penOrigin.offset(2, 0, 0);
                BlockState gs = level.getBlockState(gate);
                if (gs.canBeReplaced()) {
                    if (st == Status.WORKING) {
                        return Status.WORKING;
                    }
                    int slot = slotOf(s -> s.is(ItemTags.FENCE_GATES));
                    if (slot < 0) {
                        return Status.FAILED;
                    }
                    Equipment.select(self, slot);
                    if (!motor.placeBlockAt(gate) && ++tries > 40) {
                        return Status.FAILED;
                    }
                    return Status.WORKING;
                }
                if (gs.getBlock() instanceof FenceGateBlock && !gs.getValue(BlockStateProperties.OPEN)) {
                    toggleGate(gate);
                }
                Bases.get(self.getServer()).addPen(level.dimension(), penOrigin, ls.kind());
                livestockPens++;
                pensBuilt++;
                stage = 4;
                ticks = 0;
            }
            case 4 -> {
                // fetch them: crop in hand, close enough for them to notice
                Equipment.select(self, slotOf(s -> s.is(ls.crop())));
                Animal far = null;
                for (Animal a : ls.animals()) {
                    if (a.isAlive() && a.distanceTo(self) > 4 && (far == null || a.distanceTo(self) > far.distanceTo(self))) {
                        far = a;
                    }
                }
                if (far != null && ticks < 1200) {
                    motor.navigate(far.position(), 3.0, false);
                    return Status.WORKING;
                }
                stage = 5;
            }
            case 5 -> {
                // lead them into the pen (walk in slowly, crop held high)
                Equipment.select(self, slotOf(s -> s.is(ls.crop())));
                Vec3 center = Vec3.atBottomCenterOf(penOrigin.offset(2, 0, 3));
                if (Motor.horizontalDistance(self.position(), center) > 0.6) {
                    motor.navigate(center, 0.4, false);
                    if (Motor.horizontalDistance(self.position(), center) < 2.5) {
                        motor.moveToward(center);
                    }
                    return ticks > 2400 ? Status.FAILED : Status.WORKING;
                }
                motor.stop();
                Bases.Pen pen = Bases.get(self.getServer()).penAt(level.dimension(), self.position());
                int inside = 0;
                for (Animal a : ls.animals()) {
                    if (a.isAlive() && pen != null && pen.contains(a.position())) {
                        inside++;
                    }
                }
                if (inside >= ls.animals().size()) {
                    lured = inside;
                    emptyHand(); // crop away: they stop following
                    stage = 6;
                    ticks = 0;
                } else if (ticks > 2400) {
                    return Status.FAILED;
                } else if (ticks % 200 == 199) {
                    stage = 4; // somebody lost interest: fetch again
                }
            }
            default -> {
                return leaveAndClose(false);
            }
        }
        return Status.WORKING;
    }

    private void toggleGate(BlockPos gate) {
        Vec3 c = Vec3.atCenterOf(gate);
        motor.lookAt(c);
        self.gameMode.useItemOn(self, self.serverLevel(), self.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(c, Direction.NORTH, gate, false));
    }
}
