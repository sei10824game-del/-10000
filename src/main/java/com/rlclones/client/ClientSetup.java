package com.rlclones.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.rlclones.RLClones;
import com.rlclones.item.CloneSpawnEggItem;
import com.rlclones.registry.ModEntities;
import com.rlclones.registry.ModItems;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = RLClones.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    public static final String CATEGORY = "key.categories.rlclones";
    public static final KeyMapping SUMMON = new KeyMapping("key.rlclones.summon", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, CATEGORY);
    public static final KeyMapping LINK = new KeyMapping("key.rlclones.link", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, CATEGORY);
    public static final KeyMapping RESPAWN = new KeyMapping("key.rlclones.respawn", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, CATEGORY);
    public static final KeyMapping TEACH = new KeyMapping("key.rlclones.teach_harmful", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, CATEGORY);
    public static final KeyMapping BATTLE_ROYALE = new KeyMapping("key.rlclones.battle_royale", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY);

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onKeys(RegisterKeyMappingsEvent event) {
        event.register(SUMMON);
        event.register(LINK);
        event.register(RESPAWN);
        event.register(TEACH);
        event.register(BATTLE_ROYALE);
    }

    @SubscribeEvent
    public static void onItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tint) -> tint == 0 ? CloneSpawnEggItem.BASE_COLOR : CloneSpawnEggItem.SPOT_COLOR, ModItems.CLONE_SPAWN_EGG.get());
    }

    @SubscribeEvent
    public static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.PATH_PROXY.get(), NoopRenderer::new);
    }
}
