package com.rlclones.clone;

import com.mojang.authlib.GameProfile;
import com.rlclones.ai.CloneController;
import com.rlclones.ai.brain.Brain;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.damagesource.DamageSource;

import javax.annotation.Nullable;

/**
 * A clone is a real {@link ServerPlayer}: same attributes, hunger, reach, mining speed, attack cooldown,
 * inventory, advancements and death rules as a human. The only difference is that its inputs come from
 * {@link CloneController} instead of a network client.
 */
public class ClonePlayer extends ServerPlayer {
    private Brain brain;
    private final CloneController controller;
    private boolean aiEnabled = true;

    public ClonePlayer(MinecraftServer server, ServerLevel level, GameProfile profile, Brain brain) {
        super(server, level, profile);
        this.brain = brain;
        this.controller = new CloneController(this);
        this.setMaxUpStep(0.6F);
    }

    public Brain getCloneBrain() {
        return brain;
    }

    public void setCloneBrain(Brain brain) {
        this.brain = brain;
    }

    public CloneController controller() {
        return controller;
    }

    /** Show every skin layer, like a player with default skin customisation. */
    public void showAllSkinLayers() {
        this.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f);
    }

    @Override
    public void tick() {
        if (this.tickCount % 10 == 0) {
            this.connection.resetPosition();
        }
        this.serverLevel().getChunkSource().move(this);
        super.tick();
        if (this.isAlive() && !this.isRemoved()) {
            try {
                if (aiEnabled) {
                    controller.tick();
                } else {
                    controller.passiveTick();
                }
            } catch (RuntimeException e) {
                CloneManager.reportError(this, e);
            }
        }
        this.doTick();
    }

    /** Called for every sound packet a human client at this position would have received. */
    public void hear(SoundEvent sound, double x, double y, double z, int entityId) {
        if (!this.isAlive() || this.isRemoved()) {
            return;
        }
        try {
            controller.hear(sound, x, y, z, entityId);
        } catch (RuntimeException e) {
            CloneManager.reportError(this, e);
        }
    }

    public boolean isAiEnabled() {
        return aiEnabled;
    }

    /** With AI disabled the clone still sees and learns by watching, but takes no actions of its own. */
    public void setAiEnabled(boolean enabled) {
        this.aiEnabled = enabled;
        if (!enabled) {
            this.zza = 0;
            this.xxa = 0;
            this.setJumping(false);
            this.setSprinting(false);
        }
    }

    /**
     * A human's fall distance / fall damage is computed when its client sends movement packets
     * ({@code doCheckFallDamage}); the server-side override is a no-op. Clones move on the server,
     * so route the physics callback to the same player code path: falls hurt, crits and elytra work.
     */
    @Override
    protected void checkFallDamage(double dy, boolean onGround, BlockState state, BlockPos pos) {
        this.doCheckFallDamage(this.getDeltaMovement().x, dy, this.getDeltaMovement().z, onGround);
    }

    @Override
    public void die(DamageSource source) {
        try {
            controller.onDeath(source);
        } catch (RuntimeException e) {
            CloneManager.reportError(this, e);
        }
        super.die(source);
        MinecraftServer server = this.getServer();
        if (server != null) {
            CloneManager.get(server).onCloneDied(this);
        }
    }

    @Nullable
    @Override
    public Component getTabListDisplayName() {
        return Component.literal("[AI] ").withStyle(ChatFormatting.AQUA).append(Component.literal(getGameProfile().getName()).withStyle(ChatFormatting.WHITE));
    }
}
