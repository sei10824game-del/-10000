package com.rlclones.event;

import com.rlclones.RLClones;
import com.rlclones.ai.CloneController;
import com.rlclones.ai.Perception;
import com.rlclones.ai.Senses;
import com.rlclones.ai.observe.AgentEvents;
import com.rlclones.clone.CloneInventory;
import com.rlclones.clone.CloneManager;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import com.rlclones.command.CloneCommand;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.MobEffectEvent;
import net.minecraftforge.event.entity.living.ShieldBlockEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Routes world events to the clones. Knowledge about enemies is only learned by clones that can actually
 * SEE the event happen (the attacker must be inside their view); own rewards go to the clone involved.
 * Actions of every agent (players and clones alike) are logged for observational learning.
 */
@Mod.EventBusSubscriber(modid = RLClones.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerEvents {
    /** Damage each living entity has taken so far (to estimate enemy health when it dies in view). */
    private static final Int2FloatOpenHashMap DAMAGE_TAKEN = new Int2FloatOpenHashMap();
    private static final List<ShieldCheck> SHIELD_CHECKS = new ArrayList<>();
    private static int lastExploderId = -1;
    private static Vec3 lastExplosionCenter = Vec3.ZERO;
    private static long lastExplosionTick = -1;

    private record ShieldCheck(ClonePlayer victim, Entity attacker, long due) {
    }

    private ServerEvents() {
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        CloneCommand.register(event.getDispatcher());
    }

    // ------------------------------------------------------------------ lifecycle

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        CloneManager.get(event.getServer()).restore();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        CloneManager m = CloneManager.peek();
        if (m != null && m.server() == event.getServer()) {
            m.saveAll();
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CloneManager.shutdown();
        DAMAGE_TAKEN.clear();
        SHIELD_CHECKS.clear();
    }

    @SubscribeEvent
    public static void onLevelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level && level == level.getServer().overworld()) {
            CloneManager m = CloneManager.peek();
            if (m != null && m.server() == level.getServer()) {
                m.saveAll();
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        CloneManager.get(server).tick();
        if (!SHIELD_CHECKS.isEmpty()) {
            long now = server.overworld().getGameTime();
            SHIELD_CHECKS.removeIf(c -> {
                if (now < c.due()) {
                    return false;
                }
                if (c.victim().getCooldowns().isOnCooldown(Items.SHIELD)) {
                    for (ClonePlayer o : witnesses(c.victim().getServer(), c.attacker())) {
                        o.controller().observeShieldDisabled(c.attacker());
                    }
                }
                return true;
            });
        }
    }

    /** Sneak + right click a clone to open its inventory; with breeding on (N key), a plain right click makes a child with it. */
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (player.level().isClientSide || event.getHand() != InteractionHand.MAIN_HAND
                || !(event.getTarget() instanceof ClonePlayer clone) || player instanceof ClonePlayer) {
            return;
        }
        if (!player.isShiftKeyDown()) {
            CloneManager m = CloneManager.peek();
            if (m != null && m.isBreeding()) {
                ClonePlayer child = m.breed(player, clone);
                player.displayClientMessage(child != null
                        ? Component.translatable("rlclones.msg.born_you", child.getGameProfile().getName(), clone.getGameProfile().getName())
                        : Component.translatable("rlclones.msg.breed_need", com.rlclones.ai.Breeding.FOOD_NEEDED), true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
            }
            return;
        }
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new ChestMenu(MenuType.GENERIC_9x4, id, inv, new CloneInventory(clone), 4), clone.getDisplayName()));
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && p.getServer() != null) {
            CloneManager m = CloneManager.peek();
            if (m != null) {
                m.onLoggedOut(p);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<ClonePlayer> witnesses(MinecraftServer server, Entity subject) {
        List<ClonePlayer> out = new ArrayList<>();
        if (server == null || subject == null) {
            return out;
        }
        CloneManager m = CloneManager.peek();
        if (m == null) {
            return out;
        }
        for (ClonePlayer c : m.clones()) {
            if (c.isAlive() && c.level() == subject.level() && c.distanceTo(subject) < 128 && c.controller().perception().canSee(subject)) {
                out.add(c);
            }
        }
        return out;
    }

    private static CloneController controllerOf(Entity e) {
        if (e instanceof ClonePlayer c && c.isAlive()) {
            CloneManager m = CloneManager.peek();
            if (m != null && m.isClone(c)) {
                return c.controller();
            }
        }
        return null;
    }

    private static long tick(Entity e) {
        return e.level().getGameTime();
    }

    // ------------------------------------------------------------------ combat observation

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onAttack(LivingAttackEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || event.isCanceled()) {
            return;
        }
        DamageSource source = event.getSource();
        Entity attacker = source.getEntity();
        Entity direct = source.getDirectEntity();
        MinecraftServer server = victim.getServer();
        if (attacker instanceof LivingEntity && direct == attacker && attacker != victim) {
            com.rlclones.ai.AttackTells.attacked(attacker); // a blow: what it showed just before is how it announces one
        }
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            if (attacker != null && attacker.getId() == lastExploderId && tick(victim) - lastExplosionTick <= 1) {
                double distance = lastExplosionCenter.distanceTo(victim.getBoundingBox().getCenter());
                for (ClonePlayer o : witnesses(server, victim)) {
                    o.controller().observeExplosionDamage(attacker, distance, event.getAmount());
                }
            }
            return;
        }
        if (!(attacker instanceof Mob mob) || Senses.isAgent(attacker)) {
            return;
        }
        boolean melee = direct == attacker;
        boolean projectile = direct instanceof Projectile;
        for (ClonePlayer o : witnesses(server, attacker)) {
            if (melee) {
                o.controller().observeMelee(mob, victim, event.getAmount());
            } else if (projectile) {
                o.controller().observeProjectileHit(mob, victim, event.getAmount());
            }
        }
        if (melee && victim instanceof ClonePlayer c && c.isBlocking()) {
            SHIELD_CHECKS.add(new ShieldCheck(c, attacker, tick(victim) + 1));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || event.isCanceled() || event.getAmount() <= 0) {
            return;
        }
        float amount = Math.min(event.getAmount(), victim.getHealth());
        if (DAMAGE_TAKEN.size() > 20000) {
            DAMAGE_TAKEN.clear();
        }
        DAMAGE_TAKEN.addTo(victim.getId(), amount);
        Entity attacker = event.getSource().getEntity();
        long now = tick(victim);
        if (Senses.isAgent(victim)) {
            AgentEvents.record(victim.getUUID(), now, AgentEvents.Kind.HURT, amount, attacker == null ? -1 : attacker.getId(), 0);
        }
        if (victim instanceof ClonePlayer cp) {
            cp.controller().onHurt(event.getSource(), amount);
        }
        if (victim instanceof ClonePlayer cp && attacker == null && event.getSource().getDirectEntity() == null) {
            cp.controller().onEnvironmentDamage(event.getSource());
        }
        if (Senses.isAgent(attacker)) {
            boolean hostile = Senses.isHostileTo(victim, (LivingEntity) attacker);
            AgentEvents.record(attacker.getUUID(), now, AgentEvents.Kind.DEALT, amount, victim.getId(), hostile ? AgentEvents.FLAG_HOSTILE : 0);
            CloneController c = controllerOf(attacker);
            if (c != null) {
                c.onDealtDamage(victim, amount);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || event.isCanceled()) {
            return;
        }
        Entity killer = event.getSource().getEntity();
        long now = tick(victim);
        float taken = DAMAGE_TAKEN.remove(victim.getId());
        MinecraftServer server = victim.getServer();
        if (Senses.isAgent(victim) || victim instanceof ServerPlayer) {
            AgentEvents.record(victim.getUUID(), now, AgentEvents.Kind.DEATH, 0, killer == null ? -1 : killer.getId(), 0);
            if (killer instanceof Mob) {
                for (ClonePlayer o : witnesses(server, killer)) {
                    o.controller().observeAgentKilled(killer);
                }
            }
            return;
        }
        if (victim instanceof Mob mob && killer instanceof Player saviour && mob.getTarget() instanceof ClonePlayer saved && saved != saviour
                && saved.controller() != null) {
            saved.controller().foodAid().thank(saviour.getUUID(), 2f); // it was after us and they finished it
        }
        boolean hostile = killer instanceof LivingEntity k && Senses.isHostileTo(victim, k);
        boolean byAgent = Senses.isAgent(killer);
        if (byAgent) {
            AgentEvents.record(killer.getUUID(), now, hostile ? AgentEvents.Kind.KILL_HOSTILE : AgentEvents.Kind.KILL_ANIMAL, 0, victim.getId(), 0);
            CloneController c = controllerOf(killer);
            if (c != null) {
                c.onKill(victim, hostile);
            }
        }
        if (victim instanceof Mob) {
            for (ClonePlayer o : witnesses(server, victim)) {
                o.controller().observeDeath(victim, taken, byAgent);
            }
        }
    }

    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide || event.isCanceled()) {
            return;
        }
        LivingEntity target = event.getNewTarget();
        if (target == null || !Senses.isAgent(target) || mob.getTarget() == target) {
            return;
        }
        for (ClonePlayer o : witnesses(mob.getServer(), mob)) {
            o.controller().observeTargeting(mob, target);
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        Explosion explosion = event.getExplosion();
        Entity exploder = explosion.getExploder();
        if (event.getLevel().isClientSide || exploder == null || Senses.isAgent(exploder)) {
            return;
        }
        lastExploderId = exploder.getId();
        lastExplosionCenter = explosion.getPosition();
        lastExplosionTick = exploder.level().getGameTime();
        if (exploder instanceof LivingEntity) {
            com.rlclones.ai.AttackTells.attacked(exploder); // a blast (a creeper swells first)
        }
        if (exploder instanceof Mob) {
            for (ClonePlayer o : witnesses(exploder.getServer(), exploder)) {
                o.controller().observeExplosion(exploder);
            }
        }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof Projectile projectile)) {
            return;
        }
        Entity owner = projectile.getOwner();
        if (owner instanceof LivingEntity) {
            com.rlclones.ai.AttackTells.attacked(owner, true); // a shot (a skeleton's arrow, a rival clone's bow)
        }
        if (owner instanceof Mob mob && !Senses.isAgent(owner)) {
            for (ClonePlayer o : witnesses(mob.getServer(), mob)) {
                o.controller().observeShot(mob);
            }
        } else if (Senses.isAgent(owner)) {
            AgentEvents.record(owner.getUUID(), owner.level().getGameTime(), AgentEvents.Kind.SHOOT, 0, -1, 0);
        }
    }

    @SubscribeEvent
    public static void onEffect(MobEffectEvent.Added event) {
        Entity source = event.getEffectSource();
        if (source instanceof Projectile p) {
            source = p.getOwner();
        }
        if (!(source instanceof Mob) || event.getEntity().level().isClientSide) {
            return;
        }
        MobEffectInstance effect = event.getEffectInstance();
        var key = ForgeRegistries.MOB_EFFECTS.getKey(effect.getEffect());
        if (key == null) {
            return;
        }
        for (ClonePlayer o : witnesses(source.getServer(), event.getEntity())) {
            o.controller().observeEffect(source, key.toString());
        }
    }

    // ------------------------------------------------------------------ agent action log (players == clones)

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerAttack(AttackEntityEvent event) {
        Player p = event.getEntity();
        if (p.level().isClientSide || event.isCanceled()) {
            return;
        }
        int flags = 0;
        if (p.fallDistance > 0 && !p.onGround() && !p.onClimbable() && !p.isInWater() && p.getAttackStrengthScale(0.5F) > 0.9F) {
            flags |= AgentEvents.FLAG_CRIT;
        }
        if (p.isSprinting()) {
            flags |= AgentEvents.FLAG_SPRINT;
        }
        if (Senses.isHostileTo(event.getTarget(), p)) {
            flags |= AgentEvents.FLAG_HOSTILE;
        }
        AgentEvents.record(p.getUUID(), p.level().getGameTime(), AgentEvents.Kind.ATTACK, 0, event.getTarget().getId(), flags);
        if (p instanceof ClonePlayer cp && cp.controller() != null) {
            cp.controller().onMeleeHit(event.getTarget());
        }
    }

    /** One of a clone's projectiles struck a living thing: did it hurt? (learning what does nothing to what) */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onProjectileImpact(net.minecraftforge.event.entity.ProjectileImpactEvent event) {
        var proj = event.getProjectile();
        if (proj.level().isClientSide || event.isCanceled()) {
            return;
        }
        if (proj.getOwner() instanceof ClonePlayer cp && cp.controller() != null
                && event.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult hit && hit.getEntity() instanceof LivingEntity victim) {
            cp.controller().onProjectileHit(victim, proj);
        }
    }

    @SubscribeEvent
    public static void onPickup(PlayerEvent.ItemPickupEvent event) {
        Player p = event.getEntity();
        if (p.level().isClientSide) {
            return;
        }
        int count = event.getStack().getCount();
        AgentEvents.record(p.getUUID(), p.level().getGameTime(), AgentEvents.Kind.PICKUP, count, -1, 0);
        CloneController c = controllerOf(p);
        if (c != null) {
            c.onPickup(count);
            // something tossed to us by someone else (food above all): a helper to remember
            net.minecraft.nbt.CompoundTag t = event.getOriginalEntity().saveWithoutId(new net.minecraft.nbt.CompoundTag());
            if (t.hasUUID("Thrower") && !t.getUUID("Thrower").equals(p.getUUID())) {
                c.foodAid().thank(t.getUUID("Thrower"), com.rlclones.ai.FoodAid.goodFood(event.getStack()) ? 3f : 1f);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        Player p = event.getPlayer();
        if (p == null || p.level().isClientSide || event.isCanceled()) {
            return;
        }
        Perception.BlockKind kind = Perception.classify(event.getState());
        AgentEvents.Kind k = kind == Perception.BlockKind.LOG ? AgentEvents.Kind.BREAK_LOG
                : kind == Perception.BlockKind.ORE ? AgentEvents.Kind.BREAK_ORE : AgentEvents.Kind.BREAK_OTHER;
        AgentEvents.record(p.getUUID(), p.level().getGameTime(), k, 1, -1, 0);
        CloneController c = controllerOf(p);
        if (c != null) {
            c.onBlockBroken(event.getState(), event.getPos());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        Entity e = event.getEntity();
        if (event.isCanceled()) {
            return;
        }
        if (e instanceof net.minecraft.world.entity.player.Player pl && !(pl instanceof ClonePlayer) && pl.level() instanceof ServerLevel sl
                && (event.getPlacedBlock().getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                || event.getPlacedBlock().getBlock() instanceof net.minecraft.world.level.block.BarrelBlock)) {
            playerChest(sl, event.getPos(), pl.getGameProfile().getName());
        }
        if (!(e instanceof ServerPlayer p)) {
            return;
        }
        BlockPos pos = event.getPos();
        if (pos.getX() == p.getBlockX() && pos.getZ() == p.getBlockZ() && pos.getY() < p.getY()) {
            AgentEvents.record(p.getUUID(), p.level().getGameTime(), AgentEvents.Kind.PILLAR, 1, -1, 0);
        }
    }

    /** A chest a player puts down counts as a base: clones store their surplus there and fetch from it. */
    public static void playerChest(ServerLevel level, BlockPos pos, String player) {
        com.rlclones.clone.Bases bases = com.rlclones.clone.Bases.get(level.getServer());
        com.rlclones.clone.Bases.Base base = bases.nearest(level.dimension(), Vec3.atCenterOf(pos), 24);
        if (base == null) {
            base = bases.add(level.dimension(), pos, player);
        }
        bases.addChest(base, pos);
        if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container c) {
            bases.record(level.dimension(), pos, c);
        }
    }

    /** Clones read the chat of real players too ("help 100 64 -20", "助けて"...). */
    @SubscribeEvent
    public static void onPlayerChat(net.minecraftforge.event.ServerChatEvent event) {
        if (!(event.getPlayer() instanceof ClonePlayer)) {
            com.rlclones.ai.Chat.deliver(event.getPlayer(), event.getRawText());
        }
    }

    @SubscribeEvent
    public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        Player p = event.getEntity();
        if (p.level().isClientSide) {
            return;
        }
        var item = event.getCrafting().getItem();
        boolean gear = item instanceof net.minecraft.world.item.TieredItem || item instanceof net.minecraft.world.item.ArmorItem
                || item instanceof net.minecraft.world.item.ShieldItem;
        AgentEvents.record(p.getUUID(), p.level().getGameTime(), AgentEvents.Kind.CRAFT, gear ? 1.5f : 0.2f, -1, 0);
        CloneController c = controllerOf(p);
        if (c != null) {
            c.onCrafted(event.getCrafting());
        }
    }

    @SubscribeEvent
    public static void onSmelted(PlayerEvent.ItemSmeltedEvent event) {
        Player p = event.getEntity();
        if (p.level().isClientSide) {
            return;
        }
        AgentEvents.record(p.getUUID(), p.level().getGameTime(), AgentEvents.Kind.CRAFT, 0.5f, -1, 0);
        CloneController c = controllerOf(p);
        if (c != null) {
            c.onSmelted();
        }
    }

    @SubscribeEvent
    public static void onFinishUsing(LivingEntityUseItemEvent.Finish event) {
        if (event.getEntity() instanceof ServerPlayer p && event.getItem().isEdible()) {
            AgentEvents.record(p.getUUID(), p.level().getGameTime(), AgentEvents.Kind.EAT, 1, -1, 0);
        }
    }

    @SubscribeEvent
    public static void onShieldBlock(ShieldBlockEvent event) {
        LivingEntity blocker = event.getEntity();
        if (blocker.level().isClientSide || !Senses.isAgent(blocker)) {
            return;
        }
        AgentEvents.record(blocker.getUUID(), blocker.level().getGameTime(), AgentEvents.Kind.BLOCKED, event.getBlockedDamage(), -1, 0);
        CloneController c = controllerOf(blocker);
        if (c != null) {
            c.onShieldBlock(event.getBlockedDamage());
        }
    }
}
