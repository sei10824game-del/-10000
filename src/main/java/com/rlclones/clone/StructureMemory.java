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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared, persistent map of generated structures and clusters of artificial blocks noticed by clones, with short-lived
 * team-scoped leases and results for independent report inspections. A site is not considered an exploration target until
 * it is a world-generation structure or enough distinct artificial blocks have been seen together to look like a building.
 */
public final class StructureMemory extends net.minecraft.world.level.saveddata.SavedData {
    public static final String NAME = "rlclones_structures";
    public static final String PLAYER_BUILDING = "minecraft:player_building";
    private static final int MAX_SITES = 512;
    private static final int MAX_EVIDENCE = 128;
    private static final int MAX_WAYPOINTS = 64;
    private static final int MAX_VERIFICATION_LEASES = 128;
    private static final long VERIFICATION_LEASE_TTL = 1200L;
    private static final int MAX_VERIFICATION_OUTCOMES = 256;
    private static final long VERIFICATION_OUTCOME_TTL = 48_000L;

    private static final class VerificationLease {
        final UUID owner;
        long expiresAt;

        VerificationLease(UUID owner, long expiresAt) {
            this.owner = owner;
            this.expiresAt = expiresAt;
        }
    }

    private static final class VerificationOutcome {
        final boolean accurate;
        final long expiresAt;

        VerificationOutcome(boolean accurate, long expiresAt) {
            this.accurate = accurate;
            this.expiresAt = expiresAt;
        }
    }

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
    private final LinkedHashMap<String, VerificationLease> verificationLeases = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<String, VerificationOutcome> verificationOutcomes = new LinkedHashMap<>(32, 0.75f, true);

