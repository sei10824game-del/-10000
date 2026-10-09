package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.Bases;
import com.rlclones.clone.ClonePlayer;
import com.rlclones.ai.strategy.Option;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * R-33..R-40: what makes a clone a someone. A goal and a favourite activity (from its UUID, so they stay the same for life) that
 * tug at the strategy choice; a collector's taste for advancements; flowers round the base; a named favourite animal it
 * looks in on; tidy chests; danger spots told to friends; and a hamlet built together. Everything here runs only while the
 * clone is idle (REST) and nothing threatens it. ponytail: traits are fixed by the UUID, no learning of tastes; add a
 * Brain-backed drift if clones should change over time.
 */
public final class Persona {
    public static final String[] GOALS = {"village", "angler", "gourmet", "collector"};
    private static final Option[] FAVOURITES = {Option.FISH, Option.FARM, Option.EXPLORE, Option.MINE, Option.ANIMALS};
    private static final String[] NAMES = {"Momo", "Pip", "Biscuit", "Clover", "Nugget", "Maple", "Sprout", "Pebble"};

    private final ClonePlayer self;
    private final Motor motor;
    public int goal;
    public int favourite;

    // decor
    private final Map<BlockPos, Integer> decor = new HashMap<>();
    private BlockPos flower;
    private int decorTicks;
    private int collectTicks;
    private long decorCooldown;
    /** Flowers put round bases (tests). */
    public int flowersPlaced;

    // pet
    @Nullable
    private Animal pet;
    private long lastVisit = Long.MIN_VALUE / 2;
    private int visitTicks;
    /** Animals named / visited (tests). */
    public int petsNamed;
    public int petVisits;

    // tidy
    private final Map<BlockPos, Long> tidied = new HashMap<>();
    public int chestsTidied;
    public String tidyDebug = "";

    // danger
    private final List<BlockPos> dangers = new ArrayList<>();
    private float lastHealth = -1;
    public int dangersNoted;

    // project (a hamlet)
    private enum Project {NONE, GOTO, BUILD, FEAST_GOTO, FEAST, PLAN}
    private Project project = Project.NONE;
    private BlockPos meet;
    private Builder builder;
    private int projectTicks;
    private long projectCooldown;
    private final List<BlockPos> huts = new ArrayList<>();
    // dig site shared by a digger (R-44), festival (R-47), diary (R-49), pantry (R-41)
    @Nullable
    private BlockPos digSite;
    private long festivalCooldown;
    public int festivals;
    public long diaryAt = 6000; // (ticks lived)
    public int diaries;
    public int pantryMoves;
    private int pantryRun;
    private long weatherCooldown;
    public int stormRuns;
    public int hutsBuilt;

    public Persona(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
        int h = self.getUUID().hashCode() * 0x9E3779B1;
        goal = Math.floorMod(h >>> 8, GOALS.length);
        favourite = Math.floorMod(h >>> 16, FAVOURITES.length);
    }

    public String goalName() {
        return GOALS[goal];
    }

    private ServerLevel level() {
        return self.serverLevel();
    }

    // ------------------------------------------------------------------ R-34 / R-35 / R-36: the pull on the strategy

    /** An option the clone feels like doing (or null): its goal first, then its favourite. Rolls are uniform [0,1). */
    @Nullable
    public Option pull(int mask, float roll, float roll2, boolean structureKnown, boolean young) {
        if (young && roll < 0.5f && (mask & Option.FOLLOW.bit()) != 0) {
            return Option.FOLLOW; // R-45: a newborn sticks to the grown-ups
        }
        switch (GOALS[goal]) {
            case "village" -> {
                if (structureKnown && roll < 0.25f && (mask & Option.EXPLORE.bit()) != 0) {
                    return Option.EXPLORE; // a structure is known and not visited: off to see it
                }
            }
            case "angler" -> {
                if (roll < 0.2f && (mask & Option.FISH.bit()) != 0) {
                    return Option.FISH;
                }
            }
            case "gourmet" -> {
                if (roll < 0.15f && (mask & Option.FARM.bit()) != 0) {
                    return Option.FARM;
                }
                if (roll < 0.15f && (mask & Option.ANIMALS.bit()) != 0) {
                    return Option.ANIMALS;
                }
            }
            case "collector" -> {
                if (roll < 0.35f && (mask & Option.ACHIEVE.bit()) != 0) {
                    return Option.ACHIEVE; // R-36: advancements are what it likes
                }
            }
            default -> {
            }
        }
        Option fav = FAVOURITES[favourite];
        return roll2 < 0.1f && (mask & fav.bit()) != 0 ? fav : null;
    }

