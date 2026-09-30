package com.rlclones.clone;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;

import javax.annotation.Nullable;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * In-memory connection for clone players. Clones read the world through their own perception, so almost
 * everything the server sends is dropped - except sounds: exactly the sound packets a human's client would
 * receive (same audible range) are handed to the clone's ears.
 */
public class CloneConnection extends Connection {
    @Nullable
    private ClonePlayer owner;

    public CloneConnection() {
        super(PacketFlow.SERVERBOUND);
        this.channel = new EmbeddedChannel();
        this.address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }

    public void setOwner(@Nullable ClonePlayer owner) {
        this.owner = owner;
    }

    @Override
    public void send(Packet<?> packet) {
        send(packet, null);
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
        ClonePlayer ears = owner;
        if (ears == null) {
            return;
        }
        if (packet instanceof ClientboundSoundPacket sound) {
            ears.hear(sound.getSound().value(), sound.getX(), sound.getY(), sound.getZ(), -1);
        } else if (packet instanceof ClientboundSoundEntityPacket sound) {
            ears.hear(sound.getSound().value(), 0, 0, 0, sound.getId());
        }
    }

    @Override
    public void setReadOnly() {
    }

    @Override
    public void handleDisconnection() {
    }
}
