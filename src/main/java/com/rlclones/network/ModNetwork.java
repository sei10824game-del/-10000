package com.rlclones.network;

import com.rlclones.RLClones;
import com.rlclones.clone.ClientAction;
import com.rlclones.clone.CloneManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

public final class ModNetwork {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RLClones.MODID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private ModNetwork() {
    }

    public static void register() {
        CHANNEL.messageBuilder(ActionPacket.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ActionPacket::encode)
                .decoder(ActionPacket::decode)
                .consumerMainThread(ActionPacket::handle)
                .add();
    }

    public static void sendToServer(ClientAction action) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), new ActionPacket(action));
    }

    public record ActionPacket(ClientAction action) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeEnum(action);
        }

        public static ActionPacket decode(FriendlyByteBuf buf) {
            return new ActionPacket(buf.readEnum(ClientAction.class));
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sender = ctx.get().getSender();
            if (sender != null && sender.getServer() != null) {
                CloneManager.get(sender.getServer()).handleAction(sender, action);
            }
            ctx.get().setPacketHandled(true);
        }
    }
}
