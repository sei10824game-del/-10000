package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.Bases;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Chests: rummaging through stray (dungeon / village / shipwreck...) chests, keeping a home base with storage chests,
 * depositing what cannot be used right now and fetching it back when it is needed. Every chest action is reported in
 * chat with the coordinates and the items, so other clones (and players) know what is where.
 */
public final class Storage {
    public enum Mode {LOOT, STORE, FETCH}

    public enum Status {WORKING, DONE, FAILED}

    private final ServerPlayer self;
    private final Motor motor;
    private final Perception perception;
    private final Crafting crafting;
    private final Builder builder;

    private Mode mode;
    private int stage;
    private int ticks;
    @Nullable
    private BlockPos chest;
    @Nullable
    private Bases.Base base;
    private int openTries;
    private final Set<BlockPos> looted = new HashSet<>();
    private final Map<BlockPos, Boolean> strayCache = new HashMap<>();
    private final Set<BlockPos> fullChests = new HashSet<>();
    /** Chests that could not be reached / opened; not tried again for a while. */
    private final Map<BlockPos, Long> unreachable = new HashMap<>();

    public int deposits;
    public int withdrawals;
    public int lootings;

    public Storage(ServerPlayer self, Motor motor, Perception perception, Crafting crafting) {
        this.self = self;
        this.motor = motor;
        this.perception = perception;
        this.crafting = crafting;
        this.builder = new Builder(self, motor);
    }

    public Builder builder() {
        return builder;
    }

    // ================================================================== policy: what do I need?

    public static boolean isSeed(ItemStack s) {
        return s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof CropBlock;
    }

    private static final Set<Item> KEEP = Set.of(Items.STICK, Items.COAL, Items.CHARCOAL, Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND,
            Items.RAW_IRON, Items.RAW_GOLD, Items.TORCH, Items.ELYTRA, Items.FIREWORK_ROCKET, Items.TOTEM_OF_UNDYING, Items.ENDER_PEARL,
            Items.CRAFTING_TABLE, Items.FURNACE, Items.CHEST, Items.BUCKET, Items.WATER_BUCKET, Items.FLINT_AND_STEEL, Items.NETHERITE_INGOT);

    private static int toolKind(Item it) {
        if (it instanceof PickaxeItem) {
            return 0;
        }
        if (it instanceof AxeItem) {
            return 1;
        }
        if (it instanceof ShovelItem) {
            return 2;
        }
        if (it instanceof HoeItem) {
            return 3;
        }
        return -1;
    }

