package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Use vanilla enchanting offers, spending actual lapis and experience. */
public final class EnchantingWork {
    private final ClonePlayer self;
    private final Motor motor;
    private long retryAt;
    public int enchanted;
    public EnchantingWork(ClonePlayer self,Motor motor){this.self=self;this.motor=motor;}
    private int slot(Item item){
        for(int i=0;i<self.getInventory().items.size();i++)if(self.getInventory().items.get(i).is(item))return i;
        return -1;
    }
    private boolean eligible(ItemStack s){
        return !s.isEmpty()&&s.getCount()==1&&s.isEnchantable()&&!s.isEnchanted()
                &&(s.getItem() instanceof TieredItem||s.getItem() instanceof ArmorItem
                ||s.getItem() instanceof BowItem||s.getItem() instanceof CrossbowItem||s.getItem() instanceof TridentItem);
    }
    private int menuSlot(EnchantmentMenu m,int i){
        for(Slot s:m.slots)if(s.container==self.getInventory()&&s.getContainerSlot()==i)return s.index;
        return -1;
    }
    private boolean move(EnchantmentMenu m,int from,int to){
        if(from<0||!m.getCarried().isEmpty())return false;
        m.clicked(from,0,ClickType.PICKUP,self);
        m.clicked(to,0,ClickType.PICKUP,self);
        if(!m.getCarried().isEmpty())m.clicked(from,0,ClickType.PICKUP,self);
        return m.getCarried().isEmpty()&&m.getSlot(to).hasItem();
    }
    private BlockPos station(){
        var level=self.serverLevel();BlockPos best=null,p=self.blockPosition();double dist=16;
        Vec3 eye=self.getEyePosition();
        for(int x=-3;x<=3;x++)for(int y=-1;y<=1;y++)for(int z=-3;z<=3;z++){
            BlockPos q=p.offset(x,y,z);double d=eye.distanceToSqr(Vec3.atCenterOf(q));
            if(d>=dist||!level.hasChunkAt(q)||!level.getBlockState(q).is(Blocks.ENCHANTING_TABLE))continue;
            var ray=level.clip(new ClipContext(eye,Vec3.atCenterOf(q),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,self));
            if(ray.getType()==HitResult.Type.BLOCK&&!ray.getBlockPos().equals(q))continue;
            best=q;dist=d;
        }
        return best;
    }
    public boolean tick(long now){
        if(now<retryAt||self.experienceLevel<1||!self.onGround()||self.isUsingItem()||self.isPassenger()
                ||self.containerMenu!=self.inventoryMenu||!com.rlclones.clone.CloneMobs.canUseHands(self))return false;
        retryAt=now+100;
        int lapis=slot(Items.LAPIS_LAZULI);if(lapis<0)return false;
        int gear=-1;double score=-1;
        for(int i=0;i<self.getInventory().items.size();i++){
            ItemStack s=self.getInventory().items.get(i);if(!eligible(s))continue;
            double v=ItemValue.of(self,s);if(v>score){gear=i;score=v;}
        }
        if(gear<0)return false;
        BlockPos p=station();
        if(p==null){
            int table=slot(Items.ENCHANTING_TABLE);
            if(table<0||!Config.get(Config.ALLOW_BLOCK_PLACING,true))return false;
            for(Direction d:Direction.Plane.HORIZONTAL){
                BlockPos q=self.blockPosition().relative(d);
                if(!self.serverLevel().getBlockState(q).canBeReplaced()
                        ||!self.serverLevel().getBlockState(q.below()).isFaceSturdy(self.level(),q.below(),Direction.UP))continue;
                Equipment.select(self,table);motor.stop();
                if(motor.placeBlockAt(q))return true;
            }
            return false;
        }
        if(!self.mayInteract(self.serverLevel(),p))return false;
        motor.stop();self.setShiftKeyDown(false);motor.sneak(false);
        motor.useOnFace(p,Direction.UP);
        if(!(self.containerMenu instanceof EnchantmentMenu m))return false;
        try{
            int g=menuSlot(m,gear),l=menuSlot(m,lapis);
            if(!move(m,g,0)||!move(m,l,1)||!m.getSlot(1).getItem().is(Items.LAPIS_LAZULI))return false;
            for(int offer=2;offer>=0;offer--){
                if(m.costs[offer]<=0||self.experienceLevel<m.costs[offer]||self.experienceLevel<offer+1
                        ||m.getSlot(1).getItem().getCount()<offer+1)continue;
                if(m.clickMenuButton(self,offer)&&m.getSlot(0).getItem().isEnchanted()){
                    enchanted++;break;
                }
            }
        }finally{self.closeContainer();}
        return true;
    }
}
