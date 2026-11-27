package com.rlclones.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.lang.ref.WeakReference;
import java.util.*;

/** World-scoped intent reservations plus short active-mining leases. Human digging always wins. */
public final class BlockClaims {
    private record Key(Level level,long pos) {}
    private record Claim(UUID who,long at,WeakReference<ServerPlayer> active,BlockState state) {}
    private static final Map<Key,Claim> CLAIMS=new HashMap<>();
    private static final Map<Key,Map<UUID,Long>> HUMANS=new HashMap<>();
    private static final Map<UUID,Key> HUMAN_POS=new HashMap<>();
    private static boolean live(Key key,Claim c,long now) {
        if(now<c.at()||now-c.at()>(c.state()==null?60:2))return false;
        if(c.active()==null)return true;
        var player=c.active().get();
        if(player==null||!player.isAlive()||player.isRemoved()||player.level()!=key.level())return false;
        if(c.state()==null)return true;
        BlockPos pos=BlockPos.of(key.pos());
        return key.level().hasChunkAt(pos)&&key.level().getBlockState(pos)==c.state();
    }
    public static boolean takenByOther(Level level,BlockPos pos,UUID me,long now) {
        return takenByOther(new Key(level,pos.asLong()),me,now);
    }
    private static boolean takenByOther(Key key,UUID me,long now) {
        var humans=HUMANS.get(key);
        if(humans!=null){
            humans.values().removeIf(at->now<at||now-at>2);
            if(humans.isEmpty())HUMANS.remove(key);
            else for(var who:humans.keySet())if(!who.equals(me))return true;
        }
        Claim c=CLAIMS.get(key);
        if(c!=null&&!live(key,c,now)){CLAIMS.remove(key);c=null;}
        return c!=null&&!c.who().equals(me);
    }
    private static boolean room(Key key,long now) {
        if(CLAIMS.size()<4096||CLAIMS.containsKey(key))return true;
        CLAIMS.entrySet().removeIf(e->!live(e.getKey(),e.getValue(),now));
        return CLAIMS.size()<4096;
    }
    public static boolean claim(Level level,BlockPos pos,UUID me,long now) {
        Key key=new Key(level,pos.asLong());if(takenByOther(key,me,now)||!room(key,now))return false;
        var old=CLAIMS.get(key);
        if(old!=null){
            // Identical renewals in one tick need no allocation or player-list lookup.
            if(old.at()!=now)CLAIMS.put(key,new Claim(me,now,old.active(),old.state()));
        }else{
            var owner=level.getServer()==null?null:level.getServer().getPlayerList().getPlayer(me);
            CLAIMS.put(key,new Claim(me,now,owner==null?null:new WeakReference<>(owner),null));
        }
        return true;
    }
    public static boolean active(Level level,BlockPos pos,ServerPlayer player,long now) {
        Key key=new Key(level,pos.asLong());if(takenByOther(key,player.getUUID(),now)||!room(key,now))return false;
        var previous=CLAIMS.get(key);
        var ref=previous!=null&&previous.active()!=null&&previous.active().get()==player?previous.active():new WeakReference<>(player);
        var state=level.getBlockState(pos);
        if(previous==null||previous.at()!=now||previous.active()!=ref||previous.state()!=state)
            CLAIMS.put(key,new Claim(player.getUUID(),now,ref,state));
        return true;
    }
    public static void human(ServerPlayer player,BlockPos pos) {
        var key=new Key(player.level(),pos.asLong());var old=HUMAN_POS.get(player.getUUID());
        if(old!=null&&!old.equals(key))humanStopped(player.getUUID());
        if(HUMAN_POS.size()>=1024&&!HUMAN_POS.containsKey(player.getUUID()))return;
        HUMAN_POS.put(player.getUUID(),key);HUMANS.computeIfAbsent(key,k->new HashMap<>()).put(player.getUUID(),player.level().getGameTime());
        var claim=CLAIMS.get(key);if(claim!=null&&!claim.who().equals(player.getUUID()))CLAIMS.remove(key);
    }
    public static void humanStopped(UUID player) {
        var key=HUMAN_POS.remove(player);if(key==null)return;
        var humans=HUMANS.get(key);if(humans!=null){humans.remove(player);if(humans.isEmpty())HUMANS.remove(key);}
    }
    public static void release(Level level,BlockPos pos,UUID me) {
        var key=new Key(level,pos.asLong());var c=CLAIMS.get(key);if(c!=null&&c.who().equals(me))CLAIMS.remove(key);
    }
    public static void releaseOwner(Level level,UUID owner){CLAIMS.entrySet().removeIf(e->e.getKey().level()==level&&e.getValue().who().equals(owner));}
    public static void forgetAll(){CLAIMS.clear();HUMANS.clear();HUMAN_POS.clear();}
    private BlockClaims() {}
}
