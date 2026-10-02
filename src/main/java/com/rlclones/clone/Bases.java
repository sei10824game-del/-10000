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
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Clone bases (small houses with storage chests) shared by all clones, plus what they last saw inside each
 * base chest. Saved with the world.
 */
public class Bases extends SavedData {
    public static final String NAME = "rlclones_bases";

    public static final class Base {
        public final ResourceKey<Level> dimension;
        public final BlockPos center;
        public final String founder;
        public final List<BlockPos> chests = new ArrayList<>();

        public Base(ResourceKey<Level> dimension, BlockPos center, String founder) {
            this.dimension = dimension;
            this.center = center.immutable();
            this.founder = founder;
        }
    }

    public final List<Base> bases = new ArrayList<>();

    /** A fenced 5x5 animal pen (origin = north-west corner, gate in the middle of the north side). */
    public record Pen(ResourceKey<Level> dimension, BlockPos origin, String kind, int size) {
        public Pen(ResourceKey<Level> dimension, BlockPos origin) {
            this(dimension, origin, "", 5);
        }

        public boolean contains(Vec3 p) {
            return p.x >= origin.getX() + 1 && p.x <= origin.getX() + size - 1 && p.z >= origin.getZ() + 1 && p.z <= origin.getZ() + size - 1
                    && Math.abs(p.y - origin.getY()) < 3;
        }

        public BlockPos center() {
            return origin.offset(size / 2, 0, size / 2);
        }
    }

    public final List<Pen> pens = new ArrayList<>();

    /** Nether portals the clones know about ({@code pos}: a portal block at the bottom of the opening). */
    public record Portal(ResourceKey<Level> dimension, BlockPos pos) {
    }

    public final List<Portal> portals = new ArrayList<>();

    public void addPortal(ResourceKey<Level> dim, BlockPos pos) {
        for (Portal p : portals) {
            if (p.dimension() == dim && p.pos().distSqr(pos) < 16) {
                return; // already known
            }
        }
        portals.add(new Portal(dim, pos.immutable()));
        setDirty();
    }

