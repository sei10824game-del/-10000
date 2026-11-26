package com.rlclones.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.rlclones.Config;
import com.rlclones.client.CloneRenderDetails;
import com.rlclones.client.RenderDetailConfig;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public abstract class CloneShadowDetailMixin {
    @Inject(method="renderShadow",at=@At("HEAD"),cancellable=true)
    private static void rlclones$shadow(PoseStack pose,MultiBufferSource buffers,Entity entity,float weight,float partial,LevelReader level,float radius,CallbackInfo ci){
        if(CloneRenderDetails.distant(entity,Config.get(RenderDetailConfig.SHADOW_DISTANCE,32.0)))ci.cancel();
    }
}
