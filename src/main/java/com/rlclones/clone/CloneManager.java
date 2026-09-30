package com.rlclones.clone;

import com.mojang.authlib.GameProfile;
import com.rlclones.Config;
import com.rlclones.RLClones;
import com.rlclones.ai.brain.Brain;
import com.rlclones.ai.observe.AgentEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side owner of all clones: summoning (Z / spawn egg), the global link switch (X), the respawn
 * switch (P), vanilla-equivalent respawning, persistence of clones and their brains across restarts.
 */
public final class CloneManager {
    private static CloneManager instance;
    private static int errorCount;

    private final MinecraftServer server;
    private final Map<UUID, ClonePlayer> clones = new LinkedHashMap<>();
    private final Map<UUID, Long> deadSince = new HashMap<>();
    private final Map<UUID, CloneConnection> connections = new HashMap<>();
    private Brain shared;
    private long ticks;
    private boolean restored;

    private CloneManager(MinecraftServer server) {
        this.server = server;
    }

    public static CloneManager get(MinecraftServer server) {
        if (instance == null || instance.server != server) {
            instance = new CloneManager(server);
        }
        return instance;
    }

    @Nullable
    public static CloneManager peek() {
        return instance;
    }

    public static void shutdown() {
        instance = null;
        AgentEvents.clear();
    }

    public static int errors() {
        return errorCount;
    }

    public static void reportError(ClonePlayer clone, Throwable t) {
        errorCount++;
        if (errorCount <= 10 || errorCount % 200 == 0) {
            RLClones.LOGGER.error("Clone {} AI error (#{})", clone.getGameProfile().getName(), errorCount, t);
        }
    }

    public MinecraftServer server() {
        return server;
    }

    private CloneRoster roster() {
        return server.overworld().getDataStorage().computeIfAbsent(CloneRoster::load, CloneRoster::new, CloneRoster.NAME);
    }

    public Collection<ClonePlayer> clones() {
        return Collections.unmodifiableCollection(clones.values());
    }

