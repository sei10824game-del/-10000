package com.rlclones.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rlclones.ai.brain.Brain;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Advancements as goals for when there is nothing else to do. Looking at an advancement, the clone works out what
 * unlocks it from its criteria ("obtain minecraft:lava_bucket", "kill minecraft:zombie", "eat anything"...) and
 * remembers that; then it picks one it can do with what is at hand and does it: fills a bucket, crafts the item,
 * mines the block that drops it, eats, hunts.
 */
public final class Achievements {
    public enum Status {WORKING, DONE, FAILED}

    /** One thing that unlocks an advancement. {@code what}: obtain / eat / kill / tame / breed / enter / other. */
    public record Condition(String what, List<String> ids) {
        @Override
        public String toString() {
            return ids.isEmpty() ? what : what + " " + String.join("|", ids);
        }
    }

    public record Goal(ResourceLocation advancement, Condition condition) {
    }

    private final ClonePlayer self;
    private final Motor motor;
    private final Perception perception;
    private final Supplier<Brain> brain;
    private final Crafting crafting;
    @Nullable
    private Goal goal;
    private int ticks;
    @Nullable
    private BlockPos spot;
    private long lastScan = -100000;
    @Nullable
    private Goal planned;

    public int examined;
    public int goalsStarted;
    public int unlocked;
    public String debug = "";

    public Achievements(ClonePlayer self, Motor motor, Perception perception, Supplier<Brain> brain, Crafting crafting) {
        this.self = self;
        this.motor = motor;
        this.perception = perception;
        this.brain = brain;
        this.crafting = crafting;
    }

    // ------------------------------------------------------------------ understanding

    /** What unlocks {@code adv}, read from its criteria (each criterion its own condition). */
    public static List<Condition> understand(Advancement adv) {
        List<Condition> out = new ArrayList<>();
        for (Map.Entry<String, Criterion> e : adv.getCriteria().entrySet()) {
            CriterionTriggerInstance t = e.getValue().getTrigger();
            if (t == null) {
                continue;
            }
            String trigger = t.getCriterion().getPath();
            JsonObject json;
            try {
                json = t.serializeToJson(SerializationContext.INSTANCE);
            } catch (RuntimeException ex) {
                json = new JsonObject();
            }
            List<String> ids = new ArrayList<>();
            switch (trigger) {
                case "inventory_changed" -> {
                    collect(json.get("items"), ids, "items", "tag");
                    out.add(new Condition("obtain", ids));
                }
                case "consume_item" -> {
                    collect(json.get("item"), ids, "items", "tag");
                    out.add(new Condition("eat", ids));
                }
                case "player_killed_entity" -> {
                    collect(json.get("entity"), ids, "type");
                    out.add(new Condition("kill", ids));
                }
                case "tame_animal" -> {
                    collect(json.get("entity"), ids, "type");
                    out.add(new Condition("tame", ids));
                }
                case "bred_animals" -> {
                    collect(json.get("child"), ids, "type");
                    out.add(new Condition("breed", ids));
                }
                case "changed_dimension" -> {
                    if (json.has("to")) {
                        ids.add(json.get("to").getAsString());
                    }
                    out.add(new Condition("enter", ids));
                }
                case "placed_block" -> {
                    collect(json, ids, "blocks", "block");
                    out.add(new Condition("place", ids));
                }
                case "filled_bucket" -> {
                    collect(json.get("item"), ids, "items");
                    out.add(new Condition("obtain", ids));
                }
                default -> out.add(new Condition(trigger, ids));
            }
        }
        return out;
    }

