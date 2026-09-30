package com.rlclones.registry;

import com.rlclones.RLClones;
import com.rlclones.entity.PathProxyEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, RLClones.MODID);

    public static final RegistryObject<EntityType<PathProxyEntity>> PATH_PROXY = ENTITIES.register("path_proxy",
            () -> EntityType.Builder.<PathProxyEntity>of(PathProxyEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .noSummon()
                    .noSave()
                    .clientTrackingRange(0)
                    .build(new ResourceLocation(RLClones.MODID, "path_proxy").toString()));

    private ModEntities() {
    }

    public static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(PATH_PROXY.get(), PathProxyEntity.createAttributes().build());
    }
}