    // ------------------------------------------------------------------ chat

    public boolean onChat(net.minecraft.server.level.ServerPlayer sender, String text, long now) {
        if (text.startsWith("DANGER ")) {
            String[] p = text.split(" ");
            try {
                noteDanger(new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
            } catch (RuntimeException ignored) {
            }
            return true;
        }
        if (text.startsWith("DIGSITE ")) {
            String[] p = text.split(" ");
            try {
                digSite = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            } catch (RuntimeException ignored) {
            }
            return true;
        }
        if (text.startsWith("FESTIVAL ")) {
            String[] p = text.split(" ");
            if (sender != self && project == Project.NONE && now >= festivalCooldown) {
                try {
                    meet = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                    if (meet.distSqr(self.blockPosition()) < 64 * 64) {
                        project = Project.FEAST_GOTO;
                        projectTicks = 0;
                    }
                } catch (RuntimeException ignored) {
                }
            }
            return true;
        }
        if (text.startsWith("PLAN ")) {
            try {
                Bases.Plan pl = Bases.get(self.getServer()).plan(Integer.parseInt(text.split(" ")[1]));
                if (sender != self && pl != null && !pl.complete() && project == Project.NONE && now >= projectCooldown && Builder.buildingBlocks(self) >= Builder.BLOCKS
                        && pl.dimension == level().dimension() && Vec3.atCenterOf(pl.blocks.get(0)).distanceTo(self.position()) < 64) {
                    joinPlan(pl);
                }
            } catch (RuntimeException ignored) {
            }
            return true;
        }
        if (text.startsWith("PROJECT ")) {
            String[] p = text.split(" ");
            if (sender != self && project == Project.NONE && now >= projectCooldown && Builder.buildingBlocks(self) >= Builder.BLOCKS) {
                try {
                    BlockPos at = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                    if (at.distSqr(self.blockPosition()) < 64 * 64) {
                        meet = at;
                        project = Project.GOTO;
                        projectTicks = 0;
                    }
                } catch (RuntimeException ignored) {
                }
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ R-40: danger spots

    private void noteDanger(BlockPos p) {
        for (BlockPos d : dangers) {
            if (d.distManhattan(p) < 6) {
                return;
            }
        }
        if (dangers.size() >= 16) {
            dangers.remove(0);
        }
        dangers.add(p.immutable());
        dangersNoted++;
    }

    /** Where clones have died (all of them, until the server stops): nobody explores towards those spots. */
    private static final List<BlockPos> DEATHS = new ArrayList<>();

    public static void noteDeath(BlockPos p) {
        synchronized (DEATHS) {
            if (DEATHS.size() >= 64) {
                DEATHS.remove(0);
            }
            DEATHS.add(p.immutable());
        }
    }

    public static void forgetDeaths() {
        synchronized (DEATHS) {
            DEATHS.clear();
        }
    }

    /** Is {@code p} near a spot somebody got hurt at or died at? */
    public boolean avoids(Vec3 p) {
        synchronized (DEATHS) {
            for (BlockPos d : DEATHS) {
                if (Motor.horizontalDistance(Vec3.atCenterOf(d), p) < 10) {
                    return true;
                }
            }
        }
        for (BlockPos d : dangers) {
            if (Motor.horizontalDistance(Vec3.atCenterOf(d), p) < 10) {
                return true;
            }
        }
        return false;
    }

    private void watchHealth() {
        float hp = self.getHealth();
        if (lastHealth >= 0 && hp < lastHealth - 2.5f && self.getLastDamageSource() != null
                && self.getLastDamageSource().is(net.minecraft.world.damagesource.DamageTypes.FALL)) {
            BlockPos at = self.blockPosition();
            noteDanger(at);
            Chat.say(self, Component.literal("DANGER"), "DANGER " + at.getX() + " " + at.getY() + " " + at.getZ());
        }
        lastHealth = hp;
    }

    // ------------------------------------------------------------------ the idle-time habits

    /** Called every tick. @return true while one of the habits has the clone's hands (it only starts while the clone rests). */
    public boolean tick(long now, @Nullable Option option, boolean threatened) {
        watchHealth();
        if (self.isCreative() || self.isPassenger() || self.isInWater() || threatened) {
            return false;
        }
        boolean idle = option == Option.REST;
        if (project == Project.FEAST_GOTO || project == Project.FEAST) {
            return festivalTick(now);
        }
        if (project == Project.PLAN) {
            return planStep(now);
        }
        if (project != Project.NONE) {
            return projectTick(now);
        }
        if (decorBusy()) {
            return decorTick(now);
        }
        if (!idle || !self.onGround() || (now + self.getId()) % 20 != 0) {
            return visitTicks > 0 && petTick(now);
        }
        tidyTick(now);
        pantryTick(now);
        diaryTick(now);
        signTick();
        if (stormTick(now) || pantryRun > 0 && hungryRun(now)) {
            return true;
        }
        if (visitTicks > 0 || petTick(now)) {
            return true;
        }
        if (now >= festivalCooldown && proposeFestival(now)) {
            return true;
        }
        if (now >= projectCooldown && proposeProject(now)) {
            return true;
        }
        if (repairTick(now)) {
            return true;
        }
        if (now >= projectCooldown && proposeWall(now)) {
            return true;
        }
        return decorTick(now);
    }

    // ------------------------------------------------------------------ R-37: flowers round the base

    private boolean decorBusy() {
        return flower != null || collectTicks > 0 || decorTicks > 0;
    }

    private boolean decorTick(long now) {
        if (now < decorCooldown || !Config.get(Config.ALLOW_BLOCK_PLACING, true)) {
            return false;
        }
        Bases.Base base = Bases.get(self.getServer()).nearest(level().dimension(), self.position(), 14);
        if (base == null || decor.getOrDefault(base.center, 0) >= 4) {
            return false;
        }
        if (++decorTicks > 600) {
            decorTicks = 0;
            flower = null;
            collectTicks = 0;
            decorCooldown = now + 2400;
            return false;
        }
        var inv = self.getInventory();
        int slot = -1;
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).is(ItemTags.SMALL_FLOWERS)) {
                slot = i;
                break;
            }
        }
        if (collectTicks > 0) {
            collectTicks--;
            motor.navigate(Vec3.atBottomCenterOf(flower == null ? self.blockPosition() : flower), 0.3, false);
            if (collectTicks == 0) {
                flower = null;
            }
            return true;
        }
        if (slot < 0) {
            if (flower == null) {
                for (BlockPos p : BlockPos.betweenClosed(self.blockPosition().offset(-10, -2, -10), self.blockPosition().offset(10, 2, 10))) {
                    BlockState st = level().getBlockState(p);
                    if (st.is(BlockTags.SMALL_FLOWERS) && base.center.distSqr(p) > 36) {
                        flower = p.immutable();
                        break;
                    }
                }
                if (flower == null) {
                    decorTicks = 0;
                    decorCooldown = now + 1200;
                    return false;
                }
            }
            if (!level().getBlockState(flower).is(BlockTags.SMALL_FLOWERS)) {
                flower = null;
                return true;
            }
            if (self.getEyePosition().distanceTo(Vec3.atCenterOf(flower)) > Motor.BLOCK_REACH - 0.5) {
                motor.navigate(Vec3.atBottomCenterOf(flower), 1.5, false);
                return true;
            }
            Equipment.select(self, -1);
            if (motor.mine(flower)) {
                collectTicks = 25; // walk over the drop
            }
            return true;
        }
        // flowers in the bag: a spot 3..6 blocks from the base chest, on grass, in the open
        BlockPos spot = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(base.center.offset(-6, -1, -6), base.center.offset(6, 2, 6))) {
            double d = p.distSqr(base.center);
            if (d < 9 || d > 36 || d >= bestD || !level().getBlockState(p).isAir() || !level().getBlockState(p.below()).is(Blocks.GRASS_BLOCK)) {
                continue;
            }
            spot = p.immutable();
            bestD = d;
        }
        if (spot == null) {
            decorTicks = 0;
            decorCooldown = now + 2400;
            return false;
        }
        if (self.getEyePosition().distanceTo(Vec3.atCenterOf(spot)) > Motor.BLOCK_REACH - 0.7) {
            motor.navigate(Vec3.atBottomCenterOf(spot), 2.0, false);
            return true;
        }
        Equipment.select(self, slot);
        if (motor.placeBlockAt(spot)) {
            decor.merge(base.center, 1, Integer::sum);
            flowersPlaced++;
            decorTicks = 0;
            decorCooldown = now + 100;
        }
        return true;
    }

