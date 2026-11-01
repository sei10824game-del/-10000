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
        CHANNEL.messageBuilder(SettingsPacket.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SettingsPacket::encode)
                .decoder(SettingsPacket::decode)
                .consumerMainThread(SettingsPacket::handle)
                .add();
    }

    /** Server -> client: the switches and the clone-mob entity ids the settings screen shows. */
    public record SettingsPacket(boolean[] switches, java.util.List<String> mobTypes) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(switches.length);
            for (boolean b : switches) {
                buf.writeBoolean(b);
            }
            buf.writeCollection(mobTypes, FriendlyByteBuf::writeUtf);
        }

        public static SettingsPacket decode(FriendlyByteBuf buf) {
            boolean[] s = new boolean[buf.readVarInt()];
            for (int i = 0; i < s.length; i++) {
                s[i] = buf.readBoolean();
            }
            return new SettingsPacket(s, buf.readList(FriendlyByteBuf::readUtf));
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> com.rlclones.client.SettingsScreen.receive(this));
            ctx.get().setPacketHandled(true);
        }
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
