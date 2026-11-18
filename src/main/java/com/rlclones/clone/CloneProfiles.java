package com.rlclones.clone;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;

import java.util.Map;

/** Synthetic identities must never be resolved as real Mojang accounts. */
public final class CloneProfiles {
    private static final java.util.concurrent.atomic.AtomicLong AVOIDED_LOOKUPS = new java.util.concurrent.atomic.AtomicLong();
    private static final String MARKER = "rlclones:synthetic";

    private CloneProfiles() {}

    /** Mark new and restored clones before their profile is sent to a client. */
    public static GameProfile mark(GameProfile profile) {
        profile.getProperties().removeAll(MARKER);
        profile.getProperties().put(MARKER, new Property(MARKER, "1"));
        return profile;
    }

    public static boolean isSynthetic(GameProfile profile) {
        return profile.getProperties().get(MARKER).stream().anyMatch(p -> "1".equals(p.getValue()));
    }

    /** Diagnostic counter for the client startup test; no per-clone history is retained. */
    public static long avoidedLookups() {
        return AVOIDED_LOOKUPS.get();
    }

    public static void recordAvoidedLookup() {
        AVOIDED_LOOKUPS.incrementAndGet();
    }

    /** Decode supplied textures locally; deliberately never call fillProfileProperties. */
    public static Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> suppliedTextures(
            GameProfile profile, MinecraftSessionService service, boolean requireSecure) {
        if (!isSynthetic(profile) || !profile.getProperties().containsKey("textures")) return Map.of();
        try {
            return service.getTextures(profile, requireSecure);
        } catch (RuntimeException invalidTexture) {
            // Missing, expired, unsigned or malformed textures use the vanilla default skin.
            return Map.of();
        }
    }
}
