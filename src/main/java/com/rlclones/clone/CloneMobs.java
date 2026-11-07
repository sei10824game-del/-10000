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
import java.util.List;
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

    /** Persistent-data key: how many blocks this big body has dug out for room so far. */
    private static final String DUG = "rlclones_dug";

    /** Soul -> body (rebuilt after a restart from the soul UUID kept in each body's own saved data). */
    private static final java.util.Map<UUID, UUID> BODY = new java.util.HashMap<>();
    /** The other bodies of a chimera (cross-bred clone mob): soul -> bodies stacked on the main one. */
    private static final java.util.Map<UUID, java.util.List<UUID>> EXTRAS = new java.util.HashMap<>();
    /** Persistent-data key on a chimera's extra bodies (the main body holds the health). */
    public static final String EXTRA = "rlclones_extra_body";
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

    /** A flying body: the soul flies (creative flight, so it hangs in the air and goes where it is sent); a phasing one (vex) goes through blocks. */
    public static void giveBodyMovement(ClonePlayer soul, Mob body) {
        BodyTraits t = BodyTraits.of(body);
        soul.phasing = t.phases();
        var ab = soul.getAbilities();
        if (t.flies() != ab.mayfly || t.flies() != ab.flying) {
            ab.mayfly = t.flies();
            ab.flying = t.flies();
            soul.onUpdateAbilities();
        }
    }

    /** The soul's hit box follows the biggest of its bodies; where that does not fit it is lifted clear of the blocks. */
    public static void syncDimensions(ClonePlayer soul) {
        var bodies = bodiesOf(soul);
        if (bodies.isEmpty()) {
            return;
        }
        float w = 0f;
        float h = 0f;
        for (Mob m : bodies) {
            w = Math.max(w, m.getBbWidth());
            h = Math.max(h, m.getBbHeight());
        }
        var dims = soul.bodyDims;
        if (dims != null && Math.abs(dims.width - w) < 0.01f && Math.abs(dims.height - h) < 0.01f) {
            return;
        }
        soul.bodyDims = net.minecraft.world.entity.EntityDimensions.scalable(w, h);
        soul.refreshDimensions();
        for (int i = 0; i < 16 && !soul.level().noCollision(soul); i++) {
            soul.setPos(soul.getX(), soul.getY() + 0.5, soul.getZ()); // (a big body appeared inside a tree or a wall: up out of it)
        }
    }

    /** Out of sight (an effect, not the flag: vanilla clears the flag every tick when no effect backs it), without particles. */
    public static void hide(ClonePlayer soul) {
        var fx = soul.getEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY);
        if (fx == null || !fx.isInfiniteDuration()) {
            soul.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY,
                    net.minecraft.world.effect.MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
        }
    }

    private static final String NO_PUSH_TEAM = "rlclones_souls";

    /** Body and soul stand in one place and must not shove each other about: one team with collisions off. */
    private static void noPush(net.minecraft.server.MinecraftServer server, ClonePlayer soul, Mob body) {
        var board = server.getScoreboard();
        var team = board.getPlayerTeam(NO_PUSH_TEAM);
        if (team == null) {
            team = board.addPlayerTeam(NO_PUSH_TEAM);
            team.setCollisionRule(net.minecraft.world.scores.Team.CollisionRule.NEVER);
        }
        board.addPlayerToTeam(soul.getScoreboardName(), team);
        board.addPlayerToTeam(body.getStringUUID(), team);
    }

    /** Tie a body to its soul: the soul does the thinking and the walking, the body (no AI of its own) is moved to wherever the soul is. */
    public static void link(Mob body, ClonePlayer soul) {
        body.getPersistentData().putUUID(SOUL, soul.getUUID());
        body.getPersistentData().putBoolean(SIDE, true);
        body.setNoAi(true);
        body.setPersistenceRequired();
        giveBodyMovement(soul, body);
        hide(soul); // (armour still shows: the body wears none)
        if (soul.getServer() != null) {
            noPush(soul.getServer(), soul, body);
        }
        BODY.put(soul.getUUID(), body.getUUID());
    }

    /** A chimera's further body: stacked on the main one, no AI, its blows and damage go through the main one. */
    public static void linkExtra(Mob body, ClonePlayer soul) {
        body.getPersistentData().putUUID(SOUL, soul.getUUID());
        body.getPersistentData().putBoolean(SIDE, true);
        body.getPersistentData().putBoolean(EXTRA, true);
        body.setNoAi(true);
        body.setPersistenceRequired();
        if (soul.getServer() != null) {
            noPush(soul.getServer(), soul, body);
        }
        var list = EXTRAS.computeIfAbsent(soul.getUUID(), k -> new java.util.ArrayList<>());
        if (!list.contains(body.getUUID())) {
            list.add(body.getUUID());
        }
        giveBodyMovement(soul, body);
    }

    /** All the bodies of this soul: the main one first, then a chimera's others (empty = a plain clone). */
    public static java.util.List<Mob> bodiesOf(net.minecraft.world.entity.player.Player p) {
        Mob main = bodyOf(p);
        if (main == null) {
            return java.util.List.of();
        }
        java.util.List<Mob> out = new java.util.ArrayList<>();
        out.add(main);
        var extras = EXTRAS.get(p.getUUID());
        if (extras != null && p.level() instanceof ServerLevel l) {
            for (UUID id : extras) {
                if (l.getEntity(id) instanceof Mob m && m.isAlive()) {
                    out.add(m);
                }
            }
        }
        return out;
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
        UUID id = mob.getUUID(); // (a chimera's bodies each wait for the one soul)
        LOADED.put(id, mob);
        LOADED_UNTIL.put(id, now + 2400); // (two minutes for the clones to log in; then the body is an orphan)
    }

    /** Tests: take every clone mob (body and soul) and every clone equipment out of the world. */
    public static void clearAll(net.minecraft.server.MinecraftServer server) {
        CloneManager m = CloneManager.get(server);
        for (UUID soulId : new java.util.ArrayList<>(BODY.keySet())) {
            ClonePlayer soul = m.byId(soulId);
            for (ServerLevel l : server.getAllLevels()) {
                if (l.getEntity(BODY.get(soulId)) instanceof Mob b) {
                    b.discard();
                }
            }
            if (soul != null) {
                m.remove(soul, true, Component.literal("test over"));
            }
        }
        for (var list : EXTRAS.values()) {
            for (UUID id : list) {
                for (ServerLevel l : server.getAllLevels()) {
                    if (l.getEntity(id) != null) {
                        l.getEntity(id).discard();
                    }
                }
            }
        }
        EXTRAS.clear();
        BODY.clear();
        BORROWED.clear();
        BORROWED_TARGET.clear();
        for (ClonePlayer c : new java.util.ArrayList<>(m.clones())) {
            if (m.isEquipment(c.getUUID())) {
                m.remove(c, true, Component.literal("test over"));
            }
        }
    }

    /** Tests: what a restart does. */
    public static void forgetLink(UUID soul) {
        BODY.remove(soul);
    }

    /** Tie the loaded bodies to their souls; an orphan (no such clone any more) is removed. */
    public static void relinkLoaded(net.minecraft.server.MinecraftServer server, long now) {
        var it = LOADED.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Mob body = e.getValue();
            ClonePlayer soul = CloneManager.get(server).byId(body.getPersistentData().getUUID(SOUL));
            if (soul != null && body.isAlive()) {
                if (body.getPersistentData().getBoolean(EXTRA)) {
                    linkExtra(body, soul);
                } else {
                    link(body, soul);
                }
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

    /** Bodies whose own AI is lent for an attack: body -> game time the loan ends. */
    private static final java.util.Map<UUID, Long> BORROWED = new java.util.HashMap<>();
    private static final java.util.Map<UUID, UUID> BORROWED_TARGET = new java.util.HashMap<>();

    public static boolean isBorrowed(Mob body) {
        return BORROWED.containsKey(body.getUUID());
    }

    /**
     * Lend the body its own AI against {@code target} for {@code ticks}: whatever its goals do (a mod's wind-up area attack, a leap, a
     * summoning...) happens as it would for the mob itself. The soul follows the body meanwhile (see the tick).
     */
    public static void borrow(Mob body, net.minecraft.world.entity.LivingEntity target, long now, int ticks) {
        BORROWED.put(body.getUUID(), now + ticks);
        BORROWED_TARGET.put(body.getUUID(), target.getUUID());
        body.setNoAi(false);
        body.setTarget(target);
    }

    private static void release(Mob body) {
        BORROWED.remove(body.getUUID());
        BORROWED_TARGET.remove(body.getUUID());
        body.setTarget(null);
        body.getNavigation().stop();
        body.setNoAi(true);
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
            long nowT = soul.level().getGameTime();
            Entity raw = soul.level() instanceof ServerLevel rl ? rl.getEntity(e.getValue()) : null;
            if (soul.isAlive() && raw instanceof Mob rm && !rm.isRemoved() && rm.isDeadOrDying()) {
                // the body is dead or dying (it may rise again: a mutant zombie...): the soul waits for it
                if (nowT - DYING.computeIfAbsent(soul.getUUID(), k -> nowT) > REVIVE_WAIT) {
                    soulDies(soul);
                    it.remove();
                }
                continue;
            }
            if (raw instanceof Mob rm2 && rm2.isAlive()) {
                DYING.remove(soul.getUUID()); // (it rose again)
                DYING_SRC.remove(soul.getUUID());
            } else if (soul.isAlive() && DYING.containsKey(soul.getUUID())) { // died and is gone
                soulDies(soul);
                it.remove();
                continue;
            }
            Mob body = bodyOf(soul);
            if (body != null && !soul.isAlive()) {
                for (Mob any : rawBodies(soul)) {
                    any.kill(); // (the soul died: so do the bodies)
                }
                EXTRAS.remove(soul.getUUID());
                it.remove();
                continue;
            }
            if (body == null) {
                if (soul.level() instanceof ServerLevel l && l.getEntity(e.getValue()) == null && !l.isLoaded(soul.blockPosition())) {
                    continue; // (the body's chunk is not loaded: wait)
                }
                it.remove();
                com.rlclones.RLClones.LOGGER.info("CLONE-MOB body gone for {}: {} at {}", soul.getGameProfile().getName(),
                        soul.level() instanceof ServerLevel gl && gl.getEntity(e.getValue()) != null ? "dead" : "not found", soul.blockPosition().toShortString());
                soul.kill(); // (the body died or was removed)
                continue;
            }
            if (isBorrowed(body)) {
                var level = soul.serverLevel();
                var target = level.getEntity(BORROWED_TARGET.get(body.getUUID()));
                if (level.getGameTime() >= BORROWED.get(body.getUUID()) || !(target instanceof net.minecraft.world.entity.LivingEntity le) || !le.isAlive()) {
                    release(body); // (the loan is over: the body is where the soul will be put now)
                } else {
                    body.setTarget(le);
                    soul.teleportTo(level, body.getX(), body.getY(), body.getZ(), body.getYRot(), body.getXRot()); // the soul follows
                    soul.setHealth(Math.max(0.5f, Math.min(soul.getMaxHealth(), body.getHealth() / Math.max(1f, body.getMaxHealth()) * soul.getMaxHealth())));
                    continue;
                }
            }
            for (Mob extra : bodiesOf(soul)) { // (all the bodies of a chimera stand in one place: the look is the two pulled together)
                if (extra != body) {
                    extra.moveTo(soul.getX(), soul.getY(), soul.getZ(), soul.getYRot(), soul.getXRot());
                    extra.setYHeadRot(soul.getYHeadRot());
                    extra.setYBodyRot(soul.yBodyRot);
                    extra.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                    extra.fallDistance = 0;
                    extra.setHealth(extra.getMaxHealth());
                }
            }
            body.moveTo(soul.getX(), soul.getY(), soul.getZ(), soul.getYRot(), soul.getXRot());
            body.setYHeadRot(soul.getYHeadRot());
            body.setYBodyRot(soul.yBodyRot);
            body.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            body.fallDistance = 0;
            if (soul.tickCount % 20 == 0 && BodyTraits.of(body).sun()) {
                var helmet = soul.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
                if (body.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD) != helmet) {
                    body.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, helmet); // (the same stack: the sun wears the one helmet; a sun-sensitive body in a helmet does not burn)
                }
            }
            if (soul.tickCount % 100 == 0) {
                hide(soul);
                giveBodyMovement(soul, body);
            }
            if (soul.tickCount % 20 == 0) {
                syncDimensions(soul);
            }
            // the body holds the health: the soul (whose health the clone's own sense of danger reads) shows the same share of it
            float share = Math.max(0.02f, body.getHealth() / Math.max(1f, body.getMaxHealth()));
            soul.setHealth(Math.max(0.5f, Math.min(soul.getMaxHealth(), share * soul.getMaxHealth())));
            if (body.getHealth() < body.getMaxHealth() && soul.getFoodData().getFoodLevel() >= 18 && body.tickCount % 80 == 0) {
                body.heal(1f); // natural healing of a well-fed clone, on the body
            }
            if (body.tickCount % 10 == 0) {
                widenWay(soul, body);
            }
        }
    }

    /**
     * The body holds the health (plan: 71 follow-up). Damage that lands on the soul goes to the body instead (blows, a fall, magic, hunger);
     * what the surroundings do (fire, drowning, walls...) the body feels itself, so the soul takes none of it. An explosion hits both
     * of them where they stand: the body's own share is enough.
     */
    @SubscribeEvent
    public static void onSoulHurt(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
        if (!(event.getEntity() instanceof ClonePlayer soul) || event.isCanceled()) {
            return;
        }
        Mob body = bodyOf(soul);
        var src = event.getSource();
        if (body == null || src.is(net.minecraft.world.damagesource.DamageTypes.GENERIC_KILL) || src.is(net.minecraft.world.damagesource.DamageTypes.FELL_OUT_OF_WORLD)) {
            return;
        }
        event.setCanceled(true);
        boolean surroundings = src.is(net.minecraft.tags.DamageTypeTags.IS_FIRE) || src.is(net.minecraft.world.damagesource.DamageTypes.DROWN)
                || src.is(net.minecraft.world.damagesource.DamageTypes.IN_WALL) || src.is(net.minecraft.world.damagesource.DamageTypes.CRAMMING)
                || src.is(net.minecraft.world.damagesource.DamageTypes.CACTUS) || src.is(net.minecraft.world.damagesource.DamageTypes.SWEET_BERRY_BUSH)
                || src.is(net.minecraft.world.damagesource.DamageTypes.FREEZE) || src.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION);
        if (!surroundings || src.getEntity() != null && src.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)) {
            body.hurt(src, event.getAmount());
        }
    }

    /** A fall hurts the soul only as far as the body's kind is hurt by one. */
    @SubscribeEvent
    public static void onSoulFall(net.minecraftforge.event.entity.living.LivingFallEvent event) {
        if (event.getEntity() instanceof ClonePlayer soul && bodyOf(soul) != null && BodyTraits.of(soul).fallImmune()) {
            event.setDamageMultiplier(0f);
        }
    }

    /** A body clearly bigger than the soul (the soul is player-sized: 0.6 x 1.8; zombies, skeletons and villagers, 2.0 tall, count as that size) that the soul cannot get on with, for want of room: the blocks its box overlaps are dug out, a few at a time. */
    public static int widenWay(ClonePlayer soul, Mob body) {
        if ((body.getBbWidth() <= 0.8f && body.getBbHeight() <= 2.1f) || !canUseHands(soul) || soul.controller() == null || soul.controller().motor().stuckCount() < 2) {
            return 0;
        }
        int dug = body.getPersistentData().getInt(DUG);
        if (dug >= 24 || !(soul.level() instanceof ServerLevel level)) {
            return 0;
        }
        net.minecraft.world.phys.AABB box = body.getBoundingBox().deflate(0.01);
        Vec3 goal = soul.controller().motor().recentGoal();
        if (goal != null) { // (the soul's own box is the body's now: what blocks it is the way ahead, a little in front of it)
            Vec3 d = new Vec3(goal.x - soul.getX(), 0, goal.z - soul.getZ());
            if (d.lengthSqr() > 1e-4) {
                box = box.move(d.normalize().scale(0.7));
            }
        }
        int made = 0;
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            var st = level.getBlockState(p);
            var shape = st.getCollisionShape(level, p);
            if (shape.isEmpty() || !shape.bounds().move(p).intersects(box) || st.getDestroySpeed(level, p) < 0 || st.getDestroySpeed(level, p) > 25
                    || st.hasBlockEntity() || !soul.controller().safeToDig(p) || com.rlclones.ai.BlockClaims.takenByOther(p, soul.getUUID(), level.getGameTime())) {
                continue;
            }
            level.destroyBlock(p, true, soul);
            if (++made >= 6 || dug + made >= 24) {
                break;
            }
        }
        body.getPersistentData().putInt(DUG, dug + made);
        return made;
    }

    /** The body of this soul (null = a plain clone, or its body is not loaded). */
    @javax.annotation.Nullable
    public static Mob bodyOf(net.minecraft.world.entity.player.Player p) {
        UUID id = BODY.get(p.getUUID());
        return id != null && p.level() instanceof ServerLevel l && l.getEntity(id) instanceof Mob m && m.isAlive() ? m : null;
    }

    /** A chimera's extra body takes no harm of its own: what the surroundings do it, the main body feels; what hits it goes to the main body. */
    @SubscribeEvent
    public static void onExtraHurt(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
        if (!(event.getEntity() instanceof Mob extra) || !extra.getPersistentData().getBoolean(EXTRA) || event.isCanceled()) {
            return;
        }
        ClonePlayer soul = soulOf(extra);
        var src = event.getSource();
        if (soul == null || src.is(net.minecraft.world.damagesource.DamageTypes.GENERIC_KILL) || src.is(net.minecraft.world.damagesource.DamageTypes.FELL_OUT_OF_WORLD)) {
            return;
        }
        event.setCanceled(true);
        Mob main = bodyOf(soul);
        boolean surroundings = src.is(net.minecraft.tags.DamageTypeTags.IS_FIRE) || src.is(net.minecraft.world.damagesource.DamageTypes.DROWN)
                || src.is(net.minecraft.world.damagesource.DamageTypes.IN_WALL) || src.is(net.minecraft.world.damagesource.DamageTypes.CRAMMING)
                || src.is(net.minecraft.world.damagesource.DamageTypes.CACTUS) || src.is(net.minecraft.world.damagesource.DamageTypes.SWEET_BERRY_BUSH)
                || src.is(net.minecraft.world.damagesource.DamageTypes.FREEZE) || src.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION);
        if (main != null && !surroundings) {
            main.hurt(src, event.getAmount());
        }
    }

    /** Who gets the credit for a blow: the soul of a clone mob whose body struck it, or whose arrow / fireball / fangs / vex did (anything else: the attacker itself). */
    public static Entity credit(Entity attacker) {
        Entity src = attacker;
        if (src instanceof net.minecraft.world.entity.projectile.Projectile p && p.getOwner() != null) {
            src = p.getOwner();
        } else if (src instanceof net.minecraft.world.entity.projectile.EvokerFangs f && f.getOwner() != null) {
            src = f.getOwner();
        } else if (src instanceof net.minecraft.world.entity.monster.Vex v && v.getOwner() != null) {
            src = v.getOwner();
        }
        ClonePlayer soul = soulOf(src);
        return soul != null ? soul : attacker;
    }

    /** Hands to mine, build, open doors and craft: a plain clone, or a clone mob whose body is man-shaped (zombie, skeleton, illager, villager, piglin, witch). */
    public static boolean canUseHands(net.minecraft.world.entity.player.Player p) {
        return true; // a clone mob does what a clone does (the soul has the hands): chopping, mining, building, crafting
    }

    /** A blow of the soul is a blow of the body (its own damage and effects: a husk's hunger, a spider's poison...); a body with no attack of its own leaves it to the soul's fist. */
    public static boolean bodyStrike(net.minecraft.world.entity.player.Player p, Entity target) {
        Mob b = null;
        double best = -1;
        for (Mob m : bodiesOf(p)) { // (a chimera strikes with its strongest body)
            var attr = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            if (attr != null && attr.getValue() > best) {
                best = attr.getValue();
                b = m;
            }
        }
        if (b == null) {
            return false;
        }
        b.doHurtTarget(target);
        return true;
    }

    /** Clone mobs breed with clone mobs of any kind, a plain clone with a plain clone. */
    public static boolean compatible(net.minecraft.world.entity.player.Player a, net.minecraft.world.entity.player.Player b) {
        Mob x = bodyOf(a);
        Mob y = bodyOf(b);
        return (x == null) == (y == null); // (any clone mob with any clone mob: of another kind the child is a chimera; a plain clone only with a plain one)
    }

    /** The kinds of body the two parents have between them, the first parent's first (no limit: a chimera keeps every kind it was bred from). */
    private static java.util.List<net.minecraft.world.entity.EntityType<?>> kindsOf(net.minecraft.world.entity.player.Player a, net.minecraft.world.entity.player.Player b) {
        java.util.LinkedHashSet<net.minecraft.world.entity.EntityType<?>> kinds = new java.util.LinkedHashSet<>();
        for (Mob m : bodiesOf(a)) {
            kinds.add(m.getType());
        }
        for (Mob m : bodiesOf(b)) {
            kinds.add(m.getType());
        }
        return List.copyOf(kinds);
    }

    /**
     * The child of two clone mobs. Of one kind: a body of that kind (a baby if the type has a baby form). Of different kinds: a chimera, one
     * body of each kind stacked in one place (the look is the two forced together), with the best of their stats and all of their attacks;
     * chimeras mate with chimeras and with plain clone mobs, the child taking the kinds of both (no limit).
     */
    public static Mob birth(net.minecraft.world.entity.player.Player a, net.minecraft.world.entity.player.Player b, ClonePlayer childSoul, Vec3 pos) {
        var kinds = kindsOf(a, b);
        Mob parent = bodyOf(a);
        if (parent == null || !(parent.level() instanceof ServerLevel level)) {
            return null;
        }
        if (kinds.size() <= 1) {
            return birth(parent, childSoul, pos);
        }
        return assemble(level, kinds, childSoul, pos, parent.getYRot(), MobSpawnType.BREEDING);
    }

    /** One body of each kind, stacked and tied to the soul (the first is the main one), the best of their stats on the main one. */
    private static Mob assemble(ServerLevel level, java.util.List<net.minecraft.world.entity.EntityType<?>> kinds, ClonePlayer soul, Vec3 pos, float yaw, MobSpawnType how) {
        java.util.List<Mob> bodies = new java.util.ArrayList<>();
        for (var type : kinds) {
            if (type.create(level) instanceof Mob m) {
                m.moveTo(pos.x, pos.y, pos.z, yaw, 0f);
                m.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(pos)), how, null, null);
                bodies.add(m);
            }
        }
        if (bodies.isEmpty()) {
            return null;
        }
        Mob main = bodies.get(0);
        mergeStats(main, bodies);
        link(main, soul);
        level.addFreshEntity(main);
        for (int i = 1; i < bodies.size(); i++) {
            linkExtra(bodies.get(i), soul);
            level.addFreshEntity(bodies.get(i));
        }
        return main;
    }

    /** A chimera clone mob of the given kinds (the command): null = no room under the clone limit / not a mob. */
    public static Mob summonChimera(@javax.annotation.Nullable ServerPlayer owner, ServerLevel level, java.util.List<net.minecraft.world.entity.EntityType<?>> kinds, Vec3 pos) {
        float yaw = owner == null ? 0f : owner.getYRot();
        ClonePlayer soul = CloneManager.get(level.getServer()).summon(owner, level, pos, yaw);
        if (soul == null) {
            return null;
        }
        Mob main = assemble(level, kinds, soul, pos, yaw, MobSpawnType.COMMAND);
        if (main == null) {
            CloneManager.get(level.getServer()).remove(soul, true, Component.literal("no body"));
        }
        return main;
    }

    /** The best of every stat the bodies have goes to the main body (the one that holds the health). */
    private static void mergeStats(Mob main, java.util.List<Mob> all) {
        for (var attr : java.util.List.of(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE,
                net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED, net.minecraft.world.entity.ai.attributes.Attributes.ARMOR,
                net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS, net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE,
                net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_KNOCKBACK, net.minecraft.world.entity.ai.attributes.Attributes.FLYING_SPEED)) {
            double best = Double.NEGATIVE_INFINITY;
            for (Mob m : all) {
                var inst = m.getAttribute(attr);
                if (inst != null) {
                    best = Math.max(best, inst.getBaseValue());
                }
            }
            var mine = main.getAttribute(attr);
            if (mine != null && best > Double.NEGATIVE_INFINITY) {
                mine.setBaseValue(best);
            }
        }
        main.setHealth(main.getMaxHealth());
    }

    /** The child of two clone mobs of one kind: a body of the same type, a baby if the type has a baby form (it grows up with time), else a grown one. */
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
        adopt(mob, (ServerLevel) event.getLevel());
    }

    /** A mob already on the players' side is made a clone mob too when the setting says what clone mobs summon / infect is one. */
    private static void adopt(Mob mob, ServerLevel level) {
        CloneManager m = CloneManager.get(level.getServer());
        if (SYNC_READY && m.summonedAsClone() && !mob.getPersistentData().hasUUID(SOUL)) {
            ClonePlayer soul = m.summon(null, level, mob.position(), mob.getYRot());
            if (soul != null) {
                link(mob, soul);
            }
        }
    }

    /** Persistent-data key on a victim: a clone mob killed it, so what it turns into (a zombie villager...) is on the players' side. */
    private static final String INFECTED_BY = "rlclones_infected_by_clone_mob";

    /** A clone mob kills something that can turn (villager -> zombie villager): marked now, the change comes a moment later. */
    @SubscribeEvent
    public static void onKilledByCloneMob(LivingDeathEvent event) {
        if (event.getSource().getEntity() instanceof Mob killer && playerSide(killer) && !event.getEntity().level().isClientSide) {
            event.getEntity().getPersistentData().putBoolean(INFECTED_BY, true);
        }
    }

    /** What a victim of a clone mob turns into (infection) is on the players' side - and a clone mob too, if the setting says so. */
    @SubscribeEvent
    public static void onInfected(net.minecraftforge.event.entity.living.LivingConversionEvent.Post event) {
        if (event.getEntity().getPersistentData().getBoolean(INFECTED_BY) && event.getOutcome() instanceof Mob out && out.level() instanceof ServerLevel level) {
            out.getPersistentData().putBoolean(SIDE, true);
            adopt(out, level);
        }
    }

    /** A clone mob's body does not change into another kind (a zombie to a drowned...): the soul is tied to this body. */
    @SubscribeEvent
    public static void onBodyConversion(net.minecraftforge.event.entity.living.LivingConversionEvent.Pre event) {
        if (soulOf(event.getEntity()) != null) {
            event.setCanceled(true);
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
            // not yet: a mob that rises again (a mutant zombie...) is only "dying". The tick ends the soul when the body is really gone
            DYING.putIfAbsent(soul.getUUID(), soul.level().getGameTime());
            DYING_SRC.put(soul.getUUID(), event.getSource());
        }
    }

    /** Bodies that have died and may rise again: soul -> when they died; and by what. */
    private static final java.util.Map<UUID, Long> DYING = new java.util.HashMap<>();
    private static final java.util.Map<UUID, net.minecraft.world.damagesource.DamageSource> DYING_SRC = new java.util.HashMap<>();
    /** Ticks a dead body is waited for before its soul dies too (a mob that revives does so within this). */
    private static final long REVIVE_WAIT = 600;

    /** Every body of this soul as it stands, dead or dying included (bodiesOf wants a living main body; a chimera's others must be found when it has died). */
    private static java.util.List<Mob> rawBodies(ClonePlayer soul) {
        java.util.List<Mob> out = new java.util.ArrayList<>();
        if (!(soul.level() instanceof ServerLevel l)) {
            return out;
        }
        java.util.List<UUID> ids = new java.util.ArrayList<>();
        if (BODY.containsKey(soul.getUUID())) {
            ids.add(BODY.get(soul.getUUID()));
        }
        ids.addAll(EXTRAS.getOrDefault(soul.getUUID(), java.util.List.of()));
        for (UUID id : ids) {
            if (l.getEntity(id) instanceof Mob m) {
                out.add(m);
            }
        }
        return out;
    }

    /** The body is gone for good: the soul dies of what killed it (so the death list tells the truth), and so do any other bodies. */
    private static void soulDies(ClonePlayer soul) {
        var src = DYING_SRC.remove(soul.getUUID());
        DYING.remove(soul.getUUID());
        for (Mob body : rawBodies(soul)) {
            body.discard(); // every body of it, a chimera's others and a main body that never rose again
        }
        EXTRAS.remove(soul.getUUID());
        if (soul.isAlive()) {
            soul.setHealth(0f);
            soul.die(src != null ? src : soul.damageSources().generic());
        }
    }
}
