package com.rlclones.clone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared, persistent map of generated structures and clusters of artificial blocks noticed by clones.
 * A site is not considered an exploration target until it is a world-generation structure or enough distinct
 * artificial blocks have been seen together to look like a building rather than a lone placed block.
 */
public final class StructureMemory extends net.minecraft.world.level.saveddata.SavedData {
    public static final String NAME = "rlclones_structures";
    public static final String PLAYER_BUILDING = "minecraft:player_building";
    private static final int MAX_SITES = 512;
    private static final int MAX_EVIDENCE = 128;
    private static final int MAX_WAYPOINTS = 64;

    public static final class Site {
        public final String key;
        public final ResourceKey<Level> dimension;
        public final String id;
        private BlockPos min;
        private BlockPos max;
        @Nullable
        private BlockPos entrance;
        private final List<BlockPos> chests = new ArrayList<>();
        private final List<BlockPos> evidence = new ArrayList<>();
        private final List<BlockPos> visited = new ArrayList<>();
        public boolean generated;
        public boolean explored;
        public boolean dangerous;
        public boolean announced;
        public long lastSeen;

        private Site(String key, ResourceKey<Level> dimension, String id, BlockPos min, BlockPos max, boolean generated, long now) {
            this.key = key;
            this.dimension = dimension;
            this.id = id;
            this.min = min.immutable();
            this.max = max.immutable();
            this.generated = generated;
            this.lastSeen = now;
        }

        public BlockPos min() {
            return min;
        }

        public BlockPos max() {
            return max;
        }

        @Nullable
        public BlockPos entrance() {
            return entrance;
        }

        public List<BlockPos> chests() {
            return List.copyOf(chests);
        }

        public int evidenceCount() {
            return evidence.size();
        }

        public boolean confirmed() {
            return generated || evidence.size() >= 3;
        }

        public BlockPos center() {
            return new BlockPos((min.getX() + max.getX()) / 2, min.getY(), (min.getZ() + max.getZ()) / 2);
        }

        public boolean contains(BlockPos pos) {
            return pos.getX() >= min.getX() - 2 && pos.getX() <= max.getX() + 2
                    && pos.getY() >= min.getY() - 2 && pos.getY() <= max.getY() + 2
                    && pos.getZ() >= min.getZ() - 2 && pos.getZ() <= max.getZ() + 2;
        }

        private void include(BlockPos pos) {
            min = new BlockPos(Math.min(min.getX(), pos.getX()), Math.min(min.getY(), pos.getY()), Math.min(min.getZ(), pos.getZ()));
            max = new BlockPos(Math.max(max.getX(), pos.getX()), Math.max(max.getY(), pos.getY()), Math.max(max.getZ(), pos.getZ()));
        }

        private boolean remember(BlockPos pos, boolean door, boolean chest, boolean spawner) {
            boolean changed = false;
            if (evidence.size() < MAX_EVIDENCE && !evidence.contains(pos)) {
                evidence.add(pos.immutable());
                changed = true;
            }
            if (door && (entrance == null || pos.getY() < entrance.getY())) {
                entrance = pos.immutable();
                changed = true;
            }
            if (chest && chests.size() < MAX_WAYPOINTS && !chests.contains(pos)) {
                chests.add(pos.immutable());
                changed = true;
            }
            if (spawner && !dangerous) {
                dangerous = true;
                changed = true;
            }
            if (pos.getX() < min.getX() || pos.getY() < min.getY() || pos.getZ() < min.getZ()
                    || pos.getX() > max.getX() || pos.getY() > max.getY() || pos.getZ() > max.getZ()) {
                include(pos);
                changed = true;
            }
            return changed;
        }

        public List<BlockPos> waypoints() {
            List<BlockPos> out = new ArrayList<>();
            if (entrance != null) {
                out.add(entrance);
            }
            out.add(center());
            out.addAll(chests);
            return out;
        }

        public boolean hasVisited(BlockPos waypoint) {
            return visited.contains(waypoint);
        }

        private boolean visit(BlockPos waypoint) {
            if (visited.contains(waypoint)) {
                return false;
            }
            visited.add(waypoint.immutable());
            if (waypoints().stream().allMatch(visited::contains)) {
                explored = true;
            }
            return true;
        }
    }

    private final List<Site> sites = new ArrayList<>();

