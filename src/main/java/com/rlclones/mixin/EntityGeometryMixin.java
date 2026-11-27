package com.rlclones.mixin;

import com.rlclones.ai.util.EntityGeometry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Cache only the immutable AABB value currently supplied by an observer. */
@Mixin(Entity.class)
public abstract class EntityGeometryMixin implements EntityGeometry {
    @Unique private volatile EntityGeometry.Sample rlclones$sample;
    @Override public EntityGeometry.Sample rlclones$geometry(AABB box) {
        EntityGeometry.Sample current=rlclones$sample;
        if(current==null||current.box()!=box){
            current=EntityGeometry.Sample.of(box);
            rlclones$sample=current;
        }
        return current;
    }
}
