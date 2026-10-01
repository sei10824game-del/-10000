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

    private enum Job {TAME, BREED, PEN, EGGS}

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
    @Nullable
    private BlockPos penOrigin;
    private final List<BlockPos> penPlan = new ArrayList<>();

    public int tamed;
    public int fed;
    public int pensBuilt;
    public int eggsThrown;
    public int fencesPlaced;

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
    private Animal tameCandidate() {
        Animal best = null;
        double bestD = 16;
        for (Perception.Seen s : perception.remembered()) {
            if (!(s.entity instanceof Animal a) || !a.isAlive() || !s.visible) {
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
    public boolean needsFences() {
        return penWanted() && count(s -> s.is(ItemTags.WOODEN_FENCES)) < PEN_FENCES;
    }

    public boolean needsGate() {
        return penWanted() && count(s -> s.is(ItemTags.FENCE_GATES)) < 1;
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
        return tameCandidate() != null || breedPair() != null || canBuildPen() || eggsForPen();
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
        if (t != null) {
            job = Job.TAME;
            target = t;
        } else if (pair != null) {
            job = Job.BREED;
            target = pair[0];
            mate = pair[1];
        } else if (canBuildPen()) {
            job = Job.PEN;
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
                } else {
                    penPlan.remove(next);
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
        Vec3 outside = Vec3.atBottomCenterOf(penOrigin.offset(2, 0, -2));
        if (Motor.horizontalDistance(self.position(), outside) > 0.7) {
            motor.navigate(outside, 0.4, false);
            if (Motor.horizontalDistance(self.position(), outside) < 2.0) {
                motor.moveToward(outside);
            }
            return motor.stuckCount() > 8 ? Status.FAILED : Status.WORKING;
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
}
