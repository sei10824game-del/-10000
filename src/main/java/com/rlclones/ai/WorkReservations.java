package com.rlclones.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import java.util.*;

/** Short, bounded chest leases. Real menus/inventory still perform every transfer. Server thread only. */
public final class WorkReservations {
    private record Key(ServerLevel level, BlockPos pos) {}
    private record Lease(UUID owner, long at) {}
    private static final Map<Key,Lease> leases=new HashMap<>();
    private static Key key(ServerLevel level,BlockPos pos){
        if (!level.hasChunkAt(pos)) return new Key(level,pos.immutable()); // remembered distant chests must not load chunks
        var state=level.getBlockState(pos);
        if(state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE){
            var other=pos.relative(ChestBlock.getConnectedDirection(state));
            if(other.asLong()<pos.asLong())pos=other;
        }
        return new Key(level,pos.immutable());
    }
    public static boolean available(ServerLevel level,BlockPos pos,UUID owner,long now){
        var lease=leases.get(key(level,pos));
        return lease==null||lease.owner().equals(owner)||now<lease.at()||now-lease.at()>60;
    }
    public static boolean claim(ServerLevel level,BlockPos pos,UUID owner,long now){
        if(!available(level,pos,owner,now))return false;
        if(leases.size()>=4096)leases.values().removeIf(l->now<l.at()||now-l.at()>60);
        var key=key(level,pos);
        if(leases.size()>=4096&&!leases.containsKey(key))return false;
        leases.put(key,new Lease(owner,now));return true;
    }
    public static void release(ServerLevel level,UUID owner){leases.entrySet().removeIf(e->e.getKey().level()==level&&e.getValue().owner().equals(owner));}
    public static void clear(){leases.clear();}
}
