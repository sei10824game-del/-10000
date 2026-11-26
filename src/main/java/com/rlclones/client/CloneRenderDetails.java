package com.rlclones.client;

import com.rlclones.Config;
import com.rlclones.RLClones;
import com.rlclones.clone.CloneProfiles;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=RLClones.MODID,value=Dist.CLIENT)
public final class CloneRenderDetails {
    private CloneRenderDetails() {}
    // A client player's final profile is marked before joining. Weak keys avoid retaining unloaded players.
    private static final java.util.Map<Player,Boolean> synthetic = new java.util.WeakHashMap<>();
    private static boolean synthetic(Player player) {
        Boolean known=synthetic.get(player);
        if(known!=null)return known;
        boolean value=CloneProfiles.isSynthetic(player.getGameProfile());
        if(synthetic.size()>=4096)synthetic.clear();
        synthetic.put(player,value);return value;
    }
    public static boolean distant(Entity entity,double limit) {
        if(!(entity instanceof Player player) || !Config.get(RenderDetailConfig.ENABLED,true))return false;
        var mc=Minecraft.getInstance();
        if(mc.level==null || entity.level()!=mc.level || mc.getCameraEntity()==null)return false;
        boolean focused=entity==mc.getCameraEntity() || entity==mc.crosshairPickEntity;
        return RenderDetailPolicy.simplify(true,synthetic(player),focused,
                entity.position().distanceToSqr(mc.gameRenderer.getMainCamera().getPosition()),limit);
    }
    public static boolean cosmetic(Entity entity){return distant(entity,Config.get(RenderDetailConfig.DETAIL_DISTANCE,32.0));}
    @SubscribeEvent public static void nameTag(RenderNameTagEvent event){
        if(distant(event.getEntity(),Config.get(RenderDetailConfig.NAME_DISTANCE,32.0)))event.setResult(Event.Result.DENY);
    }
}
