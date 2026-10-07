package com.rlclones.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Crafting and smelting through the same UI a player uses: the 2x2 grid of the inventory screen, a crafting table
 * screen and a furnace screen. Items are placed with the recipe book (exactly what clicking a recipe does) and
 * results / furnace inputs are moved with shift-clicks. Tables and furnaces are placed like any block.
 */
public final class Crafting {
    public enum Status {WORKING, DONE}

    private static final Item[] SWORDS = {Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD, Items.WOODEN_SWORD};
    private static final Item[] PICKAXES = {Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE};
    private static final Item[] AXES = {Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE};
    private static final Item[] ARMOR = {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS,
            Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};

    private final ServerPlayer self;
    private final Motor motor;
    private final Perception perception;

    private int stage;
    private int timer;
    private int actions;
    @Nullable
    private CraftingRecipe recipe;
    @Nullable
    private BlockPos station;
    private boolean smelting;

    // a furnace we loaded and will come back to
    @Nullable
    private BlockPos furnace;
    private long furnaceReadyAt;

    public int crafted;
    public int smelted;
    /** Something a higher-level task needs right now (a chest for the base, a hoe for the field...); crafted first. */
    @Nullable
    public Item forcedTarget;
    /** Where the last thing that needed a crafting table was made (diagnostics, tests). */
    @Nullable
    public BlockPos lastTable;
    /** When a better pickaxe last made crafting urgent (once in 400 ticks: no loop if it cannot be made after all). */
    private long upgradeAt = Long.MIN_VALUE / 2;
    /** When smelting (or taking out what was smelted) last made crafting urgent. */
    private long smeltUrgentAt = Long.MIN_VALUE / 2;
    /** What a recipe makes -> game time until which it is left alone (it could not be carried out here: no room for a table...). */
    private final Map<Item, Long> blockedUntil = new HashMap<>();
    /** Until when no furnace / brewing stand is put down or used (it could not be put down). */
    private long stationBlockedUntil = Long.MIN_VALUE / 2;
    public int giveUps;
    public String placeDebug = "";

    public Crafting(ServerPlayer self, Motor motor, Perception perception) {
        this.self = self;
        this.motor = motor;
        this.perception = perception;
    }

    // ================================================================== planning

    /** Is there anything worth doing at a crafting table / furnace right now? */
    private long workTick = Long.MIN_VALUE;
    private int workInv = -1;
    private long workSeen = Long.MIN_VALUE;
    private boolean workCached;
    @Nullable
    private Item workForced;

    public boolean hasWork() {
        // asked by this clone and by each clone watching it: worked out once a tick (again if the bag / its view changed)
        long now = self.level().getGameTime();
        int inv = self.getInventory().getTimesChanged();
        long seen = perception.lastUpdate();
        if (now == workTick && inv == workInv && seen == workSeen && forcedTarget == workForced) {
            return workCached;
        }
        workCached = plan(self) != null || smeltWork() || furnaceReady() || stationToPlace() != null;
        workForced = forcedTarget;
        workTick = now;
        workInv = inv;
        workSeen = seen;
        return workCached;
    }

    /** A furnace / brewing stand in the bag and none around: put it down so it can be used. */
    @Nullable
    private Item stationToPlace() {
        if (!self.onGround() || self.isInWater() || self.level().getGameTime() < stationBlockedUntil) {
            return null;
        }
        if (count(self, Items.FURNACE) > 0 && nearest(Perception.BlockKind.FURNACE, 24) == null) {
            return Items.FURNACE;
        }
        if (count(self, Items.BREWING_STAND) > 0 && nearest(Perception.BlockKind.BREWING, 24) == null) {
            return Items.BREWING_STAND;
        }
        return null;
    }

    private boolean placeOnly;
    @Nullable
    private Item placeItem;

    private static int cobble(Player p) {
        return countTag(p, ItemTags.STONE_CRAFTING_MATERIALS);
    }

    private static boolean hasItem(Player p, Item item) {
        return count(p, item) > 0;
    }

    private static boolean brewingStandNearby(Player p) {
        return p instanceof com.rlclones.clone.ClonePlayer c
                && Senses.nearestBlock(c.controller().perception(), p, Perception.BlockKind.BREWING, 24) != null;
    }

    /** How much more cobblestone the clone needs for stone tools, a furnace and a brewing stand (0 = enough). */
    public static int stoneNeeded(Player p) {
        if (tierOf(p, PickaxeItem.class) < 0) {
            return 0; // cannot mine stone yet
        }
        int need = 0;
        if (tierOf(p, PickaxeItem.class) < 1) {
            need += 3;
        }
        if (bestWeapon(p) < Equipment.attackDamage(new ItemStack(Items.STONE_SWORD)) - 0.01) {
            need += 2;
        }
        if (tierOf(p, AxeItem.class) < 1) {
            need += 3;
        }
        if (count(p, Items.FURNACE) == 0 && !furnaceNearby(p)) {
            need += 8;
        }
        if (hasItem(p, Items.BLAZE_ROD) && !hasItem(p, Items.BREWING_STAND) && !brewingStandNearby(p)) {
            need += 3;
        }
        return Math.max(0, need - cobble(p));
    }

    /** The furnace we left a batch in is far away now: not worth the walk (and not a reason to never smelt again). */
    private boolean furnaceFar() {
        return furnace != null && self.blockPosition().distSqr(furnace) > 40 * 40;
    }

    private boolean furnaceReady() {
        return furnace != null && !furnaceFar() && self.level().getGameTime() >= furnaceReadyAt;
    }

