package com.rlclones.ai.strategy;

import java.util.Locale;

/** High-level behaviours ("options") chosen by the strategy Q-table. */
public enum Option {
    FIGHT(160),
    FLEE(140),
    EAT(60),
    COLLECT(120),
    FOLLOW(100),
    EXPLORE(200),
    GATHER_WOOD(300),
    MINE(300),
    HUNT(160),
    REST(60),
    CRAFT(400),
    /** Answer a call for help read in chat: go to the reported coordinates. */
    HELP(600),
    /** Put what cannot be used right now into a base chest (building a hut + chest first if there is no base). */
    STORE(4800),
    /** Take something needed back out of a base chest. */
    FETCH(1200),
    /** Rummage through a stray chest (dungeon, village, shipwreck...). */
    LOOT(800),
    /** Till soil next to water, plant seeds, harvest ripe crops. */
    FARM(1200),
    /** Lead a trip into unexplored land: call companions to a rally point, then go. */
    EXPEDITION(12000),
    /** Answer a rally call and follow its leader until the trip is over. */
    JOIN(14000),
    /** Mine stone for cobblestone (stone tools, furnace, brewing stand). */
    QUARRY(400),
    /** Go and get a block never examined before. */
    DISCOVER(600),
    /** Brew potions never had before (or collect a finished brew). */
    BREW(2400),
    /** Tame / breed animals, build a chicken pen, throw eggs into it. */
    ANIMALS(2400),
    /** Fish with a rod at open water. */
    FISH(2400),
    /** No trees anywhere: take planks / logs from things built (never from a base). */
    SALVAGE(400),
    /** Obsidian from lava + water, a Nether portal, and a first trip through it. */
    PORTAL(3000),
    /** Bring food to whoever asked for it in chat (one's own children first). */
    FEED(1800),
    /** N key: two clones with full stomachs and food to spare make a new clone. */
    BREED(1200),
    /** Work towards an advancement not made yet (what unlocks it is read from its criteria). */
    ACHIEVE(1800),
    /** Dig a staircase down for ore - or carry on down one already started. */
    STAIRS(3000),
    /** Dig a shaft straight down with ladders - or carry on down one already started. */
    SHAFT(4000);

    public static final Option[] VALUES = values();
    public static final int COUNT = VALUES.length;

    /** Hard time limit in ticks; the option may also terminate earlier on its own. */
    public final int maxTicks;

    Option(int maxTicks) {
        this.maxTicks = maxTicks;
    }

    public int bit() {
        return 1 << ordinal();
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
