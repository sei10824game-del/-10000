package com.rlclones.ai;

import com.rlclones.clone.ClonePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Things between friends. A clone that lacks the item it needs for what it is about to do (a pickaxe for the mining,
 * a hoe for the field, torches for a dark plot...) asks in chat; a clone that has more of it than it needs says it is
 * bringing it, walks over and tosses it. Runs inside the FEED option (bringing a friend what it is short of).
 */
public final class ItemAid {
    public enum Status {WORKING, DONE, FAILED}

    public record Ask(UUID from, String name, String item, int count, long tick) {
    }

    private static final List<TagKey<Item>> SAME_USE = List.of(ItemTags.LOGS, ItemTags.PLANKS, ItemTags.COALS, ItemTags.STONE_CRAFTING_MATERIALS);

    private final ClonePlayer self;
    private final Motor motor;
    private final List<Ask> asks = new ArrayList<>();
    /** Item asked for -> when (one ask a minute per item). */
    private final Map<String, Long> lastAsked = new HashMap<>();
    /** Item asked for -> when a friend said it is bringing it. */
    private final Map<String, Long> coming = new HashMap<>();
    /** R-27: asks that come from a digger's "DIGGING tier" line and our better pickaxe: the only one we have may go. */
    private final java.util.Set<Ask> offered = new java.util.HashSet<>();
    /** Requests created by a RETURN line: fulfillment is stronger evidence of reciprocity than an ordinary gift. */
    private final java.util.Set<Ask> returnRequests = new java.util.HashSet<>();
    /** Old tool reported by the digger, to be returned after the offered pickaxe is delivered. */
    private final Map<Ask, String> returnItems = new HashMap<>();
    /** Partner UUID -> deadline for a requested tool return; a missed promise is negative social evidence. */
    private final Map<UUID, Long> expectedReturns = new HashMap<>();
    @Nullable
    private Ask helping;
    private int ticks;

    public int asked;
    public int given;
    public String debug = "";

    public ItemAid(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    public static String key(Item item) {
        ResourceLocation k = ForgeRegistries.ITEMS.getKey(item);
        return k == null ? "minecraft:air" : k.toString();
    }

    private long now() {
        return self.level().getGameTime();
    }

    /** Called periodically by the controller so broken tool-return promises can lower learned trust. */
    public void tick(long now) {
        Iterator<Map.Entry<UUID, Long>> it = expectedReturns.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> pending = it.next();
            if (now >= pending.getValue()) {
                if (self.getCloneBrain() != null) {
                    self.getCloneBrain().learnSocial(pending.getKey(), -0.75f, "item_not_returned", now);
                }
                it.remove();
            }
        }
    }

    public void noteReturnedBy(UUID partner) {
        expectedReturns.remove(partner);
    }

    // ------------------------------------------------------------------ asking

    /** Ask the others for {@code item} (needed for what we are about to do). At most once a minute per item. */
    public boolean need(Item item, int count) {
        long now = now();
        String k = key(item);
        Long last = lastAsked.get(k);
        if (self.isCreative() || last != null && now - last < 1200) {
            return false;
        }
        lastAsked.put(k, now);
        asked++;
        int x = Mth.floor(self.getX());
        int y = Mth.floor(self.getY());
        int z = Mth.floor(self.getZ());
        debug = "asked " + k;
        Chat.say(self, Component.translatable("rlclones.chat.need", new ItemStack(item).getHoverName(), count), "NEED " + k + " " + count + " " + x + " " + y + " " + z);
        return true;
    }

    /** A friend said it is on its way with {@code item}. */
    public boolean helpComing(Item item) {
        Long t = coming.get(key(item));
        return t != null && now() - t < 900;
    }

