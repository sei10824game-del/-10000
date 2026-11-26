package com.rlclones.client;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client-only presentation settings. Contains no client runtime types, so dedicated servers can load the spec. */
public final class RenderDetailConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.DoubleValue DETAIL_DISTANCE, NAME_DISTANCE, SHADOW_DISTANCE;
    static {
        var b=new ForgeConfigSpec.Builder();b.push("cloneRendering");
        ENABLED=b.comment("Reduce only distant synthetic clone cosmetics. Body, equipment, held items and simulation remain intact.").define("enabled",true);
        DETAIL_DISTANCE=b.comment("Beyond this distance omit outer skin layers, armor glint, armor trim and capes. Focused clones retain full detail.").defineInRange("detailDistance",32.0,8.0,256.0);
        NAME_DISTANCE=b.comment("Beyond this distance omit clone name tags, except the focused clone.").defineInRange("nameDistance",32.0,8.0,256.0);
        SHADOW_DISTANCE=b.comment("Beyond this distance omit clone ground shadows.").defineInRange("shadowDistance",32.0,8.0,256.0);
        b.pop();SPEC=b.build();
    }
}
