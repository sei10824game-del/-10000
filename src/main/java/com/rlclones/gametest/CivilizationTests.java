package com.rlclones.gametest;

import com.rlclones.ai.civilization.*;
import com.rlclones.clone.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.*;

@GameTestHolder("rlclones_civic")
@PrefixGameTestTemplate(false)
public final class CivilizationTests {
    private static final java.util.Set<java.util.UUID> CLONES = new java.util.HashSet<>();
    @AfterBatch(batch = "civilization")
    public static void cleanup(net.minecraft.server.level.ServerLevel level) {
        var manager = CloneManager.get(level.getServer());
        for (ClonePlayer c : new java.util.ArrayList<>(manager.clones()))
            if (CLONES.contains(c.getUUID())) manager.remove(c, true, Component.literal("civic test cleanup"));
        CLONES.clear();
    }
    private static ClonePlayer clone(GameTestHelper h, double x) {
        Vec3 pos = h.absoluteVec(new Vec3(x, 2, 7.5));
        var c = CloneManager.get(h.getLevel().getServer()).summon(null, h.getLevel(), pos, 0);
        if (c == null) throw new IllegalStateException("test clone unavailable");
        CLONES.add(c.getUUID()); c.setAiEnabled(false); c.getInventory().clearContent();
        c.getFoodData().setFoodLevel(20);
        return c;
    }
    @GameTest(template = "arena", batch = "civilization", timeoutTicks = 60)
    public static void onlyOneCourierAcceptsTheSameRequest(GameTestHelper h) {
        var recipient = clone(h, 7.5); var a = clone(h, 5.5); var b = clone(h, 9.5);
        a.getInventory().add(new ItemStack(Items.BREAD, 12)); b.getInventory().add(new ItemStack(Items.BREAD, 12));
        CivicWork.post(recipient, WorkBoard.FOOD, 4);
        h.assertTrue(a.controller().civicWork().begin(), "first courier claims job");
        h.assertTrue(!b.controller().civicWork().begin(), "second courier cannot duplicate it");
        a.controller().civicWork().cancel();
        h.assertTrue(b.controller().civicWork().begin(), "interrupted delivery can be reassigned");
        b.controller().civicWork().cancel(); h.succeed();
    }
    @GameTest(template = "arena", batch = "civilization", timeoutTicks = 60)
    public static void hostileTeamsCannotAcceptEachOthersJobs(GameTestHelper h) {
        var recipient = clone(h, 7.5); var courier = clone(h, 5.5);
        recipient.setCloneTeam("village-a"); courier.setCloneTeam("village-b");
        courier.getInventory().add(new ItemStack(Items.BREAD, 12)); CivicWork.post(recipient, WorkBoard.FOOD, 4);
        h.assertTrue(!courier.controller().civicWork().canHelp(), "team boundaries are respected"); h.succeed();
    }
    @GameTest(template = "arena", batch = "civilization", timeoutTicks = 300)
    public static void sharedWarehouseFoodIsActuallyDelivered(GameTestHelper h) {
        var recipient = clone(h, 8.5); var courier = clone(h, 6.5);
        var pos = h.absolutePos(new BlockPos(6, 2, 10));
        h.getLevel().setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        var chest = (net.minecraft.world.Container) h.getLevel().getBlockEntity(pos);
        chest.setItem(0, new ItemStack(Items.BREAD, 16)); chest.setItem(1, new ItemStack(Items.DIAMOND, 3));
        var bases = Bases.get(h.getLevel().getServer()); var base = bases.add(h.getLevel().dimension(), pos, "village");
        bases.addChest(base, pos); bases.record(h.getLevel().dimension(), pos, chest);
        CivicWork.post(recipient, WorkBoard.FOOD, 4);
        h.assertTrue(courier.controller().civicWork().begin(), "courier finds known warehouse stock");
        h.onEachTick(() -> { courier.controller().civicWork().tick(); courier.controller().motor().tick(); });
        h.succeedWhen(() -> {
            h.assertTrue(courier.controller().civicWork().completed == 1, "delivery completes after recipient receives food");
            h.assertTrue(recipient.getInventory().countItem(Items.BREAD) >= 4, "recipient picked up the actual items");
            h.assertTrue(chest.countItem(Items.BREAD) == 8, "only requested food plus courier reserve was withdrawn");
            h.assertTrue(chest.countItem(Items.DIAMOND) == 3, "unrelated warehouse goods are untouched");
            h.assertTrue(courier.getInventory().countItem(Items.BREAD) == 4, "courier keeps its own food reserve");
            bases.bases.remove(base);
        });
    }
    @GameTest(template = "arena", batch = "civilization", timeoutTicks = 300)
    public static void warehouseToolKeepsItsDurabilityOnDelivery(GameTestHelper h) {
        var recipient = clone(h, 8.5); var courier = clone(h, 6.5);
        var pos = h.absolutePos(new BlockPos(6, 2, 10));
        h.getLevel().setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        var chest = (net.minecraft.world.Container) h.getLevel().getBlockEntity(pos);
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE); pick.setDamageValue(17);
        chest.setItem(0, pick);
        var bases = Bases.get(h.getLevel().getServer()); var base = bases.add(h.getLevel().dimension(), pos, "village");
        bases.addChest(base, pos); bases.record(h.getLevel().dimension(), pos, chest);
        CivicWork.post(recipient, "minecraft:iron_pickaxe", 1);
        h.assertTrue(courier.controller().civicWork().begin(), "tool courier accepts warehouse job");
        h.assertTrue(recipient.controller().itemAid().helpComing(Items.IRON_PICKAXE), "requester knows the tool is on its way");
        h.onEachTick(() -> { courier.controller().civicWork().tick(); courier.controller().motor().tick(); });
        h.succeedWhen(() -> {
            h.assertTrue(courier.controller().civicWork().completed == 1, "tool was delivered");
            ItemStack delivered = recipient.getInventory().items.stream().filter(s -> s.is(Items.IRON_PICKAXE)).findFirst().orElse(ItemStack.EMPTY);
            h.assertTrue(!delivered.isEmpty() && delivered.getDamageValue() == 17, "actual tool metadata survives transport");
            h.assertTrue(chest.countItem(Items.IRON_PICKAXE) == 0, "warehouse tool was not duplicated");
            bases.bases.remove(base);
        });
    }

    @GameTest(template = "arena", batch = "civilization", timeoutTicks = 60)
    public static void warehouseEmergencyFoodIsNotTaken(GameTestHelper h) {
        var recipient = clone(h, 8.5); var courier = clone(h, 6.5);
        recipient.setCloneTeam("reserve-village"); courier.setCloneTeam("reserve-village");
        var pos = h.absolutePos(new BlockPos(6, 2, 10));
        h.getLevel().setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        var chest = (net.minecraft.world.Container) h.getLevel().getBlockEntity(pos);
        chest.setItem(0, new ItemStack(Items.BAKED_POTATO, 4));
        var bases = Bases.get(h.getLevel().getServer()); var base = bases.add(h.getLevel().dimension(), pos, "village");
        bases.addChest(base, pos); bases.record(h.getLevel().dimension(), pos, chest);
        CivicWork.post(recipient, "minecraft:baked_potato", 4);
        h.assertTrue(!courier.controller().civicWork().begin(), "warehouse emergency reserve is not promised as surplus");
        h.assertTrue(chest.countItem(Items.BAKED_POTATO) == 4, "reserve remains in warehouse");
        bases.bases.remove(base); h.succeed();
    }

    @GameTest(template = "arena", batch = "civilization", timeoutTicks = 60)
    public static void playerCanPostAndInspectRequests(GameTestHelper h) {
        var recipient = clone(h, 8.5);
        try {
            var dispatcher = h.getLevel().getServer().getCommands().getDispatcher();
            int result = dispatcher.execute("rlclone board request minecraft:torch 8", recipient.createCommandSourceStack());
            h.assertTrue(result == 1, "request command succeeds");
            var job = CloneManager.get(h.getLevel().getServer()).workBoard().find(CivicWork.key(recipient, "minecraft:torch"), h.getLevel().getGameTime());
            h.assertTrue(job != null && job.count == 8, "request is on shared board");
            h.assertTrue(dispatcher.execute("rlclone board", recipient.createCommandSourceStack()) > 0, "board command lists requests");
            h.assertTrue(dispatcher.execute("rlclone board cancel", recipient.createCommandSourceStack()) >= 1, "requester can cancel their own jobs");
            h.assertTrue(CloneManager.get(h.getLevel().getServer()).workBoard().find(CivicWork.key(recipient, "minecraft:torch"), h.getLevel().getGameTime()) == null, "cancelled job is removed");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) {
            throw new AssertionError(ex);
        }
        h.succeed();
    }

}
