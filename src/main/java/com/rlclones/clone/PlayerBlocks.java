package com.rlclones.clone;

import com.rlclones.RLClones;
import com.rlclones.ai.Motor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** A block a real player puts down (a step for the clones, a bridge...) is not for the clones to dig out again, whatever they once laid there. */
@Mod.EventBusSubscriber(modid = RLClones.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlayerBlocks {
    private PlayerBlocks() {
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && !(p instanceof ClonePlayer) && !event.getLevel().isClientSide()) {
            var pos = event.getPos().immutable();
            Motor.PLAYER_PLACED.put(pos, p.level().getGameTime());
            Motor.PLUGS.remove(pos); // (a stale record of an old plug there must not make it one)
        }
    }
}