    /** All string values (or string arrays) stored under {@code keys} anywhere inside {@code e}. */
    private static void collect(@Nullable JsonElement e, List<String> out, String... keys) {
        if (e == null || e.isJsonNull()) {
            return;
        }
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                collect(x, out, keys);
            }
            return;
        }
        if (!e.isJsonObject()) {
            return;
        }
        JsonObject o = e.getAsJsonObject();
        for (Map.Entry<String, JsonElement> en : o.entrySet()) {
            boolean wanted = false;
            for (String k : keys) {
                wanted |= k.equals(en.getKey());
            }
            JsonElement v = en.getValue();
            if (wanted && v.isJsonPrimitive()) {
                out.add((en.getKey().equals("tag") ? "#" : "") + v.getAsString());
            } else if (wanted && v.isJsonArray()) {
                for (JsonElement x : v.getAsJsonArray()) {
                    if (x.isJsonPrimitive()) {
                        out.add(x.getAsString());
                    }
                }
            } else {
                collect(v, out, keys);
            }
        }
    }

    private boolean done(Advancement adv) {
        AdvancementProgress p = self.getAdvancements().getOrStartProgress(adv);
        return p.isDone();
    }

    /** Look through the advancements not made yet (learning what each needs); the first one doable now. */
    @Nullable
    private Goal scan() {
        if (self.getServer() == null) {
            return null;
        }
        Brain b = brain.get();
        Goal best = null;
        for (Advancement adv : self.getServer().getAdvancements().getAllAdvancements()) {
            if (adv.getDisplay() == null || adv.getId().getPath().startsWith("recipes/") || done(adv)) {
                continue;
            }
            List<Condition> conds = understand(adv);
            if (conds.isEmpty()) {
                continue;
            }
            String id = adv.getId().toString();
            if (b.advancementFact(id) == null) {
                StringBuilder sb = new StringBuilder();
                for (Condition c : conds) {
                    if (sb.length() > 0) {
                        sb.append(" / ");
                    }
                    sb.append(c);
                }
                b.learnAdvancement(id, sb.toString());
                examined++;
            }
            if (best != null) {
                continue;
            }
            AdvancementProgress progress = self.getAdvancements().getOrStartProgress(adv);
            List<String> remaining = new ArrayList<>();
            progress.getRemainingCriteria().forEach(remaining::add);
            if (remaining.size() != 1 && conds.size() > 1) {
                continue; // several things still to do: too far off for now
            }
            for (Condition c : conds) {
                if (doable(c)) {
                    best = new Goal(adv.getId(), c);
                    break;
                }
            }
        }
        return best;
    }

    @Nullable
    private static Item item(String id) {
        if (id.startsWith("#")) {
            return null;
        }
        Item it = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        return it == null || it == Items.AIR ? null : it;
    }

    /** Can we do this right now with what we carry and what is around? */
    private boolean doable(Condition c) {
        switch (c.what()) {
            case "obtain" -> {
                for (String id : c.ids()) {
                    Item it = item(id);
                    if (it != null && self.getInventory().countItem(it) > 0) {
                        return false; // already carried: the game hands it out by itself
                    }
                    if (it != null && (fluidFor(it) != null && hasBucket() && fluidNear(fluidFor(it)) != null
                            || craftable(it) || dropSource(it) != null)) {
                        return true;
                    }
                }
                return false;
            }
            case "eat" -> {
                if (c.ids().isEmpty()) {
                    return FoodAid.foodItems(self) > 0 && self.getFoodData().needsFood();
                }
                for (String id : c.ids()) {
                    Item it = item(id);
                    if (it != null && self.getInventory().countItem(it) > 0 && self.getFoodData().needsFood()) {
                        return true;
                    }
                }
                return false;
            }
            case "kill" -> {
                return prey(c) != null && Equipment.isArmed(self) && self.getHealth() > 12;
            }
            default -> {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ means

    @Nullable
    private static Fluid fluidFor(Item it) {
        return it == Items.LAVA_BUCKET ? Fluids.LAVA : it == Items.WATER_BUCKET ? Fluids.WATER : null;
    }

    private boolean hasBucket() {
        return self.getInventory().countItem(Items.BUCKET) > 0;
    }

    @Nullable
    private BlockPos fluidNear(Fluid f) {
        ServerLevel level = self.serverLevel();
        BlockPos feet = self.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-12, -4, -12), feet.offset(12, 3, 12))) {
            var fs = level.getFluidState(p);
            if (fs.isSource() && fs.getType().isSame(f) && level.getBlockState(p.above()).isAir()) {
                double d = p.distSqr(feet);
                if (d < bestD) {
                    bestD = d;
                    best = p.immutable();
                }
            }
        }
        return best;
    }

    private boolean craftable(Item it) {
        for (CraftingRecipe r : Crafting.recipesFor(self, s -> s.is(it))) {
            if (Crafting.canCraft(self, r)) {
                return true;
            }
        }
        return false;
    }

    /** A block nearby whose drop is {@code it} (and that we can harvest). */
    @Nullable
    private BlockPos dropSource(Item it) {
        for (Map.Entry<BlockPos, Perception.BlockKind> e : perception.blocks().entrySet()) {
            BlockPos p = e.getKey();
            if (p.distSqr(self.blockPosition()) > 24 * 24) {
                continue;
            }
            BlockState st = self.level().getBlockState(p);
            Item drop = st.is(net.minecraft.world.level.block.Blocks.STONE) ? Items.COBBLESTONE
                    : st.is(net.minecraft.world.level.block.Blocks.DEEPSLATE) ? Items.COBBLED_DEEPSLATE : st.getBlock().asItem();
            if (drop == it && Equipment.canHarvest(self, st)) {
                return p;
            }
        }
        return null;
    }

    @Nullable
    private LivingEntity prey(Condition c) {
        LivingEntity best = null;
        for (Perception.Seen s : perception.remembered()) {
            if (s.entity instanceof LivingEntity l && l.isAlive() && s.visible && l.distanceTo(self) < 16 && !Senses.isAllyOf(l, self)
                    && !(l instanceof net.minecraft.world.entity.player.Player)
                    // "kill something": a monster - never our livestock, pets or penned animals just for a tick in the list
                    && (c.ids().isEmpty() ? Senses.isHostileTo(l, self) : c.ids().contains(Perception.typeId(l)))
                    && !Animals.protectedAnimal(l) && !(l instanceof net.minecraft.world.entity.TamableAnimal t && t.isTame())
                    && (best == null || l.distanceTo(self) < best.distanceTo(self))) {
                best = l;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the option

    /** Something to aim for (scanned at most every 30 s). */
    public boolean hasGoal(long now) {
        if (now - lastScan > 600) {
            lastScan = now;
            planned = scan();
        }
        return planned != null;
    }

    public void begin() {
        goal = planned;
        planned = null;
        lastScan = -100000;
        ticks = 0;
        spot = null;
        motor.resetStuck();
        if (goal != null) {
            goalsStarted++;
            debug = goal.advancement() + ": " + goal.condition();
        }
    }

    public Status tick() {
        Goal g = goal;
        if (g == null || ++ticks > 1800) {
            return Status.FAILED;
        }
        Advancement adv = self.getServer() == null ? null : self.getServer().getAdvancements().getAdvancement(g.advancement());
        if (adv == null) {
            return Status.FAILED;
        }
        if (done(adv)) {
            unlocked++;
            crafting.forcedTarget = null;
            debug = "unlocked " + g.advancement();
            return Status.DONE;
        }
        Condition c = g.condition();
        return switch (c.what()) {
            case "obtain" -> obtainTick(c);
            case "eat" -> eatTick(c);
            case "kill" -> killTick(c);
            default -> Status.FAILED;
        };
    }

    private Status obtainTick(Condition c) {
        for (String id : c.ids()) {
            Item it = item(id);
            if (it == null) {
                continue;
            }
            if (self.getInventory().countItem(it) > 0) {
                // have it: picked up / filled - the advancement comes with the next inventory update
                return ticks > 60 ? Status.FAILED : Status.WORKING;
            }
            Fluid f = fluidFor(it);
            if (f != null && hasBucket()) {
                return fillTick(f);
            }
            if (craftable(it)) {
                crafting.forcedTarget = it;
                Crafting.Status st = crafting.tick();
                return st == Crafting.Status.WORKING ? Status.WORKING : self.getInventory().countItem(it) > 0 ? Status.WORKING : Status.FAILED;
            }
            BlockPos src = spot != null ? spot : dropSource(it);
            if (src != null) {
                spot = src;
                return mineTick(src);
            }
        }
        return Status.FAILED;
    }

    private Status fillTick(Fluid f) {
        if (spot == null || !self.level().getFluidState(spot).isSource()) {
            spot = fluidNear(f);
            if (spot == null) {
                return Status.FAILED;
            }
        }
        Vec3 c = Vec3.atCenterOf(spot);
        if (self.getEyePosition().distanceTo(c) > Motor.BLOCK_REACH - 1.0) {
            motor.navigate(Vec3.atBottomCenterOf(spot.above()), 2.0, false);
            return motor.stuckCount() > 8 ? Status.FAILED : Status.WORKING;
        }
        motor.stop();
        motor.lookAt(c.add(0, 0.3, 0));
        ServerLevel level = self.serverLevel();
        Vec3 eye = self.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(self.getViewVector(1f).scale(Motor.BLOCK_REACH)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, self));
        if (hit.getType() == HitResult.Type.BLOCK && level.getFluidState(hit.getBlockPos()).is(f == Fluids.LAVA ? FluidTags.LAVA : FluidTags.WATER)) {
            Equipment.select(self, self.getInventory().findSlotMatchingItem(new ItemStack(Items.BUCKET)));
            motor.useHeldItem(InteractionHand.MAIN_HAND);
        }
        return Status.WORKING;
    }

    private Status mineTick(BlockPos p) {
        if (self.level().getBlockState(p).isAir()) {
            spot = null;
            return Status.WORKING; // broken: the drop is picked up on the way
        }
        if (self.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > Motor.BLOCK_REACH - 0.3) {
            motor.navigate(motor.approachPoint(p), 1.5, false);
            return motor.stuckCount() > 6 ? Status.FAILED : Status.WORKING;
        }
        Equipment.select(self, Equipment.bestToolSlot(self, self.level().getBlockState(p)));
        motor.mine(p);
        return Status.WORKING;
    }

    private Status eatTick(Condition c) {
        int slot = -1;
        var inv = self.getInventory();
        for (int i = 0; i < inv.items.size() && slot < 0; i++) {
            ItemStack s = inv.items.get(i);
            if (c.ids().isEmpty() ? FoodAid.goodFood(s) : c.ids().contains(AttackLearning.itemId(s))) {
                slot = i;
            }
        }
        if (slot < 0) {
            return Status.FAILED;
        }
        motor.stop();
        if (!self.isUsingItem()) {
            Equipment.select(self, slot);
            motor.useHeldItem(InteractionHand.MAIN_HAND);
        }
        return Status.WORKING;
    }

    private Status killTick(Condition c) {
        LivingEntity t = prey(c);
        if (t == null) {
            return Status.FAILED;
        }
        Equipment.select(self, Equipment.bestWeaponSlot(self));
        motor.lookAt(t);
        if (!motor.withinReach(t)) {
            motor.navigate(t.position(), 1.5, true);
        } else if (motor.canHit(t) && self.getAttackStrengthScale(0.5f) >= 0.9f) {
            motor.attack(t);
        }
        return Status.WORKING;
    }

    @Nullable
    public Goal current() {
        return goal;
    }

    public static String describe(Entity e) {
        return Perception.typeId(e);
    }
}
