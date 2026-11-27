package com.rlclones.ai.brain;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Pure compression of an already detached NBT snapshot; safe to call on a worker. */
public final class BrainCompression {
    private BrainCompression() {}
    public static byte[] encode(CompoundTag detached) throws IOException {
        var out=new ByteArrayOutputStream();
        NbtIo.writeCompressed(detached,out);
        return out.toByteArray();
    }
}
