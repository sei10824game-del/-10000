package com.rlclones;

import com.mojang.logging.LogUtils;
import com.rlclones.network.ModNetwork;
import com.rlclones.registry.ModEntities;
import com.rlclones.registry.ModItems;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(RLClones.MODID)
public class RLClones {
    public static final String MODID = "rlclones";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RLClones() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.ITEMS.register(modBus);
        ModEntities.ENTITIES.register(modBus);
        modBus.addListener(ModEntities::onAttributes);
        modBus.addListener(ModItems::onCreativeTab);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        ModNetwork.register();
    }
}
