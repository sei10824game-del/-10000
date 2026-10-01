package com.rlclones.ai;

import com.rlclones.Config;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vision of a clone. Only things inside the view frustum (head yaw/pitch + FOV), within view distance
 * (reduced by darkness / blindness) and with an unobstructed line of sight are perceived. No wall hacks.
 * Seen things are remembered for a while so the clone has object permanence.
 */
public final class Perception {
    public enum BlockKind {LOG, ORE, TABLE, FURNACE, CHEST, HARMFUL, STONE, BREWING, UNKNOWN, WOOD}

    public static final class Seen {
        public final Entity entity;
        public final int id;
        public final String typeId;
        public Vec3 pos;
        public Vec3 prevPos;
        public long lastSeen;
        public long prevSeen;
        public final long firstSeen;
        public boolean visible;
        public int airborneStreak;
        public long swellStart = -1;
        public boolean flaggedBurn;
        public boolean flaggedClimb;
        /** Last tick this entity was only heard (not seen); -1 if never. */
        public long heardAt = -1;

        Seen(Entity entity, long now) {
            this.entity = entity;
            this.id = entity.getId();
            this.typeId = typeId(entity);
            this.pos = entity.position();
            this.prevPos = pos;
            this.lastSeen = now;
            this.prevSeen = now;
            this.firstSeen = now;
        }

        /** Horizontal speed in blocks per tick estimated from the last two sightings. */
        public double horizontalSpeed() {
            long dt = lastSeen - prevSeen;
            if (dt <= 0 || dt > 10) {
                return 0;
            }
            double dx = pos.x - prevPos.x;
            double dz = pos.z - prevPos.z;
            return Math.sqrt(dx * dx + dz * dz) / dt;
        }

        public boolean alive() {
            return !entity.isRemoved() && entity.isAlive();
        }
    }

    private static final int MAX_BLOCKS = 128;
    private static final int MEMORY_TICKS = 200;

