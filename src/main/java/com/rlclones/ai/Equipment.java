package com.rlclones.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Inventory helpers. The static queries work on any player so clones can reason about others' gear too. */
public final class Equipment {
    private Equipment() {
    }

    // ------------------------------------------------------------------ queries (any player)

    public static double attackDamage(ItemStack stack) {
        double dmg = 1.0;
        double speed = 4.0;
        for (AttributeModifier m : stack.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE)) {
            if (m.getOperation() == AttributeModifier.Operation.ADDITION) {
                dmg += m.getAmount();
            }
        }
        for (AttributeModifier m : stack.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_SPEED)) {
            if (m.getOperation() == AttributeModifier.Operation.ADDITION) {
                speed += m.getAmount();
            }
        }
        return dmg * Math.sqrt(Math.max(0.25, speed) / 4.0) + (stack.isEnchanted() ? 0.5 : 0.0);
    }

    /** What swinging this item has actually done per hit (scaled like {@link #attackDamage}); 0 until tried a few times. */
    public static double learnedSwing(Player p, ItemStack s) {
        if (!(p instanceof com.rlclones.clone.ClonePlayer c) || c.getCloneBrain() == null) {
            return 0;
        }
        String id = AttackLearning.itemId(s);
        if (c.getCloneBrain().itemUseTries(id, AttackLearning.SWING) < 3) {
            return 0;
        }
        double speed = 4.0;
        for (AttributeModifier m : s.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_SPEED)) {
            if (m.getOperation() == AttributeModifier.Operation.ADDITION) {
                speed += m.getAmount();
            }
        }
        return c.getCloneBrain().itemUseValue(id, AttackLearning.SWING) * Math.sqrt(Math.max(0.25, speed) / 4.0);
    }

    public static int bestWeaponSlot(Player p) {
        Inventory inv = p.getInventory();
        int best = -1;
        double bestScore = 1.0;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty() || isLauncher(s) || s.isEdible()) {
                continue;
            }
            double score = Math.max(attackDamage(s), learnedSwing(p, s));
            if (score > bestScore + 0.01) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** Best weapon of one kind (e.g. {@code AxeItem} for jump crits, {@code SwordItem} for sweeps); -1 if none. */
    public static int bestOfKind(Player p, Class<?> kind) {
        Inventory inv = p.getInventory();
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && kind.isInstance(s.getItem()) && attackDamage(s) > bestScore) {
                bestScore = attackDamage(s);
                best = i;
            }
        }
        return best;
    }

    public static boolean isArmed(Player p) {
        return attackDamage(p.getMainHandItem()) >= 4.0;
    }

    public static boolean hasShield(Player p) {
        if (p.getOffhandItem().getItem() instanceof ShieldItem) {
            return !p.getCooldowns().isOnCooldown(p.getOffhandItem().getItem());
        }
        return false;
    }

    // ------------------------------------------------------------------ ranged & special weapons (vanilla and modded)

    /** How a ranged weapon is operated, detected from its class or its use animation (works for modded items too). */
    public enum RangedKind {NONE, BOW, CROSSBOW, THROWN}

    public static RangedKind rangedKind(ItemStack s) {
        if (s.isEmpty()) {
            return RangedKind.NONE;
        }
        Item item = s.getItem();
        if (item instanceof CrossbowItem) {
            return RangedKind.CROSSBOW;
        }
        if (item instanceof ProjectileWeaponItem) {
            return RangedKind.BOW;
        }
        if (item instanceof TridentItem) {
            return RangedKind.THROWN;
        }
        return switch (s.getUseAnimation()) {
            case BOW -> RangedKind.BOW;
            case CROSSBOW -> RangedKind.CROSSBOW;
            case SPEAR -> RangedKind.THROWN;
            default -> RangedKind.NONE;
        };
    }

    /** Pure launchers (bows / crossbows) are not used as melee weapons. */
    public static boolean isLauncher(ItemStack s) {
        RangedKind k = rangedKind(s);
        return k == RangedKind.BOW || k == RangedKind.CROSSBOW;
    }

    /** Can this ranged weapon be fired right now (ammo, riptide rules)? */
    public static boolean canFire(Player p, ItemStack s) {
        RangedKind k = rangedKind(s);
        if (k == RangedKind.NONE) {
            return false;
        }
        if (k == RangedKind.THROWN) {
            return EnchantmentHelper.getRiptide(s) <= 0 || p.isInWaterOrRain();
        }
        if (s.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(s)) {
            return true;
        }
        if (s.getItem() instanceof ProjectileWeaponItem) {
            return p.getAbilities().instabuild || !p.getProjectile(s).isEmpty() || s.getItem() instanceof CrossbowItem && hasWarRockets(p);
        }
        return true; // modded launcher with its own ammo logic: just try it
    }

    /** A firework rocket with stars: shot from a crossbow it explodes where it hits (a plain rocket does no harm). */
    public static boolean isWarRocket(ItemStack s) {
        if (!(s.getItem() instanceof FireworkRocketItem)) {
            return false;
        }
        net.minecraft.nbt.CompoundTag fw = s.getTagElement("Fireworks");
        return fw != null && !fw.getList("Explosions", net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty();
    }

    public static int warRocketSlot(Player p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (isWarRocket(inv.items.get(i))) {
                return i;
            }
        }
        return -1;
    }

    public static boolean hasWarRockets(Player p) {
        return warRocketSlot(p) >= 0 || isWarRocket(p.getOffhandItem());
    }

    /** A ranged weapon ready in the off hand (the main hand keeps the sword). */
    public static boolean offhandRanged(Player p) {
        ItemStack off = p.getOffhandItem();
        return rangedKind(off) != RangedKind.NONE && canFire(p, off);
    }

    /** Swap inventory slot {@code slot} with whatever is in the off hand. */
    public static void toOffhand(Player p, int slot) {
        if (slot < 0) {
            return;
        }
        Inventory inv = p.getInventory();
        ItemStack off = p.getOffhandItem().copy();
        p.setItemSlot(EquipmentSlot.OFFHAND, inv.items.get(slot).copy());
        inv.items.set(slot, off);
    }

    /** Off hand item back into the bag. */
    public static void stowOffhand(Player p) {
        ItemStack off = p.getOffhandItem().copy();
        if (!off.isEmpty() && p.getInventory().add(off)) {
            p.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        }
    }

    /**
     * Nothing that belongs in the off hand (shield, totem): with a melee weapon in the main hand, a ranged weapon goes
     * into the off hand so both are at the ready without switching - or, with nothing to shoot, food to eat on the go.
     */
    public static void offhandLoadout(Player p) {
        ItemStack off = p.getOffhandItem();
        if (off.getItem() instanceof ShieldItem || off.is(Items.TOTEM_OF_UNDYING) || attackDamage(p.getMainHandItem()) < 2.5) {
            return; // (a melee weapon in the main hand first: even a wooden sword)
        }
        if (isLauncher(off) && canFire(p, off)) {
            return;
        }
        Inventory inv = p.getInventory();
        int ranged = -1;
        int bestScore = 0;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (i == inv.selected || !isLauncher(s) || !canFire(p, s)) {
                continue;
            }
            int score = s.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(s) ? 3 : rangedKind(s) == RangedKind.BOW ? 2 : 1;
            if (score > bestScore) {
                bestScore = score;
                ranged = i;
            }
        }
        if (ranged >= 0) {
            toOffhand(p, ranged);
            return;
        }
        if (com.rlclones.ai.FoodAid.goodFood(off)) {
            return;
        }
        if (!off.isEmpty() && !(isLauncher(off) || off.getItem() instanceof FireworkRocketItem)) {
            return; // something put there on purpose (a torch, a map...)
        }
        int food = -1;
        double bestFood = 0;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (i != inv.selected && com.rlclones.ai.FoodAid.goodFood(s) && foodScore(p, s) > bestFood) {
                bestFood = foodScore(p, s);
                food = i;
            }
        }
        if (food >= 0) {
            toOffhand(p, food);
        }
    }

    /** Muzzle speed used for aiming (blocks/tick). */
    public static double projectileSpeed(ItemStack s) {
        return switch (rangedKind(s)) {
            case CROSSBOW -> 3.15;
            case THROWN -> 2.5;
            default -> 3.0;
        };
    }

    /** Best usable ranged weapon: a loaded crossbow first, then crossbow, bow, throwables. -1 if none. */
    /** A bow we can shoot right now (arrows at hand); -1 if none. */
    public static int bowSlot(Player p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (rangedKind(s) == RangedKind.BOW && canFire(p, s)) {
                return i;
            }
        }
        return -1;
    }

    public static int rangedSlot(Player p) {
        Inventory inv = p.getInventory();
        int best = -1;
        int bestScore = 0;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (!canFire(p, s)) {
                continue;
            }
            int score = switch (rangedKind(s)) {
                case CROSSBOW -> CrossbowItem.isCharged(s) ? 5 : 3;
                case BOW -> 4;
                case THROWN -> 2;
                default -> 0;
            };
            if (i == inv.selected) {
                score++;
            }
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static final String[] WEAPON_WORDS = {"gun", "staff", "wand", "blaster", "launcher", "cannon", "rifle", "pistol", "shotgun",
            "sling", "javelin", "spear", "throwing", "shuriken", "kunai", "dart", "bomb", "grenade", "boomerang", "scythe", "hammer",
            "katana", "dagger", "rapier", "halberd", "glaive", "sword", "axe", "mace", "whip", "tome", "scepter", "rod"};

    /**
     * A weapon with a right-click ability that is neither a known launcher nor food / shield: modded swords with skills,
     * guns, magic staffs, throwing weapons... The clone tries it (USE_ITEM) and learns per enemy whether it pays off.
     */
    public static boolean isSpecialWeapon(ItemStack s) {
        if (s.isEmpty() || s.isEdible() || s.getItem() instanceof ShieldItem || s.getItem() instanceof BlockItem
                || rangedKind(s) != RangedKind.NONE || !overridesUse(s.getItem())) {
            return false;
        }
        if (attackDamage(s) >= 3.0) {
            return true;
        }
        net.minecraft.resources.ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(s.getItem());
        if (id == null) {
            return false;
        }
        String path = id.getPath();
        for (String w : WEAPON_WORDS) {
            if (path.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Something worth trying on an enemy with right clicks as well as swings: special / modded weapons, tridents,
     * throwables, rods... (not food, potions, pearls, buckets, blocks, boats or launchers - those have their own uses).
     */
    public static boolean isTriable(ItemStack s) {
        if (s.isEmpty() || s.isEdible() || s.getItem() instanceof BlockItem || s.getItem() instanceof ShieldItem || isLauncher(s)
                || s.getItem() instanceof net.minecraft.world.item.PotionItem || s.is(Items.ENDER_PEARL) || s.is(Items.ENDER_EYE)
                || s.getItem() instanceof net.minecraft.world.item.BucketItem || s.getItem() instanceof net.minecraft.world.item.MilkBucketItem
                || s.getItem() instanceof net.minecraft.world.item.BoatItem || s.getItem() instanceof net.minecraft.world.item.MinecartItem
                || s.getItem() instanceof net.minecraft.world.item.SpawnEggItem || s.getItem() instanceof net.minecraft.world.item.FlintAndSteelItem
                || s.is(Items.EXPERIENCE_BOTTLE) || s.is(Items.FIREWORK_ROCKET) || s.getItem() instanceof net.minecraft.world.item.Equipable
                || s.getItem() instanceof net.minecraft.world.item.EmptyMapItem || s.getItem() instanceof net.minecraft.world.item.WritableBookItem
                || s.getItem() instanceof net.minecraft.world.item.WrittenBookItem || s.getItem() instanceof net.minecraft.world.item.SpyglassItem
                || s.getItem() instanceof net.minecraft.world.item.InstrumentItem || s.getItem() instanceof net.minecraft.world.item.BundleItem
                || s.getItem() instanceof net.minecraft.world.item.KnowledgeBookItem) {
            return false;
        }
        return isSpecialWeapon(s) || s.getItem() instanceof TridentItem || overridesUse(s.getItem());
    }

    /** A triable item (see {@link #isTriable}) that {@code ok} accepts, the selected one first; -1 if none. */
    public static int usableSlot(Player p, java.util.function.Predicate<ItemStack> ok) {
        Inventory inv = p.getInventory();
        ItemStack sel = inv.getSelected();
        if (isTriable(sel) && ok.test(sel) && !p.getCooldowns().isOnCooldown(sel.getItem())) {
            return inv.selected;
        }
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (isTriable(s) && ok.test(s) && !p.getCooldowns().isOnCooldown(s.getItem())) {
                return i;
            }
        }
        return -1;
    }

    public static int specialSlot(Player p) {
        Inventory inv = p.getInventory();
        if (isSpecialWeapon(inv.getSelected()) && !p.getCooldowns().isOnCooldown(inv.getSelected().getItem())) {
            return inv.selected;
        }
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (isSpecialWeapon(s) && !p.getCooldowns().isOnCooldown(s.getItem())) {
                return i;
            }
        }
        return -1;
    }

    private static final java.lang.reflect.Method ITEM_USE = findUseMethod();
    private static final java.util.Map<Class<?>, Boolean> USE_OVERRIDES = new java.util.concurrent.ConcurrentHashMap<>();

    private static java.lang.reflect.Method findUseMethod() {
        for (java.lang.reflect.Method m : Item.class.getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (m.getReturnType() == net.minecraft.world.InteractionResultHolder.class && params.length == 3
                    && params[0] == net.minecraft.world.level.Level.class && params[1] == Player.class
                    && params[2] == net.minecraft.world.InteractionHand.class) {
                return m;
            }
        }
        return null;
    }

    /** Does the item's class (or a parent below Item) implement its own right-click behaviour? Mapping independent. */
    public static boolean overridesUse(Item item) {
        if (ITEM_USE == null) {
            return false;
        }
        return USE_OVERRIDES.computeIfAbsent(item.getClass(), cls -> {
            for (Class<?> c = cls; c != null && c != Item.class; c = c.getSuperclass()) {
                try {
                    c.getDeclaredMethod(ITEM_USE.getName(), ITEM_USE.getParameterTypes());
                    return true;
                } catch (NoSuchMethodException ignored) {
                    // keep walking up
                }
            }
            return false;
        });
    }

    public static double foodScore(Player p, ItemStack s) {
        if (!s.isEdible()) {
            return -1;
        }
        FoodProperties food = s.getFoodProperties(p);
        if (food == null) {
            return -1;
        }
        double score = food.getNutrition() + food.getSaturationModifier() * food.getNutrition();
        if (!food.getEffects().isEmpty() && !food.canAlwaysEat()) {
            score -= 6;
        }
        return score;
    }

    public static int bestFoodSlot(Player p) {
        Inventory inv = p.getInventory();
        int best = -1;
        double bestScore = p.getFoodData().getFoodLevel() <= 6 ? -100 : 0;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEdible()) {
                continue;
            }
            double score = foodScore(p, s);
            if (score > bestScore && (p.canEat(false) || foodCanAlwaysEat(p, s))) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static boolean foodCanAlwaysEat(Player p, ItemStack s) {
        FoodProperties food = s.getFoodProperties(p);
        return food != null && food.canAlwaysEat();
    }

    public static boolean isPillarBlock(ItemStack s) {
        if (!(s.getItem() instanceof BlockItem bi)) {
            return false;
        }
        Block block = bi.getBlock();
        if (block instanceof EntityBlock || block instanceof FallingBlock || block instanceof net.minecraft.world.level.block.MagmaBlock
                || block instanceof net.minecraft.world.level.block.TntBlock) {
            return false;
        }
        BlockState state = block.defaultBlockState();
        return state.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                && !state.is(net.minecraftforge.common.Tags.Blocks.ORES)
                && !state.is(net.minecraftforge.common.Tags.Blocks.STORAGE_BLOCKS);
    }

    /** A block this player may build with: a full solid block it has not learned to be harmful. */
    public static boolean isBuildingBlock(Player p, ItemStack s) {
        if (!isPillarBlock(s)) {
            return false;
        }
        return !(p instanceof com.rlclones.clone.ClonePlayer c && c.getCloneBrain() != null
                && c.getCloneBrain().isHarmful(Perception.blockId(((BlockItem) s.getItem()).getBlock().defaultBlockState())));
    }

    public static int pillarBlockSlot(Player p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (isBuildingBlock(p, inv.items.get(i))) {
                return i;
            }
        }
        return -1;
    }

    public static int bestToolSlot(Player p, BlockState state) {
        Inventory inv = p.getInventory();
        int best = -1;
        float bestSpeed = 1.0f;
        boolean needsTool = state.requiresCorrectToolForDrops();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty()) {
                continue;
            }
            if (needsTool && !s.isCorrectToolForDrops(state)) {
                continue;
            }
            float speed = s.getDestroySpeed(state);
            if (speed > bestSpeed || (needsTool && best < 0)) {
                bestSpeed = speed;
                best = i;
            }
        }
        if (best < 0 && !needsTool) {
            return bareSlot(p); // R-31: no tool helps here: dig with a hand / a non-tool, not a pickaxe that only wears down
        }
        return best;
    }

    /** A slot whose item takes no wear from digging: the held one if it is such, else an empty slot, else a block / food. */
    private static int bareSlot(Player p) {
        Inventory inv = p.getInventory();
        int found = -1;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty() || !s.isDamageableItem() && !(s.getItem() instanceof net.minecraft.world.item.TieredItem)) {
                if (i == inv.selected) {
                    return i;
                }
                if (found < 0 || s.isEmpty() && !inv.items.get(found).isEmpty()) {
                    found = i;
                }
            }
        }
        return found;
    }

    public static boolean canHarvest(Player p, BlockState state) {
        return !state.requiresCorrectToolForDrops() || bestToolSlot(p, state) >= 0;
    }

    // ------------------------------------------------------------------ actions (the clone itself)

    /** Makes the given inventory slot the selected hotbar slot, swapping it into the hotbar if needed. */
    public static void select(Player p, int slot) {
        if (slot < 0) {
            return;
        }
        Inventory inv = p.getInventory();
        if (slot < Inventory.getSelectionSize()) {
            inv.selected = slot;
            return;
        }
        int target = inv.selected;
        ItemStack held = inv.items.get(target);
        inv.items.set(target, inv.items.get(slot));
        inv.items.set(slot, held);
    }

    /** Wear the best armour, put a shield in the off hand and hold the best weapon. */
    public static void manage(Player p, boolean holdWeapon) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.getItem() instanceof ArmorItem armor) {
                EquipmentSlot slot = armor.getEquipmentSlot();
                ItemStack worn = p.getItemBySlot(slot);
                boolean keepElytra = slot == EquipmentSlot.CHEST && isUsableElytra(worn) && (p.isFallFlying() || !p.onGround());
                if (!keepElytra && (worn.isEmpty() || armorScore(s) > armorScore(worn))) {
                    p.setItemSlot(slot, s.copy());
                    inv.items.set(i, worn.copy());
                }
            }
        }
        ItemStack off = p.getOffhandItem();
        if (!(off.getItem() instanceof ShieldItem) && !off.is(Items.TOTEM_OF_UNDYING)) {
            for (int i = 0; i < inv.items.size(); i++) {
                ItemStack s = inv.items.get(i);
                if (s.getItem() instanceof ShieldItem) {
                    p.setItemSlot(EquipmentSlot.OFFHAND, s.copy());
                    inv.items.set(i, off.copy());
                    break;
                }
            }
        }
        // no chest armour at all -> wear the elytra so a fall can always turn into a glide
        if (p.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) {
            int el = elytraSlot(p);
            if (el >= 0) {
                p.setItemSlot(EquipmentSlot.CHEST, inv.items.get(el).copy());
                inv.items.set(el, ItemStack.EMPTY);
            }
        }
        if (holdWeapon) {
            int weapon = bestWeaponSlot(p);
            if (weapon >= 0 && attackDamage(inv.items.get(weapon)) > attackDamage(p.getMainHandItem()) + 0.01) {
                select(p, weapon);
            }
            offhandLoadout(p);
        }
    }

    // ------------------------------------------------------------------ elytra / rockets

    public static boolean isUsableElytra(ItemStack s) {
        return s.getItem() instanceof ElytraItem && ElytraItem.isFlyEnabled(s);
    }

    public static boolean wearsElytra(Player p) {
        return isUsableElytra(p.getItemBySlot(EquipmentSlot.CHEST));
    }

    public static int elytraSlot(Player p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (isUsableElytra(inv.items.get(i))) {
                return i;
            }
        }
        return -1;
    }

    public static boolean hasElytra(Player p) {
        return wearsElytra(p) || elytraSlot(p) >= 0;
    }

    /** Swap the elytra onto the chest (the current chest piece goes into the inventory), like right-clicking it. */
    public static boolean equipElytra(Player p) {
        if (wearsElytra(p)) {
            return true;
        }
        int slot = elytraSlot(p);
        if (slot < 0) {
            return false;
        }
        Inventory inv = p.getInventory();
        ItemStack worn = p.getItemBySlot(EquipmentSlot.CHEST).copy();
        p.setItemSlot(EquipmentSlot.CHEST, inv.items.get(slot).copy());
        inv.items.set(slot, worn);
        return true;
    }

    public static int rocketSlot(Player p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).getItem() instanceof FireworkRocketItem) {
                return i;
            }
        }
        return -1;
    }

    private static double armorScore(ItemStack s) {
        if (s.getItem() instanceof ArmorItem a) {
            return a.getDefense() + a.getToughness() * 0.5 + (s.isEnchanted() ? 0.25 : 0);
        }
        return 0;
    }
}
