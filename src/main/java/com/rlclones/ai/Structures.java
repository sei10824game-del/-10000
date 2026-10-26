package com.rlclones.ai;

import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * R-20 / R-32: generated structures (villages, mineshafts, ruins, mod structures) seen on the way. A chest in sight that
 * lies inside a generated structure is noted as a site (and shared: "STRUCT x y z"); EXPLORE then heads for the nearest
 * site not visited yet, and the chests there are left to LOOT as usual. ponytail: found by a chest in sight only,
 * add other "artificial" blocks (doors, beds, spawners) when villages without chests in sight are missed.
 */
public final class Structures {
    private static final int RANGE = 128;
    private static final int VISIT = 10;

    private static final class Site {
        final BlockPos pos;
        boolean visited;

        Site(BlockPos pos) {
            this.pos = pos;
        }
    }

    private final ClonePlayer self;
    private final List<Site> sites = new ArrayList<>();
    /** Sites noted / visited (diagnostics, tests). */
    public int found;
    public int visits;
    /** A village is known (seen by this clone or told of by a friend). */
    public boolean villageKnown;
    private boolean villageSaid;
    /** Ores (iron, diamond) a friend or this clone saw and has not got: where to go digging (max 24). */
    private final List<BlockPos> oreTips = new ArrayList<>();
    /** What any clone has found (overworld): iron / diamond ore tips, caves, villages - for those born or reborn later, and for friends out of earshot. Memory only. */
    private static final List<BlockPos> WORLD_ORES = new ArrayList<>();
    private static final List<BlockPos> WORLD_CAVES = new ArrayList<>();
    private static final List<BlockPos> WORLD_VILLAGES = new ArrayList<>();

    private static net.minecraft.server.MinecraftServer loadedFor;
    private static int savedHash;

    private static java.nio.file.Path mapFile(net.minecraft.server.MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("rlclones_map.txt");
    }

