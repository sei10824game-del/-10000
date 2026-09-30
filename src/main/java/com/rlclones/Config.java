package com.rlclones;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue MAX_CLONES;
    public static final ForgeConfigSpec.BooleanValue OPS_ONLY;
    public static final ForgeConfigSpec.IntValue RESPAWN_DELAY;
    public static final ForgeConfigSpec.IntValue DESPAWN_DELAY;

    public static final ForgeConfigSpec.DoubleValue VIEW_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue FOV_HORIZONTAL;
    public static final ForgeConfigSpec.DoubleValue FOV_VERTICAL;
    public static final ForgeConfigSpec.BooleanValue DARKNESS_LIMITS_VISION;

    public static final ForgeConfigSpec.DoubleValue LEARNING_RATE;
    public static final ForgeConfigSpec.DoubleValue DISCOUNT;
    public static final ForgeConfigSpec.DoubleValue EXPLORATION;
    public static final ForgeConfigSpec.DoubleValue IMITATION_WEIGHT;
    public static final ForgeConfigSpec.IntValue REPLAY_UPDATES;

    public static final ForgeConfigSpec.BooleanValue ALLOW_BLOCK_BREAKING;
    public static final ForgeConfigSpec.BooleanValue ALLOW_BLOCK_PLACING;
    public static final ForgeConfigSpec.BooleanValue CLONES_BLOCK_NIGHT_SKIP;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("clones");
        MAX_CLONES = b.comment("Maximum number of clones alive at the same time.").defineInRange("maxClones", 16, 1, 256);
        OPS_ONLY = b.comment("Only operators may summon clones or toggle link / respawn.").define("opsOnly", false);
        RESPAWN_DELAY = b.comment("Ticks a dead clone waits before respawning (when respawn is ON).").defineInRange("respawnDelayTicks", 40, 1, 20 * 60);
        DESPAWN_DELAY = b.comment("Ticks a dead clone lingers before being removed for good (when respawn is OFF). Pressing P during this time still saves it.").defineInRange("despawnDelayTicks", 60, 1, 20 * 60);
        CLONES_BLOCK_NIGHT_SKIP = b.comment("If false, clones are ignored when checking whether enough players sleep to skip the night.").define("clonesBlockNightSkip", false);
        b.pop();

        b.push("vision");
        VIEW_DISTANCE = b.comment("Maximum distance (blocks) a clone can see an entity in full daylight.").defineInRange("viewDistance", 48.0, 4.0, 128.0);
        FOV_HORIZONTAL = b.comment("Horizontal field of view in degrees (a 16:9 screen at FOV 70 is about 102).").defineInRange("fovHorizontal", 102.0, 30.0, 180.0);
        FOV_VERTICAL = b.comment("Vertical field of view in degrees (the vanilla FOV setting).").defineInRange("fovVertical", 70.0, 30.0, 110.0);
        DARKNESS_LIMITS_VISION = b.comment("Dark targets are harder to see (like for a player without night vision).").define("darknessLimitsVision", true);
        b.pop();

        b.push("learning");
        LEARNING_RATE = b.comment("Base Q-learning rate.").defineInRange("learningRate", 0.25, 0.001, 1.0);
        DISCOUNT = b.comment("Discount factor per combat decision.").defineInRange("discount", 0.9, 0.0, 0.999);
        EXPLORATION = b.comment("Initial exploration rate (decays per state as it is visited).").defineInRange("exploration", 0.35, 0.0, 1.0);
        IMITATION_WEIGHT = b.comment("Weight of experience learned by watching other clones / players (1 = same as own experience).").defineInRange("imitationWeight", 0.8, 0.0, 1.0);
        REPLAY_UPDATES = b.comment("Experience-replay updates performed per real learning step.").defineInRange("replayUpdates", 4, 0, 64);
        b.pop();

        b.push("actions");
        ALLOW_BLOCK_BREAKING = b.comment("Clones may break blocks (chop trees, mine ores).").define("allowBlockBreaking", true);
        ALLOW_BLOCK_PLACING = b.comment("Clones may place blocks (pillar up to escape).").define("allowBlockPlacing", true);
        b.pop();

        SPEC = b.build();
    }

    private Config() {
    }

    /** Reads a config value, falling back to a default while the config is not loaded yet. */
    public static double get(ForgeConfigSpec.DoubleValue value, double fallback) {
        try {
            return value.get();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    public static int get(ForgeConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    public static boolean get(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }
}
