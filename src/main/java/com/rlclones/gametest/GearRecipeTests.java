package com.rlclones.gametest;

import com.rlclones.ai.*;
import com.rlclones.clone.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.*;
import java.util.*;

/** Test-only recipe injection: no copper armor recipe is added to normal gameplay. */
@GameTestHolder("rlclones_gear")
@PrefixGameTestTemplate(false)
public final class GearRecipeTests {
    private static List<Recipe<?>> original;
    private static final Set<UUID> CLONES = new HashSet<>();
    private static CraftingRecipe recipe(String id, Item output, String... rows) {
        int width = rows[0].length();
        var ingredients = NonNullList.withSize(width * rows.length, Ingredient.EMPTY);
        for (int y=0; y<rows.length; y++) for (int x=0; x<width; x++)
            if (rows[y].charAt(x) == '#') ingredients.set(y*width+x, Ingredient.of(Items.COPPER_INGOT));
        return new ShapedRecipe(new ResourceLocation("gear_test", id), "", CraftingBookCategory.EQUIPMENT,
                width, rows.length, ingredients, new ItemStack(output));
    }
    private static List<Recipe<?>> fixtures() {
        return List.of(recipe("copper_chest",Items.CHAINMAIL_CHESTPLATE,"# #","###","###"),
                recipe("copper_legs",Items.CHAINMAIL_LEGGINGS,"###","# #","# #"),
                recipe("copper_head",Items.CHAINMAIL_HELMET,"###","# #"),
                recipe("copper_feet",Items.CHAINMAIL_BOOTS,"# #","# #"));
    }
    @BeforeBatch(batch="mod-gear")
    public static void install(ServerLevel level) {
        original = new ArrayList<>(level.getRecipeManager().getRecipes());
        var recipes = new ArrayList<>(original); recipes.addAll(fixtures());
        level.getRecipeManager().replaceRecipes(recipes); Crafting.invalidateRecipes();
    }
    @AfterBatch(batch="mod-gear")
    public static void restore(ServerLevel level) {
        if (original != null) level.getRecipeManager().replaceRecipes(original);
        Crafting.invalidateRecipes(); cleanup(level);
    }
    @AfterBatch(batch="gear-reload")
    public static void cleanup(ServerLevel level) {
        var manager = CloneManager.get(level.getServer());
        for (ClonePlayer clone : new ArrayList<>(manager.clones()))
            if (CLONES.contains(clone.getUUID())) manager.remove(clone,true,Component.literal("gear test done"));
        CLONES.clear();
    }
    static ClonePlayer clone(GameTestHelper h) {
        Vec3 pos = h.absoluteVec(new Vec3(7.5,2,7.5));
        var clone = CloneManager.get(h.getLevel().getServer()).summon(null,h.getLevel(),pos,0);
        if (clone == null) throw new IllegalStateException("no test clone");
        CLONES.add(clone.getUUID()); clone.setAiEnabled(false); clone.getInventory().clearContent();
        clone.getInventory().selected = 8;
        return clone;
    }
    static void table(GameTestHelper h, ClonePlayer clone) {
        BlockPos pos = h.absolutePos(new BlockPos(7,2,9));
        h.getLevel().setBlock(pos,Blocks.CRAFTING_TABLE.defaultBlockState(),3);
        clone.controller().perception().noteBlock(pos);
    }
    @GameTest(template="arena",batch="mod-gear",timeoutTicks=40)
    public static void copperIsKeptAndFetchableForRecipeDrivenArmor(GameTestHelper h) {
        var c=clone(h);
        ItemStack copper=new ItemStack(Items.COPPER_INGOT,8);
        h.assertTrue(Crafting.modGearMaterial(c,copper),"non-vanilla armor recipe makes copper useful even with vanilla output ID");
        h.assertTrue(Storage.wants(c).test(copper),"copper is fetched for a useful armor recipe");
        c.getInventory().setItem(0,copper);
        h.assertTrue(!Storage.unneededSlots(c).contains(0),"armor material is not treated as surplus");
        h.succeed();
    }
    @GameTest(template="arena",batch="mod-gear",timeoutTicks=40)
    public static void craftableArmorGetsPriority(GameTestHelper h) {
        var c=clone(h); table(h,c);
        c.getInventory().setItem(0,new ItemStack(Items.COPPER_INGOT,8));
        var planned=Crafting.plan(c);
        h.assertTrue(planned!=null && planned.getResultItem(h.getLevel().registryAccess()).is(Items.CHAINMAIL_CHESTPLATE),"best currently craftable armor is prioritized");
        h.assertTrue(c.controller().crafting().urgent(),"useful armor should not wait indefinitely for crafting");
        h.succeed();
    }
    @GameTest(template="arena",batch="mod-gear",timeoutTicks=40)
    public static void armorPlanBuildsItsMissingTable(GameTestHelper h) {
        var c=clone(h);
        c.getInventory().setItem(0,new ItemStack(Items.COPPER_INGOT,8));
        c.getInventory().setItem(1,new ItemStack(Items.OAK_PLANKS,4));
        var planned=Crafting.plan(c);
        h.assertTrue(planned!=null && planned.getResultItem(h.getLevel().registryAccess()).is(Items.CRAFTING_TABLE),"armor plan prepares its workbench first");
        h.assertTrue(c.controller().crafting().urgent(),"missing workbench does not hide a useful armor goal");
        h.succeed();
    }
    @GameTest(template="arena",batch="mod-gear",timeoutTicks=40)
    public static void betterCarriedArmorPreventsWaste(GameTestHelper h) {
        var c=clone(h);
        c.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.DIAMOND_CHESTPLATE));
        h.assertTrue(!Crafting.armorImproves(c,new ItemStack(Items.CHAINMAIL_CHESTPLATE)),"weaker armor is not an upgrade");
        h.assertTrue(Storage.wants(c).test(new ItemStack(Items.NETHERITE_CHESTPLATE)),"toughness improvement is recognized in storage too");
        c.getInventory().setItem(0,new ItemStack(Items.NETHERITE_CHESTPLATE));
        h.assertTrue(!Storage.unneededSlots(c).contains(0),"higher toughness is not discarded because defense is equal");
        h.assertTrue(!Crafting.armorImproves(c,new ItemStack(Items.NETHERITE_CHESTPLATE)),"already carried equipment prevents duplicate manufacture");
        h.succeed();
    }
    @GameTest(template="arena",batch="mod-gear",timeoutTicks=600)
    public static void warehouseCopperBecomesAnEquippedArmorSet(GameTestHelper h) {
        var c=clone(h); table(h,c);
        BlockPos pos=h.absolutePos(new BlockPos(9,2,7));
        h.getLevel().setBlock(pos,Blocks.CHEST.defaultBlockState(),3);
        var chest=(net.minecraft.world.Container)h.getLevel().getBlockEntity(pos);
        chest.setItem(0,new ItemStack(Items.COPPER_INGOT,24));
        var bases=Bases.get(h.getLevel().getServer()); var base=bases.add(h.getLevel().dimension(),pos,"gear-test");
        bases.addChest(base,pos); bases.record(h.getLevel().dimension(),pos,chest);
        h.assertTrue(c.controller().storage().armorSuppliesAvailable(),"warehouse armor supplies are urgent");
        c.controller().storage().begin(Storage.Mode.FETCH);
        boolean[] fetching={true};
        h.onEachTick(()->{
            if(fetching[0]) {
                if(c.controller().storage().tick()!=Storage.Status.WORKING) fetching[0]=false;
            } else c.controller().crafting().tick();
            c.controller().motor().tick(); Equipment.manage(c,true);
        });
        h.succeedWhen(()->{
            h.assertTrue(c.getItemBySlot(EquipmentSlot.CHEST).is(Items.CHAINMAIL_CHESTPLATE)
                    &&c.getItemBySlot(EquipmentSlot.LEGS).is(Items.CHAINMAIL_LEGGINGS)
                    &&c.getItemBySlot(EquipmentSlot.HEAD).is(Items.CHAINMAIL_HELMET)
                    &&c.getItemBySlot(EquipmentSlot.FEET).is(Items.CHAINMAIL_BOOTS),"warehouse materials are crafted via the table and equipped: "+c.controller().crafting().debug());
            h.assertTrue(chest.countItem(Items.COPPER_INGOT)==0 && c.getInventory().countItem(Items.COPPER_INGOT)==0,"24 copper ingots were actually used");
            bases.bases.remove(base);
        });
    }
    @GameTest(template="arena",batch="gear-reload",timeoutTicks=60)
    public static void recipeReloadRefreshesMaterialRecognition(GameTestHelper h) {
        var manager=h.getLevel().getRecipeManager();
        var before=new ArrayList<Recipe<?>>(manager.getRecipes());
        try {
            var without=new ArrayList<Recipe<?>>(before);
            without.removeIf(r->r.getId().getNamespace().equals("gear_test"));
            manager.replaceRecipes(without); Crafting.invalidateRecipes();
            var c=clone(h); ItemStack copper=new ItemStack(Items.COPPER_INGOT,8);
            h.assertTrue(!Crafting.armorMaterial(c,copper),"initial index has no copper armor");
            without.addAll(fixtures()); manager.replaceRecipes(without); Crafting.invalidateRecipes();
            h.assertTrue(Crafting.armorMaterial(c,copper),"same RecipeManager recognizes reloaded recipes");
        } finally { manager.replaceRecipes(before); Crafting.invalidateRecipes(); }
        h.succeed();
    }
}