    @Nullable
    public ClonePlayer byName(String name) {
        for (ClonePlayer c : clones.values()) {
            if (c.getGameProfile().getName().equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }

    public boolean isClone(Entity e) {
        return e instanceof ClonePlayer c && clones.get(c.getUUID()) == c;
    }

    public boolean isLinked() {
        return roster().linked;
    }

    public boolean isRespawnEnabled() {
        return roster().respawn;
    }

    // ------------------------------------------------------------------ brains on disk

    private Path brainDir() {
        return server.getWorldPath(LevelResource.ROOT).resolve("rlclones").resolve("brains");
    }

    @Nullable
    private Brain loadBrain(String name) {
        Path file = brainDir().resolve(name + ".dat");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            return Brain.load(NbtIo.readCompressed(in));
        } catch (IOException | RuntimeException e) {
            RLClones.LOGGER.warn("Could not read clone brain {}", file, e);
            return null;
        }
    }

    private void saveBrain(String name, Brain brain) {
        try {
            Path dir = brainDir();
            Files.createDirectories(dir);
            Path tmp = dir.resolve(name + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                NbtIo.writeCompressed(brain.save(), out);
            }
            Files.move(tmp, dir.resolve(name + ".dat"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            RLClones.LOGGER.warn("Could not save clone brain {}", name, e);
        }
    }

    private void deleteBrain(String name) {
        try {
            Files.deleteIfExists(brainDir().resolve(name + ".dat"));
        } catch (IOException ignored) {
            // stale file only
        }
    }

    private Brain sharedBrain() {
        if (shared == null) {
            shared = loadBrain("shared");
            if (shared == null) {
                shared = new Brain();
            }
        }
        return shared;
    }

    private Brain brainFor(UUID id) {
        if (isLinked()) {
            return sharedBrain();
        }
        Brain b = loadBrain(id.toString());
        return b != null ? b : new Brain();
    }

    public void saveAll() {
        if (isLinked()) {
            if (shared != null) {
                saveBrain("shared", shared);
            }
        } else {
            for (ClonePlayer c : clones.values()) {
                saveBrain(c.getUUID().toString(), c.getCloneBrain());
            }
        }
        roster().setDirty();
    }

    // ------------------------------------------------------------------ summoning / login

    public boolean mayControl(ServerPlayer player) {
        return !Config.get(Config.OPS_ONLY, false) || player.hasPermissions(2);
    }

    private String nextName(CloneRoster r) {
        while (true) {
            String name = "Clone" + r.nextIndex++;
            boolean taken = server.getPlayerList().getPlayerByName(name) != null
                    || (server.getSingleplayerProfile() != null && server.getSingleplayerProfile().getName().equalsIgnoreCase(name));
            if (!taken) {
                return name;
            }
        }
    }

    @Nullable
    public ClonePlayer summon(@Nullable ServerPlayer summoner, ServerLevel level, Vec3 pos, float yaw) {
        restore();
        if (clones.size() >= Config.cloneLimit()) {
            return null;
        }
        CloneRoster r = roster();
        GameProfile profile = new GameProfile(UUID.randomUUID(), nextName(r));
        if (summoner != null) {
            profile.getProperties().putAll("textures", summoner.getGameProfile().getProperties().get("textures"));
        }
        ClonePlayer clone = new ClonePlayer(server, level, profile, isLinked() ? sharedBrain() : new Brain());
        if (isLinked()) {
            sharedBrain().members++;
        }
        login(clone);
        clone.setGameMode(GameType.SURVIVAL);
        Vec3 spot = freeSpot(level, pos, clone);
        clone.teleportTo(level, spot.x, spot.y, spot.z, yaw, 0f);
        clone.setHealth(clone.getMaxHealth());
        if (summoner != null && summoner.getRespawnPosition() != null) {
            clone.setRespawnPosition(summoner.getRespawnDimension(), summoner.getRespawnPosition(), summoner.getRespawnAngle(), summoner.isRespawnForced(), false);
        }
        r.profiles.put(profile.getId(), NbtUtils.writeGameProfile(new CompoundTag(), profile));
        if (summoner != null) {
            r.summoners.put(profile.getId(), summoner.getUUID());
        }
        r.setDirty();
        return clone;
    }

    private static Vec3 freeSpot(ServerLevel level, Vec3 pos, ClonePlayer clone) {
        for (int dy = 0; dy < 4; dy++) {
            Vec3 p = pos.add(0, dy, 0);
            if (level.noCollision(clone, clone.getDimensions(clone.getPose()).makeBoundingBox(p))) {
                return p;
            }
        }
        return pos;
    }

    // ------------------------------------------------------------------ player slots

    private static java.lang.reflect.Field maxPlayersField;
    private int baseMaxPlayers = -1;

    /** PlayerList.maxPlayers: the only non-static final int field of PlayerList (mapping independent lookup). */
    private static java.lang.reflect.Field maxPlayersField() {
        if (maxPlayersField == null) {
            for (java.lang.reflect.Field f : PlayerList.class.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (f.getType() == int.class && java.lang.reflect.Modifier.isFinal(mod) && !java.lang.reflect.Modifier.isStatic(mod)) {
                    f.setAccessible(true);
                    maxPlayersField = f;
                    break;
                }
            }
        }
        return maxPlayersField;
    }

    /**
     * Clones must never take the slots of real players: otherwise a world saved with many clones answers
     * "server is full" to its own owner. Raise the cap by the number of online clones.
     */
    public void updatePlayerSlots() {
        try {
            java.lang.reflect.Field f = maxPlayersField();
            if (f == null) {
                return;
            }
            PlayerList list = server.getPlayerList();
            if (baseMaxPlayers < 0) {
                baseMaxPlayers = f.getInt(list);
            }
            long cap = (long) baseMaxPlayers + clones.size();
            f.setInt(list, (int) Math.min(Integer.MAX_VALUE, cap));
        } catch (ReflectiveOperationException | RuntimeException e) {
            RLClones.LOGGER.warn("Could not adjust the player slot limit for clones", e);
        }
    }

    public int realPlayerSlots() {
        return baseMaxPlayers < 0 ? server.getPlayerList().getMaxPlayers() : baseMaxPlayers;
    }

    private void login(ClonePlayer clone) {
        CloneConnection connection = new CloneConnection();
        connections.put(clone.getUUID(), connection);
        server.getPlayerList().placeNewPlayer(connection, clone);
        connection.setOwner(clone);
        clone.showAllSkinLayers();
        clones.put(clone.getUUID(), clone);
        updatePlayerSlots();
    }

    /** Bring back every clone listed in the world save (called once the server has started). */
    public void restore() {
        if (restored) {
            return;
        }
        restored = true;
        restoreMissing();
    }

    /** Logs in every roster clone that is not currently online (position, inventory and brain come from disk). */
    public void restoreMissing() {
        CloneRoster r = roster();
        for (Map.Entry<UUID, CompoundTag> e : new ArrayList<>(r.profiles.entrySet())) {
            if (server.getPlayerList().getPlayer(e.getKey()) != null) {
                continue;
            }
            GameProfile profile = NbtUtils.readGameProfile(e.getValue());
            if (profile == null || profile.getId() == null) {
                r.profiles.remove(e.getKey());
                r.setDirty();
                continue;
            }
            try {
                ClonePlayer clone = new ClonePlayer(server, server.overworld(), profile, brainFor(profile.getId()));
                login(clone);
                if (clone.isDeadOrDying()) {
                    onCloneDied(clone);
                }
            } catch (RuntimeException ex) {
                RLClones.LOGGER.error("Could not restore clone {}", profile.getName(), ex);
            }
        }
    }

    // ------------------------------------------------------------------ X / P switches

    public void setLinked(boolean on) {
        CloneRoster r = roster();
        if (r.linked == on) {
            return;
        }
        if (on) {
            List<Brain> brains = new ArrayList<>();
            for (ClonePlayer c : clones.values()) {
                brains.add(c.getCloneBrain());
            }
            shared = Brain.merge(brains);
            for (ClonePlayer c : clones.values()) {
                c.setCloneBrain(shared);
            }
            r.linked = true;
        } else {
            Brain base = sharedBrain();
            for (ClonePlayer c : clones.values()) {
                Brain copy = base.copy();
                c.setCloneBrain(copy);
                saveBrain(c.getUUID().toString(), copy);
            }
            saveBrain("shared", base);
            r.linked = false;
        }
        r.setDirty();
    }

    public void setRespawn(boolean on) {
        CloneRoster r = roster();
        r.respawn = on;
        r.setDirty();
    }

    /** Handles the Z / X / P key packets. */
    public void handleAction(ServerPlayer sender, ClientAction action) {
        if (!mayControl(sender)) {
            sender.displayClientMessage(Component.translatable("rlclones.msg.no_permission").withStyle(ChatFormatting.RED), true);
            return;
        }
        switch (action) {
            case SUMMON -> {
                Vec3 look = sender.getLookAngle();
                Vec3 front = sender.position().add(new Vec3(look.x, 0, look.z).normalize().scale(2.0));
                ClonePlayer c = summon(sender, sender.serverLevel(), front, sender.getYRot() + 180f);
                if (c == null) {
                    sender.displayClientMessage(Component.translatable("rlclones.msg.limit", Config.cloneLimitLabel()).withStyle(ChatFormatting.RED), true);
                } else {
                    sender.displayClientMessage(Component.translatable("rlclones.msg.summoned", c.getGameProfile().getName(), clones.size(), Config.cloneLimitLabel()).withStyle(ChatFormatting.AQUA), true);
                }
            }
            case TOGGLE_LINK -> {
                setLinked(!isLinked());
                broadcast(isLinked()
                        ? Component.translatable("rlclones.msg.link_on", clones.size()).withStyle(ChatFormatting.LIGHT_PURPLE)
                        : Component.translatable("rlclones.msg.link_off").withStyle(ChatFormatting.GRAY));
            }
            case TOGGLE_RESPAWN -> {
                setRespawn(!isRespawnEnabled());
                broadcast(isRespawnEnabled()
                        ? Component.translatable("rlclones.msg.respawn_on").withStyle(ChatFormatting.GREEN)
                        : Component.translatable("rlclones.msg.respawn_off").withStyle(ChatFormatting.GRAY));
            }
        }
    }

    private void broadcast(Component msg) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!(p instanceof ClonePlayer)) {
                p.displayClientMessage(msg, true);
                p.sendSystemMessage(msg);
            }
        }
    }

