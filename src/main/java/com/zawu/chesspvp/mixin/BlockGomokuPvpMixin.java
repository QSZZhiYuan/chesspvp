package com.zawu.chesspvp.mixin;

import com.github.tartaricacid.touhoulittlemaid.api.game.gomoku.Point;
import com.github.tartaricacid.touhoulittlemaid.api.game.gomoku.Statue;
import com.github.tartaricacid.touhoulittlemaid.block.BlockGomoku;
import com.github.tartaricacid.touhoulittlemaid.block.properties.GomokuPart;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntitySit;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidGomokuAI;
import com.github.tartaricacid.touhoulittlemaid.init.InitSounds;
import com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityGomoku;
import com.zawu.chesspvp.util.ChessPvpUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockGomoku.class)
public abstract class BlockGomokuPvpMixin {
    @Shadow(remap = false)
    private static boolean isClickChessBox(double x, double y, GomokuPart part, Direction direction) {
        throw new AssertionError();
    }

    @Shadow(remap = false)
    private static int[] getChessPos(double x, double y, GomokuPart part) {
        throw new AssertionError();
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true, remap = false)
    private void chesspvp$use(ItemStack itemStack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit,
                              CallbackInfoReturnable<InteractionResult> cir) {
        if (!(level instanceof ServerLevel serverLevel) || hand != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (!itemStack.isEmpty()) {
            return;
        }

        GomokuPart part = state.getValue(BlockGomoku.PART);
        BlockPos centerPos = pos.subtract(new Vec3i(part.getPosX(), 0, part.getPosY()));
        BlockEntity be = level.getBlockEntity(centerPos);
        if (!(be instanceof TileEntityGomoku gomoku)) {
            return;
        }

        CompoundTag data = gomoku.getPersistentData();

        // If a maid is seated, keep original maid-vs-player logic.
        if (serverLevel.getEntity(gomoku.getSitId()) instanceof EntitySit sit && sit.getFirstPassenger() instanceof EntityMaid) {
            return;
        }

        // pvp mode: we fully handle interaction
        if (!player.getMainHandItem().isEmpty()) {
            cir.setReturnValue(InteractionResult.PASS);
            return;
        }

        Vec3 location = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
        Direction facing = state.getValue(BlockGomoku.FACING);

        // reset
        if (isClickChessBox(location.x, location.z, part, facing)) {
            if (ChessPvpUtil.hasPvp(data) && !ChessPvpUtil.isP1(data, serverPlayer) && !ChessPvpUtil.isP2(data, serverPlayer)) {
                cir.setReturnValue(InteractionResult.FAIL);
                return;
            }
            gomoku.reset();
            ChessPvpUtil.clearPvp(data);
            gomoku.refresh();
            level.playSound(null, centerPos, InitSounds.GOMOKU_RESET.get(), SoundSource.BLOCKS, 1.0f, 1.0f);
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }

        // auto-join
        if (ChessPvpUtil.getP1(data) == null) {
            ChessPvpUtil.setP1(data, serverPlayer.getUUID());
            ChessPvpUtil.ensureTurnInitialized(data);
            gomoku.refresh();
            serverPlayer.sendSystemMessage(Component.literal("[ChessPVP] 你加入了对局(玩家1/黑子)。"));
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }
        if (ChessPvpUtil.getP2(data) == null && !ChessPvpUtil.isP1(data, serverPlayer)) {
            ChessPvpUtil.setP2(data, serverPlayer.getUUID());
            ChessPvpUtil.ensureTurnInitialized(data);
            gomoku.refresh();
            serverPlayer.sendSystemMessage(Component.literal("[ChessPVP] 你加入了对局(玩家2/白子)。"));
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }

        // spectator: cannot interact
        if (!ChessPvpUtil.isP1(data, serverPlayer) && !ChessPvpUtil.isP2(data, serverPlayer)) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }

        if (!ChessPvpUtil.hasBoth(data)) {
            // waiting for second player
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }

        ChessPvpUtil.ensureTurnInitialized(data);
        int turn = ChessPvpUtil.getTurn(data); // 0 -> p1 black, 1 -> p2 white
        if ((turn == 0 && !ChessPvpUtil.isP1(data, serverPlayer)) || (turn == 1 && !ChessPvpUtil.isP2(data, serverPlayer))) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }

        if (gomoku.getStatue() != Statue.IN_PROGRESS) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }

        int[] clickPos = getChessPos(location.x, location.z, part);
        if (clickPos == null) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }

        byte[][] board = gomoku.getChessData();
        int x = clickPos[0];
        int y = clickPos[1];
        if (board[x][y] != Point.EMPTY) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }

        int type = (turn == 0) ? Point.BLACK : Point.WHITE;
        Point movePoint = new Point(x, y, type);
        gomoku.setPlayerTurn(turn == 0);
        gomoku.setChessData(x, y, type);
        gomoku.setStatue(MaidGomokuAI.getStatue(board, movePoint));
        level.playSound(null, centerPos, InitSounds.GOMOKU.get(), SoundSource.BLOCKS, 1.0f, 0.8F + level.getRandom().nextFloat() * 0.4F);

        if (gomoku.getStatue() == Statue.WIN) {
            ChessPvpUtil.awardChessSage(serverPlayer);
        }
        if (gomoku.getStatue() == Statue.IN_PROGRESS) {
            ChessPvpUtil.toggleTurn(data);
            gomoku.setPlayerTurn(ChessPvpUtil.getTurn(data) == 0);
        }

        gomoku.refresh();
        cir.setReturnValue(InteractionResult.SUCCESS);
    }
}
