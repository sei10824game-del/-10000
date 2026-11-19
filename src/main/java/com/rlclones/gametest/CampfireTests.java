package com.rlclones.gametest;

import com.rlclones.ai.Campfire;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraftforge.gametest.*;

@GameTestHolder("rlclones_campfire")
@PrefixGameTestTemplate(false)
public final class CampfireTests {
    @AfterBatch(batch="campfire")
    public static void cleanup(ServerLevel level) { GearRecipeTests.cleanup(level); }
    private static BlockPos fire(GameTestHelper h) {
        BlockPos p=h.absolutePos(new BlockPos(7,2,9));
        h.getLevel().setBlock(p,Blocks.CAMPFIRE.defaultBlockState(),3);
        return p;
    }
    private static int occupied(GameTestHelper h,BlockPos pos) {
        int count=0;
        for(var stack:((CampfireBlockEntity)h.getLevel().getBlockEntity(pos)).getItems()) if(!stack.isEmpty()) count++;
        return count;
    }
    @GameTest(template="arena",batch="campfire",timeoutTicks=120)
    public static void fillsAllFourSlotsFromSplitInventoryStacks(GameTestHelper h) {
        var c=GearRecipeTests.clone(h); var p=fire(h);
        c.getInventory().setItem(0,new ItemStack(Items.BEEF));
        c.getInventory().setItem(10,new ItemStack(Items.CHICKEN,2));
        c.getInventory().setItem(20,new ItemStack(Items.PORKCHOP));
        c.controller().campfire().hurry();
        h.onEachTick(()->c.controller().campfire().tick(h.getLevel().getGameTime(),false));
        h.succeedWhen(()->{
            h.assertTrue(occupied(h,p)==4,"all empty slots filled from mixed food and non-hotbar stacks");
            h.assertTrue(!Campfire.hasRaw(c),"all four raw foods consumed");
        });
    }
    @GameTest(template="arena",batch="campfire",timeoutTicks=120)
    public static void fillsRemainingTwoSlotsOnSharedFire(GameTestHelper h) {
        var c=GearRecipeTests.clone(h); var p=fire(h);
        var be=(CampfireBlockEntity)h.getLevel().getBlockEntity(p);
        be.placeFood(null,new ItemStack(Items.BEEF),600); be.placeFood(null,new ItemStack(Items.CHICKEN),600);
        c.getInventory().setItem(0,new ItemStack(Items.PORKCHOP,6));
        c.controller().campfire().hurry();
        h.onEachTick(()->c.controller().campfire().tick(h.getLevel().getGameTime(),false));
        h.succeedWhen(()->{
            h.assertTrue(occupied(h,p)==4,"existing food leaves two slots to fill");
            h.assertTrue(c.getInventory().countItem(Items.PORKCHOP)==4,"only actual free slots consume food");
        });
    }
    @GameTest(template="arena",batch="campfire",timeoutTicks=180)
    public static void refillsAsSoonAsCookingFreesSlots(GameTestHelper h) {
        var c=GearRecipeTests.clone(h); var p=fire(h);
        var be=(CampfireBlockEntity)h.getLevel().getBlockEntity(p);
        for(int i=0;i<4;i++) be.placeFood(null,new ItemStack(Items.BEEF),30);
        c.getInventory().setItem(0,new ItemStack(Items.CHICKEN,4));
        c.controller().campfire().hurry();
        h.onEachTick(()->c.controller().campfire().tick(h.getLevel().getGameTime(),false));
        h.succeedWhen(()->{
            h.assertTrue(c.controller().campfire().cooked==4,"freed slots refill without fixed 700-tick wait");
            h.assertTrue(occupied(h,p)==4&&!Campfire.hasRaw(c),"new batch fills the fire");
        });
    }
    @GameTest(template="arena",batch="campfire",timeoutTicks=1600)
    public static void finishesMultipleBatchesWithoutTotalSessionTimeout(GameTestHelper h) {
        var c=GearRecipeTests.clone(h); var p=fire(h);
        c.getInventory().setItem(0,new ItemStack(Items.BEEF,8));
        c.controller().campfire().hurry();
        boolean[] finished={false};
        h.onEachTick(()->{
            boolean busy=c.controller().campfire().tick(h.getLevel().getGameTime(),false);
            if(c.controller().campfire().cooked==8&&!busy) finished[0]=true;
        });
        h.succeedWhen(()->{
            h.assertTrue(finished[0],"both batches finish and cooking task releases after pickup interval");
            h.assertTrue(occupied(h,p)==0&&!Campfire.hasRaw(c),"no food remains uncooked");
        });
    }
    @GameTest(template="arena",batch="campfire",timeoutTicks=120)
    public static void dangerInterruptsAndExtinguishedFireIsNotLoaded(GameTestHelper h) {
        var c=GearRecipeTests.clone(h); var p=fire(h); var cooking=c.controller().campfire();
        c.getInventory().setItem(0,new ItemStack(Items.BEEF,4));
        cooking.hurry(); cooking.tick(h.getLevel().getGameTime(),false);
        h.assertTrue(!cooking.tick(h.getLevel().getGameTime(),true),"danger releases the cooking task immediately");
        h.getLevel().setBlock(p,Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT,false),3);
        cooking.hurry();
        h.assertTrue(!cooking.tick(h.getLevel().getGameTime(),false),"unlit fire cannot keep the clone waiting");
        h.assertTrue(c.getInventory().countItem(Items.BEEF)==4,"no food consumed by interrupted/unlit fire");
        h.succeed();
    }
    @GameTest(template="arena",batch="campfire",timeoutTicks=180)
    public static void temporaryRefusalDoesNotPretendFireIsFull(GameTestHelper h) {
        var c=GearRecipeTests.clone(h); var p=fire(h); var cooking=c.controller().campfire();
        c.getInventory().setItem(0,new ItemStack(Items.BEEF,4));
        c.setOnGround(true); cooking.hurry(); cooking.tick(h.getLevel().getGameTime(),false);
        // Spectator interaction is refused by the real server game mode.
        c.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SPECTATOR);
        h.onEachTick(()->cooking.tick(h.getLevel().getGameTime(),false));
        h.runAtTickTime(35,()->{
            h.assertTrue(occupied(h,p)==0 && cooking.cooked==0,"refused interactions consume no food");
            h.assertTrue(cooking.report().contains("loaded=0"),"refusal must not invent four loaded items");
            c.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            c.setOnGround(true); cooking.hurry();
        });
        h.succeedWhen(()->h.assertTrue(occupied(h,p)==4 && cooking.cooked==4,"retry fills the fire once interaction is allowed"));
    }
    @GameTest(template="arena",batch="campfire",timeoutTicks=40)
    public static void nearestSearchMatchesOriginalAndSeesChangesImmediately(GameTestHelper h) {
        var c=GearRecipeTests.clone(h); var p=fire(h);
        h.assertTrue(p.equals(c.controller().campfire().existing()),"nearest lit fire found");
        h.getLevel().setBlock(p,Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT,false),3);
        var other=p.offset(2,0,0); h.getLevel().setBlock(other,Blocks.SOUL_CAMPFIRE.defaultBlockState(),3);
        h.assertTrue(other.equals(c.controller().campfire().existing()),"same-tick extinguish and soul fire placement seen");
        h.getLevel().setBlock(p,Blocks.CAMPFIRE.defaultBlockState(),3);
        h.assertTrue(p.equals(c.controller().campfire().existing()),"same-tick relight restores nearest result");
        h.succeed();
    }
}
