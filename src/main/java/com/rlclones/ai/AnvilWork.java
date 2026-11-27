package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;

/** Combine a real enchanted book and compatible gear in the vanilla anvil menu. */
public final class AnvilWork {
    private final ClonePlayer self;
    private final Motor motor;
    private long retryAt;
    public int combined;
    public AnvilWork(ClonePlayer self,Motor motor){this.self=self;this.motor=motor;}
    private int slot(Item item){
        var inv=self.getInventory();
        for(int i=0;i<inv.items.size();i++)if(inv.items.get(i).is(item))return i;
        return -1;
    }
    private boolean gear(ItemStack s){return s.getItem() instanceof TieredItem||s.getItem() instanceof ArmorItem
            ||s.getItem() instanceof BowItem||s.getItem() instanceof CrossbowItem||s.getItem() instanceof TridentItem
            ||s.getItem() instanceof ShieldItem||s.getItem() instanceof ElytraItem;}
    private boolean improves(ItemStack gear,ItemStack book){
        var old=EnchantmentHelper.getEnchantments(gear);
        return EnchantmentHelper.getEnchantments(book).entrySet().stream()
                .anyMatch(e->e.getKey().canEnchant(gear)&&e.getValue()>0&&old.getOrDefault(e.getKey(),0)<=e.getValue());
    }
    private BlockPos station(){
        BlockPos p=self.blockPosition(),best=null;double dist=Double.MAX_VALUE;
        var level=self.serverLevel();
        for(int x=-3;x<=3;x++)for(int y=-1;y<=1;y++)for(int z=-3;z<=3;z++){
            BlockPos q=p.offset(x,y,z);double d=self.getEyePosition().distanceToSqr(Vec3.atCenterOf(q));
            if(d<dist&&d<16&&level.hasChunkAt(q)&&level.getBlockState(q).is(BlockTags.ANVIL)){best=q;dist=d;}
        }
        return best;
    }
    private int menuSlot(AnvilMenu menu,int invIndex){
        for(Slot s:menu.slots)if(s.container==self.getInventory()&&s.getContainerSlot()==invIndex)return s.index;
        return -1;
    }
    private void move(AnvilMenu menu,int src,int dst){
        menu.clicked(src,0,ClickType.PICKUP,self);
        menu.clicked(dst,0,ClickType.PICKUP,self);
        if(!menu.getCarried().isEmpty())menu.clicked(src,0,ClickType.PICKUP,self);
    }
    public boolean tick(long now){
        if(now<retryAt||self.experienceLevel<1||!self.onGround()||self.isUsingItem()
                ||self.containerMenu!=self.inventoryMenu||!com.rlclones.clone.CloneMobs.canUseHands(self))return false;
        retryAt=now+80;
        int g=-1,b=-1;var inv=self.getInventory();
        outer:for(int i=0;i<inv.items.size();i++)if(gear(inv.items.get(i)))
            for(int j=0;j<inv.items.size();j++)if(inv.items.get(j).is(Items.ENCHANTED_BOOK)&&improves(inv.items.get(i),inv.items.get(j))){g=i;b=j;break outer;}
        if(g<0)return false;
        BlockPos pos=station();
        if(pos==null){
            int a=slot(Items.ANVIL);
            if(a<0||!Config.get(Config.ALLOW_BLOCK_PLACING,true))return false;
            for(Direction d:Direction.Plane.HORIZONTAL){
                BlockPos p=self.blockPosition().relative(d);
                if(self.serverLevel().getBlockState(p).canBeReplaced()&&self.serverLevel().getBlockState(p.below()).isFaceSturdy(self.level(),p.below(),Direction.UP)){
                    Equipment.select(self,a);motor.stop();
                    if(motor.placeBlockAt(p))return true;
                }
            }
            return false;
        }
        if(!self.mayInteract(self.serverLevel(),pos))return false;
        motor.stop();self.setShiftKeyDown(false);motor.sneak(false);
        motor.useOnFace(pos,Direction.UP);
        if(!(self.containerMenu instanceof AnvilMenu menu))return false;
        try{
            int gs=menuSlot(menu,g),bs=menuSlot(menu,b);
            if(gs<0||bs<0||!menu.getCarried().isEmpty())return false;
            ItemStack before=inv.items.get(g).copy();
            move(menu,gs,0);move(menu,bs,1);
            ItemStack result=menu.getSlot(2).getItem();
            var old=EnchantmentHelper.getEnchantments(before);
            boolean better=EnchantmentHelper.getEnchantments(result).entrySet().stream()
                    .anyMatch(e->e.getValue()>old.getOrDefault(e.getKey(),0));
            if(!result.isEmpty()&&result.is(before.getItem())&&better&&menu.getSlot(2).mayPickup(self)){
                menu.clicked(2,0,ClickType.QUICK_MOVE,self);
                if(menu.getSlot(2).getItem().isEmpty())combined++;
            }
        }finally{self.closeContainer();}
        return true;
    }
}
