package com.rlclones.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The mobs (search box + scrolling list): a click marks / unmarks the type so its natural spawns become clone mobs. */
public class MobListScreen extends Screen {
    private static final int ROWS = 8;
    private final Screen parent;
    private EditBox search;
    private int offset;
    private List<ResourceLocation> shown = List.of();

    public MobListScreen(Screen parent) {
        super(Component.translatable("rlclones.ui.mobs_title"));
        this.parent = parent;
    }

    void rebuild() {
        rebuildWidgets();
    }

    private static boolean isMob(EntityType<?> t) {
        return t != EntityType.PLAYER && t.getCategory() != MobCategory.MISC && DefaultAttributes.hasSupplier(t);
    }

    @Override
    protected void init() {
        String q = search == null ? "" : search.getValue();
        search = new EditBox(font, width / 2 - 100, 30, 200, 18, Component.translatable("rlclones.ui.search"));
        search.setHint(Component.translatable("rlclones.ui.search"));
        search.setValue(q);
        search.setResponder(s -> {
            offset = 0;
            rebuildWidgets();
        });
        addRenderableWidget(search);
        setInitialFocus(search);
        List<ResourceLocation> all = new ArrayList<>();
        String needle = q.toLowerCase(Locale.ROOT);
        for (EntityType<?> t : ForgeRegistries.ENTITY_TYPES) {
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(t);
            if (id != null && isMob(t) && (needle.isEmpty() || id.toString().contains(needle) || t.getDescription().getString().toLowerCase(Locale.ROOT).contains(needle))) {
                all.add(id);
            }
        }
        all.sort(null);
        offset = Math.max(0, Math.min(offset, Math.max(0, all.size() - ROWS)));
        shown = all.subList(offset, Math.min(all.size(), offset + ROWS));
        for (int i = 0; i < shown.size(); i++) {
            ResourceLocation id = shown.get(i);
            boolean on = SettingsScreen.mobTypes.contains(id.toString());
            Component name = ForgeRegistries.ENTITY_TYPES.getValue(id).getDescription().copy().append(" (" + id + ")");
            addRenderableWidget(Button.builder((on ? Component.literal("[x] ") : Component.literal("[ ] ")).append(name),
                    b -> SettingsScreen.command("rlclone mobtype " + (on ? "remove " : "add ") + id)).bounds(width / 2 - 150, 56 + i * 22, 300, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("rlclones.ui.done"), b -> onClose()).bounds(width / 2 - 100, 56 + ROWS * 22 + 6, 200, 20).build());
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        offset = Math.max(0, offset - (int) Math.signum(delta));
        rebuildWidgets();
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        super.render(g, mx, my, pt);
    }
}
