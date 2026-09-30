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
