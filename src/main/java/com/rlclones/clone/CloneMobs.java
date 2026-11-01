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

    /** Soul -> body (rebuilt after a restart from the soul UUID kept in each body's own saved data). */
    private static final java.util.Map<UUID, UUID> BODY = new java.util.HashMap<>();
    /** Persistent-data key on a baby body: the game time it grows up at (types with no baby form of their own, like the zombie, grow by this). */
    public static final String GROW_AT = "rlclones_grow_at";
    /** A baby clone mob grows up after this many ticks (the animals' 20 minutes). */
    public static final long GROW_TICKS = 24000;

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

    /** Tie a body to its soul: the soul does the thinking and the walking, the body (no AI of its own) is moved to wherever the soul is. */
    public static void link(Mob body, ClonePlayer soul) {
        body.getPersistentData().putUUID(SOUL, soul.getUUID());
        body.getPersistentData().putBoolean(SIDE, true);
        body.setNoAi(true);
        body.setPersistenceRequired();
        soul.setInvisible(true); // (armour still shows: the body wears none)
        BODY.put(soul.getUUID(), body.getUUID());
    }

    /** Bodies loaded from disk whose soul is not found yet (clones log back in after the world loads): soul -> (body, give-up time). */
    private static final java.util.Map<UUID, Mob> LOADED = new java.util.HashMap<>();
    private static final java.util.Map<UUID, Long> LOADED_UNTIL = new java.util.HashMap<>();

    /** A body comes back from the world data: it is tied to its soul again as soon as that clone is logged in (the link is the soul's UUID in the body's own data). */
    @SubscribeEvent
    public static void onBodyLoaded(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() && !event.getLevel().isClientSide && event.getEntity() instanceof Mob mob && mob.getPersistentData().hasUUID(SOUL)) {
            registerLoaded(mob, event.getLevel().getGameTime());
        }
    }

    public static void registerLoaded(Mob mob, long now) {
        UUID soul = mob.getPersistentData().getUUID(SOUL);
        LOADED.put(soul, mob);
        LOADED_UNTIL.put(soul, now + 2400); // (two minutes for the clones to log in; then the body is an orphan)
    }

    /** Tests: what a restart does. */
    public static void forgetLinks() {
        BODY.clear();
    }

    /** Tie the loaded bodies to their souls; an orphan (no such clone any more) is removed. */
    public static void relinkLoaded(net.minecraft.server.MinecraftServer server, long now) {
        var it = LOADED.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            ClonePlayer soul = CloneManager.get(server).byId(e.getKey());
            Mob body = e.getValue();
            if (soul != null && body.isAlive()) {
                link(body, soul);
                it.remove();
                LOADED_UNTIL.remove(e.getKey());
            } else if (!body.isAlive() || now > LOADED_UNTIL.getOrDefault(e.getKey(), 0L)) {
                if (body.isAlive()) {
                    body.discard();
                }
                it.remove();
                LOADED_UNTIL.remove(e.getKey());
            }
        }
    }

    /** Every tick: each body goes to its soul's place and facing; a body or soul that is gone takes the other with it. */
    @SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        if (!LOADED.isEmpty() && event.getServer().getTickCount() % 20 == 0) {
            relinkLoaded(event.getServer(), event.getServer().overworld().getGameTime());
        }
        if (BODY.isEmpty()) {
            return;
        }
        var it = BODY.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            ClonePlayer soul = CloneManager.peek() == null ? null : CloneManager.peek().byId(e.getKey());
            if (soul == null) {
                it.remove();
                continue;
            }
            Mob body = bodyOf(soul);
            if (body != null && !soul.isAlive()) {
                body.kill(); // (the soul died: so does the body)
                it.remove();
                continue;
            }
            if (body == null) {
                if (soul.level() instanceof ServerLevel l && l.getEntity(e.getValue()) == null && !l.isLoaded(soul.blockPosition())) {
                    continue; // (the body's chunk is not loaded: wait)
                }
                it.remove();
                soul.kill(); // (the body died or was removed)
                continue;
            }
            body.moveTo(soul.getX(), soul.getY(), soul.getZ(), soul.getYRot(), soul.getXRot());
            body.setYHeadRot(soul.getYHeadRot());
            body.setYBodyRot(soul.yBodyRot);
            body.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            body.fallDistance = 0;
        }
    }

    /** Blows that land on the body hurt the soul (its health, hunger and armour are the real ones); the body itself takes none. */
    @SubscribeEvent
    public static void onBodyAttacked(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
        ClonePlayer soul = soulOf(event.getEntity());
        if (soul != null && soul.isAlive() && !event.isCanceled()) {
            event.setCanceled(true);
            soul.hurt(event.getSource(), event.getAmount());
            if (!soul.isAlive()) {
                event.getEntity().kill();
            }
        }
    }

    /** The body of this soul (null = a plain clone, or its body is not loaded). */
    @javax.annotation.Nullable
    public static Mob bodyOf(net.minecraft.world.entity.player.Player p) {
        UUID id = BODY.get(p.getUUID());
        return id != null && p.level() instanceof ServerLevel l && l.getEntity(id) instanceof Mob m && m.isAlive() ? m : null;
    }

    /** Hands to mine, build, open doors and craft: a plain clone, or a clone mob whose body is man-shaped (zombie, skeleton, illager, villager, piglin, witch). */
    public static boolean canUseHands(net.minecraft.world.entity.player.Player p) {
        Mob b = bodyOf(p);
        return b == null || b instanceof net.minecraft.world.entity.monster.Zombie || b instanceof net.minecraft.world.entity.monster.AbstractSkeleton
                || b instanceof net.minecraft.world.entity.monster.AbstractIllager || b instanceof net.minecraft.world.entity.npc.AbstractVillager
                || b instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin || b instanceof net.minecraft.world.entity.monster.Witch;
    }

    /** A blow of the soul is a blow of the body (its own damage and effects: a husk's hunger, a spider's poison...); a body with no attack of its own leaves it to the soul's fist. */
    public static boolean bodyStrike(net.minecraft.world.entity.player.Player p, Entity target) {
        Mob b = bodyOf(p);
        if (b == null || b.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) == null) {
            return false;
        }
        b.doHurtTarget(target);
        return true;
    }

    /** Clone mobs breed with their own kind only (a plain clone with a plain clone). */
    public static boolean sameKind(net.minecraft.world.entity.player.Player a, net.minecraft.world.entity.player.Player b) {
        Mob x = bodyOf(a);
        Mob y = bodyOf(b);
        return x == null ? y == null : y != null && x.getType() == y.getType();
    }

    /** The child of two clone mobs: a body of the same type, a baby if the type has a baby form (it grows up with time), else a grown one. */
    public static Mob birth(Mob parent, ClonePlayer childSoul, Vec3 pos) {
        if (!(parent.getType().create(parent.level()) instanceof Mob kid) || !(parent.level() instanceof ServerLevel level)) {
            return null;
        }
        kid.moveTo(pos.x, pos.y, pos.z, parent.getYRot(), 0f);
        kid.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(pos)), MobSpawnType.BREEDING, null, null);
        kid.setBaby(true); // (no baby form: nothing changes and it is born grown)
        if (kid.isBaby() && !(kid instanceof net.minecraft.world.entity.AgeableMob)) {
            kid.getPersistentData().putLong(GROW_AT, level.getGameTime() + GROW_TICKS);
        }
        link(kid, childSoul);
        level.addFreshEntity(kid);
        return kid;
    }

    /** A baby with no growth of its own (zombie...) grows up when its time comes; animals grow by themselves. */
    @SubscribeEvent
    public static void onGrowTick(net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent event) {
        if (event.getEntity() instanceof Mob m && !m.level().isClientSide && m.tickCount % 20 == 0 && m.getPersistentData().contains(GROW_AT)) {
            grow(m, m.level().getGameTime());
        }
    }

    public static void grow(Mob m, long now) {
        if (now >= m.getPersistentData().getLong(GROW_AT)) {
            m.getPersistentData().remove(GROW_AT);
            m.setBaby(false);
        }
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
        link(body, soul);
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

    /** Persistent-data key: this mob is on the players' side (clone mobs and what they summon). */
    public static final String SIDE = "rlclones_players_side";

    /** Side hooks on (they were off once to bisect learnsEnemyRange...; passes with them off, 2 fails with them on: watch it). */
    private static final boolean SIDE_HOOKS = true;

    public static boolean playerSide(Entity e) {
        return e != null && e.getPersistentData().getBoolean(SIDE);
    }

    /** A zombie that is a clone mob calls for help: the reinforcement it gets (made here, so no other mob can be mistaken for it) is on the players' side. */
    @SubscribeEvent
    public static void onZombieAid(net.minecraftforge.event.entity.living.ZombieEvent.SummonAidEvent event) {
        if (SIDE_HOOKS && soulOf(event.getEntity()) != null && event.getCustomSummonedAid() == null) {
            var z = new net.minecraft.world.entity.monster.Zombie(event.getLevel());
            z.getPersistentData().putBoolean(SIDE, true);
            event.setCustomSummonedAid(z);
        }
    }

    /** What a clone mob summons (a vex of its owner, zombie reinforcements) is on the players' side - and, if set, a clone mob too. */
    @SubscribeEvent
    public static void onSummonedJoin(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (!SIDE_HOOKS || !(event.getEntity() instanceof Mob mob) || event.loadedFromDisk() || event.getLevel().isClientSide || mob.getServer() == null || mob.getPersistentData().hasUUID(SOUL)) {
            return;
        }
        boolean vex = mob instanceof net.minecraft.world.entity.monster.Vex v && soulOf(v.getOwner()) != null;
        if (vex) {
            mob.getPersistentData().putBoolean(SIDE, true);
        }
        if (!playerSide(mob)) {
            return;
        }
        CloneManager m = CloneManager.get(mob.getServer());
        if (SYNC_READY && m.summonedAsClone()) {
            ClonePlayer soul = m.summon(null, (ServerLevel) event.getLevel(), mob.position(), mob.getYRot());
            if (soul != null) {
                link(mob, soul);
            }
        }
    }

    /** Hostile mobs go for clone mobs too, as they do for players (the body is a Mob, which their player-only goal skips). */
    @SubscribeEvent
    public static void onHostileJoin(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof Mob mob && mob instanceof net.minecraft.world.entity.monster.Enemy && !event.getLevel().isClientSide
                && !playerSide(mob)) {
            mob.targetSelector.addGoal(3, new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(mob, Mob.class, 10, true, false,
                    e -> playerSide(e) && soulOf(e) != null));
        }
    }

    /** Peace with the kind: a clone mob (its body or its soul) is not a target for mobs of its own type. */
    private static boolean peaceWith(Mob attacker, Entity target) {
        Mob body = target instanceof Mob m ? m : target instanceof net.minecraft.world.entity.player.Player p ? bodyOf(p) : null;
        return body != null && body.getType() == attacker.getType();
    }

    /** Players' side never turns on the players or on each other (a vex does not go rogue); the hostile mobs' side of it is stage 4. */
    @SubscribeEvent
    public static void onTarget(net.minecraftforge.event.entity.living.LivingChangeTargetEvent event) {
        if (CloneManager.mobPeace() && event.getEntity() instanceof Mob attacker && !playerSide(attacker) && event.getNewTarget() != null
                && peaceWith(attacker, event.getNewTarget())) {
            event.setCanceled(true);
            return;
        }
        if (SIDE_HOOKS && playerSide(event.getEntity()) && event.getNewTarget() != null
                && (event.getNewTarget() instanceof net.minecraft.world.entity.player.Player || playerSide(event.getNewTarget()))) {
            event.setCanceled(true);
        }
    }

    /** Stage 3 (body <-> soul sync) is in: natural spawns of listed types become clone mobs. */
    private static final boolean SYNC_READY = true;

    /** A mob of a listed type spawns by itself (world, chunk generation, structures...): it becomes a clone mob. */
    @SubscribeEvent
    public static void onNaturalSpawn(net.minecraftforge.event.entity.living.MobSpawnEvent.FinalizeSpawn event) {
        MobSpawnType t = event.getSpawnType();
        Mob mob = event.getEntity();
        if (!SYNC_READY || mob.level().isClientSide || mob.getServer() == null || mob.getPersistentData().hasUUID(SOUL)
                || t == MobSpawnType.SPAWN_EGG || t == MobSpawnType.COMMAND || t == MobSpawnType.DISPENSER || t == MobSpawnType.MOB_SUMMONED) {
            return;
        }
        var key = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(mob.getType());
        CloneManager m = CloneManager.get(mob.getServer());
        if (key == null || !m.cloneMobTypes().contains(key.toString())) {
            return;
        }
        ClonePlayer soul = m.summon(null, event.getLevel().getLevel(), mob.position(), mob.getYRot());
        if (soul != null) {
            link(mob, soul); // (a clone mob never despawns: its soul would be left behind)
        }
    }

    /** The body is gone for good (discarded, e.g. despawned): its soul is forgotten with it. */
    @SubscribeEvent
    public static void onBodyGone(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        Entity e = event.getEntity();
        var why = e.getRemovalReason();
        if (why != Entity.RemovalReason.DISCARDED) { // (killed: the death handler above deals with the soul)
            return;
        }
        ClonePlayer soul = soulOf(e);
        if (soul != null) {
            CloneManager.get(e.getServer()).remove(soul, true, Component.literal("its body is gone"));
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