    // ------------------------------------------------------------------ R-38: a favourite animal

    private boolean petTick(long now) {
        if (pet != null && (!pet.isAlive() || pet.level() != level())) {
            pet = null;
        }
        if (pet == null) {
            if ((now + self.getId()) % 200 != 0) {
                return false;
            }
            Animal best = null;
            double bestD = 16 * 16;
            for (Animal a : level().getEntitiesOfClass(Animal.class, self.getBoundingBox().inflate(16))) {
                double d = a.distanceToSqr(self);
                if (!a.hasCustomName() && !a.isBaby() && d < bestD) {
                    bestD = d;
                    best = a;
                }
            }
            if (best == null) {
                return false;
            }
            pet = best;
            String name = NAMES[Math.floorMod(self.getUUID().hashCode() + pet.getId(), NAMES.length)];
            pet.setCustomName(Component.literal(name));
            petsNamed++;
            Chat.say(self, Component.literal(name + "!"), "PET " + name);
            lastVisit = now - 2300; // the first look-in comes soon after the naming
            return false;
        }
        if (visitTicks == 0) {
            if (now - lastVisit < 2400 || pet.distanceTo(self) > 40) {
                return false;
            }
            visitTicks = 1;
        }
        if (++visitTicks > 300) {
            visitTicks = 0;
            lastVisit = now;
            return false;
        }
        if (pet.distanceTo(self) > 3.0) {
            motor.navigate(pet.position(), 2.0, false);
            return true;
        }
        motor.stop();
        motor.lookAt(pet.getEyePosition());
        if (visitTicks > 40) {
            if (pet.getHealth() < pet.getMaxHealth()) {
                feedPet(); // R-38: hurt: a bite of its favourite food, if we have it
            }
            petVisits++;
            visitTicks = 0;
            lastVisit = now;
            return false;
        }
        return true;
    }

