package com.rlclones.clone;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CloneProfilesTest {
    private static MinecraftSessionService service(java.lang.reflect.InvocationHandler handler) {
        return (MinecraftSessionService) Proxy.newProxyInstance(MinecraftSessionService.class.getClassLoader(),
                new Class<?>[]{MinecraftSessionService.class}, handler);
    }

    @Test
    void markerPreservesIdentityAndTexturesAndIsIdempotent() {
        GameProfile profile = new GameProfile(UUID.randomUUID(), "Clone1");
        Property textures = new Property("textures", "supplied", "signature");
        profile.getProperties().put("textures", textures);
        UUID id = profile.getId();
        assertFalse(CloneProfiles.isSynthetic(profile));
        assertSame(profile, CloneProfiles.mark(profile));
        int properties = profile.getProperties().size();
        CloneProfiles.mark(profile);
        assertEquals(properties, profile.getProperties().size());
        assertEquals(id, profile.getId());
        assertEquals("Clone1", profile.getName());
        assertTrue(profile.getProperties().get("textures").contains(textures));
        assertTrue(CloneProfiles.isSynthetic(profile));
    }

    @Test
    void hundredsOfTexturelessClonesNeverContactSessionService() {
        MinecraftSessionService noRequests = service((proxy, method, args) -> {
            throw new AssertionError("Unexpected session call: " + method.getName());
        });
        for (int i = 0; i < 300; i++) {
            GameProfile profile = CloneProfiles.mark(new GameProfile(UUID.randomUUID(), "Clone" + i));
            assertTrue(CloneProfiles.suppliedTextures(profile, noRequests, true).isEmpty());
        }
    }

    @Test
    void suppliedSkinIsDecodedWithVanillaSecurityAndNoProfileLookup() {
        AtomicInteger decodes = new AtomicInteger();
        GameProfile profile = CloneProfiles.mark(new GameProfile(UUID.randomUUID(), "Clone1"));
        profile.getProperties().put("textures", new Property("textures", "supplied", "signature"));
        var skin = new MinecraftProfileTexture("http://textures.minecraft.net/texture/example", Map.of());
        var expected = Map.of(MinecraftProfileTexture.Type.SKIN, skin);
        MinecraftSessionService localDecode = service((proxy, method, args) -> {
            assertEquals("getTextures", method.getName(), "must not resolve the synthetic UUID");
            assertSame(profile, args[0]);
            assertEquals(true, args[1]);
            decodes.incrementAndGet();
            return expected;
        });
        assertSame(expected, CloneProfiles.suppliedTextures(profile, localDecode, true));
        assertEquals(1, decodes.get());
    }

    @Test
    void malformedTexturesFallBackWithoutRetrying() {
        GameProfile profile = CloneProfiles.mark(new GameProfile(UUID.randomUUID(), "Clone1"));
        profile.getProperties().put("textures", new Property("textures", "invalid"));
        AtomicInteger requests = new AtomicInteger();
        MinecraftSessionService invalid = service((proxy, method, args) -> {
            assertEquals("getTextures", method.getName());
            requests.incrementAndGet();
            throw new IllegalArgumentException("invalid texture");
        });
        assertTrue(CloneProfiles.suppliedTextures(profile, invalid, true).isEmpty());
        assertEquals(1, requests.get());
    }

    @Test
    void markerSurvivesVanillaProfileSerialization() {
        GameProfile profile = CloneProfiles.mark(new GameProfile(UUID.randomUUID(), "CloneSaved"));
        var tag = net.minecraft.nbt.NbtUtils.writeGameProfile(new net.minecraft.nbt.CompoundTag(), profile);
        GameProfile restored = net.minecraft.nbt.NbtUtils.readGameProfile(tag);
        assertNotNull(restored);
        assertEquals(profile.getId(), restored.getId());
        assertTrue(CloneProfiles.isSynthetic(restored));
    }

    @Test
    void realPlayersIncludingCloneNamesAreNotMarked() {
        assertFalse(CloneProfiles.isSynthetic(new GameProfile(UUID.randomUUID(), "Clone1")));
    }
}