    /** Inventory slots holding things this clone cannot use right now (seeds surplus, duplicate weapons / gear, junk). */
    public static List<Integer> unneededSlots(Player p) {
        Inventory inv = p.getInventory();
        List<Integer> out = new ArrayList<>();
        int weapon = Equipment.bestWeaponSlot(p);
        int ranged = Equipment.rangedSlot(p);
        int[] bestTool = {-1, -1, -1, -1};
        int[] bestTier = {-1, -1, -1, -1};
        for (int i = 0; i < inv.items.size(); i++) {
            Item it = inv.items.get(i).getItem();
            int k = toolKind(it);
            if (k >= 0 && it instanceof TieredItem t && t.getTier().getLevel() > bestTier[k]) {
                bestTier[k] = t.getTier().getLevel();
                bestTool[k] = i;
            }
        }
        Map<EquipmentSlot, Integer> bestArmor = new HashMap<>();
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).getItem() instanceof ArmorItem a) {
                Integer cur = bestArmor.get(a.getEquipmentSlot());
                if (cur == null || a.getDefense() > ((ArmorItem) inv.items.get(cur).getItem()).getDefense()) {
                    bestArmor.put(a.getEquipmentSlot(), i);
                }
            }
        }
        boolean launcher = ranged >= 0 && Equipment.isLauncher(inv.items.get(ranged));
        boolean offShield = p.getOffhandItem().getItem() instanceof ShieldItem;
        int food = 0;
        int seeds = 0;
        int blocks = 0;
        int wood = 0;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty() || i == weapon || i == ranged || i == inv.selected) {
                continue;
            }
            Item it = s.getItem();
            int k = toolKind(it);
            if (k >= 0) {
                if (i != bestTool[k]) {
                    out.add(i);
                }
            } else if (it instanceof ArmorItem a) {
                ItemStack worn = p.getItemBySlot(a.getEquipmentSlot());
                double wornDef = worn.getItem() instanceof ArmorItem w ? w.getDefense() : -1;
                if (bestArmor.get(a.getEquipmentSlot()) != i || a.getDefense() <= wornDef) {
                    out.add(i);
                }
            } else if (it instanceof ShieldItem) {
                if (offShield) {
                    out.add(i);
                } else {
                    offShield = true;
                }
            } else if (Equipment.attackDamage(s) >= 3.0 || Equipment.rangedKind(s) != Equipment.RangedKind.NONE) {
                if (!Equipment.isSpecialWeapon(s)) {
                    out.add(i); // surplus weapon of a kind we already carry a better one of
                }
            } else if (s.isEdible()) {
                if (Equipment.foodScore(p, s) > 0 && food < 3) {
                    food++;
                } else {
                    out.add(i);
                }
            } else if (isSeed(s)) {
                if (seeds++ >= 1) {
                    out.add(i);
                }
            } else if (s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS)) {
                wood += s.getCount();
                if (wood > 64) {
                    out.add(i);
                }
            } else if (Equipment.isPillarBlock(s)) {
                blocks += s.getCount();
                if (blocks > 128) {
                    out.add(i);
                }
            } else if (s.is(Items.ARROW) || s.is(Items.SPECTRAL_ARROW) || s.is(Items.TIPPED_ARROW)) {
                if (!launcher) {
                    out.add(i);
                }
            } else if (!KEEP.contains(it)) {
                out.add(i);
            }
        }
        return out;
    }

    private static int freeSlots(Player p) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) {
            if (s.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    public static boolean needsStore(Player p) {
        int unneeded = unneededSlots(p).size();
        return unneeded >= 5 || (unneeded >= 1 && freeSlots(p) <= 3);
    }

    /** Things worth fetching from a base chest (better gear than carried, food when there is none...). */
    public static Predicate<ItemStack> wants(Player p) {
        double weapon = Equipment.attackDamage(p.getMainHandItem());
        int w = Equipment.bestWeaponSlot(p);
        if (w >= 0) {
            weapon = Math.max(weapon, Equipment.attackDamage(p.getInventory().items.get(w)));
        }
        double bestWeapon = weapon;
        boolean hasPick = false;
        boolean hasFood = false;
        int blocks = 0;
        for (ItemStack s : p.getInventory().items) {
            hasPick |= s.getItem() instanceof PickaxeItem;
            hasFood |= Equipment.foodScore(p, s) > 0;
            if (Equipment.isPillarBlock(s)) {
                blocks += s.getCount();
            }
        }
        boolean needPick = !hasPick;
        boolean needFood = !hasFood && p.getFoodData().getFoodLevel() <= 16;
        boolean needBlocks = blocks < 8;
        boolean hasShield = p.getOffhandItem().getItem() instanceof ShieldItem;
        return s -> {
            if (s.isEmpty()) {
                return false;
            }
            Item it = s.getItem();
            if (it instanceof ArmorItem a) {
                ItemStack worn = p.getItemBySlot(a.getEquipmentSlot());
                double wornDef = worn.getItem() instanceof ArmorItem x ? x.getDefense() : 0;
                return a.getDefense() > wornDef;
            }
            if (Equipment.attackDamage(s) > bestWeapon + 0.5 && !(it instanceof PickaxeItem) && !(it instanceof ShovelItem) && !(it instanceof HoeItem)) {
                return true;
            }
            if (needPick && it instanceof PickaxeItem) {
                return true;
            }
            if (!hasShield && it instanceof ShieldItem) {
                return true;
            }
            if (needFood && Equipment.foodScore(p, s) > 0) {
                return true;
            }
            return needBlocks && Equipment.isPillarBlock(s);
        };
    }

    // ================================================================== what is available

    @Nullable
    private BlockPos fetchableChest() {
        Bases bases = Bases.get(self.getServer());
        Predicate<ItemStack> want = wants(self);
        BlockPos best = null;
        double bestD = 128;
        for (Bases.Base b : bases.bases) {
            if (b.dimension != self.level().dimension()) {
                continue;
            }
            for (BlockPos c : b.chests) {
                double d = Vec3.atCenterOf(c).distanceTo(self.position());
                if (d >= bestD || blocked(c)) {
                    continue;
                }
                for (var e : bases.contents(b.dimension, c).entrySet()) {
                    Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(e.getKey()));
                    if (item != null && want.test(new ItemStack(item))) {
                        bestD = d;
                        best = c;
                        break;
                    }
                }
            }
        }
        return best;
    }

    public boolean canFetch() {
        return fetchableChest() != null;
    }

    /** A stray chest: generated loot (loot table still attached) or inside a structure, and not one of our bases. */
    private boolean isStray(BlockPos pos) {
        ServerLevel level = self.serverLevel();
        if (Bases.get(self.getServer()).isBaseChest(level.dimension(), pos)) {
            return false;
        }
        return strayCache.computeIfAbsent(pos.immutable(), p -> {
            BlockEntity be = level.getBlockEntity(p);
            if (!(be instanceof RandomizableContainerBlockEntity)) {
                return false;
            }
            CompoundTag tag = be.saveWithoutMetadata();
            if (tag.contains("LootTable")) {
                return true;
            }
            return !level.structureManager().getAllStructuresAt(p).isEmpty();
        });
    }

    @Nullable
    private BlockPos strayChest() {
        BlockPos best = null;
        double bestD = 32 * 32;
        for (Map.Entry<BlockPos, Perception.BlockKind> e : perception.blocks().entrySet()) {
            if (e.getValue() != Perception.BlockKind.CHEST || looted.contains(e.getKey()) || blocked(e.getKey())) {
                continue;
            }
            double d = e.getKey().distToCenterSqr(self.position());
            if (d < bestD && isStray(e.getKey())) {
                bestD = d;
                best = e.getKey();
            }
        }
        return best;
    }

    public boolean canLoot() {
        return freeSlots(self) >= 2 && strayChest() != null;
    }

    public boolean canStore() {
        if (!needsStore(self)) {
            return false;
        }
        if (Bases.get(self.getServer()).nearest(self.level().dimension(), self.position(), 96) != null) {
            return true;
        }
        return hasChestOrMaterial() && Config.get(Config.ALLOW_BLOCK_PLACING, true);
    }

    private boolean hasChestOrMaterial() {
        int planks = 0;
        int need = self.getInventory().countItem(Items.CRAFTING_TABLE) > 0
                || Senses.nearestBlock(perception, self, Perception.BlockKind.TABLE, 24) != null ? 8 : 12;
        int logs = 0;
        for (ItemStack s : self.getInventory().items) {
            if (s.is(Items.CHEST)) {
                return true;
            }
            if (s.is(ItemTags.PLANKS)) {
                planks += s.getCount();
            }
            if (s.is(ItemTags.LOGS)) {
                logs += s.getCount();
            }
        }
        return planks + logs * 4 >= need;
    }

    // ================================================================== execution

    private boolean blocked(BlockPos pos) {
        Long since = unreachable.get(pos);
        return since != null && self.level().getGameTime() - since < 6000;
    }

    public void begin(Mode mode) {
        if (self.containerMenu != self.inventoryMenu) {
            self.closeContainer();
        }
        this.mode = mode;
        stage = 0;
        ticks = 0;
        chest = null;
        base = null;
        openTries = 0;
        crafting.forcedTarget = null;
    }

    public void reset() {
        if (self.containerMenu != self.inventoryMenu) {
            self.closeContainer();
        }
        crafting.forcedTarget = null;
        crafting.reset();
        mode = null;
    }

    public Status tick() {
        if (mode == null) {
            return Status.DONE;
        }
        if (++ticks > 4800) {
            reset();
            return Status.FAILED;
        }
        Status s = switch (mode) {
            case LOOT -> lootTick();
            case STORE -> storeTick();
            case FETCH -> fetchTick();
        };
        if (s == Status.FAILED && chest != null) {
            unreachable.put(chest.immutable(), self.level().getGameTime());
        }
        if (s != Status.WORKING) {
            reset();
        }
        return s;
    }

    private Status lootTick() {
        if (chest == null) {
            chest = strayChest();
            if (chest == null) {
                return Status.DONE;
            }
        }
        BlockPos target = chest;
        Status s = visit(target, menu -> {
            Map<Item, Integer> before = snapshot();
            for (Slot slot : containerSlots(menu)) {
                if (!slot.getItem().isEmpty() && freeSlots(self) > 0) {
                    menu.clicked(slot.index, 0, ClickType.QUICK_MOVE, self);
                }
            }
            looted.add(target);
            lootings++;
            report("LOOT", "rlclones.chat.loot", target, diff(snapshot(), before));
        });
        return s;
    }

    private Status fetchTick() {
        if (chest == null) {
            chest = fetchableChest();
            if (chest == null) {
                return Status.DONE;
            }
        }
        BlockPos target = chest;
        return visit(target, menu -> {
            Map<Item, Integer> before = snapshot();
            for (Slot slot : containerSlots(menu)) {
                if (!slot.getItem().isEmpty() && wants(self).test(slot.getItem()) && freeSlots(self) > 0) {
                    menu.clicked(slot.index, 0, ClickType.QUICK_MOVE, self);
                    Equipment.manage(self, true);
                }
            }
            withdrawals++;
            report("WITHDRAW", "rlclones.chat.withdraw", target, diff(snapshot(), before));
        });
    }

    private Status storeTick() {
        Bases bases = Bases.get(self.getServer());
        switch (stage) {
            case 0 -> {
                base = bases.nearest(self.level().dimension(), self.position(), 96);
                if (base != null) {
                    stage = 3; // there is already a base around: make it ours too
                    return Status.WORKING;
                }
                if (count(Items.CHEST) == 0) {
                    stage = 5; // craft the storage chest first, out here in the open
                    return Status.WORKING;
                }
                List<BlockPos> avoid = new ArrayList<>();
                for (Bases.Base b : bases.bases) {
                    avoid.add(b.center);
                }
                BlockPos site = Builder.buildingBlocks(self) >= Builder.BLOCKS ? Builder.findSite(self, avoid) : null;
                if (site != null) {
                    builder.start(site);
                    stage = 1;
                } else {
                    stage = 10; // not enough to build a house: a simple chest stash
                    openTries = 0;
                }
                return Status.WORKING;
            }
            case 1 -> {
                Builder.Status b = builder.tick();
                if (b == Builder.Status.DONE || (b == Builder.Status.FAILED && builder.placed >= 30)) {
                    base = bases.add(self.level().dimension(), builder.center(), self.getGameProfile().getName());
                    report("BASE", "rlclones.chat.base", builder.center(), Map.of());
                    stage = 2;
                    openTries = 0;
                } else if (b == Builder.Status.FAILED) {
                    stage = 10;
                    openTries = 0;
                }
                return Status.WORKING;
            }
            case 5 -> {
                if (craftChest()) {
                    stage = 0;
                    openTries = 0;
                } else if (crafting.forcedTarget == null) {
                    return Status.FAILED;
                }
                return Status.WORKING;
            }
            case 2, 10 -> {
                // get a chest (craft one through the crafting UI if needed) and put it down
                if (count(Items.CHEST) == 0) {
                    if (stage == 2 && Motor.horizontalDistance(self.position(), Vec3.atBottomCenterOf(base.center)) < 4) {
                        // step out of the hut first so the crafting table does not end up in the doorway
                        motor.navigate(Vec3.atBottomCenterOf(base.center.offset(0, 0, -4)), 0.8, false);
                        return motor.stuckCount() > 6 ? Status.FAILED : Status.WORKING;
                    }
                    if (!craftChest() && crafting.forcedTarget == null) {
                        return stage == 10 ? Status.FAILED : Status.DONE;
                    }
                    return Status.WORKING;
                }
                crafting.forcedTarget = null;
                BlockPos spot = stage == 2 ? freeChestSpot(base) : strayStashSpot();
                if (spot == null) {
                    return Status.FAILED;
                }
                if (stage == 2 && self.getEyePosition().distanceTo(Vec3.atCenterOf(spot)) > Motor.BLOCK_REACH - 0.5) {
                    motor.navigate(Vec3.atBottomCenterOf(base.center), 0.5, false);
                    return motor.stuckCount() > 6 ? Status.FAILED : Status.WORKING;
                }
                Equipment.select(self, slotOf(Items.CHEST));
                if (motor.placeBlockAt(spot)) {
                    if (stage == 10) {
                        base = bases.add(self.level().dimension(), spot, self.getGameProfile().getName());
                        report("BASE", "rlclones.chat.base", spot, Map.of());
                    }
                    bases.addChest(base, spot);
                    perception.noteBlock(spot);
                    Equipment.manage(self, true);
                    stage = 3;
                } else if (++openTries > 40) {
                    return Status.FAILED;
                }
                return Status.WORKING;
            }
            default -> {
                if (base == null) {
                    return Status.FAILED;
                }
                if (unneededSlots(self).isEmpty()) {
                    return Status.DONE;
                }
                if (chest == null) {
                    for (BlockPos c : base.chests) {
                        if (!fullChests.contains(c) && self.level().getBlockState(c).getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
                            chest = c;
                            break;
                        }
                    }
                    if (chest == null) {
                        // every chest full (or none yet): add one to the base
                        stage = 2;
                        openTries = 0;
                        return Status.WORKING;
                    }
                }
                BlockPos target = chest;
                Status s = visit(target, menu -> {
                    Map<Item, Integer> before = snapshot();
                    for (int idx : unneededSlots(self)) {
                        shiftClickInventorySlot(menu, idx);
                    }
                    Map<Item, Integer> moved = diff(before, snapshot());
                    if (!unneededSlots(self).isEmpty()) {
                        fullChests.add(target);
                    }
                    deposits++;
                    report("DEPOSIT", "rlclones.chat.deposit", target, moved);
                });
                if (s == Status.DONE) {
                    chest = null;
                    return unneededSlots(self).isEmpty() ? Status.DONE : Status.WORKING;
                }
                return s;
            }
        }
    }

    /**
     * Craft a chest through the crafting UI. Returns true once one is in the inventory; clears
     * {@code crafting.forcedTarget} when it cannot be made.
     */
    private boolean craftChest() {
        if (count(Items.CHEST) > 0) {
            crafting.forcedTarget = null;
            crafting.reset(); // closes the crafting table screen
            return true;
        }
        crafting.forcedTarget = Items.CHEST;
        if (crafting.tick() == Crafting.Status.DONE && count(Items.CHEST) == 0) {
            if (++openTries > 3) {
                crafting.forcedTarget = null;
            }
        }
        return false;
    }

    @Nullable
    private BlockPos freeChestSpot(Bases.Base b) {
        BlockPos origin = b.center.offset(-2, 0, -2);
        for (BlockPos p : Builder.chestSpots(origin)) {
            if (self.level().getBlockState(p).canBeReplaced() && self.level().getBlockState(p.above()).canBeReplaced()) {
                return p;
            }
        }
        return null;
    }

    @Nullable
    private BlockPos strayStashSpot() {
        BlockPos feet = self.blockPosition();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = feet.relative(d);
            if (self.level().getBlockState(p).canBeReplaced() && self.level().getBlockState(p.above()).canBeReplaced()
                    && !self.level().getBlockState(p.below()).getCollisionShape(self.level(), p.below()).isEmpty()) {
                return p;
            }
        }
        return null;
    }

    // ================================================================== chest UI

    /** Walk to a chest, open it like a player (right click), run {@code work} on the open screen, close it. */
    private Status visit(BlockPos pos, java.util.function.Consumer<AbstractContainerMenu> work) {
        ServerLevel level = self.serverLevel();
        if (level.getBlockEntity(pos) == null) {
            perception.forgetBlock(pos);
            return Status.FAILED;
        }
        if (self.containerMenu != self.inventoryMenu) {
            AbstractContainerMenu menu = self.containerMenu;
            work.accept(menu);
            Container container = menuContainer(menu);
            if (container != null && Bases.get(self.getServer()).isBaseChest(level.dimension(), pos)) {
                Bases.get(self.getServer()).record(level.dimension(), pos, container);
            }
            self.closeContainer();
            return Status.DONE;
        }
        Vec3 center = Vec3.atCenterOf(pos);
        if (self.getEyePosition().distanceTo(center) > Motor.BLOCK_REACH - 0.5) {
            motor.navigate(center, 2.0, false);
            return motor.stuckCount() > 6 ? Status.FAILED : Status.WORKING;
        }
        motor.stop();
        motor.lookAt(center);
        Direction face = Direction.getNearest(self.getX() - center.x, self.getEyeY() - center.y, self.getZ() - center.z);
        Vec3 hit = center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        self.gameMode.useItemOn(self, level, self.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        self.swing(InteractionHand.MAIN_HAND);
        if (self.containerMenu == self.inventoryMenu && ++openTries > 60) {
            return Status.FAILED; // blocked (solid block / cat on top...)
        }
        return Status.WORKING;
    }

    private List<Slot> containerSlots(AbstractContainerMenu menu) {
        List<Slot> out = new ArrayList<>();
        for (Slot s : menu.slots) {
            if (s.container != self.getInventory()) {
                out.add(s);
            }
        }
        return out;
    }

    @Nullable
    private Container menuContainer(AbstractContainerMenu menu) {
        for (Slot s : menu.slots) {
            if (s.container != self.getInventory()) {
                return s.container;
            }
        }
        return null;
    }

    private void shiftClickInventorySlot(AbstractContainerMenu menu, int inventoryIndex) {
        for (Slot slot : menu.slots) {
            if (slot.container == self.getInventory() && slot.getContainerSlot() == inventoryIndex) {
                menu.clicked(slot.index, 0, ClickType.QUICK_MOVE, self);
                return;
            }
        }
    }

    private int count(Item item) {
        return self.getInventory().countItem(item);
    }

    private int slotOf(Item item) {
        Inventory inv = self.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    // ================================================================== chat reports

    private Map<Item, Integer> snapshot() {
        Map<Item, Integer> m = new HashMap<>();
        for (ItemStack s : self.getInventory().items) {
            if (!s.isEmpty()) {
                m.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        return m;
    }

    /** Items present in {@code a} but not (or fewer) in {@code b}. */
    private static Map<Item, Integer> diff(Map<Item, Integer> a, Map<Item, Integer> b) {
        Object2IntLinkedOpenHashMap<Item> out = new Object2IntLinkedOpenHashMap<>();
        a.forEach((item, n) -> {
            int d = n - b.getOrDefault(item, 0);
            if (d > 0) {
                out.put(item, d);
            }
        });
        return out;
    }

    private void report(String verb, String key, BlockPos pos, Map<Item, Integer> items) {
        MutableComponent list = Component.empty();
        StringBuilder plain = new StringBuilder(verb).append(' ').append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ());
        int shown = 0;
        for (Map.Entry<Item, Integer> e : items.entrySet()) {
            if (shown > 0) {
                list.append(", ");
            }
            if (shown++ >= 8) {
                list.append("…");
                break;
            }
            list.append(new ItemStack(e.getKey()).getHoverName()).append("×" + e.getValue());
        }
        boolean first = true;
        for (Map.Entry<Item, Integer> e : items.entrySet()) {
            plain.append(first ? ' ' : ',').append(ForgeRegistries.ITEMS.getKey(e.getKey())).append('=').append(e.getValue());
            first = false;
        }
        Chat.say(self, Component.translatable(key, pos.getX(), pos.getY(), pos.getZ(), list), plain.toString());
    }
}
