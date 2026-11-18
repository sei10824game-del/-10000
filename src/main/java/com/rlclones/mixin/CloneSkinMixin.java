package com.rlclones.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.resources.ResourceLocation;
import com.rlclones.clone.CloneProfiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.SkinManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep synthetic UUIDs out of vanilla's asynchronous Mojang profile lookup. */
@Mixin(SkinManager.class)
public abstract class CloneSkinMixin {
    @Shadow @Final private MinecraftSessionService sessionService;

    @Shadow
    private ResourceLocation registerTexture(MinecraftProfileTexture texture, MinecraftProfileTexture.Type type,
                                             SkinManager.SkinTextureCallback callback) {
        throw new AssertionError("Mixin shadow");
    }

    @Inject(method = "registerSkins", at = @At("HEAD"), cancellable = true)
    private void rlclones$useSuppliedTextures(GameProfile profile, SkinManager.SkinTextureCallback callback,
                                            boolean requireSecure, CallbackInfo ci) {
        if (!CloneProfiles.isSynthetic(profile)) return;
        ci.cancel();
        CloneProfiles.recordAvoidedLookup();
        var textures = CloneProfiles.suppliedTextures(profile, sessionService, requireSecure);
        if (textures.isEmpty()) return; // PlayerInfo already provides a bundled default skin.
        Minecraft.getInstance().execute(() -> textures.forEach((type, texture) ->
                registerTexture(texture, type, callback)));
    }
}
