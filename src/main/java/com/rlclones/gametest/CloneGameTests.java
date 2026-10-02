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
                    // world-wide things a failed test may leave behind: no staircase to lure other tests' clones away
                    com.rlclones.clone.Bases b = com.rlclones.clone.Bases.get(h.getLevel().getServer());
                    b.staircases.clear();
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
            h.assertTrue(work[0], "soil next to water + hoe + seeds = work");
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
            h.assertTrue(c.position().distanceTo(goal) < 2.0 && !c.isPassenger(), "and get out on the other side");
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
        for (int x = 5; x <= 9; x++) {
            for (int z = 9; z <= 12; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                h.setBlock(new BlockPos(x, 1, z), Blocks.WATER);
            }
        }
        ClonePlayer c = clone(h, 7.5, 6.5, 0f, false);
        c.getInventory().add(new ItemStack(Items.FISHING_ROD));
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
        h.runAfterDelay(10, () -> wake(h, c)); // once it has had a look at who is where
        c.getInventory().add(new ItemStack(Items.BOW));
        c.getInventory().add(new ItemStack(Items.ARROW, 64));
        c.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 4));
        c.controller().forcedAction = com.rlclones.ai.combat.CombatAction.SHOOT;
        c.controller().forcedOption = com.rlclones.ai.strategy.Option.FIGHT;
        ClonePlayer friend = clone(h, 6.5, 7.5, 90f, false);
        Husk husk = dummy(h, 11.5, 7.5);
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
                    + c.controller().shootDebug + " options " + c.controller().optionLog + ")");
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
        ClonePlayer c = clone(h, 4.5, 7.5, 90f, true);
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
            h.assertTrue(c.getInventory().countItem(Items.OBSIDIAN) >= 1, "water on the lava source makes obsidian, mined with the diamond pickaxe ("
                    + c.controller().portals().debug + " mine=" + c.controller().motor().mineDebug + ")");
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
        for (int x = 5; x <= 10; x++) {
            for (int z = 1; z <= 13; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.OBSIDIAN);
                h.setBlock(new BlockPos(x, 1, z), Blocks.LAVA);
            }
        }
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
        h.succeedWhen(() -> {
            h.assertTrue(c.controller().consumables().travelPearls >= 1, "a pearl thrown across (" + c.controller().consumables().pearlDebug + " travel "
                    + c.controller().travel().debug + ")");
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
            h.assertTrue(pen.contains(cow1.position()) && pen.contains(cow2.position()), "both cows led in with the wheat (lured "
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
                    + c.controller().achievements().debug + " " + c.controller().optionLog + ")");
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
}
