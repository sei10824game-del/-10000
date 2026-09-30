package com.rlclones.item;

import com.rlclones.Config;
import com.rlclones.clone.CloneManager;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/** Spawn egg that summons a learning clone of the player who uses it (same as pressing Z). */
public class CloneSpawnEggItem extends Item {
    public static final int BASE_COLOR = 0x2E6FD8;
    public static final int SPOT_COLOR = 0x7CF2E4;

    public CloneSpawnEggItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }
        Player player = ctx.getPlayer();
        ServerPlayer summoner = player instanceof ServerPlayer sp ? sp : null;
        CloneManager manager = CloneManager.get(serverLevel.getServer());
        if (summoner != null && !manager.mayControl(summoner)) {
            summoner.displayClientMessage(Component.translatable("rlclones.msg.no_permission").withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }
        BlockPos pos = ctx.getClickedPos();
        BlockState state = level.getBlockState(pos);
        BlockPos spawn = state.getCollisionShape(level, pos).isEmpty() ? pos : pos.relative(ctx.getClickedFace());
        float yaw = player != null ? player.getYRot() + 180f : 0f;
        ClonePlayer clone = manager.summon(summoner, serverLevel, Vec3.atBottomCenterOf(spawn), yaw);
        if (clone == null) {
            if (player != null) {
                player.displayClientMessage(Component.translatable("rlclones.msg.limit", Config.cloneLimitLabel()).withStyle(ChatFormatting.RED), true);
            }
            return InteractionResult.FAIL;
        }
        if (player != null) {
            player.displayClientMessage(Component.translatable("rlclones.msg.summoned", clone.getGameProfile().getName(), manager.clones().size(), Config.cloneLimitLabel()).withStyle(ChatFormatting.AQUA), true);
            if (!player.getAbilities().instabuild) {
                ctx.getItemInHand().shrink(1);
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.rlclones.clone_spawn_egg.tooltip").withStyle(ChatFormatting.GRAY));
    }
}
