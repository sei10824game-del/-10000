package com.rlclones.ai.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Raw immutable geometry, never a sighting or learned fact. */
public interface EntityGeometry {
    record Sample(AABB box, Vec3 center, double radius) {
        public static Sample of(AABB box) {
            return new Sample(box,box.getCenter(),Math.max(box.getXsize(),box.getYsize())*.5);
        }
    }
    Sample rlclones$geometry(AABB box);
    static Sample sample(Entity entity) {
        AABB box=entity.getBoundingBox();
        return entity instanceof EntityGeometry shared?shared.rlclones$geometry(box):Sample.of(box);
    }
}
