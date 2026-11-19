package com.rlclones.ai;

import com.rlclones.ai.brain.Brain;
import com.rlclones.ai.util.NearestCandidates;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Random;
import java.util.function.Predicate;

/** Learns only the order of already known, safe, currently harvestable ores of the SAME block type. */
public final class OreWorkLearning {
    private final ClonePlayer self;
    private final Perception perception;
    private final NearestCandidates<BlockPos> nearest=new NearestCandidates<>(16);
    private final Random random=new Random();
    private BlockPos target;
    private Brain owner;
    private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
    private long started;
    private float health;
    private int state,method;
    private record ToolUse(net.minecraft.world.item.ItemStack stack,int remaining) {}
    private final java.util.List<ToolUse> tools = new java.util.ArrayList<>();
    public OreWorkLearning(ClonePlayer self,Perception perception) {this.self=self;this.perception=perception;}
    public String report() {return self.getCloneBrain().oreWork.report("鉱石の取りかかり",new String[]{"近距離","高低差","まとまり","明るさ"})+" active="+(target!=null);}
    public BlockPos select(BlockPos fallback,double radius,Predicate<BlockPos> allowed) {
        cancel();
        if(fallback==null||self.isCreative()||!Equipment.canHarvest(self,self.level().getBlockState(fallback)))return fallback;
        var block=self.level().getBlockState(fallback).getBlock();
        nearest.clear();
        for(var entry:perception.blocks().entrySet()) {
            var p=entry.getKey();double distance=p.distToCenterSqr(self.position());
            if(entry.getValue()==Perception.BlockKind.ORE&&distance<radius*radius&&allowed.test(p)
                    &&self.level().getBlockState(p).is(block)&&Equipment.canHarvest(self,self.level().getBlockState(p)))nearest.offer(p,distance);
        }
        nearest.sort();
        if(nearest.size()<2){nearest.clear();return fallback;}
        var best=new BlockPos[4];double[] score={Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE};
        boolean heights=false,clusters=false;
        for(int i=0;i<nearest.size();i++) {
            var p=nearest.get(i);int neighbors=0;
            for(Direction d:Direction.values()) if(perception.blocks().get(p.relative(d))==Perception.BlockKind.ORE) neighbors++;
            double height=Math.abs(p.getY()-self.getBlockY());heights|=height>1;clusters|=neighbors>0;
            double distance=Math.sqrt(p.distToCenterSqr(self.position()));
            int light=self.level().getMaxLocalRawBrightness(p);
            for(int a=0;a<4;a++) {double cost=cost(a,distance,height,neighbors,light);
                if(cost<score[a]){score[a]=cost;best[a]=p;}}
        }
        nearest.clear();
        int mask=1;
        for(int a=1;a<4;a++){boolean different=true;for(int b=0;b<a;b++)if((mask&(1<<b))!=0&&best[a].equals(best[b]))different=false;if(different)mask|=1<<a;}
        if(Integer.bitCount(mask)<2)return fallback;
        state=(self.level().getMaxLocalRawBrightness(self.blockPosition())<8?1:0)|(heights?2:0)|(clusters?4:0);
        owner=self.getCloneBrain();method=owner.oreWork.choose(state,mask,random);
        target=best[method].immutable();dimension=self.level().dimension();started=self.level().getGameTime();health=self.getHealth();
        tools.clear();
        for(var stack:self.getInventory().items) if(stack.getItem() instanceof net.minecraft.world.item.TieredItem&&stack.isDamageableItem())
            tools.add(new ToolUse(stack,stack.getMaxDamage()-stack.getDamageValue()));
        return target;
    }
    public static double cost(int method,double distance,double height,int knownNeighbors,int light) {
        return distance+switch(method){case 1->height*3;case 2->-Math.min(6,knownNeighbors)*1.5;case 3->(15-Math.max(0,Math.min(15,light)))*0.35;default->0;};
    }
    public static float reward(boolean mined,int ticks,float damage,int wear) {
        return Math.max(-2,(mined?1f:-0.5f)-Math.min(0.6f,Math.max(0,ticks)/240f*0.6f)
                -Math.min(1.5f,Math.max(0,damage)*0.25f)-Math.min(0.4f,Math.max(0,wear)*0.02f));
    }
    private int wear() {
        int used=0;
        for(var tool:tools) used+=Math.max(0,tool.remaining()-(tool.stack().isEmpty()?0:tool.stack().getMaxDamage()-tool.stack().getDamageValue()));
        return used;
    }
    public void onBroken(BlockState block,BlockPos pos) {
        if(target!=null&&target.equals(pos)&&Perception.classify(block)==Perception.BlockKind.ORE)finish(true);
    }
    public void failed() {finish(false);}
    public void cancel() {target=null;owner=null;tools.clear();}
    private void finish(boolean mined) {
        if(target==null)return;
        long elapsed=self.level().getGameTime()-started;
        if(owner==self.getCloneBrain()&&dimension==self.level().dimension()&&elapsed>=0&&elapsed<=300) {
            float damage=Math.max(0,health-self.getHealth());
            owner.oreWork.record(state,method,reward(mined,(int)elapsed,damage,wear()),mined);
        }
        cancel();
    }
}