    @Nullable
    private BlockPos nearest(Perception.BlockKind kind, double radius) {
        return Senses.nearestBlock(perception, self, kind, radius);
    }

    private static int count(Player p, Item item) {
        return p.getInventory().countItem(item);
    }

    private static int countTag(Player p, net.minecraft.tags.TagKey<Item> tag) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) {
            if (s.is(tag)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static int tierOf(Player p, Class<?> type) {
        int best = -1;
        for (ItemStack s : p.getInventory().items) {
            if (type.isInstance(s.getItem()) && s.getItem() instanceof TieredItem t) {
                best = Math.max(best, t.getTier().getLevel());
            }
        }
        return best;
    }

    private static boolean hasShield(Player p) {
        if (p.getOffhandItem().getItem() instanceof ShieldItem) {
            return true;
        }
        for (ItemStack s : p.getInventory().items) {
            if (s.getItem() instanceof ShieldItem) {
                return true;
            }
        }
        return false;
    }

    private static double bestWeapon(Player p) {
        double best = Equipment.attackDamage(p.getMainHandItem());
        for (ItemStack s : p.getInventory().items) {
            if (s.getItem() instanceof SwordItem || s.getItem() instanceof AxeItem) {
                best = Math.max(best, Equipment.attackDamage(s));
            }
        }
        return best;
    }

    private static boolean armorUpgrade(Player p, ArmorItem target) {
        EquipmentSlot slot = target.getEquipmentSlot();
        double worn = p.getItemBySlot(slot).getItem() instanceof ArmorItem a ? a.getDefense() : 0;
        for (ItemStack s : p.getInventory().items) {
            if (s.getItem() instanceof ArmorItem a && a.getEquipmentSlot() == slot) {
                worn = Math.max(worn, a.getDefense());
            }
        }
        return target.getDefense() > worn;
    }

    /** Items that would improve the clone, most important first. */
    private static final Item[] BOATS = {Items.OAK_BOAT, Items.SPRUCE_BOAT, Items.BIRCH_BOAT, Items.JUNGLE_BOAT, Items.ACACIA_BOAT,
            Items.DARK_OAK_BOAT, Items.MANGROVE_BOAT, Items.CHERRY_BOAT, Items.BAMBOO_RAFT};

    /** A forced pickaxe the player has (or better) by now is not wanted again. */
    private static boolean satisfied(Player p, @Nullable Item forced) {
        return forced instanceof PickaxeItem fp && tierOf(p, PickaxeItem.class) >= ((TieredItem) fp).getTier().getLevel();
    }

    private static List<Item> wanted(Player p) {
        List<Item> out = new ArrayList<>();
        if (p instanceof com.rlclones.clone.ClonePlayer c && c.controller() != null && c.controller().crafting().forcedTarget != null
                && !satisfied(p, c.controller().crafting().forcedTarget)) {
            out.add(c.controller().crafting().forcedTarget);
        }
        if (p instanceof com.rlclones.clone.ClonePlayer c && c.controller() != null && c.controller().swamALot() && Boating.boatSlot(p) < 0) {
            out.addAll(List.of(BOATS)); // lots of swimming lately: a boat would help
        }
        if (p instanceof com.rlclones.clone.ClonePlayer c && c.controller() != null && c.controller().swamALot()
                && countTag(p, ItemTags.PLANKS) >= 6 && countTag(p, ItemTags.WOODEN_DOORS) == 0) {
            out.add(Items.OAK_DOOR); // R-23: somewhere to breathe under water
        }
        if (tierOf(p, HoeItem.class) < 0 && (Farming.seedSlot(p) >= 0 || tierOf(p, PickaxeItem.class) >= 0)) {
            // a field feeds us for good: the hoe comes right after the first pickaxe, before weapons and other tools
            out.add(Items.STONE_HOE);
            out.add(Items.WOODEN_HOE);
        }
        double weapon = bestWeapon(p);
        for (Item sword : SWORDS) {
            if (Equipment.attackDamage(new ItemStack(sword)) > weapon + 0.01) {
                out.add(sword);
            }
        }
        if (!hasShield(p)) {
            out.add(Items.SHIELD);
        }
        int pick = tierOf(p, PickaxeItem.class);
        for (Item it : PICKAXES) {
            if (((TieredItem) it).getTier().getLevel() > pick) {
                out.add(it);
            }
        }
        int axe = tierOf(p, AxeItem.class);
        for (Item it : AXES) {
            if (((TieredItem) it).getTier().getLevel() > axe) {
                out.add(it);
            }
        }
        for (Item it : ARMOR) {
            if (armorUpgrade(p, (ArmorItem) it)) {
                out.add(it);
            }
        }

        if (hasItem(p, Items.BLAZE_ROD) && !hasItem(p, Items.BREWING_STAND) && !brewingStandNearby(p)) {
            out.add(Items.BREWING_STAND);
        }
        if (hasItem(p, Items.BLAZE_ROD) && !hasItem(p, Items.BLAZE_POWDER) && (hasItem(p, Items.BREWING_STAND) || brewingStandNearby(p))) {
            out.add(Items.BLAZE_POWDER);
        }
        if (count(p, Items.GLASS) >= 3 && !hasItem(p, Items.GLASS_BOTTLE) && (hasItem(p, Items.BREWING_STAND) || brewingStandNearby(p))) {
            out.add(Items.GLASS_BOTTLE);
        }
        int sticksAndPlanks = count(p, Items.STICK) + countTag(p, ItemTags.PLANKS) * 2 + countTag(p, ItemTags.LOGS) * 8;
        boolean launcher = false;
        for (ItemStack s : p.getInventory().items) {
            launcher |= Equipment.isLauncher(s);
        }
        if (!launcher && count(p, Items.STRING) >= 3 && sticksAndPlanks >= 3) {
            out.add(Items.BOW);
        }
        if (launcher && count(p, Items.ARROW) < 16 && hasItem(p, Items.FLINT) && hasItem(p, Items.FEATHER) && sticksAndPlanks >= 1) {
            out.add(Items.ARROW);
        }
        if (Fishing.rodSlot(p) < 0 && count(p, Items.STRING) >= 2 && sticksAndPlanks >= 3) {
            out.add(Items.FISHING_ROD);
        }
        if (count(p, Items.GUNPOWDER) >= 5 && countTag(p, ItemTags.SAND) >= 4) {
            out.add(Items.TNT);
        }
        if (count(p, Items.TORCH) < 16 && count(p, Items.COAL) + count(p, Items.CHARCOAL) >= 2 && sticksAndPlanks >= 1) {
            out.add(Items.TORCH); // light for caves and around the base
        }
        if (!hasItem(p, Items.FLINT_AND_STEEL) && hasItem(p, Items.IRON_INGOT) && hasItem(p, Items.FLINT) && count(p, Items.OBSIDIAN) >= 10) {
            out.add(Items.FLINT_AND_STEEL); // to light the portal frame
        }
        if (count(p, Items.IRON_INGOT) >= 3 && !hasBucket(p)) {
            if (firstBucketWanted(p)) {
                out.add(0, Items.BUCKET); // never had one: the bucket first (water for a field, putting out fire...)
            } else {
                out.add(Items.BUCKET);
            }
        }
        return out;
    }

    private static final java.util.Set<Item> PRECIOUS = java.util.Set.of(Items.DIAMOND, Items.EMERALD, Items.NETHERITE_INGOT, Items.NETHER_STAR,
            Items.TOTEM_OF_UNDYING, Items.ENDER_PEARL, Items.BLAZE_ROD, Items.ELYTRA, Items.DIAMOND_BLOCK, Items.EMERALD_BLOCK);
    private long curiosityAt = -1000;
    @Nullable
    private CraftingRecipe curiosityRecipe;

    /** A craftable recipe whose result this clone has never had (refreshed every 5 s; skips precious ingredients). */
    @Nullable
    CraftingRecipe curiosity(boolean tableAccess) {
        long now = self.level().getGameTime();
        if (now - curiosityAt < 100) {
            return curiosityRecipe != null && canCraft(self, curiosityRecipe) && (tableAccess || curiosityRecipe.canCraftInDimensions(2, 2))
                    && !knows(curiosityRecipe) ? curiosityRecipe : null;
        }
        curiosityAt = now;
        curiosityRecipe = null;
        com.rlclones.ai.brain.Brain b = ((com.rlclones.clone.ClonePlayer) self).getCloneBrain();
        if (b == null) {
            return null;
        }
        List<ItemStack> have = new ArrayList<>();
        for (ItemStack s : self.getInventory().items) {
            if (!s.isEmpty()) {
                have.add(s);
            }
        }
        if (have.isEmpty()) {
            return null;
        }
        StackedContents contents = new StackedContents();
        self.getInventory().fillStackedContents(contents);
        for (Entry e : index(self)) {
            ItemStack r = e.result();
            if (r.isEmpty() || r.is(Tags.Items.STORAGE_BLOCKS) || b.knowsItem(Discovery.keyOf(r))) {
                continue;
            }
            if (!tableAccess && !e.recipe().canCraftInDimensions(2, 2)) {
                continue;
            }
            boolean ok = true;
            for (var ing : e.recipe().getIngredients()) {
                if (ing.isEmpty()) {
                    continue;
                }
                boolean found = false;
                for (ItemStack h : have) {
                    if (ing.test(h)) {
                        found = !PRECIOUS.contains(h.getItem()) && !essential(h);
                        break;
                    }
                }
                if (!found) {
                    ok = false;
                    break;
                }
            }
            if (ok && contents.canCraft(e.recipe(), null)) {
                curiosityRecipe = e.recipe();
                return curiosityRecipe;
            }
        }
        return null;
    }

    /** Materials still needed for tools / stations: not to be spent on curiosities. */
    private boolean essential(ItemStack h) {
        if (h.is(Items.STICK)) {
            return count(self, Items.STICK) < 10;
        }
        if (h.is(ItemTags.STONE_CRAFTING_MATERIALS)) {
            return stoneNeeded(self) > 0 || cobble(self) < 16;
        }
        if (h.is(ItemTags.PLANKS)) {
            return countTag(self, ItemTags.PLANKS) < 6 && countTag(self, ItemTags.LOGS) == 0;
        }
        if (h.is(Items.IRON_INGOT) || h.is(Items.GOLD_INGOT)) {
            return h.getCount() < 6;
        }
        return false;
    }

    private boolean knows(CraftingRecipe r) {
        com.rlclones.ai.brain.Brain b = ((com.rlclones.clone.ClonePlayer) self).getCloneBrain();
        return b != null && b.knowsItem(Discovery.keyOf(r.getResultItem(self.serverLevel().registryAccess())));
    }

    public static boolean canCraft(Player p, CraftingRecipe r) {
        if (p instanceof com.rlclones.clone.ClonePlayer c && c.controller() != null && c.controller().crafting().blocked(r)) {
            return false;
        }
        StackedContents contents = new StackedContents();
        p.getInventory().fillStackedContents(contents);
        return contents.canCraft(r, null);
    }

    private record Entry(CraftingRecipe recipe, ItemStack result) {
    }

    private static Object cacheOwner;
    private static List<Entry> cache = List.of();

    private static List<Entry> index(ServerPlayer p) {
        var manager = p.serverLevel().getRecipeManager();
        if (cacheOwner != manager) {
            List<Entry> list = new ArrayList<>();
            for (CraftingRecipe r : manager.getAllRecipesFor(RecipeType.CRAFTING)) {
                if (!r.isSpecial()) {
                    list.add(new Entry(r, r.getResultItem(p.serverLevel().registryAccess())));
                }
            }
            cache = list;
            cacheOwner = manager;
        }
        return cache;
    }

    /** Distinct items that can be crafted with {@code stack} as one of the ingredients (vanilla + modded recipes). */
    public static List<ItemStack> usesOf(ServerPlayer p, ItemStack stack) {
        List<ItemStack> out = new ArrayList<>();
        java.util.Set<Item> seen = new java.util.HashSet<>();
        for (Entry e : index(p)) {
            if (e.result().isEmpty() || seen.contains(e.result().getItem()) || e.result().is(stack.getItem())) {
                continue;
            }
            for (var ing : e.recipe().getIngredients()) {
                if (!ing.isEmpty() && ing.test(stack)) {
                    seen.add(e.result().getItem());
                    out.add(e.result());
                    break;
                }
            }
        }
        return out;
    }

    public static List<CraftingRecipe> recipesFor(ServerPlayer p, java.util.function.Predicate<ItemStack> result) {
        List<CraftingRecipe> out = new ArrayList<>();
        for (Entry e : index(p)) {
            if (result.test(e.result())) {
                out.add(e.recipe());
            }
        }
        return out;
    }

    @Nullable
    private static CraftingRecipe craftable(ServerPlayer p, java.util.function.Predicate<ItemStack> result) {
        for (CraftingRecipe r : recipesFor(p, result)) {
            if (canCraft(p, r)) {
                return r;
            }
        }
        return null;
    }

    /** Next recipe to craft (all ingredients in the inventory), or null. */
    @Nullable
    public static CraftingRecipe plan(ServerPlayer p) {
        List<Item> wanted = wanted(p);
        boolean haveTable = count(p, Items.CRAFTING_TABLE) > 0;
        boolean tableAccess = haveTable || tableNearby(p);
        Item forced = p instanceof com.rlclones.clone.ClonePlayer fc && fc.controller() != null ? fc.controller().crafting().forcedTarget : null;
        if (satisfied(p, forced)) {
            forced = null;
        }
        if (forced instanceof PickaxeItem) {
            wanted = List.of(forced); // a pickaxe for the mining at hand: nothing else may eat up its materials
        } else if (furnaceWanted(p)) {
            // raw meat / ore in the bag and the stone for a furnace: the furnace comes before anything else made of stone
            if (tableAccess) {
                CraftingRecipe r = craftable(p, s -> s.is(Items.FURNACE));
                if (r != null) {
                    return r;
                }
            } else if (countTag(p, ItemTags.PLANKS) >= 4) {
                CraftingRecipe r = craftable(p, s -> s.is(Items.CRAFTING_TABLE));
                if (r != null) {
                    return r;
                }
            } else if (countTag(p, ItemTags.LOGS) > 0) {
                CraftingRecipe r = craftable(p, s -> s.is(ItemTags.PLANKS));
                if (r != null) {
                    return r;
                }
            }
        }
        if (p instanceof com.rlclones.clone.ClonePlayer c && c.controller() != null && c.controller().swamALot() && Boating.boatSlot(p) < 0) {
            // a boat first: keep the wood for it (5 planks + a table) instead of spending it on other things
            for (Item boat : BOATS) {
                for (CraftingRecipe r : recipesFor(p, s -> s.is(boat))) {
                    if (tableAccess && canCraft(p, r)) {
                        return r;
                    }
                }
            }
            int planks = countTag(p, ItemTags.PLANKS);
            int logs = countTag(p, ItemTags.LOGS);
            if (!tableAccess && planks >= 4 && planks + logs * 4 >= 9) {
                CraftingRecipe r = craftable(p, s -> s.is(Items.CRAFTING_TABLE));
                if (r != null) {
                    return r;
                }
            }
            if (logs > 0 && planks < (tableAccess ? 5 : 9)) {
                CraftingRecipe r = craftable(p, s -> s.is(ItemTags.PLANKS));
                if (r != null) {
                    return r;
                }
            }
        }
        for (Item item : wanted) {
            for (CraftingRecipe r : recipesFor(p, s -> s.is(item))) {
                if ((tableAccess || r.canCraftInDimensions(2, 2)) && canCraft(p, r)) {
                    return r;
                }
            }
        }
        // one button for every TNT, to set it off with
        if (count(p, Items.TNT) > countTag(p, ItemTags.BUTTONS)) {
            CraftingRecipe r = craftable(p, s -> s.is(ItemTags.BUTTONS));
            if (r != null) {
                return r;
            }
        }
        boolean wantsTools = !wanted.isEmpty();
        int planks = countTag(p, ItemTags.PLANKS);
        int logs = countTag(p, ItemTags.LOGS);
        int sticks = count(p, Items.STICK);
        if (wantsTools && !haveTable && planks >= 4 && !tableNearby(p)) {
            CraftingRecipe r = craftable(p, s -> s.is(Items.CRAFTING_TABLE));
            if (r != null) {
                return r;
            }
        }
        if (wantsTools && sticks < 2 && planks >= 2) {
            CraftingRecipe r = craftable(p, s -> s.is(Items.STICK));
            if (r != null) {
                return r;
            }
        }
        if (logs > 0 && planks < 8) {
            CraftingRecipe r = craftable(p, s -> s.is(ItemTags.PLANKS));
            if (r != null) {
                return r;
            }
        }
        if (count(p, Items.FURNACE) == 0 && cobble(p) >= 8 && !furnaceNearby(p)) {
            CraftingRecipe r = craftable(p, s -> s.is(Items.FURNACE));
            if (r != null) {
                return r;
            }
        }
        // fences and a gate for a chicken pen when there is wood to spare
        if (p instanceof com.rlclones.clone.ClonePlayer c && c.controller() != null && tableAccess) {
            if (c.controller().animals().needsFences()) {
                CraftingRecipe r = craftable(p, s -> s.is(ItemTags.WOODEN_FENCES));
                if (r != null) {
                    return r;
                }
                if (count(p, Items.STICK) < 2) {
                    r = craftable(p, s -> s.is(Items.STICK));
                    if (r != null) {
                        return r;
                    }
                }
            }
            if (c.controller().animals().needsGate()) {
                CraftingRecipe r = craftable(p, s -> s.is(ItemTags.FENCE_GATES));
                if (r != null) {
                    return r;
                }
                if (count(p, Items.STICK) < 4) {
                    r = craftable(p, s -> s.is(Items.STICK));
                    if (r != null) {
                        return r;
                    }
                }
            }
        }
        // curiosity: make something never made before (cheap materials only)
        if (p instanceof com.rlclones.clone.ClonePlayer c && c.controller() != null) {
            return c.controller().crafting().curiosity(tableAccess);
        }
        return null;
    }

    /** No furnace anywhere near, the cobblestone for one (8) and something worth smelting in the bag. */
    public static boolean furnaceWanted(Player p) {
        return count(p, Items.FURNACE) == 0 && cobble(p) >= 8 && !furnaceNearby(p) && !smeltables(p).isEmpty();
    }

    /** Can this pickaxe be made right now from what is in the bag (sticks, the head material, a table here or in the bag)? */
    public static boolean makeable(Player p, Item pick) {
        int wood = countTag(p, ItemTags.PLANKS) + countTag(p, ItemTags.LOGS) * 4;
        int need = (count(p, Items.STICK) >= 2 ? 0 : 2) + (count(p, Items.CRAFTING_TABLE) > 0 || tableNearby(p) ? 0 : 4);
        if (pick == Items.WOODEN_PICKAXE) {
            need += 3;
        } else if (pick == Items.STONE_PICKAXE) {
            if (cobble(p) < 3) {
                return false;
            }
        } else if (pick == Items.IRON_PICKAXE) {
            if (count(p, Items.IRON_INGOT) < 3) {
                return false;
            }
        } else if (pick == Items.DIAMOND_PICKAXE) {
            if (count(p, Items.DIAMOND) < 3) {
                return false;
            }
        } else {
            return false;
        }
        return wood >= need;
    }

    /** Planks, counting each log as four. */
    public static int woodUnits(Player p) {
        return countTag(p, ItemTags.PLANKS) + countTag(p, ItemTags.LOGS) * 4;
    }

    /** The best pickaxe better than the one in the bag that can be made right now (null = none). */
    @Nullable
    public static Item pickaxeUpgrade(Player p) {
        int have = tierOf(p, PickaxeItem.class);
        for (Item it : PICKAXES) {
            if (((TieredItem) it).getTier().getLevel() > have && makeable(p, it)) {
                return it;
            }
        }
        return null;
    }

    /** One line for the soak log: what crafting would do now and why it may not. */
    public String trace() {
        Item up = pickaxeUpgrade(self);
        CraftingRecipe r = plan(self);
        boolean bag = count(self, Items.CRAFTING_TABLE) > 0;
        return "plan=" + (r == null ? "-" : r.getResultItem(self.level().registryAccess()).getItem()) + " up=" + (up == null ? "-" : up)
                + " forced=" + forcedTarget + " table=" + (bag ? "bag" : tableNearby(self) ? "near" : "no") + " cobble=" + cobble(self)
                + " giveUps=" + giveUps + " place=[" + placeDebug + "]" + " planks=" + countTag(self, ItemTags.PLANKS) + " logs=" + countTag(self, ItemTags.LOGS) + " sticks=" + count(self, Items.STICK);
    }

    /** Coal or charcoal and a stick (or the wood for one): a torch can be made in the hand. */
    public static boolean torchMakeable(Player p) {
        return count(p, Items.COAL) + count(p, Items.CHARCOAL) > 0
                && (count(p, Items.STICK) > 0 || countTag(p, ItemTags.PLANKS) >= 2 || countTag(p, ItemTags.LOGS) > 0);
    }

    /** Any kind of bucket in the bag (one is enough: never a second). */
    public static boolean hasBucket(Player p) {
        for (ItemStack s : p.getInventory().items) {
            if (s.getItem() instanceof net.minecraft.world.item.BucketItem || s.getItem() instanceof net.minecraft.world.item.MilkBucketItem
                    || s.getItem() instanceof net.minecraft.world.item.SolidBucketItem) {
                return true;
            }
        }
        return false;
    }

    /** Never had a bucket yet and the iron for one. */
    public static boolean firstBucketWanted(Player p) {
        return !hasBucket(p) && count(p, Items.IRON_INGOT) >= 3 && p instanceof com.rlclones.clone.ClonePlayer c && c.getCloneBrain() != null
                && !c.getCloneBrain().hasFlag("had_bucket");
    }

    /** Crafting that should not wait for the policy to get round to it (a furnace for the raw food/ore, the first bucket, a pickaxe for mining). */
    public boolean urgent() {
        if (forcedTarget instanceof PickaxeItem) {
            return true;
        }
        Item up = pickaxeUpgrade(self);
        long now = self.level().getGameTime();
        if (up != null && now - upgradeAt >= 400) {
            // a better pickaxe can be made right now: nothing else may eat its wood / stone first
            upgradeAt = now;
            if (forcedTarget == null) {
                forcedTarget = up;
            }
            return true;
        }
        if ((smeltWork() || furnaceReady()) && now - smeltUrgentAt >= 400) {
            smeltUrgentAt = now; // raw iron / raw meat in the bag and a furnace to use (or a batch waiting in it): not left to chance
            return true;
        }
        boolean tableAccess = count(self, Items.CRAFTING_TABLE) > 0 || tableNearby(self) || countTag(self, ItemTags.PLANKS) + countTag(self, ItemTags.LOGS) * 4 >= 4;
        return tableAccess && (furnaceWanted(self) || firstBucketWanted(self)) || stationToPlace() == Items.FURNACE;
    }

    /** For the soak log: why smelting does or does not happen. */
    public String smeltTrace() {
        return "smelt[work=" + smeltWork() + " ready=" + furnaceReady() + " items=" + smeltables(self).size() + " fuel=" + fuelSlot(self)
                + " near=" + (nearest(Perception.BlockKind.FURNACE, 24) != null) + " bag=" + count(self, Items.FURNACE) + " at=" + furnace
                + " blocked=" + (self.level().getGameTime() < stationBlockedUntil) + "]";
    }

    // Perception is per clone; the static planner only knows about stations the player can see right now.
    private static boolean tableNearby(Player p) {
        return p instanceof com.rlclones.clone.ClonePlayer c
                && Senses.nearestBlock(c.controller().perception(), p, Perception.BlockKind.TABLE, 24) != null;
    }

    private static boolean furnaceNearby(Player p) {
        return p instanceof com.rlclones.clone.ClonePlayer c
                && Senses.nearestBlock(c.controller().perception(), p, Perception.BlockKind.FURNACE, 24) != null;
    }

    private static boolean usefulSmeltResult(ItemStack out) {
        return out.isEdible() || out.is(Tags.Items.INGOTS);
    }

    /** Inventory slots holding something worth smelting (raw ores, raw meat). */
    private static List<Integer> smeltables(Player p) {
        List<Integer> out = new ArrayList<>();
        ServerLevel level = (ServerLevel) p.level();
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.is(ItemTags.LOGS)) {
                if (spareLogs(p) > 0 && out.stream().noneMatch(j -> inv.items.get(j).is(ItemTags.LOGS))) {
                    out.add(i); // R-21: charcoal from spare logs (nothing else to burn)
                }
                continue;
            }
            if (s.isEmpty()) {
                continue;
            }
            Optional<SmeltingRecipe> r = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, new SimpleContainer(s.copy()), level);
            if (r.isPresent() && usefulSmeltResult(r.get().getResultItem(level.registryAccess()))) {
                out.add(i);
            }
        }
        return out;
    }

    /** Logs beyond the 8 kept for tables / tools / ladders, when there is no coal or charcoal (at most 8). */
    private static int spareLogs(Player p) {
        if (count(p, Items.COAL) + count(p, Items.CHARCOAL) > 0) {
            return 0;
        }
        return Math.min(8, countTag(p, ItemTags.LOGS) - 8);
    }

    private static int fuelSlot(Player p) {
        Inventory inv = p.getInventory();
        int best = -1;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty() || s.is(ItemTags.LOGS) || !AbstractFurnaceBlockEntity.isFuel(s)) {
                continue;
            }
            if (s.is(Items.COAL) || s.is(Items.CHARCOAL)) {
                return i;
            }
            if (best < 0 && !s.is(Items.LAVA_BUCKET)) {
                best = i;
            }
        }
        if (best < 0 && spareLogs(p) > 0) {
            for (int i = 0; i < inv.items.size(); i++) {
                if (inv.items.get(i).is(ItemTags.LOGS)) {
                    return i; // R-21: nothing else burns: a spare log fires the furnace (it makes the charcoal)
                }
            }
        }
        if (best < 0) {
            for (int i = 0; i < inv.items.size(); i++) {
                if (inv.items.get(i).is(Items.LAVA_BUCKET)) {
                    return i; // a bucket of lava smelts 100 items
                }
            }
        }
        return best;
    }

    private boolean smeltWork() {
        return self.level().getGameTime() >= stationBlockedUntil && (furnace == null || furnaceFar()) && !smeltables(self).isEmpty() && fuelSlot(self) >= 0
                && (nearest(Perception.BlockKind.FURNACE, 24) != null || count(self, Items.FURNACE) > 0);
    }

    // ================================================================== execution

    /** State for diagnostics. */
    public String debug() {
        StringBuilder inv = new StringBuilder();
        for (ItemStack s : self.getInventory().items) {
            if (!s.isEmpty()) {
                inv.append(s.getCount()).append(' ').append(s.getItem()).append(',');
            }
        }
        return "stage=" + stage + " recipe=" + (recipe == null ? null : recipe.getId()) + " crafted=" + crafted + " timer=" + timer
                + " actions=" + actions + " station=" + station + " inv=" + inv;
    }

    public void reset() {
        placeOnly = false;
        placeItem = null;
        closeUi();
        stage = 0;
        timer = 0;
        actions = 0;
        recipe = null;
        station = null;
        smelting = false;
    }

    private void closeUi() {
        if (self.containerMenu != self.inventoryMenu) {
            self.closeContainer();
        }
        clearInventoryGrid();
    }

    /** Advance the crafting job by one tick. */
    public Status tick() {
        timer++;
        if (timer > 400 || actions > 12) {
            reset();
            return Status.DONE;
        }
        switch (stage) {
            case 0 -> {
                return choose();
            }
            case 1 -> placeStation();
            case 2 -> walkAndOpen();
            case 3 -> workInUi();
            default -> {
                return stage < 0 ? giveUp() : done();
            }
        }
        return Status.WORKING;
    }

    private Status choose() {
        closeUi();
        station = null;
        if (furnaceReady()) {
            smelting = true;
            station = furnace;
            stage = 2;
            return Status.WORKING;
        }
        Item toPlace = stationToPlace();
        if (toPlace != null) {
            placeItem = toPlace;
            placeOnly = true;
            stage = 1;
            return Status.WORKING;
        }
        recipe = plan(self);
        if (recipe != null) {
            smelting = false;
            if (recipe.canCraftInDimensions(2, 2)) {
                stage = 3;
                return Status.WORKING;
            }
            station = nearest(Perception.BlockKind.TABLE, 24);
            stage = station == null ? (count(self, Items.CRAFTING_TABLE) > 0 ? 1 : -1) : 2;
            return stage < 0 ? giveUp() : Status.WORKING;
        }
        if (smeltWork()) {
            smelting = true;
            station = nearest(Perception.BlockKind.FURNACE, 24);
            stage = station == null ? 1 : 2;
            return Status.WORKING;
        }
        return done();
    }

    private Status done() {
        reset();
        return Status.DONE;
    }

    public boolean blocked(CraftingRecipe r) {
        if (blockedUntil.isEmpty()) {
            return false;
        }
        Long t = blockedUntil.get(r.getResultItem(self.serverLevel().registryAccess()).getItem());
        return t != null && self.level().getGameTime() < t;
    }

    /**
     * The round could not be carried out here (no room for a table, no way to the one seen): leave that recipe (or the
     * furnace work) alone for a while instead of starting it again every tick and getting nowhere.
     */
    private Status giveUp() {
        long now = self.level().getGameTime();
        if (recipe != null && !smelting) {
            blockedUntil.put(recipe.getResultItem(self.serverLevel().registryAccess()).getItem(), now + 300);
        }
        if (smelting || placeOnly) {
            stationBlockedUntil = now + 300;
        }
        if (smelting) {
            furnace = null; // could not get to it (or use it): it must not stop every later smelting
        }
        giveUps++;
        reset();
        return Status.DONE;
    }

    /** Put a crafting table / furnace on the ground next to us, like a player would. */
    private void placeStation() {
        Item item = placeItem != null ? placeItem : smelting ? Items.FURNACE : Items.CRAFTING_TABLE;
        int slot = -1;
        Inventory inv = self.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).is(item)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            stage = -1;
            return;
        }
        Equipment.select(self, slot);
        ServerLevel level = self.serverLevel();
        BlockPos feet = self.blockPosition();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos spot = feet.relative(d);
            if (level.getBlockState(spot).canBeReplaced() && level.getBlockState(spot.above()).canBeReplaced()
                    && !level.getBlockState(spot.below()).getCollisionShape(level, spot.below()).isEmpty()) {
                motor.lookAt(Vec3.atCenterOf(spot));
                if (motor.useOnTopFace(spot.below())) {
                    placed(spot);
                    return;
                }
            }
        }
        // no flat ground beside us (a tunnel, a pit): a table needs no support, so any free spot next to a solid face will do
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int up = 0; up <= 1; up++) {
                BlockPos spot = feet.relative(d).above(up);
                if (level.getBlockState(spot).canBeReplaced() && motor.placeBlockAt(spot)) {
                    placed(spot);
                    return;
                }
            }
        }
        StringBuilder sb = new StringBuilder("feet=").append(level.getBlockState(feet).getBlock()).append(" below=").append(level.getBlockState(feet.below()).getBlock());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos spot = feet.relative(d);
            sb.append(' ').append(d.getName().charAt(0)).append('=').append(level.getBlockState(spot).getBlock()).append('/').append(level.getBlockState(spot.below()).getBlock());
        }
        placeDebug = sb.toString().replace("Block{minecraft:", "").replace("}", "");
        stage = -1;
    }

    private void placed(BlockPos spot) {
        perception.noteBlock(spot);
        station = spot;
        stage = 2;
        if (placeOnly) {
            placeOnly = false;
            placeItem = null;
            stage = 0;
            return;
        }
        Equipment.manage(self, true);
    }

    private void walkAndOpen() {
        if (station == null) {
            stage = -1;
            return;
        }
        Perception.BlockKind want = smelting ? Perception.BlockKind.FURNACE : Perception.BlockKind.TABLE;
        if (Perception.classify(self.level().getBlockState(station)) != want) {
            perception.forgetBlock(station);
            if (station.equals(furnace)) {
                furnace = null;
            }
            stage = 0;
            return;
        }
        Vec3 center = Vec3.atCenterOf(station);
        if (self.getEyePosition().distanceTo(center) > Motor.BLOCK_REACH - 0.5) {
            motor.navigate(center, 2.0, false);
            if (motor.stuckCount() > 3) {
                perception.forgetBlock(station);
                stage = -1;
            }
            return;
        }
        motor.stop();
        motor.lookAt(center);
        BlockHitResult hit = new BlockHitResult(center.add(0, 0.5, 0), Direction.UP, station, false);
        self.gameMode.useItemOn(self, self.level(), self.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        self.swing(InteractionHand.MAIN_HAND);
        if (smelting ? self.containerMenu instanceof AbstractFurnaceMenu : self.containerMenu instanceof CraftingMenu) {
            stage = 3;
        } else if (timer > 200) {
            stage = -1;
        }
    }

    private void workInUi() {
        motor.stop();
        actions++;
        AbstractContainerMenu menu = self.containerMenu;
        if (smelting) {
            if (menu instanceof AbstractFurnaceMenu) {
                furnaceUi(menu);
            }
            stage = 0;
            return;
        }
        if (recipe == null || !(menu instanceof RecipeBookMenu<?> book)) {
            stage = 0;
            return;
        }
        if (!self.getRecipeBook().contains(recipe)) {
            // A player can always lay out an unknown recipe by hand; the book just saves the clicks.
            self.awardRecipes(List.<Recipe<?>>of(recipe));
        }
        book.handlePlacement(false, recipe, self);
        ItemStack result = menu.getSlot(0).getItem();
        if (!result.isEmpty()) {
            menu.clicked(0, 0, ClickType.QUICK_MOVE, self);
            crafted++;
            if (menu instanceof CraftingMenu && station != null) {
                lastTable = station;
            }
            if (self instanceof com.rlclones.clone.ClonePlayer c && c.controller() != null) {
                c.controller().discovery().watchInventory(); // study what was just made
            }
        }
        if (menu instanceof InventoryMenu) {
            clearInventoryGrid();
            stage = 0;
        } else {
            // stay at the table for the next recipe if it also needs the table
            CraftingRecipe next = plan(self);
            if (next != null) {
                recipe = next;
            } else {
                stage = 0;
            }
        }
    }

    private void furnaceUi(AbstractContainerMenu menu) {
        // 1) take finished output
        if (!menu.getSlot(2).getItem().isEmpty()) {
            menu.clicked(2, 0, ClickType.QUICK_MOVE, self);
            smelted++;
        }
        boolean inputEmpty = menu.getSlot(0).getItem().isEmpty();
        // 2) load new input + fuel with shift-clicks from the inventory part of the screen
        if (inputEmpty) {
            List<Integer> todo = smeltables(self);
            if (!todo.isEmpty() && self.getInventory().items.get(todo.get(0)).is(ItemTags.LOGS)) {
                loadSpareLogs(menu, todo.get(0), spareLogs(self));
            } else if (!todo.isEmpty()) {
                shiftClickInventorySlot(menu, todo.get(0));
                int fuel = fuelSlot(self);
                if (fuel >= 0 && menu.getSlot(1).getItem().isEmpty()) {
                    shiftClickInventorySlot(menu, fuel);
                }
            }
        }
        ItemStack input = menu.getSlot(0).getItem();
        if (!input.isEmpty()) {
            furnace = station;
            furnaceReadyAt = self.level().getGameTime() + 200L * input.getCount() + 20;
        } else {
            furnace = null;
        }
        self.closeContainer();
    }

    /** Put {@code n} logs of an inventory slot into the furnace input (pick the stack up, right-click them in one by one, put the rest back). */
    private void loadSpareLogs(AbstractContainerMenu menu, int inventoryIndex, int n) {
        Slot from = null;
        for (Slot slot : menu.slots) {
            if (slot.container == self.getInventory() && slot.getContainerSlot() == inventoryIndex) {
                from = slot;
            }
        }
        if (from == null || n <= 0) {
            return;
        }
        n = Math.min(n, from.getItem().getCount());
        menu.clicked(from.index, 0, ClickType.PICKUP, self);
        for (int i = 0; i < n; i++) {
            menu.clicked(0, 1, ClickType.PICKUP, self);
        }
        menu.clicked(from.index, 0, ClickType.PICKUP, self);
        if (!menu.getCarried().isEmpty()) {
            menu.clicked(from.index, 0, ClickType.QUICK_MOVE, self); // the rest of a stack that would not go back
        }
    }

    private void shiftClickInventorySlot(AbstractContainerMenu menu, int inventoryIndex) {
        for (Slot slot : menu.slots) {
            if (slot.container == self.getInventory() && slot.getContainerSlot() == inventoryIndex) {
                menu.clicked(slot.index, 0, ClickType.QUICK_MOVE, self);
                return;
            }
        }
    }

    /** Anything left in the 2x2 grid goes back into the inventory (shift-click each grid slot). */
    private void clearInventoryGrid() {
        InventoryMenu inv = self.inventoryMenu;
        Container grid = inv.getCraftSlots();
        for (int i = 0; i < grid.getContainerSize(); i++) {
            if (!grid.getItem(i).isEmpty()) {
                inv.clicked(InventoryMenu.CRAFT_SLOT_START + i, 0, ClickType.QUICK_MOVE, self);
            }
        }
    }

    public boolean isUsingUi() {
        return stage == 3 || self.containerMenu != self.inventoryMenu;
    }

    public static boolean isStation(Recipe<?> r) {
        return !r.canCraftInDimensions(2, 2);
    }
}
