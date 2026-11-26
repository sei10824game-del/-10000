package com.rlclones.ai;

import com.rlclones.Config;
import com.rlclones.clone.*;
import net.minecraft.core.*;
import net.minecraft.world.item.*;
import java.util.*;

/** Quarry stones become real shared paths, plazas and covered work shelters. */
public final class Masonry {
    private Masonry(){}
    private static final Set<Item> STONES=Set.of(Items.ANDESITE,Items.DIORITE,Items.GRANITE,Items.TUFF,Items.DEEPSLATE,Items.COBBLED_DEEPSLATE,Items.CALCITE,Items.SMOOTH_BASALT,Items.DRIPSTONE_BLOCK,Items.POLISHED_ANDESITE,Items.POLISHED_DIORITE,Items.POLISHED_GRANITE,Items.STONE_BRICKS,Items.DEEPSLATE_BRICKS);
    public static boolean material(Item item){return STONES.contains(item);}
    public static int count(net.minecraft.world.entity.player.Player p){int n=0;for(var s:p.getInventory().items)if(material(s.getItem()))n+=s.getCount();return n;}
    public static int slot(ClonePlayer p){int best=-1,quantity=0;for(int i=0;i<p.getInventory().items.size();i++){var s=p.getInventory().items.get(i);if(material(s.getItem())&&Equipment.isBuildingBlock(p,s)&&s.getCount()>quantity){best=i;quantity=s.getCount();}}return best;}
    public static List<BlockPos> blueprint(String kind,BlockPos origin){var out=new ArrayList<BlockPos>();switch(kind){
        case "STONE_PATH"->{for(int z=0;z<7;z++)for(int x=0;x<2;x++)out.add(origin.offset(x,0,z));}
        case "STONE_PLAZA"->{for(int x=0;x<5;x++)for(int z=0;z<5;z++)out.add(origin.offset(x,0,z));}
        case "STONE_SHELTER"->{for(int y=0;y<2;y++)for(int x:new int[]{0,2})for(int z:new int[]{0,2})out.add(origin.offset(x,y,z));for(int x=0;x<3;x++)for(int z=0;z<3;z++)out.add(origin.offset(x,2,z));}
        default->throw new IllegalArgumentException(kind);
    }return out;}
    public static Bases.Plan propose(ClonePlayer p){
        if(!Config.get(Config.ALLOW_BLOCK_PLACING,true)||count(p)<14)return null;
        var bases=Bases.get(p.getServer());var base=bases.nearest(p.level().dimension(),p.position(),24);if(base==null||bases.openPlanNear(p.level().dimension(),p.position(),48)!=null)return null;
        for(String kind:List.of("STONE_PATH","STONE_SHELTER","STONE_PLAZA")){
            if(bases.hasProject(p.level().dimension(),kind,base.center,32))continue;
            for(Direction direction:Direction.Plane.HORIZONTAL){BlockPos origin=base.center.relative(direction,6);var cells=blueprint(kind,origin);if(cells.size()>count(p))continue;boolean safe=true;
                for(BlockPos cell:cells){if(!p.level().hasChunkAt(cell)||!p.level().getFluidState(cell).isEmpty()||!p.level().getBlockState(cell).canBeReplaced()||p.level().getBlockState(cell).hasBlockEntity()){safe=false;break;}
                    BlockPos floor=new BlockPos(cell.getX(),origin.getY()-1,cell.getZ());if(!p.level().getBlockState(floor).isFaceSturdy(p.level(),floor,Direction.UP)||!p.level().getFluidState(floor).isEmpty()){safe=false;break;}
                    if(!kind.equals("STONE_SHELTER")&&(!p.level().getBlockState(cell.above()).getCollisionShape(p.level(),cell.above()).isEmpty()||!p.level().getBlockState(cell.above(2)).getCollisionShape(p.level(),cell.above(2)).isEmpty())){safe=false;break;}}
                if(!safe)continue;var plan=bases.addPlan(p.level().dimension(),kind,cells,kind.equals("STONE_SHELTER")?4:5);plan.ordered=true;bases.addProject(p.level().dimension(),kind,base.center);return plan;
            }
        }
        return null;
    }
}
