package com.zawu.chesspvp.mixin;

import com.github.tartaricacid.touhoulittlemaid.api.game.xqwlight.Position;
import com.github.tartaricacid.touhoulittlemaid.block.BlockCChess;
import com.github.tartaricacid.touhoulittlemaid.block.BlockGomoku;
import com.github.tartaricacid.touhoulittlemaid.block.properties.GomokuPart;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntitySit;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitSounds;
import com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityCChess;
import com.github.tartaricacid.touhoulittlemaid.util.CChessUtil;
import com.zawu.chesspvp.util.ChessPvpUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockCChess.class)
public abstract class BlockCChessPvpMixin {
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

        GomokuPart part = state.getValue(BlockCChess.PART);
        BlockPos centerPos = pos.subtract(new Vec3i(part.getPosX(), 0, part.getPosY()));
        BlockEntity be = level.getBlockEntity(centerPos);
        if (!(be instanceof TileEntityCChess chess)) {
            return;
        }

        CompoundTag data = chess.getPersistentData();
        // If a maid is seated, keep original maid-vs-player logic.
        if (serverLevel.getEntity(chess.getSitId()) instanceof EntitySit sit && sit.getFirstPassenger() instanceof EntityMaid) {
            return;
        }

        // only empty hand
        if (!player.getMainHandItem().isEmpty()) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }

        Direction facing = state.getValue(BlockGomoku.FACING);
        Vec3 clickPos = hit.getLocation()
                .subtract(pos.getX(), pos.getY(), pos.getZ())
                .add(part.getPosX() - 0.5, 0, part.getPosY() - 0.5)
                .yRot(facing.toYRot() * Mth.DEG_TO_RAD);

        // reset
        if (CChessUtil.isClickResetArea(clickPos)) {
            if (ChessPvpUtil.hasPvp(data) && !ChessPvpUtil.isP1(data, serverPlayer) && !ChessPvpUtil.isP2(data, serverPlayer)) {
                cir.setReturnValue(InteractionResult.FAIL);
                return;
            }
            chess.reset();
            ChessPvpUtil.clearPvp(data);
            chess.refresh();
            level.playSound(null, centerPos, InitSounds.GOMOKU_RESET.get(), SoundSource.BLOCKS, 1.0f, 1.0f);
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }

        // auto-join
        if (ChessPvpUtil.getP1(data) == null) {
            ChessPvpUtil.setP1(data, serverPlayer.getUUID());
            chess.refresh();
            serverPlayer.sendSystemMessage(Component.literal("[ChessPVP] 你加入了对局(玩家1/红方)。"));
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }
        if (ChessPvpUtil.getP2(data) == null && !ChessPvpUtil.isP1(data, serverPlayer)) {
            ChessPvpUtil.setP2(data, serverPlayer.getUUID());
            chess.refresh();
            serverPlayer.sendSystemMessage(Component.literal("[ChessPVP] 你加入了对局(玩家2/黑方)。"));
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }

        // spectator: cannot interact
        if (!ChessPvpUtil.isP1(data, serverPlayer) && !ChessPvpUtil.isP2(data, serverPlayer)) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }

        if (!ChessPvpUtil.hasBoth(data)) {
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }

        boolean isP1Turn = chess.isPlayerTurn();
        if ((isP1Turn && !ChessPvpUtil.isP1(data, serverPlayer)) || (!isP1Turn && !ChessPvpUtil.isP2(data, serverPlayer))) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }

        // no click on board
        int nowClick = CChessUtil.getClickPosition(clickPos);
        if (nowClick < 0 || !Position.IN_BOARD(nowClick)) {
            cir.setReturnValue(InteractionResult.PASS);
            return;
        }

        // cannot play if draw/lose
        if (chess.isMoveNumberLimit() || chess.isRepeat()) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        if (chess.isCheckmate()) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }

        Position chessData = chess.getChessData();
        byte[] squares = chessData.squares;
        int preClick = chess.getSelectChessPoint();
        if (preClick < 0 || squares.length <= preClick) {
            preClick = 0;
        }
        byte prePiece = squares[preClick];
        byte nowPiece = squares[nowClick];

        boolean ownPieceNow;
        boolean ownPiecePre;
        if (isP1Turn) {
            ownPieceNow = CChessUtil.isRed(nowPiece);
            ownPiecePre = CChessUtil.isRed(prePiece);
        } else {
            ownPieceNow = CChessUtil.isBlack(nowPiece);
            ownPiecePre = CChessUtil.isBlack(prePiece);
        }

        // no selection yet
        if (prePiece <= 0 || !ownPiecePre) {
            if (ownPieceNow) {
                chess.setSelectChessPoint(nowClick);
                chess.refresh();
                level.playSound(null, pos, InitSounds.GOMOKU.get(), SoundSource.BLOCKS, 1.0f, 0.8F + level.getRandom().nextFloat() * 0.4F);
            }
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }

        // reselect
        if (ownPiecePre && ownPieceNow) {
            chess.setSelectChessPoint(nowClick);
            chess.refresh();
            level.playSound(null, pos, InitSounds.GOMOKU.get(), SoundSource.BLOCKS, 1.0f, 0.8F + level.getRandom().nextFloat() * 0.4F);
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }

        int move = Position.MOVE(preClick, nowClick);
        if (!chessData.legalMove(move)) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }

        boolean notChecked = chessData.makeMove(move);
        if (notChecked) {
            if (chessData.captured()) {
                chessData.setIrrev();
            }
            chess.setCheckmate(chessData.isMate());
            if (chess.isCheckmate()) {
                ChessPvpUtil.awardChessSage(serverPlayer);
            }
            if (!chess.isCheckmate()) {
                if (CChessUtil.reachMoveLimit(chessData)) {
                    chess.setMoveNumberLimit(true);
                } else if (CChessUtil.isRepeat(chessData)) {
                    chess.setRepeat(true);
                }
            }
            chess.addChessCounter();
            chess.setSelectChessPoint(nowClick);
            chess.refresh();
            level.playSound(null, pos, InitSounds.GOMOKU.get(), SoundSource.BLOCKS, 1.0f, 0.8F + level.getRandom().nextFloat() * 0.4F);
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }

        player.sendSystemMessage(Component.translatable("message.touhou_little_maid.cchess.check"));
        level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 1.0f, 0.8F + level.getRandom().nextFloat() * 0.4F);
        cir.setReturnValue(InteractionResult.FAIL);
    }
}