    private final ServerPlayer self;
    private final Int2ObjectOpenHashMap<Seen> memory = new Int2ObjectOpenHashMap<>();
    private final List<Seen> visible = new ArrayList<>();
    private final Map<BlockPos, BlockKind> blocks = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockPos, BlockKind> eldest) {
            return size() > MAX_BLOCKS;
        }
    };
    private long lastUpdate = Long.MIN_VALUE;
    /** Block ids this clone has learned hurt (magma, infection blocks...). */
    private java.util.function.Predicate<String> harmful = id -> false;
    /** Blocks the clone has never examined but could obtain (see Discovery). */
    private java.util.function.BiPredicate<BlockState, BlockPos> unknown = (st, pos) -> false;
    /** Things newly noticed (entities coming into view, interesting blocks); used to judge how well looking around works. */
    public int discoveries;

    public Perception(ServerPlayer self) {
        this.self = self;
    }

    /** Arrows / tridents lying around that a player could pick up. */
    public static boolean isRetrievable(Entity e) {
        return e instanceof net.minecraft.world.entity.projectile.AbstractArrow a
                && a.pickup == net.minecraft.world.entity.projectile.AbstractArrow.Pickup.ALLOWED
                && (a.getDeltaMovement().lengthSqr() < 0.25 || a.tickCount > 60);
    }

    public static String typeId(Entity e) {
        net.minecraft.resources.ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
        return key == null ? "unknown" : key.toString();
    }

    // ------------------------------------------------------------------ update

    public void update(long now) {
        lastUpdate = now;
        visible.clear();
        double range = maxRange();
        Level level = self.level();
        AABB box = self.getBoundingBox().inflate(range);
        List<Entity> candidates = level.getEntities(self, box, e -> e.isAlive() && !e.isSpectator()
                && (e instanceof LivingEntity || e instanceof ItemEntity || e instanceof PrimedTnt || isRetrievable(e)));
        Vec3 eye = self.getEyePosition();
        candidates.sort(Comparator.comparingDouble(e -> e.distanceToSqr(eye)));
        int checked = 0;
        for (Entity e : candidates) {
            if (checked++ >= 64) {
                break;
            }
            if (!canSee(e, range)) {
                continue;
            }
            Seen s = memory.get(e.getId());
            if (s == null || s.entity != e) {
                s = new Seen(e, now);
                memory.put(e.getId(), s);
                discoveries++;
            } else {
                if (!s.visible && now - s.lastSeen > 20) {
                    discoveries++; // lost sight of it a while ago and found it again
                }
                s.prevPos = s.pos;
                s.prevSeen = s.lastSeen;
                s.pos = e.position();
                s.lastSeen = now;
            }
            s.visible = true;
            visible.add(s);
        }
        for (Seen s : memory.values()) {
            if (s.lastSeen != now) {
                s.visible = false;
            }
        }
        memory.values().removeIf(s -> now - s.lastSeen > MEMORY_TICKS || (s.entity.isRemoved() && now - s.lastSeen > 20));
        if (((now / 4) & 1) == 0) {
            scanBlocks();
        }
    }

    // ------------------------------------------------------------------ vision checks

    public double maxRange() {
        if (self.hasEffect(MobEffects.BLINDNESS)) {
            return 5.0;
        }
        if (self.hasEffect(MobEffects.DARKNESS)) {
            return 10.0;
        }
        return Config.get(Config.VIEW_DISTANCE, 48.0);
    }

    /** Instant check: can this clone see the entity right now? */
    public boolean canSee(Entity e) {
        return canSee(e, maxRange());
    }

    private boolean canSee(Entity e, double range) {
        if (e == self || e.isRemoved() || e.isSpectator() || e.level() != self.level()) {
            return false;
        }
        Vec3 eye = self.getEyePosition();
        AABB bb = e.getBoundingBox();
        Vec3 center = bb.getCenter();
        double dist = eye.distanceTo(center);
        if (dist > range * lightFactor(e)) {
            return false;
        }
        if (e.isInvisibleTo(self) && !(e instanceof LivingEntity le && wearsSomething(le))) {
            return false;
        }
        double radius = Math.max(bb.getXsize(), bb.getYsize()) * 0.5;
        if (!inFov(eye, center, radius, dist)) {
            return false;
        }
        if (bb.contains(eye)) {
            return true;
        }
        return clearLine(eye, e.getEyePosition()) || clearLine(eye, center) || clearLine(eye, new Vec3(center.x, bb.minY + 0.1, center.z));
    }

    /** Human-readable breakdown of the vision check (for debugging and test diagnostics). */
    public String explain(Entity e) {
        Vec3 eye = self.getEyePosition();
        AABB bb = e.getBoundingBox();
        Vec3 c = bb.getCenter();
        double dist = eye.distanceTo(c);
        double radius = Math.max(bb.getXsize(), bb.getYsize()) * 0.5;
        BlockHitResult hit = self.level().clip(new ClipContext(eye, c, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, self));
        String blocker = hit.getType() == HitResult.Type.MISS ? "none" : hit.getBlockPos().toShortString() + "=" + self.level().getBlockState(hit.getBlockPos());
        return String.format(java.util.Locale.ROOT,
                "eye=(%.2f,%.2f,%.2f) yaw=%.1f head=%.1f pitch=%.1f target=(%.2f,%.2f,%.2f) dist=%.2f range=%.1f light=%.2f invisible=%s fov=%s los=%s/%s/%s blocker=%s",
                eye.x, eye.y, eye.z, self.getYRot(), self.getYHeadRot(), self.getXRot(), c.x, c.y, c.z, dist, maxRange(), lightFactor(e),
                e.isInvisibleTo(self), inFov(eye, c, radius, dist), clearLine(eye, e.getEyePosition()), clearLine(eye, c),
                clearLine(eye, new Vec3(c.x, bb.minY + 0.1, c.z)), blocker);
    }

    private static boolean wearsSomething(LivingEntity le) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!le.getItemBySlot(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private double lightFactor(Entity e) {
        if (!Config.get(Config.DARKNESS_LIMITS_VISION, true) || self.hasEffect(MobEffects.NIGHT_VISION)
                || e.isCurrentlyGlowing() || e.isOnFire()) {
            return 1.0;
        }
        int light = self.level().getMaxLocalRawBrightness(e.blockPosition());
        return 0.35 + 0.65 * Math.pow(light / 15.0, 0.7);
    }

    public boolean inFov(Vec3 eye, Vec3 point, double radius, double dist) {
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yawTo = (float) Math.toDegrees(Mth.atan2(dz, dx)) - 90.0F;
        float pitchTo = (float) -Math.toDegrees(Mth.atan2(dy, horiz));
        double slack = Math.toDegrees(Math.atan2(radius, Math.max(0.2, dist)));
        float yawDiff = Mth.degreesDifferenceAbs(self.getYHeadRot(), yawTo);
        float pitchDiff = Math.abs(pitchTo - self.getXRot());
        double hf = Config.get(Config.FOV_HORIZONTAL, 102.0) * 0.5;
        double vf = Config.get(Config.FOV_VERTICAL, 70.0) * 0.5;
        return yawDiff <= hf + slack && pitchDiff <= vf + slack;
    }

    public boolean clearLine(Vec3 from, Vec3 to) {
        BlockHitResult hit = self.level().clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, self));
        return hit.getType() == HitResult.Type.MISS;
    }

    // ------------------------------------------------------------------ blocks

    private void scanBlocks() {
        Level level = self.level();
        Vec3 eye = self.getEyePosition();
        double hf = Config.get(Config.FOV_HORIZONTAL, 102.0) * 0.5;
        double vf = Config.get(Config.FOV_VERTICAL, 70.0) * 0.5;
        for (int i = 0; i < 10; i++) {
            float yaw = self.getYHeadRot() + (i == 0 ? 0f : (float) ((self.getRandom().nextDouble() * 2 - 1) * hf));
            float pitch = Mth.clamp(self.getXRot() + (i == 0 ? 0f : (float) ((self.getRandom().nextDouble() * 2 - 1) * vf)), -90f, 90f);
            Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
            BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(dir.scale(24)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, self));
            if (hit.getType() != HitResult.Type.BLOCK) {
                continue;
            }
            BlockPos pos = hit.getBlockPos().immutable();
            BlockKind kind = kindOf(pos);
            if (kind == BlockKind.STONE && !blocks.containsKey(pos) && !diggable.test(pos)) {
                continue;
            }
            if (kind != null && !blocks.containsKey(pos) && (kind == BlockKind.STONE || kind == BlockKind.UNKNOWN || kind == BlockKind.WOOD)
                    && count(kind) >= (kind == BlockKind.UNKNOWN ? 12 : 8)) {
                continue; // plenty of those remembered already
            }
            if (kind != null) {
                if (blocks.put(pos, kind) == null) {
                    discoveries++;
                }
            }
        }
    }

    /** Like {@link #classify(BlockState)}, but logs only count when they belong to a natural tree (not a house wall). */
    public static BlockKind classify(Level level, BlockPos pos) {
        BlockKind kind = classify(level.getBlockState(pos));
        if (kind == BlockKind.LOG && !isTreeLog(level, pos)) {
            return BlockKind.WOOD; // a log that is part of something built
        }
        return kind;
    }

    private static boolean isTreeLog(Level level, BlockPos pos) {
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-2, 0, -2), pos.offset(2, 5, 2))) {
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof LeavesBlock && s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT)) {
                return true;
            }
        }
        return false;
    }

    public static BlockKind classify(BlockState state) {
        if (state.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) {
            return BlockKind.TABLE;
        }
        if (state.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock) {
            return BlockKind.FURNACE;
        }
        if (state.is(BlockTags.LOGS)) {
            return BlockKind.LOG;
        }
        if (state.is(Tags.Blocks.ORES)) {
            return BlockKind.ORE;
        }
        if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock) {
            return BlockKind.CHEST;
        }
        if (state.getBlock() instanceof net.minecraft.world.level.block.BrewingStandBlock) {
            return BlockKind.BREWING;
        }
        if (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Tags.Blocks.COBBLESTONE)) {
            return BlockKind.STONE;
        }
        if (state.is(BlockTags.PLANKS)) {
            return BlockKind.WOOD;
        }
        return null;
    }

    /**
     * Look over the ground around for blocks already known to hurt (in view and with a clear line to their top face,
     * like a player scanning the floor after stepping on magma).
     */
    public void scanForHarmful(int radius) {
        Level level = self.level();
        Vec3 eye = self.getEyePosition();
        BlockPos feet = self.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-radius, -3, -radius), feet.offset(radius, 2, radius))) {
            BlockState st = level.getBlockState(p);
            if (st.isAir() || !harmful.test(blockId(st))) {
                continue;
            }
            Vec3 top = new Vec3(p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5);
            double dist = eye.distanceTo(top);
            if (!inFov(eye, top, 0.5, dist) && !p.equals(self.getOnPos())) {
                continue;
            }
            BlockHitResult hit = level.clip(new ClipContext(eye, top.add(0, -0.05, 0), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, self));
            if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(p)) {
                blocks.put(p.immutable(), BlockKind.HARMFUL);
            }
        }
    }

    public void setHarmful(java.util.function.Predicate<String> harmful) {
        this.harmful = harmful;
    }

    public static String blockId(BlockState state) {
        return String.valueOf(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock()));
    }

    public void setUnknown(java.util.function.BiPredicate<BlockState, BlockPos> unknown) {
        this.unknown = unknown;
    }

    /** Stone worth remembering: only where it can safely be dug (not the thin floor over a drop). */
    private java.util.function.Predicate<BlockPos> diggable = pos -> true;

    public void setDiggable(java.util.function.Predicate<BlockPos> diggable) {
        this.diggable = diggable;
    }

    private int count(BlockKind kind) {
        int n = 0;
        for (BlockKind k : blocks.values()) {
            if (k == kind) {
                n++;
            }
        }
        return n;
    }

    /** What this clone makes of the block at {@code pos} (its own harmful / unknown knowledge included). */
    public BlockKind kindAt(BlockPos pos) {
        return kindOf(pos);
    }

    private BlockKind kindOf(BlockPos pos) {
        BlockState state = self.level().getBlockState(pos);
        if (!state.isAir() && harmful.test(blockId(state))) {
            return BlockKind.HARMFUL;
        }
        BlockKind k = classify(self.level(), pos);
        if (k == null && !state.isAir() && unknown.test(state, pos)) {
            return BlockKind.UNKNOWN;
        }
        return k;
    }

    /** Remember a block the clone is looking at directly (e.g. the block it just mined next to). */
    public void noteBlock(BlockPos pos) {
        BlockKind kind = kindOf(pos);
        if (kind != null) {
            blocks.put(pos.immutable(), kind);
        } else {
            blocks.remove(pos);
        }
    }

    /**
     * The clone heard this entity at {@code pos} (it may be behind it or behind a wall). Like a player it now knows
     * roughly where it is, but it is not "visible" until it actually comes into view.
     */
    public Seen hear(Entity e, Vec3 pos, long now) {
        Seen s = memory.get(e.getId());
        if (s == null || s.entity != e) {
            s = new Seen(e, now);
            s.pos = pos;
            s.prevPos = pos;
            memory.put(e.getId(), s);
        } else if (!s.visible) {
            s.prevPos = pos;
            s.prevSeen = now;
            s.pos = pos;
            s.lastSeen = now;
        }
        s.heardAt = now;
        return s;
    }

    public void forgetBlock(BlockPos pos) {
        blocks.remove(pos);
    }

    public Map<BlockPos, BlockKind> blocks() {
        return Collections.unmodifiableMap(blocks);
    }

    // ------------------------------------------------------------------ queries

    public List<Seen> visible() {
        return visible;
    }

    public Collection<Seen> remembered() {
        return memory.values();
    }

    public Seen get(Entity e) {
        Seen s = memory.get(e.getId());
        return s != null && s.entity == e ? s : null;
    }

    public boolean isVisible(Entity e) {
        Seen s = get(e);
        return s != null && s.visible;
    }

    public long lastUpdate() {
        return lastUpdate;
    }
}
