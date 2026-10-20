package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * A monster spawner in a cave: torches go on and round it (monsters will not spawn in the light), and if it keeps on spawning in spite of
 * them (blazes, say, do not mind the light) it is broken with a pickaxe. Only an armed clone in good health goes up to one, and only when nothing is
 * after it; the spawners dealt with are remembered for everyone (a hundred clones do not torch the same cage a hundred times).
 * ponytail: one look round every 80 ticks within 12 blocks, underground only; no map of the dungeon, no fighting the cage's mobs on purpose (the
 * fight option does that when they come); add a ranking if cages with many spawners matter.
 */
public final class Spawners {
    private enum Stage {IDLE, APPROACH, TORCH, WATCH, BREAK}

    /** Spawners already torched or broken (positions), whoever did it. */
    private static final Set<Long> HANDLED = new HashSet<>();

    private final ClonePlayer self;
    private final Motor motor;
    private Stage stage = Stage.IDLE;
    private BlockPos spawner;
    private int ticks;
    private long watchUntil;
    private long cooldownUntil;
    /** Torches put on spawners / spawners broken (tests, soak). */
    public int torches;
    public int broken;
    public String debug = "";

    public Spawners(ClonePlayer self, Motor motor) {
        this.self = self;
        this.motor = motor;
    }

    /** For tests: forget which spawners were dealt with. */
    public static void forgetAll() {
        HANDLED.clear();
    }

    private ServerLevel level() {
        return self.serverLevel();
    }

    private int slotOf(Item item) {
        for (int i = 0; i < self.getInventory().items.size(); i++) {
            if (self.getInventory().items.get(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    private boolean fit() {
        return Config.get(Config.ALLOW_BLOCK_PLACING, true) && Equipment.isArmed(self) && self.getHealth() >= self.getMaxHealth() * 0.7f
                && self.getFoodData().getFoodLevel() >= 10 && slotOf(Items.TORCH) >= 0;
    }

    @javax.annotation.Nullable
    private BlockPos find() {
        ServerLevel level = level();
        BlockPos c = self.blockPosition();
        if (level.canSeeSky(c)) {
            return null; // out in the open: dungeons are underground
        }
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-12, -5, -12), c.offset(12, 5, 12))) {
            if (level.getBlockState(p).is(Blocks.SPAWNER) && !HANDLED.contains(p.asLong())) {
                return p.immutable();
            }
        }
        return null;
    }

    /** The monsters that came out of it lately and are about it (a spawn counts as young: they have not lived long). */
    private int youngMobsAround() {
        int n = 0;
        for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, new AABB(spawner).inflate(5), x -> x instanceof Monster && x.isAlive() && x.tickCount < 400)) {
            n++;
        }
        return n;
    }

    /** True while busy with a spawner (the caller leaves the clone to it). */
    public boolean tick(long now, boolean threatened) {
        if (stage == Stage.IDLE) {
            if (now < cooldownUntil || threatened || (now + self.getId()) % 80 != 0) {
                return false;
            }
            if (!fit()) {
                debug = "not fit (armed " + Equipment.isArmed(self) + " hp " + self.getHealth() + " food " + self.getFoodData().getFoodLevel() + " torch " + slotOf(Items.TORCH) + ")";
                return false;
            }
            spawner = find();
            if (spawner == null) {
                debug = "none found (sky " + level().canSeeSky(self.blockPosition()) + ")";
                return false;
            }
            stage = Stage.APPROACH;
            ticks = 0;
            debug = "going to the spawner at " + spawner.toShortString();
        }
        if (!level().getBlockState(spawner).is(Blocks.SPAWNER)) {
            stage = Stage.IDLE; // gone (someone broke it)
            return false;
        }
        if (threatened || ++ticks > 900 || !fit() && stage != Stage.BREAK) {
            debug += " (gave up: " + (threatened ? "threat" : "time/fitness") + ")";
            stage = Stage.IDLE;
            cooldownUntil = now + 2400;
            return false;
        }
        Vec3 c = Vec3.atCenterOf(spawner);
        switch (stage) {
            case APPROACH -> {
                if (self.getEyePosition().distanceTo(c) > 3.5) {
                    motor.navigate(c, 2.5, false);
                    if (motor.stuckCount() > 6) {
                        stage = Stage.IDLE;
                        cooldownUntil = now + 2400;
                        return false;
                    }
                    return true;
                }
                stage = Stage.TORCH;
            }
            case TORCH -> {
                motor.stop();
                int slot = slotOf(Items.TORCH);
                if (slot < 0 || ticks % 6 != 0) {
                    return true;
                }
                Equipment.select(self, slot);
                boolean placed = false;
                for (Direction d : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                    BlockPos cell = spawner.relative(d);
                    if (level().getBlockState(cell).isAir() && motor.placeBlockAt(cell)) {
                        torches++;
                        placed = true;
                        break;
                    }
                }
                if (!placed && ticks > 60 || torches >= 4 && level().getBrightness(LightLayer.BLOCK, spawner.above()) >= 8) {
                    stage = Stage.WATCH;
                    watchUntil = now + 240;
                }
                return true;
            }
            case WATCH -> {
                // a little way back: what comes out in spite of the light? (spawns younger than 20 s about the cage)
                if (self.getEyePosition().distanceTo(c) < 5) {
                    motor.navigate(c.add(0, 0, 6), 1.0, false);
                }
                if (now < watchUntil) {
                    return true;
                }
                if (youngMobsAround() >= 2 && slotOf(Items.TORCH) >= 0) {
                    debug = "still spawning in the light: broken";
                    stage = Stage.BREAK;
                    ticks = 0;
                } else {
                    HANDLED.add(spawner.asLong());
                    stage = Stage.IDLE;
                    cooldownUntil = now + 1200;
                    return false;
                }
            }
            case BREAK -> {
                if (Equipment.bestToolSlot(self, level().getBlockState(spawner)) < 0) {
                    HANDLED.add(spawner.asLong());
                    stage = Stage.IDLE;
                    return false;
                }
                if (self.getEyePosition().distanceTo(c) > Motor.BLOCK_REACH - 0.5) {
                    motor.navigate(c, 2.0, false);
                    return true;
                }
                Equipment.select(self, Equipment.bestToolSlot(self, level().getBlockState(spawner)));
                if (motor.mine(spawner)) {
                    broken++;
                    HANDLED.add(spawner.asLong());
                    stage = Stage.IDLE;
                    return false;
                }
                return true;
            }
            default -> {
            }
        }
        return true;
    }
}
