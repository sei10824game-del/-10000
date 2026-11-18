package com.rlclones.ai.civilization;

import com.rlclones.ai.*;
import com.rlclones.clone.CloneManager;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;
import java.util.function.Predicate;

/** Delivery jobs use ordinary movement, shared chest menus and targeted item drops. */
public final class CivicWork {
    private final ClonePlayer self;
    private final Motor motor;
    private final Storage warehouse;
    private WorkBoard.Job active;
    private boolean fetching, dropped;
    private int beforeFetch, cargo, ticks, deliveryTicks;
    private long retryAfter;
    public int completed;

    public CivicWork(ClonePlayer self, Motor motor, Perception perception, Crafting crafting) {
        this.self = self; this.motor = motor;
        warehouse = new Storage(self, motor, perception, crafting);
    }
    public static String team(Player player) {
        return player instanceof ClonePlayer clone ? clone.cloneTeam() : ClonePlayer.DEFAULT_TEAM;
    }
    public static WorkBoard.Key key(ServerPlayer requester, String item) {
        return new WorkBoard.Key(requester.getUUID(), requester.level().dimension().location().toString(), team(requester), item);
    }
    public static boolean post(ServerPlayer requester, String item, int count) {
        return CloneManager.get(requester.getServer()).workBoard().post(key(requester, item), count, requester.level().getGameTime());
    }
    public static boolean handledByBoard(ClonePlayer helper, UUID requester, String item) {
        var key = new WorkBoard.Key(requester, helper.level().dimension().location().toString(), team(helper), item);
        return CloneManager.get(helper.getServer()).workBoard().find(key, helper.level().getGameTime()) != null;
    }
    private WorkBoard board() { return CloneManager.get(self.getServer()).workBoard(); }
    private long now() { return self.level().getGameTime(); }
    private Predicate<ItemStack> wanted(WorkBoard.Job job) {
        return WorkBoard.FOOD.equals(job.key.item()) ? FoodAid::goodFood : stack -> ItemAid.matches(stack, job.key.item());
    }
    private int count(Player player, WorkBoard.Job job) {
        Predicate<ItemStack> wanted = wanted(job);
        int total = 0;
        for (ItemStack stack : player.getInventory().items) if (wanted.test(stack)) total += stack.getCount();
        if (wanted.test(player.getOffhandItem())) total += player.getOffhandItem().getCount();
        return total;
    }
    private ServerPlayer requester(WorkBoard.Job job) {
        ServerPlayer player = self.getServer().getPlayerList().getPlayer(job.key.requester());
        return player != null && player.isAlive() && player.level() == self.level()
                && team(player).equals(job.key.team()) && Senses.isAllyOf(player, self) ? player : null;
    }
    private int spare(WorkBoard.Job job) {
        return WorkBoard.FOOD.equals(job.key.item()) ? Math.max(0, count(self, job) - FoodAid.RESERVE)
                : ItemAid.spare(self, job.key.item());
    }
    private WorkBoard.Job pick() {
        if (!com.rlclones.clone.CloneMobs.canUseHands(self) || self.isCreative() || self.getFoodData().getFoodLevel() <= 6 || now() < retryAfter) return null;
        WorkBoard.Job best = null;
        double distance = 64 * 64;
        for (var job : board().jobs(now())) {
            if (job.key.requester().equals(self.getUUID()) || !job.key.dimension().equals(self.level().dimension().location().toString())
                    || !job.key.team().equals(team(self))) continue;
            UUID owner = job.worker(now());
            if (owner != null && !owner.equals(self.getUUID())) continue;
            ServerPlayer recipient = requester(job);
            if (recipient == null) continue;
            if (count(recipient, job) >= job.count) { board().cancel(job.key); continue; }
            double d = recipient.distanceToSqr(self);
            if (d >= distance || spare(job) == 0 && !warehouse.canSupply(wanted(job))) continue;
            best = job; distance = d;
        }
        return best;
    }
    private long checked = Long.MIN_VALUE / 2;
    private boolean available;
    public boolean canHelp() {
        if (now() - checked >= 20) { checked = now(); available = pick() != null; }
        return available;
    }
    public boolean begin() {
        active = pick();
        if (active == null || !board().claim(active.key, self.getUUID(), now())) { active = null; return false; }
        ticks = cargo = deliveryTicks = 0; dropped = false;
        fetching = spare(active) == 0;
        if (fetching) {
            beforeFetch = count(self, active);
            int reserve = WorkBoard.FOOD.equals(active.key.item()) ? Math.max(0, FoodAid.RESERVE - beforeFetch) : 0;
            warehouse.beginSupply(wanted(active), Math.min(64, active.count - count(requester(active), active) + reserve));
        }
        motor.resetStuck();
        String recipientName = requester(active).getGameProfile().getName();
        String announcement = WorkBoard.FOOD.equals(active.key.item()) ? "CIVIC_ACCEPT food" : "GIVE " + recipientName + " " + active.key.item();
        Chat.say(self, Component.translatable("rlclones.civic.accept", recipientName), announcement);
        return true;
    }
    /** true until finished, failed or interrupted. */
    public boolean tick() {
        if (active == null) return false;
        ServerPlayer recipient = requester(active);
        if (recipient == null || self.getFoodData().getFoodLevel() <= 6 || ++ticks > 1800
                || !board().claim(active.key, self.getUUID(), now())) { cancel(); retryAfter = now() + 200; return false; }
        if (count(recipient, active) >= active.count) {
            if (board().complete(active.key, self.getUUID(), now())) {
                completed++;
                Chat.say(self, Component.translatable("rlclones.civic.complete", recipient.getGameProfile().getName()), "CIVIC_DONE " + active.key.item());
                if (recipient instanceof ClonePlayer clone) clone.controller().foodAid().thank(self.getUUID(), 2);
            }
            cancel(); return false;
        }
        if (fetching) {
            Storage.Status status = warehouse.tick();
            if (status == Storage.Status.WORKING) return true;
            cargo = Math.max(0, count(self, active) - beforeFetch);
            warehouse.reset(); fetching = false;
            if (cargo == 0) { cancel(); retryAfter = now() + 200; return false; }
        }
        if (dropped) {
            if (++deliveryTicks > 100) { cancel(); retryAfter = now() + 200; return false; }
            motor.stop(); return true;
        }
        if (recipient.distanceTo(self) > 2.5) {
            motor.navigate(recipient.position(), 1.5, recipient.distanceTo(self) > 10);
            if (motor.stuckCount() > 8) { cancel(); retryAfter = now() + 200; return false; }
            return true;
        }
        int available = WorkBoard.FOOD.equals(active.key.item()) ? spare(active) : Math.max(cargo, spare(active));
        int amount = Math.min(active.count - count(recipient, active), available);
        if (amount <= 0) { cancel(); return false; }
        motor.stop(); motor.lookAt(recipient);
        java.util.List<ItemStack> supplies = new java.util.ArrayList<>(self.getInventory().items);
        supplies.add(self.getOffhandItem());
        supplies.sort(java.util.Comparator.comparingInt(stack -> stack.getItem() instanceof net.minecraft.world.item.TieredItem tool ? tool.getTier().getLevel() : 0));
        for (ItemStack stack : supplies) {
            if (amount <= 0) break;
            if (!wanted(active).test(stack)) continue;
            int n = Math.min(amount, stack.getCount());
            var gift = self.drop(stack.split(n), false, true);
            if (gift != null) {
                // Same reach as handing an item to a friend, with exclusive pickup ownership.
                gift.setDeltaMovement(recipient.position().subtract(self.position()).normalize().scale(0.2).add(0, 0.1, 0));
                ItemAid.gift(gift, recipient.getUUID());
            }
            amount -= n;
        }
        self.getInventory().setChanged();
        dropped = true;
        return true;
    }
    public void cancel() {
        if (active != null) board().release(active.key, self.getUUID());
        if (fetching) warehouse.reset();
        active = null; fetching = false;
    }
}
