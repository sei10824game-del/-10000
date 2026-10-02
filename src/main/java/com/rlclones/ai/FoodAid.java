package com.rlclones.ai;

import com.rlclones.clone.CloneManager;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Food between friends. Starving with nothing to eat, a clone asks in chat ("食べ物をください！"); a clone with food
 * to spare walks over and tosses some (its own children first). Whoever helps is remembered - gratitude makes a
 * clone follow its helpers more often.
 */
public final class FoodAid {
    public enum Status {WORKING, DONE, FAILED}

    public record Ask(UUID from, String name, Vec3 pos, long tick) {
    }

    private static final Pattern COORDS = Pattern.compile("(-?\\d+) (-?\\d+) (-?\\d+)");
    private static final String[] FOOD_WORDS = {"食べ物", "たべもの", "ごはん", "ご飯", "おなかすいた", "お腹すいた", "腹減", "food please", "need food", "hungry"};
    /** Food kept for ourselves; only what goes beyond it is given away. */
    public static final int RESERVE = 4;

    private final ClonePlayer self;
    private final Motor motor;
    private final List<Ask> asks = new ArrayList<>();
    private final Map<UUID, Float> gratitude = new HashMap<>();
    @Nullable
    private Ask helping;
    private long lastAsk = -100000;
    private int ticks;

    public int asked;
    public int given;
    @Nullable
    public UUID lastHelped;
    public String debug = "";

