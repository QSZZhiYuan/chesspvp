package com.zawu.chesspvp;

import com.github.tartaricacid.touhoulittlemaid.block.BlockCChess;
import com.github.tartaricacid.touhoulittlemaid.block.BlockGomoku;
import com.github.tartaricacid.touhoulittlemaid.block.BlockWChess;
import com.github.tartaricacid.touhoulittlemaid.block.properties.GomokuPart;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntitySit;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitBlocks;
import com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityCChess;
import com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityGomoku;
import com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityJoy;
import com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityWChess;
import com.zawu.chesspvp.util.ChessPvpUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.util.ProblemReporter;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.zawu.chesspvp.network.ChessPvpNetwork;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@EventBusSubscriber(modid = ChessPvpMod.MODID)
public final class ChessPvpBoardCarry {
    private static final String CARRY_KEY = "ChessPvpCarriedBoard";
    private static final String BOARD_DATA = "BlockEntity";
    private static final String RESTORE_PENDING = "RestorePending";
    private static final String BONUS_TURN = "BonusTurn";
    private static final int GOMOKU = 0;
    private static final int WESTERN = 1;
    private static final int CHINESE = 2;

    private ChessPvpBoardCarry() {
    }

    public static boolean hasBoard(ServerPlayer player) {
        return player.getPersistentData().contains(CARRY_KEY);
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Player player = event.getEntity();
        if (!player.isShiftKeyDown() || !player.mayBuild()) {
            return;
        }
        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (boardType(state.getBlock()) < 0) {
            return;
        }

        // Cancel on both sides, including creative mode, so this gesture never mines a board.
        event.setCanceled(true);
        // Only a client START sends a request. Vanilla mining packets never toggle carry state.
        if (event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START
                && event.getLevel().isClientSide()) {
            ChessPvpNetwork.request(event.getPos());
        }
    }

