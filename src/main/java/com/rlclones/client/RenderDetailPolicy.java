package com.rlclones.client;

/** Pure presentation rule, independent of Minecraft client initialization. */
public final class RenderDetailPolicy {
    private RenderDetailPolicy() {}
    public static boolean simplify(boolean enabled,boolean synthetic,boolean focused,double distanceSquared,double limit) {
        return enabled && synthetic && !focused && Double.isFinite(distanceSquared) && distanceSquared>=0
                && Double.isFinite(limit) && limit>0 && distanceSquared>limit*limit;
    }
}