    public FoodAid(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    /** Real food: not rotten flesh, spider eyes, poisonous potatoes, pufferfish... (nothing with a harmful effect). */
    public static boolean goodFood(ItemStack s) {
        if (s.isEmpty() || !s.isEdible()) {
            return false;
        }
        FoodProperties fp = s.getFoodProperties(null);
        if (fp == null) {
            return false;
        }
        for (var e : fp.getEffects()) {
            if (!e.getFirst().getEffect().isBeneficial()) {
                return false;
            }
        }
        return !s.is(Items.CHORUS_FRUIT);
    }

    public static int foodItems(Player p) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) {
            if (goodFood(s)) {
                n += s.getCount();
            }
        }
        if (goodFood(p.getOffhandItem())) {
            n += p.getOffhandItem().getCount();
        }
        return n;
    }

    public int surplus() {
        return foodItems(self) - RESERVE;
    }

    // ------------------------------------------------------------------ asking

    /** Every second: starving with no food at all - ask the others (at most once a minute). */
    public void tick(long now) {
        if (self.getFoodData().getFoodLevel() <= 6 && foodItems(self) == 0 && !Senses.hasFood(self) && now - lastAsk > 1200 && !self.isCreative()) {
            lastAsk = now;
            asked++;
            int x = Mth.floor(self.getX());
            int y = Mth.floor(self.getY());
            int z = Mth.floor(self.getZ());
            Chat.say(self, Component.translatable("rlclones.chat.food", x, y, z), "FOOD " + x + " " + y + " " + z);
        }
        asks.removeIf(a -> now - a.tick > 2400);
    }

    /** Somebody asks for food ("FOOD x y z" from a clone, "食べ物ちょうだい" from a player). Returns true if it was such a line. */
    public boolean onChat(ServerPlayer sender, String text, long now) {
        String lower = text.toLowerCase(Locale.ROOT);
        boolean ask = text.startsWith("FOOD ");
        if (!ask) {
            for (String w : FOOD_WORDS) {
                if (lower.contains(w)) {
                    ask = true;
                    break;
                }
            }
        }
        if (!ask || sender == self) {
            return false;
        }
        Vec3 pos = sender.position();
        Matcher m = COORDS.matcher(text);
        if (m.find()) {
            pos = new Vec3(Integer.parseInt(m.group(1)) + 0.5, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)) + 0.5);
        }
        asks.removeIf(a -> a.from.equals(sender.getUUID()));
        asks.add(new Ask(sender.getUUID(), sender.getGameProfile().getName(), pos, now));
        return true;
    }

    // ------------------------------------------------------------------ giving

    @Nullable
    private Player online(UUID id) {
        if (self.getServer() == null) {
            return null;
        }
        Player p = self.getServer().getPlayerList().getPlayer(id);
        return p != null && p.isAlive() && p.level() == self.level() ? p : null;
    }

    /** The request to answer: our own children first, then whoever is nearest. */
    @Nullable
    private Ask pick() {
        Ask best = null;
        double bestScore = Double.MAX_VALUE;
        for (Ask a : asks) {
            Player p = online(a.from);
            // still without food in the bag and not full: a bite somebody else gave does not end the request
            if (p == null || p.distanceTo(self) > 64 || foodItems(p) >= 2 || p.getFoodData().getFoodLevel() >= 17) {
                continue;
            }
            double score = p.distanceTo(self) - (CloneManager.isParentOf(self, p) ? 1000 : 0);
            if (score < bestScore) {
                bestScore = score;
                best = a;
            }
        }
        return best;
    }

    /** Diagnostics. */
    public String state() {
        StringBuilder sb = new StringBuilder("asks=" + asks.size() + " surplus=" + surplus() + " food=" + self.getFoodData().getFoodLevel());
        for (Ask a : asks) {
            Player p = online(a.from);
            sb.append(' ').append(a.name).append(p == null ? ":offline" : ":d" + (int) p.distanceTo(self) + "/f" + p.getFoodData().getFoodLevel()
                    + "/i" + foodItems(p));
        }
        return sb.toString();
    }

    public boolean canHelp() {
        return surplus() >= 2 && self.getFoodData().getFoodLevel() > 6 && pick() != null;
    }

    public void begin() {
        helping = pick();
        ticks = 0;
        motor.resetStuck();
    }

    /** Walk over to whoever asked and toss them food (half of what we can spare, at least two). */
    public Status helpTick() {
        Ask a = helping;
        Player p = a == null ? null : online(a.from);
        if (p == null || ++ticks > 1800) {
            asks.remove(a);
            return Status.FAILED;
        }
        if (p.distanceTo(self) > 3.0) {
            motor.navigate(p.position(), 2.0, p.distanceTo(self) > 10);
            if (motor.stuckCount() > 8) {
                asks.remove(a); // cannot get there: somebody else will have to
                return Status.FAILED;
            }
            return Status.WORKING;
        }
        motor.stop();
        Vec3 to = p.getEyePosition().subtract(self.getEyePosition());
        float yaw = (float) Math.toDegrees(Mth.atan2(to.z, to.x)) - 90.0F;
        self.setYRot(yaw);
        self.setYHeadRot(yaw);
        self.setXRot(15f);
        int give = Math.max(2, surplus() / 2);
        var inv = self.getInventory();
        for (int i = 0; i < inv.items.size() && give > 0; i++) {
            ItemStack s = inv.items.get(i);
            if (goodFood(s)) {
                int n = Math.min(give, s.getCount());
                self.drop(s.split(n), false, true);
                give -= n;
            }
        }
        given++;
        lastHelped = a.from;
        debug = "fed " + a.name;
        if (p instanceof ClonePlayer other && other.controller() != null) {
            other.controller().foodAid().thank(self.getUUID(), 3f); // they know who brought it
        }
        asks.remove(a);
        helping = null;
        return Status.DONE;
    }

    // ------------------------------------------------------------------ gratitude

    public void thank(UUID helper, float amount) {
        if (!helper.equals(self.getUUID())) {
            gratitude.merge(helper, amount, Float::sum);
        }
    }

    public float gratitude(UUID who) {
        return gratitude.getOrDefault(who, 0f);
    }

    /** The helper we owe most who is around (same dimension, within {@code radius}); null if nobody helped. */
    @Nullable
    public Player favourite(double radius) {
        Player best = null;
        float bestG = 0;
        for (Map.Entry<UUID, Float> e : gratitude.entrySet()) {
            Player p = online(e.getKey());
            if (p != null && p.distanceTo(self) < radius && e.getValue() > bestG && !p.isSpectator()) {
                bestG = e.getValue();
                best = p;
            }
        }
        return best;
    }

    public Map<UUID, Float> gratitudeView() {
        return java.util.Collections.unmodifiableMap(gratitude);
    }

    /** Forget requests of whoever has eaten since (keeps the list short). */
    public void prune() {
        for (Iterator<Ask> it = asks.iterator(); it.hasNext(); ) {
            Player p = online(it.next().from);
            if (p == null) {
                it.remove();
            }
        }
    }
}
