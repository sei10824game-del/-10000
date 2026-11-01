package com.rlclones.clone;

import com.rlclones.RLClones;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Clone mobs (plan.md 71): a spawn egg used after the V key makes a mob (the body) with a hidden clone (the soul) behind it.
 * Stage 1-2: the link body <-> soul (kept in the body's persistent data) and the summon; stage 3 (sync) and 4 (sides) follow.
 */
@Mod.EventBusSubscriber(modid = RLClones.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CloneMobs {
    /** Persistent-data key on the body: the soul's UUID. */
    public static final String SOUL = "rlclones_soul";
    /** Players whose next spawn egg makes a clone mob. */
    private static final Set<UUID> ARMED = new HashSet<>();

    private CloneMobs() {
    }

    /** V key: the next spawn egg this player uses makes a clone mob (pressed again: off). */
    public static void toggleArmed(ServerPlayer player) {
        boolean on = ARMED.add(player.getUUID());
        if (!on) {
            ARMED.remove(player.getUUID());
        }
        player.sendSystemMessage(Component.translatable(on ? "rlclones.msg.clonemob_on" : "rlclones.msg.clonemob_off"));
    }

    public static boolean isArmed(ServerPlayer player) {
        return ARMED.contains(player.getUUID());
    }

    /** The soul of this body (null = not a clone mob, or the soul is gone). */
    public static ClonePlayer soulOf(Entity body) {
        if (!(body instanceof Mob) || body.level().isClientSide || body.getServer() == null || !body.getPersistentData().hasUUID(SOUL)) {
            return null;
        }
        return CloneManager.get(body.getServer()).byId(body.getPersistentData().getUUID(SOUL));
    }

    /** Make the body for {@code type} at {@code pos} and a soul for it (null = could not). */
    public static Mob summon(@javax.annotation.Nullable ServerPlayer owner, ServerLevel level, EntityType<?> type, Vec3 pos) {
        if (!(type.create(level) instanceof Mob body)) {
            return null;
        }
        float yaw = owner == null ? 0f : owner.getYRot();
        body.moveTo(pos.x, pos.y, pos.z, yaw, 0f);
        body.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(pos)), MobSpawnType.SPAWN_EGG, null, null);
        ClonePlayer soul = CloneManager.get(level.getServer()).summon(owner, level, pos, yaw);
        if (soul == null) {
            return null;
        }
        body.getPersistentData().putUUID(SOUL, soul.getUUID());
        level.addFreshEntity(body);
        return body;
    }

    @SubscribeEvent
    public static void onEgg(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !ARMED.contains(player.getUUID())
                || !(event.getItemStack().getItem() instanceof SpawnEggItem egg)) {
            return;
        }
        event.setCanceled(true); // (the plain spawn is replaced)
        ARMED.remove(player.getUUID());
        ServerLevel level = player.serverLevel();
        BlockPos at = event.getPos().relative(event.getFace());
        Mob body = summon(player, level, egg.getType(event.getItemStack().getTag()), Vec3.atBottomCenterOf(at));
        if (body != null && !player.getAbilities().instabuild) {
            event.getItemStack().shrink(1);
        }
    }

    /** The body dies: so does its soul (the usual clone death follows). */
    @SubscribeEvent
    public static void onBodyDeath(LivingDeathEvent event) {
        ClonePlayer soul = soulOf(event.getEntity());
        if (soul != null && soul.isAlive()) {
            soul.kill();
        }
    }
}
