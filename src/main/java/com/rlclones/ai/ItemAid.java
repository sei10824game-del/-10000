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
    /** R-27: the friend who tossed us the better pickaxe (the one we had before goes back to them). */
    @Nullable
    private UUID toolFrom;
    private long toolFromAt;
    public int returned;
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
                asks.removeIf(a -> a.from.equals(sender.getUUID()) && a.item.equals(p[1]));
                asks.add(new Ask(sender.getUUID(), sender.getGameProfile().getName(), p[1], n, now));
                while (asks.size() > 8) {
                    asks.remove(0);
                }
            }
            return true;
        }
        if (text.startsWith("DIGGING ") && p.length >= 2 && sender != self && !self.isCreative()) {
            offerPickaxe(sender, p[1], now);
            return true;
        }
        if (text.startsWith("GIVE ")) {
            if (p.length >= 3) {
                if (p[1].equals(self.getGameProfile().getName())) {
                    coming.put(p[2], now);
                }
                asks.removeIf(a -> a.name.equals(p[1]) && a.item.equals(p[2])); // somebody else is bringing it already
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ digging

    public static int bestPickTier(Player p) {
        int t = -1;
        for (ItemStack s : p.getInventory().items) {
            if (s.getItem() instanceof PickaxeItem pi) {
                t = Math.max(t, pi.getTier().getLevel());
            }
        }
        return t;
    }

    /** Say what we dig with, so a friend with a better pickaxe can bring it (every 600 ticks while on the stairs / shaft). */
    public void announceDigging() {
        Chat.say(self, Component.literal("DIGGING"), "DIGGING " + bestPickTier(self));
        returnOldTool(); // R-27: the old pickaxe also goes back on this beat
    }

    /** R-27: a digger within 16 blocks digs with a worse pickaxe than our best, and we are not digging ourselves: bring ours. */
    private void offerPickaxe(ServerPlayer digger, String tier, long now) {
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
        for (ItemStack s : self.getInventory().items) {
            if (s.getItem() instanceof PickaxeItem pi && pi.getTier().getLevel() > theirs
                    && (best == null || pi.getTier().getLevel() > ((PickaxeItem) best).getTier().getLevel())) {
                best = pi;
            }
        }
        if (best != null) {
            Ask a = new Ask(digger.getUUID(), digger.getGameProfile().getName(), key(best), 1, now);
            asks.removeIf(x -> x.from.equals(a.from) && x.item.equals(a.item));
            asks.add(a);
            offered.add(a);
        }
    }

    /** R-27: a pickaxe a friend tossed us (remember them: the one we were using goes back). */
    public void onGift(UUID from, ItemStack stack) {
        if (stack.getItem() instanceof PickaxeItem) {
            toolFrom = from;
            toolFromAt = now();
        }
    }

    /** R-27 (the way back): toss the pickaxe we dug with before to whoever brought us the better one. */
    public void returnOldTool() {
        UUID from = toolFrom;
        if (from == null) {
            return;
        }
        if (now() - toolFromAt > 1200) {
            toolFrom = null; // too long ago: the friend has moved on, we keep the old one
            return;
        }
        Player friend = online(from);
        if (friend == null || friend.distanceTo(self) > 16) {
            return; // not right now: try again later
        }
        int best = bestPickTier(self);
        int old = -1;
        int oldTier = Integer.MAX_VALUE;
        var inv = self.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.getItem() instanceof PickaxeItem pi && pi.getTier().getLevel() < best && pi.getTier().getLevel() < oldTier) {
                oldTier = pi.getTier().getLevel();
                old = i;
            }
        }
        if (old < 0) {
            return; // nothing older left (we have only the better one)
        }
        toolFrom = null;
        tossTo(friend, inv.items.get(old).split(1));
        returned++;
        debug = "gave back a pickaxe to " + friend.getGameProfile().getName();
    }

    /** Toss one stack to a friend, aimed so it lands at their feet (only they may pick it up). */
    private void tossTo(Player friend, ItemStack stack) {
        Vec3 eye = self.getEyePosition();
        Vec3 to = friend.position().add(0, 0.5, 0).subtract(eye);
        float yaw = (float) Math.toDegrees(Mth.atan2(to.z, to.x)) - 90.0F;
        self.setYRot(yaw);
        self.setYHeadRot(yaw);
        self.setXRot(30f);
        ItemEntity e = self.drop(stack, false, true);
        if (e == null) {
            return;
        }
        double d = to.length();
        if (d > 0.05) {
            double speed = Mth.clamp(0.12 + d * 0.05, 0.15, 0.55);
            e.setPos(eye.x, eye.y - 0.3, eye.z);
            e.setDeltaMovement(to.normalize().scale(speed).add(0, 0.02 * (d / speed), 0));
        }
        gift(e, friend.getUUID());
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
        asks.removeIf(a -> now - a.tick > 2400);
        Ask best = null;
        double bestD = Double.MAX_VALUE;
        for (Ask a : asks) {
            Player p = online(a.from);
            if (p == null || p.distanceTo(self) > 64 || has(p, a.item) || spare(self, a.item) <= 0 && !offered.contains(a)) {
                continue;
            }
            double d = p.distanceTo(self);
            if (d < bestD) {
                bestD = d;
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
            asks.remove(a);
            return Status.FAILED;
        }
        if (p.distanceTo(self) > 2.5) {
            motor.navigate(p.position(), 1.5, p.distanceTo(self) > 10);
            if (motor.stuckCount() > 8) {
                asks.remove(a);
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
            tossTo(p, inv.items.get(worst).split(1));
        } else {
            for (int i = 0; i < inv.items.size() && give > 0; i++) {
                ItemStack s = inv.items.get(i);
                if (matches(s, a.item)) {
                    int n = Math.min(give, s.getCount());
                    tossTo(p, s.split(n));
                    give -= n;
                }
            }
        }
        given++;
        debug = "gave " + a.item + " to " + a.name;
        if (p instanceof ClonePlayer other && other.controller() != null) {
            other.controller().foodAid().thank(self.getUUID(), 2f); // they know who brought it
        }
        asks.remove(a);
        offered.remove(a);
        helping = null;
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