    @Nullable
    public Portal nearestPortal(ResourceKey<Level> dim, Vec3 pos, double radius) {
        Portal best = null;
        double bestD = radius;
        for (Portal p : portals) {
            double d = Vec3.atCenterOf(p.pos()).distanceTo(pos);
            if (p.dimension() == dim && d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    public void forgetPortal(Portal p) {
        if (portals.remove(p)) {
            setDirty();
        }
    }

    public void addPen(ResourceKey<Level> dim, BlockPos origin) {
        addPen(dim, origin, "");
    }

    /** {@code kind}: the animal kept in it ("" = chickens / anything). */
    public void addPen(ResourceKey<Level> dim, BlockPos origin, String kind) {
        addPen(dim, origin, kind, 5);
    }

    public void addPen(ResourceKey<Level> dim, BlockPos origin, String kind, int size) {
        pens.add(new Pen(dim, origin.immutable(), kind, size));
        setDirty();
    }

    @Nullable
    public Pen nearestPen(ResourceKey<Level> dim, Vec3 pos, double radius, String kind) {
        Pen best = null;
        double bestD = radius;
        for (Pen p : pens) {
            double d = Vec3.atCenterOf(p.center()).distanceTo(pos);
            if (p.dimension() == dim && p.kind().equals(kind) && d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- staircases dug down for ore

    /** A staircase going down from {@code top} in direction {@code dir}; {@code end} = the lowest step dug so far. */
    public static final class Staircase {
        public final ResourceKey<Level> dimension;
        public final BlockPos top;
        public final net.minecraft.core.Direction dir;
        public BlockPos end;
        public boolean finished;

        public Staircase(ResourceKey<Level> dimension, BlockPos top, net.minecraft.core.Direction dir, BlockPos end, boolean finished) {
            this.dimension = dimension;
            this.top = top.immutable();
            this.dir = dir;
            this.end = end.immutable();
            this.finished = finished;
        }

        public int steps() {
            return top.getY() - end.getY();
        }
    }

    public final List<Staircase> staircases = new ArrayList<>();

    public Staircase addStaircase(ResourceKey<Level> dim, BlockPos top, net.minecraft.core.Direction dir) {
        Staircase s = new Staircase(dim, top, dir, top, false);
        staircases.add(s);
        setDirty();
        return s;
    }

    /** The nearest staircase not yet dug to the bottom (where a clone would carry on digging). */
    @Nullable
    public Staircase nearestStaircase(ResourceKey<Level> dim, Vec3 pos, double radius) {
        Staircase best = null;
        double bestD = radius;
        for (Staircase s : staircases) {
            double d = Vec3.atCenterOf(s.top).distanceTo(pos);
            if (s.dimension == dim && !s.finished && d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    @Nullable
    public Pen penAt(ResourceKey<Level> dim, Vec3 pos) {
        for (Pen p : pens) {
            if (p.dimension() == dim && p.contains(pos)) {
                return p;
            }
        }
        return null;
    }

    @Nullable
    public Pen nearestPen(ResourceKey<Level> dim, Vec3 pos, double radius) {
        Pen best = null;
        double bestD = radius;
        for (Pen p : pens) {
            double d = Vec3.atCenterOf(p.center()).distanceTo(pos);
            if (p.dimension() == dim && d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }
    private final Map<String, Map<String, Integer>> contents = new HashMap<>();

    public static Bases get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(Bases::load, Bases::new, NAME);
    }

    private static String key(ResourceKey<Level> dim, BlockPos pos) {
        return dim.location() + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    @Nullable
    public Base nearest(ResourceKey<Level> dim, Vec3 pos, double radius) {
        Base best = null;
        double bestD = radius;
        for (Base b : bases) {
            if (b.dimension != dim) {
                continue;
            }
            double d = Vec3.atCenterOf(b.center).distanceTo(pos);
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    public Base add(ResourceKey<Level> dim, BlockPos center, String founder) {
        Base b = new Base(dim, center, founder);
        bases.add(b);
        setDirty();
        return b;
    }

    public void addChest(Base base, BlockPos chest) {
        if (!base.chests.contains(chest)) {
            base.chests.add(chest.immutable());
            setDirty();
        }
    }

    public boolean isBaseChest(ResourceKey<Level> dim, BlockPos pos) {
        for (Base b : bases) {
            if (b.dimension == dim && b.chests.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /** Remember what is in a base chest right now (called whenever a clone had it open). */
    public void record(ResourceKey<Level> dim, BlockPos pos, Container container) {
        Map<String, Integer> items = new LinkedHashMap<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (!s.isEmpty()) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(s.getItem());
                if (id != null) {
                    items.merge(id.toString(), s.getCount(), Integer::sum);
                }
            }
        }
        contents.put(key(dim, pos), items);
        setDirty();
    }

    public Map<String, Integer> contents(ResourceKey<Level> dim, BlockPos pos) {
        return contents.getOrDefault(key(dim, pos), Map.of());
    }

    public static Bases load(CompoundTag tag) {
        Bases r = new Bases();
        ListTag list = tag.getList("bases", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag b = list.getCompound(i);
            ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(b.getString("dim")));
            Base base = new Base(dim, NbtUtils.readBlockPos(b.getCompound("center")), b.getString("founder"));
            ListTag chests = b.getList("chests", Tag.TAG_COMPOUND);
            for (int j = 0; j < chests.size(); j++) {
                base.chests.add(NbtUtils.readBlockPos(chests.getCompound(j)));
            }
            r.bases.add(base);
        }
        ListTag pens = tag.getList("pens", Tag.TAG_COMPOUND);
        for (int i = 0; i < pens.size(); i++) {
            CompoundTag pt = pens.getCompound(i);
            r.pens.add(new Pen(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(pt.getString("dim"))), NbtUtils.readBlockPos(pt.getCompound("origin")),
                    pt.getString("kind"), pt.contains("size") ? pt.getInt("size") : 5));
        }
        ListTag stairList = tag.getList("staircases", Tag.TAG_COMPOUND);
        for (int i = 0; i < stairList.size(); i++) {
            CompoundTag st = stairList.getCompound(i);
            r.staircases.add(new Staircase(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(st.getString("dim"))),
                    NbtUtils.readBlockPos(st.getCompound("top")), net.minecraft.core.Direction.from3DDataValue(st.getInt("dir")),
                    NbtUtils.readBlockPos(st.getCompound("end")), st.getBoolean("finished")));
        }
        ListTag portalList = tag.getList("portals", Tag.TAG_COMPOUND);
        for (int i = 0; i < portalList.size(); i++) {
            CompoundTag pt = portalList.getCompound(i);
            r.portals.add(new Portal(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(pt.getString("dim"))), NbtUtils.readBlockPos(pt.getCompound("pos"))));
        }
        CompoundTag c = tag.getCompound("contents");
        for (String k : c.getAllKeys()) {
            CompoundTag items = c.getCompound(k);
            Map<String, Integer> m = new LinkedHashMap<>();
            for (String item : items.getAllKeys()) {
                m.put(item, items.getInt(item));
            }
            r.contents.put(k, m);
        }
        return r;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Base base : bases) {
            CompoundTag b = new CompoundTag();
            b.putString("dim", base.dimension.location().toString());
            b.put("center", NbtUtils.writeBlockPos(base.center));
            b.putString("founder", base.founder);
            ListTag chests = new ListTag();
            for (BlockPos p : base.chests) {
                chests.add(NbtUtils.writeBlockPos(p));
            }
            b.put("chests", chests);
            list.add(b);
        }
        tag.put("bases", list);
        ListTag penList = new ListTag();
        for (Pen p : pens) {
            CompoundTag pt = new CompoundTag();
            pt.putString("dim", p.dimension().location().toString());
            pt.put("origin", NbtUtils.writeBlockPos(p.origin()));
            pt.putString("kind", p.kind());
            pt.putInt("size", p.size());
            penList.add(pt);
        }
        tag.put("pens", penList);
        ListTag stairList = new ListTag();
        for (Staircase st : staircases) {
            CompoundTag t = new CompoundTag();
            t.putString("dim", st.dimension.location().toString());
            t.put("top", NbtUtils.writeBlockPos(st.top));
            t.put("end", NbtUtils.writeBlockPos(st.end));
            t.putInt("dir", st.dir.get3DDataValue());
            t.putBoolean("finished", st.finished);
            stairList.add(t);
        }
        tag.put("staircases", stairList);
        ListTag portalList = new ListTag();
        for (Portal p : portals) {
            CompoundTag pt = new CompoundTag();
            pt.putString("dim", p.dimension().location().toString());
            pt.put("pos", NbtUtils.writeBlockPos(p.pos()));
            portalList.add(pt);
        }
        tag.put("portals", portalList);
        CompoundTag c = new CompoundTag();
        contents.forEach((k, items) -> {
            CompoundTag t = new CompoundTag();
            items.forEach(t::putInt);
            c.put(k, t);
        });
        tag.put("contents", c);
        return tag;
    }
}