    /** Pets fed (tests). */
    public int petsFed;

    private void feedPet() {
        var inv = self.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack st = inv.items.get(i);
            if (!st.isEmpty() && pet.isFood(st)) {
                pet.heal(4f); // (a cow eats wheat, a pig a carrot: it also falls in love, which the breeding code can use)
                st.shrink(1);
                petsFed++;
                return;
            }
        }
    }

    // ------------------------------------------------------------------ R-39: tidy chests

    private static int category(ItemStack s) {
        if (s.isEdible()) {
            return 0;
        }
        if (s.getMaxStackSize() == 1) {
            return 1; // tools, weapons, armour
        }
        if (s.is(net.minecraftforge.common.Tags.Items.INGOTS) || s.is(net.minecraftforge.common.Tags.Items.ORES) || s.is(net.minecraftforge.common.Tags.Items.GEMS)
                || s.is(net.minecraftforge.common.Tags.Items.RAW_MATERIALS) || s.is(net.minecraft.world.item.Items.COAL)) {
            return 2;
        }
        return s.getItem() instanceof net.minecraft.world.item.BlockItem ? 3 : 4;
    }

    private void tidyTick(long now) {
        Bases.Base base = Bases.get(self.getServer()).nearest(level().dimension(), self.position(), 8);
        tidyDebug = base == null ? "no base" : "base chests=" + base.chests.size() + " pos=" + self.blockPosition().toShortString();
        if (base == null) {
            return;
        }
        for (BlockPos pos : base.chests) {
            if (self.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > 5 || now - tidied.getOrDefault(pos, -100000L) < 6000) {
                continue;
            }
            tidied.put(pos, now);
            if (level().getBlockEntity(pos) instanceof Container c) {
                List<ItemStack> all = new ArrayList<>();
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack s = c.getItem(i);
                    if (s.isEmpty()) {
                        continue;
                    }
                    boolean merged = false;
                    for (ItemStack o : all) {
                        if (ItemStack.isSameItemSameTags(o, s) && o.getCount() < o.getMaxStackSize()) {
                            int n = Math.min(s.getCount(), o.getMaxStackSize() - o.getCount());
                            o.grow(n);
                            s.shrink(n);
                            merged = s.isEmpty();
                            if (merged) {
                                break;
                            }
                        }
                    }
                    if (!merged) {
                        all.add(s.copy());
                    }
                }
                all.sort(Comparator.comparingInt(Persona::category).thenComparing(s -> s.getItem().builtInRegistryHolder().key().location().toString()));
                for (int i = 0; i < c.getContainerSize(); i++) {
                    c.setItem(i, i < all.size() ? all.get(i) : ItemStack.EMPTY);
                }
                c.setChanged();
                chestsTidied++;
                self.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            return;
        }
    }

    // ------------------------------------------------------------------ R-33: a hamlet together

    private boolean proposeProject(long now) {
        if (Builder.buildingBlocks(self) < Builder.BLOCKS || !Config.get(Config.ALLOW_BLOCK_PLACING, true)
                || Bases.get(self.getServer()).nearest(level().dimension(), self.position(), 24) == null
                || self.getRandom().nextFloat() > 0.02f) {
            return false;
        }
        meet = self.blockPosition();
        project = Project.BUILD;
        projectTicks = 0;
        startHut();
        Chat.say(self, Component.literal("PROJECT"), "PROJECT " + meet.getX() + " " + meet.getY() + " " + meet.getZ());
        return project == Project.BUILD;
    }

    private void startHut() {
        List<BlockPos> avoid = new ArrayList<>(huts);
        for (Bases.Base b : Bases.get(self.getServer()).bases) {
            avoid.add(b.center);
        }
        BlockPos site = Builder.findSite(self, avoid);
        if (site == null) {
            project = Project.NONE;
            projectCooldown = level().getGameTime() + 6000;
            return;
        }
        if (builder == null) {
            builder = new Builder(self, motor);
        }
        builder.start(site);
        huts.add(builder.center());
    }

    private boolean projectTick(long now) {
        if (++projectTicks > 4800) {
            project = Project.NONE;
            projectCooldown = now + 12000;
            return false;
        }
        if (project == Project.GOTO) {
            if (Motor.horizontalDistance(self.position(), Vec3.atCenterOf(meet)) > 8) {
                motor.navigate(Vec3.atBottomCenterOf(meet), 3.0, false);
                if (motor.stuckCount() > 6) {
                    project = Project.NONE;
                    projectCooldown = now + 6000;
                    return false;
                }
                return true;
            }
            project = Project.BUILD;
            startHut();
            return project == Project.BUILD;
        }
        Builder.Status st = builder.tick();
        if (st != Builder.Status.WORKING) {
            if (st == Builder.Status.DONE) {
                hutsBuilt++;
            }
            project = Project.NONE;
            projectCooldown = now + 12000;
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ R-59: a big job shared with others

    private PlanWork planWork;
    /** Plans made / finished by walls (tests). */
    public int plansStarted;
    public int plansHelped;

    public PlanWork planWork() {
        if (planWork == null) {
            planWork = new PlanWork(self, motor);
        }
        return planWork;
    }

    /** Take part in a plan (called when a call for hands is heard, and by tests). */
    public void joinPlan(Bases.Plan plan) {
        planWork().begin(plan);
        project = Project.PLAN;
        projectTicks = 0;
        plansHelped++;
    }

    private boolean planStep(long now) {
        PlanWork.Status st = planWork().tick();
        if (st != PlanWork.Status.WORKING) {
            project = Project.NONE;
            projectCooldown = now + 6000;
            return false;
        }
        return true;
    }

    /** R-54: a finished plan near us with blocks missing (broken, burnt, blown up): its segments are open again and we mend them. */
    private boolean repairTick(long now) {
        if (Builder.buildingBlocks(self) < 16 || !Config.get(Config.ALLOW_BLOCK_PLACING, true) || (now + self.getId()) % 100 != 0) {
            return false;
        }
        for (Bases.Plan plan : Bases.get(self.getServer()).plans) {
            if (plan.dimension != level().dimension() || !plan.complete() || Vec3.atCenterOf(plan.blocks.get(0)).distanceTo(self.position()) > 48) {
                continue;
            }
            boolean any = false;
            for (int i = 0; i < plan.blocks.size(); i++) {
                if (level().getBlockState(plan.blocks.get(i)).canBeReplaced()) {
                    plan.reopen(i / plan.segSize);
                    any = true;
                }
            }
            if (any) {
                joinPlan(plan);
                plansRepaired++;
                return true;
            }
        }
        return false;
    }

    /** Plans reopened for repair (tests). */
    public int plansRepaired;

    /** A wall of 12 blocks along the ground (3 segments of 4) beside the base: something for several to do at once. */
    private boolean proposeWall(long now) {
        Bases bases = Bases.get(self.getServer());
        Bases.Base base = bases.nearest(level().dimension(), self.position(), 24);
        if (base == null || Builder.buildingBlocks(self) < Builder.BLOCKS || !Config.get(Config.ALLOW_BLOCK_PLACING, true) || self.getRandom().nextFloat() > 0.01f
                || bases.openPlanNear(level().dimension(), self.position(), 64) != null) {
            return false;
        }
        for (int[] d : new int[][]{{1, 0}, {0, 1}, {-1, 0}, {0, -1}}) {
            List<BlockPos> line = new ArrayList<>();
            BlockPos o = base.center.offset(-d[0] * 3 + 4 * (d[1] != 0 ? 1 : 0), 0, -d[1] * 3 + 4 * (d[0] != 0 ? 1 : 0));
            for (int i = 0; i < 12; i++) {
                BlockPos p = o.offset(d[0] * i, 0, d[1] * i);
                if (!level().getBlockState(p).canBeReplaced() || !level().getBlockState(p.below()).isFaceSturdy(level(), p.below(), net.minecraft.core.Direction.UP)) {
                    line = null;
                    break;
                }
                line.add(p);
            }
            if (line != null) {
                Bases.Plan plan = bases.addPlan(level().dimension(), "WALL", line, 4);
                plansStarted++;
                joinPlan(plan);
                Chat.say(self, Component.literal("PLAN"), "PLAN " + plan.id);
                return true;
            }
        }
        projectCooldown = now + 6000;
        return false;
    }

    // ------------------------------------------------------------------ R-41: the pantry

    private static boolean isFood(ItemStack s) {
        return s.isEdible() && !s.is(Items.ROTTEN_FLESH) && !s.is(Items.SPIDER_EYE) && !s.is(Items.POISONOUS_POTATO) && !s.is(Items.PUFFERFISH);
    }

    private static ItemStack insert(Container c, ItemStack s) {
        for (int i = 0; i < c.getContainerSize() && !s.isEmpty(); i++) {
            ItemStack o = c.getItem(i);
            if (o.isEmpty()) {
                c.setItem(i, s.copy());
                return ItemStack.EMPTY;
            }
            if (ItemStack.isSameItemSameTags(o, s) && o.getCount() < o.getMaxStackSize()) {
                int n = Math.min(s.getCount(), o.getMaxStackSize() - o.getCount());
                o.grow(n);
                s.shrink(n);
            }
        }
        return s;
    }

    /** At the base chest: food beyond 12 goes in (a store for the lean days); empty-handed and hungry, some comes out. */
    private void pantryTick(long now) {
        Bases.Base base = Bases.get(self.getServer()).nearest(level().dimension(), self.position(), 8);
        if (base == null) {
            return;
        }
        for (BlockPos pos : base.chests) {
            if (self.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > 5 || !(level().getBlockEntity(pos) instanceof Container c)) {
                continue;
            }
            int have = FoodAid.foodItems(self);
            var inv = self.getInventory();
            if (have > 12) {
                int excess = have - 12;
                for (int i = 0; i < inv.items.size() && excess > 0; i++) {
                    ItemStack s = inv.items.get(i);
                    if (isFood(s)) {
                        int n = Math.min(excess, s.getCount());
                        ItemStack left = insert(c, s.split(n));
                        s.grow(left.getCount()); // (no room: back in the bag)
                        excess -= n - left.getCount();
                        pantryMoves += n - left.getCount();
                    }
                }
                c.setChanged();
            } else if (have == 0 && self.getFoodData().getFoodLevel() < 14) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack s = c.getItem(i);
                    if (isFood(s)) {
                        int n = Math.min(8, s.getCount());
                        inv.add(s.split(n));
                        pantryMoves += n;
                        c.setChanged();
                        break;
                    }
                }
            }
            return;
        }
    }

    /** Hungry with nothing to eat, a base with food in its chest within reach: go there (the pantryTick then takes some). */
    private boolean hungryRun(long now) {
        if (--pantryRun <= 0) {
            return false;
        }
        Bases.Base base = Bases.get(self.getServer()).nearest(level().dimension(), self.position(), 64);
        if (base == null || FoodAid.foodItems(self) > 0) {
            pantryRun = 0;
            return false;
        }
        if (Motor.horizontalDistance(self.position(), Vec3.atCenterOf(base.center)) > 3) {
            motor.navigate(Vec3.atBottomCenterOf(base.center), 2.0, false);
            return true;
        }
        pantryRun = 0;
        return false;
    }

    /** Called when the clone is hungry and has no food (by the controller each second): maybe the base chest has some. */
    public void wantPantry() {
        if (pantryRun <= 0) {
            Bases.Base base = Bases.get(self.getServer()).nearest(level().dimension(), self.position(), 64);
            if (base != null) {
                for (BlockPos pos : base.chests) {
                    if (level().getBlockEntity(pos) instanceof Container c) {
                        for (int i = 0; i < c.getContainerSize(); i++) {
                            if (isFood(c.getItem(i))) {
                                pantryRun = 600;
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ R-44: a dig site told by a digger

    public void announceDig() {
        BlockPos at = self.blockPosition();
        Chat.say(self, Component.literal("DIGSITE"), "DIGSITE " + at.getX() + " " + at.getY() + " " + at.getZ());
    }

    @Nullable
    public Vec3 digTarget() {
        return digSite == null || digSite.distSqr(self.blockPosition()) > 128 * 128 ? null : Vec3.atBottomCenterOf(digSite);
    }

    // ------------------------------------------------------------------ R-46: out of a thunderstorm

    private boolean stormTick(long now) {
        boolean storm = level().isThundering() && level().canSeeSky(self.blockPosition());
        // R-53: after dark, one with no weapon or badly hurt does not wander about: home to the base, where the doors are
        boolean night = level().dimension() == net.minecraft.world.level.Level.OVERWORLD && level().getDayTime() % 24000L >= 13000L
                && (!Equipment.isArmed(self) || self.getHealth() < self.getMaxHealth() * 0.6f);
        if (!(storm || night) || now < weatherCooldown) {
            return false;
        }
        Bases.Base base = Bases.get(self.getServer()).nearest(level().dimension(), self.position(), 80);
        if (base == null) {
            return false;
        }
        if (Motor.horizontalDistance(self.position(), Vec3.atCenterOf(base.center)) > 3) {
            motor.navigate(Vec3.atBottomCenterOf(base.center), 2.0, false);
            if (motor.stuckCount() > 6) {
                weatherCooldown = now + 1200;
            }
            stormRuns++;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ R-47: a gathering at the base

    private boolean proposeFestival(long now) {
        Bases.Base base = Bases.get(self.getServer()).nearest(level().dimension(), self.position(), 24);
        if (base == null || self.getRandom().nextFloat() > 0.003f || now < 12000) {
            return false;
        }
        meet = base.center;
        project = Project.FEAST_GOTO;
        projectTicks = 0;
        Chat.say(self, Component.literal("FESTIVAL"), "FESTIVAL " + meet.getX() + " " + meet.getY() + " " + meet.getZ());
        return true;
    }

    private boolean festivalTick(long now) {
        if (++projectTicks > 1500) {
            project = Project.NONE;
            festivalCooldown = now + 24000;
            return false;
        }
        if (project == Project.FEAST_GOTO) {
            if (Motor.horizontalDistance(self.position(), Vec3.atCenterOf(meet)) > 4) {
                motor.navigate(Vec3.atBottomCenterOf(meet), 3.0, false);
                if (motor.stuckCount() > 6) {
                    project = Project.NONE;
                    festivalCooldown = now + 6000;
                    return false;
                }
                return true;
            }
            project = Project.FEAST;
            projectTicks = 0;
            return true;
        }
        motor.stop();
        if (projectTicks % 40 == 0 && self.onGround()) {
            motor.jump(); // a little hop: it is a party
        }
        if (projectTicks > 300) {
            festivals++;
            project = Project.NONE;
            festivalCooldown = now + 24000;
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ R-50: a sign by the base chest

    /** Signs put up (tests). */
    public int signsPlaced;
    private boolean signDone;
    /** Why no sign went up (tests). */
    public String signWhy = "";

    /** With a sign in the bag, standing by a base chest on firm ground: put it up there, saying whose chest it is. */
    private void signTick() {
        if (signDone || !Config.get(Config.ALLOW_BLOCK_PLACING, true)) {
            return;
        }
        int slot = -1;
        for (int i = 0; i < self.getInventory().items.size(); i++) {
            if (self.getInventory().items.get(i).is(ItemTags.SIGNS)) {
                slot = i;
                break;
            }
        }
        Bases.Base base = Bases.get(self.getServer()).nearest(level().dimension(), self.position(), 6);
        BlockPos feet = self.blockPosition();
        if (slot < 0 || base == null || !level().getBlockState(feet).canBeReplaced() || !level().getBlockState(feet.below()).isFaceSturdy(level(), feet.below(), net.minecraft.core.Direction.UP)) {
            signWhy = "slot=" + slot + " base=" + (base != null) + " feet=" + level().getBlockState(feet).getBlock() + " below=" + level().getBlockState(feet.below()).getBlock();
            return;
        }
        int before = self.getInventory().selected;
        Equipment.select(self, slot);
        boolean used = motor.useOnTopFace(feet.below());
        signWhy = "used=" + used + " held=" + self.getMainHandItem().getItem() + " now=" + level().getBlockState(feet).getBlock();
        if (used && level().getBlockEntity(feet) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
            String name = self.getGameProfile().getName();
            sign.updateText(t -> t.setMessage(0, Component.literal(name)).setMessage(1, Component.literal("lives here")), true);
            signsPlaced++;
            signDone = true;
        }
        if (before < net.minecraft.world.entity.player.Inventory.getSelectionSize()) {
            self.getInventory().selected = before;
        }
    }

    // ------------------------------------------------------------------ R-49: a diary in the base chest

    private void diaryTick(long now) {
        if (self.tickCount < diaryAt) {
            return;
        }
        Bases.Base base = Bases.get(self.getServer()).nearest(level().dimension(), self.position(), 8);
        if (base == null) {
            return;
        }
        String name = self.getGameProfile().getName();
        for (BlockPos pos : base.chests) {
            if (self.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > 5 || !(level().getBlockEntity(pos) instanceof Container c)) {
                continue;
            }
            var brain = self.getCloneBrain();
            String text = "Day " + (level().getDayTime() / 24000L) + " - " + name + ", the " + goalName() + " type.\nHP " + (int) self.getHealth() + ", food "
                    + self.getFoodData().getFoodLevel() + ", died " + (brain == null ? 0 : brain.deaths) + " times.\nSites seen: "
                    + (self.controller() == null ? 0 : self.controller().structures().found) + ".";
            ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
            var tag = book.getOrCreateTag();
            tag.putString("title", "Diary of " + name);
            tag.putString("author", name);
            ListTag pages = new ListTag();
            pages.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(text))));
            tag.put("pages", pages);
            if (insert(c, book).isEmpty()) {
                c.setChanged();
                diaries++;
            }
            diaryAt = self.tickCount + 12000;
            return;
        }
    }

    public boolean projectActive() {
        return project != Project.NONE;
    }
}
