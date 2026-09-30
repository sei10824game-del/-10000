package com.rlclones.registry;

import com.rlclones.RLClones;
import com.rlclones.item.CloneSpawnEggItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, RLClones.MODID);

    public static final RegistryObject<Item> CLONE_SPAWN_EGG = ITEMS.register("clone_spawn_egg",
            () -> new CloneSpawnEggItem(new Item.Properties()));

    private ModItems() {
    }

    public static void onCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(CLONE_SPAWN_EGG);
        }
    }
}
