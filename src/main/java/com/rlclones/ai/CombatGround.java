package com.rlclones.ai;

import com.rlclones.ai.brain.*;
import com.rlclones.ai.combat.Terrain;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.*;
import java.util.*;

/** Local tactical positions, bounded terminal learning, and repeated validation of their advantage. */
public final class CombatGround {
    public static final int COVER=0,HIGH=1,CHOKE=2,OPEN=3;
    public record Site(int kind,BlockPos feet,BlockPos escape,BlockPos peek,double score){}
    private final ClonePlayer self;private final Motor motor;private final Random random=new Random();
    private Site site;private Entity enemy;private Brain owner;private ResourceKey<Level> dimension;
    private long started,nextSearch,lastCheck,phaseAt;private int state,phase;
    private float damage,dealt;private boolean friendly;
    private List<net.minecraft.world.entity.player.Player> allies=List.of(),shooters=List.of();private boolean rangedRole;
    public boolean cancelAction;public int selected,repositions,invalidated,coverCycles,spreadMoves;public String debug="";
    public CombatGround(ClonePlayer self,Motor motor){this.self=self;this.motor=motor;}
    public Site site(){return site;}
    public boolean active(){return site!=null;}
    public boolean exposed(){return site!=null&&site.kind==COVER&&phase==4;}
    private Vec3 point(BlockPos p){return Vec3.atBottomCenterOf(p);}
    private Vec3 eye(BlockPos p){return point(p).add(0,self.getEyeHeight(),0);}
    private boolean solid(BlockPos p){return self.level().getFluidState(p).isEmpty()&&self.level().getBlockState(p).isFaceSturdy(self.level(),p,Direction.UP);}
    // Only valid during one synchronous candidate scan. Never reuse across ticks,
    // clones, or separate calls: terrain, pose and individual learned hazards can change.
    private record Ray(Vec3 from, Vec3 to) {}
    private static final class ScanMemo {
        final Map<BlockPos, Boolean> safety = new HashMap<>();
        final Map<Ray, Boolean> rays = new HashMap<>();
    }
    private ScanMemo scanMemo;
    public int lastScanSafetyHits, lastScanRayHits;