    // ------------------------------------------------------------------ death / respawn / removal

    public void onCloneDied(ClonePlayer clone) {
        deadSince.putIfAbsent(clone.getUUID(), ticks);
    }

    public void onLoggedOut(ServerPlayer player) {
        if (player instanceof ClonePlayer c && clones.get(c.getUUID()) == c) {
            clones.remove(c.getUUID());
            deadSince.remove(c.getUUID());
            updatePlayerSlots();
        }
    }

    /** Same steps as {@code PlayerList.respawn}, but the new player object is again a clone. */
    public ClonePlayer respawn(ClonePlayer old) {
        PlayerList list = server.getPlayerList();
        list.players.remove(old);
        old.serverLevel().removePlayerImmediately(old, Entity.RemovalReason.DISCARDED);
        BlockPos respawnPos = old.getRespawnPosition();
        float angle = old.getRespawnAngle();
        boolean forced = old.isRespawnForced();
        ServerLevel respawnLevel = server.getLevel(old.getRespawnDimension());
        Optional<Vec3> spot = respawnLevel != null && respawnPos != null
                ? Player.findRespawnPositionAndUseSpawnBlock(respawnLevel, respawnPos, angle, forced, false)
                : Optional.empty();
        ServerLevel level = respawnLevel != null && spot.isPresent() ? respawnLevel : server.overworld();

        ClonePlayer fresh = new ClonePlayer(server, level, old.getGameProfile(), old.getCloneBrain());
        fresh.connection = old.connection;
        CloneConnection ears = connections.get(old.getUUID());
        if (ears != null) {
            ears.setOwner(fresh);
        }
        fresh.connection.player = fresh;
        fresh.restoreFrom(old, false);
        fresh.setId(old.getId());
        fresh.setMainArm(old.getMainArm());
        for (String tag : old.getTags()) {
            fresh.addTag(tag);
        }
        if (spot.isPresent()) {
            BlockState st = level.getBlockState(respawnPos);
            Vec3 v = spot.get();
            float yaw = angle;
            if (st.is(BlockTags.BEDS) || st.is(Blocks.RESPAWN_ANCHOR)) {
                Vec3 d = Vec3.atBottomCenterOf(respawnPos).subtract(v).normalize();
                yaw = (float) Mth.wrapDegrees(Mth.atan2(d.z, d.x) * (180F / Math.PI) - 90.0D);
            }
            fresh.moveTo(v.x, v.y, v.z, yaw, 0.0F);
            fresh.setRespawnPosition(level.dimension(), respawnPos, angle, forced, false);
        }
        while (!level.noCollision(fresh) && fresh.getY() < level.getMaxBuildHeight()) {
            fresh.setPos(fresh.getX(), fresh.getY() + 1.0D, fresh.getZ());
        }
        level.addRespawnedPlayer(fresh);
        list.players.add(fresh);
        list.playersByUUID.put(fresh.getUUID(), fresh);
        fresh.initInventoryMenu();
        fresh.setHealth(fresh.getHealth());
        fresh.showAllSkinLayers();
        ForgeEventFactory.firePlayerRespawnEvent(fresh, false);
        clones.put(fresh.getUUID(), fresh);
        deadSince.remove(fresh.getUUID());
        return fresh;
    }

