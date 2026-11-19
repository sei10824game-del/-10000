package com.rlclones.gametest;

import com.rlclones.ai.*;
import com.rlclones.clone.Bases;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.*;
import net.minecraftforge.registries.ForgeRegistries;

/** Optional integration test. Install Copper Age Backport and select rlclones_copper_compat. */
@GameTestHolder("rlclones_copper_compat")
@PrefixGameTestTemplate(false)
public final class CopperBackportTests {
    @AfterBatch(batch="copper-backport")
    public static void cleanup(ServerLevel level) { GearRecipeTests.cleanup(level); }

    @GameTest(template="arena",batch="copper-backport",timeoutTicks=800)
    public static void realCopperBackportArmorIsFetchedCraftedAndEquipped(GameTestHelper h) {
        h.assertTrue(net.minecraftforge.fml.ModList.get().isLoaded("copperagebackport"), "this optional test requires Copper Age Backport");
        var helmet = ForgeRegistries.ITEMS.getValue(new ResourceLocation("minecraft:copper_helmet"));
        var chestplate = ForgeRegistries.ITEMS.getValue(new ResourceLocation("minecraft:copper_chestplate"));
        var leggings = ForgeRegistries.ITEMS.getValue(new ResourceLocation("minecraft:copper_leggings"));
        var boots = ForgeRegistries.ITEMS.getValue(new ResourceLocation("minecraft:copper_boots"));
        h.assertTrue(helmet instanceof ArmorItem && chestplate instanceof ArmorItem && leggings instanceof ArmorItem && boots instanceof ArmorItem,
                "real backported copper armor is registered under minecraft namespace");
        var c=GearRecipeTests.clone(h); GearRecipeTests.table(h,c);
        BlockPos pos=h.absolutePos(new BlockPos(9,2,7));
        h.getLevel().setBlock(pos,Blocks.CHEST.defaultBlockState(),3);
        var chest=(net.minecraft.world.Container)h.getLevel().getBlockEntity(pos);
        chest.setItem(0,new ItemStack(Items.COPPER_INGOT,24));
        var bases=Bases.get(h.getLevel().getServer()); var base=bases.add(h.getLevel().dimension(),pos,"copper-compat");
        bases.addChest(base,pos); bases.record(h.getLevel().dimension(),pos,chest);
        h.assertTrue(c.controller().storage().armorSuppliesAvailable(),"real copper armor recipes make stored copper useful");
        c.controller().storage().begin(Storage.Mode.FETCH);
        boolean[] fetching={true};
        h.onEachTick(()->{
            if(fetching[0]) { if(c.controller().storage().tick()!=Storage.Status.WORKING) fetching[0]=false; }
            else c.controller().crafting().tick();
            c.controller().motor().tick(); Equipment.manage(c,true);
        });
        h.succeedWhen(()->{
            h.assertTrue(c.getItemBySlot(EquipmentSlot.HEAD).is(helmet) && c.getItemBySlot(EquipmentSlot.CHEST).is(chestplate)
                    && c.getItemBySlot(EquipmentSlot.LEGS).is(leggings) && c.getItemBySlot(EquipmentSlot.FEET).is(boots),
                    "real copper armor set is crafted and equipped: "+c.controller().crafting().debug());
            h.assertTrue(chest.countItem(Items.COPPER_INGOT)==0 && c.getInventory().countItem(Items.COPPER_INGOT)==0,"actual recipes consumed 24 copper");
            bases.bases.remove(base);
        });
    }
}