    /** The shared map from the world folder (once per run of the server). */
    private static void loadWorld(net.minecraft.server.MinecraftServer server) {
        if (loadedFor == server) {
            return;
        }
        loadedFor = server; // (another world in the same run: its own map)
        WORLD_ORES.clear();
        WORLD_CAVES.clear();
        WORLD_VILLAGES.clear();
        try {
            for (String line : java.nio.file.Files.readAllLines(mapFile(server))) {
                String[] w = line.trim().split(" ");
                if (w.length == 4) {
                    BlockPos p = new BlockPos(Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3]));
                    switch (w[0]) {
                        case "ore" -> remember(WORLD_ORES, p, 12, 64);
                        case "cave" -> remember(WORLD_CAVES, p, 24, 32);
                        case "village" -> remember(WORLD_VILLAGES, p, 64, 8);
                        default -> {
                        }
                    }
                }
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // no map yet, or a line that is not one: start from what is found now
        }
        savedHash = WORLD_ORES.hashCode() * 31 + WORLD_CAVES.hashCode() * 17 + WORLD_VILLAGES.hashCode();
    }

    /** Writes the shared map when it has changed (the manager calls this now and then). */
    public static void saveWorld(net.minecraft.server.MinecraftServer server) {
        int h = WORLD_ORES.hashCode() * 31 + WORLD_CAVES.hashCode() * 17 + WORLD_VILLAGES.hashCode();
        if (loadedFor != server || h == savedHash) {
            return;
        }
        savedHash = h;
        List<String> lines = new ArrayList<>();
        for (BlockPos p : WORLD_ORES) {
            lines.add("ore " + p.getX() + " " + p.getY() + " " + p.getZ());
        }
        for (BlockPos p : WORLD_CAVES) {
            lines.add("cave " + p.getX() + " " + p.getY() + " " + p.getZ());
        }
        for (BlockPos p : WORLD_VILLAGES) {
            lines.add("village " + p.getX() + " " + p.getY() + " " + p.getZ());
        }
        try {
            java.nio.file.Files.write(mapFile(server), lines);
        } catch (java.io.IOException ignored) {
            // a read-only world folder: the map stays in memory
        }
    }

    private static void remember(List<BlockPos> list, BlockPos p, int near, int cap) {
        for (BlockPos q : list) {
            if (q.distManhattan(p) <= near) {
                return;
            }
        }
        if (list.size() >= cap) {
            list.remove(0);
        }
        list.add(p.immutable());
    }
    private long lastOreSaid;
    private BlockPos villagePos;
    /** Caves seen or told of (max 16). */
    private final List<BlockPos> caves = new ArrayList<>();
    /** The height a diamond ore was last seen or told of at (the dig for diamonds goes down to it): MIN_VALUE while none was. */
    private int diamondY = Integer.MIN_VALUE;
    public int oreTipsGiven;
    public int oreTipsGot;

    public Structures(ClonePlayer self) {
        if (self.getServer() != null) {
            loadWorld(self.getServer());
        }
        this.self = self;
    }

    private boolean known(BlockPos p) {
        for (Site s : sites) {
            if (s.pos.distManhattan(p) <= 48) {
                return true;
            }
        }
        return false;
    }

    private boolean add(BlockPos p) {
        if (known(p) || sites.size() >= 32) {
            return false;
        }
        sites.add(new Site(p.immutable()));
        found++;
        return true;
    }

    /** Called every tick; looks every two seconds. */
    public void tick(long now, Perception perception) {
        if ((now + self.getId()) % 40 != 0) {
            return;
        }
        ServerLevel level = self.serverLevel();
        for (var e : perception.blocks().entrySet()) {
            BlockPos p = e.getKey();
            if (e.getValue() == Perception.BlockKind.CHEST && p.distSqr(self.blockPosition()) < 48 * 48 && !known(p)
                    && !level.structureManager().getAllStructuresAt(p).isEmpty() && add(p)) {
                Chat.say(self, Component.literal("STRUCT"), "STRUCT " + p.getX() + " " + p.getY() + " " + p.getZ());
            }
        }
        if (!villageSaid) {
            for (net.minecraft.world.entity.npc.Villager v : level.getEntitiesOfClass(net.minecraft.world.entity.npc.Villager.class, self.getBoundingBox().inflate(32), net.minecraft.world.entity.LivingEntity::isAlive)) {
                if (perception.canSee(v)) { // seen with its own eyes, not just near
                    BlockPos p = v.blockPosition();
                    villageSaid = true;
                    villageKnown = true;
                    villagePos = p.immutable();
                    if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                        remember(WORLD_VILLAGES, p, 64, 8);
                    }
                    add(p);
                    Chat.say(self, Component.translatable("rlclones.chat.village", p.getX(), p.getZ()), "VILLAGE " + p.getX() + " " + p.getY() + " " + p.getZ());
                    break;
                }
            }
        }
        if (now - lastOreSaid > 400) {
            for (var e : perception.blocks().entrySet()) {
                if (e.getValue() != Perception.BlockKind.ORE || e.getKey().distSqr(self.blockPosition()) > 24 * 24) {
                    continue;
                }
                var st = level.getBlockState(e.getKey());
                boolean diamond = st.is(net.minecraft.tags.BlockTags.DIAMOND_ORES);
                if ((diamond || st.is(net.minecraft.tags.BlockTags.IRON_ORES)) && !tipNear(e.getKey())) {
                    BlockPos p = e.getKey();
                    oreTips.add(p.immutable());
                    if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                        remember(WORLD_ORES, p, 12, 64);
                    }
                    lastOreSaid = now;
                    oreTipsGiven++;
                    if (diamond) {
                        noteDiamondY(p.getY());
                    }
                    Chat.say(self, Component.translatable("rlclones.chat.ore", p.getX(), p.getY(), p.getZ()), "ORE " + p.getX() + " " + p.getY() + " " + p.getZ() + (diamond ? " d" : " i"));
                    break;
                }
            }
        }
        oreTips.removeIf(t -> Motor.horizontalDistance(self.position(), Vec3.atCenterOf(t)) < 6 && Math.abs(self.getY() - t.getY()) < 8);
        WORLD_ORES.removeIf(t -> Motor.horizontalDistance(self.position(), Vec3.atCenterOf(t)) < 6 && Math.abs(self.getY() - t.getY()) < 8);
        for (Site s : sites) {
            if (!s.visited && Motor.horizontalDistance(self.position(), Vec3.atCenterOf(s.pos)) < VISIT && Math.abs(self.getY() - s.pos.getY()) < 12) {
                s.visited = true;
                visits++;
            }
        }
    }

    /** "STRUCT x y z" from a friend. */
    private void noteDiamondY(int y) {
        if (y >= -64 && y <= 16) {
            diamondY = y;
        }
    }

    public int diamondY() {
        return diamondY;
    }

    private boolean tipNear(BlockPos p) {
        for (BlockPos t : oreTips) {
            if (t.distManhattan(p) <= 12) {
                return true;
            }
        }
        return false;
    }

    /** The nearest ore tip within 128 blocks (iron / diamond to dig for), or null. */
    @Nullable
    public Vec3 oreTip() {
        BlockPos best = null;
        double bestD = 128 * 128;
        for (BlockPos t : oreTips) {
            double d = t.distSqr(self.blockPosition());
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        if (best == null && self.level().dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            for (BlockPos t : WORLD_ORES) { // nobody told this one: the world's map
                double d = t.distSqr(self.blockPosition());
                if (d < bestD) {
                    bestD = d;
                    best = t;
                }
            }
        }
        return best == null ? null : Vec3.atBottomCenterOf(best);
    }

    /** A cave found: remembered; true if it is new (the caller tells the others). */
    public boolean noteCave(BlockPos p) {
        for (BlockPos c : caves) {
            if (c.distManhattan(p) <= 24) {
                return false;
            }
        }
        if (caves.size() >= 16) {
            caves.remove(0);
        }
        caves.add(p.immutable());
        if (self.level().dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            remember(WORLD_CAVES, p, 24, 32);
        }
        return true;
    }

    /** The nearest village this clone knows of (its own or the world's), or null. */
    @Nullable
    public Vec3 villageSpot() {
        villageToVisit(); // (takes over what a friend found)
        return villagePos == null ? null : Vec3.atBottomCenterOf(villagePos);
    }

    /** A known village 40-160 blocks away that the clone has not been near yet (the place to put a home), or null. */
    @Nullable
    public Vec3 villageToVisit() {
        if (villagePos == null && !WORLD_VILLAGES.isEmpty() && self.level().dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            villagePos = WORLD_VILLAGES.get(WORLD_VILLAGES.size() - 1); // one a friend found: this one knows of it too
            villageKnown = true;
        }
        if (villagePos == null) {
            return null;
        }
        double d = Math.sqrt(villagePos.distSqr(self.blockPosition()));
        return d > 40 && d < 160 ? Vec3.atBottomCenterOf(villagePos) : null;
    }

    /** The nearest known cave within 128 blocks, or null. */
    @Nullable
    public Vec3 caveTarget() {
        BlockPos best = null;
        double bestD = 128 * 128;
        for (BlockPos c : caves) {
            double d = c.distSqr(self.blockPosition());
            if (d > 10 * 10 && d < bestD) {
                bestD = d;
                best = c;
            }
        }
        if (best == null && self.level().dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            for (BlockPos c : WORLD_CAVES) {
                double d = c.distSqr(self.blockPosition());
                if (d > 10 * 10 && d < bestD) {
                    bestD = d;
                    best = c;
                }
            }
        }
        return best == null ? null : Vec3.atBottomCenterOf(best);
    }

    public boolean onChat(String text) {
        if (text.startsWith("ORE ") || text.startsWith("CAVE ")) {
            String[] p = text.split(" ");
            try {
                BlockPos at = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                if (p[0].equals("CAVE")) {
                    noteCave(at);
                } else if (!tipNear(at) && oreTips.size() < 24) {
                    oreTips.add(at);
                    oreTipsGot++;
                    if (p.length > 4 && p[4].equals("d")) {
                        noteDiamondY(at.getY());
                    }
                }
            } catch (RuntimeException ignored) {
            }
            return true;
        }
        if (text.startsWith("VILLAGE ")) {
            try {
                String[] vp = text.split(" ");
                villagePos = new BlockPos(Integer.parseInt(vp[1]), Integer.parseInt(vp[2]), Integer.parseInt(vp[3]));
            } catch (RuntimeException ignored) {
            }
            villageKnown = true; // a friend found one: it is a place to visit (and no trip to look for one)
            villageSaid = true; // (the finder has said it: not again)
            return onChat("STRUCT " + text.substring(8));
        }
        if (!text.startsWith("STRUCT ")) {
            return false;
        }
        String[] p = text.split(" ");
        try {
            add(new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
        } catch (RuntimeException ignored) {
        }
        return true;
    }

    /** The nearest site not visited yet, within 128 blocks. */
    @Nullable
    public Vec3 target() {
        Site best = null;
        double bestD = RANGE * RANGE;
        for (Site s : sites) {
            double d = s.pos.distSqr(self.blockPosition());
            if (!s.visited && d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best == null ? null : Vec3.atBottomCenterOf(best.pos);
    }

    /** Test hook. */
    public void note(BlockPos p) {
        add(p);
    }
}