    public static StructureMemory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(StructureMemory::load, StructureMemory::new, NAME);
    }

    public List<Site> sites() {
        return List.copyOf(sites);
    }

    public void clear() {
        sites.clear();
        verificationLeases.clear();
        verificationOutcomes.clear();
        setDirty();
    }

    /** True when a teammate already has a live investigation lease for this team-scoped report key. */
    public boolean verificationClaimedByOther(String key, UUID owner, long now) {
        if (key == null || owner == null) {
            return false;
        }
        expireVerificationLeases(now);
        VerificationLease lease = verificationLeases.get(safeVerificationKey(key));
        return lease != null && !lease.owner.equals(owner);
    }

    /** Claim or renew a short lease; only one clone per team can inspect a given unresolved report at a time. */
    public boolean claimVerification(String key, UUID owner, long now) {
        if (key == null || key.isBlank() || owner == null) {
            return false;
        }
        String safeKey = safeVerificationKey(key);
        expireVerificationLeases(now);
        VerificationLease lease = verificationLeases.get(safeKey);
        if (lease != null && !lease.owner.equals(owner)) {
            return false;
        }
        if (lease == null) {
            verificationLeases.put(safeKey, new VerificationLease(owner, now + VERIFICATION_LEASE_TTL));
            trimVerificationLeases();
            setDirty();
        } else if (lease.expiresAt - now <= VERIFICATION_LEASE_TTL / 2) {
            lease.expiresAt = now + VERIFICATION_LEASE_TTL;
            setDirty();
        }
        return true;
    }

    public void releaseVerification(String key, UUID owner) {
        if (key == null || owner == null) {
            return;
        }
        String safeKey = safeVerificationKey(key);
        VerificationLease lease = verificationLeases.get(safeKey);
        if (lease != null && lease.owner.equals(owner)) {
            verificationLeases.remove(safeKey);
            setDirty();
        }
    }

    /** Cache a completed independent inspection so other clones do not repeat the same trip. */
    public void recordVerificationOutcome(String key, UUID owner, boolean accurate, long now) {
        if (key == null || owner == null) {
            return;
        }
        String safeKey = safeVerificationKey(key);
        expireVerificationLeases(now);
        expireVerificationOutcomes(now);
        VerificationLease lease = verificationLeases.get(safeKey);
        if (lease == null || !lease.owner.equals(owner)) {
            return; // stale owners cannot overwrite a newer inspector's result
        }
        verificationLeases.remove(safeKey);
        verificationOutcomes.put(safeKey, new VerificationOutcome(accurate, now + VERIFICATION_OUTCOME_TTL));
        trimVerificationOutcomes();
        setDirty();
    }

    @Nullable
    public Boolean verificationOutcome(String key, long now) {
        if (key == null || key.isBlank()) {
            return null;
        }
        expireVerificationOutcomes(now);
        VerificationOutcome outcome = verificationOutcomes.get(safeVerificationKey(key));
        return outcome == null ? null : outcome.accurate;
    }

    private static String safeVerificationKey(String key) {
        return key.length() > 512 ? key.substring(0, 512) : key;
    }

    private void expireVerificationLeases(long now) {
        boolean removed = verificationLeases.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        if (removed) {
            setDirty();
        }
    }

    private void expireVerificationOutcomes(long now) {
        boolean removed = verificationOutcomes.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        if (removed) {
            setDirty();
        }
    }

    private void trimVerificationOutcomes() {
        while (verificationOutcomes.size() > MAX_VERIFICATION_OUTCOMES) {
            verificationOutcomes.remove(verificationOutcomes.keySet().iterator().next());
        }
    }

    private void trimVerificationLeases() {
        while (verificationLeases.size() > MAX_VERIFICATION_LEASES) {
            verificationLeases.remove(verificationLeases.keySet().iterator().next());
        }
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

    /**
     * Verify a report against loaded world data: 1 = confirmed, 0 = contradicted, -1 = not currently verifiable.
     * Manual structures require later independent block observations, so a missing cluster is not treated as false.
     */
    public int verifyReport(ServerLevel level, String id, BlockPos pos) {
        if (!level.hasChunkAt(pos)) {
            return -1;
        }
        if (PLAYER_BUILDING.equals(id)) {
            Site site = findNearbyManual(level.dimension(), pos);
            return site != null && site.confirmed() ? 1 : -1;
        }
        ResourceLocation reported = ResourceLocation.tryParse(id);
        if (reported == null) {
            return 0;
        }
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        var at = level.structureManager().getAllStructuresAt(pos);
        for (var entry : at.entrySet()) {
            ResourceLocation key = registry.getKey(entry.getKey());
            if (reported.equals(key) && entry.getValue() != null && entry.getValue().isValid()) {
                return 1;
            }
        }
        return 0;
    }

    /**
     * Inspect a small loaded-world volume around an unverified player-building claim. Three distinct artificial
     * blocks are required, matching the normal manual-site confirmation rule; unloaded chunks are never forced in.
     */
    public int verifyManualReport(ServerLevel level, BlockPos reported, long now) {
        if (!level.hasChunkAt(reported)) {
            return -1;
        }
        if (Bases.get(level.getServer()).nearest(level.dimension(), Vec3.atCenterOf(reported), 24) != null) {
            rejectUnconfirmedManualReport(level.dimension(), reported);
            return 0;
        }
        Site remembered = findNearbyManual(level.dimension(), reported);
        if (remembered != null && remembered.confirmed()
                && (remembered.contains(reported) || remembered.center().distManhattan(reported) <= 8)) {
            return 1;
        }
        List<BlockPos> evidence = new ArrayList<>(3);
        boolean completeScan = true;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = -4; dy <= 6 && evidence.size() < 3; dy++) {
            for (int dx = -8; dx <= 8 && evidence.size() < 3; dx++) {
                for (int dz = -8; dz <= 8 && evidence.size() < 3; dz++) {
                    cursor.set(reported.getX() + dx, reported.getY() + dy, reported.getZ() + dz);
                    if (!level.hasChunkAt(cursor)) {
                        completeScan = false;
                        continue;
                    }
                    var state = level.getBlockState(cursor);
                    if (artificial(level, cursor, state)) {
                        evidence.add(cursor.immutable());
                    }
                }
            }
        }
        if (evidence.size() < 3) {
            if (!completeScan) {
                return -1; // unloaded neighboring chunks make absence inconclusive
            }
            rejectUnconfirmedManualReport(level.dimension(), reported);
            return 0;
        }
        Site site = remembered;
        if (site == null) {
            BlockPos first = evidence.get(0);
            site = new Site(manualKey(level.dimension(), first), level.dimension(), PLAYER_BUILDING, first, first, false, now);
            add(site);
        }
        for (BlockPos pos : evidence) {
            var state = level.getBlockState(pos);
            site.remember(pos, state.getBlock() instanceof DoorBlock, isChest(state), state.is(Blocks.SPAWNER));
        }
        site.lastSeen = now;
        setDirty();
        return 1;
    }

    public void rejectUnconfirmedManualReport(ResourceKey<Level> dimension, BlockPos reported) {
        Site site = findNearbyManual(dimension, reported);
        if (site != null && !site.confirmed() && site.evidenceCount() <= 2) {
            sites.remove(site);
            setDirty();
        }
    }

    /** Merge a machine-readable STRUCT report from another clone into shared world memory. */
    public void rememberReported(ServerLevel level, String id, BlockPos pos, long now) {
        if (id.equals(PLAYER_BUILDING)) {
            Site site = findNearbyManual(level.dimension(), pos);
            if (site == null) {
                site = new Site(manualKey(level.dimension(), pos), level.dimension(), id, pos, pos, false, now);
                add(site);
            }
            // A reported coordinate is only a tentative lead, never block evidence; the verifier must scan the world.
            site.lastSeen = now;
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
        ListTag leases = tag.getList("verificationLeases", Tag.TAG_COMPOUND);
        for (int i = 0; i < leases.size() && i < MAX_VERIFICATION_LEASES; i++) {
            CompoundTag leaseTag = leases.getCompound(i);
            String key = leaseTag.getString("key");
            try {
                UUID owner = UUID.fromString(leaseTag.getString("owner"));
                long expiresAt = leaseTag.getLong("expiresAt");
                if (!key.isBlank() && key.length() <= 512 && expiresAt > 0) {
                    memory.verificationLeases.put(key, new VerificationLease(owner, expiresAt));
                }
            } catch (IllegalArgumentException ignored) {
                // Discard malformed lease owners from edited or older save data.
            }
        }
        ListTag outcomes = tag.getList("verificationOutcomes", Tag.TAG_COMPOUND);
        for (int i = 0; i < outcomes.size() && i < MAX_VERIFICATION_OUTCOMES; i++) {
            CompoundTag outcomeTag = outcomes.getCompound(i);
            String key = outcomeTag.getString("key");
            long expiresAt = outcomeTag.getLong("expiresAt");
            if (!key.isBlank() && key.length() <= 512 && expiresAt > 0) {
                memory.verificationOutcomes.put(key, new VerificationOutcome(outcomeTag.getBoolean("accurate"), expiresAt));
            }
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
        ListTag leases = new ListTag();
        for (Map.Entry<String, VerificationLease> entry : verificationLeases.entrySet()) {
            CompoundTag lease = new CompoundTag();
            lease.putString("key", entry.getKey());
            lease.putString("owner", entry.getValue().owner.toString());
            lease.putLong("expiresAt", entry.getValue().expiresAt);
            leases.add(lease);
        }
        tag.put("verificationLeases", leases);
        ListTag outcomes = new ListTag();
        for (Map.Entry<String, VerificationOutcome> entry : verificationOutcomes.entrySet()) {
            CompoundTag outcome = new CompoundTag();
            outcome.putString("key", entry.getKey());
            outcome.putBoolean("accurate", entry.getValue().accurate);
            outcome.putLong("expiresAt", entry.getValue().expiresAt);
            outcomes.add(outcome);
        }
        tag.put("verificationOutcomes", outcomes);
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
