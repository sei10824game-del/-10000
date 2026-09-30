package com.rlclones.gametest;

import com.rlclones.RLClones;
import com.rlclones.ai.brain.Brain;
import com.rlclones.ai.brain.EnemyKnowledge;
import com.rlclones.ai.brain.QTable;
import com.rlclones.ai.combat.CombatAction;
import com.rlclones.ai.combat.CombatState;
import com.rlclones.clone.ClientAction;
import com.rlclones.clone.CloneManager;
import com.rlclones.clone.ClonePlayer;
import com.rlclones.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Acceptance tests that run on a real headless server ({@code ./gradlew runGameTestServer}).
 * Arena: 15x15 stone floor (relative y=1, entities stand at y=2), barrier walls, open sky.
 */
@GameTestHolder(RLClones.MODID)
@PrefixGameTestTemplate(false)
public final class CloneGameTests {
    private static final String ARENA = "arena";

    private CloneGameTests() {
    }

    // ------------------------------------------------------------------ helpers

    private static CloneManager manager(GameTestHelper h) {
        return CloneManager.get(h.getLevel().getServer());
    }

    private static ClonePlayer clone(GameTestHelper h, double x, double z, float yaw, boolean ai) {
        Vec3 pos = h.absoluteVec(new Vec3(x, 2, z));
        ClonePlayer c = manager(h).summon(null, h.getLevel(), pos, yaw);
        if (c == null) {
            throw new IllegalStateException("clone could not be summoned");
        }
        c.setAiEnabled(ai);
        c.teleportTo(h.getLevel(), pos.x, pos.y, pos.z, yaw, 0f);
        c.setYHeadRot(yaw);
        return c;
    }

    private static void finish(GameTestHelper h, ClonePlayer... clones) {
        for (ClonePlayer c : clones) {
            ClonePlayer live = manager(h).byName(c.getGameProfile().getName());
            if (live != null) {
                manager(h).remove(live, true, Component.literal("test finished"));
            }
        }
        h.assertTrue(CloneManager.errors() == 0, "clone AI threw " + CloneManager.errors() + " errors (see log)");
    }

    private static Pig pig(GameTestHelper h, double x, double z) {
        Pig pig = h.spawn(EntityType.PIG, new Vec3(x, 2, z));
        pig.setNoAi(true);
        return pig;
    }

    // ------------------------------------------------------------------ AC: clones are real players

