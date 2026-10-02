package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.ai.brain.Brain;
import com.rlclones.clone.Bases;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Curiosity: blocks the clone has never examined are mined and taken; every newly obtained item is studied (what it
 * crafts into, whether it smelts, burns, feeds, glows, lets monsters spawn on it, what a potion does...) and announced.
 * Clones nearby that do not know a special item yet are told (and handed one when there is a spare).
 */
public final class Discovery {
    private final ClonePlayer self;
    private final Motor motor;
    private final Perception perception;

    @Nullable
    private BlockPos target;
    private String targetId = "";
    private int ticks;
    private int collect;
    private final Map<String, Long> toldAt = new HashMap<>();

    public int discovered;
    public int blocksExamined;
    public int told;
    public int given;

    public Discovery(ClonePlayer self, Motor motor, Perception perception) {
        this.self = self;
        this.motor = motor;
        this.perception = perception;
    }

    @Nullable
    private Brain brain() {
        return self.getCloneBrain();
    }

    // ================================================================== what is an item good for?

    /** Knowledge key of an item: its id, plus the potion for potions / tipped arrows. */
    public static String keyOf(ItemStack s) {
        String k = String.valueOf(ForgeRegistries.ITEMS.getKey(s.getItem()));
        Potion potion = PotionUtils.getPotion(s);
        if (potion != Potions.EMPTY) {
            k += "#" + ForgeRegistries.POTIONS.getKey(potion);
        }
        return k;
    }

