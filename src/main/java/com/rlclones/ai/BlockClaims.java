package com.rlclones.ai;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Who is going for which block. A crowd of clones sent to one tree or one rock all picked the nearest block - the same one - and stood in
 * each other's way breaking it. A clone that settles on a block says so here, keeps saying so while it works on it, and the others take the nearest
 * block nobody has claimed. A claim nobody has renewed for 3 seconds lapses (its owner left or died). Not saved.
 */
public final class BlockClaims {
    private record Claim(UUID who, long tick) {
    }

    private static final long LAPSE = 60;
    private static final Map<Long, Claim> CLAIMS = new HashMap<>();

    private BlockClaims() {
    }

    /** True if some other clone has this block and is still at it. */
    public static boolean takenByOther(BlockPos p, UUID me, long now) {
        Claim c = CLAIMS.get(p.asLong());
        return c != null && !c.who.equals(me) && now - c.tick <= LAPSE;
    }

    /** Take the block, or renew the claim. False if somebody else holds it (nothing changes then). */
    public static boolean claim(BlockPos p, UUID me, long now) {
        if (takenByOther(p, me, now)) {
            return false;
        }
        CLAIMS.put(p.asLong(), new Claim(me, now));
        if (CLAIMS.size() > 4000) {
            for (Iterator<Claim> it = CLAIMS.values().iterator(); it.hasNext(); ) {
                if (now - it.next().tick > LAPSE) {
                    it.remove();
                }
            }
        }
        return true;
    }

    public static void release(BlockPos p, UUID me) {
        Claim c = CLAIMS.get(p.asLong());
        if (c != null && c.who.equals(me)) {
            CLAIMS.remove(p.asLong());
        }
    }

    public static void forgetAll() {
        CLAIMS.clear();
    }
}
