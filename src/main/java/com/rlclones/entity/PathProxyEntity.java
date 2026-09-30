package com.rlclones.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;

/**
 * Invisible player-sized helper that is never added to the world. Clones use its navigation to run the
 * vanilla path finder (players have no path finder of their own); the resulting path is then walked with
 * ordinary player movement inputs.
 */
public class PathProxyEntity extends PathfinderMob {
    public PathProxyEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setNoAi(true);
        this.setInvisible(true);
        this.setSilent(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.1)
                .add(Attributes.FOLLOW_RANGE, 64.0);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        GroundPathNavigation nav = new GroundPathNavigation(this, level);
        nav.setCanOpenDoors(true);
        nav.setCanPassDoors(true);
        nav.setCanFloat(true);
        return nav;
    }

    @Override
    public void tick() {
        // Never meant to exist in the world.
        this.discard();
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }
}
