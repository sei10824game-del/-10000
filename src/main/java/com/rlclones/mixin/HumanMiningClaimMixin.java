package com.rlclones.mixin;
import com.rlclones.ai.BlockClaims;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.core.*;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe vanilla's accepted mining state; never intercept or cancel a human action. */
@Mixin(ServerPlayerGameMode.class)
public abstract class HumanMiningClaimMixin {
    @Shadow @Final protected ServerPlayer player;
    @Shadow protected ServerLevel level;
    @Shadow private boolean isDestroyingBlock;
    @Shadow private BlockPos destroyPos;
    @Shadow private boolean hasDelayedDestroy;
    @Shadow private BlockPos delayedDestroyPos;
    @Unique private void rlclones$observe() {
        if(player instanceof ClonePlayer)return;
        BlockPos pos=isDestroyingBlock?destroyPos:hasDelayedDestroy?delayedDestroyPos:null;
        if(player.isAlive()&&!player.isRemoved()&&player.level()==level&&pos!=null&&!level.getBlockState(pos).isAir())BlockClaims.human(player,pos);
        else BlockClaims.humanStopped(player.getUUID());
    }
    @Inject(method="handleBlockBreakAction",at=@At("RETURN"))
    private void rlclones$action(BlockPos pos,ServerboundPlayerActionPacket.Action action,Direction face,int height,int sequence,CallbackInfo ci){rlclones$observe();}
    @Inject(method="tick",at=@At("RETURN"))
    private void rlclones$tick(CallbackInfo ci){rlclones$observe();}
}