    /**
     * Log a clone out.
     *
     * @param forever also forget it: roster entry, brain file (unless linked) and player data are deleted
     */
    public void remove(ClonePlayer clone, boolean forever, Component reason) {
        UUID id = clone.getUUID();
        clones.remove(id);
        deadSince.remove(id);
        if (forever) {
            CloneRoster r = roster();
            r.profiles.remove(id);
            r.summoners.remove(id);
            r.setDirty();
            if (!isLinked()) {
                deleteBrain(id.toString());
            } else if (shared != null) {
                shared.members = Math.max(1, shared.members - 1);
            }
        } else if (!isLinked()) {
            saveBrain(id.toString(), clone.getCloneBrain());
        }
        CloneConnection ears = connections.remove(id);
        if (ears != null) {
            ears.setOwner(null);
        }
        clone.connection.onDisconnect(reason);
        updatePlayerSlots();
        if (forever) {
            Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            try {
                Files.deleteIfExists(dir.resolve(id + ".dat"));
                Files.deleteIfExists(dir.resolve(id + ".dat_old"));
            } catch (IOException ignored) {
                // leftover file is harmless
            }
        }
    }

    public int removeAll() {
        List<ClonePlayer> all = new ArrayList<>(clones.values());
        for (ClonePlayer c : all) {
            remove(c, true, Component.literal("removed"));
        }
        return all.size();
    }

