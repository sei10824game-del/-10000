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
import java.util.List;
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

    public Crafting(ServerPlayer self, Motor motor, Perception perception) {
        this.self = self;
        this.motor = motor;
        this.perception = perception;
    }

    // ================================================================== planning

    /** Is there anything worth doing at a crafting table / furnace right now? */
    public boolean hasWork() {
        return plan(self) != null || smeltWork() || furnaceReady();
    }

    private boolean furnaceReady() {
        return furnace != null && self.level().getGameTime() >= furnaceReadyAt;
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
    private static List<Item> wanted(Player p) {
        List<Item> out = new ArrayList<>();
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
        return out;
    }

    private static boolean canCraft(Player p, CraftingRecipe r) {
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

    private static List<CraftingRecipe> recipesFor(ServerPlayer p, java.util.function.Predicate<ItemStack> result) {
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
        for (Item item : wanted) {
            for (CraftingRecipe r : recipesFor(p, s -> s.is(item))) {
                if ((tableAccess || r.canCraftInDimensions(2, 2)) && canCraft(p, r)) {
                    return r;
                }
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
        if (count(p, Items.FURNACE) == 0 && count(p, Items.COBBLESTONE) + count(p, Items.COBBLED_DEEPSLATE) >= 8
                && !smeltables(p).isEmpty() && !furnaceNearby(p)) {
            return craftable(p, s -> s.is(Items.FURNACE));
        }
        return null;
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
            if (s.isEmpty() || s.is(ItemTags.LOGS)) {
                continue;
            }
            Optional<SmeltingRecipe> r = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, new SimpleContainer(s.copy()), level);
            if (r.isPresent() && usefulSmeltResult(r.get().getResultItem(level.registryAccess()))) {
                out.add(i);
            }
        }
        return out;
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
        return best;
    }

    private boolean smeltWork() {
        return furnace == null && !smeltables(self).isEmpty() && fuelSlot(self) >= 0
                && (nearest(Perception.BlockKind.FURNACE, 24) != null || count(self, Items.FURNACE) > 0);
    }

    // ================================================================== execution

    public void reset() {
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
                reset();
                return Status.DONE;
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
        recipe = plan(self);
        if (recipe != null) {
            smelting = false;
            if (recipe.canCraftInDimensions(2, 2)) {
                stage = 3;
                return Status.WORKING;
            }
            station = nearest(Perception.BlockKind.TABLE, 24);
            stage = station == null ? (count(self, Items.CRAFTING_TABLE) > 0 ? 1 : -1) : 2;
            return stage < 0 ? done() : Status.WORKING;
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

    /** Put a crafting table / furnace on the ground next to us, like a player would. */
    private void placeStation() {
        Item item = smelting ? Items.FURNACE : Items.CRAFTING_TABLE;
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
                    perception.noteBlock(spot);
                    station = spot;
                    stage = 2;
                    Equipment.manage(self, true);
                    return;
                }
            }
        }
        stage = -1;
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
            if (!todo.isEmpty()) {
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