    /** Room for the actual body and a stable neighbourhood, including the knockback side. */
    public boolean safe(BlockPos p){
        if (scanMemo == null) return safeUncached(p);
        Boolean result = scanMemo.safety.get(p);
        if (result != null) { lastScanSafetyHits++; return result; }
        boolean safe = safeUncached(p);
        scanMemo.safety.put(p.immutable(), safe);
        return safe;
    }
    private boolean safeUncached(BlockPos p){
        var l=self.level();if(!l.hasChunkAt(p)||!solid(p.below())||!l.getFluidState(p).isEmpty()||!l.getFluidState(p.above()).isEmpty()||Terrain.hazard(l,p))return false;
        double half=self.getBbWidth()*.5;AABB box=new AABB(p.getX()+.5-half,p.getY()+.01,p.getZ()+.5-half,p.getX()+.5+half,p.getY()+self.getBbHeight(),p.getZ()+.5+half);
        if(l.getBlockCollisions(self,box).iterator().hasNext())return false;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(!solid(p.offset(dx,-1,dz)))return false;
        for(Direction d:Direction.Plane.HORIZONTAL){BlockPos q=p.relative(d);if(!solid(q.below()))return false;
            for(BlockPos b:List.of(q,q.below())){var s=l.getBlockState(b);var id=net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(s.getBlock());
                if(!s.getFluidState().isEmpty()||s.is(net.minecraft.tags.BlockTags.FIRE)||s.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)||s.is(net.minecraft.world.level.block.Blocks.CACTUS)||s.is(net.minecraft.tags.BlockTags.CAMPFIRES)||id!=null&&self.getCloneBrain().isHarmful(id.toString()))return false;}}
        return true;
    }
    private boolean visible(Vec3 from,Vec3 to){
        if (scanMemo == null) return visibleUncached(from,to);
        Ray key = new Ray(from,to);
        Boolean result = scanMemo.rays.get(key);
        if (result != null) { lastScanRayHits++; return result; }
        boolean clear = visibleUncached(from,to);
        scanMemo.rays.put(key,clear);
        return clear;
    }
    private boolean visibleUncached(Vec3 from,Vec3 to){return self.level().clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.ANY,self)).getType()==HitResult.Type.MISS;}
    public boolean area(Entity target){var k=self.getCloneBrain().knowledgeIfPresent(Perception.typeId(target));
        return target instanceof Creeper||k!=null&&(k.isExplosive()||k.effects.keySet().stream().anyMatch(id->{var key=net.minecraft.resources.ResourceLocation.tryParse(id);var e=key==null?null:net.minecraftforge.registries.ForgeRegistries.MOB_EFFECTS.getValue(key);return e!=null&&e.getCategory()==net.minecraft.world.effect.MobEffectCategory.HARMFUL;}));}
    private boolean rangedEnemy(Entity e){var k=self.getCloneBrain().knowledgeIfPresent(Perception.typeId(e));return e instanceof RangedAttackMob||k!=null&&k.isRanged();}
    public boolean highUseful(Entity e){var k=self.getCloneBrain().knowledgeIfPresent(Perception.typeId(e));return !area(e)&&!rangedEnemy(e)&&!(e instanceof Spider)&&!(e instanceof net.minecraft.world.entity.FlyingMob)&&!(e instanceof net.minecraft.world.entity.monster.Phantom)&&!(k!=null&&(k.has(EnemyKnowledge.FLIES)||k.has(EnemyKnowledge.CLIMBS)||k.has(EnemyKnowledge.TELEPORTS)));}
    private boolean nearbyArea(Entity e){return area(e)||Senses.threats(self.controller().perception(),self,self.level().getGameTime(),20).stream().anyMatch(t->area(t.entity));}
    private boolean nearbyRanged(Entity e){return rangedEnemy(e)||Senses.threats(self.controller().perception(),self,self.level().getGameTime(),20).stream().anyMatch(t->rangedEnemy(t.entity));}
    private boolean armedRanged(){return Equipment.rangedSlot(self)>=0||Equipment.offhandRanged(self);}
    private void allies(){allies=new ArrayList<>();shooters=new ArrayList<>();rangedRole=armedRanged();for(var p:self.level().players())if(p!=self&&Senses.isAllyOf(p,self)&&p.distanceToSqr(self)<24*24){allies.add(p);if(p instanceof ClonePlayer c&&Equipment.rangedSlot(c)>=0)shooters.add(p);}}
    private double spacing(Vec3 p){double min=20;for(var ally:allies)min=Math.min(min,ally.position().distanceTo(p));return min;}
    private boolean lane(BlockPos p,Entity e){
        if(rangedRole)return self.controller().rangedBow().friendInLine(e,eye(p))==null;
        for(var ally:shooters)if(ally.distanceTo(e)>point(p).distanceTo(e.position())+2
                &&new AABB(p).inflate(.5).clip(ally.getEyePosition(),e.getBoundingBox().getCenter()).isPresent())return false;
        return true;
    }
    private BlockPos escape(BlockPos p,Entity e){Vec3 away=point(p).subtract(e.position());Direction back=Direction.getNearest(away.x,0,away.z);
        for(Direction d:new Direction[]{back,back.getClockWise(),back.getCounterClockWise()}){BlockPos q=p.relative(d);if(safe(q)&&!occupied(q)&&point(q).distanceTo(e.position())>=point(p).distanceTo(e.position())-.2)return q;}return null;}
    private boolean occupied(BlockPos p){for(var a:allies){if(a.distanceToSqr(point(p))<1)return true;if(a instanceof ClonePlayer c&&c.controller().ground().site!=null&&c.controller().ground().site.feet.distSqr(p)<2)return true;}return false;}
    private boolean choke(BlockPos p){return wall(p.east())&&wall(p.west())||wall(p.north())&&wall(p.south());}
    private boolean wall(BlockPos p){return !self.level().getBlockState(p).getCollisionShape(self.level(),p).isEmpty()&&!self.level().getBlockState(p.above()).getCollisionShape(self.level(),p.above()).isEmpty();}
    private int neighbours(BlockPos p){int open=0;for(Direction d:Direction.Plane.HORIZONTAL)if(safe(p.relative(d)))open++;return open;}
    private BlockPos peek(BlockPos p,Entity e){for(Direction d:Direction.Plane.HORIZONTAL){BlockPos q=p.relative(d);if(safe(q)&&visible(eye(q),e.getBoundingBox().getCenter())&&self.controller().rangedBow().friendInLine(e,eye(q))==null)return q;}return null;}
    private boolean hidden(BlockPos p,Entity e){if(visible(e.getEyePosition(),eye(p)))return false;for(var t:Senses.threats(self.controller().perception(),self,self.level().getGameTime(),20))if(t.entity!=e&&visible(t.entity.getEyePosition(),eye(p)))return false;return true;}
    public List<Site> candidates(Entity e){
        ScanMemo previous = scanMemo;
        scanMemo = new ScanMemo();
        lastScanSafetyHits = lastScanRayHits = 0;
        try { return collectCandidates(e); }
        finally { scanMemo = previous; }
    }

    /** Reference scan for regression tests; no cached terrain or ray results. */
    public List<Site> candidatesUncached(Entity e){
        ScanMemo previous = scanMemo;
        scanMemo = null;
        try { return collectCandidates(e); }
        finally { scanMemo = previous; }
    }

    private List<Site> collectCandidates(Entity e){
        allies();boolean range=armedRanged(),aoe=nearbyArea(e),rangedThreat=nearbyRanged(e);
        boolean high=highUseful(e)&&!rangedThreat&&!aoe&&Senses.threats(self.controller().perception(),self,self.level().getGameTime(),16).stream().allMatch(t->highUseful(t.entity));int threats=Senses.threats(self.controller().perception(),self,self.level().getGameTime(),16).size();
        var best=new ArrayList<Site>();Vec3 towards=e.position().subtract(self.position()).multiply(1,0,1).normalize();
        for(int ring:new int[]{0,2,3,5})for(int direction=0;direction<8;direction++)for(int dy:new int[]{0,1,-1,2}){
            if(ring==0&&direction>0)continue;
            double angle=direction*Math.PI/4;BlockPos p=self.blockPosition().offset((int)Math.round(Math.cos(angle)*ring),dy,(int)Math.round(Math.sin(angle)*ring));
            if(!safe(p)||occupied(p))continue;double distance=point(p).distanceTo(e.position());if(distance<Math.min(3,self.distanceTo(e))||distance>24)continue;
            BlockPos retreat=escape(p,e);if(retreat==null)continue;
            double moved=point(p).distanceTo(self.position()),space=spacing(point(p));if(aoe&&space<4)continue;
            double common=-moved*.13+Math.min(6,space)*.12+(range?-Math.abs(distance-10)*.05:-Math.abs(distance-3)*.12);
            // A deterministic small preference separates equally good flank assignments without shared global state.
            common+=((self.getUUID().getLeastSignificantBits()^p.asLong())&7)*.01;
            if(range&&hidden(p,e)){BlockPos peek=peek(p,e);if(peek!=null)add(best,new Site(COVER,p,retreat,peek,common+3));}
            if(high&&p.getY()>=Math.max(e.getY()+1,e.getBoundingBox().maxY)&&p.getY()<=e.getY()+3&&visible(eye(p),e.getBoundingBox().getCenter())&&lane(p,e))add(best,new Site(HIGH,p,retreat,null,common+2));
            if(threats>=2&&!rangedThreat&&!aoe&&choke(p)&&visible(eye(p),e.getBoundingBox().getCenter())&&lane(p,e))add(best,new Site(CHOKE,p,retreat,null,common+2));
            boolean lure=Terrain.walls(self.level(),self.blockPosition())>=2||Terrain.hazard(self.level(),self.blockPosition())||!visible(self.getEyePosition(),e.getBoundingBox().getCenter());
            boolean formation=range&&self.controller().rangedBow().friendInLine(e,self.getEyePosition())!=null||!range&&!lane(self.blockPosition(),e);
            if((lure&&(!rangedEnemy(e)||Terrain.hazard(self.level(),self.blockPosition()))||formation||aoe&&spacing(self.position())<5)&&neighbours(p)>=3&&visible(eye(p),e.getBoundingBox().getCenter())&&lane(p,e))
                add(best,new Site(OPEN,p,retreat,null,common+2+Math.min(7,space)*.2));
        }
        return best;
    }
    private void add(List<Site> all,Site s){all.add(s);all.sort(Comparator.comparingDouble(Site::score).reversed());int found=0;for(var it=all.iterator();it.hasNext();)if(it.next().kind==s.kind&&++found>2)it.remove();}
    public boolean choose(Entity e,long now){
        if(!self.onGround()||self.isInWater()||self.onClimbable()||self.isCreative()||self.phasing)return false;
        List<Site> options=candidates(e);Site[] reachable=new Site[4];
        for(var s:options)if(reachable[s.kind]==null&&motor.pathTo(point(s.feet))!=null)reachable[s.kind]=s;
        int mask=0;for(int i=0;i<4;i++)if(reachable[i]!=null)mask|=1<<i;if(mask==0){nextSearch=now+60;return false;}
        state=(armedRanged()?1:0)|(rangedEnemy(e)?2:0)|(area(e)||Senses.threats(self.controller().perception(),self,now,16).size()>=2?4:0);
        int selected=self.getHealth()<self.getMaxHealth()*.5?Integer.numberOfTrailingZeros(mask):self.getCloneBrain().ground.choose(state,mask,random);
        site=reachable[selected];enemy=e;owner=self.getCloneBrain();dimension=self.level().dimension();started=phaseAt=now;lastCheck=now;phase=0;damage=dealt=0;friendly=false;this.selected++;if(area(e))spreadMoves++;debug="choose "+selected+" "+site.feet.toShortString();cancelAction=true;return true;
    }
    public boolean valid(Entity e){
        if(site==null||!safe(site.feet)||!safe(site.escape)||occupied(site.escape)||occupied(site.feet))return false;
        if(nearbyArea(e)&&spacing(point(site.feet))<4)return false;
        return switch(site.kind){case COVER->hidden(site.feet,e)&&site.peek!=null&&safe(site.peek)&&!occupied(site.peek)&&self.controller().rangedBow().friendInLine(e,eye(site.peek))==null&&visible(eye(site.peek),e.getBoundingBox().getCenter());case HIGH->highUseful(e)&&!nearbyRanged(e)&&!nearbyArea(e)&&Senses.threats(self.controller().perception(),self,self.level().getGameTime(),16).stream().allMatch(t->highUseful(t.entity))&&site.feet.getY()>=Math.max(e.getY()+1,e.getBoundingBox().maxY)&&lane(site.feet,e);case CHOKE->!nearbyArea(e)&&!nearbyRanged(e)&&choke(site.feet)&&lane(site.feet,e);default->lane(site.feet,e)&&visible(eye(site.feet),e.getBoundingBox().getCenter());};
    }
    public boolean tick(Entity e,long now){
        cancelAction=false;
        if(site!=null&&(owner!=self.getCloneBrain()||dimension!=self.level().dimension()||e!=enemy)){cancel();}
        if(site==null){if(now<nextSearch||!Terrain.hazard(self.level(),self.blockPosition())&&(now+self.getId())%10!=0)return false;nextSearch=now+60;if(!choose(e,now))return false;}
        if(now-lastCheck>=10){lastCheck=now;allies();if(!valid(e)){debug="lost floor="+safe(site.feet)+" exit="+safe(site.escape)+" cover="+hidden(site.feet,e)+" flooded="+(!self.level().getFluidState(site.feet).isEmpty()||!self.level().getFluidState(site.feet.above()).isEmpty())+" at="+site.feet.toShortString();invalidated++;finish(false);nextSearch=now+10;cancelAction=true;return false;}}
        if(now-started>=160){finish(true);nextSearch=now+40;return false;}
        if(site.kind==COVER)return cover(e,now);
        if(self.position().distanceToSqr(point(site.feet))>.5){self.stopUsingItem();cancelAction=true;motor.navigate(point(site.feet),.5,false);repositions++;return true;}
        // Hold a defensible line, enticing melee enemies into it; do not charge out and throw the advantage away.
        if(!armedRanged()&&Senses.gap(self,e)>Senses.REACH_GAP){motor.stop();motor.lookAt(e);return true;}
        return false;
    }
    private boolean cover(Entity e,long now){
        BlockPos goal=phase==2||phase==4?site.peek:site.feet;
        double settle=phase==2||phase==4?.09:.0225;
        if(self.position().distanceToSqr(point(goal))>settle){if(phase!=2&&phase!=4){self.stopUsingItem();cancelAction=true;}if(phase==2||phase==4)motor.navigate(point(goal),.25,false);else if(self.position().distanceToSqr(point(goal))>2.5)motor.navigate(point(goal),.5,false);else motor.movePrecisely(point(goal));repositions++;return true;}
        if(phase==2){phase=4;phaseAt=now;}
        if(phase==4){if(now-phaseAt>40){self.stopUsingItem();phase=3;phaseAt=now;cancelAction=true;return true;}return false;}
        if(phase==0||phase==3){phase=1;phaseAt=now;cancelAction=true;motor.stop();return true;}
        motor.stop();int slot=Equipment.rangedSlot(self);if(slot<0){finish(false);return false;}Equipment.select(self,slot);ItemStack held=self.getMainHandItem();
        boolean ready=false;
        if(held.getItem() instanceof BowItem){if(!self.isUsingItem())motor.useHeldItem(InteractionHand.MAIN_HAND);ready=self.getTicksUsingItem()>=18;}
        else if(held.getItem() instanceof CrossbowItem){if(CrossbowItem.isCharged(held))ready=true;else if(!self.isUsingItem())motor.useHeldItem(InteractionHand.MAIN_HAND);else if(self.getTicksUsingItem()>=CrossbowItem.getChargeDuration(held)+1){self.releaseUsingItem();ready=true;}}
        else ready=now-phaseAt>=10;
        if(ready){phase=2;phaseAt=now;coverCycles++;}return true;
    }
    public void fired(long now){if(site!=null&&site.kind==COVER&&phase==4){phase=3;phaseAt=now;}}
    public void received(float amount){if(site!=null&&Float.isFinite(amount)&&amount>0)damage+=amount;}
    public void dealt(LivingEntity victim,float amount){if(site==null||amount<=0)return;if(Senses.isAllyOf(victim,self))friendly=true;else if(victim==enemy)dealt+=amount;}
    public void friendly(){if(site!=null)friendly=true;}
    public void end(boolean died){if(site==null)return;if(died){if(owner==self.getCloneBrain()&&dimension==self.level().dimension())owner.ground.record(state,site.kind,-2,false);cancel();}else if(dealt>0||damage>0||friendly)finish(true);else cancel();}
    public void finish(boolean completed){
        if(site==null)return;
        if(owner==self.getCloneBrain()&&dimension==self.level().dimension()){
            boolean exit=safe(site.escape)&&!occupied(site.escape),success=completed&&exit&&!friendly&&self.isAlive()&&dealt>0&&dealt>damage&&damage<self.getMaxHealth()*.35f&&self.getHealth()>=self.getMaxHealth()*.5f;
            float reward=friendly||!self.isAlive()?-2:Math.max(-2,Math.min(1,(dealt-damage*1.5f)/8f-.15f-(exit?0:1)));
            if(!completed)reward=Math.min(-.4f,reward);owner.ground.record(state,site.kind,reward,success);
        }
        cancel();
    }
    public void cancel(){site=null;enemy=null;owner=null;phase=0;allies=shooters=List.of();}
    public String report(){return self.getCloneBrain().ground.report("戦闘の地形",new String[]{"遮蔽","高所","狭所","開地"})+" 選択="+selected+" 再配置="+repositions+" 優位喪失="+invalidated+" 遮蔽射撃="+coverCycles+" 散開="+spreadMoves+" "+debug;}
}