    // ------------------------------------------------------------------ ticking

    public void tick() {
        ticks++;
        if (!deadSince.isEmpty()) {
            boolean respawn = isRespawnEnabled();
            for (Map.Entry<UUID, Long> e : new ArrayList<>(deadSince.entrySet())) {
                ClonePlayer c = clones.get(e.getKey());
                if (c == null) {
                    deadSince.remove(e.getKey());
                    continue;
                }
                long since = ticks - e.getValue();
                if (respawn && since >= Config.get(Config.RESPAWN_DELAY, 40)) {
                    deadSince.remove(e.getKey());
                    try {
                        respawn(c);
                    } catch (RuntimeException ex) {
                        RLClones.LOGGER.error("Failed to respawn clone {}", c.getGameProfile().getName(), ex);
                    }
                } else if (!respawn && since >= Config.get(Config.DESPAWN_DELAY, 60)) {
                    deadSince.remove(e.getKey());
                    Component msg = Component.translatable("rlclones.msg.perished", c.getGameProfile().getName()).withStyle(ChatFormatting.DARK_GRAY);
                    remove(c, true, msg);
                    broadcast(msg);
                }
            }
        }
        if (ticks % 200 == 0) {
            AgentEvents.prune(server.overworld().getGameTime());
        }
        if (ticks % 6000 == 0) {
            saveAll();
        }
        if (!clones.isEmpty() && !Config.get(Config.CLONES_BLOCK_NIGHT_SKIP, false)) {
            for (ServerLevel level : server.getAllLevels()) {
                skipNightIfRealPlayersSleep(level);
            }
        }
    }

    /** Vanilla counts clones as players for sleeping; by default only real players have to sleep. */
    private void skipNightIfRealPlayersSleep(ServerLevel level) {
        List<ServerPlayer> real = new ArrayList<>();
        boolean anyClone = false;
        for (ServerPlayer p : level.players()) {
            if (p instanceof ClonePlayer) {
                anyClone = true;
            } else if (!p.isSpectator()) {
                real.add(p);
            }
        }
        if (!anyClone || real.isEmpty()) {
            return;
        }
        int percent = level.getGameRules().getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE);
        int needed = Math.max(1, Mth.ceil(real.size() * percent / 100.0F));
        int sleeping = 0;
        int deep = 0;
        for (ServerPlayer p : real) {
            if (p.isSleeping()) {
                sleeping++;
            }
            if (p.isSleepingLongEnough()) {
                deep++;
            }
        }
        if (sleeping < needed || deep < needed) {
            return;
        }
        if (level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) {
            long time = level.getDayTime() + 24000L;
            level.setDayTime(ForgeEventFactory.onSleepFinished(level, time - time % 24000L, level.getDayTime()));
        }
        for (ServerPlayer p : level.players()) {
            if (p.isSleeping()) {
                p.stopSleepInBed(false, false);
            }
        }
        if (level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE) && level.isRaining()) {
            level.setWeatherParameters(0, 0, false, false);
        }
    }

    public String describe(ClonePlayer c) {
        return String.format(Locale.ROOT, "%s hp=%.1f food=%d at %d,%d,%d (%s) - %s",
                c.getGameProfile().getName(), c.getHealth(), c.getFoodData().getFoodLevel(),
                c.getBlockX(), c.getBlockY(), c.getBlockZ(), c.level().dimension().location(),
                c.isAlive() ? c.controller().describe() : "dead");
    }
}
