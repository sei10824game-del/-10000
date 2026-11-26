package com.rlclones.mixin;

import com.rlclones.client.CloneRenderDetails;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Let vanilla set every part per player, avoiding saved visibility flags or leaked model state. */
@Mixin(PlayerRenderer.class)
public abstract class CloneOuterLayerDetailMixin {
    @Redirect(method="setModelProperties",at=@At(value="INVOKE",target="Lnet/minecraft/client/player/AbstractClientPlayer;isModelPartShown(Lnet/minecraft/world/entity/player/PlayerModelPart;)Z"))
    private boolean rlclones$outerWear(AbstractClientPlayer player,PlayerModelPart part){
        return player.isModelPartShown(part) && !CloneRenderDetails.cosmetic(player);
    }
}
