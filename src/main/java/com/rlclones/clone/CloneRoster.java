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
    public boolean linked;
    public boolean respawn;
    public int nextIndex = 1;

    public static CloneRoster load(CompoundTag tag) {
        CloneRoster r = new CloneRoster();
        r.linked = tag.getBoolean("linked");
        r.respawn = tag.getBoolean("respawn");
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
        }
        return r;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("linked", linked);
        tag.putBoolean("respawn", respawn);
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
            list.add(c);
        });
        tag.put("clones", list);
        return tag;
    }
}