    /** Something we asked for lies on the ground close by (tossed to us): go and pick it up. */
    public boolean deliveryNear() {
        long now = now();
        for (ItemEntity e : self.level().getEntitiesOfClass(ItemEntity.class, self.getBoundingBox().inflate(6))) {
            for (Map.Entry<String, Long> a : lastAsked.entrySet()) {
                if (now - a.getValue() < 2400 && matches(e.getItem(), a.getKey())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** "NEED item n x y z" / "GIVE name item". Returns true if it was such a line. */
    public boolean onChat(ServerPlayer sender, String text, long now) {
        String[] p = text.split(" ");
        if (text.startsWith("NEED ")) {
            if (p.length >= 3 && sender != self) {
                int n = 1;
                try {
                    n = Math.max(1, Math.min(64, Integer.parseInt(p[2])));
                } catch (NumberFormatException ignored) {
                }
                removeAsks(a -> a.from.equals(sender.getUUID()) && a.item.equals(p[1]));
                asks.add(new Ask(sender.getUUID(), sender.getGameProfile().getName(), p[1], n, now));
                while (asks.size() > 8) {
                    Ask dropped = asks.remove(0);
                    offered.remove(dropped);
                    returnItems.remove(dropped);
                }
            }
            return true;
        }
        if (text.startsWith("DIGGING ") && p.length >= 2 && sender != self && !self.isCreative()) {
            offerPickaxe(sender, p[1], p.length >= 3 ? p[2] : "", now);
            return true;
        }
        if (text.startsWith("RETURN ")) {
            if (p.length >= 3 && p[1].equals(self.getGameProfile().getName()) && sender != self && !self.isCreative()) {
                try {
                    Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(p[2]));
                    if (item instanceof PickaxeItem) {
                        removeAsks(a -> a.from.equals(sender.getUUID()) && a.item.equals(p[2]));
                        Ask a = new Ask(sender.getUUID(), sender.getGameProfile().getName(), p[2], 1, now);
                        asks.add(a);
                        offered.add(a); // the old tool is not surplus to the helper; it is the actual requested return
                        returnRequests.add(a);
                    }
                } catch (RuntimeException ignored) {
                    // malformed item ids do not enter the request queue
                }
            }
            return true;
        }
        if (text.startsWith("GIVE ")) {
            if (p.length >= 3) {
                if (p[1].equals(self.getGameProfile().getName())) {
                    coming.put(p[2], now);
                }
                removeAsks(a -> a.name.equals(p[1]) && a.item.equals(p[2])); // somebody else is bringing it already
            }
            return true;
        }
        return false;
    }

    private void removeAsks(java.util.function.Predicate<Ask> predicate) {
        var it = asks.iterator();
        while (it.hasNext()) {
            Ask ask = it.next();
            if (predicate.test(ask)) {
                it.remove();
                offered.remove(ask);
                returnRequests.remove(ask);
                returnItems.remove(ask);
                if (ask.equals(helping)) {
                    helping = null;
                }
            }
        }
    }

    // ------------------------------------------------------------------ digging

    private static Item bestPickItem(Player p) {
        Item best = null;
        for (ItemStack stack : p.getInventory().items) {
            if (stack.getItem() instanceof PickaxeItem pick
                    && (best == null || pick.getTier().getLevel() > ((PickaxeItem) best).getTier().getLevel())) {
                best = stack.getItem();
            }
        }
        return best;
    }

    public static int bestPickTier(Player p) {
        Item best = bestPickItem(p);
        return best instanceof PickaxeItem pick ? pick.getTier().getLevel() : -1;
    }

    /** Say what we dig with, so a friend with a better pickaxe can bring it (every 600 ticks while on the stairs / shaft). */
    public void announceDigging() {
        Item best = bestPickItem(self);
        String line = "DIGGING " + bestPickTier(self) + (best == null ? "" : " " + key(best));
        Chat.say(self, Component.literal("DIGGING"), line);
    }

    /** R-27: a digger within 16 blocks digs with a worse pickaxe than our best, and we are not digging ourselves: bring ours. */
    private void offerPickaxe(ServerPlayer digger, String tier, String oldId, long now) {
        var ctl = self.controller();
        var o = ctl == null ? null : ctl.option();
        if (o == com.rlclones.ai.strategy.Option.STAIRS || o == com.rlclones.ai.strategy.Option.SHAFT || o == com.rlclones.ai.strategy.Option.MINE
                || o == com.rlclones.ai.strategy.Option.QUARRY || digger.distanceTo(self) > 16) {
            return;
        }
        int theirs;
        try {
            theirs = Integer.parseInt(tier);
        } catch (NumberFormatException e) {
            return;
        }
        Item best = null;
        for (ItemStack stack : self.getInventory().items) {
            if (stack.getItem() instanceof PickaxeItem pick && pick.getTier().getLevel() > theirs
                    && (best == null || pick.getTier().getLevel() > ((PickaxeItem) best).getTier().getLevel())) {
                best = stack.getItem();
            }
        }
        if (best == null) {
            return;
        }
        Item old = null;
        if (!oldId.isBlank()) {
            try {
                Item reported = ForgeRegistries.ITEMS.getValue(new ResourceLocation(oldId));
                if (reported instanceof PickaxeItem pick && pick.getTier().getLevel() == theirs && hasExact(digger, reported)) {
                    old = reported;
                }
            } catch (RuntimeException ignored) {
                // old clients only report the tier; use the inventory fallback below
            }
        }
        if (old == null) {
            for (ItemStack stack : digger.getInventory().items) {
                if (stack.getItem() instanceof PickaxeItem pick && pick.getTier().getLevel() == theirs) {
                    old = stack.getItem();
                    break;
                }
            }
        }
        removeAsks(a -> a.from.equals(digger.getUUID()) && offered.contains(a));
        Ask a = new Ask(digger.getUUID(), digger.getGameProfile().getName(), key(best), 1, now);
        asks.add(a);
        offered.add(a); // the best pickaxe may be the only one the helper owns
        if (old != null) {
            returnItems.put(a, key(old));
        }
    }

    private static boolean hasExact(Player player, Item item) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ giving

    private static boolean sameToolClass(Item a, Item b) {
        return a instanceof PickaxeItem && b instanceof PickaxeItem || a instanceof HoeItem && b instanceof HoeItem
                || a instanceof AxeItem && b instanceof AxeItem || a instanceof ShovelItem && b instanceof ShovelItem
                || a instanceof SwordItem && b instanceof SwordItem;
    }

    /** Would {@code have} do for what was asked? A pickaxe at least as good does for a pickaxe, any log for a log... */
    public static boolean matches(ItemStack have, String want) {
        if (have.isEmpty()) {
            return false;
        }
        Item w = ForgeRegistries.ITEMS.getValue(new ResourceLocation(want));
        if (w == null || w == Items.AIR) {
            return false;
        }
        if (have.is(w)) {
            return true;
        }
        if (w instanceof TieredItem wt && have.getItem() instanceof TieredItem ht && sameToolClass(w, have.getItem())) {
            return ht.getTier().getLevel() >= wt.getTier().getLevel();
        }
        ItemStack ws = new ItemStack(w);
        for (TagKey<Item> tag : SAME_USE) {
            if (ws.is(tag) && have.is(tag)) {
                return true;
            }
        }
        return false;
    }

    /** How many of {@code want} this player can spare: tools beyond the one it keeps, materials beyond a reserve. */
    public static int spare(Player p, String want) {
        int n = 0;
        boolean tool = false;
        for (ItemStack s : p.getInventory().items) {
            if (matches(s, want)) {
                n += s.getCount();
                tool |= s.getMaxStackSize() == 1;
            }
        }
        return Math.max(0, n - (tool ? 1 : 8));
    }

    @Nullable
    private Player online(UUID id) {
        if (self.getServer() == null) {
            return null;
        }
        Player p = self.getServer().getPlayerList().getPlayer(id);
        return p != null && p.isAlive() && p.level() == self.level() ? p : null;
    }

    private static boolean has(Player p, String want) {
        for (ItemStack s : p.getInventory().items) {
            if (matches(s, want)) {
                return true;
            }
        }
        return false;
    }

    /** The nearest ask we can answer (the asker still without it, we with some to spare). */
    @Nullable
    private Ask pick() {
        long now = now();
        removeAsks(a -> now - a.tick > 2400);
        Ask best = null;
        double bestD = Double.MAX_VALUE;
        for (Ask a : asks) {
            Player p = online(a.from);
            if (p == null || p.distanceTo(self) > 64 || has(p, a.item) || spare(self, a.item) <= 0 && !offered.contains(a)) {
                continue;
            }
            double d = p.distanceTo(self);
            float trust = self.getCloneBrain() == null ? 0f : self.getCloneBrain().socialTrust(a.from, now);
            float balance = self.getCloneBrain() == null ? 0f : self.getCloneBrain().partnerBalance(a.from, now);
            double score = d - trust * 12.0 - Math.tanh(balance / 8.0) * 7.0;
            if (score < bestD) {
                bestD = score;
                best = a;
            }
        }
        return best;
    }

    public boolean canHelp() {
        return !self.isCreative() && !asks.isEmpty() && pick() != null;
    }

    public void begin() {
        helping = pick();
        ticks = 0;
        motor.resetStuck();
        if (helping != null) {
            Item it = ForgeRegistries.ITEMS.getValue(new ResourceLocation(helping.item));
            Chat.say(self, Component.translatable("rlclones.chat.give", helping.name, it == null ? Component.literal(helping.item) : new ItemStack(it).getHoverName()),
                    "GIVE " + helping.name + " " + helping.item);
        }
    }

    /** Walk over to whoever asked and toss it to them (the worst of our tools that will do, or what we can spare). */
    public Status helpTick() {
        Ask a = helping;
        Player p = a == null ? null : online(a.from);
        if (p == null || ++ticks > 1800 || has(p, a.item)) {
            removeAsks(x -> x.equals(a));
            helping = null;
            return Status.FAILED;
        }
        if (p.distanceTo(self) > 2.5) {
            motor.navigate(p.position(), 1.5, p.distanceTo(self) > 10);
            if (motor.stuckCount() > 8) {
                removeAsks(x -> x.equals(a));
                helping = null;
                return Status.FAILED;
            }
            return Status.WORKING;
        }
        motor.stop();
        Vec3 to = p.position().subtract(self.getEyePosition());
        float yaw = (float) Math.toDegrees(Mth.atan2(to.z, to.x)) - 90.0F;
        self.setYRot(yaw);
        self.setYHeadRot(yaw);
        self.setXRot(30f);
        int give = Math.min(a.count, Math.max(spare(self, a.item), offered.contains(a) ? 1 : 0));
        var inv = self.getInventory();
        // tools: hand over the worst one that will do and keep the best
        int worst = -1;
        int worstTier = Integer.MAX_VALUE;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.getMaxStackSize() == 1 && matches(s, a.item)) {
                int tier = s.getItem() instanceof TieredItem t ? t.getTier().getLevel() : 0;
                if (tier < worstTier) {
                    worstTier = tier;
                    worst = i;
                }
            }
        }
        if (worst >= 0) {
            gift(self.drop(inv.items.get(worst).split(1), false, true), a.from);
        } else {
            for (int i = 0; i < inv.items.size() && give > 0; i++) {
                ItemStack s = inv.items.get(i);
                if (matches(s, a.item)) {
                    int n = Math.min(give, s.getCount());
                    gift(self.drop(s.split(n), false, true), a.from);
                    give -= n;
                }
            }
        }
        boolean returnedItem = returnRequests.remove(a);
        given++;
        debug = "gave " + a.item + " to " + a.name;
        if (p instanceof ClonePlayer other && other.controller() != null) {
            other.controller().foodAid().thank(self.getUUID(), 2f, returnedItem ? "item_returned" : "item_shared");
            if (self.getCloneBrain() != null) {
                if (returnedItem) {
                    self.getCloneBrain().recordContribution(other.getUUID(), 2f, "item_returned", self.level().getGameTime());
                } else {
                    self.getCloneBrain().recordWithdrawal(other.getUUID(), 2f, "item_given", self.level().getGameTime());
                }
            }
        }
        String returnKey = returnItems.remove(a);
        asks.remove(a);
        offered.remove(a);
        helping = null;
        if (returnKey != null) {
            expectedReturns.put(a.from, now() + 2400);
            Chat.say(self, Component.literal("Please return the old pickaxe"), "RETURN " + a.name + " " + returnKey);
        }
        return Status.DONE;
    }

    /** Items tossed to a friend (entity id -> who it is for): nobody else picks them up (the giver least of all). */
    private static final Map<Integer, UUID> GIFTS = new HashMap<>();

    private static void gift(@Nullable ItemEntity e, UUID to) {
        if (e != null) {
            e.setTarget(to);
            if (GIFTS.size() > 256) {
                GIFTS.clear();
            }
            GIFTS.put(e.getId(), to);
        }
    }

    /** Is this item on the ground meant for somebody else? */
    public static boolean giftForOther(net.minecraft.world.entity.Entity e, Player p) {
        UUID to = GIFTS.get(e.getId());
        return to != null && !to.equals(p.getUUID());
    }

    public String state() {
        return "asks=" + asks.size() + " asked=" + lastAsked.keySet() + " coming=" + coming.keySet() + " " + debug;
    }
}
