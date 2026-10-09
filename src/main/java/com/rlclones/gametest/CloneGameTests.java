package com.rlclones.gametest;

import com.rlclones.RLClones;
import com.rlclones.ai.Motor;
import com.rlclones.ai.Progression;
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
import net.minecraft.world.entity.EquipmentSlot;
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
        // the arenas are dark: without this, zombies would spawn in them and wander into unrelated tests
        var spawning = h.getLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING);
        if (spawning.get()) {
            spawning.set(false, h.getLevel().getServer());
        }
        Vec3 pos = h.absoluteVec(new Vec3(x, 2, z));
        ClonePlayer c = manager(h).summon(null, h.getLevel(), pos, yaw);
        if (c == null) {
            throw new IllegalStateException("clone could not be summoned");
        }
        c.setAiEnabled(ai);
        c.teleportTo(h.getLevel(), pos.x, pos.y, pos.z, yaw, 0f);
        c.setYHeadRot(yaw);
        cleanUpOnFailure(h, c);
        return c;
    }

    /**
     * A failed test leaves its arena standing: take its clones (which would keep thinking, talking and calling others)
     * and its mobs away so they cannot disturb the tests that run later.
     */
    private static void cleanUpOnFailure(GameTestHelper h, ClonePlayer c) {
        try {
            java.lang.reflect.Field f = GameTestHelper.class.getDeclaredField("testInfo");
            f.setAccessible(true);
            ((net.minecraft.gametest.framework.GameTestInfo) f.get(h)).addListener(new net.minecraft.gametest.framework.GameTestListener() {
                public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {
                }

                public void testPassed(net.minecraft.gametest.framework.GameTestInfo info) {
                }

                public void testFailed(net.minecraft.gametest.framework.GameTestInfo info) {
                    ClonePlayer live = manager(h).byName(c.getGameProfile().getName());
                    if (live != null) {
                        manager(h).remove(live, true, Component.literal("test failed"));
                    }
                    h.killAllEntities();
                    clearAbove(h);
                    resetArena(h);
                    com.rlclones.clone.CloneManager m = manager(h);
                    if (CloneManager.coward()) {
                        m.setCoward(false);
                    }
                    // world-wide things a failed test may leave behind: no staircase to lure other tests' clones away
                    com.rlclones.clone.Bases b = com.rlclones.clone.Bases.get(h.getLevel().getServer());
                    b.staircases.clear();
                    b.shafts.clear();
                    b.setDirty();
                    manager(h).setBreeding(false);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            RLClones.LOGGER.warn("could not watch the test for clean-up", e);
        }
    }

    /** Switch the AI on after a first look around (so its first decision is not taken blind). */
    private static void wake(GameTestHelper h, ClonePlayer c) {
        c.controller().perception().update(h.getLevel().getGameTime());
        c.setAiEnabled(true);
    }

    private static void finish(GameTestHelper h, ClonePlayer... clones) {
        for (ClonePlayer c : clones) {
            ClonePlayer live = manager(h).byName(c.getGameProfile().getName());
            if (live != null) {
                manager(h).remove(live, true, Component.literal("test finished"));
            }
        }
        h.assertTrue(CloneManager.errors() == 0, "clone AI threw " + CloneManager.errors() + " errors (see log)");
        // finished arenas stay in the world (behind see-through barriers): leave no mobs there to distract later tests
        h.killAllEntities();
        clearAbove(h);
        resetArena(h);
    }

    /**
     * The arena template only holds its stone floor and barrier walls (no air): whatever a test built or dug inside
     * would still be there for the next test run at the same spot. Floor back to stone, the room above it empty.
     */
    private static void resetArena(GameTestHelper h) {
        for (int x = 1; x <= 13; x++) {
            for (int z = 1; z <= 13; z++) {
                for (int y = 0; y <= 5; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    var want = y == 1 ? Blocks.STONE : Blocks.AIR;
                    var st = h.getBlockState(p);
                    if (!st.is(want) || !h.getLevel().getFluidState(h.absolutePos(p)).isEmpty()) {
                        h.getLevel().setBlock(h.absolutePos(p), want.defaultBlockState(), 2 | 16);
                    }
                }
            }
        }
    }

    /** Whatever a test built higher than the arena (a portal's top, a pillar) would still stand there for the next test. */
    private static void clearAbove(GameTestHelper h) {
        for (int x = 0; x <= 14; x++) {
            for (int z = 0; z <= 14; z++) {
                for (int y = 6; y <= 12; y++) {
                    if (!h.getBlockState(new BlockPos(x, y, z)).isAir() && !h.getBlockState(new BlockPos(x, y, z)).is(Blocks.BARRIER)) { // (the barrier walls stop other arenas' water)
                        h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                    }
                }
                // gravel / sand hanging above the cleared space would drop into the arena (and smother whatever stands there)
                for (int y = 13; y <= 28; y++) {
                    if (h.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof net.minecraft.world.level.block.FallingBlock) {
                        h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
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
            } else {
                // walk onto the drop, as the COLLECT behaviour does
                for (net.minecraft.world.entity.item.ItemEntity item : h.getLevel().getEntitiesOfClass(
                        net.minecraft.world.entity.item.ItemEntity.class, c.getBoundingBox().inflate(4))) {
                    c.controller().motor().moveToward(item.position());
                    break;
                }
                c.controller().motor().tick();
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(h.getBlockState(log).isAir(), "log should be mined (" + c.controller().motor().mineDebug + " pos="
                    + c.position().subtract(h.absoluteVec(Vec3.ZERO)) + " alive=" + c.isAlive() + ")");
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

    // ------------------------------------------------------------------ v2 features

    @GameTest(template = ARENA, timeoutTicks = 40, batch = "many")
    public static void noCloneLimitByDefault(GameTestHelper h) {
        List<ClonePlayer> made = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            made.add(clone(h, 2.5 + (i % 6) * 2, 2.5 + (i / 6) * 2, 0f, false));
        }
        h.assertTrue(made.size() == 24, "24 clones (more than the old cap of 16) must be allowed");
        var list = h.getLevel().getServer().getPlayerList();
        h.assertTrue(list.canPlayerLogin(new java.net.InetSocketAddress("127.0.0.1", 25565),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "RealPlayer")) == null,
                "a real player must still be able to join although clones exceed max-players (" + list.getMaxPlayers() + ")");
        finish(h, made.toArray(new ClonePlayer[0]));
        h.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 40)
    public static void hearsMobOutOfSight(GameTestHelper h) {
        ClonePlayer c = clone(h, 8.5, 7.5, -90f, false); // facing +X
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(4.5, 2, 7.5)); // behind it
        husk.setNoAi(true);
        h.runAfterDelay(2, () -> {
            var p = c.controller().perception();
            h.assertFalse(p.canSee(husk), "husk is behind the clone");
            h.assertTrue(p.get(husk) == null, "not known before it makes a sound");
            husk.playAmbientSound();
            var heard = p.get(husk);
            h.assertTrue(heard != null && heard.heardAt >= 0, "clone should hear the husk groan behind it");
            h.assertFalse(p.isVisible(husk), "heard, not seen");
            finish(h, c);
            h.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 400)
    public static void cloneSwimsAcrossAndClimbsOut(GameTestHelper h) {
        for (int x = 1; x <= 9; x++) {
            for (int z = 1; z <= 13; z++) {
                for (int y = 2; y <= 4; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.WATER);
                }
            }
        }
        for (int x = 10; x <= 13; x++) {
            for (int z = 1; z <= 13; z++) {
                for (int y = 2; y <= 5; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        ClonePlayer c = clone(h, 3.5, 7.5, -90f, false);
        Vec3 goal = h.absoluteVec(new Vec3(11.5, 6, 7.5));
        h.onEachTick(() -> {
            c.controller().motor().navigate(goal, 0.8, false);
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.isAlive(), "clone must not drown");
            h.assertTrue(c.getY() >= goal.y - 0.1 && c.position().distanceTo(goal) < 2.0, "clone should swim across and climb onto the ledge");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600)
    public static void craftsThroughTheUi(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.OAK_LOG, 3));
        h.onEachTick(() -> {
            c.controller().crafting().tick();
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.getInventory().countItem(Items.WOODEN_SWORD) >= 1, "clone should craft a wooden sword (logs -> planks -> table -> sticks -> sword)");
            boolean table = false;
            for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(1, 2, 1)), h.absolutePos(new BlockPos(13, 3, 13)))) {
                table |= h.getLevel().getBlockState(p).is(Blocks.CRAFTING_TABLE);
            }
            h.assertTrue(table, "clone should have placed a crafting table and used it");
            h.assertTrue(c.containerMenu == c.inventoryMenu || c.controller().crafting().isUsingUi(), "menus are handled");
            finish(h, c);
        });
    }

    /** More raw iron found while a batch cooks is added to the running furnace (no waiting for the batch to end). */
    @GameTest(template = ARENA, timeoutTicks = 1500)
    public static void topsUpARunningFurnace(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.FURNACE));
        c.getInventory().add(new ItemStack(Items.RAW_IRON, 2));
        c.getInventory().add(new ItemStack(Items.COAL, 2));
        boolean[] added = {false};
        h.onEachTick(() -> {
            c.controller().crafting().tick();
            c.controller().motor().tick();
            if (!added[0]) {
                for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(1, 0, 1)), h.absolutePos(new BlockPos(13, 4, 13)))) {
                    if (h.getLevel().getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity f
                            && !f.getItem(0).isEmpty()) {
                        c.getInventory().add(new ItemStack(Items.RAW_IRON, 2));
                        added[0] = true;
                    }
                }
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(added[0] && c.getInventory().countItem(Items.IRON_INGOT) >= 4, "all 4 smelted, has " + c.getInventory().countItem(Items.IRON_INGOT)
                    + " added=" + added[0] + " raw=" + c.getInventory().countItem(Items.RAW_IRON));
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1000)
    public static void smeltsInAFurnace(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.FURNACE));
        c.getInventory().add(new ItemStack(Items.RAW_IRON, 2));
        c.getInventory().add(new ItemStack(Items.COAL, 1));
        h.onEachTick(() -> {
            c.controller().crafting().tick();
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.getInventory().countItem(Items.IRON_INGOT) >= 2, "clone should smelt raw iron through the furnace screen, has "
                    + c.getInventory().countItem(Items.IRON_INGOT));
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    public static void clonesGlideWithElytra(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, true);
        c.getInventory().add(new ItemStack(Items.ELYTRA));
        Vec3 high = h.absoluteVec(new Vec3(7.5, 24, 7.5));
        c.teleportTo(h.getLevel(), high.x, high.y, high.z, 0f, 0f);
        boolean[] flew = {false};
        double[] maxFall = {0};
        h.onEachTick(() -> {
            flew[0] |= c.isFallFlying();
            maxFall[0] = Math.max(maxFall[0], c.fallDistance);
        });
        h.succeedWhen(() -> {
            h.assertTrue(flew[0], "clone should put on the elytra and glide when falling: y=" + c.getY() + " ground=" + c.onGround()
                    + " maxFall=" + maxFall[0] + " chest=" + c.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)
                    + " errors=" + CloneManager.errors() + " alive=" + c.isAlive() + " ai=" + c.isAiEnabled());
            h.assertTrue(c.isAlive(), "and survive");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 240, batch = "fall")
    public static void fallDamageLikeAPlayer(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        Vec3 high = h.absoluteVec(new Vec3(7.5, 12, 7.5));
        boolean[] dropped = {false};
        float[] lowest = {20f};
        StringBuilder trace = new StringBuilder();
        int[] tick = {0};
        h.onEachTick(() -> {
            tick[0]++;
            if (c.getHealth() < lowest[0] || dropped[0] && trace.length() < 600 && tick[0] % 3 == 0) {
                trace.append(' ').append(tick[0]).append(':').append(String.format("%.2f", c.getY() - high.y)).append('/')
                        .append(c.getHealth()).append('/').append(String.format("%.1f", c.fallDistance));
            }
            lowest[0] = Math.min(lowest[0], c.getHealth());
        });
        // wait out the 3 s spawn protection every freshly joined player has
        h.runAfterDelay(70, () -> {
            c.teleportTo(h.getLevel(), high.x, high.y, high.z, 0f, 0f);
            dropped[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(dropped[0] && c.onGround() && c.getY() < high.y - 5, "landed");
            // 10 blocks -> 7 damage for an unarmoured player
            h.assertTrue(lowest[0] <= 14f && lowest[0] >= 12f, "fall damage should match a player's, lowest health=" + lowest[0] + " trace" + trace);
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1200)
    public static void clonesRaiseShields(GameTestHelper h) {
        ClonePlayer c = clone(h, 5.5, 7.5, -90f, true);
        c.getInventory().add(new ItemStack(Items.SHIELD));
        c.getInventory().add(new ItemStack(Items.IRON_SWORD));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 3000, 4));
        c.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 3000, 2));
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(8.5, 2, 7.5));
        husk.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 3000, 4));
        boolean[] blocked = {false};
        h.onEachTick(() -> blocked[0] |= c.isBlocking());
        h.succeedWhen(() -> {
            h.assertTrue(c.getOffhandItem().is(Items.SHIELD), "shield goes to the off hand");
            h.assertTrue(blocked[0], "clone should raise its shield against the husk");
            finish(h, c);
        });
    }

    // ------------------------------------------------------------------ v3: weapons, escaping, chat

    private static void walls(GameTestHelper h, int cx, int cz, int top, net.minecraft.world.level.block.Block block) {
        for (int y = 2; y <= top; y++) {
            h.setBlock(new BlockPos(cx + 1, y, cz), block);
            h.setBlock(new BlockPos(cx - 1, y, cz), block);
            h.setBlock(new BlockPos(cx, y, cz + 1), block);
            h.setBlock(new BlockPos(cx, y, cz - 1), block);
        }
    }

    private static Husk dummy(GameTestHelper h, double x, double z) {
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(x, 2, z));
        husk.setNoAi(true);
        husk.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        return husk;
    }

    @GameTest(template = ARENA, timeoutTicks = 20)
    public static void recognisesWeaponKinds(GameTestHelper h) {
        h.assertTrue(com.rlclones.ai.Equipment.rangedKind(new ItemStack(Items.CROSSBOW)) == com.rlclones.ai.Equipment.RangedKind.CROSSBOW, "crossbow");
        h.assertTrue(com.rlclones.ai.Equipment.rangedKind(new ItemStack(Items.BOW)) == com.rlclones.ai.Equipment.RangedKind.BOW, "bow");
        h.assertTrue(com.rlclones.ai.Equipment.rangedKind(new ItemStack(Items.TRIDENT)) == com.rlclones.ai.Equipment.RangedKind.THROWN, "trident");
        h.assertTrue(com.rlclones.ai.Equipment.overridesUse(Items.TRIDENT), "trident has a right-click use");
        h.assertFalse(com.rlclones.ai.Equipment.overridesUse(Items.DIAMOND_SWORD), "plain sword has none");
        h.assertFalse(com.rlclones.ai.Equipment.isSpecialWeapon(new ItemStack(Items.DIAMOND_SWORD)), "sword is a plain melee weapon");
        h.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 600)
    public static void firesCrossbow(GameTestHelper h) {
        ClonePlayer c = clone(h, 3.5, 7.5, -90f, true);
        c.getInventory().add(new ItemStack(Items.CROSSBOW));
        c.getInventory().add(new ItemStack(Items.ARROW, 16));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.controller().forcedAction = com.rlclones.ai.combat.CombatAction.SHOOT;
        dummy(h, 11.5, 7.5);
        boolean[] fired = {false};
        h.onEachTick(() -> fired[0] |= !h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.projectile.AbstractArrow.class,
                c.getBoundingBox().inflate(20), a -> a.getOwner() == c).isEmpty());
        h.succeedWhen(() -> {
            h.assertTrue(fired[0], "clone should load and fire the crossbow");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600)
    public static void throwsTrident(GameTestHelper h) {
        ClonePlayer c = clone(h, 3.5, 7.5, -90f, true);
        c.getInventory().add(new ItemStack(Items.TRIDENT));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.controller().forcedAction = com.rlclones.ai.combat.CombatAction.SHOOT;
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FIGHT;
        dummy(h, 11.5, 7.5);
        boolean[] thrown = {false};
        h.onEachTick(() -> thrown[0] |= !h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.projectile.ThrownTrident.class,
                c.getBoundingBox().inflate(20), a -> a.getOwner() == c).isEmpty());
        h.succeedWhen(() -> {
            h.assertTrue(thrown[0], "clone should throw the trident (options " + c.controller().optionLog + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 800)
    public static void retrievesThrownTrident(GameTestHelper h) {
        ClonePlayer c = clone(h, 3.5, 7.5, -90f, true);
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.COLLECT;
        var trident = new net.minecraft.world.entity.projectile.ThrownTrident(h.getLevel(), c, new ItemStack(Items.TRIDENT));
        trident.pickup = net.minecraft.world.entity.projectile.AbstractArrow.Pickup.ALLOWED;
        Vec3 at = h.absoluteVec(new Vec3(9.5, 2.2, 7.5));
        trident.setPos(at.x, at.y, at.z);
        trident.setDeltaMovement(0, -0.1, 0);
        h.getLevel().addFreshEntity(trident);
        h.succeedWhen(() -> {
            h.assertTrue(c.getInventory().countItem(Items.TRIDENT) >= 1, "clone should walk over and pick its trident up (trident "
                    + trident.position().subtract(h.absoluteVec(Vec3.ZERO)) + " alive=" + trident.isAlive() + " clone "
                    + c.position().subtract(h.absoluteVec(Vec3.ZERO)) + " options " + c.controller().optionLog + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 800, batch = "escape")
    public static void digsOutOfAPit(GameTestHelper h) {
        walls(h, 7, 7, 4, Blocks.STONE);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, true);
        c.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        BlockPos pit = h.absolutePos(new BlockPos(7, 2, 7));
        h.succeedWhen(() -> {
            BlockPos b = c.blockPosition();
            h.assertTrue(!(b.getX() == pit.getX() && b.getZ() == pit.getZ()) || b.getY() >= pit.getY() + 3,
                    "clone should dig itself out of the pit (mined " + c.controller().escape().blocksMined + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 800, batch = "escape")
    public static void pillarsOutOfAPit(GameTestHelper h) {
        walls(h, 7, 7, 4, Blocks.OBSIDIAN);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, true);
        c.getInventory().add(new ItemStack(Items.DIRT, 8));
        BlockPos pit = h.absolutePos(new BlockPos(7, 2, 7));
        h.succeedWhen(() -> {
            h.assertTrue(c.getY() >= pit.getY() + 3 || !(c.blockPosition().getX() == pit.getX() && c.blockPosition().getZ() == pit.getZ()),
                    "clone should pillar up out of the pit (placed " + c.controller().escape().blocksPlaced + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 300, batch = "chat")
    public static void callsSosInLavaAndOthersRead(GameTestHelper h) {
        ClonePlayer victim = clone(h, 7.5, 7.5, 0f, true);
        ClonePlayer listener = clone(h, 2.5, 2.5, 0f, false);
        victim.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 6000, 0));
        h.setBlock(new BlockPos(7, 2, 7), Blocks.LAVA);
        String name = victim.getGameProfile().getName();
        h.succeedWhen(() -> {
            boolean said = com.rlclones.ai.Chat.recent().stream().anyMatch(l -> l.startsWith(name + ": SOS ") && l.contains(" lava"));
            h.assertTrue(said, "clone in lava should post SOS with its coordinates in chat");
            boolean read = listener.controller().requests().stream().anyMatch(r -> r.urgent() && r.fromName().equals(name)
                    && r.pos().distanceTo(victim.position()) < 3);
            h.assertTrue(read, "another clone should read the SOS and its coordinates");
            finish(h, victim, listener);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 300, batch = "chat2")
    public static void callsForBackupWhenOutnumbered(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, true);
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 6000, 3));
        dummy(h, 7.5, 10.5);
        dummy(h, 9.5, 9.5);
        dummy(h, 5.5, 9.5);
        String name = c.getGameProfile().getName();
        h.succeedWhen(() -> {
            boolean said = com.rlclones.ai.Chat.recent().stream().anyMatch(l -> l.startsWith(name + ": ") && l.contains(" outnumbered ")
                    && l.contains("minecraft:husk=3"));
            h.assertTrue(said, "clone facing 3 husks should call for backup in chat");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 40, batch = "chat3")
    public static void readsPlayersCallForHelp(GameTestHelper h) {
        ClonePlayer speaker = clone(h, 3.5, 3.5, 0f, false);
        ClonePlayer listener = clone(h, 10.5, 10.5, 0f, false);
        com.rlclones.ai.Chat.deliver(speaker, "助けて！ 120 64 -35 ゾンビがいっぱい");
        var r = listener.controller().requests();
        h.assertTrue(r.size() == 1 && r.get(0).urgent() && Math.abs(r.get(0).pos().x - 120.5) < 1e-6 && Math.abs(r.get(0).pos().z + 34.5) < 1e-6,
                "clone should understand a Japanese call for help with coordinates");
        com.rlclones.ai.Chat.deliver(speaker, "help");
        h.assertTrue(listener.controller().requests().get(0).pos().distanceTo(speaker.position()) < 1e-6,
                "without coordinates the speaker's position is used");
        finish(h, speaker, listener);
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

    // ------------------------------------------------------------------ AC round 4: help count / resolved

    @GameTest(template = ARENA, timeoutTicks = 800, batch = "resolve")
    public static void reportsResolvedSoHelpersStayAway(GameTestHelper h) {
        ClonePlayer victim = clone(h, 7.5, 7.5, 0f, true);
        ClonePlayer helper = clone(h, 2.5, 2.5, 0f, false);
        victim.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 6000, 0));
        h.setBlock(new BlockPos(7, 2, 7), Blocks.LAVA);
        String name = victim.getGameProfile().getName();
        boolean[] cleared = {false};
        h.onEachTick(() -> {
            if (!cleared[0] && helper.controller().requests().stream().anyMatch(r -> r.fromName().equals(name))) {
                cleared[0] = true;
                h.setBlock(new BlockPos(7, 2, 7), Blocks.AIR); // the lava is gone: emergency over
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(cleared[0], "helper should first read the SOS");
            h.assertTrue(com.rlclones.ai.Chat.recent().stream().anyMatch(l -> l.startsWith(name + ": RESOLVED ")),
                    "victim should announce in chat that the emergency is resolved");
            h.assertTrue(helper.controller().requests().stream().noneMatch(r -> r.fromName().equals(name)),
                    "helpers forget a call that was resolved");
            h.assertFalse(helper.controller().hasHelpRequest(), "nobody should still go there");
            finish(h, victim, helper);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 40, batch = "chat4")
    public static void onlyAsManyHelpersAsNeeded(GameTestHelper h) {
        ClonePlayer asker = clone(h, 3.5, 3.5, 0f, false);
        ClonePlayer a = clone(h, 10.5, 10.5, 0f, false);
        ClonePlayer b = clone(h, 10.5, 3.5, 0f, false);
        ClonePlayer late = clone(h, 3.5, 10.5, 0f, false);
        String an = asker.getGameProfile().getName();
        BlockPos p = asker.blockPosition();
        com.rlclones.ai.Chat.deliver(asker, "HELP " + p.getX() + " " + p.getY() + " " + p.getZ() + " outnumbered minecraft:zombie=3 need=2");
        h.assertTrue(late.controller().requests().size() == 1 && late.controller().requests().get(0).need() == 2, "need count is read from chat");
        h.assertTrue(late.controller().hasHelpRequest(), "nobody on the way yet: go");
        com.rlclones.ai.Chat.deliver(a, "OMW " + an);
        h.assertTrue(late.controller().hasHelpRequest(), "1 of 2 helpers on the way: still needed");
        com.rlclones.ai.Chat.deliver(b, "OMW " + an);
        h.assertFalse(late.controller().hasHelpRequest(), "2 of 2 helpers on the way: the rest stay put");
        com.rlclones.ai.Chat.deliver(asker, "もう大丈夫、解決した！");
        h.assertTrue(late.controller().requests().isEmpty(), "a player's 'resolved' message clears the call");
        com.rlclones.ai.Chat.deliver(asker, "応援 3人 来て 10 64 10");
        h.assertTrue(late.controller().requests().get(0).need() == 3, "'3人' in a player's message sets the number needed");
        finish(h, asker, a, b, late);
        h.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 300, batch = "chat5")
    public static void asksForMoreHelpAgainstMoreEnemies(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 5.5, 0f, true);
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 6000, 3));
        dummy(h, 7.5, 9.5);
        dummy(h, 9.5, 9.5);
        dummy(h, 5.5, 9.5);
        dummy(h, 8.5, 11.5);
        dummy(h, 6.5, 11.5);
        String name = c.getGameProfile().getName();
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().lastAlarmSent == com.rlclones.ai.CloneController.Alarm.OUTNUMBERED, "should call for backup");
            h.assertTrue(c.controller().lastNeed >= 3, "5 enemies should need at least 3 helpers, asked for " + c.controller().lastNeed);
            h.assertTrue(com.rlclones.ai.Chat.recent().stream().anyMatch(l -> l.startsWith(name + ": HELP ") && l.endsWith(" need=" + c.controller().lastNeed)),
                    "the number needed is in the chat message");
            finish(h, c);
        });
    }

    // ------------------------------------------------------------------ AC round 4: harmful blocks

    @GameTest(template = ARENA, timeoutTicks = 1600, batch = "hazard")
    public static void learnsMagmaRemovesItAndTellsOthers(GameTestHelper h) {
        h.setBlock(new BlockPos(7, 0, 7), Blocks.STONE);
        h.setBlock(new BlockPos(7, 1, 7), Blocks.MAGMA_BLOCK);
        h.setBlock(new BlockPos(7, 0, 11), Blocks.STONE);
        h.setBlock(new BlockPos(7, 1, 11), Blocks.MAGMA_BLOCK);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        ClonePlayer friend = clone(h, 4.5, 11.5, -135f, false);
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 1));
        String magma = "minecraft:magma_block";
        // stand on the magma until the spawn protection (60 ticks) wears off, then act freely
        h.runAfterDelay(90, () -> c.setAiEnabled(true));
        h.succeedWhen(() -> {
            h.assertTrue(c.getCloneBrain().isHarmful(magma), "magma damage should teach that magma blocks are harmful");
            h.assertFalse(c.getCloneBrain().isHarmful("minecraft:stone"), "the floor is not harmful");
            boolean first = !h.getLevel().getBlockState(h.absolutePos(new BlockPos(7, 1, 7))).is(Blocks.MAGMA_BLOCK);
            boolean second = !h.getLevel().getBlockState(h.absolutePos(new BlockPos(7, 1, 11))).is(Blocks.MAGMA_BLOCK);
            h.assertTrue(first && second, "both magma blocks should be removed (first=" + first + " second=" + second + ") alive=" + c.isAlive()
                    + " " + c.controller().hazardDebug() + " magma=" + h.absolutePos(new BlockPos(7, 1, 7)).toShortString());
            h.assertTrue(c.controller().hazardsCleaned >= 2, "both magma blocks should be broken by the clone");
            h.assertTrue(friend.getCloneBrain().isHarmful(magma), "a clone nearby should be told about the harmful block");
            finish(h, c, friend);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 40, batch = "hazard2")
    public static void learnsUnknownHarmfulBlock(GameTestHelper h) {
        // stands for a modded block (e.g. an infection block) that hurts with its own / generic damage type
        h.setBlock(new BlockPos(7, 1, 7), Blocks.MOSS_BLOCK);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        var src = h.getLevel().damageSources().generic();
        c.controller().onEnvironmentDamage(src);
        h.assertFalse(c.getCloneBrain().isHarmful("minecraft:moss_block"), "one hit is not enough evidence");
        c.controller().onEnvironmentDamage(src);
        c.controller().onEnvironmentDamage(src);
        h.assertTrue(c.getCloneBrain().isHarmful("minecraft:moss_block"), "the block touched every time it hurt is learned as harmful");
        h.assertFalse(c.getCloneBrain().isHarmful("minecraft:barrier") || c.getCloneBrain().isHarmful("minecraft:stone"), "only the culprit");
        finish(h, c);
        h.succeed();
    }

    // ------------------------------------------------------------------ AC round 4: clone limit command

    @GameTest(template = ARENA, timeoutTicks = 40, batch = "limit")
    public static void limitCommandChangesTheCap(GameTestHelper h) {
        var server = h.getLevel().getServer();
        int existing = manager(h).clones().size();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "rlclone limit " + (existing + 2));
        h.assertTrue(com.rlclones.Config.cloneLimit() == existing + 2, "/rlclone limit N sets the cap");
        ClonePlayer a = clone(h, 3.5, 3.5, 0f, false);
        ClonePlayer b = clone(h, 5.5, 3.5, 0f, false);
        ClonePlayer over = manager(h).summon(null, h.getLevel(), h.absoluteVec(new Vec3(7.5, 2, 7.5)), 0f);
        h.assertTrue(over == null, "the cap is enforced");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "rlclone limit 100");
        ClonePlayer c = clone(h, 7.5, 3.5, 0f, false);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "rlclone limit unlimited");
        h.assertTrue(com.rlclones.Config.cloneLimit() == Integer.MAX_VALUE, "/rlclone limit unlimited removes the cap");
        h.assertTrue(server.getPlayerList().getMaxPlayers() > server.getPlayerList().getPlayerCount(), "a real player can still join");
        finish(h, a, b, c);
        h.succeed();
    }

    // ------------------------------------------------------------------ AC round 4: chests and bases

    private static void clearBases(GameTestHelper h) {
        com.rlclones.clone.Bases b = com.rlclones.clone.Bases.get(h.getLevel().getServer());
        b.bases.clear();
        b.pens.clear();
        b.portals.clear();
        b.staircases.clear();
        b.shafts.clear();
        b.projects.clear();
        b.plans.clear();
        b.setDirty();
    }

    private static void runStorage(GameTestHelper h, ClonePlayer c, com.rlclones.ai.Storage.Mode mode, boolean[] started) {
        h.onEachTick(() -> {
            var ctl = c.controller();
            ctl.perception().update(h.getLevel().getGameTime());
            if (!started[0]) {
                boolean can = switch (mode) {
                    case LOOT -> ctl.storage().canLoot();
                    case STORE -> ctl.storage().canStore();
                    case FETCH -> ctl.storage().canFetch();
                };
                if (can) {
                    ctl.storage().begin(mode);
                    started[0] = true;
                }
            }
            if (started[0]) {
                ctl.storage().tick();
            }
            ctl.motor().tick();
        });
    }

    private static boolean said(String name, String prefix) {
        return com.rlclones.ai.Chat.recent().stream().anyMatch(l -> l.startsWith(name + ": " + prefix));
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "loot")
    public static void lootsStrayChest(GameTestHelper h) {
        clearBases(h);
        BlockPos rel = new BlockPos(7, 2, 11);
        h.setBlock(rel, Blocks.CHEST);
        BlockPos abs = h.absolutePos(rel);
        ((net.minecraft.world.level.block.entity.ChestBlockEntity) h.getLevel().getBlockEntity(abs))
                .setLootTable(net.minecraft.world.level.storage.loot.BuiltInLootTables.SIMPLE_DUNGEON, 42L);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        String name = c.getGameProfile().getName();
        runStorage(h, c, com.rlclones.ai.Storage.Mode.LOOT, new boolean[1]);
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().storage().lootings >= 1, "clone should open the dungeon chest and take its loot");
            h.assertFalse(c.getInventory().isEmpty(), "loot should be in the clone's inventory");
            h.assertTrue(said(name, "LOOT " + abs.getX() + " " + abs.getY() + " " + abs.getZ() + " "), "looting is reported in chat with coordinates and items");
            h.assertTrue(c.containerMenu == c.inventoryMenu, "the chest screen is closed again");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 3000, batch = "store")
    public static void buildsBaseAndStoresSurplus(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.IRON_SWORD));
        c.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        c.getInventory().add(new ItemStack(Items.OAK_PLANKS, 16));
        c.getInventory().add(new ItemStack(Items.STONE_SWORD));
        c.getInventory().add(new ItemStack(Items.STONE_SWORD));
        c.getInventory().add(new ItemStack(Items.BONE, 3));
        c.getInventory().add(new ItemStack(Items.STRING, 4));
        c.getInventory().add(new ItemStack(Items.GUNPOWDER, 2));
        c.getInventory().add(new ItemStack(Items.FEATHER, 5));
        h.assertTrue(com.rlclones.ai.Storage.needsStore(c), "surplus weapons and junk should make the clone want to store");
        String name = c.getGameProfile().getName();
        runStorage(h, c, com.rlclones.ai.Storage.Mode.STORE, new boolean[1]);
        h.succeedWhen(() -> {
            com.rlclones.clone.Bases bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
            com.rlclones.clone.Bases.Base base = bases.nearest(h.getLevel().dimension(), c.position(), 16);
            h.assertTrue(base != null, "clone should found a base");
            h.assertTrue(c.controller().storage().builder().placed >= 45, "clone should build the hut (placed " + c.controller().storage().builder().placed
                    + c.controller().storage().builder().debug + ")");
            h.assertTrue(!base.chests.isEmpty(), "a storage chest is placed in the base");
            var chest = (net.minecraft.world.Container) h.getLevel().getBlockEntity(base.chests.get(0));
            h.assertTrue(chest != null && chest.hasAnyOf(java.util.Set.of(Items.BONE, Items.STONE_SWORD, Items.STRING)), "surplus is put into the chest");
            h.assertTrue(c.getInventory().countItem(Items.IRON_SWORD) == 1, "the weapon in use is kept");
            h.assertTrue(said(name, "BASE ") && said(name, "DEPOSIT "), "base and deposit are reported in chat");
            h.assertTrue(com.rlclones.ai.Chat.recent().stream().anyMatch(l -> l.startsWith(name + ": DEPOSIT ") && l.contains("minecraft:bone=3")),
                    "the deposit message lists the items");
            finish(h, c);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "fetch")
    public static void fetchesNeededGearFromBase(GameTestHelper h) {
        clearBases(h);
        BlockPos rel = new BlockPos(7, 2, 11);
        h.setBlock(rel, Blocks.CHEST);
        BlockPos abs = h.absolutePos(rel);
        var be = (net.minecraft.world.level.block.entity.ChestBlockEntity) h.getLevel().getBlockEntity(abs);
        be.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        be.setItem(1, new ItemStack(Items.IRON_CHESTPLATE));
        be.setItem(2, new ItemStack(Items.DIRT, 10));
        com.rlclones.clone.Bases bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
        var base = bases.add(h.getLevel().dimension(), abs, "someone");
        bases.addChest(base, abs);
        bases.record(h.getLevel().dimension(), abs, be);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.WOODEN_SWORD));
        c.getInventory().add(new ItemStack(Items.COBBLESTONE, 32));
        h.assertTrue(c.controller().storage().canFetch(), "the base chest (known from the shared index) holds something better");
        String name = c.getGameProfile().getName();
        runStorage(h, c, com.rlclones.ai.Storage.Mode.FETCH, new boolean[1]);
        h.succeedWhen(() -> {
            h.assertTrue(c.getInventory().countItem(Items.DIAMOND_SWORD) + (c.getMainHandItem().is(Items.DIAMOND_SWORD) ? 1 : 0) >= 1, "clone takes the better sword");
            h.assertTrue(c.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE)
                    || c.getInventory().countItem(Items.IRON_CHESTPLATE) == 1, "and the armour");
            h.assertTrue(be.countItem(Items.DIRT) == 10, "things it does not need stay in the chest");
            h.assertTrue(com.rlclones.ai.Chat.recent().stream().anyMatch(l -> l.startsWith(name + ": WITHDRAW " + abs.getX() + " " + abs.getY() + " " + abs.getZ())
                    && l.contains("minecraft:diamond_sword=1")), "withdrawal is reported in chat with coordinates and items");
            finish(h, c);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "reuse")
    public static void usesExistingBaseNearby(GameTestHelper h) {
        clearBases(h);
        BlockPos rel = new BlockPos(7, 2, 11);
        h.setBlock(rel, Blocks.CHEST);
        BlockPos abs = h.absolutePos(rel);
        com.rlclones.clone.Bases bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
        var base = bases.add(h.getLevel().dimension(), abs, "other-clone");
        bases.addChest(base, abs);
        ClonePlayer c = clone(h, 7.5, 5.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.IRON_SWORD));
        c.getInventory().add(new ItemStack(Items.STONE_SWORD));
        c.getInventory().add(new ItemStack(Items.BONE, 3));
        c.getInventory().add(new ItemStack(Items.STRING, 4));
        c.getInventory().add(new ItemStack(Items.GUNPOWDER, 2));
        c.getInventory().add(new ItemStack(Items.FEATHER, 5));
        String name = c.getGameProfile().getName();
        runStorage(h, c, com.rlclones.ai.Storage.Mode.STORE, new boolean[1]);
        h.succeedWhen(() -> {
            var be = (net.minecraft.world.Container) h.getLevel().getBlockEntity(abs);
            h.assertTrue(be.hasAnyOf(java.util.Set.of(Items.BONE)), "surplus goes into the other clone's base chest");
            h.assertTrue(bases.bases.size() == 1, "no second base is founded next to an existing one");
            h.assertTrue(said(name, "DEPOSIT " + abs.getX() + " " + abs.getY() + " " + abs.getZ()), "deposit is reported in chat");
            finish(h, c);
            clearBases(h);
        });
    }

    // ------------------------------------------------------------------ AC round 4: farming

    @GameTest(template = ARENA, timeoutTicks = 800, batch = "farm")
    public static void tillsSoilByWaterAndPlants(GameTestHelper h) {
        h.setBlock(new BlockPos(7, 0, 10), Blocks.STONE); // the water must not run away under the floor
        h.setBlock(new BlockPos(7, 1, 10), Blocks.WATER);
        for (int x = 6; x <= 8; x++) {
            h.setBlock(new BlockPos(x, 1, 11), Blocks.GRASS_BLOCK);
        }
        h.setBlock(new BlockPos(6, 1, 10), Blocks.DIRT);
        h.setBlock(new BlockPos(8, 1, 10), Blocks.DIRT);
        h.setBlock(new BlockPos(10, 2, 12), Blocks.GLOWSTONE); // crops need light (>= 8) to be planted
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.WOODEN_HOE));
        c.getInventory().add(new ItemStack(Items.WHEAT_SEEDS, 8));
        boolean[] work = {false};
        h.onEachTick(() -> {
            if (!work[0]) {
                work[0] = c.controller().farming().hasWork(); // (asked once the glowstone light has spread)
                return;
            }
            c.controller().farming().tick();
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            int planted = 0;
            for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(5, 1, 9)), h.absolutePos(new BlockPos(9, 1, 12)))) {
                if (h.getLevel().getBlockState(p).is(Blocks.FARMLAND) && h.getLevel().getBlockState(p.above()).is(Blocks.WHEAT)) {
                    planted++;
                }
            }
            h.assertTrue(work[0], "soil next to water + hoe + seeds = work (" + c.controller().farming().diag() + ")");
            h.assertTrue(planted >= 4, "clone should till the soil by the water with the hoe and plant seeds (" + planted + ") "
                    + c.controller().farming().debug());
            finish(h, c);
        });
    }

    // ------------------------------------------------------------------ AC round 4: expeditions

    @GameTest(template = ARENA, timeoutTicks = 2000, batch = "expedition")
    public static void expeditionRalliesAndFollows(GameTestHelper h) {
        ClonePlayer leader = clone(h, 3.5, 3.5, 0f, false);
        ClonePlayer m1 = clone(h, 11.5, 11.5, 0f, false);
        ClonePlayer m2 = clone(h, 11.5, 3.5, 0f, false);
        ClonePlayer extra = clone(h, 3.5, 11.5, 0f, false);
        int need = leader.controller().expedition().companionsNeeded(h.absolutePos(new BlockPos(200, 2, 200)), leader.getCloneBrain());
        h.assertTrue(need >= 1 && need <= 4, "leader decides how many companions it needs");
        String ln = leader.getGameProfile().getName();
        leader.controller().expedition().lead(h.absolutePos(new BlockPos(11, 2, 11)), 2);
        double[] maxGap = {0};
        h.onEachTick(() -> {
            leader.controller().expedition().leadTick();
            for (ClonePlayer m : List.of(m1, m2, extra)) {
                var ex = m.controller().expedition();
                if (ex.joinedOffer() == null && ex.completed == 0 && ex.canJoin(h.getLevel().getGameTime())) {
                    ex.join();
                }
                if (ex.joinedOffer() != null) {
                    ex.followTick();
                }
                m.controller().motor().tick();
            }
            leader.controller().motor().tick();
            if (said(ln, "DEPART ") && m1.controller().expedition().joinedOffer() != null && leader.distanceTo(m1) > maxGap[0]) {
                maxGap[0] = leader.distanceTo(m1);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(com.rlclones.ai.Chat.recent().stream().anyMatch(l -> l.startsWith(ln + ": RALLY ") && l.endsWith(" need=2")),
                    "leader calls a rally point with the number needed");
            h.assertTrue(said(m1.getGameProfile().getName(), "JOIN " + ln) && said(m2.getGameProfile().getName(), "JOIN " + ln), "two clones answer");
            h.assertTrue(extra.controller().expedition().joinedTrips == 0, "no more than needed join");
            h.assertTrue(said(ln, "DEPART ") && said(ln, "EXPEDITION_END"), "the group departs and the leader ends the trip");
            h.assertTrue(m1.controller().expedition().completed == 1 && m2.controller().expedition().completed == 1, "companions stay until the goal is reached");
            h.assertTrue(maxGap[0] < 16, "companions follow the leader (max gap " + maxGap[0] + ")");
            finish(h, leader, m1, m2, extra);
        });
    }

    // ------------------------------------------------------------------ AC round 5: L key teaches a harmful block

    @GameTest(template = ARENA, timeoutTicks = 40, batch = "teach")
    public static void lKeyTeachesHeldBlockAsHarmful(GameTestHelper h) {
        ClonePlayer a = clone(h, 3.5, 3.5, 0f, false);
        ClonePlayer b = clone(h, 10.5, 10.5, 0f, false);
        a.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SPONGE));
        manager(h).handleAction(a, ClientAction.TEACH_HARMFUL);
        h.assertTrue(a.getCloneBrain().isHarmful("minecraft:sponge") && b.getCloneBrain().isHarmful("minecraft:sponge"),
                "every clone learns that the held block is harmful");
        b.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        h.assertTrue(manager(h).teachHarmful(b) == 0, "holding something that is not a block teaches nothing");
        finish(h, a, b);
        h.succeed();
    }

    // ------------------------------------------------------------------ AC round 5: curiosity and item knowledge

    @GameTest(template = ARENA, timeoutTicks = 800, batch = "discover")
    public static void minesUnknownBlockLearnsWhatItIsAndTellsOthers(GameTestHelper h) {
        clearBases(h);
        h.setBlock(new BlockPos(7, 2, 10), Blocks.HAY_BLOCK);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        ClonePlayer friend = clone(h, 4.5, 11.5, 180f, false); // off to the side: it must not pick up the bale itself
        String name = c.getGameProfile().getName();
        boolean[] started = {false};
        boolean[] done = {false};
        h.onEachTick(() -> {
            var ctl = c.controller();
            long now = h.getLevel().getGameTime();
            ctl.perception().update(now);
            if (!started[0] && ctl.discovery().canDiscover()) {
                ctl.discovery().begin();
                started[0] = true;
            }
            if (started[0] && !done[0]) {
                done[0] = ctl.discovery().tick();
            }
            ctl.motor().tick();
            if (now % 20 == 0) {
                ctl.discovery().watchInventory();
            }
            if (now % 40 == 0) {
                ctl.discovery().share(now);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.getInventory().countItem(Items.HAY_BLOCK) >= 1, "clone should mine the unknown hay bale and pick it up: started="
                    + started[0] + " done=" + done[0] + " " + c.controller().discovery().debug() + " hay="
                    + h.getLevel().getBlockState(h.absolutePos(new BlockPos(7, 2, 10))) + " pos=" + c.position() + " friendHay=" + friend.getInventory().countItem(Items.HAY_BLOCK));
            String facts = c.getCloneBrain().facts("minecraft:hay_block");
            h.assertTrue(facts.contains("craft=") && facts.contains("minecraft:wheat") && facts.contains("then=") && facts.contains("minecraft:bread"),
                    "it should understand that the hay bale gives wheat and from that bread (" + facts + ")");
            h.assertTrue(said(name, "DISCOVER minecraft:hay_block"), "the discovery is announced in chat");
            h.assertTrue(friend.getCloneBrain().knowsItem("minecraft:hay_block"), "a clone nearby that did not know it is told (told="
                    + c.controller().discovery().told + " seen=" + (c.controller().perception().get(friend) != null) + " "
                    + c.controller().perception().explain(friend) + ")");
            finish(h, c, friend);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 40, batch = "facts")
    public static void understandsBlocksAndItems(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        String stone = com.rlclones.ai.Discovery.facts(c, new ItemStack(Items.COBBLESTONE));
        h.assertTrue(stone.contains("spawn") && !stone.contains("nospawn"), "a full block lets monsters spawn on it (" + stone + ")");
        String glass = com.rlclones.ai.Discovery.facts(c, new ItemStack(Items.GLASS));
        h.assertTrue(glass.contains("nospawn"), "monsters cannot spawn on glass (" + glass + ")");
        String coal = com.rlclones.ai.Discovery.facts(c, new ItemStack(Items.COAL));
        h.assertTrue(coal.contains("fuel="), "coal is fuel (" + coal + ")");
        String iron = com.rlclones.ai.Discovery.facts(c, new ItemStack(Items.RAW_IRON));
        h.assertTrue(iron.contains("smelt=minecraft:iron_ingot"), "raw iron smelts into an ingot (" + iron + ")");
        String bread = com.rlclones.ai.Discovery.facts(c, new ItemStack(Items.BREAD));
        h.assertTrue(bread.contains("food=5"), "bread is food (" + bread + ")");
        finish(h, c);
        h.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "curious")
    public static void craftsItemsItNeverHad(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.WOODEN_PICKAXE));
        c.getInventory().add(new ItemStack(Items.WOODEN_AXE));
        c.getInventory().add(new ItemStack(Items.WOODEN_SWORD));
        c.getInventory().add(new ItemStack(Items.CRAFTING_TABLE));
        c.getInventory().add(new ItemStack(Items.STICK, 4));
        c.getInventory().add(new ItemStack(Items.OAK_PLANKS, 8));
        c.controller().discovery().watchInventory();
        java.util.Set<String> before = new java.util.HashSet<>(c.getCloneBrain().knownItems());
        h.onEachTick(() -> {
            c.controller().perception().update(h.getLevel().getGameTime());
            c.controller().crafting().tick();
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            java.util.Set<String> now = new java.util.HashSet<>(c.getCloneBrain().knownItems());
            now.removeAll(before);
            h.assertTrue(!now.isEmpty() && c.controller().crafting().crafted >= 1, "clone should craft something it never had and learn it");
            finish(h, c);
        });
    }

    // ------------------------------------------------------------------ AC round 5: look-around speed is the clone's choice

    @GameTest(template = ARENA, timeoutTicks = 2400, batch = "look")
    public static void learnsItsOwnLookAroundSpeed(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, true);
        pig(h, 3.5, 11.5);
        pig(h, 11.5, 3.5);
        h.succeedWhen(() -> {
            long distinct = c.controller().lookHistory.stream().distinct().count();
            h.assertTrue(c.getCloneBrain().lookUpdates >= 3, "look-arounds should be scored and learned (" + c.getCloneBrain().lookUpdates + ")");
            h.assertTrue(distinct >= 2, "the clone tries different look-around speeds " + c.controller().lookHistory);
            finish(h, c);
        });
    }

    // ------------------------------------------------------------------ AC round 5: boats, no crafting at sea

    private static void pool(GameTestHelper h, int z0, int z1) {
        for (int x = 1; x <= 13; x++) {
            for (int z = z0; z <= z1; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                h.setBlock(new BlockPos(x, 1, z), Blocks.WATER);
            }
        }
    }

    @GameTest(template = ARENA, timeoutTicks = 1200, batch = "boat")
    public static void crossesWaterByBoat(GameTestHelper h) {
        pool(h, 4, 10);
        ClonePlayer c = clone(h, 7.5, 2.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.OAK_BOAT));
        Vec3 goal = h.absoluteVec(new Vec3(7.5, 2, 12.5));
        h.onEachTick(() -> {
            c.controller().motor().navigate(goal, 1.0, false);
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().motor().boating().rodeBoat, "clone should put its boat on the water and ride it");
            h.assertTrue(c.position().distanceTo(goal) < 2.0 && !c.isPassenger(), "and get out on the other side (at "
                    + c.position().subtract(h.absoluteVec(Vec3.ZERO)) + " riding " + c.isPassenger() + " " + c.controller().motor().boating().state() + ")");
            h.assertTrue(com.rlclones.ai.Boating.boatSlot(c) >= 0, "the boat is broken back into an item and taken along");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 800, batch = "boat2")
    public static void craftsBoatAfterSwimmingALot(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.OAK_LOG, 3));
        c.controller().noteSwimming(400);
        h.onEachTick(() -> {
            c.controller().perception().update(h.getLevel().getGameTime());
            c.controller().crafting().tick();
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(com.rlclones.ai.Boating.boatSlot(c) >= 0, "a clone that swims a lot should craft a boat: " + c.controller().crafting().debug());
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 60, batch = "boat3")
    public static void doesNotTryToCraftWhileSwimming(GameTestHelper h) {
        pool(h, 4, 10);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.teleportTo(h.getLevel(), c.getX(), c.getY() - 0.9, c.getZ(), 0f, 0f);
        c.getInventory().add(new ItemStack(Items.OAK_LOG, 4));
        h.runAfterDelay(10, () -> {
            long now = h.getLevel().getGameTime();
            h.assertTrue(c.isInWater(), "clone is in the water");
            h.assertTrue(c.controller().crafting().hasWork(), "there would be something to craft");
            int mask = com.rlclones.ai.Senses.strategyMask(c.controller().perception(), c, c, now);
            h.assertTrue((mask & com.rlclones.ai.strategy.Option.CRAFT.bit()) == 0, "but no crafting (or placing a table) while in the water");
            finish(h, c);
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ AC round 5: stone age

    @GameTest(template = ARENA, timeoutTicks = 4800, batch = "stone")
    public static void minesStoneAndBuildsAFurnace(GameTestHelper h) {
        // a rock to quarry (the arena floor is a thin slab over nothing: a careful clone does not dig holes in it)
        for (int x = 10; x <= 12; x++) {
            for (int z = 9; z <= 11; z++) {
                for (int y = 2; y <= 3; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, true);
        c.getInventory().add(new ItemStack(Items.WOODEN_PICKAXE));
        c.getInventory().add(new ItemStack(Items.WOODEN_SWORD));
        c.getInventory().add(new ItemStack(Items.WOODEN_AXE));
        c.getInventory().add(new ItemStack(Items.STICK, 8));
        c.getInventory().add(new ItemStack(Items.CRAFTING_TABLE));
        h.succeedWhen(() -> {
            boolean furnace = false;
            for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(1, 0, 1)), h.absolutePos(new BlockPos(13, 4, 13)))) {
                furnace |= h.getLevel().getBlockState(p).is(Blocks.FURNACE);
            }
            h.assertTrue(c.getCloneBrain().obtained("minecraft:cobblestone"), "clone should mine stone and get cobblestone: quarried="
                    + c.controller().quarried + " stoneNeeded=" + com.rlclones.ai.Crafting.stoneNeeded(c) + " options=" + c.controller().optionLog
                    + " stoneSeen=" + c.controller().perception().blocks().values().stream().filter(k -> k == com.rlclones.ai.Perception.BlockKind.STONE).count()
                    + " " + c.controller().crafting().debug() + " alive=" + c.isAlive() + " death=" + c.controller().lastDeath
                    + " harvest=" + c.controller().harvestDebug + " mine=" + c.controller().motor().mineDebug + " pos="
                    + c.blockPosition().subtract(h.absolutePos(BlockPos.ZERO)).toShortString());
            h.assertTrue(furnace, "and craft a furnace and put it down (cobble " + c.getInventory().countItem(Items.COBBLESTONE)
                    + ", option " + c.controller().option() + ") quarried=" + c.controller().quarried + " placed=" + c.controller().escape().blocksPlaced
                    + " stoneNeeded=" + com.rlclones.ai.Crafting.stoneNeeded(c) + " options=" + c.controller().optionLog + " " + c.controller().crafting().debug()
                    + " alive=" + c.isAlive() + " death=" + c.controller().lastDeath + " pos=" + c.blockPosition().subtract(h.absolutePos(BlockPos.ZERO)).toShortString());
            finish(h, c);
        });
    }

    // ------------------------------------------------------------------ AC round 5: brewing and potions

    private static ItemStack potion(net.minecraft.world.item.Item item, net.minecraft.world.item.alchemy.Potion p) {
        return net.minecraft.world.item.alchemy.PotionUtils.setPotion(new ItemStack(item), p);
    }

    @GameTest(template = ARENA, timeoutTicks = 800, batch = "brew")
    public static void craftsAndPlacesBrewingStand(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.BLAZE_ROD));
        c.getInventory().add(new ItemStack(Items.COBBLESTONE, 3));
        c.getInventory().add(new ItemStack(Items.CRAFTING_TABLE));
        c.getInventory().add(new ItemStack(Items.WOODEN_SWORD));
        h.onEachTick(() -> {
            c.controller().perception().update(h.getLevel().getGameTime());
            c.controller().crafting().tick();
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            boolean stand = false;
            for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(1, 2, 1)), h.absolutePos(new BlockPos(13, 3, 13)))) {
                stand |= h.getLevel().getBlockState(p).is(Blocks.BREWING_STAND);
            }
            h.assertTrue(stand, "clone should craft a brewing stand (blaze rod + cobblestone) and place it");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 400, batch = "brew2")
    public static void usesExistingBrewingStand(GameTestHelper h) {
        h.setBlock(new BlockPos(7, 2, 9), Blocks.BREWING_STAND);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.BLAZE_ROD));
        c.getInventory().add(new ItemStack(Items.COBBLESTONE, 3));
        c.getInventory().add(new ItemStack(Items.CRAFTING_TABLE));
        c.getInventory().add(new ItemStack(Items.WOODEN_SWORD));
        long start = h.getLevel().getGameTime();
        h.onEachTick(() -> {
            c.controller().perception().update(h.getLevel().getGameTime());
            if (h.getLevel().getGameTime() - start > 40) { // look around first, like arriving somewhere
                c.controller().crafting().tick();
            }
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.getInventory().countItem(Items.BLAZE_POWDER) >= 1, "with a stand nearby the rod becomes fuel (blaze powder)");
            h.assertTrue(c.getInventory().countItem(Items.BREWING_STAND) == 0, "and no second stand is crafted");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1600, batch = "brew3")
    public static void brewsAPotionItNeverHad(GameTestHelper h) {
        h.setBlock(new BlockPos(7, 2, 10), Blocks.BREWING_STAND);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        for (int i = 0; i < 3; i++) {
            c.getInventory().add(potion(Items.POTION, net.minecraft.world.item.alchemy.Potions.WATER));
        }
        c.getInventory().add(new ItemStack(Items.NETHER_WART));
        c.getInventory().add(new ItemStack(Items.BLAZE_POWDER));
        String name = c.getGameProfile().getName();
        boolean[] active = {false};
        h.onEachTick(() -> {
            var ctl = c.controller();
            long now = h.getLevel().getGameTime();
            ctl.perception().update(now);
            if (now % 20 == 0) {
                ctl.discovery().watchInventory();
            }
            if (!active[0] && ctl.brewing().hasWork(now)) {
                ctl.brewing().begin();
                active[0] = true;
            }
            if (active[0] && ctl.brewing().tick(now) != com.rlclones.ai.Brewing.Status.WORKING) {
                active[0] = false;
            }
            ctl.motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.getCloneBrain().obtained("minecraft:potion#minecraft:awkward"), "clone should brew the awkward potion it never had");
            h.assertTrue(said(name, "DISCOVER minecraft:potion#minecraft:awkward"), "and announce what it got");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 300, batch = "potion")
    public static void drinksHealingPotionWhenLow(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, true);
        c.getInventory().add(potion(Items.POTION, net.minecraft.world.item.alchemy.Potions.HEALING));
        c.setHealth(5f);
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().consumables().potionsUsed >= 1 && c.getInventory().countItem(Items.GLASS_BOTTLE) >= 1,
                    "a clone low on health should drink its (learned) healing potion");
            h.assertTrue(c.getHealth() > 5f, "and get healed");
            finish(h, c);
        });
    }

    // ------------------------------------------------------------------ AC round 5: totem

    @GameTest(template = ARENA, timeoutTicks = 200, batch = "totem")
    public static void holdsTotemInOffhandWhenNearDeath(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, true);
        c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        c.getInventory().add(new ItemStack(Items.TOTEM_OF_UNDYING));
        boolean[] hit = {false};
        h.runAfterDelay(80, () -> c.setHealth(4f));
        h.onEachTick(() -> {
            if (!hit[0] && c.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
                hit[0] = true;
                c.hurt(h.getLevel().damageSources().generic(), 100f);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(hit[0], "near death the totem goes into the off hand");
            h.assertTrue(c.isAlive() && c.controller().consumables().totemSwaps >= 1, "and saves the clone from a deadly hit");
            finish(h, c);
        });
    }

    // ------------------------------------------------------------------ AC round 5: ender pearls

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "pearl")
    public static void pearlsOutOfAnObsidianPit(GameTestHelper h) {
        walls(h, 7, 7, 3, Blocks.OBSIDIAN); // two blocks deep: too high to jump, no blocks to build with
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, true);
        c.getInventory().add(new ItemStack(Items.ENDER_PEARL, 4));
        BlockPos pit = h.absolutePos(new BlockPos(7, 2, 7));
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().consumables().pearlsThrown >= 1, "with nothing to build with, the clone throws an ender pearl: "
                    + c.controller().consumables().pearlDebug + " escaping=" + c.controller().isEscaping() + " trapped=" + c.controller().escape().isTrapped());
            h.assertTrue(c.getY() >= pit.getY() + 1.5 || !(c.blockPosition().getX() == pit.getX() && c.blockPosition().getZ() == pit.getZ()),
                    "and is out of the pit");
            finish(h, c);
        });
    }

    // ------------------------------------------------------------------ AC round 5: buckets

    @GameTest(template = ARENA, timeoutTicks = 400, batch = "bucket")
    public static void waterBucketBreaksALongFall(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, true);
        c.getInventory().add(new ItemStack(Items.WATER_BUCKET));
        Vec3 high = h.absoluteVec(new Vec3(7.5, 24, 7.5));
        float[] lowest = {20f};
        boolean[] dropped = {false};
        h.onEachTick(() -> lowest[0] = Math.min(lowest[0], c.getHealth()));
        h.runAfterDelay(70, () -> {
            c.teleportTo(h.getLevel(), high.x, high.y, high.z, 0f, 0f);
            dropped[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(dropped[0] && c.getY() < high.y - 15 && (c.onGround() || c.isInWater()), "landed");
            h.assertTrue(lowest[0] >= 19f, "placing water before landing should prevent the fall damage (lowest " + lowest[0] + ")");
            h.assertTrue(c.getInventory().countItem(Items.WATER_BUCKET) == 1, "and the water is scooped up again");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 300, batch = "bucket")
    public static void waterBucketPutsOutFire(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, true);
        c.getInventory().add(new ItemStack(Items.WATER_BUCKET));
        h.runAfterDelay(70, () -> c.setSecondsOnFire(10));
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().consumables().waterPlaced >= 1 && !c.isOnFire(), "a burning clone pours water on itself");
            h.assertTrue(c.getInventory().countItem(Items.WATER_BUCKET) == 1, "and scoops it back up");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 200, batch = "bucket")
    public static void milkCuresPoison(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, true);
        c.getInventory().add(new ItemStack(Items.MILK_BUCKET));
        c.addEffect(new MobEffectInstance(MobEffects.POISON, 600, 0));
        h.succeedWhen(() -> {
            h.assertFalse(c.hasEffect(MobEffects.POISON), "drinking milk removes the poison");
            h.assertTrue(c.getInventory().countItem(Items.BUCKET) == 1, "the empty bucket is kept");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 300, batch = "bucket")
    public static void fillsEmptyBucketWithWater(GameTestHelper h) {
        h.setBlock(new BlockPos(7, 0, 10), Blocks.STONE);
        h.setBlock(new BlockPos(7, 1, 10), Blocks.WATER);
        ClonePlayer c = clone(h, 7.5, 8.5, 0f, true);
        c.getInventory().add(new ItemStack(Items.BUCKET));
        h.succeedWhen(() -> {
            h.assertTrue(c.getInventory().countItem(Items.WATER_BUCKET) == 1, "an empty bucket gets filled at water in reach");
            finish(h, c);
        });
    }

    // ------------------------------------------------------------------ AC round 6

    private static void craftLoop(GameTestHelper h, ClonePlayer c) {
        h.onEachTick(() -> {
            c.controller().perception().update(h.getLevel().getGameTime());
            c.controller().crafting().tick();
            c.controller().motor().tick();
        });
    }

    private static void lavaTrench(GameTestHelper h) {
        for (int x = 1; x <= 13; x++) {
            for (int z = 5; z <= 7; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                h.setBlock(new BlockPos(x, 1, z), Blocks.LAVA);
            }
        }
    }

    @GameTest(template = ARENA, timeoutTicks = 200, batch = "sneak")
    public static void crouchesAtADeadlyEdge(GameTestHelper h) {
        for (int x = 6; x <= 8; x++) {
            for (int z = 6; z <= 8; z++) {
                h.setBlock(new BlockPos(x, 7, z), Blocks.STONE);
            }
        }
        ClonePlayer c = clone(h, 7.5, 7.5, -90f, false);
        Vec3 top = h.absoluteVec(new Vec3(7.5, 8, 7.5));
        c.teleportTo(h.getLevel(), top.x, top.y, top.z, -90f, 0f);
        Vec3 far = h.absoluteVec(new Vec3(13.5, 8, 7.5));
        double[] lowest = {top.y};
        StringBuilder trace = new StringBuilder();
        int[] tick = {0};
        h.onEachTick(() -> {
            tick[0]++;
            c.controller().motor().moveToward(far);
            c.controller().motor().tick();
            if (c.getY() < lowest[0] - 0.01 || tick[0] % 10 == 0) {
                trace.append(' ').append(tick[0]).append(':').append(c.position().subtract(top).toString().replace(" ", ""))
                        .append(c.isShiftKeyDown() ? "S" : "").append(c.onGround() ? "G" : "");
            }
            lowest[0] = Math.min(lowest[0], c.getY());
        });
        h.runAfterDelay(120, () -> {
            h.assertTrue(c.controller().motor().edgeSneaks >= 1, "clone should crouch on its own at the 6-block drop");
            h.assertTrue(lowest[0] > top.y - 0.5, "and not slip off the edge (lowest y " + lowest[0] + ") trace" + trace);
            finish(h, c);
            h.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "hoe")
    public static void craftsAHoeWhenItHasSeeds(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.OAK_LOG, 2));
        c.getInventory().add(new ItemStack(Items.WHEAT_SEEDS, 4));
        craftLoop(h, c);
        h.succeedWhen(() -> {
            h.assertTrue(c.getInventory().countItem(Items.WOODEN_HOE) >= 1, "with seeds and wood the clone makes a hoe: " + c.controller().crafting().debug());
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 40, batch = "breakinfo")
    public static void learnsABlockJustByBreakingIt(GameTestHelper h) {
        h.setBlock(new BlockPos(7, 2, 9), Blocks.GLASS);
        h.setBlock(new BlockPos(8, 2, 9), Blocks.STONE);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.gameMode.destroyBlock(h.absolutePos(new BlockPos(7, 2, 9)));
        c.gameMode.destroyBlock(h.absolutePos(new BlockPos(8, 2, 9)));
        String glass = c.getCloneBrain().facts("minecraft:glass");
        h.assertTrue(glass.contains("drops=none") && glass.contains("nospawn"), "glass: breaks into nothing, no monsters on it (" + glass + ")");
        String stone = c.getCloneBrain().facts("minecraft:stone");
        h.assertTrue(stone.contains("tool=pickaxe") && stone.contains("needstool"), "stone: needs a pickaxe (" + stone + ")");
        h.assertTrue(c.getCloneBrain().knowsBlock("minecraft:glass"), "the block is known now");
        h.assertTrue(said(c.getGameProfile().getName(), "DISCOVER minecraft:glass"), "and the clone says what it learned");
        finish(h, c);
        h.succeed();
    }

    private static void animalLoop(GameTestHelper h, ClonePlayer c) {
        boolean[] active = {false};
        h.onEachTick(() -> {
            var ctl = c.controller();
            ctl.perception().update(h.getLevel().getGameTime());
            if (!active[0] && ctl.animals().hasWork()) {
                ctl.animals().begin();
                active[0] = true;
            }
            if (active[0] && ctl.animals().tick() != com.rlclones.ai.Animals.Status.WORKING) {
                active[0] = false;
            }
            ctl.motor().tick();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "tame")
    public static void tamesAWolfWithBones(GameTestHelper h) {
        var wolf = h.spawn(EntityType.WOLF, new Vec3(7.5, 2, 10.5));
        wolf.setNoAi(true);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.BONE, 32));
        animalLoop(h, c);
        h.succeedWhen(() -> {
            h.assertTrue(wolf.isTame() && wolf.isOwnedBy(c), "clone should tame the wolf with its bones");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 400, batch = "breed")
    public static void breedsItsTamedWolves(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        var w1 = h.spawn(EntityType.WOLF, new Vec3(6.5, 2, 9.5));
        var w2 = h.spawn(EntityType.WOLF, new Vec3(8.5, 2, 9.5));
        for (var w : List.of(w1, w2)) {
            w.setNoAi(true);
            w.tame(c);
        }
        c.getInventory().add(new ItemStack(Items.BEEF, 4));
        animalLoop(h, c);
        h.succeedWhen(() -> {
            boolean baby = !h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.Wolf.class, w1.getBoundingBox().inflate(6), net.minecraft.world.entity.animal.Wolf::isBaby).isEmpty();
            h.assertTrue(baby || (w1.isInLove() && w2.isInLove()), "both tamed wolves should be fed to breed");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "lava")
    public static void usesLavaBucketInAFightAndTakesItBack(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 6.5, 7.5, -90f, false);
        h.runAfterDelay(10, () -> wake(h, c)); // it has seen the husk before it starts moving
        c.getInventory().add(new ItemStack(Items.LAVA_BUCKET));
        c.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 6000, 0));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        dummy(h, 11.5, 7.5);
        h.succeedWhen(() -> {
            var cons = c.controller().consumables();
            h.assertTrue(cons.lavaUsed >= 1, "clone should pour lava under the enemy (" + cons.lavaDebug + " options " + c.controller().optionLog + ")");
            h.assertTrue(cons.lavaRecovered >= 1 && c.getInventory().countItem(Items.LAVA_BUCKET) == 1, "and scoop it back up");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 260, batch = "lava2")
    public static void noLavaWhenAFriendIsClose(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 6.5, 7.5, -90f, true);
        ClonePlayer friend = clone(h, 11.5, 9.5, 180f, false);
        c.getInventory().add(new ItemStack(Items.LAVA_BUCKET));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        friend.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        dummy(h, 11.5, 7.5);
        h.runAfterDelay(240, () -> {
            h.assertTrue(c.controller().consumables().lavaUsed == 0, "no lava next to another clone");
            finish(h, c, friend);
            h.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 300, batch = "fallhay")
    public static void predictsTheLandingAndPutsHayThere(GameTestHelper h) {
        ClonePlayer c = clone(h, 4.5, 7.5, -90f, true);
        c.getInventory().add(new ItemStack(Items.HAY_BLOCK, 4));
        Vec3 high = h.absoluteVec(new Vec3(4.5, 24, 7.5));
        float[] lowest = {20f};
        boolean[] dropped = {false};
        h.onEachTick(() -> lowest[0] = Math.min(lowest[0], c.getHealth()));
        h.runAfterDelay(70, () -> {
            c.teleportTo(h.getLevel(), high.x, high.y, high.z, -90f, 0f);
            c.setDeltaMovement(0.25, 0, 0); // drifting sideways: the landing spot has to be predicted
            dropped[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(dropped[0] && c.getY() < high.y - 15 && c.onGround(), "landed");
            h.assertTrue(c.controller().consumables().cushionsPlaced >= 1, "a hay bale is put where the clone will land");
            h.assertTrue(lowest[0] >= 13f, "which takes most of the fall damage (lowest " + lowest[0] + ")");
            finish(h, c);
        });
    }

    private static void travelLoop(GameTestHelper h, ClonePlayer c, Vec3 goal) {
        h.onEachTick(() -> {
            long now = h.getLevel().getGameTime();
            var ctl = c.controller();
            if (!ctl.travel().tick(now)) {
                ctl.motor().navigate(goal, 1.0, false);
            }
            ctl.motor().tick();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 800, batch = "bridge")
    public static void bridgesAGapWithSpareBlocks(GameTestHelper h) {
        lavaTrench(h);
        ClonePlayer c = clone(h, 7.5, 2.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.DIRT, 16));
        Vec3 goal = h.absoluteVec(new Vec3(7.5, 2, 11.5));
        travelLoop(h, c, goal);
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().travel().blocksBridged >= 3, "clone should bridge the lava with its dirt (" + c.controller().travel().debug + ")");
            h.assertTrue(c.position().distanceTo(goal) < 2.0, "and walk across");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "pearlx")
    public static void pearlsAcrossWhenItCannotBridge(GameTestHelper h) {
        lavaTrench(h);
        ClonePlayer c = clone(h, 7.5, 2.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.ENDER_PEARL, 4));
        Vec3 goal = h.absoluteVec(new Vec3(7.5, 2, 11.5));
        travelLoop(h, c, goal);
        BlockPos far = h.absolutePos(new BlockPos(7, 2, 8));
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().consumables().travelPearls >= 1, "with nothing to bridge with, the clone throws a pearl across");
            h.assertTrue(c.getZ() >= far.getZ() && c.isAlive(), "and gets across alive");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 400, batch = "dive")
    public static void divesDownInWater(GameTestHelper h) {
        for (int x = 2; x <= 12; x++) {
            for (int z = 2; z <= 12; z++) {
                for (int y = -3; y <= 1; y++) {
                    boolean wall = x == 2 || z == 2 || x == 12 || z == 12 || y == -3;
                    h.setBlock(new BlockPos(x, y, z), wall ? (y == 1 ? Blocks.STONE : Blocks.STONE) : Blocks.WATER);
                }
            }
        }
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        Vec3 surface = h.absoluteVec(new Vec3(7.5, 1.6, 7.5));
        c.teleportTo(h.getLevel(), surface.x, surface.y, surface.z, 0f, 0f);
        Vec3 bottom = h.absoluteVec(new Vec3(7.5, -2, 7.5));
        double[] lowest = {surface.y};
        h.onEachTick(() -> {
            c.controller().motor().navigate(bottom, 0.5, false);
            c.controller().motor().tick();
            lowest[0] = Math.min(lowest[0], c.getY());
        });
        h.succeedWhen(() -> {
            h.assertTrue(lowest[0] <= bottom.y + 0.8, "clone should swim down to the bottom (lowest " + (lowest[0] - bottom.y) + " above it)");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 3000, batch = "fish")
    public static void fishesWithARod(GameTestHelper h) {
        for (int x = 4; x <= 10; x++) {
            for (int z = 9; z <= 13; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                h.setBlock(new BlockPos(x, 1, z), Blocks.WATER);
            }
        }
        ClonePlayer c = clone(h, 7.5, 6.5, 0f, false);
        ItemStack rod = new ItemStack(Items.FISHING_ROD);
        rod.enchant(net.minecraft.world.item.enchantment.Enchantments.FISHING_SPEED, 3); // (fish bite slowly down here under the rock)
        c.getInventory().add(rod);
        int before = c.getInventory().items.stream().mapToInt(ItemStack::getCount).sum();
        boolean[] active = {false};
        h.onEachTick(() -> {
            var ctl = c.controller();
            if (!active[0] && ctl.fishing().canFish()) {
                ctl.fishing().begin();
                active[0] = true;
            }
            if (active[0] && ctl.fishing().tick() != com.rlclones.ai.Fishing.Status.WORKING) {
                active[0] = false;
            }
            ctl.motor().tick();
        });
        h.succeedWhen(() -> {
            int now = c.getInventory().items.stream().mapToInt(ItemStack::getCount).sum();
            h.assertTrue(c.controller().fishing().catches >= 1 && now > before, "clone should cast, hear the bite and reel something in (casts "
                    + c.controller().fishing().casts + " heard " + c.controller().fishing().heard + " " + c.controller().fishing().debug + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 800, batch = "bow")
    public static void craftsABowAndArrows(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.STONE_SWORD));
        c.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        c.getInventory().add(new ItemStack(Items.STONE_AXE));
        c.getInventory().add(new ItemStack(Items.CRAFTING_TABLE));
        c.getInventory().add(new ItemStack(Items.STICK, 8));
        c.getInventory().add(new ItemStack(Items.STRING, 3));
        c.getInventory().add(new ItemStack(Items.FLINT, 2));
        c.getInventory().add(new ItemStack(Items.FEATHER, 2));
        craftLoop(h, c);
        h.succeedWhen(() -> {
            h.assertTrue(c.getInventory().countItem(Items.BOW) >= 1 && c.getInventory().countItem(Items.ARROW) >= 4,
                    "clone should make a bow and arrows: " + c.controller().crafting().debug());
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "tntcraft")
    public static void craftsTntAndAButtonForIt(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.GUNPOWDER, 5));
        c.getInventory().add(new ItemStack(Items.SAND, 4));
        c.getInventory().add(new ItemStack(Items.CRAFTING_TABLE));
        c.getInventory().add(new ItemStack(Items.OAK_PLANKS, 2));
        c.getInventory().add(new ItemStack(Items.STONE_SWORD));
        craftLoop(h, c);
        h.succeedWhen(() -> {
            int buttons = 0;
            for (ItemStack s : c.getInventory().items) {
                if (s.is(net.minecraft.tags.ItemTags.BUTTONS)) {
                    buttons += s.getCount();
                }
            }
            h.assertTrue(c.getInventory().countItem(Items.TNT) >= 1 && buttons >= 1, "clone should craft TNT and a button to set it off: "
                    + c.controller().crafting().debug());
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "tnt")
    public static void blowsUpACrowdWithTnt(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 3.5, 3.5, -45f, false);
        h.runAfterDelay(10, () -> wake(h, c)); // it has seen them before it starts moving
        c.getInventory().add(new ItemStack(Items.TNT));
        c.getInventory().add(new ItemStack(Items.OAK_BUTTON));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        dummy(h, 10.5, 10.5);
        dummy(h, 11.5, 10.5);
        dummy(h, 10.5, 11.5);
        dummy(h, 11.5, 11.5);
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().explosives().tntUsed >= 1, "outnumbered, with nobody of ours around, the clone sets off TNT ("
                    + c.controller().explosives().debug + " options " + c.controller().optionLog + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 40, batch = "tnt2")
    public static void tntNeverNearFriends(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 2.5, 2.5, 0f, false);
        ClonePlayer friend = clone(h, 12.5, 12.5, 0f, false);
        h.assertFalse(c.controller().explosives().safeAt(friend.position().add(2, 0, 0)), "no TNT within reach of another clone");
        h.assertTrue(c.controller().explosives().safeAt(h.absoluteVec(new Vec3(2.5, 2, 2.5)).add(0, 0, -1)), "far away it is fine");
        finish(h, c, friend);
        h.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 1600, batch = "salvage")
    public static void takesPlanksFromBuildingsButNotFromBases(GameTestHelper h) {
        clearBases(h);
        for (int z = 1; z <= 3; z++) {
            h.setBlock(new BlockPos(13, 2, z), Blocks.OAK_PLANKS);
        }
        h.setBlock(new BlockPos(1, 2, 13), Blocks.OAK_PLANKS);
        BlockPos baseWood = h.absolutePos(new BlockPos(1, 2, 13));
        var bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
        bases.add(h.getLevel().dimension(), baseWood, "someone");
        ClonePlayer c = clone(h, 7.5, 7.5, -90f, true);
        for (int z = 1; z <= 3; z++) {
            c.controller().perception().noteBlock(h.absolutePos(new BlockPos(13, 2, z))); // seen on the way in
        }
        c.controller().perception().noteBlock(baseWood);
        h.succeedWhen(() -> {
            h.assertTrue(c.getInventory().countItem(Items.OAK_PLANKS) >= 1, "with no tree around, the clone takes planks from what was built: options "
                    + c.controller().optionLog + " harvest=" + c.controller().harvestDebug + " wood=" + c.controller().perception().blocks().entrySet().stream()
                    .filter(e -> e.getValue() == com.rlclones.ai.Perception.BlockKind.WOOD).map(e -> e.getKey().toShortString()).toList() + " trace" + c.controller().harvestTrace);
            h.assertTrue(h.getLevel().getBlockState(baseWood).is(Blocks.OAK_PLANKS), "but never from a base");
            finish(h, c);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 2400, batch = "grass")
    public static void cutsGrassForSeedsThenFarms(GameTestHelper h) {
        for (int x = 1; x <= 13; x++) {
            for (int z = 10; z <= 13; z++) {
                h.setBlock(new BlockPos(x, 1, z), Blocks.GRASS_BLOCK);
                h.setBlock(new BlockPos(x, 2, z), Blocks.GRASS);
            }
        }
        h.setBlock(new BlockPos(7, 0, 9), Blocks.STONE);
        h.setBlock(new BlockPos(7, 1, 9), Blocks.WATER);
        h.setBlock(new BlockPos(1, 2, 9), Blocks.GLOWSTONE); // crops need light (>= 8) to be planted
        h.setBlock(new BlockPos(13, 2, 9), Blocks.GLOWSTONE);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.WOODEN_HOE));
        boolean[] active = {false};
        h.onEachTick(() -> {
            var f = c.controller().farming();
            if (!active[0] && f.hasWork()) {
                f.reset();
                active[0] = true;
            }
            if (active[0] && f.tick() != com.rlclones.ai.Farming.Status.WORKING) {
                active[0] = false;
            }
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            int planted = 0;
            for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(1, 1, 8)), h.absolutePos(new BlockPos(13, 1, 13)))) {
                if (h.getLevel().getBlockState(p).is(Blocks.FARMLAND) && h.getLevel().getBlockState(p.above()).is(Blocks.WHEAT)) {
                    planted++;
                }
            }
            h.assertTrue(c.controller().farming().grassCut >= 1, "clone should cut grass to get seeds");
            h.assertTrue(planted >= 1, "and then till by the water and plant them: seeds=" + c.getInventory().countItem(Items.WHEAT_SEEDS)
                    + " cut=" + c.controller().farming().grassCut + " " + c.controller().farming().debug() + " " + c.controller().farming().diag());
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 3000, batch = "pen")
    public static void buildsAChickenPenAndThrowsEggsIn(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.CRAFTING_TABLE));
        c.getInventory().add(new ItemStack(Items.STONE_SWORD));
        c.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        c.getInventory().add(new ItemStack(Items.STONE_AXE));
        c.getInventory().add(new ItemStack(Items.OAK_PLANKS, 64));
        c.getInventory().add(new ItemStack(Items.STICK, 16));
        c.getInventory().add(new ItemStack(Items.EGG, 4));
        boolean[] active = {false};
        h.onEachTick(() -> {
            var ctl = c.controller();
            ctl.perception().update(h.getLevel().getGameTime());
            if (!active[0] && ctl.animals().hasWork()) {
                ctl.animals().begin();
                active[0] = true;
            }
            if (active[0]) {
                if (ctl.animals().tick() != com.rlclones.ai.Animals.Status.WORKING) {
                    active[0] = false;
                }
            } else {
                ctl.crafting().tick();
            }
            ctl.motor().tick();
        });
        h.succeedWhen(() -> {
            var a = c.controller().animals();
            int fences = 0;
            boolean gate = false;
            for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(1, 2, 1)), h.absolutePos(new BlockPos(13, 2, 13)))) {
                fences += h.getLevel().getBlockState(p).is(net.minecraft.tags.BlockTags.WOODEN_FENCES) ? 1 : 0;
                gate |= h.getLevel().getBlockState(p).is(net.minecraft.tags.BlockTags.FENCE_GATES);
            }
            h.assertTrue(a.pensBuilt >= 1 && fences >= 14 && gate, "clone should craft fences and a gate and build a pen (fences "
                    + fences + ", gate " + gate + ") " + c.controller().crafting().debug());
            h.assertTrue(a.eggsThrown >= 4, "and throw its eggs into it");
            finish(h, c);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 40, batch = "pen2")
    public static void alwaysKeepsTwoChickens(GameTestHelper h) {
        clearBases(h);
        var bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
        bases.addPen(h.getLevel().dimension(), h.absolutePos(new BlockPos(4, 2, 4)));
        var c1 = h.spawn(EntityType.CHICKEN, new Vec3(6.5, 2, 6.5));
        var c2 = h.spawn(EntityType.CHICKEN, new Vec3(7.5, 2, 7.5));
        c1.setNoAi(true);
        c2.setNoAi(true);
        // (looked at a few ticks later, once the new arena's entities are all in the world)
        h.runAfterDelay(5, () -> {
            h.assertTrue(com.rlclones.ai.Animals.protectedAnimal(c1), "with only two chickens in the pen, they are not eaten");
            var c3 = h.spawn(EntityType.CHICKEN, new Vec3(6.5, 2, 7.5));
            c3.setNoAi(true);
        });
        h.runAfterDelay(10, () -> {
            h.assertFalse(com.rlclones.ai.Animals.protectedAnimal(c1), "a third one may be eaten when hungry");
            h.assertFalse(com.rlclones.ai.Senses.isFoodAnimal(c1) == false, "and counts as food again");
            clearBases(h);
            h.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 400, batch = "pen3")
    public static void breedsPennedChickensWithSeeds(GameTestHelper h) {
        clearBases(h);
        var bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
        bases.addPen(h.getLevel().dimension(), h.absolutePos(new BlockPos(5, 2, 5)));
        var c1 = h.spawn(EntityType.CHICKEN, new Vec3(6.5, 2, 8.5));
        var c2 = h.spawn(EntityType.CHICKEN, new Vec3(8.5, 2, 8.5));
        c1.setNoAi(true);
        c2.setNoAi(true);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.WHEAT_SEEDS, 4));
        animalLoop(h, c);
        h.succeedWhen(() -> {
            boolean baby = !h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.Chicken.class, c1.getBoundingBox().inflate(6),
                    net.minecraft.world.entity.animal.Chicken::isBaby).isEmpty();
            h.assertTrue(baby || (c1.isInLove() && c2.isInLove()), "penned chickens get seeds to breed");
            finish(h, c);
            clearBases(h);
        });
    }

    /** R-59: a wall of 12 blocks in 3 segments, two clones with stone: both work, no segment twice, the wall stands whole. */
    @GameTest(template = ARENA, timeoutTicks = 2400, batch = "r16plan")
    public static void buildsAWallTogether(GameTestHelper h) {
        clearBases(h);
        var bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
        java.util.List<BlockPos> line = new java.util.ArrayList<>();
        for (int x = 2; x <= 13; x++) {
            line.add(h.absolutePos(new BlockPos(x, 2, 11)));
        }
        var plan = bases.addPlan(h.getLevel().dimension(), "WALL", line, 4);
        ClonePlayer a = clone(h, 3.5, 8.5, 0f, false);
        ClonePlayer b = clone(h, 11.5, 8.5, 0f, false);
        a.getInventory().add(new ItemStack(Items.COBBLESTONE, 24));
        b.getInventory().add(new ItemStack(Items.COBBLESTONE, 24));
        a.controller().persona().joinPlan(plan);
        b.controller().persona().joinPlan(plan);
        h.onEachTick(() -> {
            for (ClonePlayer c : new ClonePlayer[]{a, b}) {
                c.controller().persona().tick(h.getLevel().getGameTime(), opt("REST"), false);
                c.controller().motor().tick();
            }
        });
        h.succeedWhen(() -> {
            int stones = 0;
            for (BlockPos p : line) {
                stones += h.getLevel().getBlockState(p).isAir() ? 0 : 1;
            }
            h.assertTrue(stones == 12 && plan.complete(), "the whole wall stands (" + stones + "/12, a " + a.controller().persona().planWork().placed + " b "
                    + b.controller().persona().planWork().placed + " " + a.controller().persona().planWork().debug + b.controller().persona().planWork().debug + ")");
            h.assertTrue(new java.util.HashSet<>(plan.claims.values()).size() == 2, "and both of them worked on it");
            finish(h, a, b);
            clearBases(h);
        });
    }

    /** R-54: a finished wall with a block knocked out: a clone with stone nearby mends it. */
    @GameTest(template = ARENA, timeoutTicks = 1200, batch = "r16plan")
    public static void mendsABrokenWall(GameTestHelper h) {
        clearBases(h);
        var bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
        java.util.List<BlockPos> line = new java.util.ArrayList<>();
        for (int x = 2; x <= 13; x++) {
            line.add(h.absolutePos(new BlockPos(x, 2, 11)));
            h.setBlock(new BlockPos(x, 2, 11), Blocks.COBBLESTONE);
        }
        var plan = bases.addPlan(h.getLevel().dimension(), "WALL", line, 4);
        for (int sgm = 0; sgm < plan.segments(); sgm++) {
            plan.finish(sgm);
        }
        h.setBlock(new BlockPos(8, 2, 11), Blocks.AIR);
        ClonePlayer c = clone(h, 8.5, 8.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.COBBLESTONE, 8));
        h.onEachTick(() -> {
            c.controller().persona().tick(h.getLevel().getGameTime(), opt("REST"), false);
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(!h.getBlockState(new BlockPos(8, 2, 11)).isAir() && c.controller().persona().plansRepaired >= 1, "the gap in the wall is filled again");
            finish(h, c);
            clearBases(h);
        });
    }

    private static java.util.List<net.minecraft.world.entity.animal.Cow> cowsInPen(GameTestHelper h, int n) {
        var bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
        bases.addPen(h.getLevel().dimension(), h.absolutePos(new BlockPos(5, 2, 5)), "cow");
        java.util.List<net.minecraft.world.entity.animal.Cow> cows = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            var cow = h.spawn(EntityType.COW, new Vec3(5.5 + (i % 3) * 1.5, 2, 5.5 + (i / 3) * 1.5));
            cow.setNoAi(true);
            cows.add(cow);
        }
        return cows;
    }

    /** R-60: 2 cows in the pen and wheat in the bag: below the target of 4, so they are bred. */
    @GameTest(template = ARENA, timeoutTicks = 600, batch = "r16herd")
    public static void breedsCowsBelowTheTarget(GameTestHelper h) {
        clearBases(h);
        var cows = cowsInPen(h, 2);
        ClonePlayer c = clone(h, 7.5, 3.5, 0f, false); // (facing the pen: the cows must be in its view)
        c.getInventory().add(new ItemStack(Items.WHEAT, 8));
        animalLoop(h, c);
        h.succeedWhen(() -> {
            boolean baby = !h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.Cow.class, cows.get(0).getBoundingBox().inflate(8),
                    net.minecraft.world.entity.animal.Cow::isBaby).isEmpty();
            h.assertTrue(baby || cows.get(0).isInLove() && cows.get(1).isInLove(), "two cows below the target are fed and bred");
            finish(h, c);
            clearBases(h);
        });
    }

    /** R-60: 4 cows already (the target): wheat in the bag, but no breeding. */
    @GameTest(template = ARENA, timeoutTicks = 400, batch = "r16herd")
    public static void doesNotBreedPastTheTarget(GameTestHelper h) {
        clearBases(h);
        var cows = cowsInPen(h, 4);
        ClonePlayer c = clone(h, 7.5, 3.5, 0f, false); // (facing the pen: the cows must be in its view)
        c.getInventory().add(new ItemStack(Items.WHEAT, 8));
        animalLoop(h, c);
        h.runAfterDelay(300, () -> {
            boolean any = cows.stream().anyMatch(net.minecraft.world.entity.animal.Animal::isInLove) || c.getInventory().countItem(Items.WHEAT) < 8;
            h.assertFalse(any, "at the target the herd is left alone");
            finish(h, c);
            clearBases(h);
            h.succeed();
        });
    }

    /** R-60: 6 cows (two over the target of 4), one of them named: the oldest unnamed one is culled, the pet stays. */
    @GameTest(template = ARENA, timeoutTicks = 1500, batch = "r16herd")
    public static void cullsTheSurplusButNotAPet(GameTestHelper h) {
        clearBases(h);
        var cows = cowsInPen(h, 6);
        cows.get(0).setCustomName(Component.literal("Daisy"));
        ClonePlayer c = clone(h, 7.5, 3.5, 0f, false); // (facing the pen: the cows must be in its view)
        animalLoop(h, c);
        h.succeedWhen(() -> {
            long alive = cows.stream().filter(net.minecraft.world.entity.Entity::isAlive).count();
            h.assertTrue(c.controller().animals().culled >= 1 && alive == 5, "one cow culled (alive " + alive + ", culled " + c.controller().animals().culled + ")");
            h.assertTrue(cows.get(0).isAlive(), "the named one stays");
            finish(h, c);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1600, batch = "huntexp")
    public static void callsAHuntingPartyForABoss(GameTestHelper h) {
        var ravager = h.spawn(EntityType.RAVAGER, new Vec3(12.5, 2, 12.5));
        ravager.setNoAi(true);
        ClonePlayer leader = clone(h, 2.5, 2.5, -45f, false);
        ClonePlayer member = clone(h, 2.5, 5.5, -45f, false);
        String ln = leader.getGameProfile().getName();
        Object[][] hunt = {null};
        boolean[] killed = {false};
        h.onEachTick(() -> {
            long now = h.getLevel().getGameTime();
            var lc = leader.controller();
            lc.perception().update(now);
            if (hunt[0] == null) {
                hunt[0] = lc.huntTarget(now);
                if (hunt[0] != null) {
                    lc.expedition().leadHunt(((net.minecraft.world.entity.Entity) hunt[0][0]).blockPosition(), 1, "minecraft:ravager");
                }
            } else {
                lc.expedition().leadTick();
            }
            var mx = member.controller().expedition();
            if (mx.joinedOffer() == null && mx.completed == 0 && mx.canJoin(now)) {
                mx.join();
            }
            if (mx.joinedOffer() != null) {
                mx.followTick();
            }
            if (!killed[0] && said(ln, "DEPART ")) {
                killed[0] = true;
                ravager.kill(); // the party wins the fight
            }
            lc.motor().tick();
            member.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(hunt[0] != null && hunt[0][0] == ravager && (Integer) hunt[0][1] >= 2,
                    "a 100-health ravager far away is a hunting target needing several clones");
            h.assertTrue(com.rlclones.ai.Chat.recent().stream().anyMatch(l -> l.startsWith(ln + ": RALLY ") && l.contains("kind=HUNT foe=minecraft:ravager")),
                    "the leader calls a hunting party in chat");
            h.assertTrue(said(member.getGameProfile().getName(), "JOIN " + ln), "a clone joins");
            h.assertTrue(com.rlclones.ai.Chat.recent().stream().anyMatch(l -> l.startsWith(ln + ": EXPEDITION_END") && l.contains("kind=HUNT")),
                    "and the hunt is declared over once the enemy is gone");
            finish(h, leader, member);
        });
    }

    // ================================================================== AC round 7

    @GameTest(template = ARENA, timeoutTicks = 400, batch = "torch")
    public static void craftsTorchesFromCoal(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.COAL, 2));
        c.getInventory().add(new ItemStack(Items.STICK, 2));
        c.getInventory().add(new ItemStack(Items.CRAFTING_TABLE));
        c.getInventory().add(new ItemStack(Items.STONE_SWORD));
        craftLoop(h, c);
        h.succeedWhen(() -> {
            h.assertTrue(c.getInventory().countItem(Items.TORCH) >= 4, "coal and sticks make torches: " + c.controller().crafting().debug());
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 300, batch = "torch2")
    public static void lightsUpWhereMonstersCouldSpawn(GameTestHelper h) {
        clearBases(h);
        com.rlclones.clone.Bases.get(h.getLevel().getServer()).add(h.getLevel().dimension(), h.absolutePos(new BlockPos(3, 2, 3)), "someone");
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.TORCH, 8));
        BlockPos feet = c.blockPosition();
        h.assertTrue(c.controller().lighting().darkSpot(), "pitch dark here at first");
        h.onEachTick(() -> {
            if (h.getLevel().getGameTime() % 20 == 0) {
                c.controller().lighting().tick();
            }
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().lighting().torchesPlaced >= 1 && h.getLevel().getBlockState(feet).is(Blocks.TORCH),
                    "the clone puts a torch down where monsters could spawn");
            h.assertTrue(!c.controller().lighting().darkSpot(), "and it is lit now");
            finish(h, c);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1200, batch = "tunnel")
    public static void digsThroughAWallInTheWay(GameTestHelper h) {
        for (int x = 1; x <= 13; x++) {
            for (int y = 2; y <= 5; y++) {
                h.setBlock(new BlockPos(x, y, 4), Blocks.STONE);
            }
        }
        ClonePlayer c = clone(h, 7.5, 1.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        Vec3 goal = h.absoluteVec(new Vec3(7.5, 2, 12.5));
        travelLoop(h, c, goal);
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().travel().blocksTunneled >= 1, "a wall in the way: the clone digs through (" + c.controller().travel().debug + ")");
            h.assertTrue(c.position().distanceTo(goal) < 2.0, "and goes on");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 800, batch = "arc")
    public static void lobsArrowsOverAFriend(GameTestHelper h) {
        ClonePlayer c = clone(h, 2.5, 7.5, -90f, false);
        StringBuilder atWake = new StringBuilder();
        c.getInventory().add(new ItemStack(Items.BOW));
        c.getInventory().add(new ItemStack(Items.ARROW, 64));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.controller().forcedAction = com.rlclones.ai.combat.CombatAction.SHOOT;
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FIGHT;
        ClonePlayer friend = clone(h, 6.5, 7.5, 90f, false);
        Husk husk = dummy(h, 11.5, 7.5);
        h.runAfterDelay(10, () -> {
            wake(h, c); // once it has had a look at who is where
            atWake.append("husk ").append(c.controller().perception().canSee(husk)).append(" friend ").append(c.controller().perception().canSee(friend))
                    .append(" remembered ").append(c.controller().perception().remembered().size()).append(" | ").append(c.controller().perception().explain(husk));
        });
        float[] lowest = {20f};
        boolean[] hit = {false};
        StringBuilder hurt = new StringBuilder();
        h.onEachTick(() -> {
            if (friend.getHealth() < lowest[0] && hurt.length() < 400) {
                var src = friend.getLastDamageSource();
                hurt.append(" [").append(src == null ? "?" : src.getMsgId() + " by " + src.getEntity() + " direct " + src.getDirectEntity())
                        .append(" | ").append(c.controller().shootDebug).append("]");
            }
            lowest[0] = Math.min(lowest[0], friend.getHealth());
            hit[0] |= husk.getLastHurtByMob() == c;
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().arcShots >= 2, "with a friend in the line of fire the clone shoots high arcs (" + c.controller().arcShots + " "
                    + c.controller().shootDebug + " options " + c.controller().optionLog + " at wake: " + atWake + ")");
            h.assertTrue(lowest[0] >= 20f, "the friend is never hit (lowest " + lowest[0] + hurt + " held " + c.controller().heldShots + ")");
            h.assertTrue(hit[0], "and the arrows come down on the enemy");
            finish(h, c, friend);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "arc2")
    public static void takesTheBowWhenAFriendBlocksTheCrossbow(GameTestHelper h) {
        ClonePlayer c = clone(h, 2.5, 7.5, -90f, false);
        h.runAfterDelay(10, () -> wake(h, c)); // once it has had a look at who is where
        c.getInventory().add(new ItemStack(Items.CROSSBOW));
        c.getInventory().add(new ItemStack(Items.BOW));
        c.getInventory().add(new ItemStack(Items.ARROW, 64));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.controller().forcedAction = com.rlclones.ai.combat.CombatAction.SHOOT;
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FIGHT;
        ClonePlayer friend = clone(h, 6.5, 7.5, 90f, false);
        dummy(h, 11.5, 7.5);
        float[] lowest = {20f};
        h.onEachTick(() -> lowest[0] = Math.min(lowest[0], friend.getHealth()));
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().bowSwitches >= 1, "the crossbow is swapped for the bow");
            h.assertTrue(c.controller().arcShots >= 1, "which lobs over the friend");
            h.assertTrue(lowest[0] >= 20f, "the friend stays unhurt (lowest " + lowest[0] + ")");
            finish(h, c, friend);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "corner")
    public static void fightsWhenCorneredInsteadOfHoppingAtWalls(GameTestHelper h) {
        for (int z = 0; z <= 5; z++) {
            for (int y = 2; y <= 4; y++) {
                // obsidian (nothing to dig through or discover): a 1-wide dead end, the husk standing in its mouth
                h.setBlock(new BlockPos(6, y, z), Blocks.OBSIDIAN);
                h.setBlock(new BlockPos(8, y, z), Blocks.OBSIDIAN);
                h.setBlock(new BlockPos(7, y, 0), Blocks.OBSIDIAN);
            }
        }
        ClonePlayer c = clone(h, 7.5, 1.5, 0f, false);
        h.runAfterDelay(10, () -> wake(h, c)); // it has seen the husk before it starts moving
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.getInventory().add(new ItemStack(Items.IRON_SWORD));
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FLEE;
        dummy(h, 7.5, 4.5); // standing in the only way out
        boolean[] fought = {false};
        h.onEachTick(() -> {
            if (c.controller().corneredFights > 0) {
                c.controller().forcedOption = null;
                fought[0] |= c.controller().option() == com.rlclones.ai.strategy.Option.FIGHT;
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().corneredFights >= 1, "a dead end while fleeing: no more running (options " + c.controller().optionLog + ")");
            h.assertTrue(fought[0], "it turns round and fights (options " + c.controller().optionLog + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 400, batch = "weapons")
    public static void jumpAttacksWithAnAxe(GameTestHelper h) {
        ClonePlayer c = clone(h, 6.0, 7.5, -90f, true);
        c.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
        c.getInventory().add(new ItemStack(Items.IRON_AXE));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.controller().forcedAction = com.rlclones.ai.combat.CombatAction.CRIT_ATTACK;
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FIGHT;
        dummy(h, 8.0, 7.5);
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().axeCrits >= 1, "jump attacks are done with the axe (" + c.controller().critDebug + " options " + c.controller().optionLog + ")");
            finish(h, c);
        });
    }

    /** Terrain in a fight: open ground reads as open, an alcove as covered, a lava edge as hazard; POSITION walks into the alcove. */
    @GameTest(template = ARENA, timeoutTicks = 400, batch = "weapons2")
    public static void takesTheAlcoveInAFight(GameTestHelper h) {
        // a 1-wide alcove at x 3..4, z 3 (stone on both sides and behind)
        for (int x = 2; x <= 5; x++) {
            for (int y = 2; y <= 3; y++) {
                h.setBlock(new BlockPos(x, y, 2), Blocks.STONE);
                h.setBlock(new BlockPos(x, y, 4), Blocks.STONE);
            }
        }
        for (int y = 2; y <= 3; y++) {
            h.setBlock(new BlockPos(2, y, 3), Blocks.STONE);
        }
        ClonePlayer c = clone(h, 6.5, 7.5, -90f, true);
        c.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        h.assertTrue(com.rlclones.ai.combat.Terrain.state(h.getLevel(), h.absolutePos(new BlockPos(3, 2, 3))) == com.rlclones.ai.combat.Terrain.COVERED, "alcove reads as covered");
        h.assertTrue(com.rlclones.ai.combat.Terrain.state(h.getLevel(), h.absolutePos(new BlockPos(9, 2, 9))) == com.rlclones.ai.combat.Terrain.OPEN, "open ground reads as open");
        c.controller().forcedAction = com.rlclones.ai.combat.CombatAction.POSITION;
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FIGHT;
        dummy(h, 11.5, 7.5);
        h.succeedWhen(() -> {
            BlockPos at = c.blockPosition().subtract(h.absolutePos(BlockPos.ZERO));
            h.assertTrue(c.controller().positions >= 1 && at.getZ() == 3 && at.getX() <= 5, "walked into the alcove, at " + at.toShortString() + " positions " + c.controller().positions);
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 400, batch = "weapons2")
    public static void sweepsACrowdWithTheSword(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, -90f, true);
        c.getInventory().add(new ItemStack(Items.IRON_AXE));
        c.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.controller().forcedAction = com.rlclones.ai.combat.CombatAction.ATTACK;
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FIGHT;
        dummy(h, 9.3, 7.5);
        dummy(h, 9.3, 8.4);
        dummy(h, 9.3, 6.6);
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().swordSweeps >= 1, "against several side by side the sword sweeps");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 100, batch = "teams")
    public static void summonsATeamByCommand(GameTestHelper h) {
        var server = h.getLevel().getServer();
        int before = manager(h).clones().size();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPosition(h.absoluteVec(new Vec3(3.5, 2, 3.5)))
                .withLevel(h.getLevel()).withPermission(4), "rlclone summon team red");
        ClonePlayer red = null;
        for (ClonePlayer x : manager(h).clones()) {
            if ("red".equals(x.cloneTeam())) {
                red = x;
            }
        }
        h.assertTrue(manager(h).clones().size() == before + 1 && red != null, "the command summons a clone for team red");
        ClonePlayer ours = clone(h, 10.5, 10.5, 0f, false);
        ClonePlayer ours2 = clone(h, 11.5, 10.5, 0f, false);
        h.assertTrue(com.rlclones.ai.Senses.rivals(red, ours) && com.rlclones.ai.Senses.isHostileTo(red, ours), "other teams are enemies");
        h.assertFalse(com.rlclones.ai.Senses.rivals(ours, ours2), "the same team is not");
        h.assertTrue(com.rlclones.clone.ClonePlayer.DEFAULT_TEAM.equals(ours.cloneTeam()), "no team given: the default one");
        finish(h, red, ours, ours2);
        h.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "teams2")
    public static void clonesOfOtherTeamsFight(GameTestHelper h) {
        h.getLevel().getServer().setPvpAllowed(true); // as on a normal server (pvp=true)
        ClonePlayer red = clone(h, 4.5, 7.5, -90f, true);
        red.setCloneTeam("red");
        ClonePlayer blue = clone(h, 10.5, 7.5, 90f, true);
        blue.setCloneTeam("blue");
        red.getInventory().add(new ItemStack(Items.IRON_SWORD));
        blue.getInventory().add(new ItemStack(Items.IRON_SWORD));
        red.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        blue.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        h.succeedWhen(() -> {
            h.assertTrue(red.getLastHurtByMob() == blue || blue.getLastHurtByMob() == red, "red and blue clones attack each other: red "
                    + red.controller().optionLog + " blue " + blue.controller().optionLog);
            finish(h, red, blue);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 60, batch = "br")
    public static void gKeyStartsABattleRoyale(GameTestHelper h) {
        ClonePlayer a = clone(h, 4.5, 7.5, 0f, false);
        ClonePlayer b = clone(h, 10.5, 7.5, 0f, false);
        h.assertFalse(com.rlclones.ai.Senses.rivals(a, b), "same team: friends");
        manager(h).handleAction(a, ClientAction.BATTLE_ROYALE);
        boolean br = com.rlclones.ai.Senses.rivals(a, b) && com.rlclones.ai.Senses.isHostileTo(b, a);
        manager(h).handleAction(a, ClientAction.BATTLE_ROYALE);
        h.assertTrue(br, "G: battle royale - everyone is everyone's enemy");
        h.assertFalse(com.rlclones.ai.Senses.rivals(a, b), "G again: back to teams");
        finish(h, a, b);
        h.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 400, batch = "creative")
    public static void creativeCloneHelpsTheOthers(GameTestHelper h) {
        ClonePlayer helper = clone(h, 4.5, 7.5, -90f, true);
        helper.setGameMode(GameType.CREATIVE);
        ClonePlayer friend = clone(h, 7.5, 7.5, 90f, false);
        friend.getFoodData().setFoodLevel(4);
        h.succeedWhen(() -> {
            boolean food = false;
            for (ItemStack s : friend.getInventory().items) {
                food |= s.isEdible();
            }
            h.assertTrue(helper.controller().creative().takenFromMenu >= 1, "the creative clone takes things from the creative menu ("
                    + helper.controller().creative().debug + ")");
            h.assertTrue(food && helper.controller().creative().gifts >= 1, "and brings the hungry survival clone food");
            finish(h, helper, friend);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 400, batch = "cake")
    public static void eatsCake(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, true);
        c.getInventory().add(new ItemStack(Items.CAKE));
        c.getFoodData().setFoodLevel(6);
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.EAT;
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().cakeBites >= 1 && c.getFoodData().getFoodLevel() > 6, "a hungry clone puts its cake down and eats it");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 3600, batch = "horse")
    public static void tamesSaddlesAndRidesAHorse(GameTestHelper h) {
        ClonePlayer c = clone(h, 4.5, 7.5, -90f, false);
        c.getInventory().add(new ItemStack(Items.SADDLE));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        var horse = h.spawn(EntityType.HORSE, new Vec3(9.5, 2, 7.5));
        animalLoop(h, c);
        h.succeedWhen(() -> {
            h.assertTrue(horse.isTamed() && horse.isSaddled() && c.getVehicle() == horse, "with a saddle the clone tames the horse, saddles it and rides (tries "
                    + c.controller().animals().mountAttempts + ", tamed " + horse.isTamed() + ", saddled " + horse.isSaddled() + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 2400, batch = "parkour")
    public static void learnsToSprintJumpAGap(GameTestHelper h) {
        for (int x = 1; x <= 13; x++) {
            for (int z = 6; z <= 8; z++) {
                for (int y = -3; y <= 1; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
                h.setBlock(new BlockPos(x, -4, z), Blocks.STONE);
            }
        }
        ClonePlayer c = clone(h, 7.5, 3.5, 0f, false);
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        Vec3 start = h.absoluteVec(new Vec3(7.5, 2, 3.5));
        Vec3 goal = h.absoluteVec(new Vec3(7.5, 2, 12.5));
        int[] falls = {0};
        h.onEachTick(() -> {
            if (c.getY() < start.y - 1.5 && c.onGround()) {
                falls[0]++; // fell in: back to the start (a player would climb out)
                c.teleportTo(h.getLevel(), start.x, start.y, start.z, 0f, 0f);
                c.fallDistance = 0;
                c.setDeltaMovement(Vec3.ZERO);
            }
        });
        travelLoop(h, c, goal);
        h.succeedWhen(() -> {
            var b = c.getCloneBrain();
            h.assertTrue(c.controller().travel().parkourSuccesses >= 1 && c.getZ() > h.absoluteVec(new Vec3(0, 0, 9.5)).z,
                    "the clone gets over the 3-wide gap by jumping (" + c.controller().travel().lastParkour + ", falls " + falls[0] + ")");
            h.assertTrue(b.parkourValue(3, 1) > b.parkourValue(3, 0), "and has learned that a sprinting run-up works better than walking up to it ("
                    + b.parkourValue(3, 0) + " / " + b.parkourValue(3, 1) + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 400, batch = "boattrap")
    public static void trapsAMonsterInABoat(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.0, 7.5, -90f, true);
        c.getInventory().add(new ItemStack(Items.OAK_BOAT));
        c.getInventory().add(new ItemStack(Items.IRON_SWORD));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FIGHT;
        Husk husk = dummy(h, 10.5, 7.5);
        h.succeedWhen(() -> {
            h.assertTrue(husk.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat && c.controller().boatTrap().trapsSprung >= 1,
                    "a boat put at its feet: the husk sits in it (" + c.controller().boatTrap().debug + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "boattrap2")
    public static void learnsThatSpidersDoNotSitInBoats(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.0, 7.5, -90f, true);
        c.getInventory().add(new ItemStack(Items.OAK_BOAT));
        c.getInventory().add(new ItemStack(Items.IRON_SWORD));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FIGHT;
        var spider = h.spawn(EntityType.SPIDER, new Vec3(10.5, 2, 7.5));
        spider.setNoAi(true);
        spider.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        h.succeedWhen(() -> {
            h.assertTrue(c.getCloneBrain().hasFlag("noboat:minecraft:spider"), "the spider did not get in: learned (" + c.controller().boatTrap().debug + ")");
            h.assertTrue(com.rlclones.ai.Boating.boatSlot(c) >= 0, "and the boat is broken and taken back (" + c.controller().boatTrap().debug
                    + " recovered " + c.controller().boatTrap().boatsRecovered + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1200, batch = "perch")
    public static void perchesOnTwoBlocksWhenChased(GameTestHelper h) {
        ClonePlayer c = clone(h, 4.5, 7.5, -90f, false);
        h.runAfterDelay(3, () -> wake(h, c)); // it sees the husk coming before it decides anything
        c.getInventory().add(new ItemStack(Items.COBBLESTONE, 16));
        c.getInventory().add(new ItemStack(Items.WOODEN_PICKAXE));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.setHealth(8f);
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FLEE;
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(7.5, 2, 7.5));
        husk.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        double floor = h.absoluteVec(new Vec3(0, 2, 0)).y;
        h.onEachTick(() -> {
            var p = c.controller().perch();
            if (husk.isAlive() && p.busy() && c.getY() >= floor + 1.9) {
                husk.kill(); // the danger is gone (killed by someone else)
            }
        });
        h.succeedWhen(() -> {
            var p = c.controller().perch();
            h.assertTrue(p.perches >= 1 && p.highest >= floor + 1.9, "chased, the clone jumps two blocks up (" + p.debug + " options "
                    + c.controller().optionLog + ")");
            h.assertTrue(!husk.isAlive() && !p.busy() && p.blocksRecovered >= 2 && c.getY() < floor + 0.5, "once it is safe it comes down again ("
                    + p.debug + ")");
            h.assertTrue(c.getInventory().countItem(Items.COBBLESTONE) >= 15, "taking the blocks back");
            finish(h, c);
        });
    }

    private static void portalLoop(GameTestHelper h, ClonePlayer c) {
        boolean[] active = {false};
        h.onEachTick(() -> {
            var ctl = c.controller();
            ctl.perception().update(h.getLevel().getGameTime());
            if (!active[0] && ctl.portals().hasWork()) {
                ctl.portals().begin();
                active[0] = true;
            }
            if (active[0] && ctl.portals().tick() != com.rlclones.ai.Portals.Status.WORKING) {
                active[0] = false;
            }
            ctl.motor().tick();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1500, batch = "obsidian")
    public static void makesObsidianWithWaterAndLava(GameTestHelper h) {
        clearBases(h);
        h.setBlock(new BlockPos(7, 0, 10), Blocks.STONE);
        h.setBlock(new BlockPos(7, 1, 10), Blocks.LAVA);
        ClonePlayer c = clone(h, 7.5, 6.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));
        c.getInventory().add(new ItemStack(Items.WATER_BUCKET));
        c.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 6000, 0));
        portalLoop(h, c);
        h.succeedWhen(() -> {
            StringBuilder drops = new StringBuilder();
            for (var it : h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, c.getBoundingBox().inflate(16))) {
                drops.append(' ').append(it.getItem()).append('@').append(it.blockPosition().subtract(h.absolutePos(BlockPos.ZERO)).toShortString());
            }
            h.assertTrue(c.getInventory().countItem(Items.OBSIDIAN) >= 1, "water on the lava source makes obsidian, mined with the diamond pickaxe ("
                    + c.controller().portals().debug + " mine=" + c.controller().motor().mineDebug + " made " + c.controller().portals().obsidianMade
                    + " block " + h.getBlockState(new BlockPos(7, 1, 10)) + " below " + h.getBlockState(new BlockPos(7, 0, 10)) + " drops" + drops
                    + " clone@" + c.blockPosition().subtract(h.absolutePos(BlockPos.ZERO)).toShortString() + ")");
            h.assertTrue(c.getInventory().countItem(Items.WATER_BUCKET) >= 1, "and the water is scooped back up");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "portal")
    public static void buildsAndLightsANetherPortal(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 7.5, 4.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.OBSIDIAN, 10));
        c.getInventory().add(new ItemStack(Items.COBBLESTONE, 4));
        c.getInventory().add(new ItemStack(Items.FLINT_AND_STEEL));
        String name = c.getGameProfile().getName();
        portalLoop(h, c);
        h.succeedWhen(() -> {
            boolean portal = false;
            for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(1, 2, 1)), h.absolutePos(new BlockPos(13, 8, 13)))) {
                portal |= h.getLevel().getBlockState(p).is(Blocks.NETHER_PORTAL);
            }
            h.assertTrue(portal, "the clone builds an obsidian frame and lights it (" + c.controller().portals().debug + ")");
            h.assertTrue(said(name, "PORTAL ") && !com.rlclones.clone.Bases.get(h.getLevel().getServer()).portals.isEmpty(),
                    "and tells the others where the portal is");
            finish(h, c);
            clearBases(h);
        });
    }

    /** An obsidian frame set into the floor (opening at x 6..7, y 2..4, z 10: walk straight in), lit, and known to the clones. */
    private static BlockPos litPortal(GameTestHelper h) {
        for (int x = 5; x <= 8; x++) {
            for (int y = 1; y <= 5; y++) {
                boolean edge = x == 5 || x == 8 || y == 1 || y == 5;
                h.setBlock(new BlockPos(x, y, 10), edge ? Blocks.OBSIDIAN : Blocks.AIR);
            }
        }
        h.setBlock(new BlockPos(6, 2, 10), Blocks.FIRE);
        BlockPos inside = h.absolutePos(new BlockPos(6, 2, 10));
        com.rlclones.clone.Bases.get(h.getLevel().getServer()).addPortal(h.getLevel().dimension(), inside);
        return inside;
    }

    @GameTest(template = ARENA, timeoutTicks = 3600, batch = "nether")
    public static void visitsTheNetherAndComesBack(GameTestHelper h) {
        clearBases(h);
        BlockPos inside = litPortal(h);
        h.assertTrue(h.getLevel().getBlockState(inside).is(Blocks.NETHER_PORTAL), "the test portal is lit");
        ClonePlayer c = clone(h, 6.5, 6.5, 0f, true);
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 6000, 0));
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.PORTAL;
        long[] start = {h.getLevel().getGameTime()};
        h.succeedWhen(() -> {
            var pt = c.controller().portals();
            h.assertTrue(pt.netherTrips >= 1 && c.getCloneBrain().hasFlag(com.rlclones.ai.Portals.NETHER_FLAG),
                    "a clone that has never been to the Nether goes through the portal (" + pt.debug + " options " + c.controller().optionLog + ")");
            h.assertTrue(pt.homeTrips >= 1 && c.level().dimension() == net.minecraft.world.level.Level.OVERWORLD,
                    "and comes back on its own (" + c.level().dimension().location() + " " + pt.debug + " events" + pt.events + " start@" + start[0]
                    + " alive=" + c.isAlive() + " death=" + c.controller().lastDeath + " options " + c.controller().optionLog + ")");
            finish(h, c);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "portal2")
    public static void fleesIntoAPortal(GameTestHelper h) {
        clearBases(h);
        litPortal(h);
        ClonePlayer c = clone(h, 6.5, 7.0, 180f, true); // looking at the husk; the portal is behind
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 6000, 0));
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FLEE;
        Husk husk = dummy(h, 6.5, 4.5);
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().portals().portalEscapes >= 1, "running away with a portal close by: into it (" + c.controller().optionLog + " "
                    + c.controller().perception().explain(husk) + ")");
            finish(h, c);
            clearBases(h);
        });
    }

    // ------------------------------------------------------------------ round 8

    @GameTest(template = ARENA, timeoutTicks = 40, batch = "r8give")
    public static void givesEveryCloneADifferentUsableItem(GameTestHelper h) {
        h.assertTrue(com.rlclones.clone.UsableItems.usable(Items.DIAMOND_SWORD) && com.rlclones.clone.UsableItems.usable(Items.ENDER_PEARL)
                && com.rlclones.clone.UsableItems.usable(Items.IRON_CHESTPLATE) && com.rlclones.clone.UsableItems.usable(Items.BOW)
                && com.rlclones.clone.UsableItems.usable(Items.SPLASH_POTION) && com.rlclones.clone.UsableItems.usable(Items.FISHING_ROD),
                "weapons, armour, pearls, potions, rods are usable");
        h.assertTrue(!com.rlclones.clone.UsableItems.usable(Items.IRON_INGOT) && !com.rlclones.clone.UsableItems.usable(Items.STICK)
                && !com.rlclones.clone.UsableItems.usable(Items.STRING) && !com.rlclones.clone.UsableItems.usable(Items.DIAMOND)
                && !com.rlclones.clone.UsableItems.usable(Items.COBBLESTONE) && !com.rlclones.clone.UsableItems.usable(Items.OAK_LOG)
                && !com.rlclones.clone.UsableItems.usable(Items.ZOMBIE_SPAWN_EGG) && !com.rlclones.clone.UsableItems.usable(Items.BEEF),
                "materials, blocks, spawn eggs and plain food are not");
        List<ClonePlayer> cs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            cs.add(clone(h, 3.5 + i * 2, 7.5, 0f, false));
        }
        List<ItemStack> given = com.rlclones.clone.UsableItems.giveEach(cs, h.getLevel().getRandom());
        h.assertTrue(given.size() == 5, "one item for each clone: " + given.size());
        java.util.Set<net.minecraft.world.item.Item> kinds = new java.util.HashSet<>();
        for (int i = 0; i < 5; i++) {
            ItemStack g = given.get(i);
            h.assertTrue(!(g.getItem() instanceof net.minecraft.world.item.BlockItem) && com.rlclones.clone.UsableItems.usable(g.getItem()), "usable: " + g);
            h.assertTrue(cs.get(i).getInventory().countItem(g.getItem()) >= 1, "it is in the clone's inventory: " + g);
            kinds.add(g.getItem());
        }
        h.assertTrue(kinds.size() == 5, "no item given to two clones: " + given);
        finish(h, cs.toArray(new ClonePlayer[0]));
        h.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 1600, batch = "r8tame")
    public static void tamesAWolfItSeesAndLetsItStand(GameTestHelper h) {
        ClonePlayer c = clone(h, 3.5, 3.5, -45f, true);
        c.getInventory().add(new ItemStack(Items.BONE, 16));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        var wolf = h.spawn(EntityType.WOLF, new Vec3(11.5, 2, 11.5));
        h.succeedWhen(() -> {
            h.assertTrue(wolf.isTame() && wolf.isOwnedBy(c), "the clone sets out to tame the wolf it sees (" + c.controller().optionLog + ")");
            h.assertTrue(!wolf.isOrderedToSit(), "and does not leave it sitting (stood up " + c.controller().animals().stoodUp + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 800, batch = "r8stand")
    public static void standsUpItsSittingPet(GameTestHelper h) {
        ClonePlayer c = clone(h, 4.5, 7.5, -90f, true);
        var wolf = h.spawn(EntityType.WOLF, new Vec3(9.5, 2, 7.5));
        wolf.tame(c);
        wolf.setOrderedToSit(true);
        h.succeedWhen(() -> {
            h.assertTrue(!wolf.isOrderedToSit(), "our pet is not left sitting (" + c.controller().optionLog + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1600, batch = "r8pearl")
    public static void pearlsOverALavaMoatWhenThatIsTheOnlyWay(GameTestHelper h) {
        for (int x = 1; x <= 13; x++) {
            for (int z = 1; z <= 13; z++) {
                for (int y = 2; y <= 64; y++) {
                    if (!h.getBlockState(new BlockPos(x, y, z)).isAir()) {
                        h.setBlock(new BlockPos(x, y, z), Blocks.AIR); // nothing left from an earlier test (gravel up there would fall into the moat)
                    }
                }
            }
        }
        for (int x = 5; x <= 10; x++) {
            for (int z = 1; z <= 13; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.OBSIDIAN);
                h.setBlock(new BlockPos(x, 1, z), Blocks.LAVA);
            }
        }
        h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class, new net.minecraft.world.phys.AABB(h.absolutePos(BlockPos.ZERO))
                .inflate(40, 120, 40)).forEach(net.minecraft.world.entity.Entity::discard); // anything already on its way down
        for (int y = 2; y <= 4; y++) {
            h.setBlock(new BlockPos(13, y, 7), Blocks.OAK_LOG);
        }
        ClonePlayer c = clone(h, 2.5, 7.5, -90f, true);
        for (int y = 2; y <= 4; y++) {
            c.controller().perception().noteBlock(h.absolutePos(new BlockPos(13, y, 7)));
        }
        c.getInventory().add(new ItemStack(Items.ENDER_PEARL, 4));
        c.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 6000, 0));
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.GATHER_WOOD;
        double farSide = h.absoluteVec(new Vec3(11, 2, 0)).x;
        StringBuilder where = new StringBuilder();
        int[] tick = {0};
        h.onEachTick(() -> {
            tick[0]++;
            if (c.isInLava() && where.indexOf("lava") < 0) {
                where.append(String.format(Locale.ROOT, "lava@%d x%.1f z%.1f v%s %s shift=%s ground=%s path=%s ", tick[0], c.getX() - h.absoluteVec(Vec3.ZERO).x,
                        c.getZ() - h.absoluteVec(Vec3.ZERO).z, c.getDeltaMovement(), c.controller().option(), c.isShiftKeyDown(), c.onGround(),
                        c.controller().motor().recentGoal()));
            }
            int gravel = c.getInventory().countItem(Items.GRAVEL);
            if (where.length() < 300 && (gravel > 0 && where.indexOf("inv") < 0 || h.getBlockState(new BlockPos(5, 1, 7)).is(Blocks.GRAVEL) && where.indexOf("moat") < 0)) {
                where.append(gravel > 0 && where.indexOf("inv") < 0 ? "inv" : "moat").append("@").append(tick[0]).append(" x")
                        .append(String.format(Locale.ROOT, "%.1f", c.getX() - h.absoluteVec(Vec3.ZERO).x)).append(" ").append(c.controller().option()).append(' ');
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().consumables().travelPearls >= 1, "a pearl thrown across (" + where + c.controller().consumables().pearlDebug + " travel "
                    + c.controller().travel().debug + " at x " + String.format(Locale.ROOT, "%.1f", c.getX() - h.absoluteVec(Vec3.ZERO).x) + " stuck "
                    + c.controller().motor().stuckCount() + " " + c.controller().travel().guardDebug + " " + c.controller().travel().gapTrace + " " + c.controller().harvestTrace + " "
                    + c.controller().optionLog + ")");
            h.assertTrue(c.getX() >= farSide && c.isAlive(), "and the clone is on the far side");
            finish(h, c);
        });
    }

    private static net.minecraft.world.entity.monster.Husk target(GameTestHelper h, double x, double z, float health) {
        var husk = h.spawn(EntityType.HUSK, new Vec3(x, 2, z));
        husk.setNoAi(true);
        husk.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(health);
        husk.setHealth(health);
        return husk;
    }

    @GameTest(template = ARENA, timeoutTicks = 800, batch = "r8tnt")
    public static void usesTntInAFightAndPressesTheButton(GameTestHelper h) {
        ClonePlayer c = clone(h, 3.5, 7.5, -90f, true);
        c.getInventory().add(new ItemStack(Items.TNT, 2));
        c.getInventory().add(new ItemStack(Items.OAK_BUTTON, 2));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FIGHT;
        var a = target(h, 9.5, 7.0, 40f);
        var b = target(h, 9.5, 8.5, 40f);
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().explosives().tntUsed >= 1, "TNT placed and set off with the button (" + c.controller().explosives().debug + ")");
            h.assertTrue(a.getHealth() < 40f || !a.isAlive(), "the blast hurts the enemies (" + a.getHealth() + ", " + b.getHealth() + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 800, batch = "r8tnt2")
    public static void setsTntOffWithFlintAndSteel(GameTestHelper h) {
        ClonePlayer c = clone(h, 3.5, 7.5, -90f, true);
        c.getInventory().add(new ItemStack(Items.TNT, 2));
        c.getInventory().add(new ItemStack(Items.FLINT_AND_STEEL));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FIGHT;
        var a = target(h, 9.5, 7.5, 40f);
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().explosives().tntUsed >= 1, "TNT lit with flint and steel (" + c.controller().explosives().debug + ")");
            h.assertTrue(a.getHealth() < 40f || !a.isAlive(), "and it hurts the enemy (" + a.getHealth() + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 40, batch = "r8tnt3")
    public static void neverBuildsWithTnt(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.TNT, 16));
        h.assertTrue(!com.rlclones.ai.Equipment.isBuildingBlock(c, new ItemStack(Items.TNT)), "TNT is not a building block");
        h.assertTrue(com.rlclones.ai.Equipment.pillarBlockSlot(c) < 0, "nothing to pillar / bridge with when all we carry is TNT");
        finish(h, c);
        h.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 4800, batch = "r8use")
    public static void learnsHowToUseATrident(GameTestHelper h) {
        ClonePlayer c = clone(h, 5.5, 7.5, -90f, true);
        for (int i = 0; i < 2; i++) {
            ItemStack trident = new ItemStack(Items.TRIDENT);
            trident.enchant(net.minecraft.world.item.enchantment.Enchantments.LOYALTY, 3);
            c.getInventory().add(trident);
        }
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FIGHT;
        c.controller().forcedAction = CombatAction.USE_ITEM;
        target(h, 10.5, 7.5, 1000f);
        h.succeedWhen(() -> {
            Brain b = c.getCloneBrain();
            String id = "minecraft:trident";
            StringBuilder sb = new StringBuilder();
            for (int m = 0; m < Brain.USE_MODES; m++) {
                sb.append(' ').append(com.rlclones.ai.AttackLearning.MODE_NAMES[m]).append('=').append(b.itemUseTries(id, m)).append('/')
                        .append(String.format(Locale.ROOT, "%.1f", b.itemUseValue(id, m)));
            }
            for (int m = 0; m < Brain.USE_MODES; m++) {
                h.assertTrue(b.itemUseTries(id, m) >= 1, "every way of using it tried:" + sb + " (" + c.controller().attacks().debug + " "
                        + c.controller().optionLog + " tridents " + c.getInventory().countItem(Items.TRIDENT) + ")");
            }
            int best = b.bestItemUse(id);
            h.assertTrue(b.itemUseTries(id) >= 6 && best != com.rlclones.ai.AttackLearning.TAP
                    && b.itemUseValue(id, com.rlclones.ai.AttackLearning.TAP) < b.itemUseValue(id, best), "a quick click does nothing, the rest hurts:" + sb);
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1600, batch = "r8useless")
    public static void learnsThatSnowballsDoNothingToAHusk(GameTestHelper h) {
        ClonePlayer c = clone(h, 4.5, 7.5, -90f, true);
        c.getInventory().add(new ItemStack(Items.SNOWBALL, 16));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FIGHT;
        c.controller().forcedAction = CombatAction.USE_ITEM;
        target(h, 9.5, 7.5, 1000f);
        int[] after = {-1};
        long[] learnedAt = {-1};
        h.onEachTick(() -> {
            EnemyKnowledge k = c.getCloneBrain().knowledgeIfPresent("minecraft:husk");
            if (learnedAt[0] < 0 && com.rlclones.ai.AttackLearning.useless(k, "thrown:minecraft:snowball")) {
                learnedAt[0] = h.getTick();
                after[0] = c.getInventory().countItem(Items.SNOWBALL);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(learnedAt[0] >= 0, "snowballs hit but take no health: learned useless against husks (" + c.controller().attacks().debug + ")");
            h.assertTrue(h.getTick() - learnedAt[0] >= 120, "watching what it does next");
            h.assertTrue(c.getInventory().countItem(Items.SNOWBALL) >= after[0] - 1, "no more snowballs thrown at husks ("
                    + c.getInventory().countItem(Items.SNOWBALL) + " left, " + after[0] + " when learned)");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1600, batch = "r8food")
    public static void asksForFoodAndAFriendBringsSome(GameTestHelper h) {
        ClonePlayer hungry = clone(h, 3.5, 3.5, 0f, true);
        hungry.getFoodData().setFoodLevel(4);
        hungry.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        ClonePlayer friend = clone(h, 11.5, 11.5, 0f, true);
        friend.getInventory().add(new ItemStack(Items.BREAD, 12));
        h.succeedWhen(() -> {
            h.assertTrue(com.rlclones.ai.Chat.recent().stream().anyMatch(l -> l.startsWith(hungry.getGameProfile().getName() + ": FOOD ")), "asked for food in chat");
            h.assertTrue(friend.controller().foodAid().given >= 1, "a clone with food to spare brought some (" + friend.controller().optionLog + " "
                    + friend.controller().foodAid().state() + ")");
            h.assertTrue(hungry.getInventory().countItem(Items.BREAD) > 0 || hungry.getFoodData().getFoodLevel() > 4, "and the hungry one got it");
            h.assertTrue(hungry.controller().foodAid().gratitude(friend.getUUID()) > 0, "who helped is remembered");
            finish(h, hungry, friend);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 3000, batch = "r8follow")
    public static void followsWhoHelpedIt(GameTestHelper h) {
        ClonePlayer hungry = clone(h, 3.5, 3.5, 0f, true);
        hungry.getFoodData().setFoodLevel(4);
        hungry.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        ClonePlayer friend = clone(h, 11.5, 11.5, 0f, true);
        friend.getInventory().add(new ItemStack(Items.BREAD, 12));
        ClonePlayer other = clone(h, 3.5, 6.5, 0f, false);
        boolean[] moved = {false};
        boolean[] followedHelper = {false};
        h.onEachTick(() -> {
            followedHelper[0] |= moved[0] && hungry.controller().followDebug.startsWith(friend.getGameProfile().getName());
            if (!moved[0] && hungry.controller().foodAid().gratitude(friend.getUUID()) > 0) {
                moved[0] = true;
                friend.setAiEnabled(false);
                Vec3 far = h.absoluteVec(new Vec3(12.5, 2, 12.5));
                friend.teleportTo(h.getLevel(), far.x, far.y, far.z, 0f, 0f);
                other.teleportTo(h.getLevel(), hungry.getX(), hungry.getY(), hungry.getZ() + 1.5, 0f, 0f);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(moved[0], "first the friend brings food");
            h.assertTrue(followedHelper[0], "then the clone goes after its helper rather than whoever is nearest (follows " + hungry.controller().followDebug
                    + ", helper " + friend.getGameProfile().getName() + ", nearest " + other.getGameProfile().getName() + ", gratitude "
                    + hungry.controller().foodAid().gratitudeView() + ", " + hungry.controller().optionLog + ")");
            finish(h, hungry, friend, other);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1600, batch = "r8breed")
    public static void twoWellFedClonesMakeANewOne(GameTestHelper h) {
        CloneManager m = manager(h);
        m.setBreeding(true);
        ClonePlayer a = clone(h, 3.5, 7.5, -90f, true);
        ClonePlayer b = clone(h, 10.5, 7.5, 90f, true);
        a.getInventory().add(new ItemStack(Items.BREAD, 8));
        b.getInventory().add(new ItemStack(Items.BREAD, 8));
        a.getCloneBrain().setFlag("test:from_a");
        b.getCloneBrain().setFlag("test:from_b");
        java.util.Set<UUID> before = new java.util.HashSet<>();
        m.clones().forEach(x -> before.add(x.getUUID()));
        h.succeedWhen(() -> {
            ClonePlayer child = null;
            for (ClonePlayer x : m.clones()) {
                if (!before.contains(x.getUUID()) && CloneManager.isParentOf(a, x) && CloneManager.isParentOf(b, x)) {
                    child = x;
                }
            }
            h.assertTrue(child != null, "a new clone born of the two (" + a.controller().optionLog + " / " + b.controller().optionLog + ")");
            h.assertTrue(child.getCloneBrain().hasFlag("test:from_a") && child.getCloneBrain().hasFlag("test:from_b"), "knowing what both parents knew");
            h.assertTrue(a.getFoodData().getFoodLevel() <= 6 && b.getFoodData().getFoodLevel() <= 6, "the parents gave up their food gauge");
            m.setBreeding(false);
            finish(h, a, b, child);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 100, batch = "r8breed2")
    public static void aPlayerCanMakeAChildWithAClone(GameTestHelper h) {
        CloneManager m = manager(h);
        m.setBreeding(true);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.BREAD, 8));
        c.getCloneBrain().setFlag("test:clone_parent");
        net.minecraft.world.entity.player.Player player = h.makeMockPlayer();
        Vec3 p = h.absoluteVec(new Vec3(8.5, 2, 7.5));
        player.moveTo(p.x, p.y, p.z, 0f, 0f);
        player.getInventory().add(new ItemStack(Items.BREAD, 8));
        var ev = new net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, c);
        com.rlclones.event.ServerEvents.onInteract(ev);
        ClonePlayer child = null;
        for (ClonePlayer x : m.clones()) {
            if (CloneManager.isParentOf(c, x) && CloneManager.isParentOf(player, x)) {
                child = x;
            }
        }
        m.setBreeding(false);
        h.assertTrue(child != null, "right-clicking a clone with breeding on makes a child");
        h.assertTrue(child.getCloneBrain().hasFlag("test:clone_parent"), "that knows what the clone parent knew");
        h.assertTrue(player.getFoodData().getFoodLevel() <= 6 && c.getFoodData().getFoodLevel() <= 6, "both gave up their food gauge");
        finish(h, c, child);
        h.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 1600, batch = "r8child")
    public static void parentsFeedTheirChildFirst(GameTestHelper h) {
        ClonePlayer parent = clone(h, 7.5, 3.5, 0f, true);
        parent.getInventory().add(new ItemStack(Items.BREAD, 8));
        ClonePlayer stranger = clone(h, 3.5, 11.5, 0f, true);
        ClonePlayer child = clone(h, 11.5, 11.5, 0f, true);
        manager(h).recordParents(child.getUUID(), parent.getUUID(), UUID.randomUUID());
        for (ClonePlayer x : List.of(stranger, child)) {
            x.getFoodData().setFoodLevel(4);
            x.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        }
        h.succeedWhen(() -> {
            var aid = parent.controller().foodAid();
            h.assertTrue(aid.given >= 1, "the parent shares food (" + parent.controller().optionLog + ")");
            h.assertTrue(child.getUUID().equals(aid.lastHelped) || aid.given >= 2 && child.getInventory().countItem(Items.BREAD) > 0
                    && stranger.getInventory().countItem(Items.BREAD) == 0 || child.getInventory().countItem(Items.BREAD) > 0
                    && stranger.getInventory().countItem(Items.BREAD) == 0, "its own child first");
            finish(h, parent, stranger, child);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 5000, batch = "r8cows")
    public static void pensCowsWhenWheatPilesUp(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 4.5, 4.5, 0f, true);
        c.getInventory().add(new ItemStack(Items.WHEAT, 20));
        c.getInventory().add(new ItemStack(Items.OAK_FENCE, 24));
        c.getInventory().add(new ItemStack(Items.OAK_FENCE_GATE, 1));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        var cow1 = h.spawn(EntityType.COW, new Vec3(4.5, 2, 7.5));
        var cow2 = h.spawn(EntityType.COW, new Vec3(5.5, 2, 8.0));
        h.succeedWhen(() -> {
            var bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
            com.rlclones.clone.Bases.Pen pen = bases.nearestPen(h.getLevel().dimension(), c.position(), 64, "minecraft:cow");
            h.assertTrue(pen != null, "a pen built for the cows (" + c.controller().optionLog + " " + c.controller().animals().livestockDebug + " remembered "
                    + c.controller().perception().remembered().size() + " cows " + cow1.isAlive() + "@" + cow1.blockPosition().subtract(h.absolutePos(BlockPos.ZERO)).toShortString()
                    + " " + cow2.isAlive() + " clone@" + c.blockPosition().subtract(h.absolutePos(BlockPos.ZERO)).toShortString() + ")");
            var gateNow = h.getLevel().getBlockState(pen.origin().offset(pen.size() / 2, 0, 0));
            h.assertTrue(pen.contains(cow1.position()) && pen.contains(cow2.position()), "both cows led in with the wheat (gate " + gateNow + " held "
                    + c.getMainHandItem() + " clone at " + c.blockPosition().subtract(pen.origin()).toShortString() + " pens " + bases.pens.size() + " log" + c.controller().animals().livestockLog + " lured "
                    + c.controller().animals().lured + " " + c.controller().animals().livestockDebug + " cows at "
                    + cow1.blockPosition().subtract(pen.origin()).toShortString() + " / " + cow2.blockPosition().subtract(pen.origin()).toShortString() + ")");
            var gate = h.getLevel().getBlockState(pen.origin().offset(pen.size() / 2, 0, 0));
            h.assertTrue(gate.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock
                    && !gate.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN), "and the gate shut");
            h.assertTrue(!pen.contains(c.position()), "the clone came out");
            finish(h, c);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 2400, batch = "r8adv")
    public static void goesForAnAdvancementWhenIdle(GameTestHelper h) {
        h.setBlock(new BlockPos(10, 1, 10), Blocks.LAVA);
        h.setBlock(new BlockPos(10, 0, 10), Blocks.STONE);
        ClonePlayer c = clone(h, 4.5, 4.5, 0f, true);
        c.getInventory().add(new ItemStack(Items.BUCKET));
        c.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 6000, 0));
        h.succeedWhen(() -> {
            var adv = h.getLevel().getServer().getAdvancements().getAdvancement(new net.minecraft.resources.ResourceLocation("minecraft", "story/lava_bucket"));
            String fact = c.getCloneBrain().advancementFact("minecraft:story/lava_bucket");
            h.assertTrue(fact != null && fact.contains("obtain minecraft:lava_bucket"), "it read what the advancement needs: " + fact);
            h.assertTrue(c.getAdvancements().getOrStartProgress(adv).isDone(), "and went for it with nothing else to do ("
                    + c.controller().achievements().debug + " | " + c.controller().achievements().fillDebug + " " + c.controller().optionLog + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "r8hoe")
    public static void makesAHoeRightAfterThePickaxe(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        h.setBlock(new BlockPos(7, 2, 10), Blocks.CRAFTING_TABLE);
        c.controller().perception().noteBlock(h.absolutePos(new BlockPos(7, 2, 10)));
        c.getInventory().add(new ItemStack(Items.WOODEN_PICKAXE));
        c.getInventory().add(new ItemStack(Items.OAK_PLANKS, 4));
        c.getInventory().add(new ItemStack(Items.STICK, 2));
        h.onEachTick(() -> {
            c.controller().perception().update(h.getLevel().getGameTime());
            c.controller().crafting().tick();
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.getInventory().countItem(Items.WOODEN_HOE) >= 1, "the hoe is made before a sword or an axe (" + c.controller().crafting().debug() + ")");
            h.assertTrue(c.getInventory().countItem(Items.WOODEN_SWORD) == 0 && c.getInventory().countItem(Items.WOODEN_AXE) == 0, "with the wood it had");
            finish(h, c);
        });
    }

    /** A stone pickaxe and everything else stone age, so that digging down is all that is left to do. */
    private static void stairsKit(GameTestHelper h, ClonePlayer c) {
        for (var it : List.of(Items.STONE_PICKAXE, Items.STONE_SWORD, Items.STONE_AXE, Items.STONE_HOE, Items.STONE_SHOVEL)) {
            c.getInventory().add(new ItemStack(it));
        }
        c.getInventory().add(new ItemStack(Items.COBBLESTONE, 4));
        h.setBlock(new BlockPos(1, 2, 1), Blocks.FURNACE); // a furnace already standing about
        c.controller().perception().noteBlock(h.absolutePos(new BlockPos(1, 2, 1)));
    }

    private static void stoneMass(GameTestHelper h) {
        for (int x = 3; x <= 11; x++) {
            for (int z = 6; z <= 8; z++) {
                for (int y = 2; y <= 4; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
    }

    @GameTest(template = ARENA, timeoutTicks = 3000, batch = "r8stairs")
    public static void digsAStaircaseDownWhenThereIsNothingElseToDo(GameTestHelper h) {
        clearBases(h);
        stoneMass(h);
        ClonePlayer c = clone(h, 4.5, 7.5, -90f, false);
        Vec3 top = h.absoluteVec(new Vec3(4.5, 5, 7.5));
        c.teleportTo(h.getLevel(), top.x, top.y, top.z, -90f, 0f);
        stairsKit(h, c);
        for (String b : List.of("minecraft:stone", "minecraft:deepslate", "minecraft:cobblestone", "minecraft:tuff", "minecraft:bedrock", "minecraft:furnace")) {
            c.getCloneBrain().learnBlock(b);
        }
        c.controller().stairs().assumeSurface = true;
        c.controller().stairs().targetY = (int) top.y - 40;
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            var bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
            h.assertTrue(com.rlclones.ai.StairMining.ironGeared(c) == false && bases.staircases.size() >= 1, "a staircase started ("
                    + c.controller().optionLog + " " + c.controller().stairs().debug + " | " + c.controller().stairs().trace + " at "
                    + c.blockPosition().subtract(h.absolutePos(BlockPos.ZERO)).toShortString() + ")");
            h.assertTrue(c.controller().stairs().stepsDug >= 3, "dug down step by step: "
                    + c.controller().stairs().stepsDug + " " + c.controller().stairs().debug);
            finish(h, c);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 3000, batch = "r8stairs2")
    public static void carriesOnDownAStaircaseAlreadyStarted(GameTestHelper h) {
        clearBases(h);
        stoneMass(h);
        for (int y = 4; y <= 6; y++) {
            h.setBlock(new BlockPos(5, y, 7), Blocks.AIR);
        }
        for (int y = 3; y <= 5; y++) {
            h.setBlock(new BlockPos(6, y, 7), Blocks.AIR);
        }
        var bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
        var st = bases.addStaircase(h.getLevel().dimension(), h.absolutePos(new BlockPos(4, 5, 7)), Direction.EAST);
        st.end = h.absolutePos(new BlockPos(6, 3, 7));
        int endBefore = st.end.getY();
        ClonePlayer c = clone(h, 10.5, 7.5, 90f, false);
        Vec3 top = h.absoluteVec(new Vec3(10.5, 5, 7.5));
        c.teleportTo(h.getLevel(), top.x, top.y, top.z, 90f, 0f);
        stairsKit(h, c);
        for (String b : List.of("minecraft:stone", "minecraft:deepslate", "minecraft:cobblestone", "minecraft:tuff", "minecraft:bedrock")) {
            c.getCloneBrain().learnBlock(b);
        }
        c.controller().stairs().targetY = (int) top.y - 40;
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.STAIRS;
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().stairs().resumed >= 1, "the known staircase is used (" + c.controller().stairs().debug + ")");
            h.assertTrue(bases.staircases.size() == 1 && bases.staircases.get(0).end.getY() < endBefore && c.controller().stairs().stepsDug >= 1,
                    "and dug on from its bottom (" + c.controller().stairs().debug + " | " + c.controller().stairs().trace + " at "
                    + c.blockPosition().subtract(h.absolutePos(BlockPos.ZERO)).toShortString() + " " + c.controller().optionLog + ")");
            finish(h, c);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1600, batch = "r8hill")
    public static void digsStairsUpAHillToReachItsGoal(GameTestHelper h) {
        clearBases(h);
        for (int x = 6; x <= 13; x++) {
            for (int z = 1; z <= 13; z++) {
                for (int y = 2; y <= 4; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        ClonePlayer friend = clone(h, 10.5, 7.5, 90f, false);
        Vec3 hillTop = h.absoluteVec(new Vec3(10.5, 5, 7.5));
        friend.teleportTo(h.getLevel(), hillTop.x, hillTop.y, hillTop.z, 90f, 0f);
        ClonePlayer c = clone(h, 3.5, 7.5, -90f, true);
        stairsKit(h, c); // stone age done: nothing to quarry the hill for
        for (String b : List.of("minecraft:stone", "minecraft:deepslate", "minecraft:cobblestone")) {
            c.getCloneBrain().learnBlock(b);
        }
        c.controller().foodAid().thank(friend.getUUID(), 10f); // someone we owe: followed even when out of sight
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FOLLOW; // up there with the friend
        double topY = h.absoluteVec(new Vec3(0, 5, 0)).y;
        boolean[] up = {false};
        h.onEachTick(() -> up[0] |= c.getY() >= topY - 0.01 && c.onGround());
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().travel().stairSteps >= 2, "stairs dug up the slope (" + c.controller().travel().stairsDebug + " " + c.controller().travel().debug
                    + " " + c.controller().travel().guardDebug + " " + c.controller().optionLog + ")");
            h.assertTrue(up[0], "and up on top of the hill");
            finish(h, c, friend);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1600, batch = "r8sbridge")
    public static void bridgesUpToHigherGround(GameTestHelper h) {
        clearBases(h);
        for (int x = 5; x <= 9; x++) {
            for (int z = 1; z <= 13; z++) {
                h.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
                h.setBlock(new BlockPos(x, 0, z), Blocks.AIR);
                h.setBlock(new BlockPos(x, -1, z), Blocks.LAVA);
            }
        }
        for (int x = 10; x <= 13; x++) {
            for (int z = 1; z <= 13; z++) {
                h.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
                h.setBlock(new BlockPos(x, 3, z), Blocks.STONE);
            }
        }
        ClonePlayer friend = clone(h, 12.5, 7.5, 90f, false);
        Vec3 there = h.absoluteVec(new Vec3(12.5, 4, 7.5));
        friend.teleportTo(h.getLevel(), there.x, there.y, there.z, 90f, 0f);
        ClonePlayer c = clone(h, 2.5, 7.5, -90f, true);
        c.getInventory().add(new ItemStack(Items.COBBLESTONE, 32));
        c.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 6000, 0));
        c.controller().foodAid().thank(friend.getUUID(), 10f); // someone we owe: followed even when out of sight
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FOLLOW; // over there with the friend
        Vec3 far = h.absoluteVec(new Vec3(10, 4, 0));
        h.succeedWhen(() -> {
            var t = c.controller().travel();
            h.assertTrue(t.stairBridges >= 1, "a bridge that climbs (" + t.stairBridgeDebug + " / " + t.debug + ")");
            boolean rising = false;
            for (int z = 1; z <= 13; z++) {
                boolean low = false;
                for (int x = 3; x <= 6; x++) {
                    low |= h.getBlockState(new BlockPos(x, 2, z)).is(Blocks.COBBLESTONE) && h.getBlockState(new BlockPos(x + 1, 3, z)).is(Blocks.COBBLESTONE);
                }
                rising |= low && h.getBlockState(new BlockPos(9, 3, z)).is(Blocks.COBBLESTONE);
            }
            h.assertTrue(rising, "built like steps: a step up, then on at the far side's height (" + t.stairBridgeDebug + ")");
            h.assertTrue(c.getX() >= far.x && c.getY() >= far.y - 0.01, "and the clone is up on the far side");
            finish(h, c, friend);
        });
    }

    // ------------------------------------------------------------------ AC round 9

    private static ItemStack warRockets(int n) {
        ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET, n);
        net.minecraft.nbt.CompoundTag fw = rocket.getOrCreateTagElement("Fireworks");
        net.minecraft.nbt.ListTag ex = new net.minecraft.nbt.ListTag();
        net.minecraft.nbt.CompoundTag star = new net.minecraft.nbt.CompoundTag();
        star.putByte("Type", (byte) 0);
        star.putIntArray("Colors", new int[]{0xFF0000});
        ex.add(star);
        fw.put("Explosions", ex);
        fw.putByte("Flight", (byte) 1);
        return rocket;
    }

    private static net.minecraft.world.entity.monster.Husk slowHusk(GameTestHelper h, double x, double y, double z) {
        var husk = h.spawn(EntityType.HUSK, new Vec3(x, y, z));
        husk.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 6000, 255));
        husk.setPersistenceRequired();
        return husk;
    }

    private static ClonePlayer creative(GameTestHelper h, double x, double z, float yaw) {
        ClonePlayer cr = clone(h, x, z, yaw, true);
        cr.setGameMode(GameType.CREATIVE);
        return cr;
    }

    @GameTest(template = ARENA, timeoutTicks = 2400, batch = "r9tells")
    public static void learnsTheSignsOfAnAttackAndRaisesTheShieldInTime(GameTestHelper h) {
        // what announces an attack: a creeper swelling (synced entity data, as modded bosses animate theirs), a bow drawn
        clearAbove(h); // (gravel overhead fell onto the skeleton and smothered it: no damage, no source)
        String[] cells = {""};
        for (int dy = 1; dy <= 4; dy++) {
            cells[0] += " y" + dy + "=" + h.getBlockState(new BlockPos(10, dy, 7)).getBlock().getDescriptionId().replace("block.minecraft.", "");
        }
        var creeper = h.spawn(EntityType.CREEPER, new Vec3(12.5, 2, 12.5));
        creeper.setNoAi(true);
        creeper.setSwellDir(1);
        List<String> swell = com.rlclones.ai.AttackTells.cues(creeper);
        creeper.discard();
        h.assertTrue(swell.contains("d16=1"), "a swelling creeper shows it in its data: " + swell);
        ClonePlayer rival = clone(h, 12.5, 2.5, 0f, false);
        rival.getInventory().add(new ItemStack(Items.ARROW, 4));
        rival.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        rival.startUsingItem(InteractionHand.MAIN_HAND);
        List<String> draw = com.rlclones.ai.AttackTells.cues(rival);
        manager(h).remove(rival, true, Component.literal("cue checked"));
        h.assertTrue(draw.contains("use:bow"), "a clone drawing its bow shows it: " + draw);

        ClonePlayer c = clone(h, 3.5, 7.5, -90f, true);
        c.getInventory().add(new ItemStack(Items.IRON_SWORD));
        c.getInventory().add(new ItemStack(Items.SHIELD));
        com.rlclones.ai.Equipment.manage(c, true);
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 6000, 2));
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FIGHT;
        c.controller().forcedAction = CombatAction.HOLD; // only the reflex raises the shield
        var sk = h.spawn(EntityType.SKELETON, new Vec3(10.5, 2, 7.5));
        sk.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        sk.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        sk.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 6000, 255));
        sk.setPersistenceRequired();
        String[] death = {""};
        h.onEachTick(() -> {
            if (death[0].isEmpty() && !sk.isAlive()) {
                var src = sk.getLastDamageSource();
                death[0] = "t" + h.getTick() + " " + (src == null ? "no source" : src.getMsgId() + " by " + src.getEntity() + " direct " + src.getDirectEntity())
                        + " removal " + sk.getRemovalReason() + " cells at start" + cells[0] + " now"
                        + " y2=" + h.getBlockState(new BlockPos(10, 2, 7)).getBlock().getDescriptionId().replace("block.minecraft.", "")
                        + " y3=" + h.getBlockState(new BlockPos(10, 3, 7)).getBlock().getDescriptionId().replace("block.minecraft.", "") + " clone at " + c.blockPosition().subtract(h.absolutePos(BlockPos.ZERO)).toShortString() + " option " + c.controller().option();
            }
        });
        h.succeedWhen(() -> {
            EnemyKnowledge k = c.getCloneBrain().knowledgeIfPresent("minecraft:skeleton");
            String traits = k == null ? "none" : k.traits.toString();
            h.assertTrue(k != null && com.rlclones.ai.AttackTells.isTell(k, "use:bow"), "the drawn bow is learned as the sign of a shot (" + traits + " | skeleton " + (sk.isAlive() ? "alive" : "died " + death[0] + " | dead by " + (sk.getLastDamageSource() == null ? "-" : sk.getLastDamageSource().getMsgId() + " " + sk.getLastDamageSource().getEntity()) + " dmg " + sk.getLastHurtByMob()) + " " + sk.blockPosition().subtract(h.absolutePos(BlockPos.ZERO)).toShortString()
                    + " hp " + (int) c.getHealth() + " " + c.blockPosition().subtract(h.absolutePos(BlockPos.ZERO)).toShortString() + " " + c.controller().optionLog + " " + c.controller().tells().debug + ")");
            h.assertTrue(c.controller().tells().tellBlocks >= 2, "and the shield comes up in time to catch the arrows (" + c.controller().tells().tellBlocks
                    + " " + c.controller().tells().debug + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 100, batch = "r9sight")
    public static void seesMuchFurtherThanBefore(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        h.assertTrue(c.controller().perception().maxRange() >= 96, "view distance raised: " + c.controller().perception().maxRange());
        BlockPos base = h.absolutePos(new BlockPos(7, 2, 7));
        // a clear line straight up for one tick only (put back at once: no water or lava from above runs down it)
        net.minecraft.world.level.block.state.BlockState[] was = new net.minecraft.world.level.block.state.BlockState[76];
        for (int y = 2; y <= 75; y++) {
            was[y] = h.getLevel().getBlockState(base.above(y));
            h.getLevel().setBlock(base.above(y), Blocks.AIR.defaultBlockState(), 2);
        }
        var stand = new net.minecraft.world.entity.decoration.ArmorStand(h.getLevel(), base.getX() + 0.5, base.getY() + 70, base.getZ() + 0.9);
        stand.setNoGravity(true);
        stand.setGlowingTag(true);
        h.getLevel().addFreshEntity(stand);
        c.setXRot(-90f);
        boolean seen = c.controller().perception().canSee(stand);
        String why = c.controller().perception().explain(stand);
        stand.discard();
        for (int y = 2; y <= 75; y++) {
            h.getLevel().setBlock(base.above(y), was[y], 2);
        }
        h.assertTrue(seen, "something 70 blocks off (beyond the old 48) is seen: " + why);
        finish(h, c);
        h.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "r9cover")
    public static void fleesBehindCoverOutOfSight(GameTestHelper h) {
        for (int x = 5; x <= 9; x++) {
            for (int y = 2; y <= 4; y++) {
                h.setBlock(new BlockPos(x, y, 11), Blocks.STONE);
            }
        }
        ClonePlayer c = clone(h, 6.5, 7.5, 90f, false);
        target(h, 3.5, 7.5, 40f);
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FLEE;
        h.runAfterDelay(5, () -> wake(h, c));
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().coverTaken >= 1, "the clone runs behind the wall where the husk cannot see it (at "
                    + c.blockPosition().subtract(h.absolutePos(BlockPos.ZERO)).toShortString() + " " + c.controller().optionLog + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "r9rocket")
    public static void shootsFireworksFromACrossbow(GameTestHelper h) {
        ClonePlayer c = clone(h, 2.5, 7.5, -90f, true);
        c.getInventory().add(new ItemStack(Items.CROSSBOW));
        c.getInventory().add(warRockets(8));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FIGHT;
        c.controller().forcedAction = CombatAction.SHOOT;
        var husk = target(h, 11.5, 7.5, 40f);
        boolean[] rocket = {false};
        h.onEachTick(() -> rocket[0] |= !h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.projectile.FireworkRocketEntity.class,
                c.getBoundingBox().inflate(24), r -> r.getOwner() == c).isEmpty());
        h.succeedWhen(() -> {
            h.assertTrue(rocket[0] && c.controller().rocketShots >= 1, "a firework rocket is loaded into the crossbow and shot ("
                    + c.controller().shootDebug + ")");
            h.assertTrue(husk.getHealth() < 40f, "and it blows up on the husk");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "r9fire")
    public static void setsTheGroundUnderTheEnemyAlight(GameTestHelper h) {
        ClonePlayer c = clone(h, 4.5, 7.5, -90f, false);
        h.runAfterDelay(5, () -> wake(h, c)); // its first decision taken with the husk in view
        c.getInventory().add(new ItemStack(Items.FLINT_AND_STEEL));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 6000, 0));
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FIGHT;
        c.controller().forcedAction = CombatAction.HOLD;
        var husk = slowHusk(h, 8.5, 2, 7.5); // (a mob without AI never steps into the fire)
        boolean[] burnt = {false};
        h.onEachTick(() -> burnt[0] |= husk.isOnFire());
        h.succeedWhen(() -> {
            var k = c.getCloneBrain().knowledgeIfPresent("minecraft:husk");
            h.assertTrue(c.controller().consumables().firesSet >= 1 && burnt[0], "flint and steel sets the ground under the husk alight ("
                    + c.controller().consumables().trapDebug + ")");
            h.assertTrue(k != null && k.traits.getInt("effect:fire") >= 1, "and it is learned that fire works on it");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "r9web")
    public static void putsACobwebAtTheEnemysFeet(GameTestHelper h) {
        ClonePlayer c = clone(h, 4.5, 7.5, -90f, false);
        h.runAfterDelay(5, () -> wake(h, c)); // its first decision taken with the husk in view
        c.getInventory().add(new ItemStack(Items.COBWEB, 4));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FIGHT;
        c.controller().forcedAction = CombatAction.HOLD;
        slowHusk(h, 8.5, 2, 7.5);
        h.succeedWhen(() -> {
            var k = c.getCloneBrain().knowledgeIfPresent("minecraft:husk");
            h.assertTrue(c.controller().consumables().websPlaced >= 1 && h.getBlockState(new BlockPos(8, 2, 7)).is(Blocks.COBWEB),
                    "a cobweb goes down where the husk stands (" + c.controller().consumables().trapDebug + " / " + c.controller().consumables().trapWhy
                    + " options " + c.controller().optionLog + ")");
            h.assertTrue(k != null && k.traits.getInt("effect:web") >= 1, "and it is learned that it holds it fast");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "r9offhand")
    public static void keepsTheBowOrFoodInTheOffHand(GameTestHelper h) {
        ClonePlayer c = clone(h, 2.5, 7.5, -90f, true);
        c.getInventory().add(new ItemStack(Items.IRON_SWORD));
        c.getInventory().add(new ItemStack(Items.BOW));
        c.getInventory().add(new ItemStack(Items.ARROW, 32));
        com.rlclones.ai.Equipment.manage(c, true);
        h.assertTrue(c.getOffhandItem().is(Items.BOW) && c.getMainHandItem().is(Items.IRON_SWORD),
                "no shield or totem: the bow goes to the off hand, the sword stays in the main hand (" + c.getOffhandItem() + "/" + c.getMainHandItem() + ")");
        ClonePlayer d = clone(h, 2.5, 3.5, -90f, false);
        d.getInventory().add(new ItemStack(Items.IRON_SWORD));
        d.getInventory().add(new ItemStack(Items.BREAD, 8));
        com.rlclones.ai.Equipment.manage(d, true);
        h.assertTrue(d.getOffhandItem().is(Items.BREAD) && d.getMainHandItem().is(Items.IRON_SWORD), "nothing to shoot: food in the off hand");
        d.getFoodData().setFoodLevel(10);
        d.controller().forcedOption = com.rlclones.ai.strategy.Option.EAT;
        d.setAiEnabled(true);
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FIGHT;
        c.controller().forcedAction = CombatAction.SHOOT;
        dummy(h, 11.5, 7.5);
        boolean[] sword = {true};
        h.onEachTick(() -> sword[0] &= c.getMainHandItem().is(Items.IRON_SWORD));
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().offhandShots >= 1, "arrows are shot with the bow in the off hand (" + c.controller().shootDebug + ")");
            h.assertTrue(sword[0], "the sword never leaves the main hand");
            h.assertTrue(d.controller().offhandMeals >= 1 && d.getFoodData().getFoodLevel() > 10 && d.getMainHandItem().is(Items.IRON_SWORD),
                    "and the food is eaten straight from the off hand");
            finish(h, c, d);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 800, batch = "r9sapling")
    public static void plantsSaplings(GameTestHelper h) {
        clearBases(h);
        for (int x = 8; x <= 12; x++) {
            for (int z = 5; z <= 9; z++) {
                h.setBlock(new BlockPos(x, 1, z), Blocks.GRASS_BLOCK);
            }
        }
        ClonePlayer c = clone(h, 7.5, 7.5, -90f, false);
        c.getInventory().add(new ItemStack(Items.OAK_SAPLING, 3));
        h.runAfterDelay(5, () -> wake(h, c));
        h.succeedWhen(() -> {
            boolean planted = false;
            for (int x = 8; x <= 12; x++) {
                for (int z = 5; z <= 9; z++) {
                    planted |= h.getBlockState(new BlockPos(x, 2, z)).is(net.minecraft.tags.BlockTags.SAPLINGS);
                }
            }
            h.assertTrue(planted && c.controller().consumables().saplingsPlanted >= 1, "a sapling is planted in the grass ("
                    + c.controller().consumables().saplingDebug + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "r9fly")
    public static void creativeClonesFly(GameTestHelper h) {
        clearBases(h);
        for (int y = 2; y <= 8; y++) {
            h.setBlock(new BlockPos(11, y, 7), Blocks.STONE);
        }
        ClonePlayer friend = clone(h, 11.5, 7.5, 90f, false);
        Vec3 top = h.absoluteVec(new Vec3(11.5, 9, 7.5));
        friend.teleportTo(h.getLevel(), top.x, top.y, top.z, 90f, 0f);
        friend.getFoodData().setFoodLevel(4);
        ClonePlayer cr = creative(h, 1.5, 7.5, -90f);
        double[] maxY = {-1000};
        h.onEachTick(() -> maxY[0] = Math.max(maxY[0], cr.getY()));
        h.succeedWhen(() -> {
            h.assertTrue(cr.controller().motor().creativeFlightTicks > 0 && maxY[0] >= top.y - 0.5, "the creative clone flies up to the friend on the pillar ("
                    + cr.controller().creative().debug + " max y " + maxY[0] + " / " + top.y + ")");
            h.assertTrue(cr.controller().creative().gifts >= 1, "and hands over food up there");
            finish(h, cr, friend);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 800, batch = "r9tnt")
    public static void creativeBlowsUpACrowdWithTnt(GameTestHelper h) {
        clearBases(h);
        blastProof(h);
        ClonePlayer cr = creative(h, 2.5, 2.5, -45f);
        List<Husk> crowd = new ArrayList<>();
        for (double[] p : new double[][]{{6.5, 7.5}, {7.5, 7.5}, {6.5, 8.5}, {7.5, 8.5}, {8.5, 8.5}}) {
            crowd.add(target(h, p[0], p[1], 20f));
        }
        h.succeedWhen(() -> {
            var play = cr.controller().creative().play();
            h.assertTrue(play.tntLit >= 1, "TNT from the creative menu is put down by the crowd and lit (" + play.log + ")");
            h.assertTrue(crowd.stream().filter(e -> e.isAlive() && e.getHealth() >= 20f).count() <= 1, "and the blast catches the crowd");
            finish(h, cr);
        });
    }

    /** Obsidian under the floor: a blast in the arena cannot hollow out the ground the next tests stand on. */
    private static void blastProof(GameTestHelper h) {
        for (int x = 1; x <= 13; x++) {
            for (int z = 1; z <= 13; z++) {
                for (int y = -2; y <= 0; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.REINFORCED_DEEPSLATE);
                }
            }
        }
    }

    private static void baseAt(GameTestHelper h, BlockPos rel, String... done) {
        h.setBlock(rel, Blocks.CHEST);
        var bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
        var base = bases.add(h.getLevel().dimension(), h.absolutePos(rel), "friend");
        bases.addChest(base, h.absolutePos(rel));
        for (String kind : done) {
            bases.addProject(h.getLevel().dimension(), kind, h.absolutePos(rel)); // built already: not this test's business
        }
    }

    @GameTest(template = ARENA, timeoutTicks = 3600, batch = "r9build")
    public static void creativeBuildsPortalsAndAnEnchantingRoom(GameTestHelper h) {
        clearBases(h);
        ClonePlayer friend = clone(h, 7.5, 7.5, 0f, false);
        friend.getInventory().add(new ItemStack(Items.IRON_SWORD));
        ClonePlayer cr = creative(h, 6.5, 6.5, 0f);
        h.succeedWhen(() -> {
            var play = cr.controller().creative().play();
            h.assertTrue(play.enchantRooms >= 1, "an enchanting table with its bookshelves (" + play.log + ")");
            int shelves = 0;
            BlockPos table = null;
            for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(1, 2, 1)), h.absolutePos(new BlockPos(13, 2, 13)))) {
                if (h.getLevel().getBlockState(p).is(Blocks.ENCHANTING_TABLE)) {
                    table = p.immutable();
                }
            }
            h.assertTrue(table != null, "the table stands");
            for (BlockPos p : BlockPos.betweenClosed(table.offset(-2, 0, -2), table.offset(2, 1, 2))) {
                shelves += h.getLevel().getBlockState(p).is(Blocks.BOOKSHELF) ? 1 : 0;
            }
            h.assertTrue(shelves >= 15, "with 15 bookshelves around it: " + shelves);
            h.assertTrue(play.portalsBuilt >= 1, "a Nether portal built and lit (" + play.log + ")");
            h.assertTrue(play.endPortals >= 1, "an End portal, frames facing in and all eyes in (" + play.log + ")");
            finish(h, cr, friend);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 2400, batch = "r9farm")
    public static void creativeMakesAFarmByTheBase(GameTestHelper h) {
        clearBases(h);
        for (int x = 1; x <= 13; x++) {
            for (int z = 1; z <= 13; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        baseAt(h, new BlockPos(3, 2, 3), "enchanting", "nether_portal", "end_portal");
        ClonePlayer friend = clone(h, 5.5, 8.5, 0f, false);
        friend.getInventory().add(new ItemStack(Items.IRON_SWORD));
        ClonePlayer cr = creative(h, 5.5, 5.5, 0f);
        h.succeedWhen(() -> {
            var play = cr.controller().creative().play();
            int farmland = 0;
            int crops = 0;
            boolean water = false;
            for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(1, 1, 1)), h.absolutePos(new BlockPos(13, 2, 13)))) {
                var st = h.getLevel().getBlockState(p);
                farmland += st.is(Blocks.FARMLAND) ? 1 : 0;
                crops += st.is(Blocks.WHEAT) ? 1 : 0;
                water |= h.getLevel().getFluidState(p).isSource() && st.is(Blocks.WATER);
            }
            h.assertTrue(play.farmsMade >= 1 && farmland >= 8 && water, "the ground is broken open for water and a field tilled around it ("
                    + farmland + " farmland, " + play.log + ")");
            h.assertTrue(crops >= 4, "and sown: " + crops);
            finish(h, cr, friend);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 2400, batch = "r9mobs")
    public static void creativeSpawnsAnimalsAndBuildsAnIronGolem(GameTestHelper h) {
        clearBases(h);
        baseAt(h, new BlockPos(3, 2, 3), "enchanting", "nether_portal", "end_portal", "farm");
        ClonePlayer friend = clone(h, 5.5, 8.5, 0f, false);
        friend.getInventory().add(new ItemStack(Items.IRON_SWORD));
        ClonePlayer cr = creative(h, 5.5, 5.5, 0f);
        h.succeedWhen(() -> {
            var play = cr.controller().creative().play();
            h.assertTrue(play.mobsSpawned >= 3, "farm animals let out of spawn eggs (" + play.mobsSpawned + " " + play.log + ")");
            h.assertTrue(play.golemsBuilt >= 1 && !h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.IronGolem.class,
                    new net.minecraft.world.phys.AABB(h.absolutePos(BlockPos.ZERO)).inflate(20)).isEmpty(), "and an iron golem built (" + play.log + ")");
            finish(h, cr, friend);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "r9pchest")
    public static void aChestAPlayerPutsDownBecomesABase(GameTestHelper h) {
        clearBases(h);
        BlockPos rel = new BlockPos(7, 2, 11);
        h.setBlock(rel, Blocks.CHEST);
        BlockPos abs = h.absolutePos(rel);
        net.minecraft.world.entity.player.Player player = h.makeMockPlayer();
        com.rlclones.event.ServerEvents.onPlace(new net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent(
                net.minecraftforge.common.util.BlockSnapshot.create(h.getLevel().dimension(), h.getLevel(), abs), Blocks.STONE.defaultBlockState(), player));
        com.rlclones.clone.Bases bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
        h.assertTrue(bases.isBaseChest(h.getLevel().dimension(), abs), "the chest the player put down counts as a base chest");
        ClonePlayer c = clone(h, 7.5, 5.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.IRON_SWORD));
        c.getInventory().add(new ItemStack(Items.STONE_SWORD));
        c.getInventory().add(new ItemStack(Items.BONE, 3));
        c.getInventory().add(new ItemStack(Items.STRING, 4));
        c.getInventory().add(new ItemStack(Items.GUNPOWDER, 2));
        c.getInventory().add(new ItemStack(Items.FEATHER, 5));
        runStorage(h, c, com.rlclones.ai.Storage.Mode.STORE, new boolean[1]);
        h.succeedWhen(() -> {
            var be = (net.minecraft.world.Container) h.getLevel().getBlockEntity(abs);
            h.assertTrue(be.hasAnyOf(java.util.Set.of(Items.BONE)), "the clone stores its surplus in the player's chest");
            h.assertTrue(bases.bases.size() == 1, "and founds no base of its own");
            finish(h, c);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 800, batch = "r9potion")
    public static void creativeThrowsPotionsThatHurtTheCrowd(GameTestHelper h) {
        clearBases(h);
        ClonePlayer cr = creative(h, 2.5, 2.5, -45f);
        List<Husk> crowd = new ArrayList<>();
        for (double[] p : new double[][]{{9.5, 9.5}, {10.5, 9.5}, {9.5, 10.5}}) {
            crowd.add(target(h, p[0], p[1], 20f));
        }
        boolean[] healing = {false};
        h.onEachTick(() -> {
            for (var tp : h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.projectile.ThrownPotion.class, cr.getBoundingBox().inflate(32), t -> t.getOwner() == cr)) {
                for (var e : net.minecraft.world.item.alchemy.PotionUtils.getMobEffects(tp.getItem())) {
                    healing[0] |= e.getEffect() == MobEffects.HEAL;
                }
            }
        });
        h.succeedWhen(() -> {
            var play = cr.controller().creative().play();
            h.assertTrue(play.potionsThrown >= 1 && healing[0], "a splash potion of healing - which hurts the undead - is thrown at the crowd ("
                    + play.lastPotionThrown + " " + play.log + ")");
            h.assertTrue(crowd.stream().anyMatch(e -> !e.isAlive() || e.getHealth() < 20f), "and it hurts them");
            h.assertTrue(com.rlclones.ai.CreativePlay.potionScore(net.minecraft.world.item.alchemy.Potions.HARMING, false)
                    > com.rlclones.ai.CreativePlay.potionScore(net.minecraft.world.item.alchemy.Potions.HEALING, false), "the living get harming instead");
            finish(h, cr);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 800, batch = "r9cliff")
    public static void creativeBreaksTheGroundFromUnderAMonster(GameTestHelper h) {
        clearBases(h);
        for (int x = 6; x <= 8; x++) {
            h.setBlock(new BlockPos(x, 6, 7), Blocks.STONE); // a thin bridge high up, nothing under it
        }
        var husk = slowHusk(h, 7.5, 7, 7.5);
        ClonePlayer cr = creative(h, 1.5, 7.5, -90f);
        cr.setXRot(-25f);
        double startY = h.absoluteVec(new Vec3(0, 7, 0)).y;
        boolean[] fell = {false};
        h.onEachTick(() -> fell[0] |= husk.getY() < startY - 3);
        h.succeedWhen(() -> {
            var play = cr.controller().creative().play();
            h.assertTrue(play.cliffDrops >= 1 && fell[0], "the block under the husk is broken away and down it goes (" + play.log + ")");
            finish(h, cr);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1000, batch = "r9rod")
    public static void creativeHooksAMonsterFliesUpAndReelsItIn(GameTestHelper h) {
        clearBases(h);
        var husk = slowHusk(h, 9.5, 2, 7.5);
        ClonePlayer cr = creative(h, 3.5, 7.5, -90f);
        double floor = h.absoluteVec(new Vec3(0, 2, 0)).y;
        double[] maxY = {-1000};
        boolean[] fall = {false};
        h.onEachTick(() -> {
            maxY[0] = Math.max(maxY[0], husk.getY());
            var src = husk.getLastDamageSource();
            fall[0] |= src != null && src.is(net.minecraft.world.damagesource.DamageTypes.FALL);
        });
        h.succeedWhen(() -> {
            var play = cr.controller().creative().play();
            h.assertTrue(play.rodLaunches >= 1, "hooked with a rod, up into the air and reeled in (" + play.log + ")");
            h.assertTrue(maxY[0] > floor + 3 && fall[0], "the husk is flung up and hurt by the fall (max " + (maxY[0] - floor) + ")");
            finish(h, cr);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1200, batch = "r9hazard")
    public static void creativeBlowsUpAPatchOfHarmfulBlocks(GameTestHelper h) {
        clearBases(h);
        blastProof(h);
        for (int x = 6; x <= 10; x++) {
            for (int z = 5; z <= 9; z++) {
                if ((x + z) % 2 == 0) {
                    h.setBlock(new BlockPos(x, 2, z), Blocks.COBWEB);
                } else {
                    h.setBlock(new BlockPos(x, 1, z), Blocks.MAGMA_BLOCK);
                }
            }
        }
        ClonePlayer cr = creative(h, 2.5, 7.5, -90f);
        h.succeedWhen(() -> {
            var play = cr.controller().creative().play();
            int left = 0;
            for (int x = 6; x <= 10; x++) {
                for (int z = 5; z <= 9; z++) {
                    left += h.getBlockState(new BlockPos(x, 2, z)).is(Blocks.COBWEB) || h.getBlockState(new BlockPos(x, 1, z)).is(Blocks.MAGMA_BLOCK) ? 1 : 0;
                }
            }
            h.assertTrue(play.hazardBlasts >= 1 && left <= 12, "TNT clears the patch of cobwebs and magma (" + left + " of 25 left, " + play.log + ")");
            finish(h, cr);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1600, batch = "r9climb")
    public static void swimsUpAWaterfallToHigherGround(GameTestHelper h) {
        clearBases(h);
        for (int x = 9; x <= 13; x++) {
            for (int z = 1; z <= 13; z++) {
                for (int y = 2; y <= 7; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.OBSIDIAN);
                }
            }
        }
        for (int y = 2; y <= 8; y++) {
            h.setBlock(new BlockPos(8, y, 6), Blocks.GLASS);
            h.setBlock(new BlockPos(8, y, 8), Blocks.GLASS);
            if (y >= 4) {
                h.setBlock(new BlockPos(7, y, 7), Blocks.GLASS);
            }
        }
        h.setBlock(new BlockPos(8, 8, 7), Blocks.WATER); // falls down the glass tube and runs out at the bottom
        ClonePlayer friend = clone(h, 11.5, 7.5, 90f, false);
        Vec3 up = h.absoluteVec(new Vec3(11.5, 8, 7.5));
        friend.teleportTo(h.getLevel(), up.x, up.y, up.z, 90f, 0f);
        ClonePlayer c = clone(h, 3.5, 7.5, -90f, true);
        c.controller().foodAid().thank(friend.getUUID(), 10f);
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FOLLOW;
        boolean[] top = {false};
        h.onEachTick(() -> top[0] |= c.getY() >= up.y - 0.01 && c.onGround() && c.getX() > up.x - 3);
        h.succeedWhen(() -> {
            var t = c.controller().travel();
            h.assertTrue(top[0], "up on top by the friend (clone at " + c.position().subtract(h.absoluteVec(Vec3.ZERO)) + " " + t.waterDebug + " / " + t.debug + " "
                    + c.controller().optionLog + ")");
            h.assertTrue(t.waterClimbs >= 1, "by swimming up the falling water (" + t.waterDebug + ")");
            finish(h, c, friend);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1200, batch = "r9dive")
    public static void jumpsOffACliffIntoTheWaterBelow(GameTestHelper h) {
        clearBases(h);
        for (int x = 1; x <= 4; x++) {
            for (int z = 1; z <= 13; z++) {
                for (int y = 2; y <= 9; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.OBSIDIAN);
                }
            }
        }
        for (int x = 5; x <= 9; x++) {
            for (int z = 5; z <= 9; z++) {
                boolean rim = x == 5 || x == 9 || z == 5 || z == 9;
                h.setBlock(new BlockPos(x, 2, z), rim ? Blocks.GLASS : Blocks.WATER);
            }
        }
        h.setBlock(new BlockPos(5, 2, 7), Blocks.WATER); // the pool reaches the foot of the cliff
        ClonePlayer friend = clone(h, 11.5, 7.5, 90f, false);
        ClonePlayer c = clone(h, 2.5, 7.5, -90f, true);
        Vec3 cliff = h.absoluteVec(new Vec3(2.5, 10, 7.5));
        c.teleportTo(h.getLevel(), cliff.x, cliff.y, cliff.z, -90f, 0f);
        c.controller().foodAid().thank(friend.getUUID(), 10f);
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FOLLOW;
        float[] lowest = {20f};
        h.onEachTick(() -> lowest[0] = Math.min(lowest[0], c.getHealth()));
        h.succeedWhen(() -> {
            var t = c.controller().travel();
            h.assertTrue(t.waterDrops >= 1 && c.getY() < cliff.y - 5, "down off the cliff into the water (" + t.waterDebug + " / " + t.debug + " "
                    + t.guardDebug + " " + c.controller().optionLog + ")");
            h.assertTrue(lowest[0] >= 20f, "without a scratch: " + lowest[0]);
            finish(h, c, friend);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1200, batch = "r9claim")
    public static void movesIntoABuildingStandingEmpty(GameTestHelper h) {
        clearBases(h);
        for (int x = 8; x <= 12; x++) {
            for (int z = 4; z <= 8; z++) {
                for (int y = 2; y <= 4; y++) {
                    if (x == 8 || x == 12 || z == 4 || z == 8) {
                        h.setBlock(new BlockPos(x, y, z), Blocks.OAK_PLANKS);
                    }
                }
                h.setBlock(new BlockPos(x, 5, z), Blocks.OAK_PLANKS);
            }
        }
        h.setBlock(new BlockPos(10, 2, 8), Blocks.AIR);
        h.setBlock(new BlockPos(10, 3, 8), Blocks.AIR); // the doorway
        ClonePlayer c = clone(h, 4.5, 11.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.CHEST));
        c.getInventory().add(new ItemStack(Items.IRON_SWORD));
        c.getInventory().add(new ItemStack(Items.STONE_SWORD));
        c.getInventory().add(new ItemStack(Items.BONE, 3));
        c.getInventory().add(new ItemStack(Items.STRING, 4));
        c.getInventory().add(new ItemStack(Items.GUNPOWDER, 2));
        c.getInventory().add(new ItemStack(Items.FEATHER, 5));
        runStorage(h, c, com.rlclones.ai.Storage.Mode.STORE, new boolean[1]);
        h.succeedWhen(() -> {
            var st = c.controller().storage();
            com.rlclones.clone.Bases bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
            var base = bases.nearest(h.getLevel().dimension(), c.position(), 32);
            h.assertTrue(st.buildingsClaimed >= 1 && base != null, "the clone moves into the empty house (" + st.claimDebug + " stage " + st.stage()
                    + " bases " + bases.bases.size() + (base == null ? "" : " at " + base.center.subtract(h.absolutePos(BlockPos.ZERO)).toShortString() + " by " + base.founder)
                    + " stages" + st.stageLog + ")");
            BlockPos center = base.center.subtract(h.absolutePos(BlockPos.ZERO));
            h.assertTrue(center.getX() >= 9 && center.getX() <= 11 && center.getZ() >= 5 && center.getZ() <= 8, "the base is inside it: " + center.toShortString());
            BlockPos chest = base.chests.get(0).subtract(h.absolutePos(BlockPos.ZERO));
            h.assertTrue(chest.getX() >= 9 && chest.getX() <= 11 && chest.getZ() >= 5 && chest.getZ() <= 7, "its chest stands inside: " + chest.toShortString());
            h.assertTrue(st.builder().placed == 0, "no hut of its own built");
            var be = (net.minecraft.world.Container) h.getLevel().getBlockEntity(base.chests.get(0));
            h.assertTrue(be != null && be.hasAnyOf(java.util.Set.of(Items.BONE)), "and the surplus goes into the chest");
            finish(h, c);
            clearBases(h);
        });
    }

    private static void shaftBlock(GameTestHelper h) {
        for (int x = 6; x <= 8; x++) {
            for (int z = 6; z <= 8; z++) {
                for (int y = 2; y <= 4; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
    }

    private static void shaftMind(GameTestHelper h, ClonePlayer c, double topY) {
        for (String b : List.of("minecraft:stone", "minecraft:deepslate", "minecraft:cobblestone", "minecraft:bedrock", "minecraft:furnace",
                "minecraft:crafting_table", "minecraft:ladder")) {
            c.getCloneBrain().learnBlock(b);
        }
        c.controller().shafts().assumeSurface = true;
        c.controller().shafts().targetY = h.absolutePos(new BlockPos(0, 2, 0)).getY(); // never through the arena floor
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.SHAFT;
    }

    @GameTest(template = ARENA, timeoutTicks = 3000, batch = "r9shaft")
    public static void digsAShaftStraightDownWithLadders(GameTestHelper h) {
        clearBases(h);
        shaftBlock(h);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        Vec3 top = h.absoluteVec(new Vec3(7.5, 5, 7.5));
        c.teleportTo(h.getLevel(), top.x, top.y, top.z, 0f, 0f);
        stairsKit(h, c);
        c.getInventory().add(new ItemStack(Items.STICK, 7));
        h.setBlock(new BlockPos(6, 5, 6), Blocks.CRAFTING_TABLE);
        c.controller().perception().noteBlock(h.absolutePos(new BlockPos(6, 5, 6)));
        shaftMind(h, c, top.y);
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            var sm = c.controller().shafts();
            com.rlclones.clone.Bases bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
            var s = bases.shafts.isEmpty() ? null : bases.shafts.get(0);
            h.assertTrue(sm.laddersCrafted >= 3, "ladders crafted first (" + sm.debug + " | " + sm.trace + " " + c.controller().optionLog + ")");
            h.assertTrue(s != null && s.depth() >= 3 && sm.laddersPlaced >= 3, "dug straight down with a ladder at every level ("
                    + (s == null ? "no shaft" : "depth " + s.depth()) + " ladders " + sm.laddersPlaced + " " + sm.debug + " | " + sm.trace + ")");
            int ladders = 0;
            for (int y = 2; y <= 4; y++) {
                ladders += h.getLevel().getBlockState(new BlockPos(s.top.getX(), h.absolutePos(new BlockPos(0, y, 0)).getY(), s.top.getZ())).is(Blocks.LADDER) ? 1 : 0;
            }
            h.assertTrue(ladders >= 3, "the ladders are on the wall: " + ladders);
            h.assertTrue(c.getInventory().countItem(Items.LADDER) == 0, "until they ran out");
            finish(h, c);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 3000, batch = "r9shaft2")
    public static void carriesOnDownAShaftAlreadyDug(GameTestHelper h) {
        clearBases(h);
        shaftBlock(h);
        var ladder = Blocks.LADDER.defaultBlockState().setValue(net.minecraft.world.level.block.LadderBlock.FACING, Direction.WEST);
        h.setBlock(new BlockPos(7, 4, 7), ladder);
        h.setBlock(new BlockPos(7, 3, 7), ladder);
        com.rlclones.clone.Bases bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
        var shaft = bases.addShaft(h.getLevel().dimension(), h.absolutePos(new BlockPos(7, 5, 7)), Direction.EAST);
        shaft.end = h.absolutePos(new BlockPos(7, 3, 7));
        ClonePlayer c = clone(h, 6.5, 6.5, 0f, false);
        Vec3 top = h.absoluteVec(new Vec3(6.5, 5, 6.5));
        c.teleportTo(h.getLevel(), top.x, top.y, top.z, 0f, 0f);
        stairsKit(h, c);
        c.getInventory().add(new ItemStack(Items.LADDER, 3));
        shaftMind(h, c, top.y);
        c.setAiEnabled(true);
        int bottom = h.absolutePos(new BlockPos(0, 2, 0)).getY();
        h.succeedWhen(() -> {
            var sm = c.controller().shafts();
            h.assertTrue(sm.resumed >= 1 && shaft.end.getY() <= bottom, "climbed down the shaft someone started and dug on from its bottom ("
                    + shaft.end.getY() + " / " + bottom + " " + sm.debug + " | " + sm.trace + " " + c.controller().optionLog + ")");
            finish(h, c);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 100, batch = "r9chat")
    public static void cloneChatCanBeHiddenByCommand(GameTestHelper h) {
        ClonePlayer a = clone(h, 3.5, 7.5, 0f, false);
        ClonePlayer b = clone(h, 6.5, 7.5, 0f, false);
        var server = h.getLevel().getServer();
        var src = server.createCommandSourceStack().withSuppressedOutput();
        int before = com.rlclones.ai.Chat.hiddenLines;
        server.getCommands().performPrefixedCommand(src, "rlclone chat off");
        boolean off = !CloneManager.chatShown();
        com.rlclones.ai.Chat.say(a, Component.literal("FOOD 1 2 3"), "FOOD 1 2 3");
        int hidden = com.rlclones.ai.Chat.hiddenLines - before;
        String heard = b.controller().foodAid().state();
        server.getCommands().performPrefixedCommand(src, "rlclone chat on");
        h.assertTrue(off, "/rlclone chat off hides clone chat");
        h.assertTrue(hidden == 1, "the line is kept out of the players' chat: " + hidden);
        h.assertTrue(heard.contains(a.getGameProfile().getName()), "but the other clones still read it: " + heard);
        h.assertTrue(CloneManager.chatShown(), "/rlclone chat on shows it again");
        finish(h, a, b);
        h.succeed();
    }

    // ------------------------------------------------------------------ AC round 10

    private static com.rlclones.ai.strategy.Option opt(String name) {
        return com.rlclones.ai.strategy.Option.valueOf(name);
    }

    private static int countBlocks(GameTestHelper h, net.minecraft.world.level.block.Block block) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(1, 0, 1), new BlockPos(13, 6, 13))) {
            if (h.getBlockState(p).is(block)) {
                n++;
            }
        }
        return n;
    }

    private static boolean hasPick(ClonePlayer c) {
        for (ItemStack s : c.getInventory().items) {
            if (s.getItem() instanceof net.minecraft.world.item.PickaxeItem) {
                return true;
            }
        }
        return false;
    }

    /** The clone has seen these blocks already (what it would notice in its first look round anyway). */
    private static void seen(GameTestHelper h, ClonePlayer c, BlockPos... rel) {
        for (BlockPos p : rel) {
            c.controller().perception().noteBlock(h.absolutePos(p));
        }
    }

    /** A small natural tree (logs with leaves that decay) at x, z. */
    private static void tree(GameTestHelper h, int x, int z) {
        for (int y = 2; y <= 4; y++) {
            h.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                h.setBlock(new BlockPos(x + dx, 5, z + dz), Blocks.OAK_LEAVES);
            }
        }
    }

    @GameTest(template = ARENA, timeoutTicks = 2400, batch = "r10tools")
    public static void makesAPickaxeBeforeMiningFetchingTheWoodFirst(GameTestHelper h) {
        clearBases(h);
        h.setBlock(new BlockPos(11, 2, 7), Blocks.COAL_ORE);
        h.setBlock(new BlockPos(11, 3, 7), Blocks.STONE);
        tree(h, 11, 10);
        h.killAllEntities();
        ClonePlayer c = clone(h, 7.5, 7.5, -90f, false); // facing the ore and the tree, carrying nothing
        c.getInventory().clearContent();
        seen(h, c, new BlockPos(11, 2, 7), new BlockPos(11, 2, 10), new BlockPos(11, 3, 10), new BlockPos(11, 4, 10));
        c.controller().forcedOption = opt("MINE");
        c.setAiEnabled(true);
        boolean[] wood = {false};
        h.onEachTick(() -> wood[0] |= c.controller().option() == opt("GATHER_WOOD"));
        h.succeedWhen(() -> {
            var cc = c.controller();
            h.assertTrue(cc.toolUps >= 1, "no pickaxe: it sets out to get one first (" + cc.toolUpDebug + " " + cc.optionLog + ")");
            h.assertTrue(wood[0], "nothing to make it from: wood first (" + cc.toolUpDebug + " " + cc.optionLog + ")");
            h.assertTrue(hasPick(c), "a pickaxe made (" + cc.toolUpDebug + " " + cc.crafting().debug() + " " + cc.optionLog + ")");
            h.assertTrue(countBlocks(h, Blocks.CRAFTING_TABLE) == 1, "at a crafting table it put down: " + countBlocks(h, Blocks.CRAFTING_TABLE));
            h.assertTrue(c.getInventory().countItem(Items.COAL) >= 1, "and then the coal mined with it (" + cc.harvestTrace + " " + cc.harvestDebug + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1600, batch = "r10tools2")
    public static void usesTheCraftingTableNearbyForThePickaxe(GameTestHelper h) {
        clearBases(h);
        h.setBlock(new BlockPos(11, 2, 7), Blocks.COAL_ORE);
        h.setBlock(new BlockPos(8, 2, 9), Blocks.CRAFTING_TABLE);
        ClonePlayer c = clone(h, 7.5, 7.5, -90f, false);
        seen(h, c, new BlockPos(11, 2, 7), new BlockPos(8, 2, 9));
        c.getInventory().add(new ItemStack(Items.OAK_LOG, 2));
        c.controller().forcedOption = opt("MINE");
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            var cc = c.controller();
            h.assertTrue(hasPick(c) && c.getInventory().countItem(Items.COAL) >= 1, "pickaxe made, coal mined (" + cc.toolUpDebug + " "
                    + cc.crafting().debug() + " " + cc.optionLog + ")");
            h.assertTrue(h.absolutePos(new BlockPos(8, 2, 9)).equals(cc.crafting().lastTable), "at the table that was already there ("
                    + cc.crafting().lastTable + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1200, batch = "r10furnace")
    public static void makesAndPutsDownAFurnaceForRawFood(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.COBBLESTONE, 9));
        c.getInventory().add(new ItemStack(Items.BEEF, 3));
        c.getInventory().add(new ItemStack(Items.OAK_LOG, 1));
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            var cc = c.controller();
            h.assertTrue(countBlocks(h, Blocks.FURNACE) >= 1, "a furnace made and put down (" + cc.urgentCrafts + " " + cc.crafting().debug() + " "
                    + cc.optionLog + ")");
            h.assertTrue(cc.urgentCrafts >= 1, "raw meat and the stone for it: crafting right away");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 700, batch = "r10safe")
    public static void correctsABlockWronglyThoughtHarmful(GameTestHelper h) {
        clearBases(h);
        for (int x = 6; x <= 8; x++) {
            for (int z = 6; z <= 8; z++) {
                h.setBlock(new BlockPos(x, 1, z), Blocks.BEDROCK);
                if (x != 7 || z != 7) {
                    h.setBlock(new BlockPos(x, 2, z), Blocks.BEDROCK);
                    h.setBlock(new BlockPos(x, 3, z), Blocks.BEDROCK);
                }
            }
        }
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        ClonePlayer friend = clone(h, 3.5, 3.5, 0f, false);
        String id = "minecraft:bedrock";
        c.getCloneBrain().learnHarmful(id);
        friend.getCloneBrain().learnHarmful(id);
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            var cc = c.controller();
            h.assertTrue(!c.getCloneBrain().isHarmful(id) && cc.hazardsCorrected >= 1, "standing and touching it a long while unhurt: not harmful after all (samples " + cc.harmlessSamples(id) + " hp " + c.getHealth() + " at " + c.blockPosition().subtract(h.absolutePos(new BlockPos(0, 0, 0))).toShortString() + " " + cc.optionLog + ")");
            h.assertTrue(c.getCloneBrain().provenSafe(id), "and not believed again on hearsay");
            h.assertTrue(!friend.getCloneBrain().isHarmful(id), "the others are told");
            finish(h, c, friend);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1200, batch = "r10ore")
    public static void quarryingTakesTheOresFirst(GameTestHelper h) {
        clearBases(h);
        for (int z = 6; z <= 8; z++) {
            h.setBlock(new BlockPos(8, 2, z), Blocks.STONE);
            h.setBlock(new BlockPos(6, 2, z), Blocks.STONE);
        }
        h.setBlock(new BlockPos(11, 2, 5), Blocks.COAL_ORE);
        h.setBlock(new BlockPos(11, 2, 10), Blocks.IRON_ORE);
        ClonePlayer c = clone(h, 7.5, 7.5, -90f, false);
        c.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        seen(h, c, new BlockPos(8, 2, 6), new BlockPos(8, 2, 7), new BlockPos(8, 2, 8), new BlockPos(11, 2, 5), new BlockPos(11, 2, 10));
        c.controller().forcedOption = opt("QUARRY");
        int[] stoneWhenOres = {-1};
        h.onEachTick(() -> {
            if (stoneWhenOres[0] < 0 && !h.getBlockState(new BlockPos(11, 2, 5)).is(Blocks.COAL_ORE) && !h.getBlockState(new BlockPos(11, 2, 10)).is(Blocks.IRON_ORE)) {
                stoneWhenOres[0] = c.controller().quarried;
            }
        });
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            var cc = c.controller();
            h.assertTrue(stoneWhenOres[0] >= 0, "both ores mined (" + cc.harvestTrace + " " + cc.optionLog + ")");
            h.assertTrue(stoneWhenOres[0] == 0 && cc.oresBeforeStone >= 2, "before any of the plain stone right beside it (stone "
                    + stoneWhenOres[0] + ", ores first " + cc.oresBeforeStone + ")");
            h.assertTrue(c.getInventory().countItem(Items.COAL) >= 1 && c.getInventory().countItem(Items.RAW_IRON) >= 1, "coal and raw iron in the bag");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 400, batch = "r10orereflex")
    public static void minesAnOreRightInFrontWhileExploring(GameTestHelper h) {
        clearBases(h);
        h.setBlock(new BlockPos(9, 2, 7), Blocks.IRON_ORE);
        ClonePlayer c = clone(h, 7.5, 7.5, -90f, false);
        c.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        seen(h, c, new BlockPos(9, 2, 7));
        c.controller().forcedOption = opt("EXPLORE");
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            var cc = c.controller();
            h.assertTrue(cc.oreReflexes >= 1 && c.getInventory().countItem(Items.RAW_IRON) >= 1, "the ore within reach is taken on the spot ("
                    + cc.harvestTrace + " " + cc.optionLog + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 200, batch = "r10orepull")
    public static void anOreInSightIsTheFirstThingToDo(GameTestHelper h) {
        clearBases(h);
        h.setBlock(new BlockPos(11, 2, 7), Blocks.COAL_ORE);
        ClonePlayer c = clone(h, 6.5, 7.5, -90f, false);
        c.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        seen(h, c, new BlockPos(11, 2, 7));
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            var cc = c.controller();
            h.assertTrue(!cc.optionLog.isEmpty(), "a first decision");
            h.assertTrue(cc.optionLog.get(0).startsWith("MINE") && cc.orePulls >= 1, "coal in sight: mining comes first (" + cc.optionLog + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 2400, batch = "r10soil")
    public static void makesSoilBesideBareWaterAndLightsThePlot(GameTestHelper h) {
        clearBases(h);
        // a closed dark room with a pool in the middle and nothing but stone round it
        for (int x = 3; x <= 11; x++) {
            for (int z = 3; z <= 11; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                boolean wall = x == 3 || x == 11 || z == 3 || z == 11;
                boolean pool = x >= 6 && x <= 8 && z >= 6 && z <= 8;
                h.setBlock(new BlockPos(x, 1, z), pool ? Blocks.WATER : Blocks.STONE);
                for (int y = 2; y <= 4; y++) {
                    h.setBlock(new BlockPos(x, y, z), wall ? Blocks.STONE : Blocks.AIR);
                }
                h.setBlock(new BlockPos(x, 5, z), Blocks.STONE);
            }
        }
        ClonePlayer c = clone(h, 4.5, 7.5, -90f, false);
        c.getInventory().add(new ItemStack(Items.WOODEN_HOE));
        c.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        c.getInventory().add(new ItemStack(Items.WHEAT_SEEDS, 8));
        c.getInventory().add(new ItemStack(Items.DIRT, 6));
        c.getInventory().add(new ItemStack(Items.COAL, 1));
        c.getInventory().add(new ItemStack(Items.STICK, 2));
        c.controller().forcedOption = opt("FARM");
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            var f = c.controller().farming();
            h.assertTrue(f.torchesPlaced >= 1, "too dark for crops: a torch made and put up (" + f.debug() + " " + c.controller().optionLog + ")");
            h.assertTrue(f.soilMade >= 1, "a bank block beside the water swapped for soil (" + f.debug() + ")");
            h.assertTrue(f.planted >= 1 && countBlocks(h, Blocks.WHEAT) >= 1, "tilled and sown (" + f.debug() + ")");
            finish(h, c);
        });
    }

    /** A pool three deep from x 2 to 12, z 5 to 9 (stone round and under it). */
    private static void deepPool(GameTestHelper h, int x0, int x1) {
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            for (int z = 4; z <= 10; z++) {
                for (int y = -2; y <= 1; y++) {
                    boolean wall = x == x0 - 1 || x == x1 + 1 || z == 4 || z == 10 || y == -2;
                    h.setBlock(new BlockPos(x, y, z), wall ? Blocks.STONE : Blocks.WATER);
                }
            }
        }
    }

    @GameTest(template = ARENA, timeoutTicks = 300, batch = "r10swim")
    public static void swimsAcrossInsteadOfBobbing(GameTestHelper h) {
        deepPool(h, 2, 12);
        ClonePlayer c = clone(h, 2.5, 7.5, -90f, false);
        Vec3 start = h.absoluteVec(new Vec3(2.5, 1.2, 7.5));
        c.teleportTo(h.getLevel(), start.x, start.y, start.z, -90f, 0f);
        Vec3 goal = h.absoluteVec(new Vec3(12.5, 0, 7.5));
        int[] reached = {-1};
        int[] t = {0};
        h.onEachTick(() -> {
            t[0]++;
            c.controller().motor().navigate(goal, 0.8, true);
            c.controller().motor().tick();
            if (reached[0] < 0 && c.getX() >= goal.x - 1.5) {
                reached[0] = t[0];
            }
        });
        h.succeedWhen(() -> {
            var m = c.controller().motor();
            String diag = String.format(Locale.ROOT, "x %.1f y %.2f swimming=%s pose=%s v=%s strokes %d pose %d stalls %d air %d", c.getX() - start.x,
                    c.getY() - start.y, c.isSwimming(), c.getPose(), c.getDeltaMovement(), m.swimStrokes, m.swimPoseTicks, m.swimStalls, c.getAirSupply());
            h.assertTrue(reached[0] > 0, "across the pool (" + diag + ")");
            h.assertTrue(m.swimStrokes >= 20 && m.swimPoseTicks >= 5 && m.swimStalls == 0, "swimming, not bobbing (" + diag + ")");
            finish(h, c);
        });
    }

    /** Low on air, water up to the ceiling above: swim along to the air pocket instead of pushing up against the roof. */
    @GameTest(template = ARENA, timeoutTicks = 400, batch = "r10swim")
    public static void swimsToAnAirPocketUnderACeiling(GameTestHelper h) {
        for (int x = 1; x <= 13; x++) {
            for (int z = 4; z <= 10; z++) {
                for (int y = -2; y <= 3; y++) {
                    boolean wall = x == 1 || x == 13 || z == 4 || z == 10 || y == -2 || y == 3 || (y == 2 && x <= 9);
                    h.setBlock(new BlockPos(x, y, z), wall ? Blocks.STONE : y == 2 ? Blocks.AIR : Blocks.WATER);
                }
            }
        }
        ClonePlayer c = clone(h, 3.5, 7.5, -90f, false);
        Vec3 start = h.absoluteVec(new Vec3(3.5, 0.5, 7.5));
        c.teleportTo(h.getLevel(), start.x, start.y, start.z, -90f, 0f);
        c.setAirSupply(60);
        h.onEachTick(() -> c.controller().motor().tick());
        h.succeedWhen(() -> {
            h.assertTrue(c.isAlive() && c.getAirSupply() >= c.getMaxAirSupply() * 0.9, "breathing in the pocket (air " + c.getAirSupply() + " x " + (c.getX() - start.x)
                    + " steps " + c.controller().motor().breathStepsTaken() + ")");
            finish(h, c);
        });
    }

    /** No pickaxe (and none to be made): a digging option ends at once instead of breaking stone by hand. */
    @GameTest(template = ARENA, timeoutTicks = 200, batch = "r10swim")
    public static void doesNotDigWithoutAPickaxe(GameTestHelper h) {
        for (int x = 9; x <= 11; x++) {
            for (int z = 7; z <= 9; z++) {
                h.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
            }
        }
        ClonePlayer c = clone(h, 7.5, 8.5, -90f, false);
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.QUARRY;
        c.setAiEnabled(true);
        h.runAfterDelay(150, () -> {
            int stone = 0;
            for (int x = 9; x <= 11; x++) {
                for (int z = 7; z <= 9; z++) {
                    stone += h.getBlockState(new BlockPos(x, 2, z)).is(Blocks.STONE) ? 1 : 0;
                }
            }
            h.assertTrue(stone == 9, "no stone broken by hand, " + stone + " left of 9");
            finish(h, c);
            h.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 900, batch = "r10fish")
    public static void huntsFishInTheWater(GameTestHelper h) {
        deepPool(h, 4, 10);
        var cod = h.spawn(EntityType.COD, new Vec3(9.5, 0, 7.5));
        cod.setPersistenceRequired();
        ClonePlayer c = clone(h, 3.5, 7.5, -90f, false);
        Vec3 shore = h.absoluteVec(new Vec3(3.5, 2, 7.5));
        c.teleportTo(h.getLevel(), shore.x, shore.y, shore.z, -90f, 20f);
        c.getInventory().add(new ItemStack(Items.STONE_SWORD));
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            var cc = c.controller();
            h.assertTrue(cc.fishHunts >= 1, "fish in sight and little food: off hunting it (" + cc.optionLog + ")");
            h.assertTrue(!cod.isAlive(), "and caught (" + cc.optionLog + " strokes " + cc.motor().swimStrokes + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1600, batch = "r10ask")
    public static void asksForAPickaxeAndAFriendBringsOne(GameTestHelper h) {
        clearBases(h);
        h.setBlock(new BlockPos(11, 2, 7), Blocks.COAL_ORE);
        ClonePlayer a = clone(h, 7.5, 7.5, -90f, false);
        ClonePlayer b = clone(h, 3.5, 3.5, 0f, false);
        b.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        b.getInventory().add(new ItemStack(Items.WOODEN_PICKAXE));
        seen(h, a, new BlockPos(11, 2, 7));
        a.controller().forcedOption = opt("MINE");
        a.setAiEnabled(true);
        b.setAiEnabled(true);
        h.succeedWhen(() -> {
            var ia = a.controller().itemAid();
            var ib = b.controller().itemAid();
            h.assertTrue(ia.asked >= 1, "no pickaxe and nothing to make one from: it asks (" + a.controller().toolUpDebug + " " + ia.state() + ")");
            h.assertTrue(ib.given >= 1, "the friend with a spare one brings it (" + ib.state() + " " + b.controller().optionLog + ")");
            h.assertTrue(hasPick(a) && hasPick(b), "now both have one (a " + hasPick(a) + " b " + hasPick(b) + " coal " + a.getInventory().countItem(Items.COAL)
                    + " " + a.controller().optionLog + " | " + b.controller().optionLog + ")");
            finish(h, a, b);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 2400, batch = "r10duel")
    public static void creativeFightsInDifferentWays(GameTestHelper h) {
        clearBases(h);
        ClonePlayer cr = creative(h, 3.5, 7.5, -90f);
        net.minecraft.world.entity.LivingEntity[] foe = {slowHusk(h, 9.5, 2, 7.5)};
        int[] killed = {0};
        h.onEachTick(() -> {
            if (!foe[0].isAlive() && killed[0] < 3) {
                killed[0]++;
                if (killed[0] < 3) {
                    foe[0] = slowHusk(h, 9.5, 2, 4.5 + killed[0] * 3);
                }
            }
        });
        h.succeedWhen(() -> {
            var play = cr.controller().creative().play();
            long kinds = play.duelLog.stream().distinct().count();
            h.assertTrue(killed[0] >= 2, "monsters dealt with: " + killed[0] + " (" + play.log + ")");
            h.assertTrue(kinds >= 2, "not always the rod: " + play.duelLog + " (" + play.log + ")");
            finish(h, cr);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 400, batch = "r10perf")
    public static void manyClonesThinkWithinBudget(GameTestHelper h) {
        clearBases(h);
        List<ClonePlayer> cs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            cs.add(clone(h, 2.5 + (i % 4) * 3, 2.5 + (i / 4) * 4, i * 30f, true));
        }
        com.rlclones.ai.Prof.reset();
        com.rlclones.ai.Prof.on = true;
        h.runAfterDelay(300, () -> {
            com.rlclones.ai.Prof.on = false;
            long perTick = com.rlclones.ai.Prof.NANOS[com.rlclones.ai.Prof.TOTAL] / Math.max(1, com.rlclones.ai.Prof.cloneTicks);
            String rep = com.rlclones.ai.Prof.report();
            RLClones.LOGGER.info("PERF " + rep);
            h.assertTrue(com.rlclones.ai.Prof.cloneTicks >= 12 * 250, "every clone thought every tick: " + com.rlclones.ai.Prof.cloneTicks);
            h.assertTrue(perTick < 2_000_000, "a clone's thinking stays well under 2 ms a tick on average (" + rep + ")");
            finish(h, cs.toArray(new ClonePlayer[0]));
            h.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 400, batch = "r10busy")
    public static void neverStandsIdleWhileFriendsFight(GameTestHelper h) {
        clearBases(h);
        var husk = slowHusk(h, 10.5, 2, 7.5);
        husk.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4)); // the fight goes on for the whole test
        ClonePlayer ally = clone(h, 7.5, 7.5, -90f, false);
        ally.getInventory().add(new ItemStack(Items.IRON_SWORD));
        ally.controller().forcedOption = opt("FIGHT");
        h.onEachTick(() -> {
            // a policy that would only look on: the clone itself must break that off
            for (var en : ally.getCloneBrain().combatTable("minecraft:husk").entries()) {
                en.getValue().q[CombatAction.HOLD.ordinal()] = 1000f;
            }
        });
        ClonePlayer c = clone(h, 4.5, 7.5, -90f, false);
        c.controller().forcedOption = opt("REST");
        ally.setAiEnabled(true);
        c.setAiEnabled(true);
        h.runAfterDelay(200, () -> {
            var cc = c.controller();
            long now = h.getLevel().getGameTime();
            int mask = com.rlclones.ai.Senses.strategyMask(cc.perception(), c, c, now);
            h.assertTrue((mask & opt("REST").bit()) == 0 && (mask & opt("FOLLOW").bit()) == 0 && (mask & opt("FLEE").bit()) != 0,
                    "an enemy about: resting / tagging along are off the table");
            h.assertTrue(cc.optionLog.stream().noneMatch(o -> o.startsWith("REST") || o.startsWith("FOLLOW")) && !cc.optionLog.isEmpty(),
                    "it fights, flees or works - never just stands there: " + cc.optionLog);
            h.assertTrue(ally.controller().holdBreaks >= 1, "and the one fighting does not just look on for long either");
            finish(h, ally, c);
            h.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "r10buried")
    public static void digsItselfOutWhenBuriedByGravel(GameTestHelper h) {
        clearBases(h);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    h.setBlock(new BlockPos(7 + dx, 2, 7 + dz), Blocks.STONE);
                    h.setBlock(new BlockPos(7 + dx, 3, 7 + dz), Blocks.STONE);
                }
            }
        }
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, true);
        boolean[] buried = {false};
        h.runAfterDelay(20, () -> {
            for (int y = 4; y <= 7; y++) {
                h.setBlock(new BlockPos(7, y, 7), Blocks.GRAVEL);
            }
        });
        h.onEachTick(() -> buried[0] |= c.isInWall());
        h.succeedWhen(() -> {
            var cc = c.controller();
            h.assertTrue(buried[0], "the gravel came down on it");
            h.assertTrue(c.isAlive() && !c.isInWall() && cc.digOuts >= 1, "and it got itself out (" + cc.digOuts + ", in wall " + c.isInWall() + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 800, batch = "r10vantage")
    public static void climbsUpToShootAnEnemyHiddenBehindAWall(GameTestHelper h) {
        clearBases(h);
        var husk = slowHusk(h, 11.5, 2, 7.5);
        // steps up to a ledge two high, behind the clone
        h.setBlock(new BlockPos(3, 2, 9), Blocks.STONE);
        h.setBlock(new BlockPos(3, 2, 10), Blocks.STONE);
        h.setBlock(new BlockPos(3, 3, 10), Blocks.STONE);
        ClonePlayer c = clone(h, 3.5, 7.5, -90f, false);
        c.getInventory().add(new ItemStack(Items.BOW));
        c.getInventory().add(new ItemStack(Items.ARROW, 32));
        c.controller().forcedOption = opt("FIGHT");
        c.controller().forcedAction = CombatAction.SHOOT;
        c.setAiEnabled(true);
        float[] start = {husk.getHealth()};
        h.runAfterDelay(30, () -> {
            for (int z = 4; z <= 11; z++) {
                h.setBlock(new BlockPos(7, 2, z), Blocks.STONE); // a wall goes up between them
                h.setBlock(new BlockPos(7, 3, z), Blocks.STONE);
            }
            start[0] = husk.getHealth();
        });
        h.succeedWhen(() -> {
            var cc = c.controller();
            h.assertTrue(cc.vantageClimbs >= 1, "out of sight behind the wall: up the steps for a look (" + cc.vantageDebug + " " + cc.optionLog + ")");
            h.assertTrue(husk.getHealth() < start[0] || !husk.isAlive(), "and shot from up there (" + husk.getHealth() + " / " + start[0] + " arrows "
                    + cc.arrowsLoosed + " " + cc.vantageDebug + " | " + cc.shootDebug + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 400, batch = "r10coward")
    public static void cowardModeOnlyRunsAndHides(GameTestHelper h) {
        clearBases(h);
        var server = h.getLevel().getServer();
        var src = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(src, "rlclone coward on");
        boolean on = CloneManager.coward();
        var husk = slowHusk(h, 10.5, 2, 7.5);
        ClonePlayer c = clone(h, 5.5, 7.5, -90f, false);
        c.getInventory().add(new ItemStack(Items.IRON_SWORD));
        c.setAiEnabled(true);
        h.runAfterDelay(150, () -> {
            var cc = c.controller();
            long now = h.getLevel().getGameTime();
            int mask = com.rlclones.ai.Senses.strategyMask(cc.perception(), c, c, now);
            List<String> log = new ArrayList<>(cc.optionLog);
            server.getCommands().performPrefixedCommand(src, "rlclone coward off");
            int maskOff = com.rlclones.ai.Senses.strategyMask(cc.perception(), c, c, now);
            var r = new com.rlclones.clone.CloneRoster();
            r.coward = true;
            boolean saved = com.rlclones.clone.CloneRoster.load(r.save(new net.minecraft.nbt.CompoundTag())).coward;
            h.assertTrue(on, "/rlclone coward on");
            h.assertTrue((mask & opt("FIGHT").bit()) == 0 && (mask & opt("FLEE").bit()) != 0, "coward: fleeing, never fighting");
            h.assertTrue(log.stream().noneMatch(o -> o.startsWith("FIGHT")) && log.stream().anyMatch(o -> o.startsWith("FLEE")), "it ran: " + log);
            h.assertTrue(!CloneManager.coward() && (maskOff & opt("FIGHT").bit()) != 0, "/rlclone coward off: fighting is back");
            h.assertTrue(saved, "the setting is saved with the world");
            finish(h, c);
            h.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 1200, batch = "r10bucket")
    public static void makesItsFirstBucketButOnlyOne(GameTestHelper h) {
        clearBases(h);
        h.setBlock(new BlockPos(8, 2, 9), Blocks.CRAFTING_TABLE);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        seen(h, c, new BlockPos(8, 2, 9));
        c.getInventory().add(new ItemStack(Items.IRON_INGOT, 6));
        c.setAiEnabled(true);
        int[] madeAt = {-1};
        int[] t = {0};
        h.onEachTick(() -> {
            t[0]++;
            if (madeAt[0] < 0 && c.getInventory().countItem(Items.BUCKET) + c.getInventory().countItem(Items.WATER_BUCKET) > 0) {
                madeAt[0] = t[0];
            }
        });
        h.runAfterDelay(1100, () -> {
            var inv = c.getInventory();
            int buckets = inv.countItem(Items.BUCKET) + inv.countItem(Items.WATER_BUCKET);
            h.assertTrue(madeAt[0] > 0 && c.controller().urgentCrafts >= 1, "a bucket made right away (made at " + madeAt[0] + " " + c.controller().optionLog + " "
                    + c.controller().crafting().debug() + ")");
            h.assertTrue(c.getCloneBrain().hasFlag("had_bucket"), "remembered: it has had a bucket");
            h.assertTrue(buckets == 1, "but only the one: " + buckets + " (iron left " + inv.countItem(Items.IRON_INGOT) + ")");
            finish(h, c);
            h.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 3600, batch = "r10spring")
    public static void bringsWaterHomeAndMakesASpring(GameTestHelper h) {
        clearBases(h);
        for (int x = 1; x <= 13; x++) {
            for (int z = 1; z <= 13; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        for (int x = 11; x <= 13; x++) {
            for (int z = 11; z <= 13; z++) {
                h.setBlock(new BlockPos(x, 1, z), Blocks.WATER); // a lake that never runs dry
            }
        }
        baseAt(h, new BlockPos(2, 2, 2));
        ClonePlayer c = clone(h, 6.5, 6.5, 45f, false);
        c.getInventory().add(new ItemStack(Items.BUCKET));
        c.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            var w = c.controller().water();
            int sources = 0;
            for (BlockPos p : BlockPos.betweenClosed(new BlockPos(1, 1, 1), new BlockPos(10, 1, 10))) {
                if (h.getLevel().getFluidState(h.absolutePos(p)).isSource()) {
                    sources++;
                }
            }
            h.assertTrue(w.springsMade >= 1 && sources >= 4, "an endless spring of 4 sources a few blocks from the base (" + sources + " " + w.debug + " | "
                    + w.trace + " | " + c.controller().optionLog + ")");
            h.assertTrue(w.bucketsFilled >= 2, "filled twice at the lake: " + w.bucketsFilled);
            finish(h, c);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "r10effects")
    public static void learnsWhatTheEffectsOnItDo(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, true);
        c.getInventory().add(new ItemStack(Items.MILK_BUCKET));
        c.setHealth(8f);
        c.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 400, 1));
        String regen = "minecraft:regeneration";
        String unluck = "minecraft:unluck";
        float[] regenValue = {Float.NaN};
        h.runAfterDelay(120, () -> {
            regenValue[0] = c.getCloneBrain().effectValue(regen);
            c.removeEffect(MobEffects.REGENERATION);
            c.addEffect(new MobEffectInstance(MobEffects.UNLUCK, 2400, 0));
        });
        h.succeedWhen(() -> {
            var b = c.getCloneBrain();
            h.assertTrue(!Float.isNaN(regenValue[0]) && regenValue[0] > 0, "regeneration learned as good: " + regenValue[0]);
            h.assertTrue(b.knowsEffect(unluck) && b.effectValue(unluck) < 0, "bad luck learned as bad: " + b.effectValue(unluck));
            h.assertTrue(c.controller().consumables().milkDrunk >= 1 && !c.hasEffect(MobEffects.UNLUCK), "so it drinks the milk to be rid of it");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "r10cloud")
    public static void learnsALingeringCloudIsBadAndKeepsOut(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, true);
        c.controller().forcedOption = opt("REST"); // standing about, not walking off by chance
        h.runAfterDelay(70, () -> c.teleportTo(h.getLevel(), h.absoluteVec(new Vec3(7.5, 2, 7.5)).x, h.absoluteVec(new Vec3(7.5, 2, 7.5)).y,
                h.absoluteVec(new Vec3(7.5, 2, 7.5)).z, 0f, 0f)); // (spawn protection over: back into the middle)
        Vec3 at = h.absoluteVec(new Vec3(7.5, 2, 7.5));
        var cloud = new net.minecraft.world.entity.AreaEffectCloud(h.getLevel(), at.x, at.y, at.z);
        cloud.setRadius(3.5f);
        cloud.setDuration(1200);
        cloud.setWaitTime(0);
        cloud.setRadiusPerTick(0);
        cloud.setPotion(net.minecraft.world.item.alchemy.Potions.STRONG_POISON);
        h.runAfterDelay(70, () -> h.getLevel().addFreshEntity(cloud));
        String key = com.rlclones.ai.EffectSense.cloudKey(cloud);
        long[] outSince = {-1};
        h.onEachTick(() -> {
            if (!cloud.isAddedToWorld()) {
                return;
            }
            boolean out = Math.hypot(c.getX() - at.x, c.getZ() - at.z) > cloud.getRadius() + 0.3;
            if (!out) {
                outSince[0] = -1;
            } else if (outSince[0] < 0) {
                outSince[0] = h.getLevel().getGameTime();
            }
        });
        h.succeedWhen(() -> {
            var b = c.getCloneBrain();
            var e = c.controller().effects();
            h.assertTrue(b.knowsEffect(key) && b.effectValue(key) < 0, "the cloud learned as bad: " + b.effectValue(key) + " " + e.debug);
            h.assertTrue(e.cloudsLeft >= 1, "it walked out of it");
            h.assertTrue(outSince[0] > 0 && h.getLevel().getGameTime() - outSince[0] > 60, "and stays out");
            h.assertTrue(cloud.isAlive(), "(while the cloud is still there)");
            finish(h, c);
            cloud.discard();
        });
    }

    // ------------------------------------------------------------------ progression towards full diamond gear (plan.md)

    @GameTest(template = ARENA, timeoutTicks = 40, batch = "p1progress")
    public static void knowsWhatToProgressTo(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        var inv = c.getInventory();
        inv.clearContent();
        h.assertTrue(Progression.tier(c) == 0 && Progression.need(c) == Progression.Need.WOOD
                && !Progression.digWanted(c), "bare hands: wood first (" + Progression.describe(c) + ")");
        inv.add(new ItemStack(Items.WOODEN_PICKAXE));
        h.assertTrue(Progression.tier(c) == 1 && Progression.need(c) == Progression.Need.STONE
                && !Progression.digWanted(c), "wooden pickaxe: stone next (" + Progression.describe(c) + ")");
        inv.add(new ItemStack(Items.STONE_PICKAXE));
        h.assertTrue(Progression.tier(c) == 2 && Progression.need(c) == Progression.Need.IRON
                && Progression.ironNeed(c) == 29 && Progression.ironShort(c) == 29 && Progression.digWanted(c)
                && Progression.digTargetY(Progression.need(c)) == 16, "stone pickaxe: iron next, 29 short (" + Progression.describe(c) + ")");
        inv.add(new ItemStack(Items.IRON_INGOT, 9));
        inv.add(new ItemStack(Items.RAW_IRON, 1));
        h.assertTrue(Progression.ironShort(c) == 19, "ingots and raw iron count off the iron still to find (" + Progression.describe(c) + ")");
        inv.clearContent();
        inv.add(new ItemStack(Items.GOLDEN_PICKAXE));
        c.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.GOLDEN_BOOTS));
        h.assertTrue(Progression.need(c) == Progression.Need.STONE && Progression.ironNeed(c) == 29,
                "gold counts for nothing (" + Progression.describe(c) + ")");
        inv.clearContent();
        inv.add(new ItemStack(Items.IRON_PICKAXE));
        inv.add(new ItemStack(Items.IRON_SWORD));
        c.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        c.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        c.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        inv.add(new ItemStack(Items.IRON_BOOTS)); // in the bag counts too (it will be put on)
        h.assertTrue(Progression.tier(c) == 4 && Progression.ironShort(c) == 0
                && Progression.need(c) == Progression.Need.DIAMOND && Progression.diamondNeed(c) == 29
                && Progression.digWanted(c) && Progression.digTargetY(Progression.need(c)) == -58,
                "iron gear: diamonds next, dig down to -58 (" + Progression.describe(c) + ")");
        inv.add(new ItemStack(Items.DIAMOND, 10));
        h.assertTrue(Progression.diamondShort(c) == 19, "diamonds in the bag count off (" + Progression.describe(c) + ")");
        inv.clearContent();
        for (var it : List.of(Items.DIAMOND_PICKAXE, Items.DIAMOND_SWORD, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS)) {
            inv.add(new ItemStack(it));
        }
        h.assertTrue(Progression.tier(c) == 6 && Progression.need(c) == Progression.Need.NONE
                && !Progression.digWanted(c), "all diamond: nothing left to dig for (" + Progression.describe(c) + ")");
        inv.clearContent();
        finish(h, c);
        h.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 40, batch = "p1progress")
    public static void oldBrainsStillLoad(GameTestHelper h) {
        h.assertTrue(com.rlclones.ai.strategy.StrategyState.encode(0, 0, 0, false, false, false, false, false, false, false) == 0, "the lowest state is key 0");
        h.assertTrue(com.rlclones.ai.strategy.StrategyState.encode(2, 2, 2, true, true, true, true, true, true, true) == 3455, "the highest state is key 3455 (3*3*3*2^7 states)");
        h.assertTrue(com.rlclones.ai.strategy.StrategyState.encode(1, 2, 0, true, false, true, false, true, false, true) == ((((((((1 * 3 + 2) * 3 + 0) * 2 + 1) * 2 + 0) * 2 + 1) * 2 + 0) * 2 + 1) * 2 + 0) * 2 + 1,
                "keys are laid out as they always were (a saved Q table stays valid)");
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 200; i++) {
            int[] v = {r.nextInt(3), r.nextInt(3), r.nextInt(3), r.nextInt(2), r.nextInt(2), r.nextInt(2), r.nextInt(2), r.nextInt(2), r.nextInt(2), r.nextInt(2)};
            int key = com.rlclones.ai.strategy.StrategyState.encode(v[0], v[1], v[2], v[3] == 1, v[4] == 1, v[5] == 1, v[6] == 1, v[7] == 1, v[8] == 1, v[9] == 1);
            h.assertTrue(java.util.Arrays.equals(v, com.rlclones.ai.strategy.StrategyState.decode(key)), "decode(encode(x)) == x for " + java.util.Arrays.toString(v));
        }
        h.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 900, batch = "p1stonepick")
    public static void craftsAStonePickaxeWhenCobbleAndWoodAreInTheBag(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.WOODEN_PICKAXE));
        c.getInventory().add(new ItemStack(Items.COBBLESTONE, 6));
        c.getInventory().add(new ItemStack(Items.OAK_PLANKS, 4));
        c.getInventory().add(new ItemStack(Items.OAK_LOG, 3));
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            var cc = c.controller();
            h.assertTrue(c.getInventory().countItem(Items.STONE_PICKAXE) >= 1, "cobble, planks and logs in the bag: a stone pickaxe is made (" + cc.crafting().trace() + " "
                    + cc.optionLog + " idle=" + cc.idleWhy + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 700, batch = "p2tunnel")
    public static void craftsInATunnelWithNoFlatGroundBesideIt(GameTestHelper h) {
        clearBases(h);
        // a one-wide cell: stone to the north, south and west and overhead; open to the east, where the floor is missing too
        for (int y = 2; y <= 3; y++) {
            h.setBlock(new BlockPos(6, y, 7), Blocks.STONE);
            for (int x = 7; x <= 8; x++) {
                h.setBlock(new BlockPos(x, y, 6), Blocks.STONE);
                h.setBlock(new BlockPos(x, y, 8), Blocks.STONE);
            }
        }
        h.setBlock(new BlockPos(7, 4, 7), Blocks.STONE);
        h.setBlock(new BlockPos(8, 4, 7), Blocks.STONE);
        h.setBlock(new BlockPos(8, 1, 7), Blocks.AIR);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.WOODEN_PICKAXE));
        c.getInventory().add(new ItemStack(Items.COBBLESTONE, 6));
        c.getInventory().add(new ItemStack(Items.STICK, 2));
        c.getInventory().add(new ItemStack(Items.CRAFTING_TABLE));
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            var cc = c.controller();
            h.assertTrue(c.getInventory().countItem(Items.STONE_PICKAXE) >= 1, "a table put down against the wall and a stone pickaxe made (" + cc.crafting().trace()
                    + " " + cc.optionLog + ")");
            finish(h, c);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 3000, batch = "p2dig")
    public static void digsForIronEvenWhenOtherWorkIsAround(GameTestHelper h) {
        clearBases(h);
        stoneMass(h);
        ClonePlayer friend = clone(h, 2.5, 3.5, 0f, false); // somebody to go on a trip with: a trip is always possible
        ClonePlayer c = clone(h, 4.5, 7.5, -90f, false);
        c.controller().expedition().readyToLead();
        Vec3 top = h.absoluteVec(new Vec3(4.5, 5, 7.5));
        c.teleportTo(h.getLevel(), top.x, top.y, top.z, -90f, 0f);
        stairsKit(h, c);
        for (String b : List.of("minecraft:stone", "minecraft:deepslate", "minecraft:cobblestone", "minecraft:tuff", "minecraft:bedrock", "minecraft:furnace")) {
            c.getCloneBrain().learnBlock(b);
        }
        c.controller().stairs().assumeSurface = true;
        c.controller().stairs().targetY = (int) top.y - 40;
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            var cc = c.controller();
            h.assertTrue(cc.digDrives >= 1 && cc.optionLog.stream().anyMatch(o -> o.startsWith("STAIRS")), "iron is what is missing: digging is pulled in ("
                    + cc.digDrives + " " + cc.optionLog + ")");
            h.assertTrue(cc.optionLog.stream().noneMatch(o -> o.startsWith("EXPEDITION") || o.startsWith("JOIN")), "and no trip (" + cc.optionLog + ")");
            h.assertTrue(cc.stairs().stepsDug >= 3, "dug down step by step: " + cc.stairs().stepsDug + " " + cc.stairs().debug);
            finish(h, c, friend);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 40, batch = "p2diamond")
    public static void keepsDiggingForDiamondsWhenIronGeared(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        var inv = c.getInventory();
        inv.clearContent();
        inv.add(new ItemStack(Items.STONE_PICKAXE));
        var st = c.controller().stairs();
        var sh = c.controller().shafts();
        h.assertTrue(st.target() == 16 && sh.target() == 16, "iron is the aim at first: dig to 16 (" + st.target() + " " + sh.target() + ")");
        inv.clearContent();
        inv.add(new ItemStack(Items.IRON_PICKAXE));
        inv.add(new ItemStack(Items.IRON_SWORD));
        inv.add(new ItemStack(Items.IRON_HELMET));
        inv.add(new ItemStack(Items.IRON_CHESTPLATE));
        inv.add(new ItemStack(Items.IRON_LEGGINGS));
        inv.add(new ItemStack(Items.IRON_BOOTS));
        st.assumeSurface = true;
        h.assertTrue(com.rlclones.ai.StairMining.ironGeared(c) && st.target() == -58 && sh.target() == -58, "iron gear: diamonds are the aim, dig to -58 ("
                + st.target() + " " + sh.target() + ")");
        st.targetY = c.getBlockY() - 5; // (the arena lies below the real depth)
        h.assertTrue(st.wanted(), "iron gear and no diamonds: still digging down");
        inv.clearContent();
        for (var it : List.of(Items.DIAMOND_PICKAXE, Items.DIAMOND_SWORD, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS)) {
            inv.add(new ItemStack(it));
        }
        h.assertTrue(!st.wanted(), "all of it diamond: nothing left to dig for");
        inv.clearContent();
        finish(h, c);
        h.succeed();
    }

    private static int strategyMask(GameTestHelper h, ClonePlayer c) {
        return com.rlclones.ai.Senses.strategyMask(c.controller().perception(), c, c, h.getLevel().getGameTime());
    }

    @GameTest(template = ARENA, timeoutTicks = 80, batch = "p2home")
    public static void staysHomeToDigAndWhenHungry(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        ClonePlayer friend = clone(h, 3.5, 3.5, 0f, false); // somebody to go on a trip with
        c.controller().expedition().readyToLead();
        int trip = opt("EXPEDITION").bit();
        int[] mask = new int[4];
        h.runAfterDelay(5, () -> mask[0] = strategyMask(h, c)); // bare hands, fed: a trip is possible
        h.runAfterDelay(10, () -> {
            c.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
            c.controller().stairs().targetY = c.getBlockY() - 40;
        });
        h.runAfterDelay(15, () -> mask[1] = strategyMask(h, c)); // iron to dig for: no trip
        h.runAfterDelay(20, () -> {
            c.getInventory().clearContent();
            c.controller().stairs().targetY = Integer.MIN_VALUE;
            c.getFoodData().setFoodLevel(4);
        });
        h.runAfterDelay(25, () -> mask[2] = strategyMask(h, c)); // hungry with nothing to eat: no trip
        h.runAfterDelay(30, () -> c.getFoodData().setFoodLevel(20));
        h.runAfterDelay(35, () -> {
            mask[3] = strategyMask(h, c);
            h.assertTrue((mask[0] & trip) != 0, "bare hands and fed: a trip is on offer");
            h.assertTrue((mask[1] & trip) == 0, "a stone pickaxe and iron still to find: stays to dig");
            h.assertTrue((mask[2] & trip) == 0, "hungry and nothing to eat: stays");
            h.assertTrue((mask[3] & trip) != 0, "fed again: a trip is on offer again");
            finish(h, c, friend);
            h.succeed();
        });
    }

    private static void learnStoneBlocks(ClonePlayer c) {
        for (String b : List.of("minecraft:stone", "minecraft:deepslate", "minecraft:cobblestone", "minecraft:tuff", "minecraft:bedrock", "minecraft:furnace")) {
            c.getCloneBrain().learnBlock(b);
        }
    }

    @GameTest(template = ARENA, timeoutTicks = 3000, batch = "p25line")
    public static void staircaseGrowsOnlyAlongItsLine(GameTestHelper h) {
        clearBases(h);
        stoneMass(h);
        ClonePlayer c = clone(h, 4.5, 7.5, -90f, false);
        Vec3 top = h.absoluteVec(new Vec3(4.5, 5, 7.5));
        c.teleportTo(h.getLevel(), top.x, top.y, top.z, -90f, 0f);
        stairsKit(h, c);
        learnStoneBlocks(c);
        c.controller().stairs().assumeSurface = true;
        c.controller().stairs().targetY = (int) top.y - 40;
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            var bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
            h.assertTrue(c.controller().stairs().stepsDug >= 3 && !bases.staircases.isEmpty(), "a staircase dug (" + c.controller().stairs().stepsDug + " "
                    + c.controller().stairs().debug + ")");
            for (var st : bases.staircases) {
                int n = st.steps();
                h.assertTrue(st.end.equals(st.top.relative(st.dir, n).below(n)), "the end lies on the line from the top: top " + st.top + " " + st.dir + " end " + st.end);
            }
            finish(h, c);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 3000, batch = "p25broken")
    public static void startsANewStaircaseWhenTheOldOneIsBroken(GameTestHelper h) {
        clearBases(h);
        stoneMass(h);
        for (int x = 3; x <= 11; x++) {
            for (int z = 6; z <= 8; z++) {
                for (int y = 5; y <= 6; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        // a pit in the stone, and a staircase on record that was never dug (or was filled in): it cannot be followed from down here
        h.setBlock(new BlockPos(8, 3, 7), Blocks.AIR);
        h.setBlock(new BlockPos(8, 4, 7), Blocks.AIR);
        var bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
        var old = bases.addStaircase(h.getLevel().dimension(), h.absolutePos(new BlockPos(4, 7, 7)), Direction.EAST);
        old.end = h.absolutePos(new BlockPos(5, 6, 7));
        ClonePlayer c = clone(h, 8.5, 7.5, 90f, false);
        Vec3 pit = h.absoluteVec(new Vec3(8.5, 3, 7.5));
        c.teleportTo(h.getLevel(), pit.x, pit.y, pit.z, 90f, 0f);
        stairsKit(h, c);
        learnStoneBlocks(c);
        c.controller().stairs().assumeSurface = true;
        c.controller().stairs().targetY = (int) pit.y - 40;
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.STAIRS;
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            var st = c.controller().stairs();
            h.assertTrue(old.failures >= 1 || old.finished, "the old staircase is given up (" + st.debug + " | " + st.trace + ")");
            h.assertTrue(bases.staircases.size() >= 2 && st.stepsDug >= 1, "and a new one dug from the pit (" + bases.staircases.size() + " " + st.stepsDug + " "
                    + st.debug + " | " + st.recent + ")");
            finish(h, c);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 3000, batch = "p25giveup")
    public static void givesUpAStaircaseItCannotFollow(GameTestHelper h) {
        clearBases(h);
        stoneMass(h);
        var bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
        var old = bases.addStaircase(h.getLevel().dimension(), h.absolutePos(new BlockPos(4, 5, 7)), Direction.EAST);
        old.end = h.absolutePos(new BlockPos(7, 2, 7)); // three steps on record, none of them dug
        ClonePlayer c = clone(h, 4.5, 7.5, -90f, false);
        Vec3 top = h.absoluteVec(new Vec3(4.5, 5, 7.5));
        c.teleportTo(h.getLevel(), top.x, top.y, top.z, -90f, 0f);
        stairsKit(h, c);
        learnStoneBlocks(c);
        c.controller().stairs().assumeSurface = true;
        c.controller().stairs().targetY = (int) top.y - 40;
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.STAIRS;
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            var st = c.controller().stairs();
            h.assertTrue(st.giveUps >= 1 && (old.failures >= 1 || old.finished), "it could not follow the old staircase and said so (" + st.giveUps + " " + st.debug + " | " + st.trace + ")");
            h.assertTrue(bases.staircases.size() >= 2 && st.stepsDug >= 1, "and dug a new one (" + bases.staircases.size() + " " + st.stepsDug + " | " + st.recent + ")");
            finish(h, c);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 80, batch = "p26stone")
    public static void staysForTheStonePickaxeBeforeAnyTrip(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        ClonePlayer friend = clone(h, 3.5, 3.5, 0f, false);
        c.controller().expedition().readyToLead();
        int trip = opt("EXPEDITION").bit();
        int[] mask = new int[3];
        h.runAfterDelay(5, () -> mask[0] = strategyMask(h, c)); // bare hands: a trip is on offer
        h.runAfterDelay(10, () -> c.getInventory().add(new ItemStack(Items.WOODEN_PICKAXE)));
        h.runAfterDelay(15, () -> mask[1] = strategyMask(h, c)); // a wooden pickaxe: the stone pickaxe first
        h.runAfterDelay(20, () -> {
            c.getInventory().clearContent();
            c.getInventory().add(new ItemStack(Items.STONE_PICKAXE)); // (and this arena lies below the depth to dig to: nothing to stay for)
        });
        h.runAfterDelay(25, () -> {
            mask[2] = strategyMask(h, c);
            h.assertTrue((mask[0] & trip) != 0, "bare hands: a trip is on offer");
            h.assertTrue((mask[1] & trip) == 0, "a wooden pickaxe and no stone one yet: no trip");
            h.assertTrue((mask[2] & trip) != 0, "a stone pickaxe: trips again (nothing to dig for down here)");
            finish(h, c, friend);
            h.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 40, batch = "p26churn")
    public static void coolsDownAnOptionThatEndsAtOnce(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        var cc = c.controller();
        long now = h.getLevel().getGameTime();
        var mine = opt("MINE");
        var craft = opt("CRAFT");
        var quarry = opt("QUARRY");
        cc.noteOptionEnd(mine, 0, -0.005f, false, now);
        cc.noteOptionEnd(mine, 1, -0.005f, false, now);
        h.assertTrue(!cc.cooledDown(mine, now + 1), "twice is not yet a habit");
        cc.noteOptionEnd(mine, 0, 0f, false, now);
        h.assertTrue(cc.cooledDown(mine, now + 1) && !cc.cooledDown(mine, now + 700), "three in a row: left out for 600 ticks, then back");
        cc.noteOptionEnd(craft, 0, 0f, false, now);
        cc.noteOptionEnd(craft, 0, 0f, false, now);
        cc.noteOptionEnd(craft, 40, 0.2f, false, now); // one that did something breaks the run
        cc.noteOptionEnd(craft, 0, 0f, false, now);
        h.assertTrue(!cc.cooledDown(craft, now + 1), "a run broken by one that ran is no habit");
        for (int i = 0; i < 6; i++) {
            cc.noteOptionEnd(quarry, 0, 1.5f, false, now); // ended at once but with something gained
        }
        h.assertTrue(!cc.cooledDown(quarry, now + 1), "ending at once with a reward does not count");
        finish(h, c);
        h.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 40, batch = "p26pit")
    public static void leavesAPitItKeepsFallingInto(GameTestHelper h) {
        clearBases(h);
        BlockPos ore = new BlockPos(9, 2, 7);
        h.setBlock(ore, Blocks.COAL_ORE);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        seen(h, c, ore);
        var cc = c.controller();
        long now = h.getLevel().getGameTime();
        h.assertTrue(cc.onEscapeStart(now) == 0 && cc.onEscapeStart(now + 100) == 0, "the first two times out of the cell is enough");
        h.assertTrue(!cc.skipped(h.absolutePos(ore)), "and the ore still draws");
        h.assertTrue(cc.onEscapeStart(now + 200) == 6, "the third time in the same place: well away this time");
        h.assertTrue(cc.skipped(h.absolutePos(ore)), "and the ore nearby is left alone");
        h.assertTrue(cc.onEscapeStart(now + 5000) == 0, "long after, it is a new start");
        finish(h, c);
        h.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 2500, batch = "p3tunnel")
    public static void tunnelsSidewaysAtTheTargetDepth(GameTestHelper h) {
        clearBases(h);
        stoneMass(h);
        h.setBlock(new BlockPos(4, 2, 7), Blocks.AIR);
        h.setBlock(new BlockPos(4, 3, 7), Blocks.AIR);
        var bases = com.rlclones.clone.Bases.get(h.getLevel().getServer());
        var st = bases.addStaircase(h.getLevel().dimension(), h.absolutePos(new BlockPos(3, 3, 7)), Direction.EAST);
        st.end = h.absolutePos(new BlockPos(4, 2, 7)); // one step down: this is the bottom
        ClonePlayer c = clone(h, 4.5, 7.5, 90f, false);
        stairsKit(h, c); // a stone pickaxe and no iron: iron is what is wanted
        learnStoneBlocks(c);
        c.controller().stairs().targetY = h.absolutePos(new BlockPos(4, 2, 7)).getY(); // as deep as it goes: level with the clone
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.STAIRS;
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            var stairs = c.controller().stairs();
            h.assertTrue(st.tunnelLen >= 5 && stairs.tunnelDug >= 5, "a level tunnel dug on from the bottom (" + st.tunnelLen + " " + stairs.debug + " | " + stairs.recent + ")");
            h.assertTrue(h.getBlockState(new BlockPos(6, 2, 7)).isAir() && h.getBlockState(new BlockPos(6, 3, 7)).isAir(), "two blocks high");
            finish(h, c);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 40, batch = "p3junk")
    public static void throwsAwayTunnelJunkWhenTheBagIsFull(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        var inv = c.getInventory();
        inv.clearContent();
        inv.add(new ItemStack(Items.STONE_PICKAXE));
        inv.add(new ItemStack(Items.RAW_IRON, 7));
        for (int i = 0; i < 4; i++) {
            inv.add(new ItemStack(Items.COBBLESTONE, 64));
        }
        for (var it : List.of(Items.GRANITE, Items.DIORITE, Items.ANDESITE, Items.TUFF, Items.DIRT, Items.GRAVEL, Items.COBBLED_DEEPSLATE, Items.DEEPSLATE,
                Items.CALCITE, Items.SMOOTH_BASALT, Items.DRIPSTONE_BLOCK, Items.COARSE_DIRT)) {
            inv.add(new ItemStack(it, 40));
        }
        for (var it : List.of(Items.POPPY, Items.DANDELION, Items.OAK_SAPLING, Items.BIRCH_SAPLING, Items.SPRUCE_SAPLING, Items.CLAY_BALL, Items.BRICK, Items.SNOWBALL,
                Items.FEATHER, Items.STRING, Items.BONE, Items.FLINT, Items.EGG)) {
            inv.add(new ItemStack(it, 3)); // fill the rest: few slots free
        }
        c.controller().stairs().dropJunk();
        h.assertTrue(inv.countItem(Items.GRANITE) == 0 && inv.countItem(Items.TUFF) == 0 && inv.countItem(Items.DIRT) == 0, "granite, tuff and dirt thrown away");
        h.assertTrue(inv.countItem(Items.COBBLESTONE) == 128 && inv.countItem(Items.RAW_IRON) == 7 && inv.countItem(Items.STONE_PICKAXE) == 1,
                "but the cobblestone is kept (two stacks) and the iron and the pickaxe (cobble " + inv.countItem(Items.COBBLESTONE) + ")");
        finish(h, c);
        h.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 200, batch = "p4brake")
    public static void doesNotWalkStraightIntoFire(GameTestHelper h) {
        for (int z = 6; z <= 8; z++) {
            h.setBlock(new BlockPos(8, 1, z), Blocks.NETHERRACK); // (burns for ever: no spreading, no going out)
            h.setBlock(new BlockPos(8, 2, z), Blocks.FIRE);
        }
        ClonePlayer c = clone(h, 4.5, 7.5, -90f, false);
        Vec3 goal = h.absoluteVec(new Vec3(11.5, 2, 7.5));
        double wall = h.absoluteVec(new Vec3(8.0, 2, 7.5)).x;
        boolean[] burned = {false};
        h.onEachTick(() -> {
            c.controller().motor().moveToward(goal);
            c.controller().motor().tick();
            burned[0] |= c.isOnFire() || c.getX() > wall - 0.3;
        });
        h.runAfterDelay(120, () -> {
            h.assertTrue(!burned[0], "it never stepped into the fire (x " + c.getX() + ", wall " + wall + ", brakes " + c.controller().motor().hazardBrakes + ")");
            h.assertTrue(c.getX() > h.absoluteVec(new Vec3(6.0, 2, 7.5)).x && c.controller().motor().hazardBrakes > 0, "but walked right up to it (x " + c.getX() + ")");
            finish(h, c);
            h.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 60, batch = "p4gravel")
    public static void doesNotDigOutWhatHoldsUpGravel(GameTestHelper h) {
        clearBases(h);
        h.setBlock(new BlockPos(5, 3, 7), Blocks.STONE);
        h.setBlock(new BlockPos(5, 4, 7), Blocks.STONE);
        h.setBlock(new BlockPos(5, 5, 7), Blocks.GRAVEL); // rests on the stone below it
        h.setBlock(new BlockPos(9, 3, 7), Blocks.STONE);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        var st = c.controller().stairs();
        h.assertTrue(!st.canDigOut(h.absolutePos(new BlockPos(5, 4, 7))), "stone with gravel on top: digging it out would bring the gravel down");
        h.assertTrue(st.canDigOut(h.absolutePos(new BlockPos(5, 3, 7))), "the stone under it is fine (nothing above it that falls)") ;
        h.assertTrue(st.canDigOut(h.absolutePos(new BlockPos(9, 3, 7))), "plain stone is fine");
        finish(h, c);
        h.succeed();
    }

    // ------------------------------------------------------------------ soak: how far do clones get on their own?

    /** Game ticks the soak runs by default (an hour of game time); a number alone on a line of soak.flag overrides it. */
    private static final int SOAK_TICKS = 72000;
    /** The most a soak.flag may ask for (the test's time limit). */
    private static final int SOAK_MAX_TICKS = 300000;
    private static final String[] SOAK_STEPS = {"wood", "table", "wood_pick", "stone_pick", "furnace", "coal", "iron_ingot", "iron_pick", "iron_gear",
            "diamond", "diamond_pick", "diamond_armor_1", "diamond_armor_2", "diamond_armor_3", "diamond_armor_4"};

    /** The soak only runs when a file named soak.flag lies in the game directory (run/) or the project root. */
    private static boolean soakWanted() {
        java.nio.file.Path game = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get();
        return java.nio.file.Files.exists(game.resolve("soak.flag")) || game.getParent() != null && java.nio.file.Files.exists(game.getParent().resolve("soak.flag"));
    }

    private static int soakTicks() {
        java.nio.file.Path game = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get();
        for (java.nio.file.Path dir : new java.nio.file.Path[]{game, game.getParent()}) {
            if (dir == null || !java.nio.file.Files.exists(dir.resolve("soak.flag"))) {
                continue;
            }
            try {
                for (String line : java.nio.file.Files.readAllLines(dir.resolve("soak.flag"))) {
                    if (line.trim().matches("\\d{3,7}")) {
                        return Math.max(600, Math.min(SOAK_MAX_TICKS, Integer.parseInt(line.trim())));
                    }
                }
            } catch (java.io.IOException | RuntimeException ignored) {
            }
        }
        return SOAK_TICKS;
    }

    /** Mob spawning is off for the arenas' sake, which also stops the animals coming back: one grazing animal near each clone now and then. */
    private static void soakAnimal(net.minecraft.server.level.ServerLevel level, ClonePlayer c, int n) {
        EntityType<?>[] kinds = {EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN};
        var rnd = level.getRandom();
        for (int tries = 0; tries < 16; tries++) {
            int x = c.getBlockX() + (rnd.nextBoolean() ? 1 : -1) * (14 + rnd.nextInt(18));
            int z = c.getBlockZ() + (rnd.nextBoolean() ? 1 : -1) * (14 + rnd.nextInt(18));
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (level.getBlockState(pos.below()).is(Blocks.GRASS_BLOCK) && level.getBlockState(pos).isAir()) {
                kinds[Math.floorMod(n, kinds.length)].spawn(level, pos, net.minecraft.world.entity.MobSpawnType.NATURAL);
                return;
            }
        }
    }

    private static boolean treesNear(net.minecraft.server.level.ServerLevel level, int x, int z) {
        int logs = 0;
        for (int dx = -32; dx <= 32; dx += 4) {
            for (int dz = -32; dz <= 32; dz += 4) {
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
                if (level.getBlockState(new BlockPos(x + dx, y - 1, z + dz)).is(net.minecraft.tags.BlockTags.LOGS)) {
                    logs++;
                }
            }
        }
        return logs >= 3;
    }

    /** Dry land with trees about, well away from the test arenas (which sit underground by the world spawn). */
    private static BlockPos soakSite(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos origin = h.absolutePos(BlockPos.ZERO);
        int[][] offs = {{3000, 0}, {-3000, 0}, {0, 3000}, {0, -3000}, {6000, 0}, {-6000, 0}, {0, 6000}, {0, -6000}, {4500, 4500}, {-4500, -4500}, {4500, -4500}, {-4500, 4500}};
        BlockPos fallback = null;
        for (int[] o : offs) {
            int x = origin.getX() + o[0];
            int z = origin.getZ() + o[1];
            level.getChunk(x >> 4, z >> 4);
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            if (fallback == null) {
                fallback = p;
            }
            if (y > 63 && level.getFluidState(p.below()).isEmpty() && treesNear(level, x, z)) {
                return p;
            }
        }
        return fallback;
    }

    private static java.util.Set<String> soakReached(ClonePlayer c) {
        var out = new java.util.LinkedHashSet<String>();
        var inv = c.getInventory();
        List<ItemStack> all = new ArrayList<>(inv.items);
        all.addAll(inv.armor);
        all.addAll(inv.offhand);
        int pick = -1;
        boolean wood = false, table = false, furnace = false, coal = false, iron = false, diamond = false;
        var diamondSlots = new java.util.HashSet<net.minecraft.world.entity.EquipmentSlot>();
        for (ItemStack st : all) {
            if (st.isEmpty()) {
                continue;
            }
            var it = st.getItem();
            wood |= st.is(net.minecraft.tags.ItemTags.LOGS) || st.is(net.minecraft.tags.ItemTags.PLANKS);
            table |= it == Items.CRAFTING_TABLE;
            furnace |= it == Items.FURNACE;
            coal |= it == Items.COAL || it == Items.CHARCOAL;
            if (it instanceof net.minecraft.world.item.PickaxeItem pi) {
                pick = Math.max(pick, pi.getTier().getLevel());
            }
            if (it instanceof net.minecraft.world.item.TieredItem ti) {
                iron |= ti.getTier() == net.minecraft.world.item.Tiers.IRON;
                diamond |= ti.getTier() == net.minecraft.world.item.Tiers.DIAMOND;
            }
            if (it instanceof net.minecraft.world.item.ArmorItem ai) {
                iron |= ai.getMaterial() == net.minecraft.world.item.ArmorMaterials.IRON;
                if (ai.getMaterial() == net.minecraft.world.item.ArmorMaterials.DIAMOND) {
                    diamond = true;
                    diamondSlots.add(ai.getEquipmentSlot());
                }
            }
            iron |= it == Items.IRON_INGOT;
            diamond |= it == Items.DIAMOND;
        }
        var perception = c.controller().perception();
        table |= perception.blocks().containsValue(com.rlclones.ai.Perception.BlockKind.TABLE);
        furnace |= perception.blocks().containsValue(com.rlclones.ai.Perception.BlockKind.FURNACE);
        for (var e : new Object[][]{{"wood", wood}, {"table", table}, {"wood_pick", pick >= 0}, {"stone_pick", pick >= 1}, {"furnace", furnace}, {"coal", coal},
                {"iron_ingot", iron}, {"iron_pick", pick >= 2}, {"iron_gear", com.rlclones.ai.StairMining.ironGeared(c)}, {"diamond", diamond},
                {"diamond_pick", pick >= 3}, {"diamond_armor_1", diamondSlots.size() >= 1}, {"diamond_armor_2", diamondSlots.size() >= 2},
                {"diamond_armor_3", diamondSlots.size() >= 3}, {"diamond_armor_4", diamondSlots.size() >= 4}}) {
            if ((Boolean) e[1]) {
                out.add((String) e[0]);
            }
        }
        return out;
    }

    private static String soakBag(ClonePlayer c) {
        var inv = c.getInventory();
        return "log=" + (inv.countItem(Items.OAK_LOG) + inv.countItem(Items.BIRCH_LOG) + inv.countItem(Items.SPRUCE_LOG)) + " plank=" + (inv.countItem(Items.OAK_PLANKS)
                + inv.countItem(Items.BIRCH_PLANKS) + inv.countItem(Items.SPRUCE_PLANKS)) + " cobble=" + inv.countItem(Items.COBBLESTONE) + " coal=" + inv.countItem(Items.COAL)
                + " raw_iron=" + inv.countItem(Items.RAW_IRON) + " iron=" + inv.countItem(Items.IRON_INGOT) + " diamond=" + inv.countItem(Items.DIAMOND)
                + " food=" + c.getFoodData().getFoodLevel() + " hp=" + (int) c.getHealth() + " y=" + c.getBlockY();
    }

    // ------------------------------------------------------------------ Round 11 (S11-1): water

    /**
     * R-24: a 1-wide water tunnel under a ceiling with one air pocket at x=6. The clone, low on air, pushes at the dead end
     * (x=3) and makes no headway: it must try other ways (the last, back along the tunnel, passes the pocket) and get its air back.
     */
    @GameTest(template = ARENA, timeoutTicks = 600, batch = "r11water")
    public static void getsOutOfAnUnderwaterCorner(GameTestHelper h) {
        for (int x = 3; x <= 11; x++) {
            for (int z = 6; z <= 8; z++) {
                for (int y = 1; y <= 5; y++) {
                    boolean wet = z == 7 && x >= 4 && x <= 10 && (y == 2 || y == 3);
                    h.setBlock(new BlockPos(x, y, z), wet ? Blocks.WATER : Blocks.STONE);
                }
            }
        }
        h.setBlock(new BlockPos(6, 4, 7), Blocks.AIR);
        ClonePlayer c = clone(h, 4.5, 7.5, 90f, false);
        c.setAirSupply((int) (c.getMaxAirSupply() * 0.25));
        Vec3 goal = h.absoluteVec(new Vec3(1.5, 2, 7.5));
        int[] maxAir = {0};
        h.onEachTick(() -> {
            c.controller().motor().moveToward(goal);
            c.controller().motor().tick();
            maxAir[0] = Math.max(maxAir[0], c.getAirSupply());
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.isAlive(), "not drowned (hp " + c.getHealth() + " air " + c.getAirSupply() + " at " + c.position() + " escapes " + c.controller().motor().waterEscapes + ")");
            h.assertTrue(c.controller().motor().waterEscapes >= 1, "tried another way out of the dead end");
            h.assertTrue(maxAir[0] >= c.getMaxAirSupply() * 0.9, "and got to the air pocket (max air " + maxAir[0] + ")");
            finish(h, c);
        });
    }

    /** R-23: deep water, little air: a door goes down, the clone breathes in it, breaks it and has it back. */
    @GameTest(template = ARENA, timeoutTicks = 800, batch = "r11water")
    public static void breathesInADoorUnderwater(GameTestHelper h) {
        for (int x = 4; x <= 10; x++) {
            for (int z = 4; z <= 10; z++) {
                for (int y = 1; y <= 14; y++) {
                    boolean shell = x == 4 || x == 10 || z == 4 || z == 10 || y == 1 || y == 14;
                    h.setBlock(new BlockPos(x, y, z), shell ? Blocks.STONE : Blocks.WATER);
                }
            }
        }
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.OAK_DOOR));
        c.setAirSupply((int) (c.getMaxAirSupply() * 0.25));
        int[] maxAir = {0};
        h.onEachTick(() -> {
            c.controller().doorBreath().tick(h.getLevel().getGameTime());
            c.controller().motor().tick();
            maxAir[0] = Math.max(maxAir[0], c.getAirSupply());
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().doorBreath().doorsPlaced >= 1, "a door was put down");
            h.assertTrue(maxAir[0] >= c.getMaxAirSupply() * 0.9, "air back to nearly full (max " + maxAir[0] + ")");
            h.assertTrue(!c.controller().doorBreath().busy() && c.getInventory().countItem(Items.OAK_DOOR) == 1, "and the door is back in the bag");
            finish(h, c);
        });
    }

    /** R-22: a block on the bank while standing in shallow water: it comes ashore first, then digs. */
    @GameTest(template = ARENA, timeoutTicks = 400, batch = "r11water")
    public static void stepsOutOfTheWaterToMine(GameTestHelper h) {
        for (int x = 4; x <= 8; x++) {
            for (int z = 4; z <= 8; z++) {
                h.setBlock(new BlockPos(x, 2, z), Blocks.WATER);
            }
        }
        BlockPos dirt = new BlockPos(10, 2, 6);
        h.setBlock(dirt, Blocks.DIRT);
        ClonePlayer c = clone(h, 6.5, 6.5, 0f, false);
        BlockPos abs = h.absolutePos(dirt);
        boolean[] wetWhenDone = {true};
        boolean[] done = {false};
        h.onEachTick(() -> {
            if (!done[0]) {
                boolean wet = c.isInWater();
                if (c.controller().motor().mine(abs)) {
                    done[0] = true;
                    wetWhenDone[0] = wet;
                }
            }
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(done[0], "the block was dug");
            h.assertTrue(!wetWhenDone[0], "from dry footing, not out of the water");
            finish(h, c);
        });
    }

    // ------------------------------------------------------------------ Round 11 (S11-2)

    /** R-18: a 7-high trunk: the top logs are out of reach from the ground, so it pillars up beside it and cuts them all. */
    @GameTest(template = ARENA, timeoutTicks = 1600, batch = "r11goods")
    public static void cutsATallTreeToTheTop(GameTestHelper h) {
        for (int y = 2; y <= 8; y++) {
            h.setBlock(new BlockPos(9, y, 7), Blocks.OAK_LOG);
        }
        for (int x = 8; x <= 10; x++) {
            for (int z = 6; z <= 8; z++) {
                h.setBlock(new BlockPos(x, 9, z), Blocks.OAK_LEAVES);
            }
        }
        ClonePlayer c = clone(h, 6.5, 7.5, -90f, false);
        c.getInventory().add(new ItemStack(Items.DIRT, 16));
        c.controller().forcedOption = opt("GATHER_WOOD");
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            h.assertTrue(logsLeft(h).isEmpty() && c.controller().scaffoldsPlaced >= 1, "the tree cut but the top (scaffold up for the high logs), " + countBlocks(h, Blocks.OAK_LOG) + " left (scaffold "
                    + c.controller().scaffoldsPlaced + " y " + c.getY() + " dirt " + c.getInventory().countItem(Items.DIRT) + " " + c.controller().harvestDebug + " " + c.controller().optionLog + ") trace=" + c.controller().harvestTrace + " left=" + logsLeft(h) + " clone=" + c.blockPosition().subtract(h.absolutePos(BlockPos.ZERO)).toShortString());
            finish(h, c);
        });
    }

    private static String logsLeft(GameTestHelper h) {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y <= 12; y++) {
            if (h.getBlockState(new BlockPos(9, y, 7)).is(Blocks.OAK_LOG)) {
                sb.append("y").append(y).append(' ');
            }
        }
        return sb.toString();
    }

    /** R-25: 10 cells of soil by a channel, 6 seeds: exactly the plot of 6 is tilled and sown, one after the other. */
    @GameTest(template = ARENA, timeoutTicks = 1800, batch = "r11goods")
    public static void tillsAndPlantsAsManyAsItHasSeeds(GameTestHelper h) {
        for (int x = 3; x <= 12; x++) {
            h.setBlock(new BlockPos(x, 0, 10), Blocks.STONE);
            h.setBlock(new BlockPos(x, 1, 10), Blocks.WATER);
            h.setBlock(new BlockPos(x, 1, 11), Blocks.GRASS_BLOCK);
        }
        h.setBlock(new BlockPos(7, 2, 13), Blocks.GLOWSTONE);
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.WOODEN_HOE));
        c.getInventory().add(new ItemStack(Items.WHEAT_SEEDS, 6));
        boolean[] work = {false};
        h.onEachTick(() -> {
            if (!work[0]) {
                work[0] = c.controller().farming().hasWork();
                return;
            }
            c.controller().farming().tick();
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            int planted = 0;
            for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(3, 1, 11)), h.absolutePos(new BlockPos(12, 1, 12)))) {
                if (h.getLevel().getBlockState(p).is(Blocks.FARMLAND) && h.getLevel().getBlockState(p.above()).is(Blocks.WHEAT)) {
                    planted++;
                }
            }
            h.assertTrue(planted == 6, "6 cells tilled and sown, got " + planted + " (" + c.controller().farming().debug() + ")");
            finish(h, c);
        });
    }

    /** R-26: 30 torches, a dark field with crops: torches go up beside the crops and 16 are kept. */
    @GameTest(template = ARENA, timeoutTicks = 800, batch = "r11goods")
    public static void lightsTheCropsWithSpareTorches(GameTestHelper h) {
        for (int x = 3; x <= 11; x++) {
            for (int z = 3; z <= 11; z++) {
                boolean wall = x == 3 || x == 11 || z == 3 || z == 11;
                for (int y = 2; y <= 4; y++) {
                    h.setBlock(new BlockPos(x, y, z), wall ? Blocks.STONE : Blocks.AIR);
                }
                h.setBlock(new BlockPos(x, 5, z), Blocks.STONE);
            }
        }
        h.setBlock(new BlockPos(6, 1, 5), Blocks.WATER);
        for (int x = 5; x <= 7; x++) {
            for (int z = 6; z <= 7; z++) {
                h.setBlock(new BlockPos(x, 1, z), Blocks.FARMLAND);
                h.setBlock(new BlockPos(x, 2, z), Blocks.WHEAT);
            }
        }
        ClonePlayer c = clone(h, 4.5, 8.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.TORCH, 30));
        h.onEachTick(() -> {
            c.controller().farming().tick();
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().farming().torchesPlaced >= 1, "a torch put up by the crops (" + c.controller().farming().debug() + ")");
            h.assertTrue(c.getInventory().countItem(Items.TORCH) >= 16, "and 16 or more kept");
            finish(h, c);
        });
    }

    /** R-17: a row of 8 grass between the clone and its goal: cut on the way, without slowing down. */
    @GameTest(template = ARENA, timeoutTicks = 300, batch = "r11walk")
    public static void cutsGrassOnTheWayWithoutStopping(GameTestHelper h) {
        for (int x = 4; x <= 11; x++) {
            h.setBlock(new BlockPos(x, 1, 7), Blocks.GRASS_BLOCK); // (grass pops off anything but soil)
            h.setBlock(new BlockPos(x, 2, 7), Blocks.GRASS);
        }
        ClonePlayer c = clone(h, 2.5, 7.5, -90f, false);
        Vec3 goal = h.absoluteVec(new Vec3(13.5, 2, 7.5));
        int[] ticks = {0};
        h.onEachTick(() -> {
            if (Motor.horizontalDistance(c.position(), goal) > 1.0) {
                ticks[0]++;
            }
            c.controller().motor().sweep();
            c.controller().motor().navigate(goal, 0.5, true);
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(Motor.horizontalDistance(c.position(), goal) <= 1.0, "reached the goal");
            h.assertTrue(c.controller().motor().sweepCuts >= 6, "6 or more blades cut on the way, got " + c.controller().motor().sweepCuts);
            h.assertTrue(ticks[0] <= 110, "and no stop for them: " + ticks[0] + " ticks for 11 blocks");
            finish(h, c);
        });
    }

    /** R-27: a digger with a stone pickaxe says so; an idle friend with an iron one brings it. */
    @GameTest(template = ARENA, timeoutTicks = 1200, batch = "r11walk")
    public static void handsABetterPickaxeToTheDigger(GameTestHelper h) {
        clearBases(h);
        ClonePlayer a = clone(h, 8.5, 7.5, 0f, false);
        ClonePlayer b = clone(h, 4.5, 4.5, 0f, false);
        a.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        b.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        b.controller().itemAid().onChat(a, "DIGGING 1", h.getLevel().getGameTime());
        b.controller().forcedOption = opt("FEED");
        b.setAiEnabled(true);
        h.succeedWhen(() -> {
            h.assertTrue(b.controller().itemAid().given >= 1, "the friend brought it (" + b.controller().itemAid().state() + " " + b.controller().optionLog + ")");
            h.assertTrue(a.getInventory().countItem(Items.IRON_PICKAXE) >= 1, "and the digger holds the iron pickaxe now");
            finish(h, a, b);
        });
    }

    // ------------------------------------------------------------------ Round 11 (S11-4)

    /** A 5x5 stone block 11 high (y 2..12) with a hole cut through it at x=7, z=7 (and z=8 when {@code wide}); the clone stands on top, facing the hole. */
    private static ClonePlayer onAPitEdge(GameTestHelper h, boolean wide) {
        for (int x = 5; x <= 9; x++) {
            for (int z = 5; z <= 9; z++) {
                for (int y = 2; y <= 12; y++) {
                    boolean hole = x == 7 && (z == 7 || wide && z == 8);
                    h.setBlock(new BlockPos(x, y, z), hole ? Blocks.AIR : Blocks.STONE);
                }
            }
        }
        ClonePlayer c = clone(h, 6.5, 7.5, -90f, false);
        Vec3 top = h.absoluteVec(new Vec3(6.5, 13, 7.5));
        c.teleportTo(h.getLevel(), top.x, top.y, top.z, -90f, 0f);
        return c;
    }

    /** R-19: an 11-deep 1x1 hole, 12 ladders: down the ladders to the bottom, alive. */
    @GameTest(template = ARENA, timeoutTicks = 900, batch = "r11pit")
    public static void laddersDownIntoADeepPit(GameTestHelper h) {
        ClonePlayer c = onAPitEdge(h, false);
        c.getInventory().add(new ItemStack(Items.LADDER, 12));
        c.controller().pits().force = true;
        h.onEachTick(() -> {
            c.controller().pits().tick(h.getLevel().getGameTime(), null);
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            var p = c.controller().pits();
            h.assertTrue(p.laddersPlaced >= 3 && p.descents >= 1, "climbed down by ladders (placed " + p.laddersPlaced + " descents " + p.descents + " y " + c.getY() + ")");
            h.assertTrue(c.isAlive() && c.getHealth() >= 18f, "and unhurt (hp " + c.getHealth() + ")");
            h.assertTrue(c.getY() < h.absoluteVec(new Vec3(0, 4, 0)).y, "at the bottom (y " + c.getY() + ")");
            finish(h, c);
        });
    }

    /** R-19: a deep 1x2 hole beside a base, nothing to dig for: it is covered with blocks. */
    @GameTest(template = ARENA, timeoutTicks = 600, batch = "r11pit")
    public static void coversADeadlyHoleNearHome(GameTestHelper h) {
        clearBases(h);
        baseAt(h, new BlockPos(2, 2, 2));
        ClonePlayer c = onAPitEdge(h, true);
        c.getInventory().add(new ItemStack(Items.COBBLESTONE, 8));
        h.onEachTick(() -> {
            c.controller().pits().tick(h.getLevel().getGameTime(), null);
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(!h.getBlockState(new BlockPos(7, 12, 7)).isAir() && !h.getBlockState(new BlockPos(7, 12, 8)).isAir(),
                    "both cells of the hole covered (covered " + c.controller().pits().covered + ")");
            finish(h, c);
            clearBases(h);
        });
    }

    /** R-20: told of a structure across the room, an exploring clone heads there and counts it as visited. */
    @GameTest(template = ARENA, timeoutTicks = 1200, batch = "r11pit")
    public static void headsForAKnownStructureAndVisitsIt(GameTestHelper h) {
        clearBases(h);
        for (int x = 10; x <= 12; x++) {
            for (int z = 10; z <= 12; z++) {
                h.setBlock(new BlockPos(x, 2, z), x == 11 && z == 11 ? Blocks.CHEST : Blocks.OAK_PLANKS);
            }
        }
        ClonePlayer c = clone(h, 2.5, 2.5, 0f, false);
        c.controller().structures().note(h.absolutePos(new BlockPos(11, 2, 11)));
        c.controller().forcedOption = opt("EXPLORE");
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().structures().visits >= 1, "went to the structure and counted it (visits " + c.controller().structures().visits + " " + c.controller().optionLog + ")");
            finish(h, c);
        });
    }

    // ------------------------------------------------------------------ Round 13: a someone (R-33..R-40)

    /** R-34/35/36: a collector (and a fishing favourite) feels like advancements / fishing, whatever else is on offer. */
    @GameTest(template = ARENA, timeoutTicks = 100, batch = "r12goal")
    public static void goalAndFavouritePullTheStrategy(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        var p = c.controller().persona();
        p.goal = java.util.Arrays.asList(com.rlclones.ai.Persona.GOALS).indexOf("collector");
        p.favourite = 0; // FISH
        int all = 0;
        for (var o : com.rlclones.ai.strategy.Option.VALUES) {
            all |= o.bit();
        }
        int ach = 0;
        int fish = 0;
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 400; i++) {
            var o = p.pull(all, r.nextFloat(), r.nextFloat(), false, false);
            ach += o == opt("ACHIEVE") ? 1 : 0;
            fish += o == opt("FISH") ? 1 : 0;
        }
        h.assertTrue(ach >= 80 && fish >= 10, "a collector goes for advancements (" + ach + "/400) and now and then fishes (" + fish + "/400)");
        finish(h, c);
        h.succeed();
    }

    /** R-37: flowers in the bag, a base with grass round it: they are planted 3..6 blocks from the chest. */
    @GameTest(template = ARENA, timeoutTicks = 900, batch = "r12deco")
    public static void decoratesTheBaseWithFlowers(GameTestHelper h) {
        clearBases(h);
        for (int x = 1; x <= 13; x++) {
            for (int z = 1; z <= 13; z++) {
                h.setBlock(new BlockPos(x, 1, z), Blocks.GRASS_BLOCK);
            }
        }
        baseAt(h, new BlockPos(7, 2, 7));
        ClonePlayer c = clone(h, 6.5, 6.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.POPPY, 3));
        h.onEachTick(() -> {
            c.controller().persona().tick(h.getLevel().getGameTime(), opt("REST"), false);
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().persona().flowersPlaced >= 2 && countBlocks(h, Blocks.POPPY) >= 2,
                    "flowers planted round the base (" + c.controller().persona().flowersPlaced + ")");
            finish(h, c);
            clearBases(h);
        });
    }

    /** R-38: an animal near: it gets a name and the clone walks over to see it. */
    @GameTest(template = ARENA, timeoutTicks = 1200, batch = "r12pet")
    public static void namesAndVisitsAFavouriteAnimal(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 3.5, 7.5, 0f, false);
        Pig pig = pig(h, 11.5, 7.5);
        boolean[] close = {false};
        h.onEachTick(() -> {
            c.controller().persona().tick(h.getLevel().getGameTime(), opt("REST"), false);
            c.controller().motor().tick();
            close[0] |= c.controller().persona().petVisits >= 1 && pig.distanceTo(c) < 4.5;
        });
        h.succeedWhen(() -> {
            var p = c.controller().persona();
            h.assertTrue(pig.hasCustomName() && p.petsNamed >= 1, "the animal got a name");
            h.assertTrue(close[0], "and the clone walked over to look in on it (visits " + p.petVisits + ")");
            finish(h, c);
        });
    }

    /** R-50: surplus wheat and a villager buying it: the clone walks over and sells it for an emerald. */
    @GameTest(template = ARENA, timeoutTicks = 800, batch = "r12pet")
    public static void sellsSurplusWheatToAVillager(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 3.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.WHEAT, 64));
        net.minecraft.world.entity.npc.Villager v = h.spawn(EntityType.VILLAGER, new Vec3(11.5, 2, 7.5));
        v.setNoAi(true);
        v.getOffers().clear();
        v.getOffers().add(new net.minecraft.world.item.trading.MerchantOffer(new ItemStack(Items.WHEAT, 20), new ItemStack(Items.EMERALD, 1), 16, 2, 0.05f));
        h.onEachTick(() -> {
            c.controller().trading().tick(h.getLevel().getGameTime());
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().trading().deals >= 1 && c.getInventory().countItem(Items.EMERALD) >= 1, "wheat sold for an emerald (deals " + c.controller().trading().deals + ")");
            finish(h, c);
        });
    }

    /** R-50: a sign in the bag by the base: it is put up, with the clone's name on it. */
    @GameTest(template = ARENA, timeoutTicks = 400, batch = "r12sign")
    public static void putsUpASignByTheBase(GameTestHelper h) {
        clearBases(h);
        baseAt(h, new BlockPos(7, 2, 7));
        ClonePlayer c = clone(h, 6.5, 6.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.OAK_SIGN));
        h.onEachTick(() -> {
            c.controller().persona().tick(h.getLevel().getGameTime(), opt("REST"), false);
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().persona().signsPlaced >= 1, "a sign put up (" + c.controller().persona().signWhy + ")");
            finish(h, c);
            clearBases(h);
        });
    }

    /** R-38: a hurt favourite animal and its food in the bag: the clone feeds it. */
    @GameTest(template = ARENA, timeoutTicks = 1200, batch = "r12pet")
    public static void feedsAHurtFavouriteAnimal(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 3.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.CARROT, 4));
        Pig pig = pig(h, 11.5, 7.5);
        pig.setHealth(3f);
        h.onEachTick(() -> {
            c.controller().persona().tick(h.getLevel().getGameTime(), opt("REST"), false);
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().persona().petsFed >= 1 && pig.getHealth() > 3f, "the hurt animal was fed (fed " + c.controller().persona().petsFed + ")");
            finish(h, c);
        });
    }

    /** R-39: an untidy base chest next to the clone is put in order: food first, tools next, stacks merged. */
    @GameTest(template = ARENA, timeoutTicks = 300, batch = "r12tidy")
    public static void sortsTheBaseChest(GameTestHelper h) {
        clearBases(h);
        BlockPos at = new BlockPos(7, 2, 7);
        baseAt(h, at);
        var chest = (net.minecraft.world.Container) h.getLevel().getBlockEntity(h.absolutePos(at));
        chest.setItem(0, new ItemStack(Items.DIRT, 10));
        chest.setItem(1, new ItemStack(Items.IRON_PICKAXE));
        chest.setItem(2, new ItemStack(Items.DIRT, 20));
        chest.setItem(3, new ItemStack(Items.BREAD, 5));
        ClonePlayer c = clone(h, 6.5, 6.5, 0f, false);
        h.onEachTick(() -> {
            c.controller().persona().tick(h.getLevel().getGameTime(), opt("REST"), false);
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().persona().chestsTidied >= 1, "the chest was tidied (" + c.controller().persona().tidyDebug + ")");
            h.assertTrue(chest.getItem(0).is(Items.BREAD) && chest.getItem(1).is(Items.IRON_PICKAXE) && chest.getItem(2).is(Items.DIRT)
                    && chest.getItem(2).getCount() == 30 && chest.getItem(3).isEmpty(), "food, tools, then the dirt in one stack");
            finish(h, c);
            clearBases(h);
        });
    }

    /** R-40: told of a spot where a friend fell, the clone steers its explorations away from it. */
    @GameTest(template = ARENA, timeoutTicks = 100, batch = "r12danger")
    public static void takesNoteOfADangerSpotItIsToldAbout(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        ClonePlayer friend = clone(h, 3.5, 3.5, 0f, false);
        BlockPos spot = h.absolutePos(new BlockPos(12, 2, 12));
        c.controller().persona().onChat(friend, "DANGER " + spot.getX() + " " + spot.getY() + " " + spot.getZ(), h.getLevel().getGameTime());
        h.assertTrue(c.controller().persona().avoids(Vec3.atCenterOf(spot.offset(3, 0, 2))), "a point near the spot is to be avoided");
        h.assertTrue(!c.controller().persona().avoids(Vec3.atCenterOf(spot.offset(-30, 0, 0))), "far from it is fine");
        finish(h, c, friend);
        h.succeed();
    }

    /** R-33: a friend starts a project (a hut): a clone with building blocks answers and sets off for it. */
    @GameTest(template = ARENA, timeoutTicks = 200, batch = "r12hamlet")
    public static void answersAFriendsHamletProject(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 3.5, 3.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        ClonePlayer friend = clone(h, 12.5, 12.5, 0f, false);
        BlockPos at = h.absolutePos(new BlockPos(12, 2, 12));
        c.controller().persona().onChat(friend, "PROJECT " + at.getX() + " " + at.getY() + " " + at.getZ(), h.getLevel().getGameTime());
        h.assertTrue(c.controller().persona().projectActive(), "it took the project up");
        finish(h, c, friend);
        h.succeed();
    }

    // ------------------------------------------------------------------ Round 14: new ideas (R-41..R-49)

    /** R-43: where a clone died is remembered by all: a new clone steers away from it. */
    @GameTest(template = ARENA, timeoutTicks = 100, batch = "r14death")
    public static void rememberedDeathSpotsAreAvoided(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        BlockPos spot = h.absolutePos(new BlockPos(12, 2, 12));
        com.rlclones.ai.Persona.noteDeath(spot);
        h.assertTrue(c.controller().persona().avoids(Vec3.atCenterOf(spot.offset(2, 0, 2))), "near the death spot: avoided");
        com.rlclones.ai.Persona.forgetDeaths();
        h.assertTrue(!c.controller().persona().avoids(Vec3.atCenterOf(spot)), "forgotten again (test cleanup)");
        finish(h, c);
        h.succeed();
    }

    /** R-41: a hungry clone with nothing to eat takes bread from the base chest; a full-handed one puts the surplus in. */
    @GameTest(template = ARENA, timeoutTicks = 600, batch = "r14pantry")
    public static void takesFoodFromAndStoresFoodInTheBaseChest(GameTestHelper h) {
        clearBases(h);
        BlockPos at = new BlockPos(7, 2, 7);
        baseAt(h, at);
        var chest = (net.minecraft.world.Container) h.getLevel().getBlockEntity(h.absolutePos(at));
        chest.setItem(0, new ItemStack(Items.BREAD, 10));
        ClonePlayer hungry = clone(h, 6.5, 6.5, 0f, false);
        hungry.getFoodData().setFoodLevel(6);
        h.onEachTick(() -> {
            hungry.controller().persona().tick(h.getLevel().getGameTime(), opt("REST"), false);
            hungry.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(hungry.getInventory().countItem(Items.BREAD) >= 1 && hungry.controller().persona().pantryMoves >= 1, "the hungry clone took bread");
            finish(h, hungry);
            clearBases(h);
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 600, batch = "r14stash")
    public static void putsSurplusFoodIntoTheBaseChest(GameTestHelper h) {
        clearBases(h);
        BlockPos at = new BlockPos(7, 2, 7);
        baseAt(h, at);
        var chest = (net.minecraft.world.Container) h.getLevel().getBlockEntity(h.absolutePos(at));
        ClonePlayer rich = clone(h, 6.5, 6.5, 0f, false);
        rich.getInventory().add(new ItemStack(Items.COOKED_BEEF, 40));
        h.onEachTick(() -> {
            rich.controller().persona().tick(h.getLevel().getGameTime(), opt("REST"), false);
            rich.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            int stored = 0;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                stored += chest.getItem(i).is(Items.COOKED_BEEF) ? chest.getItem(i).getCount() : 0;
            }
            h.assertTrue(stored >= 20 && rich.getInventory().countItem(Items.COOKED_BEEF) <= 20, "the surplus went into the chest (" + stored + ")");
            finish(h, rich);
            clearBases(h);
        });
    }

    /** R-44: a digger tells where it is digging: the one that heard has the place as a target. */
    @GameTest(template = ARENA, timeoutTicks = 100, batch = "r14dig")
    public static void learnsWhereAFriendIsDigging(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        ClonePlayer friend = clone(h, 3.5, 3.5, 0f, false);
        BlockPos at = h.absolutePos(new BlockPos(11, 2, 11));
        h.assertTrue(c.controller().persona().digTarget() == null, "nothing known at first");
        c.controller().persona().onChat(friend, "DIGSITE " + at.getX() + " " + at.getY() + " " + at.getZ(), h.getLevel().getGameTime());
        h.assertTrue(c.controller().persona().digTarget() != null, "the dig site is known now");
        finish(h, c, friend);
        h.succeed();
    }

    /** R-45: a newborn sticks to the grown-ups. */
    @GameTest(template = ARENA, timeoutTicks = 100, batch = "r14young")
    public static void aNewbornFollowsTheGrownUps(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        int follow = opt("FOLLOW").bit();
        int n = 0;
        for (int i = 0; i < 100; i++) {
            n += c.controller().persona().pull(follow, 0.1f, 0.9f, false, true) == opt("FOLLOW") ? 1 : 0;
        }
        h.assertTrue(n == 100 && c.controller().persona().pull(follow, 0.9f, 0.9f, false, true) == null, "young: follows; the odd time it does not");
        h.assertTrue(c.controller().persona().pull(follow, 0.1f, 0.9f, false, false) == null, "grown up: no such pull");
        finish(h, c);
        h.succeed();
    }

    /** R-47: a friend calls a gathering at the base: the clone walks there, hops about, and counts it. */
    @GameTest(template = ARENA, timeoutTicks = 900, batch = "r14feast")
    public static void joinsAGatheringAtTheBase(GameTestHelper h) {
        clearBases(h);
        ClonePlayer c = clone(h, 2.5, 2.5, 0f, false);
        ClonePlayer friend = clone(h, 12.5, 12.5, 0f, false);
        BlockPos at = h.absolutePos(new BlockPos(10, 2, 10));
        c.controller().persona().onChat(friend, "FESTIVAL " + at.getX() + " " + at.getY() + " " + at.getZ(), h.getLevel().getGameTime());
        h.onEachTick(() -> {
            c.controller().persona().tick(h.getLevel().getGameTime(), opt("REST"), false);
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().persona().festivals >= 1, "it came, stayed a while and went home (festivals " + c.controller().persona().festivals + ")");
            finish(h, c, friend);
            clearBases(h);
        });
    }

    /** R-49: at the base the clone writes a diary page and leaves the book in the chest. */
    @GameTest(template = ARENA, timeoutTicks = 300, batch = "r14diary")
    public static void leavesADiaryInTheBaseChest(GameTestHelper h) {
        clearBases(h);
        BlockPos at = new BlockPos(7, 2, 7);
        baseAt(h, at);
        var chest = (net.minecraft.world.Container) h.getLevel().getBlockEntity(h.absolutePos(at));
        ClonePlayer c = clone(h, 6.5, 6.5, 0f, false);
        c.controller().persona().diaryAt = 0;
        h.onEachTick(() -> {
            c.controller().persona().tick(h.getLevel().getGameTime(), opt("REST"), false);
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            boolean book = false;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                book |= chest.getItem(i).is(Items.WRITTEN_BOOK);
            }
            h.assertTrue(book && c.controller().persona().diaries >= 1, "a diary in the chest");
            finish(h, c);
            clearBases(h);
        });
    }

    // ------------------------------------------------------------------ Round 15: fighting what it has not met (mod mobs)

    /** A mob that is no Enemy (any mod's) but is seen hurting again and again counts as hostile; before that it does not. */
    @GameTest(template = ARENA, timeoutTicks = 100, batch = "r15hostile")
    public static void learnsThatAnUnlistedMobIsHostile(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        Pig stand_in = pig(h, 9.5, 7.5); // stands in for a modded mob that is no Enemy
        h.assertTrue(!com.rlclones.ai.Senses.isHostileTo(stand_in, c), "unknown: not hostile");
        var k = c.getCloneBrain().knowledge(com.rlclones.ai.Perception.typeId(stand_in));
        k.meleeDamage.add(4.0);
        k.meleeDamage.add(5.0);
        h.assertTrue(com.rlclones.ai.Senses.isHostileTo(stand_in, c), "seen hurting twice: hostile");
        finish(h, c);
        h.succeed();
    }

    /** What to expect of a kind not met before is read off the mob: a ravager is far worse than a zombie. */
    @GameTest(template = ARENA, timeoutTicks = 100, batch = "r15prior")
    public static void expectsMoreOfAStrongerUnknownMob(GameTestHelper h) {
        var ravager = h.spawn(EntityType.RAVAGER, new Vec3(9.5, 2, 7.5));
        var zombie = h.spawn(EntityType.ZOMBIE, new Vec3(5.5, 2, 7.5));
        ravager.setNoAi(true);
        zombie.setNoAi(true);
        double r = com.rlclones.ai.Senses.priorDps(ravager);
        double z = com.rlclones.ai.Senses.priorDps(zombie);
        h.assertTrue(r >= 2 * z, "ravager " + r + " vs zombie " + z);
        h.killAllEntities();
        h.succeed();
    }

    /** A kind met for the first time starts from the fighting experience gathered against its likes, lightly. */
    @GameTest(template = ARENA, timeoutTicks = 100, batch = "r15transfer")
    public static void aNewMobTypeStartsFromItsLikes(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        var brain = c.getCloneBrain();
        var husk = brain.combatTable("minecraft:husk");
        for (int s = 0; s < 40; s++) {
            var e = husk.get(s, com.rlclones.ai.brain.QTable.Prior.ZERO);
            e.q[0] = 1.5f;
            e.n[0] = 9;
        }
        var modded = brain.combatTable("somemod:crawler");
        var e0 = modded.peek(3);
        h.assertTrue(modded.size() >= 40 && e0 != null && e0.q[0] > 1.0f && e0.n[0] <= 2, "seeded: size " + modded.size());
        finish(h, c);
        h.succeed();
    }

    /** Hurt and chased with a base to the east: the flight bends towards it instead of running straight away. */
    @GameTest(template = ARENA, timeoutTicks = 300, batch = "r15refuge")
    public static void fleesTowardsTheBaseWhenHurt(GameTestHelper h) {
        clearBases(h);
        baseAt(h, new BlockPos(13, 2, 7));
        ClonePlayer c = clone(h, 6.5, 7.5, 0f, false);
        c.setHealth(6f);
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        slowHusk(h, 6.5, 2, 2.5); // north of it: straight away would be south
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FLEE;
        h.runAfterDelay(10, () -> wake(h, c));
        double startX = c.getX();
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().fledHome >= 1 && c.getX() > startX + 2.5, "the flight bent east to the base (dx " + (c.getX() - startX) + ", fledHome " + c.controller().fledHome + ")");
            finish(h, c);
            clearBases(h);
        });
    }

    /** R-53: night, no weapon: the clone makes for the base instead of wandering about in the dark. */
    @GameTest(template = ARENA, timeoutTicks = 600, batch = "r15night")
    public static void staysHomeAtNightWithoutAWeapon(GameTestHelper h) {
        clearBases(h);
        baseAt(h, new BlockPos(12, 2, 7));
        long before = h.getLevel().getDayTime();
        h.getLevel().setDayTime(before - before % 24000L + 15000L); // the middle of the night
        ClonePlayer c = clone(h, 3.5, 7.5, 0f, false);
        StringBuilder path = new StringBuilder();
        StringBuilder waterAt = new StringBuilder();
        StringBuilder sources = new StringBuilder();
        h.onEachTick(() -> {
            c.controller().persona().tick(h.getLevel().getGameTime(), opt("REST"), false);
            c.controller().motor().tick();
            if (waterAt.length() < 120 && h.getLevel().getGameTime() % 40 == 0) {
                int w = 0;
                for (int x = 1; x <= 13; x++) {
                    for (int z = 1; z <= 13; z++) {
                        w += h.getBlockState(new BlockPos(x, 2, z)).is(Blocks.WATER) ? 1 : 0;
                    }
                }
                waterAt.append(' ').append(w);
                if (w > 0 && sources.length() == 0) {
                    for (int y = 1; y <= 12; y++) {
                        for (int x = 0; x <= 14; x++) {
                            for (int z = 0; z <= 14; z++) {
                                var fs = h.getLevel().getFluidState(h.absolutePos(new BlockPos(x, y, z)));
                                if (fs.isSource() && sources.length() < 120) {
                                    sources.append(' ').append(x).append(',').append(y).append(',').append(z);
                                }
                            }
                        }
                    }
                    sources.append(" @t").append(h.getLevel().getGameTime() % 100000L);
                }
            }
            if (h.getLevel().getGameTime() % 20 == 0 && path.length() < 400) {
                path.append(' ').append(c.blockPosition().subtract(h.absolutePos(BlockPos.ZERO)).toShortString()).append(c.onGround() ? "" : "^");
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().persona().stormRuns >= 1 && c.distanceToSqr(h.absoluteVec(new Vec3(12.5, 2, 7.5))) < 36, "walked to the base at night (runs " + c.controller().persona().stormRuns + " at " + c.blockPosition().subtract(h.absolutePos(BlockPos.ZERO)).toShortString() + " stuck " + c.controller().motor().stuckCount() + " path" + path + " sources[" + sources + "] water(y2 cells every 40t)" + waterAt + " origin " + h.absolutePos(BlockPos.ZERO).toShortString() + " grid " + java.util.stream.Stream.of(new BlockPos(5, 2, 5), new BlockPos(5, 2, 6), new BlockPos(5, 2, 8), new BlockPos(6, 2, 7), new BlockPos(2, 2, 7), new BlockPos(2, 5, 7), new BlockPos(9, 2, 9)).map(q -> h.getBlockState(q).getBlock().getDescriptionId().replace("block.minecraft.", "")).toList() + " ahead " + h.getBlockState(new BlockPos(5, 2, 7)).getBlock() + "/" + h.getBlockState(new BlockPos(5, 3, 7)).getBlock() + " near " + h.getLevel().getEntities(c, c.getBoundingBox().inflate(4)).stream().map(e -> e.getType().toShortString() + "@" + e.blockPosition().subtract(h.absolutePos(BlockPos.ZERO)).toShortString()).toList() + " time " + h.getLevel().getDayTime() % 24000L + " bases " + com.rlclones.clone.Bases.get(h.getLevel().getServer()).bases.stream().map(b -> b.center.subtract(h.absolutePos(BlockPos.ZERO)).toShortString()).toList() + ")");
            h.getLevel().setDayTime(before);
            finish(h, c);
            clearBases(h);
        });
    }

    /** Where a clone died (lava, a drop...) nothing is gathered: the tree there stands, the one elsewhere is felled. */
    @GameTest(template = ARENA, timeoutTicks = 1800, batch = "r15death")
    public static void gathersNothingWhereACloneDied(GameTestHelper h) {
        for (int y = 2; y <= 4; y++) {
            h.setBlock(new BlockPos(12, y, 3), Blocks.OAK_LOG); // by the death spot
            h.setBlock(new BlockPos(2, y, 12), Blocks.OAK_LOG); // far from it
        }
        com.rlclones.ai.Persona.forgetDeaths();
        com.rlclones.ai.Persona.noteDeath(h.absolutePos(new BlockPos(12, 2, 4)));
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.controller().forcedOption = opt("GATHER_WOOD");
        c.setAiEnabled(true);
        h.succeedWhen(() -> {
            h.assertTrue(h.getBlockState(new BlockPos(2, 2, 12)).isAir() || h.getBlockState(new BlockPos(2, 3, 12)).isAir(), "the far tree was felled (" + c.controller().harvestDebug + " " + c.controller().optionLog + " at " + c.blockPosition().subtract(h.absolutePos(new BlockPos(0, 0, 0))).toShortString() + ")");
            h.assertTrue(h.getBlockState(new BlockPos(12, 2, 3)).is(Blocks.OAK_LOG) && h.getBlockState(new BlockPos(12, 3, 3)).is(Blocks.OAK_LOG), "the tree by the death spot stands");
            com.rlclones.ai.Persona.forgetDeaths();
            finish(h, c);
        });
    }

    /** R-21: no coal, logs to spare: charcoal from the surplus, then the iron is smelted with it. */
    @GameTest(template = ARENA, timeoutTicks = 9000, batch = "r11goods")
    public static void makesCharcoalFromSpareLogs(GameTestHelper h) {
        ClonePlayer c = clone(h, 7.5, 7.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.FURNACE));
        c.getInventory().add(new ItemStack(Items.OAK_LOG, 48));
        c.getInventory().add(new ItemStack(Items.OAK_PLANKS, 8));
        c.getInventory().add(new ItemStack(Items.RAW_IRON, 3));
        h.onEachTick(() -> {
            c.controller().crafting().tick();
            c.controller().motor().tick();
        });
        h.succeedWhen(() -> {
            h.assertTrue(c.getInventory().countItem(Items.RAW_IRON) == 0, "the iron went into the furnace with the logs as its only fuel (raw " + c.getInventory().countItem(Items.RAW_IRON) + " " + c.controller().crafting().smeltTrace() + ")");
            finish(h, c);
        });
    }

    /**
     * Informational (never fails the build): clones at a forest with nothing in their hands, left alone for half an
     * hour of game time. Logs when each one first reaches each step of the way to full diamond gear ("SOAK ..." lines).
     */
    @GameTest(template = ARENA, timeoutTicks = SOAK_MAX_TICKS + 600, batch = "zsoak", required = false)
    public static void soakHowFarCloneGetsOnItsOwn(GameTestHelper h) {
        if (!soakWanted()) {
            h.succeed();
            return;
        }
        var level = h.getLevel();
        var spawning = level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING);
        if (spawning.get()) {
            spawning.set(false, level.getServer()); // (the other tests rely on dark arenas staying free of monsters)
        }
        final int total = soakTicks();
        BlockPos site = soakSite(h);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int x = site.getX() + (i % 3) * 3;
            int z = site.getZ() + (i / 3) * 3;
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            ClonePlayer c = manager(h).summon(null, level, new Vec3(x + 0.5, y, z + 0.5), i * 60f);
            if (c == null) {
                continue;
            }
            for (String hazard : List.of("minecraft:lava", "minecraft:fire", "minecraft:soul_fire", "minecraft:magma_block", "minecraft:cactus")) {
                for (int k = 0; k < 3; k++) {
                    c.getCloneBrain().learnHarmful(hazard); // as if the player had taught them with the L key (a new clone alone learns lava only by dying)
                }
            }
            c.setAiEnabled(true);
            cleanUpOnFailure(h, c);
            names.add(c.getGameProfile().getName());
        }
        RLClones.LOGGER.info("SOAK start at {} with {} clones, {} ticks", site.toShortString(), names.size(), total);
        java.util.Map<String, java.util.Map<String, Integer>> first = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> gone = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> opts = new java.util.TreeMap<>();
        java.util.Map<String, Integer> nones = new java.util.TreeMap<>();
        int[] tick = {0};
        h.onEachTick(() -> {
            tick[0]++;
            try {
                if (tick[0] % 1200 == 0) {
                    int k = 0;
                    for (String n : names) {
                        ClonePlayer live = manager(h).byName(n);
                        if (live != null && live.isAlive()) {
                            soakAnimal(level, live, tick[0] / 1200 + k++);
                        }
                    }
                }
                if (tick[0] % 200 == 0) {
                    for (String n : names) {
                        ClonePlayer live = manager(h).byName(n);
                        if (live == null || !live.isAlive()) {
                            gone.putIfAbsent(n, tick[0]);
                            continue;
                        }
                        var m = first.computeIfAbsent(n, k -> new java.util.LinkedHashMap<>());
                        for (String step : soakReached(live)) {
                            m.putIfAbsent(step, tick[0]);
                        }
                        var cc = live.controller();
                        var o = cc.option();
                        opts.merge(o == null ? "none" : o.name(), 1, Integer::sum);
                        if (o == null) {
                            nones.merge(live.level().getGameTime() - cc.idleWhyTick > 2 ? "skipped" : cc.idleWhy.equals("strategy")
                                    ? "strategy:" + cc.endedOption + (cc.endedAfter <= 1 ? "<=1" : cc.endedAfter < 20 ? "<20" : "long") : cc.idleWhy, 1, Integer::sum);
                        }
                        if (tick[0] % 2400 == 0) {
                            RLClones.LOGGER.info("SOAK-TRACE t={} {} opt={} {} | {} | esc={}[{}] cd={} dig={} stairs={}/{}/{} shaft={}/{} | {}", tick[0], n, o, Progression.describe(live), cc.crafting().trace(),
                                    cc.escapeStarts, cc.escapeWhy, cc.churnCooldowns, cc.digDrives, cc.stairs().stepsDug + "g" + cc.stairs().giveUps + "a" + cc.stairs().abandoned, cc.stairs().debug,
                                    cc.stairs().recent,
                                    cc.shafts().levelsDug, cc.shafts().debug, soakBag(live));
                        }
                    }
                }
                if (tick[0] % 3000 == 0 || tick[0] == total) {
                    StringBuilder sb = new StringBuilder("SOAK t=" + tick[0] + " gone=" + gone.keySet() + " reached:");
                    for (String step : SOAK_STEPS) {
                        int n = 0;
                        for (var m : first.values()) {
                            n += m.containsKey(step) ? 1 : 0;
                        }
                        sb.append(' ').append(step).append('=').append(n);
                    }
                    RLClones.LOGGER.info(sb.toString());
                }
                if (tick[0] == total) {
                    for (String n : names) {
                        var m = first.getOrDefault(n, java.util.Map.of());
                        ClonePlayer live = manager(h).byName(n);
                        RLClones.LOGGER.info("SOAK-CLONE {} steps={} gone@{} | {}", n, m, gone.get(n), live == null ? "-" : soakBag(live) + " | " + Progression.describe(live)
                                + " stairs=" + live.controller().stairs().stepsDug + "g" + live.controller().stairs().giveUps + "a" + live.controller().stairs().abandoned
                                + " tunnel=" + live.controller().stairs().tunnelDug + " esc=" + live.controller().escapeStarts + " cd=" + live.controller().churnCooldowns
                                + " dig=" + live.controller().digDrives + " | " + live.controller().stairs().debug + " | " + live.controller().crafting().smeltTrace()
                                + " store=" + live.controller().storage().deposits + "/" + live.controller().storage().withdrawals + " food=" + com.rlclones.ai.FoodAid.foodItems(live));
                    }
                    RLClones.LOGGER.info("SOAK-OPTIONS (samples every 200 ticks) {}", opts);
                    RLClones.LOGGER.info("SOAK-NONE (what the clone was busy with when no option ran) {}", nones);
                    int full = 0;
                    for (var m : first.values()) {
                        full += m.containsKey("diamond_armor_4") ? 1 : 0;
                    }
                    java.util.Map<String, Integer> reached = new java.util.LinkedHashMap<>();
                    for (String step : SOAK_STEPS) {
                        int n = 0;
                        for (var m : first.values()) {
                            n += m.containsKey(step) ? 1 : 0;
                        }
                        reached.put(step, n);
                    }
                    RLClones.LOGGER.info("SOAK-SUMMARY ticks={} clones={} gone={} full_diamond_armor={}/{} errors={} reached={}", total, names.size(), gone.size(), full, names.size(),
                            CloneManager.errors(), reached);
                    for (String n : names) {
                        ClonePlayer live = manager(h).byName(n);
                        if (live != null) {
                            manager(h).remove(live, true, Component.literal("soak finished"));
                        }
                    }
                    h.succeed();
                }
            } catch (RuntimeException e) {
                RLClones.LOGGER.warn("SOAK sampling failed", e);
            }
        });
    }
}
