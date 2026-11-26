package com.rlclones.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.rlclones.client.CloneRenderDetails;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CapeLayer.class)
public abstract class CloneCapeDetailMixin {
    @Inject(method="render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",at=@At("HEAD"),cancellable=true)
    private void rlclones$cape(PoseStack pose,MultiBufferSource buffers,int light,AbstractClientPlayer player,float limb,float amount,float partial,float age,float yaw,float pitch,CallbackInfo ci){
        if(CloneRenderDetails.cosmetic(player))ci.cancel();
    }
}
