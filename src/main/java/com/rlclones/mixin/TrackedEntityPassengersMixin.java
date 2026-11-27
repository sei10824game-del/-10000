package com.rlclones.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla tracking immediately iterates the indirect passenger iterable to calculate range.
 * Avoid creating a stream pipeline for the overwhelmingly common empty passenger list.
 * Mounted entities, all tracking distances, recipient selection and packets remain vanilla.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityPassengersMixin {
    @Redirect(method = "getEffectiveRange", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getIndirectPassengers()Ljava/lang/Iterable;"))
    private Iterable<Entity> rlclones$skipEmptyPassengerTraversal(Entity entity) {
        return entity.getPassengers().isEmpty() ? java.util.Collections.emptyList() : entity.getIndirectPassengers();
    }
}