    public static ItemStack stackOf(String key) {
        String[] parts = key.split("#", 2);
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(parts[0]));
        ItemStack s = new ItemStack(item == null ? Items.AIR : item);
        if (parts.length > 1) {
            Potion p = ForgeRegistries.POTIONS.getValue(new ResourceLocation(parts[1]));
            if (p != null) {
                PotionUtils.setPotion(s, p);
            }
        }
        return s;
    }

    private static String ids(List<ItemStack> stacks, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(max, stacks.size()); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(ForgeRegistries.ITEMS.getKey(stacks.get(i).getItem()));
        }
        return b.toString();
    }

    /** Study an item: returns ";"-separated fact tokens (see {@link #describe}). */
    public static String facts(ClonePlayer p, ItemStack stack) {
        List<String> f = new ArrayList<>();
        ServerLevel level = p.serverLevel();
        List<ItemStack> uses = Crafting.usesOf(p, stack);
        uses.sort(Comparator.comparingInt((ItemStack s) -> s.isEdible() ? 0 : 1));
        if (!uses.isEmpty()) {
            f.add("craft=" + ids(uses, 4));
            if (uses.size() <= 2) {
                // e.g. hay bale -> wheat -> bread: look one step further
                List<ItemStack> next = new ArrayList<>();
                for (ItemStack u : uses) {
                    for (ItemStack n : Crafting.usesOf(p, u)) {
                        if (!n.is(stack.getItem()) && next.stream().noneMatch(x -> x.is(n.getItem()))) {
                            next.add(n);
                        }
                    }
                }
                next.sort(Comparator.comparingInt((ItemStack s) -> s.isEdible() ? 0 : 1));
                if (!next.isEmpty()) {
                    f.add("then=" + ids(next, 3));
                }
            }
        }
        Optional<SmeltingRecipe> sm = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, new SimpleContainer(stack.copyWithCount(1)), level);
        sm.ifPresent(r -> f.add("smelt=" + ForgeRegistries.ITEMS.getKey(r.getResultItem(level.registryAccess()).getItem())));
        int burn = ForgeHooks.getBurnTime(stack, RecipeType.SMELTING);
        if (burn > 0) {
            f.add("fuel=" + burn);
        }
        if (stack.isEdible()) {
            FoodProperties fp = stack.getFoodProperties(p);
            if (fp != null) {
                f.add("food=" + fp.getNutrition());
                if (!fp.getEffects().isEmpty()) {
                    f.add("foodfx");
                }
            }
        }
        if (stack.getItem() instanceof BlockItem bi) {
            Block b = bi.getBlock();
            BlockState st = b.defaultBlockState();
            if (st.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                f.add(st.isValidSpawn(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, EntityType.ZOMBIE) ? "spawn" : "nospawn");
            }
            if (st.getLightEmission() > 0) {
                f.add("light=" + st.getLightEmission());
            }
            if (b instanceof FallingBlock) {
                f.add("gravity");
            }
            if (b instanceof IPlantable) {
                f.add("plant");
            }
            Brain brain = p.getCloneBrain();
            if (brain != null && brain.isHarmful(Perception.blockId(st))) {
                f.add("harmful");
            }
        }
        int effects = 0;
        for (MobEffectInstance e : PotionUtils.getMobEffects(stack)) {
            if (effects++ >= 3) {
                break;
            }
            f.add("effect=" + ForgeRegistries.MOB_EFFECTS.getKey(e.getEffect()) + "|" + e.getAmplifier() + "|" + e.getDuration());
        }
        if (!(stack.getItem() instanceof BlockItem) && Equipment.attackDamage(stack) >= 2.0) {
            f.add("damage=" + String.format(Locale.ROOT, "%.1f", Equipment.attackDamage(stack)));
        }
        if (stack.getItem() instanceof ArmorItem a) {
            f.add("armor=" + a.getDefense());
        }
        return String.join(";", f);
    }

    /** Facts worth passing on (anything beyond "a plain block / a weapon"). */
    public static boolean special(String facts) {
        for (String tok : facts.split(";")) {
            String k = tok.split("=", 2)[0];
            if (!k.isEmpty() && !k.equals("spawn") && !k.equals("damage") && !k.equals("armor") && !k.equals("tool")
                    && !k.equals("needstool") && !k.equals("drops")) {
                return true;
            }
        }
        return false;
    }

    private static MutableComponent names(String csv) {
        MutableComponent out = Component.empty();
        boolean first = true;
        for (String id : csv.split(",")) {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
            if (item == null || item == Items.AIR) {
                continue;
            }
            if (!first) {
                out.append(", ");
            }
            first = false;
            out.append(new ItemStack(item).getHoverName());
        }
        return out;
    }

    /** Human readable introduction of an item from its fact tokens. */
    public static MutableComponent describe(String facts) {
        MutableComponent out = Component.empty();
        boolean first = true;
        for (String tok : facts.split(";")) {
            if (tok.isEmpty()) {
                continue;
            }
            String[] kv = tok.split("=", 2);
            String v = kv.length > 1 ? kv[1] : "";
            Component c = switch (kv[0]) {
                case "craft" -> Component.translatable("rlclones.fact.craft", names(v));
                case "then" -> Component.translatable("rlclones.fact.then", names(v));
                case "smelt" -> Component.translatable("rlclones.fact.smelt", names(v));
                case "fuel" -> Component.translatable("rlclones.fact.fuel", String.format(Locale.ROOT, "%.1f", Integer.parseInt(v) / 200.0));
                case "food" -> Component.translatable("rlclones.fact.food", v);
                case "foodfx" -> Component.translatable("rlclones.fact.foodfx");
                case "spawn" -> Component.translatable("rlclones.fact.spawn");
                case "nospawn" -> Component.translatable("rlclones.fact.nospawn");
                case "light" -> Component.translatable("rlclones.fact.light", v);
                case "gravity" -> Component.translatable("rlclones.fact.gravity");
                case "plant" -> Component.translatable("rlclones.fact.plant");
                case "harmful" -> Component.translatable("rlclones.fact.harmful");
                case "damage" -> Component.translatable("rlclones.fact.damage", v);
                case "armor" -> Component.translatable("rlclones.fact.armor", v);
                case "effect" -> effect(v);
                case "drops" -> v.equals("none") ? Component.translatable("rlclones.fact.drops_none") : Component.translatable("rlclones.fact.drops", names(v));
                case "tool" -> Component.translatable("rlclones.fact.tool", Component.translatable("rlclones.tool." + v));
                case "needstool" -> Component.translatable("rlclones.fact.needstool");
                default -> null;
            };
            if (c == null) {
                continue;
            }
            if (!first) {
                out.append(" / ");
            }
            first = false;
            out.append(c);
        }
        if (first) {
            out.append(Component.translatable("rlclones.fact.none"));
        }
        return out;
    }

    private static Component effect(String v) {
        String[] p = v.split("\\|");
        MobEffect e = ForgeRegistries.MOB_EFFECTS.getValue(new ResourceLocation(p[0]));
        if (e == null) {
            return null;
        }
        int amp = p.length > 1 ? Integer.parseInt(p[1]) : 0;
        int dur = p.length > 2 ? Integer.parseInt(p[2]) : 0;
        MutableComponent name = e.getDisplayName().copy();
        if (amp > 0) {
            name.append(" " + (amp + 1));
        }
        return e.isInstantenous() ? Component.translatable("rlclones.fact.effect_instant", name)
                : Component.translatable("rlclones.fact.effect", name, dur / 20);
    }

    /** Effects (id, amplifier) an item gives, from learned facts. */
    public static List<String[]> effects(String facts) {
        List<String[]> out = new ArrayList<>();
        for (String tok : facts.split(";")) {
            if (tok.startsWith("effect=")) {
                out.add(tok.substring(7).split("\\|"));
            }
        }
        return out;
    }

    // ================================================================== learning from the inventory

    /**
     * The clone broke a block: even if nothing (or something else) dropped, it now knows what the block is - what it
     * drops, which tool suits it, plus everything known about it as an item.
     */
    public void onBroken(BlockState state, BlockPos pos) {
        Brain b = brain();
        if (b == null || state.isAir()) {
            return;
        }
        Item item = state.getBlock().asItem();
        String key = item == Items.AIR ? "block:" + Perception.blockId(state) : keyOf(new ItemStack(item));
        if (b.facts(key).contains("drops=")) {
            return;
        }
        List<String> f = new ArrayList<>();
        if (item != Items.AIR) {
            String base = facts(self, new ItemStack(item));
            if (!base.isEmpty()) {
                f.add(base);
            }
        }
        List<ItemStack> drops = Block.getDrops(state, self.serverLevel(), pos, null, self, self.getMainHandItem());
        f.add("drops=" + (drops.isEmpty() ? "none" : ids(drops, 3)));
        if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE)) {
            f.add("tool=pickaxe");
        } else if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE)) {
            f.add("tool=axe");
        } else if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL)) {
            f.add("tool=shovel");
        } else if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_HOE)) {
            f.add("tool=hoe");
        }
        if (state.requiresCorrectToolForDrops()) {
            f.add("needstool");
        }
        String facts = String.join(";", f);
        b.learnItem(key, facts, b.obtained(key));
        b.learnBlock(Perception.blockId(state));
        discovered++;
        Chat.say(self, Component.translatable("rlclones.chat.discover_break", state.getBlock().getName(), describe(facts)), "DISCOVER " + key + " facts=" + facts);
    }

    /** Study items that are new to this clone (called every second). */
    public void watchInventory() {
        Brain b = brain();
        if (b == null) {
            return;
        }
        Map<String, ItemStack> current = new LinkedHashMap<>();
        for (ItemStack s : self.getInventory().items) {
            if (!s.isEmpty()) {
                current.putIfAbsent(keyOf(s), s);
            }
        }
        for (ItemStack s : self.getInventory().armor) {
            if (!s.isEmpty()) {
                current.putIfAbsent(keyOf(s), s);
            }
        }
        if (!self.getOffhandItem().isEmpty()) {
            current.putIfAbsent(keyOf(self.getOffhandItem()), self.getOffhandItem());
        }
        int announced = 0;
        for (Map.Entry<String, ItemStack> e : current.entrySet()) {
            if (b.obtained(e.getKey())) {
                continue;
            }
            ItemStack s = e.getValue();
            boolean known = b.knowsItem(e.getKey());
            String f = b.facts(e.getKey()).contains("drops=") ? b.facts(e.getKey()) : facts(self, s);
            b.learnItem(e.getKey(), f, true);
            if (known) {
                continue; // already studied when breaking it / told by others: just remember we have it now
            }
            if (s.getItem() instanceof BlockItem bi) {
                b.learnBlock(Perception.blockId(bi.getBlock().defaultBlockState()));
            }
            discovered++;
            if (announced++ < 3) {
                Chat.say(self, Component.translatable("rlclones.chat.discover", s.getHoverName(), describe(f)), "DISCOVER " + e.getKey() + " facts=" + f);
            }
        }
    }

    /** Tell clones nearby (in view) about special items they do not know; hand one over when there is a spare. */
    public void share(long now) {
        Brain mine = brain();
        if (mine == null) {
            return;
        }
        Map<String, List<ClonePlayer>> lacking = new LinkedHashMap<>();
        for (Perception.Seen s : perception.remembered()) {
            // seen in the last few seconds: still around even if we just looked away
            if (now - s.lastSeen > 200 || !(s.entity instanceof ClonePlayer other) || other == self || !other.isAlive() || other.distanceTo(self) > 16
                    || other.getCloneBrain() == null || other.getCloneBrain() == mine) {
                continue;
            }
            for (String key : mine.knownItems()) {
                if (!mine.obtained(key) || other.getCloneBrain().knowsItem(key) || !special(mine.facts(key))) {
                    continue;
                }
                String k = other.getGameProfile().getName() + "|" + key;
                Long at = toldAt.get(k);
                if (at != null && now - at < 1200) {
                    continue;
                }
                lacking.computeIfAbsent(key, x -> new ArrayList<>()).add(other);
            }
        }
        // our own children hear (and get) things first
        List<Map.Entry<String, List<ClonePlayer>>> order = new ArrayList<>(lacking.entrySet());
        for (List<ClonePlayer> l : lacking.values()) {
            l.sort(java.util.Comparator.comparingInt(o -> com.rlclones.clone.CloneManager.isParentOf(self, o) ? 0 : 1));
        }
        order.sort(java.util.Comparator.comparingInt(e -> com.rlclones.clone.CloneManager.isParentOf(self, e.getValue().get(0)) ? 0 : 1));
        int lines = 0;
        for (Map.Entry<String, List<ClonePlayer>> e : order) {
            if (lines++ >= 2) {
                break;
            }
            String key = e.getKey();
            List<String> names = new ArrayList<>();
            for (ClonePlayer o : e.getValue()) {
                names.add(o.getGameProfile().getName());
                toldAt.put(o.getGameProfile().getName() + "|" + key, now);
            }
            String facts = mine.facts(key);
            Chat.say(self, Component.translatable("rlclones.chat.info", String.join(", ", names), stackOf(key).getHoverName(), describe(facts)),
                    "INFO " + key + " to=" + String.join(",", names) + " facts=" + facts);
            told += names.size();
            for (ClonePlayer o : e.getValue()) {
                give(o, key);
            }
        }
    }

    /** Hand over one of an item if we have a spare and the other clone is close. */
    private void give(ClonePlayer other, String key) {
        if (other.distanceTo(self) > 5) {
            return;
        }
        var inv = self.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && keyOf(s).equals(key) && s.getCount() >= 2) {
                Vec3 to = other.getEyePosition().subtract(self.getEyePosition());
                float yaw = (float) Math.toDegrees(Mth.atan2(to.z, to.x)) - 90.0F;
                self.setYRot(yaw);
                self.setYHeadRot(yaw);
                self.setXRot(10f);
                self.drop(s.split(1), false, true);
                given++;
                return;
            }
        }
    }

    /** Read "INFO key to=a,b facts=..." from chat. Returns true if it was such a line. */
    public boolean onChat(String text) {
        if (!text.startsWith("INFO ")) {
            return false;
        }
        int to = text.indexOf(" to=");
        int fa = text.indexOf(" facts=");
        if (to < 0 || fa < to) {
            return true;
        }
        String key = text.substring(5, to).trim();
        List<String> names = List.of(text.substring(to + 4, fa).split(","));
        Brain b = brain();
        if (b != null && names.contains(self.getGameProfile().getName()) && !b.knowsItem(key)) {
            b.learnItem(key, text.substring(fa + 7), false);
            ItemStack s = stackOf(key);
            if (s.getItem() instanceof BlockItem bi) {
                b.learnBlock(Perception.blockId(bi.getBlock().defaultBlockState()));
            }
            heard++;
        }
        return true;
    }

    public int heard;

    // ================================================================== curiosity: go and get unknown blocks

    /** A block this clone has never examined and could take home (has an item, breakable with what we carry). */
    public boolean isUnknownObtainable(BlockState st, BlockPos pos) {
        Brain b = brain();
        if (b == null || st.isAir() || st.getBlock() instanceof LiquidBlock || !Config.get(Config.ALLOW_BLOCK_BREAKING, true)) {
            return false;
        }
        Block block = st.getBlock();
        Item item = block.asItem();
        if (item == Items.AIR || block instanceof EntityBlock || block instanceof CropBlock || block instanceof StemBlock
                || block instanceof DoorBlock || block instanceof BedBlock) {
            return false;
        }
        float hardness = st.getDestroySpeed(self.level(), pos);
        if (hardness < 0 || hardness > 10) {
            return false;
        }
        String id = Perception.blockId(st);
        if (b.knowsBlock(id) || b.isHarmful(id) || b.knowsItem(keyOf(new ItemStack(item)))) {
            return false;
        }
        if (st.requiresCorrectToolForDrops() && !Equipment.canHarvest(self, st)) {
            return false;
        }
        return Bases.get(self.getServer()).nearest(self.level().dimension(), Vec3.atCenterOf(pos), 8) == null;
    }

    @Nullable
    private BlockPos nearestUnknown() {
        BlockPos best = null;
        double bestD = 16 * 16;
        for (Map.Entry<BlockPos, Perception.BlockKind> e : perception.blocks().entrySet()) {
            if (e.getValue() != Perception.BlockKind.UNKNOWN) {
                continue;
            }
            if (e.getKey().getY() > self.getBlockY() + 3) {
                continue; // up a cliff / over a wall: out of reach without climbing
            }
            double d = e.getKey().distToCenterSqr(self.position());
            if (d < bestD && isUnknownObtainable(self.level().getBlockState(e.getKey()), e.getKey())) {
                bestD = d;
                best = e.getKey();
            }
        }
        return best;
    }

    /** State for diagnostics. */
    public String debug() {
        return "target=" + target + " id=" + targetId + " ticks=" + ticks + " collect=" + collect + " examined=" + blocksExamined
                + " unknownSeen=" + perception.blocks().entrySet().stream().filter(e -> e.getValue() == Perception.BlockKind.UNKNOWN)
                .map(e -> e.getKey().toShortString()).toList();
    }

    public boolean canDiscover() {
        return nearestUnknown() != null;
    }

    public void begin() {
        target = null;
        ticks = 0;
        collect = 0;
        motor.resetStuck();
    }

    /** Mine one unknown block and pick it up. Returns true when finished. */
    public boolean tick() {
        if (++ticks > 600) {
            return true;
        }
        if (collect > 0) {
            // walk over what dropped (it may have bounced off a little)
            collect--;
            var drops = self.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(target).inflate(4), e -> e.isAlive());
            Vec3 to = Vec3.atBottomCenterOf(target);
            double best = Double.MAX_VALUE;
            for (var it : drops) {
                double d = it.distanceToSqr(self);
                if (d < best) {
                    best = d;
                    to = it.position();
                }
            }
            motor.navigate(to, 0.2, false);
            if (Motor.horizontalDistance(self.position(), to) < 1.5) {
                motor.moveToward(to);
            }
            return collect == 0 || drops.isEmpty() && collect < 50;
        }
        if (target == null) {
            target = nearestUnknown();
            if (target == null) {
                return true;
            }
            targetId = Perception.blockId(self.level().getBlockState(target));
        }
        if (ticks % 20 == 0) {
            // something new and closer turned up on the way: that first
            BlockPos nearer = nearestUnknown();
            if (nearer != null && nearer.distToCenterSqr(self.position()) + 4 < target.distToCenterSqr(self.position())) {
                target = nearer;
                targetId = Perception.blockId(self.level().getBlockState(target));
                motor.resetStuck();
            }
        }
        BlockState st = self.level().getBlockState(target);
        if (!isUnknownObtainable(st, target)) {
            perception.forgetBlock(target);
            target = null;
            return false;
        }
        if (self.getEyePosition().distanceTo(Vec3.atCenterOf(target)) > Motor.BLOCK_REACH - 0.5) {
            motor.navigate(motor.approachPoint(target), 1.5, false);
            if (motor.stuckCount() > 6) {
                perception.forgetBlock(target);
                target = null;
            }
            return false;
        }
        if (self.controller() != null && !self.controller().safeToDig(target)) {
            perception.forgetBlock(target);
            target = null;
            return false;
        }
        motor.stop();
        Equipment.select(self, Equipment.bestToolSlot(self, st));
        if (motor.mine(target)) {
            Brain b = brain();
            if (b != null) {
                b.learnBlock(targetId);
            }
            blocksExamined++;
            perception.forgetBlock(target);
            collect = 60;
        }
        return false;
    }
}
