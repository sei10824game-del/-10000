package com.rlclones.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.rlclones.client.CloneRenderDetails;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.armortrim.ArmorTrim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import java.util.Optional;

/** Preserve armor geometry/material; only the distant decorative passes are omitted. No shared renderer state. */
@Mixin(HumanoidArmorLayer.class)
public abstract class CloneArmorDetailMixin {
    @Redirect(method="renderArmorPiece",at=@At(value="INVOKE",target="Lnet/minecraft/world/item/ItemStack;hasFoil()Z"))
    private boolean rlclones$armorGlint(ItemStack stack,PoseStack pose,MultiBufferSource buffers,LivingEntity entity,EquipmentSlot slot,int light,HumanoidModel<?> model){
        return stack.hasFoil() && !CloneRenderDetails.cosmetic(entity);
    }
    @Redirect(method="renderArmorPiece",at=@At(value="INVOKE",target="Lnet/minecraft/world/item/armortrim/ArmorTrim;getTrim(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/item/ItemStack;)Ljava/util/Optional;"))
    private Optional<ArmorTrim> rlclones$armorTrim(RegistryAccess registry,ItemStack stack,PoseStack pose,MultiBufferSource buffers,LivingEntity entity,EquipmentSlot slot,int light,HumanoidModel<?> model){
        return CloneRenderDetails.cosmetic(entity)?Optional.empty():ArmorTrim.getTrim(registry,stack);
    }
}
