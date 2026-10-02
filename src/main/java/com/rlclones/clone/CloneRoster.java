package com.rlclones.clone;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** World-saved list of clones (their game profiles) plus the global X (link) and P (respawn) switches. */
public class CloneRoster extends SavedData {
    public static final String NAME = "rlclones_roster";

    public final Map<UUID, CompoundTag> profiles = new LinkedHashMap<>();
    public final Map<UUID, UUID> summoners = new LinkedHashMap<>();
    public final Map<UUID, String> teams = new LinkedHashMap<>();
    /** Clones born of two parents (N key breeding): child -> its parents. */
    public final Map<UUID, java.util.List<UUID>> parents = new LinkedHashMap<>();
    public boolean breeding;
    public boolean linked;
    public boolean respawn;
    public int nextIndex = 1;

    public static CloneRoster load(CompoundTag tag) {
        CloneRoster r = new CloneRoster();
        r.linked = tag.getBoolean("linked");
        r.respawn = tag.getBoolean("respawn");
        r.breeding = tag.getBoolean("breeding");
        r.nextIndex = Math.max(1, tag.getInt("nextIndex"));
        ListTag list = tag.getList("clones", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            if (!c.hasUUID("uuid")) {
                continue;
            }
            UUID id = c.getUUID("uuid");
            r.profiles.put(id, c.getCompound("profile"));
            if (c.hasUUID("summoner")) {
                r.summoners.put(id, c.getUUID("summoner"));
            }
            if (c.contains("team")) {
                r.teams.put(id, c.getString("team"));
            }
            ListTag ps = c.getList("parents", Tag.TAG_INT_ARRAY);
            if (!ps.isEmpty()) {
                java.util.List<UUID> list2 = new java.util.ArrayList<>();
                for (Tag t : ps) {
                    list2.add(net.minecraft.nbt.NbtUtils.loadUUID(t));
                }
                r.parents.put(id, list2);
            }
        }
        return r;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("linked", linked);
        tag.putBoolean("respawn", respawn);
        tag.putBoolean("breeding", breeding);
        tag.putInt("nextIndex", nextIndex);
        ListTag list = new ListTag();
        profiles.forEach((id, profile) -> {
            CompoundTag c = new CompoundTag();
            c.putUUID("uuid", id);
            c.put("profile", profile);
            UUID summoner = summoners.get(id);
            if (summoner != null) {
                c.putUUID("summoner", summoner);
            }
            String team = teams.get(id);
            if (team != null) {
                c.putString("team", team);
            }
            java.util.List<UUID> ps = parents.get(id);
            if (ps != null) {
                ListTag pl = new ListTag();
                ps.forEach(u -> pl.add(net.minecraft.nbt.NbtUtils.createUUID(u)));
                c.put("parents", pl);
            }
            list.add(c);
        });
        tag.put("clones", list);
        return tag;
    }
}