    /** Entry point for a decoded client request. The client supplies only a position. */
    public static boolean requestCarry(ServerPlayer player, BlockPos pos) {
        ServerLevel level = (ServerLevel) player.level();
        if (!player.mayBuild() || !player.isWithinBlockInteractionRange(pos, 1.0)
                || !level.mayInteract(player, pos) || !level.getWorldBorder().isWithinBounds(pos)
                || level.getServer().isUnderSpawnProtection(level, pos, player) ) {
            player.sendSystemMessage(Component.translatable("message.chesspvp.board.not_allowed"));
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (boardType(state.getBlock()) < 0) return false;
        boolean success = handleShiftLeftClick(level, state, pos, player);
        if (!success) {
            player.sendSystemMessage(Component.translatable("message.chesspvp.board.invalid_structure"));
        }
        return success;
    }

    public static CompoundTag getCarriedData(ServerPlayer player) {
        return player.getPersistentData().getCompoundOrEmpty(CARRY_KEY).copy();
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer viewer && event.getTarget() instanceof ServerPlayer carrier) {
            ChessPvpNetwork.syncTo(viewer, carrier);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) ChessPvpNetwork.syncTo(player, player);
    }

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) ChessPvpNetwork.syncTo(player, player);
    }

    public static boolean handleShiftLeftClick(ServerLevel level, BlockState clickedState, BlockPos clickedPos,
                                                ServerPlayer player) {
        CompoundTag playerData = player.getPersistentData();
        if (playerData.contains(CARRY_KEY)) {
            CompoundTag carried = playerData.getCompoundOrEmpty(CARRY_KEY);
            carried.putBoolean(RESTORE_PENDING, true);
            playerData.put(CARRY_KEY, carried);
            if (!tryRestore(player)) {
                player.sendSystemMessage(Component.translatable("message.chesspvp.board.restore_blocked"));
            }
            return true;
        }

        if (tryPickUp(level, clickedState, clickedPos, player)) {
            return true;
        }
        return false;
    }

    private static boolean tryPickUp(ServerLevel level, BlockState clickedState, BlockPos clickedPos,
                                     ServerPlayer player) {
        int type = boardType(clickedState.getBlock());
        if (type < 0) {
            return false;
        }

        GomokuPart clickedPart = getPart(clickedState, type);
        Direction facing = clickedState.getValue(BlockGomoku.FACING);
        BlockPos center = clickedPos.offset(-clickedPart.getPosX(), 0, -clickedPart.getPosY());
        Block block = blockFor(type);
        List<BlockPos> positions = new ArrayList<>(GomokuPart.values().length);
        for (GomokuPart part : GomokuPart.values()) {
            BlockPos partPos = center.offset(part.getPosX(), 0, part.getPosY());
            BlockState expected = stateFor(type, part, facing);
            BlockState actual = level.getBlockState(partPos);
            if (actual.getBlock() != block || getPart(actual, type) != part
                    || actual.getValue(BlockGomoku.FACING) != facing || !level.hasChunkAt(partPos)) {
                return false;
            }
            positions.add(partPos);
        }

        BlockEntity blockEntity = level.getBlockEntity(center);
        if (!(blockEntity instanceof TileEntityJoy joy) || !matchesBoardEntity(type, joy)) {
            return false;
        }
        CompoundTag carried = new CompoundTag();
        carried.putInt("Type", type);
        carried.putString("Dimension", level.dimension().toString());
        carried.putLong("Center", center.asLong());
        carried.putInt("Facing", facing.get3DDataValue());
        carried.put(BOARD_DATA, blockEntity.saveWithoutMetadata(level.registryAccess()));
        player.getPersistentData().put(CARRY_KEY, carried);

        // BlockJoy discards its seat on removal. The snapshot already has the real UUID;
        // detach only the old block entity so carrying does not eject the seated maid.
        joy.setSitId(new UUID(0L, 0L));
        for (BlockPos partPos : positions) {
            level.setBlock(partPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        ChessPvpNetwork.sync(player);
        player.sendSystemMessage(Component.translatable("message.chesspvp.board.picked_up"));
        return true;
    }

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            smash(player, event.getTarget());
        }
    }

    public static boolean smash(ServerPlayer player, Entity target) {
        if (!(target instanceof Player || target instanceof EntityMaid) || !hasBoard(player)) {
            return false;
        }
        CompoundTag carried = player.getPersistentData().getCompoundOrEmpty(CARRY_KEY);
        carried.putBoolean(RESTORE_PENDING, true);
        carried.putBoolean(BONUS_TURN, true);
        player.getPersistentData().put(CARRY_KEY, carried);
        if (!tryRestore(player)) {
            player.sendSystemMessage(Component.translatable("message.chesspvp.board.restore_blocked"));
        }
        return true;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (hasBoard(player) && player.getPersistentData().getCompoundOrEmpty(CARRY_KEY).getBooleanOr(RESTORE_PENDING, false)) {
                tryRestore(player);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && hasBoard(player)) {
            CompoundTag carried = player.getPersistentData().getCompoundOrEmpty(CARRY_KEY);
            carried.putBoolean(RESTORE_PENDING, true);
            player.getPersistentData().put(CARRY_KEY, carried);
            tryRestore(player);
        }
    }

    private static boolean tryRestore(ServerPlayer player) {
        CompoundTag playerData = player.getPersistentData();
        if (!playerData.contains(CARRY_KEY)) {
            return false;
        }
        CompoundTag carried = playerData.getCompoundOrEmpty(CARRY_KEY);
        ServerLevel targetLevel = findLevel(player, carried.getStringOr("Dimension", ""));
        if (targetLevel == null) {
            return false;
        }

        int type = carried.getIntOr("Type", -1);
        if (type < GOMOKU || type > CHINESE) {
            return false;
        }
        BlockPos center = BlockPos.of(carried.getLongOr("Center", 0L));
        Direction facing = Direction.from3DDataValue(carried.getIntOr("Facing", 0));
        Block block = blockFor(type);
        List<BlockPos> positions = new ArrayList<>(GomokuPart.values().length);
        boolean alreadyPlaced = true;
        for (GomokuPart part : GomokuPart.values()) {
            BlockPos partPos = center.offset(part.getPosX(), 0, part.getPosY());
            if (!targetLevel.hasChunkAt(partPos)) {
                return false;
            }
            BlockState expected = stateFor(type, part, facing);
            BlockState actual = targetLevel.getBlockState(partPos);
            if (actual.getBlock() != block || getPart(actual, type) != part
                    || actual.getValue(BlockGomoku.FACING) != facing) {
                alreadyPlaced = false;
                if (!actual.canBeReplaced()) {
                    return false;
                }
            }
            positions.add(partPos);
        }

        BlockEntity blockEntity = targetLevel.getBlockEntity(center);
        if (alreadyPlaced && blockEntity instanceof TileEntityJoy joy && matchesBoardEntity(type, joy)
                && joy.saveWithoutMetadata(targetLevel.registryAccess()).equals(carried.getCompoundOrEmpty(BOARD_DATA))) {
            finishRestore(player, carried, joy);
            return true;
        }

        List<BlockPos> placed = new ArrayList<>(positions.size());
        for (GomokuPart part : GomokuPart.values()) {
            BlockPos partPos = center.offset(part.getPosX(), 0, part.getPosY());
            if (!targetLevel.setBlock(partPos, stateFor(type, part, facing), Block.UPDATE_ALL)) {
                rollbackPlacement(targetLevel, type, block, placed);
                return false;
            }
            placed.add(partPos);
        }

        blockEntity = targetLevel.getBlockEntity(center);
        if (!(blockEntity instanceof TileEntityJoy joy) || !matchesBoardEntity(type, joy)) {
            rollbackPlacement(targetLevel, type, block, placed);
            return false;
        }
        joy.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, targetLevel.registryAccess(), carried.getCompoundOrEmpty(BOARD_DATA)));
        joy.setChanged();
        finishRestore(player, carried, joy);
        return true;
    }

    private static void finishRestore(ServerPlayer player, CompoundTag carried, TileEntityJoy joy) {
        if (carried.getBooleanOr(BONUS_TURN, false)) {
            grantExtraTurn(joy, player);
        }
        joy.refresh();
        player.getPersistentData().remove(CARRY_KEY);
        ChessPvpNetwork.sync(player);
        player.sendSystemMessage(Component.translatable("message.chesspvp.board.restored"));
    }

    private static void grantExtraTurn(TileEntityJoy joy, ServerPlayer player) {
        CompoundTag data = joy.getPersistentData();
        boolean maidGame = joy.getLevel() instanceof ServerLevel level
                && level.getEntity(joy.getSitId()) instanceof EntitySit seat
                && seat.getFirstPassenger() instanceof EntityMaid;
        if (!maidGame && !ChessPvpUtil.hasBoth(data)) return;
        boolean playerIsP1 = maidGame || ChessPvpUtil.isP1(data, player);
        if (!maidGame && !playerIsP1 && !ChessPvpUtil.isP2(data, player)) {
            return;
        }

        if (joy instanceof TileEntityGomoku gomoku) {
            if (gomoku.getStatue() != com.github.tartaricacid.touhoulittlemaid.api.game.gomoku.Statue.IN_PROGRESS) {
                return;
            }
            int playerSide = playerIsP1 ? 0 : 1;
            if (maidGame) {
                gomoku.setPlayerTurn(true);
            } else if (ChessPvpUtil.getTurn(data) != playerSide) {
                ChessPvpUtil.setTurn(data, playerSide);
                gomoku.setPlayerTurn(playerSide == 0);
            }
        } else if (joy instanceof TileEntityWChess chess) {
            if (chess.isCheckmate() || chess.isMoveNumberLimit() || chess.isRepeat()) {
                return;
            }
            if (chess.isPlayerTurn() != playerIsP1) {
                chess.getChessData().changeSide();
            }
        } else if (joy instanceof TileEntityCChess chess) {
            if (chess.isCheckmate() || chess.isMoveNumberLimit() || chess.isRepeat()) {
                return;
            }
            if (chess.isPlayerTurn() != playerIsP1) {
                chess.getChessData().changeSide();
            }
        }
        joy.setChanged();
    }

    private static void rollbackPlacement(ServerLevel level, int type, Block block, List<BlockPos> placed) {
        for (BlockPos pos : placed) {
            if (level.getBlockState(pos).getBlock() == block) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static ServerLevel findLevel(ServerPlayer player, String dimension) {
        for (ServerLevel level : player.level().getServer().getAllLevels()) {
            if (level.dimension().toString().equals(dimension)) {
                return level;
            }
        }
        return null;
    }

    private static int boardType(Block block) {
        if (block == InitBlocks.GOMOKU.get()) return GOMOKU;
        if (block == InitBlocks.WCHESS.get()) return WESTERN;
        if (block == InitBlocks.CCHESS.get()) return CHINESE;
        return -1;
    }

    private static Block blockFor(int type) {
        return switch (type) {
            case GOMOKU -> InitBlocks.GOMOKU.get();
            case WESTERN -> InitBlocks.WCHESS.get();
            case CHINESE -> InitBlocks.CCHESS.get();
            default -> throw new IllegalArgumentException("Unknown chess board type " + type);
        };
    }

    private static GomokuPart getPart(BlockState state, int type) {
        return switch (type) {
            case GOMOKU -> state.getValue(BlockGomoku.PART);
            case WESTERN -> state.getValue(BlockWChess.PART);
            case CHINESE -> state.getValue(BlockCChess.PART);
            default -> throw new IllegalArgumentException("Unknown chess board type " + type);
        };
    }

    private static BlockState stateFor(int type, GomokuPart part, Direction facing) {
        return switch (type) {
            case GOMOKU -> InitBlocks.GOMOKU.get().defaultBlockState()
                    .setValue(BlockGomoku.PART, part).setValue(BlockGomoku.FACING, facing);
            case WESTERN -> InitBlocks.WCHESS.get().defaultBlockState()
                    .setValue(BlockWChess.PART, part).setValue(BlockGomoku.FACING, facing);
            case CHINESE -> InitBlocks.CCHESS.get().defaultBlockState()
                    .setValue(BlockCChess.PART, part).setValue(BlockGomoku.FACING, facing);
            default -> throw new IllegalArgumentException("Unknown chess board type " + type);
        };
    }

    private static boolean matchesBoardEntity(int type, TileEntityJoy joy) {
        return switch (type) {
            case GOMOKU -> joy instanceof TileEntityGomoku;
            case WESTERN -> joy instanceof TileEntityWChess;
            case CHINESE -> joy instanceof TileEntityCChess;
            default -> false;
        };
    }
}
