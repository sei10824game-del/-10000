package com.rlclones.ai;

import com.rlclones.RLClones;
import com.rlclones.clone.CloneManager;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public chat between agents. Clones talk exactly where players talk (the chat everyone sees) and read
 * everything said there - by clones and by real players alike - picking out calls for help with coordinates.
 */
public final class Chat {
    /** A call for help read from chat. {@code urgent} = SOS (emergency), otherwise a request for more hands. */
    public record Request(UUID from, String fromName, ResourceKey<Level> dimension, Vec3 pos, boolean urgent, String reason, long tick) {
    }

    private static final Pattern COORDS = Pattern.compile("(-?\\d+)[\\s,/]+(-?\\d+)[\\s,/]+(-?\\d+)");
    private static final String[] SOS_WORDS = {"sos", "mayday", "助けて", "たすけて", "救援", "緊急", "ヘルプ"};
    private static final String[] HELP_WORDS = {"help", "backup", "応援", "来て", "きて", "手伝", "てつだ", "集合"};
    private static final Deque<String> RECENT = new ArrayDeque<>();

    private Chat() {
    }

    /** Say something in public chat as {@code sender}; other clones read the plain (machine readable) form. */
    public static void say(ServerPlayer sender, Component content, String plain) {
        MinecraftServer server = sender.getServer();
        if (server == null) {
            return;
        }
        server.getPlayerList().broadcastSystemMessage(Component.translatable("chat.type.text", sender.getDisplayName(), content), false);
        synchronized (RECENT) {
            RECENT.addLast(sender.getGameProfile().getName() + ": " + plain);
            while (RECENT.size() > 50) {
                RECENT.pollFirst();
            }
        }
        deliver(sender, plain);
    }

    /** Let every clone (except the speaker) read a chat line. Also called for real players' chat. */
    public static void deliver(ServerPlayer sender, String text) {
        CloneManager m = CloneManager.peek();
        if (m == null) {
            return;
        }
        for (ClonePlayer c : new ArrayList<>(m.clones())) {
            if (c != sender && c.isAlive()) {
                try {
                    c.controller().onChat(sender, text);
                } catch (RuntimeException e) {
                    RLClones.LOGGER.warn("Clone {} failed to read chat", c.getGameProfile().getName(), e);
                }
            }
        }
    }

    public static List<String> recent() {
        synchronized (RECENT) {
            return new ArrayList<>(RECENT);
        }
    }

    /** Understand "SOS 12 64 -30 lava", "help 100 70 20", "助けて！" (no coordinates = where the speaker stands)... */
    @Nullable
    public static Request parse(ServerPlayer sender, String text, long now) {
        String lower = text.toLowerCase(Locale.ROOT);
        boolean sos = contains(lower, SOS_WORDS);
        boolean help = contains(lower, HELP_WORDS);
        if (!sos && !help) {
            return null;
        }
        Vec3 pos = sender.position();
        String reason = "";
        Matcher m = COORDS.matcher(text);
        if (m.find()) {
            try {
                pos = new Vec3(Integer.parseInt(m.group(1)) + 0.5, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)) + 0.5);
                reason = text.substring(m.end()).trim();
            } catch (NumberFormatException ignored) {
                // keep the speaker's position
            }
        }
        return new Request(sender.getUUID(), sender.getGameProfile().getName(), sender.level().dimension(), pos, sos, reason, now);
    }

    private static boolean contains(String text, String[] words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
