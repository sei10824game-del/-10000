package com.rlclones.client;

import com.rlclones.network.ModNetwork;
import com.rlclones.clone.ClientAction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/** K key: the clone switches (same as the /rlclone commands) and the clone-mob list. Every button runs the command, then asks the server for the new state. */
public class SettingsScreen extends Screen {
    /** Order of the packet's switches; the command word for each. */
    static final String[] COMMANDS = {"battleroyale", "breeding", "chat", "coward", "flee", "link", "respawn", "summonedmobs", "mobfaction", "equipfaction", "plainstands"};
    static boolean[] switches = new boolean[COMMANDS.length];
    static java.util.Set<String> mobTypes = new java.util.TreeSet<>();

    public SettingsScreen() {
        super(Component.translatable("rlclones.ui.title"));
    }

    /** From the server: show the new state (open the screen if none of ours is up). */
    public static void receive(ModNetwork.SettingsPacket p) {
        if (p.switches().length == COMMANDS.length) {
            switches = p.switches();
        }
        mobTypes = new java.util.TreeSet<>(p.mobTypes());
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MobListScreen m) {
            m.rebuild();
        } else if (mc.screen instanceof SettingsScreen s) {
            s.rebuildWidgets();
        } else if (mc.screen == null) {
            mc.setScreen(new SettingsScreen());
        }
    }

    static void command(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.connection.sendCommand(text);
            ModNetwork.sendToServer(ClientAction.OPEN_SETTINGS);
        }
    }

    private static final int PER_PAGE = 6;
    private static int page;

    private int pages() {
        return (COMMANDS.length + PER_PAGE - 1) / PER_PAGE;
    }

    private void turn(int by) {
        int next = Math.max(0, Math.min(pages() - 1, page + by));
        if (next != page) {
            page = next;
            rebuildWidgets();
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        turn(delta < 0 ? 1 : -1);
        return true;
    }

    @Override
    protected void init() {
        int w = 220;
        int x = width / 2 - w / 2;
        int y = 32;
        page = Math.min(page, pages() - 1);
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= COMMANDS.length) {
                break;
            }
            String word = COMMANDS[idx];
            boolean on = switches[idx];
            addRenderableWidget(Button.builder(Component.translatable("rlclones.ui." + word).append(": ").append(on ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF),
                    b -> command("rlclone " + word + (word.endsWith("faction") ? (on ? " hostile" : " peace") : on ? " off" : " on"))).bounds(x, y + i * 24, w, 20).build());
        }
        int ny = y + PER_PAGE * 24 + 2;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> turn(-1)).bounds(x, ny, 40, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal(">"), b -> turn(1)).bounds(x + w - 40, ny, 40, 20).build()).active = page < pages() - 1;
        addRenderableWidget(Button.builder(Component.translatable("rlclones.ui.mobs"), b -> minecraft.setScreen(new MobListScreen(this)))
                .bounds(x, ny + 26, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("rlclones.ui.done"), b -> onClose())
                .bounds(x, ny + 50, w, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        g.drawCenteredString(font, Component.literal((page + 1) + "/" + pages()), width / 2, 32 + PER_PAGE * 24 + 8, 0xFFFFFF);
        super.render(g, mx, my, pt);
    }
}
