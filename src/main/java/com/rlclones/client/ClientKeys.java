package com.rlclones.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.rlclones.RLClones;
import com.rlclones.clone.ClientAction;
import com.rlclones.network.ModNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.client.event.ScreenEvent;

import java.util.Arrays;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Z = summon a clone, X = link / unlink the reinforcement learning of all clones, P = toggle clone respawn.
 * Keys are read straight from the input event so they work even if a vanilla binding shares the key.
 */
@Mod.EventBusSubscriber(modid = RLClones.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientKeys {
    private static boolean conflictsChecked;

    private ClientKeys() {
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || mc.getConnection() == null) {
            return;
        }
        InputConstants.Key key = InputConstants.getKey(event.getKey(), event.getScanCode());
        if (matches(ClientSetup.SUMMON, key)) {
            ModNetwork.sendToServer(ClientAction.SUMMON);
        } else if (matches(ClientSetup.LINK, key)) {
            ModNetwork.sendToServer(ClientAction.TOGGLE_LINK);
        } else if (matches(ClientSetup.RESPAWN, key)) {
            ModNetwork.sendToServer(ClientAction.TOGGLE_RESPAWN);
        }
    }

    private static boolean matches(KeyMapping mapping, InputConstants.Key key) {
        return !mapping.isUnbound() && mapping.getKey().equals(key) && mapping.getKeyModifier().isActive(mapping.getKeyConflictContext());
    }

    /** CI smoke test (-Drlclones.smokeTest=true): report that the client reached the title screen, then quit. */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!Boolean.getBoolean("rlclones.smokeTest") || !(event.getScreen() instanceof TitleScreen)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        List<KeyMapping> all = Arrays.asList(mc.options.keyMappings);
        boolean registered = all.contains(ClientSetup.SUMMON) && all.contains(ClientSetup.LINK) && all.contains(ClientSetup.RESPAWN);
        RLClones.LOGGER.info("RLCLONES_CLIENT_READY registered={} keys={},{},{}", registered,
                ClientSetup.SUMMON.getKey().getName(), ClientSetup.LINK.getKey().getName(), ClientSetup.RESPAWN.getKey().getName());
        mc.stop();
    }

    /** P is the vanilla "social interactions" key: move that binding out of the way once so P toggles respawn. */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (conflictsChecked || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null || mc.player == null) {
            return;
        }
        conflictsChecked = true;
        KeyMapping social = mc.options.keySocialInteractions;
        if (!social.isUnbound() && social.getKey().equals(ClientSetup.RESPAWN.getKey())) {
            social.setKey(InputConstants.UNKNOWN);
            KeyMapping.resetMapping();
            mc.options.save();
            mc.player.displayClientMessage(Component.translatable("rlclones.msg.unbound_social").withStyle(ChatFormatting.GRAY), false);
        }
    }
}
