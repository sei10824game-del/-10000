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
    /** Entity types (ids) whose natural spawns become clone mobs (plan.md 71 / settings screen). */
    public final java.util.Set<String> mobTypes = new java.util.TreeSet<>();
    /** Mobs a clone mob summons (vex, zombie reinforcements...) are clone mobs too. */
    public boolean summonedAsClone;
    /** /rlclone mobfaction peace: mobs of a clone mob's own kind leave it alone (hostile = they still attack it, the default). */
    public boolean mobPeace;
    /** Clones that are clone equipment (armour that walks), by UUID. */
    public final java.util.Set<UUID> equipment = new java.util.HashSet<>();
    /** /rlclone equipfaction peace: mobs leave clone equipment alone. */
    public boolean equipPeace;
    /** Plain armor stands turn into clone equipment too, and clone equipment makes more of itself from its spare gear. */
    public boolean plainStands;
    /** Everything the clones (mobs and equipment too) ever earned / got: id -> who first (names live on in the book). */
    public final Map<String, String> advancementsDone = new LinkedHashMap<>();
    public final Map<String, String> itemsGot = new LinkedHashMap<>();
    public boolean breeding;
    /** /rlclone chat off: clones keep talking to each other but nothing shows in the players' chat. */
    public boolean chatHidden;
    /** /rlclone coward: clones never fight, they only run and hide. */
    public boolean coward;
    /** /rlclone flee off: clones never run away (they fight, or carry on). */
    public boolean noFlee;
    public boolean linked;
    public boolean respawn;
    public int nextIndex = 1;

    public static CloneRoster load(CompoundTag tag) {
        CloneRoster r = new CloneRoster();
        r.linked = tag.getBoolean("linked");
        r.respawn = tag.getBoolean("respawn");
        r.breeding = tag.getBoolean("breeding");
        for (String k : tag.getCompound("advancementsDone").getAllKeys()) {
            r.advancementsDone.put(k, tag.getCompound("advancementsDone").getString(k));
        }
        for (String k : tag.getCompound("itemsGot").getAllKeys()) {
            r.itemsGot.put(k, tag.getCompound("itemsGot").getString(k));
        }
        r.equipPeace = tag.getBoolean("equipPeace");
        r.plainStands = tag.getBoolean("plainStands");
        for (Tag t : tag.getList("equipment", Tag.TAG_INT_ARRAY)) {
            r.equipment.add(net.minecraft.nbt.NbtUtils.loadUUID(t));
        }
        r.mobPeace = tag.getBoolean("mobPeace");
        r.summonedAsClone = tag.getBoolean("summonedAsClone");
        r.chatHidden = tag.getBoolean("chatHidden");
        r.coward = tag.getBoolean("coward");
        r.noFlee = tag.getBoolean("noFlee");
        r.nextIndex = Math.max(1, tag.getInt("nextIndex"));
        for (Tag t : tag.getList("mobTypes", Tag.TAG_STRING)) {
            r.mobTypes.add(t.getAsString());
        }
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
        CompoundTag adv = new CompoundTag();
        advancementsDone.forEach(adv::putString);
        tag.put("advancementsDone", adv);
        CompoundTag its = new CompoundTag();
        itemsGot.forEach(its::putString);
        tag.put("itemsGot", its);
        tag.putBoolean("equipPeace", equipPeace);
        tag.putBoolean("plainStands", plainStands);
        ListTag eq = new ListTag();
        equipment.forEach(u -> eq.add(net.minecraft.nbt.NbtUtils.createUUID(u)));
        tag.put("equipment", eq);
        tag.putBoolean("mobPeace", mobPeace);
        tag.putBoolean("summonedAsClone", summonedAsClone);
        tag.putBoolean("chatHidden", chatHidden);
        tag.putBoolean("coward", coward);
        tag.putBoolean("noFlee", noFlee);
        tag.putInt("nextIndex", nextIndex);
        ListTag mt = new ListTag();
        mobTypes.forEach(m -> mt.add(net.minecraft.nbt.StringTag.valueOf(m)));
        tag.put("mobTypes", mt);
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