    @GameTest(template = ARENA, timeoutTicks = 200)
    public static void cloneIsRealPlayer(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, true);
        c.getInventory().add(new ItemStack(Items.IRON_SWORD));
        c.getInventory().add(new ItemStack(Items.IRON_CHESTPLATE));
        h.assertTrue(h.getLevel().getServer().getPlayerList().getPlayer(c.getUUID()) == c, "clone must be in the player list");
        h.assertTrue(c.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "clone must be in survival");
        h.assertTrue(c.getMaxHealth() == 20f && c.getFoodData().getFoodLevel() == 20, "player health / hunger");
        h.assertTrue(Math.abs(c.getEntityReach() - 3.0) < 1e-6, "player reach");
        int start = c.tickCount;
        h.succeedWhen(() -> {
            h.assertTrue(c.tickCount > start + 60, "clone must tick");
            h.assertTrue(c.getMainHandItem().is(Items.IRON_SWORD), "clone should wield its best weapon");
            h.assertTrue(c.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "clone should wear armour");
            finish(h, c);
        });
    }

    // ------------------------------------------------------------------ AC: vision (FOV + line of sight, no wall hacks)

    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void visionRespectsFovAndWalls(GameTestHelper h) {
        ClonePlayer c = clone(h, 3.5, 7.5, -90f, false); // facing +X
        for (int z = 9; z <= 13; z++) {
            for (int y = 2; y <= 4; y++) {
                h.setBlock(new BlockPos(6, y, z), Blocks.STONE);
            }
        }
        for (int z = 1; z <= 5; z++) {
            for (int y = 2; y <= 4; y++) {
                h.setBlock(new BlockPos(6, y, z), Blocks.GLASS);
            }
        }
        Pig front = pig(h, 10.5, 7.5);
        Pig behind = pig(h, 1.5, 7.5);
        Pig behindWall = pig(h, 9.5, 11.5);
        Pig behindGlass = pig(h, 9.5, 3.5);
        h.runAfterDelay(5, () -> {
            var p = c.controller().perception();
            h.assertTrue(p.canSee(front), "must see the pig in front: " + p.explain(front));
            h.assertFalse(p.canSee(behind), "must not see behind itself");
            h.assertFalse(p.canSee(behindWall), "must not see through stone");
            h.assertTrue(p.canSee(behindGlass), "should see through glass");
            c.setYRot(90f);
            c.setYHeadRot(90f);
            h.assertTrue(p.canSee(behind), "after turning around it sees the other pig");
            h.assertFalse(p.canSee(front), "and no longer the first one");
            finish(h, c);
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ AC: fights and learns per enemy type

    @GameTest(template = ARENA, timeoutTicks = 900)
    public static void cloneFightsAndLearnsHusk(GameTestHelper h) {
        ClonePlayer c = clone(h, 3.5, 7.5, -90f, true);
        c.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 2000, 2));
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(10.5, 2, 7.5));
        h.succeedWhen(() -> {
            h.assertTrue(!husk.isAlive(), "clone should kill the husk");
            Brain b = c.getCloneBrain();
            h.assertTrue(b.ownUpdates > 0, "clone must have learned from its own fight");
            h.assertTrue(b.combatTypes().contains("minecraft:husk") && b.combatTable("minecraft:husk").size() > 0, "husk-specific combat table");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 400)
    public static void learnsEnemyRangeAndCooldownByWatching(GameTestHelper h) {
        ClonePlayer observer = clone(h, 2.5, 7.5, -90f, false);
        ClonePlayer victim = clone(h, 11.5, 7.5, 90f, false);
        victim.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 2000, 4));
        victim.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 2000, 4));
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(9.5, 2, 7.5));
        husk.setTarget(victim);
        h.succeedWhen(() -> {
            husk.setTarget(victim);
            EnemyKnowledge k = observer.getCloneBrain().knowledgeIfPresent("minecraft:husk");
            h.assertTrue(k != null, "observer knows husks: " + observer.controller().perception().explain(husk));
            h.assertTrue(k.meleeDamage.count() >= 3, "observer saw at least 3 attacks, saw " + k.meleeDamage.count()
                    + " | husk target=" + husk.getTarget() + " gap=" + com.rlclones.ai.Senses.gap(husk, victim) + " | " + observer.controller().perception().explain(husk));
            h.assertTrue(k.meleeCooldown.known(), "cooldown learned");
            double cd = k.meleeCooldown.get();
            double range = k.meleeRange.get();
            h.assertTrue(cd >= 15 && cd <= 30, String.format(Locale.ROOT, "husk cooldown should be ~20 ticks, learned %.1f", cd));
            h.assertTrue(range >= 0.0 && range <= 2.0, String.format(Locale.ROOT, "husk reach should be short, learned %.2f", range));
            finish(h, observer, victim);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600)
    public static void imitatesOtherAgents(GameTestHelper h) {
        ClonePlayer observer = clone(h, 2.5, 7.5, -90f, false);
        ClonePlayer fighter = clone(h, 7.5, 7.5, -90f, true);
        fighter.getInventory().add(new ItemStack(Items.IRON_SWORD));
        fighter.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 2000, 2));
        h.spawn(EntityType.HUSK, new Vec3(12.5, 2, 7.5));
        h.succeedWhen(() -> {
            Brain b = observer.getCloneBrain();
            h.assertTrue(b.imitationUpdates >= 5, "observer should learn from watching, got " + b.imitationUpdates);
            h.assertTrue(b.ownUpdates == 0, "observer never acted itself");
            h.assertTrue(b.combatTypes().contains("minecraft:husk"), "observer built a husk combat table by watching");
            finish(h, observer, fighter);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 400)
    public static void cloneMinesLikePlayer(GameTestHelper h) {
        ClonePlayer c = clone(h, 5.5, 7.5, -90f, false);
        BlockPos log = new BlockPos(6, 2, 7);
        h.setBlock(log, Blocks.OAK_LOG);
        BlockPos abs = h.absolutePos(log);
        h.onEachTick(() -> {
            if (!h.getBlockState(log).isAir()) {
                c.controller().motor().lookAt(Vec3.atCenterOf(abs));
                c.controller().motor().tick();
                c.controller().motor().mine(abs);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(h.getBlockState(log).isAir(), "log should be mined");
            h.assertTrue(c.getInventory().countItem(Items.OAK_LOG) >= 1, "clone should pick up the dropped log");
            finish(h, c);
        });
    }

    // ------------------------------------------------------------------ AC: brains persist and merge correctly

    @GameTest(template = ARENA, timeoutTicks = 20)
    public static void brainSaveLoadAndMerge(GameTestHelper h) {
        Brain a = new Brain();
        int s = CombatState.encode(2, 2, 2, 2, 0, 0, 1);
        for (int i = 0; i < 20; i++) {
            a.learn("minecraft:zombie", s, CombatAction.ATTACK.ordinal(), 2f, 0, true, 0f, 0, 1f, false);
        }
        a.knowledge("minecraft:zombie").meleeCooldown.add(20);
        Brain loaded = Brain.load(a.save());
        QTable.Entry e1 = a.combatTable("minecraft:zombie").peek(s);
        QTable.Entry e2 = loaded.combatTable("minecraft:zombie").peek(s);
        h.assertTrue(e2 != null && Math.abs(e1.q[1] - e2.q[1]) < 1e-6 && e1.n[1] == e2.n[1], "Q table survives save/load");
        h.assertTrue(e2.q[1] > 1.5f, "repeated reward 2 should push Q(attack) towards 2, got " + e2.q[1]);
        h.assertTrue(Math.abs(loaded.knowledge("minecraft:zombie").meleeCooldown.get() - 20) < 1e-6, "knowledge survives save/load");

        Brain b = new Brain();
        for (int i = 0; i < 20; i++) {
            b.learn("minecraft:zombie", s, CombatAction.ATTACK.ordinal(), -2f, 0, true, 0f, 0, 1f, false);
        }
        Brain merged = Brain.merge(List.of(a, b));
        float mq = merged.combatTable("minecraft:zombie").peek(s).q[1];
        h.assertTrue(Math.abs(mq) < 0.5f, "equal-weight merge of +2 and -2 should be near 0, got " + mq);
        h.assertTrue(merged.members == 2, "merged brain counts its members");
        h.succeed();
    }

    // ------------------------------------------------------------------ AC: X links all clones' learning

    @GameTest(template = ARENA, timeoutTicks = 60, batch = "link")
    public static void linkSharesOneBrain(GameTestHelper h) {
        CloneManager m = manager(h);
        m.setLinked(false);
        ClonePlayer a = clone(h, 4.5, 4.5, 0f, false);
        ClonePlayer b = clone(h, 10.5, 10.5, 0f, false);
        a.getCloneBrain().knowledge("minecraft:skeleton").rangedDamage.add(2);
        b.getCloneBrain().knowledge("minecraft:skeleton").rangedDamage.add(4);
        h.assertTrue(a.getCloneBrain() != b.getCloneBrain(), "separate brains while unlinked");
        m.handleAction(a, ClientAction.TOGGLE_LINK);
        h.assertTrue(m.isLinked(), "X toggles link on");
        h.assertTrue(a.getCloneBrain() == b.getCloneBrain(), "linked clones share one brain");
        EnemyKnowledge k = a.getCloneBrain().knowledge("minecraft:skeleton");
        h.assertTrue(k.rangedDamage.count() == 2 && Math.abs(k.rangedDamage.get() - 3) < 1e-6, "experience of both clones merged");
        ClonePlayer late = clone(h, 7.5, 7.5, 0f, false);
        h.assertTrue(late.getCloneBrain() == a.getCloneBrain(), "clones summoned while linked join the hive");
        m.handleAction(a, ClientAction.TOGGLE_LINK);
        h.assertFalse(m.isLinked(), "X toggles link off");
        h.assertTrue(a.getCloneBrain() != b.getCloneBrain(), "unlinked clones get their own copy");
        h.assertTrue(b.getCloneBrain().knowledge("minecraft:skeleton").rangedDamage.count() == 2, "copies keep the shared knowledge");
        finish(h, a, b, late);
        h.succeed();
    }

    // ------------------------------------------------------------------ AC: Z summons, P toggles respawn

    @GameTest(template = ARENA, timeoutTicks = 60, batch = "keys")
    public static void keyActionsWork(GameTestHelper h) {
        CloneManager m = manager(h);
        ClonePlayer sender = clone(h, 7.5, 7.5, 0f, false);
        int before = m.clones().size();
        m.handleAction(sender, ClientAction.SUMMON);
        h.assertTrue(m.clones().size() == before + 1, "Z summons one clone");
        List<ClonePlayer> made = new ArrayList<>();
        for (ClonePlayer c : m.clones()) {
            if (c != sender && c.distanceTo(sender) < 4) {
                made.add(c);
            }
        }
        h.assertTrue(!made.isEmpty(), "new clone appears next to the summoner");
        boolean was = m.isRespawnEnabled();
        m.handleAction(sender, ClientAction.TOGGLE_RESPAWN);
        h.assertTrue(m.isRespawnEnabled() != was, "P toggles respawn");
        m.handleAction(sender, ClientAction.TOGGLE_RESPAWN);
        h.assertTrue(m.isRespawnEnabled() == was, "P toggles back");
        made.add(sender);
        finish(h, made.toArray(new ClonePlayer[0]));
        h.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 60, batch = "egg")
    public static void spawnEggSummonsClone(GameTestHelper h) {
        CloneManager m = manager(h);
        ClonePlayer user = clone(h, 4.5, 7.5, -90f, false);
        user.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.CLONE_SPAWN_EGG.get(), 2));
        int before = m.clones().size();
        BlockPos floor = h.absolutePos(new BlockPos(8, 1, 7));
        InteractionResult r = user.getMainHandItem().useOn(new UseOnContext(user, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false)));
        h.assertTrue(r.consumesAction(), "egg use should succeed, got " + r);
        h.assertTrue(m.clones().size() == before + 1, "spawn egg summons one clone");
        h.assertTrue(user.getMainHandItem().getCount() == 1, "egg is consumed in survival");
        List<ClonePlayer> made = new ArrayList<>();
        for (ClonePlayer c : m.clones()) {
            if (c != user && c.blockPosition().closerThan(floor.above(), 1.5)) {
                made.add(c);
            }
        }
        h.assertTrue(made.size() == 1, "clone stands on the clicked block");
        made.add(user);
        finish(h, made.toArray(new ClonePlayer[0]));
        h.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 100, batch = "persist")
    public static void clonesAndBrainsSurviveRelog(GameTestHelper h) {
        CloneManager m = manager(h);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.GOLDEN_APPLE, 3));
        c.getCloneBrain().knowledge("minecraft:creeper").fuseTicks.add(30);
        String name = c.getGameProfile().getName();
        UUID id = c.getUUID();
        Vec3 pos = c.position();
        m.remove(c, false, Component.literal("server stopping"));
        h.assertTrue(m.byName(name) == null, "clone logged out");
        m.restoreMissing();
        ClonePlayer back = m.byName(name);
        h.assertTrue(back != null && back != c && back.getUUID().equals(id), "clone restored from the roster");
        h.assertTrue(back.position().distanceTo(pos) < 0.5, "restored at its saved position");
        h.assertTrue(back.getInventory().countItem(Items.GOLDEN_APPLE) == 3, "inventory restored from player data");
        h.assertTrue(back.getCloneBrain() != c.getCloneBrain() && back.getCloneBrain().knowledge("minecraft:creeper").fuseTicks.count() == 1,
                "brain reloaded from disk");
        finish(h, back);
        h.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 200, batch = "respawn_on")
    public static void respawnOnBringsCloneBack(GameTestHelper h) {
        CloneManager m = manager(h);
        m.setRespawn(true);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getCloneBrain().knowledge("minecraft:zombie").meleeDamage.add(3);
        Brain brain = c.getCloneBrain();
        String name = c.getGameProfile().getName();
        UUID id = c.getUUID();
        c.kill();
        h.succeedWhen(() -> {
            ClonePlayer back = m.byName(name);
            h.assertTrue(back != null && back != c, "a new clone body must exist");
            h.assertTrue(back.isAlive() && back.getHealth() == back.getMaxHealth(), "respawned alive with full health");
            h.assertTrue(back.getUUID().equals(id), "same identity");
            h.assertTrue(back.getCloneBrain() == brain, "keeps its brain (learning survives death)");
            h.assertTrue(h.getLevel().getServer().getPlayerList().getPlayer(id) == back, "player list points at the new body");
            m.setRespawn(false);
            finish(h, back);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 200, batch = "respawn_off")
    public static void respawnOffRemovesClone(GameTestHelper h) {
        CloneManager m = manager(h);
        m.setRespawn(false);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        String name = c.getGameProfile().getName();
        UUID id = c.getUUID();
        c.kill();
        h.succeedWhen(() -> {
            h.assertTrue(m.byName(name) == null, "dead clone is gone for good");
            h.assertTrue(h.getLevel().getServer().getPlayerList().getPlayer(id) == null, "and logged out");
            h.assertTrue(CloneManager.errors() == 0, "no AI errors");
        });
    }
}