    public static StructureMemory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(StructureMemory::load, StructureMemory::new, NAME);
    }

    public List<Site> sites() {
        return List.copyOf(sites);
    }

    public void clear() {
        sites.clear();
        setDirty();
    }

    /**
     * Observe a block seen by a clone. Querying structure starts first distinguishes a generated village/mineshaft/etc.
     * from a player/mod-built cluster; both are remembered, but the latter needs several independent observations.
     * Returns the site, if this block is evidence for one.
     */
    @Nullable
    public Site observe(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state, long now) {
        var at = level.structureManager().getAllStructuresAt(pos);
        if (!at.isEmpty()) {
            Site result = null;
            var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            for (var entry : at.entrySet()) {
                Structure structure = entry.getKey();
                StructureStart start = entry.getValue();
                ResourceLocation id = registry.getKey(structure);
                if (id == null || start == null || !start.isValid()) {
                    continue;
                }
                BoundingBox box = start.getBoundingBox();
                Site site = findGenerated(level.dimension(), id.toString(), box);
                if (site == null) {
                    BlockPos min = new BlockPos(box.minX(), box.minY(), box.minZ());
                    BlockPos max = new BlockPos(box.maxX(), box.maxY(), box.maxZ());
                    site = new Site(generatedKey(level.dimension(), id.toString(), min), level.dimension(), id.toString(), min, max, true, now);
                    add(site);
                }
                site.lastSeen = now;
                if (site.remember(pos, state.getBlock() instanceof DoorBlock, isChest(state), state.is(Blocks.SPAWNER))) {
                    setDirty();
                }
                result = site;
            }
            return result;
        }
        if (!artificial(level, pos, state)) {
            return null;
        }
        if (Bases.get(level.getServer()).nearest(level.dimension(), Vec3.atCenterOf(pos), 24) != null) {
            return null; // our own base is already known; do not make it a new exploration destination
        }
        Site site = findNearbyManual(level.dimension(), pos);
        if (site == null) {
            String key = manualKey(level.dimension(), pos);
            site = new Site(key, level.dimension(), PLAYER_BUILDING, pos, pos, false, now);
            add(site);
        }
        site.lastSeen = now;
        if (site.remember(pos, state.getBlock() instanceof DoorBlock, isChest(state), state.is(Blocks.SPAWNER))) {
            setDirty();
        }
        return site;
    }

    /** Merge a machine-readable STRUCT report from another clone into shared world memory. */
    public void rememberReported(ServerLevel level, String id, BlockPos pos, long now) {
        if (id.equals(PLAYER_BUILDING)) {
            Site site = findNearbyManual(level.dimension(), pos);
            if (site == null) {
                site = new Site(manualKey(level.dimension(), pos), level.dimension(), id, pos, pos, false, now);
                add(site);
            }
            site.lastSeen = now;
            // The report is a second clone's confirmation that this is a real cluster.
            if (site.evidence.size() < 3) {
                site.evidence.add(pos.immutable());
            }
            setDirty();
            return;
        }
        var at = level.structureManager().getAllStructuresAt(pos);
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (var entry : at.entrySet()) {
            ResourceLocation key = registry.getKey(entry.getKey());
            if (key != null && key.toString().equals(id) && entry.getValue().isValid()) {
                BoundingBox box = entry.getValue().getBoundingBox();
                Site site = findGenerated(level.dimension(), id, box);
                if (site == null) {
                    BlockPos min = new BlockPos(box.minX(), box.minY(), box.minZ());
                    BlockPos max = new BlockPos(box.maxX(), box.maxY(), box.maxZ());
                    site = new Site(generatedKey(level.dimension(), id, min), level.dimension(), id, min, max, true, now);
                    add(site);
                }
                site.lastSeen = now;
                setDirty();
                return;
            }
        }
    }

    public boolean isDangerousAt(ResourceKey<Level> dimension, BlockPos pos) {
        for (Site site : sites) {
            if (site.dimension == dimension && site.dangerous && site.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /** Whether this chest belongs to a remembered building/structure. */
    public boolean isKnownChest(ResourceKey<Level> dimension, BlockPos pos) {
        for (Site site : sites) {
            if (site.dimension == dimension && site.confirmed() && site.chests.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public Site nearestUnexplored(ResourceKey<Level> dimension, Vec3 from, double radius) {
        Site best = null;
        double bestDistance = radius;
        for (Site site : sites) {
            if (site.dimension != dimension || site.explored || !site.confirmed()) {
                continue;
            }
            double d = Vec3.atCenterOf(site.center()).distanceTo(from);
            if (d < bestDistance) {
                bestDistance = d;
                best = site;
            }
        }
        return best;
    }

    @Nullable
    public BlockPos nextWaypoint(Site site) {
        for (BlockPos waypoint : site.waypoints()) {
            if (!site.hasVisited(waypoint)) {
                return waypoint;
            }
        }
        if (!site.explored) {
            site.explored = true;
            setDirty();
        }
        return null;
    }

    public void visit(Site site, BlockPos waypoint) {
        if (site.visit(waypoint)) {
            setDirty();
        }
    }

    /** A STRUCT line is sent once a candidate has enough evidence to be worth a trip. */
    public boolean markAnnounced(Site site) {
        if (!site.confirmed() || site.announced) {
            return false;
        }
        site.announced = true;
        setDirty();
        return true;
    }

    private static boolean isChest(net.minecraft.world.level.block.state.BlockState state) {
        return state.getBlock() instanceof ChestBlock || state.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock;
    }

    private static boolean artificial(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (state.isAir() || !level.getFluidState(pos).isEmpty() || state.getBlock() instanceof LeavesBlock
                || state.is(net.minecraft.tags.BlockTags.LOGS) || state.getBlock() instanceof net.minecraft.world.level.block.CropBlock) {
            return false;
        }
        if (state.is(net.minecraft.tags.BlockTags.PLANKS) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.GLASS)
                || state.is(net.minecraft.tags.BlockTags.WOOL) || state.is(net.minecraft.tags.BlockTags.FENCES)
                || state.getBlock() instanceof DoorBlock || state.getBlock() instanceof BedBlock || isChest(state)
                || state.is(Blocks.BOOKSHELF) || state.is(Blocks.SPAWNER)) {
            return true;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return key != null && !key.getNamespace().equals("minecraft") && state.getBlock().asItem() != net.minecraft.world.item.Items.AIR
                && !state.is(Tags.Blocks.ORES) && !state.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)
                && !state.getCollisionShape(level, pos).isEmpty();
    }

    private Site findGenerated(ResourceKey<Level> dimension, String id, BoundingBox box) {
        for (Site site : sites) {
            if (site.dimension == dimension && site.generated && site.id.equals(id)
                    && box.minX() == site.min.getX() && box.minY() == site.min.getY() && box.minZ() == site.min.getZ()
                    && box.maxX() == site.max.getX() && box.maxY() == site.max.getY() && box.maxZ() == site.max.getZ()) {
                return site;
            }
        }
        return null;
    }

    @Nullable
    private Site findNearbyManual(ResourceKey<Level> dimension, BlockPos pos) {
        Site best = null;
        int bestDistance = 17;
        for (Site site : sites) {
            if (site.dimension != dimension || site.generated || !site.id.equals(PLAYER_BUILDING)) {
                continue;
            }
            BlockPos center = site.center();
            int distance = Math.abs(center.getX() - pos.getX()) + Math.abs(center.getZ() - pos.getZ());
            if (Math.abs(center.getY() - pos.getY()) <= 6 && distance < bestDistance) {
                bestDistance = distance;
                best = site;
            }
        }
        return best;
    }

    private void add(Site site) {
        sites.add(site);
        if (sites.size() > MAX_SITES) {
            int discard = 0;
            for (int i = 0; i < sites.size(); i++) {
                if (sites.get(i).explored) {
                    discard = i;
                    break;
                }
            }
            sites.remove(discard);
        }
        setDirty();
    }

    private static String generatedKey(ResourceKey<Level> dimension, String id, BlockPos min) {
        return dimension.location() + "|" + id + "|" + min.getX() + "," + min.getY() + "," + min.getZ();
    }

    private static String manualKey(ResourceKey<Level> dimension, BlockPos first) {
        return dimension.location() + "|" + PLAYER_BUILDING + "|" + first.getX() + "," + first.getY() + "," + first.getZ();
    }

    public static StructureMemory load(CompoundTag tag) {
        StructureMemory memory = new StructureMemory();
        ListTag list = tag.getList("sites", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && i < MAX_SITES; i++) {
            CompoundTag t = list.getCompound(i);
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(t.getString("dim")));
            Site site = new Site(t.getString("key"), dimension, t.getString("id"), NbtUtils.readBlockPos(t.getCompound("min")),
                    NbtUtils.readBlockPos(t.getCompound("max")), t.getBoolean("generated"), t.getLong("seen"));
            if (t.contains("entrance")) {
                site.entrance = NbtUtils.readBlockPos(t.getCompound("entrance"));
            }
            readPositions(t.getList("chests", Tag.TAG_COMPOUND), site.chests, MAX_WAYPOINTS);
            readPositions(t.getList("evidence", Tag.TAG_COMPOUND), site.evidence, MAX_EVIDENCE);
            readPositions(t.getList("visited", Tag.TAG_COMPOUND), site.visited, MAX_WAYPOINTS + 2);
            site.explored = t.getBoolean("explored");
            site.dangerous = t.getBoolean("dangerous");
            site.announced = t.getBoolean("announced");
            memory.sites.add(site);
        }
        return memory;
    }

    private static void readPositions(ListTag tags, List<BlockPos> into, int max) {
        for (int i = 0; i < tags.size() && i < max; i++) {
            BlockPos pos = NbtUtils.readBlockPos(tags.getCompound(i));
            if (!into.contains(pos)) {
                into.add(pos);
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Site site : sites) {
            CompoundTag t = new CompoundTag();
            t.putString("key", site.key);
            t.putString("dim", site.dimension.location().toString());
            t.putString("id", site.id);
            t.put("min", NbtUtils.writeBlockPos(site.min));
            t.put("max", NbtUtils.writeBlockPos(site.max));
            if (site.entrance != null) {
                t.put("entrance", NbtUtils.writeBlockPos(site.entrance));
            }
            t.put("chests", writePositions(site.chests));
            t.put("evidence", writePositions(site.evidence));
            t.put("visited", writePositions(site.visited));
            t.putBoolean("generated", site.generated);
            t.putBoolean("explored", site.explored);
            t.putBoolean("dangerous", site.dangerous);
            t.putBoolean("announced", site.announced);
            t.putLong("seen", site.lastSeen);
            list.add(t);
        }
        tag.put("sites", list);
        return tag;
    }

    private static ListTag writePositions(List<BlockPos> positions) {
        ListTag tags = new ListTag();
        for (BlockPos pos : positions) {
            tags.add(NbtUtils.writeBlockPos(pos));
        }
        return tags;
    }
}
