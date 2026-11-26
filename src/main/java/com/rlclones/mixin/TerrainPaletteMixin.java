package com.rlclones.mixin;

import com.rlclones.ai.util.TerrainSections;
import com.rlclones.ai.util.TerrainVersion;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Watch the storage primitive, rather than relying on optional place/break Forge events. */
@Mixin(PalettedContainer.class)
public abstract class TerrainPaletteMixin implements TerrainVersion {
    @Unique private volatile long rlclones$version;
    @Unique private volatile boolean rlclones$watched;
    @Override public long rlclones$terrainVersion(){return rlclones$version;}
    @Override public void rlclones$watchTerrain(){rlclones$watched=true;}
    @Unique private void rlclones$changed(int index,Object value){
        if(value instanceof BlockState state){
            long version=++rlclones$version;
            if(rlclones$watched)TerrainSections.changed((PalettedContainer<?>)(Object)this,version,index,state);
        }
    }
    @Inject(method="getAndSet(ILjava/lang/Object;)Ljava/lang/Object;",at=@At("RETURN"))
    private void rlclones$swap(int index,Object value,CallbackInfoReturnable<Object> ci){
        if(ci.getReturnValue()!=value)rlclones$changed(index,value);
    }
    @Inject(method="set(ILjava/lang/Object;)V",at=@At("RETURN"))
    private void rlclones$set(int index,Object value,CallbackInfo ci){rlclones$changed(index,value);}
    @Inject(method="read",at=@At("RETURN"))
    private void rlclones$bulkRead(FriendlyByteBuf buffer,CallbackInfo ci){++rlclones$version;}
}
