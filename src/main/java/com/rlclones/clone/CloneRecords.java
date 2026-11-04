package com.rlclones.clone;

import com.rlclones.RLClones;
import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * What the clones have done, for /rlclone achievement and /rlclone item: every advancement a clone (a clone mob's soul, a piece of
 * clone equipment...) earned, and every kind of item one ever held, with the first clone to do it. Kept in the world's clone data.
 */
@Mod.EventBusSubscriber(modid = RLClones.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CloneRecords {
    private CloneRecords() {
    }

    @SubscribeEvent
    public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (event.getEntity() instanceof ClonePlayer c && c.getServer() != null && event.getAdvancement().getDisplay() != null) {
            CloneManager.get(c.getServer()).recordAdvancement(event.getAdvancement().getId().toString(), c.getGameProfile().getName());
        }
    }

    /** Every five seconds: what is in the clones' bags (and on them) that no clone had before is written down. */
    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 100 != 0 || CloneManager.peek() == null) {
            return;
        }
        CloneManager m = CloneManager.peek();
        for (ClonePlayer c : new ArrayList<>(m.clones())) {
            for (List<ItemStack> list : List.of(c.getInventory().items, c.getInventory().armor, c.getInventory().offhand)) {
                for (ItemStack s : list) {
                    if (!s.isEmpty()) {
                        ResourceLocation id = ForgeRegistries.ITEMS.getKey(s.getItem());
                        if (id != null) {
                            m.recordItem(id.toString(), c.getGameProfile().getName());
                        }
                    }
                }
            }
        }
    }

    /** Advancements the clones logged in now already hold (earned before the book was kept). */
    public static void catchUp(MinecraftServer server) {
        CloneManager m = CloneManager.get(server);
        for (ClonePlayer c : new ArrayList<>(m.clones())) {
            for (Advancement adv : server.getAdvancements().getAllAdvancements()) {
                if (adv.getDisplay() != null && c.getAdvancements().getOrStartProgress(adv).isDone()) {
                    m.recordAdvancement(adv.getId().toString(), c.getGameProfile().getName());
                }
            }
        }
    }
}
