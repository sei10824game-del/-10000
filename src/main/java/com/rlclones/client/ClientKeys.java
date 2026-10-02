package com.rlclones.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.rlclones.RLClones;
import com.rlclones.clone.ClientAction;
import com.rlclones.network.ModNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.List;

/**
 * Z = summon a clone, X = link / unlink the reinforcement learning of all clones, P = toggle clone respawn,
 * L = teach every clone that the block in hand is harmful.
 * Keys are read straight from the input event so they work even if a vanilla binding shares the key.
 */
@Mod.EventBusSubscriber(modid = RLClones.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientKeys {
    private static boolean conflictsChecked;
    private static boolean smokeReported;

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
        } else if (matches(ClientSetup.TEACH, key)) {
            ModNetwork.sendToServer(ClientAction.TEACH_HARMFUL);
        } else if (matches(ClientSetup.BATTLE_ROYALE, key)) {
            ModNetwork.sendToServer(ClientAction.BATTLE_ROYALE);
        } else if (matches(ClientSetup.BREEDING, key)) {
            ModNetwork.sendToServer(ClientAction.TOGGLE_BREEDING);
        }
    }

    private static boolean matches(KeyMapping mapping, InputConstants.Key key) {
        return !mapping.isUnbound() && mapping.getKey().equals(key) && mapping.getKeyModifier().isActive(mapping.getKeyConflictContext());
    }

    /** CI smoke test (-Drlclones.smokeTest=true): once loading has finished and a menu is shown, report and quit. */
    private static void smokeTest(Minecraft mc) {
        if (smokeReported || !Boolean.getBoolean("rlclones.smokeTest") || mc.getOverlay() != null || mc.screen == null) {
            return;
        }
        smokeReported = true;
        List<KeyMapping> all = Arrays.asList(mc.options.keyMappings);
        boolean registered = all.contains(ClientSetup.SUMMON) && all.contains(ClientSetup.LINK) && all.contains(ClientSetup.RESPAWN)
                && all.contains(ClientSetup.TEACH) && all.contains(ClientSetup.BATTLE_ROYALE) && all.contains(ClientSetup.BREEDING);
        RLClones.LOGGER.info("RLCLONES_CLIENT_READY registered={} keys={},{},{},{},{},{} screen={}", registered,
                ClientSetup.SUMMON.getKey().getName(), ClientSetup.LINK.getKey().getName(), ClientSetup.RESPAWN.getKey().getName(),
                ClientSetup.TEACH.getKey().getName(), ClientSetup.BATTLE_ROYALE.getKey().getName(), ClientSetup.BREEDING.getKey().getName(), mc.screen.getClass().getSimpleName());
        mc.stop();
    }

    /** P is the vanilla "social interactions" key: move that binding out of the way once so P toggles respawn. */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        smokeTest(mc);
        if (conflictsChecked) {
            return;
        }
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
