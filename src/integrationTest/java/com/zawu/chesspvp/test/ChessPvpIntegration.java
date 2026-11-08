package com.zawu.chesspvp.test;

import com.github.tartaricacid.touhoulittlemaid.api.game.chess.Position;
import com.github.tartaricacid.touhoulittlemaid.api.game.gomoku.Point;
import com.github.tartaricacid.touhoulittlemaid.api.game.gomoku.Statue;
import com.github.tartaricacid.touhoulittlemaid.block.BlockGomoku;
import com.github.tartaricacid.touhoulittlemaid.block.BlockCChess;
import com.github.tartaricacid.touhoulittlemaid.block.BlockWChess;
import com.github.tartaricacid.touhoulittlemaid.block.properties.GomokuPart;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntitySit;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitBlocks;
import com.github.tartaricacid.touhoulittlemaid.tileentity.*;
import com.zawu.chesspvp.util.ChessPvpUtil;
import com.zawu.chesspvp.ChessPvpBoardCarry;
import com.zawu.chesspvp.network.BoardCarryRequest;
import com.zawu.chesspvp.network.BoardCarryState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import io.netty.buffer.Unpooled;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.UUID;


/** Calls the real, mixin-transformed blocks in an isolated dedicated server. */
@EventBusSubscriber(modid = "chesspvp")
public final class ChessPvpIntegration {
    private static int assertions;
    private static int ticks;
    private static ServerLevel level;
    private static ServerPlayer first, second, spectator;

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        level = event.getServer().overworld();
        level.setChunkForced(0, 0, true);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (level == null || ++ticks != 40) return;
        try {
            first = player("ChessPvpOne", 1);
            second = player("ChessPvpTwo", 2);
            spectator = player("ChessPvpView", 3);
            gomoku();
            var chessSage = level.getServer().getAdvancements().get(
                    ResourceLocation.fromNamespaceAndPath("chesspvp", "chess_sage"));
            require(chessSage != null, "Chess Sage advancement is loaded");
            require(chessSage.value().requirements().names().contains("win"),
                    "Chess Sage completion requirement references the win criterion");
            ServerPlayer advancementPlayer = new ServerPlayer(level.getServer(), level,
                    new GameProfile(new UUID(0x4348455353505650L, 4), "ChessSageWinner"),
                    ClientInformation.createDefault());
            ChessPvpUtil.awardChessSage(advancementPlayer);
            require(advancementPlayer.getAdvancements().getOrStartProgress(chessSage).isDone(),
                    "a normal server player can earn Chess Sage advancement");
            // PvP helper success alone does not cover the original maid match path.
            for (String game : new String[]{"gomoku", "wchess", "cchess"}) {
                for (String criterion : chessSage.value().criteria().keySet()) {
                    advancementPlayer.getAdvancements().revoke(chessSage, criterion);
                }
                com.github.tartaricacid.touhoulittlemaid.init.InitTrigger.MAID_EVENT.get()
                        .trigger(advancementPlayer, "tamed_maid");
                require(!advancementPlayer.getAdvancements().getOrStartProgress(chessSage).isDone(),
                        "unrelated maid event does not grant Chess Sage");
                com.github.tartaricacid.touhoulittlemaid.init.InitTrigger.MAID_EVENT.get()
                        .trigger(advancementPlayer, "win_" + game);
                var progress = advancementPlayer.getAdvancements().getOrStartProgress(chessSage);
                require(progress.isDone() && progress.getCriterion("maid_" + game).isDone(),
                        "one original maid victory independently grants Chess Sage: " + game);
            }
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                western(facing);
                chinese(facing);
            }
            maidFallback();
            boardCarryInput();
            maidBoardCarry();
            boardCarry();
            LogUtils.getLogger().info("CHESSPVP_INTEGRATION PASS: {} assertions; three boards, carry and restore, bonus turns, win/draw, reset, NBT round trips, maid fallback", assertions);
        } catch (Throwable failure) {
            LogUtils.getLogger().error("CHESSPVP_INTEGRATION FAIL", failure);
        } finally {
            level.setChunkForced(0, 0, false);
            event.getServer().halt(false);
        }
    }

    private static ServerPlayer player(String name, long id) {
        return FakePlayerFactory.get(level, new GameProfile(new UUID(0x4348455353505650L, id), name));
    }

    private static BlockPos board(Block block, int x, Direction facing) {
        BlockPos center = new BlockPos(x, 80, 0);
        for (GomokuPart part : GomokuPart.values()) {
            level.setBlock(center.offset(part.getPosX(), 0, part.getPosY()), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        // Use the real placement callback, including all eight secondary board parts.
        BlockState placedState = block.defaultBlockState().setValue(BlockGomoku.FACING, facing);
        level.setBlock(center, placedState, Block.UPDATE_CLIENTS);
        block.setPlacedBy(level, center, placedState, first, new ItemStack(block));
        require(level.getBlockEntity(center) != null, "center block entity exists");
        return center;
    }

    private static void click(BlockPos pos, Vec3 point, ServerPlayer who) {
        BlockState state = level.getBlockState(pos);
        who.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        state.useItemOn(ItemStack.EMPTY, level, who, InteractionHand.MAIN_HAND,
                new BlockHitResult(point, Direction.UP, pos, false));
    }

    private static void local(BlockPos center, double x, double z, ServerPlayer who) {
        Vec3 offset = new Vec3(x, 0, z).yRot(-level.getBlockState(center).getValue(BlockGomoku.FACING).toYRot() * Mth.DEG_TO_RAD);
        Vec3 point = new Vec3(center.getX() + .5 + offset.x, center.getY() + .5, center.getZ() + .5 + offset.z);
        click(BlockPos.containing(point), point, who);
    }

    private static void join(BlockPos center, TileEntityJoy chess) {
        Vec3 point = Vec3.atCenterOf(center);
        first.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
        level.getBlockState(center).useItemOn(first.getMainHandItem(), level, first, InteractionHand.MAIN_HAND,
                new BlockHitResult(point, Direction.UP, center, false));
        require(!ChessPvpUtil.hasPvp(chess.getPersistentData()), "held item must not join PVP");
        click(center, point, first);
        require(first.getUUID().equals(ChessPvpUtil.getP1(chess.getPersistentData())), "first player joins");
        require(!ChessPvpUtil.hasBoth(chess.getPersistentData()), "wait for second player");
        click(center, point, first);
        require(ChessPvpUtil.getP2(chess.getPersistentData()) == null, "one player cannot join both sides");
        click(center, point, second);
        require(second.getUUID().equals(ChessPvpUtil.getP2(chess.getPersistentData())), "second player joins");
    }

    private static void gomokuPoint(BlockPos center, int x, int y, ServerPlayer who) {
        // Interior intersections of the CENTER part, derived from BlockGomoku.getData.
        click(center, new Vec3(center.getX() + .115 + (x - 4) * .1316, center.getY() + .5,
                center.getZ() + .105 + (y - 4) * .1316), who);
    }

    private static void gomokuReset(BlockPos center, ServerPlayer who) {
        BlockPos part = center.offset(1, 0, -1);
        click(part, new Vec3(part.getX() + .7, part.getY() + .5, part.getZ() + .8), who);
    }

    private static void gomoku() {
        BlockPos center = board(InitBlocks.GOMOKU.get(), 0, Direction.NORTH);
        TileEntityGomoku chess = (TileEntityGomoku) level.getBlockEntity(center);
        join(center, chess);
        gomokuPoint(center, 4, 4, second);
        require(chess.getChessCounter() == 0, "gomoku wrong first turn");
        gomokuPoint(center, 4, 4, first);
        require(chess.getChessData()[4][4] == Point.BLACK && chess.getChessCounter() == 1, "black move");
        require(!chess.isPlayerTurn() && ChessPvpUtil.getTurn(chess.getPersistentData()) == 1, "gomoku displayed turn matches PVP");
        gomokuPoint(center, 5, 4, first);
        gomokuPoint(center, 4, 4, second);
        gomokuPoint(center, 5, 4, spectator);
        gomokuReset(center, spectator);
        require(chess.getChessCounter() == 1 && ChessPvpUtil.hasBoth(chess.getPersistentData()), "wrong turn, occupied, spectator/reset rejected");
        TileEntityGomoku restored = new TileEntityGomoku(center, level.getBlockState(center));
        roundTrip(chess, restored);
        require(restored.getChessData()[4][4] == Point.BLACK && !restored.isPlayerTurn(), "gomoku board and turn persist");
        for (int x = 4; x < 8; x++) {
            gomokuPoint(center, x, 5, second);
            gomokuPoint(center, x + 1, 4, first);
        }
        require(chess.getStatue() == Statue.WIN && chess.isPlayerTurn() && chess.getChessCounter() == 9, "five in a row ends for black");
        gomokuPoint(center, 9, 5, second);
        require(chess.getChessCounter() == 9, "gomoku cannot continue after win");
        gomokuReset(center, second);
        require(chess.getChessCounter() == 0 && chess.getStatue() == Statue.IN_PROGRESS && !ChessPvpUtil.hasPvp(chess.getPersistentData()), "gomoku participant reset");
        join(center, chess);
        // White win exercises the other winner/renderer flag.
        for (int i = 0; i < 5; i++) {
            gomokuPoint(center, 4 + i, 4 + (i % 2) * 2, first);
            gomokuPoint(center, 4 + i, 5, second);
        }
        require(chess.getStatue() == Statue.WIN && !chess.isPlayerTurn(), "white winner flag");
    }

    private static void westernPoint(BlockPos center, int square, ServerPlayer who) {
        local(center, (Position.FILE_X(square) - Position.FILE_LEFT + .5) * .25 - 1,
                (Position.RANK_Y(square) - Position.RANK_TOP + .5) * .25 - 1, who);
    }

    private static void westernMove(BlockPos center, String move, ServerPlayer who) {
        int mv = Position.PARSE_MOVE(move);
        westernPoint(center, Position.SRC(mv), who);
        westernPoint(center, Position.DST(mv), who);
    }

    private static void western(Direction facing) {
        BlockPos center = board(InitBlocks.WCHESS.get(), 16 + facing.get2DDataValue() * 8, facing);
        TileEntityWChess chess = (TileEntityWChess) level.getBlockEntity(center);
        join(center, chess);
        westernMove(center, "e7e5", second);
        require(chess.getChessCounter() == 0, "western wrong turn");
        westernMove(center, "e2e5", first);
        require(chess.getChessCounter() == 0, "western illegal move");
        westernMove(center, "e2e4", spectator);
        local(center, 1.25, 0, spectator);
        require(chess.getChessCounter() == 0 && ChessPvpUtil.hasBoth(chess.getPersistentData()), "western spectator/reset rejected");
        westernMove(center, "f2f3", first);
        require(chess.getChessCounter() == 1 && !chess.isPlayerTurn(), "western white move");
        require(chess.getChessData().moveNum == 1, "pawn resets irreversible counter");
        westernMove(center, "e7e5", second);
        require(chess.getChessCounter() == 2 && chess.isPlayerTurn(), "western black move");
        TileEntityWChess restored = new TileEntityWChess(center, level.getBlockState(center));
        roundTrip(chess, restored);
        require(restored.getChessData().toFen().equals(chess.getChessData().toFen()) && restored.getChessCounter() == 2, "western board persists");
        westernMove(center, "g2g4", first);
        westernMove(center, "d8h4", second);
        require(chess.getChessCounter() == 4 && chess.isCheckmate(), "fools mate ends western game");
        westernMove(center, "e2e4", first);
        require(chess.getChessCounter() == 4, "western cannot play after mate");
        local(center, 1.25, 0, first);
        require(chess.getChessCounter() == 0 && !chess.isCheckmate() && !ChessPvpUtil.hasPvp(chess.getPersistentData()), "western participant reset");
        join(center, chess);
        for (int i = 0; i < 3 && !chess.isRepeat(); i++) {
            westernMove(center, "g1f3", first);
            westernMove(center, "g8f6", second);
            westernMove(center, "f3g1", first);
            westernMove(center, "f6g8", second);
        }
        require(chess.isRepeat(), "western repetition ends game");
        int count = chess.getChessCounter();
        westernMove(center, "e2e4", first);
        require(chess.getChessCounter() == count, "western draw prevents more moves");
    }

    private static int cSquare(int file, int rank) {
        return com.github.tartaricacid.touhoulittlemaid.api.game.xqwlight.Position.COORD_XY(file + 3, rank + 3);
    }

    private static void chinesePoint(BlockPos center, int file, int rank, ServerPlayer who) {
        local(center, file * .304 - 1.365, rank * .304 - 1.370, who);
    }

    private static void chineseMove(BlockPos center, int fx, int fy, int tx, int ty, ServerPlayer who) {
        chinesePoint(center, fx, fy, who);
        chinesePoint(center, tx, ty, who);
    }

    private static void chinese(Direction facing) {
        BlockPos center = board(InitBlocks.CCHESS.get(), 64 + facing.get2DDataValue() * 8, facing);
        TileEntityCChess chess = (TileEntityCChess) level.getBlockEntity(center);
        join(center, chess);
        chineseMove(center, 0, 3, 0, 4, second);
        require(chess.getChessCounter() == 0, "chinese wrong turn");
        chineseMove(center, 0, 6, 0, 4, first);
        require(chess.getChessCounter() == 0, "chinese illegal pawn jump");
        chineseMove(center, 0, 6, 0, 5, spectator);
        local(center, 1.3, .7, spectator);
        require(chess.getChessCounter() == 0 && ChessPvpUtil.hasBoth(chess.getPersistentData()), "chinese spectator/reset rejected");
        chineseMove(center, 0, 6, 0, 5, first);
        require(chess.getChessCounter() == 1 && !chess.isPlayerTurn() && chess.getChessData().squares[cSquare(0, 5)] != 0, "red pawn move");
        chineseMove(center, 0, 3, 0, 4, second);
        require(chess.getChessCounter() == 2 && chess.isPlayerTurn(), "black pawn move");
        TileEntityCChess restored = new TileEntityCChess(center, level.getBlockState(center));
        roundTrip(chess, restored);
        require(restored.getChessData().toFen().equals(chess.getChessData().toFen()) && restored.getChessCounter() == 2, "chinese board persists");
        local(center, 1.3, .7, second);
        require(chess.getChessCounter() == 0 && !ChessPvpUtil.hasPvp(chess.getPersistentData()), "chinese participant reset");
        join(center, chess);
        // Ladder mate: Ra8 controls the escape rank, Rb7-b9 checks the king.
        chess.setEndgame("4k4/R8/1R7/9/4P4/9/9/9/9/4K4");
        chineseMove(center, 1, 2, 1, 0, first);
        require(chess.isCheckmate(), "chinese mate ends game");
        int count = chess.getChessCounter();
        chineseMove(center, 4, 0, 4, 1, second);
        require(chess.getChessCounter() == count, "chinese cannot play after mate");
    }

    private static void roundTrip(TileEntityJoy original, TileEntityJoy restored) {
        CompoundTag tag = original.saveWithoutMetadata(level.registryAccess());
        restored.loadWithComponents(tag, level.registryAccess());
        require(ChessPvpUtil.getP1(original.getPersistentData()).equals(ChessPvpUtil.getP1(restored.getPersistentData())), "P1 persists");
        require(ChessPvpUtil.getP2(original.getPersistentData()).equals(ChessPvpUtil.getP2(restored.getPersistentData())), "P2 persists");
    }

    private static void maidFallback() {
        for (Block block : new Block[]{InitBlocks.GOMOKU.get(), InitBlocks.CCHESS.get(), InitBlocks.WCHESS.get()}) {
            BlockPos center = board(block, 0, Direction.NORTH);
            TileEntityJoy chess = (TileEntityJoy) level.getBlockEntity(center);
            EntitySit sit = new EntitySit(level, Vec3.atCenterOf(center), "", center);
            EntityMaid maid = new EntityMaid(level);
            require(level.addFreshEntity(sit), "seat added");
            require(level.addFreshEntity(maid), "maid added");
            require(level.getEntity(sit.getUUID()) == sit, "seat visible in tracked chunk");
            require(maid.startRiding(sit), "maid sits for fallback");
            chess.setSitId(sit.getUUID());
            click(center, Vec3.atCenterOf(center), first);
            require(!ChessPvpUtil.hasPvp(chess.getPersistentData()), "seated maid preserves original game");
            maid.discard();
            sit.discard();
        }
    }

    private static void boardCarry() {
        BlockPos gomokuPos = board(InitBlocks.GOMOKU.get(), 96, Direction.NORTH);
        TileEntityGomoku gomoku = (TileEntityGomoku) level.getBlockEntity(gomokuPos);
        join(gomokuPos, gomoku);
        gomokuPoint(gomokuPos, 4, 4, first);
        require(ChessPvpUtil.getTurn(gomoku.getPersistentData()) == 1, "gomoku opponent turn before smash");
        sneakClick(gomokuPos, first);
        require(ChessPvpBoardCarry.hasBoard(first) && level.getBlockState(gomokuPos).isAir(), "gomoku is carried without an item");
        level.setBlock(gomokuPos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        attack(first, second);
        require(ChessPvpBoardCarry.hasBoard(first), "blocked restore keeps carried board data");
        level.setBlock(gomokuPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos restoreTrigger = board(InitBlocks.CCHESS.get(), 120, Direction.EAST);
        sneakClick(restoreTrigger, first);
        require(!ChessPvpBoardCarry.hasBoard(first) && !level.getBlockState(restoreTrigger).isAir(),
                "sneak-left-click restores the pending board without picking up another");
        gomoku = (TileEntityGomoku) level.getBlockEntity(gomokuPos);
        require(!ChessPvpBoardCarry.hasBoard(first) && gomoku.getChessCounter() == 1
                && gomoku.getChessData()[4][4] == Point.BLACK, "gomoku state survives carry");
        require(ChessPvpUtil.getTurn(gomoku.getPersistentData()) == 0 && gomoku.isPlayerTurn(),
                "smash grants gomoku player one turn");
        gomokuPoint(gomokuPos, 5, 4, first);
        require(ChessPvpUtil.getTurn(gomoku.getPersistentData()) == 1, "gomoku normal move returns turn to opponent");

        BlockPos westernPos = board(InitBlocks.WCHESS.get(), 104, Direction.EAST);
        TileEntityWChess western = (TileEntityWChess) level.getBlockEntity(westernPos);
        join(westernPos, western);
        westernMove(westernPos, "f2f3", first);
        require(!western.isPlayerTurn() && western.getChessCounter() == 1, "western opponent turn before smash");
        EntityMaid maid = new EntityMaid(level);
        maid.setPos(120, 80, 2);
        require(level.addFreshEntity(maid), "smash target maid added");
        sneakClick(westernPos, first);
        attack(first, maid);
        maid.discard();
        western = (TileEntityWChess) level.getBlockEntity(westernPos);
        require(!ChessPvpBoardCarry.hasBoard(first) && western.getChessCounter() == 1
                && western.getChessData().toFen().contains("/5P2/"), "western position survives carry");
        require(level.getBlockState(westernPos).getValue(BlockGomoku.FACING) == Direction.EAST,
                "western facing survives carry");
        require(western.isPlayerTurn(), "smash grants western player one turn");
        westernMove(westernPos, "e2e4", first);
        require(western.getChessCounter() == 2 && !western.isPlayerTurn(), "western normal move returns turn to opponent");

        BlockPos chinesePos = board(InitBlocks.CCHESS.get(), 112, Direction.SOUTH);
        TileEntityCChess chinese = (TileEntityCChess) level.getBlockEntity(chinesePos);
        join(chinesePos, chinese);
        chineseMove(chinesePos, 0, 6, 0, 5, first);
        require(!chinese.isPlayerTurn() && chinese.getChessCounter() == 1, "chinese opponent turn before smash");
        sneakClick(chinesePos, first);
        attack(first, second);
        chinese = (TileEntityCChess) level.getBlockEntity(chinesePos);
        require(!ChessPvpBoardCarry.hasBoard(first) && chinese.getChessCounter() == 1
                && chinese.getChessData().toFen().contains("P8/"), "chinese position survives carry");
        require(level.getBlockState(chinesePos).getValue(BlockGomoku.FACING) == Direction.SOUTH,
                "chinese facing survives carry");
        require(chinese.isPlayerTurn(), "smash grants chinese player one turn");
        chineseMove(chinesePos, 2, 6, 2, 5, first);
        require(chinese.getChessCounter() == 2 && !chinese.isPlayerTurn(), "chinese normal move returns turn to opponent");
    }

    private static PlayerInteractEvent.LeftClickBlock leftClick(BlockPos pos, ServerPlayer who,
                                                                 ServerboundPlayerActionPacket.Action action) {
        who.setPos(pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5);
        return CommonHooks.onLeftClickBlock(who, pos, Direction.UP, action);
    }

    private static void sneakClick(BlockPos pos, ServerPlayer who) {
        boolean wasSneaking = who.isShiftKeyDown();
        who.setShiftKeyDown(true);
        try {
            require(leftClick(pos, who, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK).isCanceled(),
                    "sneak-left-click goes through the event bus and cancels mining");
            // The server's sneak flag may lag behind the client input by a tick.
            who.setShiftKeyDown(false);
            require(requestThroughCodec(pos, who), "decoded client request picks up or restores the board");
        } finally {
            who.setShiftKeyDown(wasSneaking);
        }
    }

    private static boolean requestThroughCodec(BlockPos pos, ServerPlayer who) {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            BoardCarryRequest.STREAM_CODEC.encode(buf, new BoardCarryRequest(pos));
            var decoded = BoardCarryRequest.STREAM_CODEC.decode(buf);
            require(decoded.pos().equals(pos), "request position survives network codec");
            boolean success = ChessPvpBoardCarry.requestCarry(who, decoded.pos());
            var state = new BoardCarryState(who.getUUID(), ChessPvpBoardCarry.getCarriedData(who));
            buf.clear();
            BoardCarryState.STREAM_CODEC.encode(buf, state);
            var snapshot = BoardCarryState.STREAM_CODEC.decode(buf);
            require(snapshot.player().equals(who.getUUID()) && snapshot.board().equals(state.board()),
                    "client visual packet preserves carrier UUID and complete chess data");
            return success;
        } finally { buf.release(); }
    }

    private static void maidBoardCarry() {
        for (Block block : new Block[]{InitBlocks.GOMOKU.get(), InitBlocks.WCHESS.get(), InitBlocks.CCHESS.get()}) {
            BlockPos center = board(block, 160, Direction.EAST);
            TileEntityJoy board = (TileEntityJoy) level.getBlockEntity(center);
            EntitySit seat = new EntitySit(level, Vec3.atCenterOf(center), "", center);
            EntityMaid maid = new EntityMaid(level);
            maid.tame(first);
            require(level.addFreshEntity(seat) && level.addFreshEntity(maid) && maid.startRiding(seat),
                    "maid is seated at a normally placed chessboard");
            board.setSitId(seat.getUUID());
            if (board instanceof TileEntityGomoku g) {
                g.setStatue(Statue.IN_PROGRESS);
                g.setPlayerTurn(false);
            } else if (board instanceof TileEntityWChess w) {
                w.getChessData().changeSide();
            } else if (board instanceof TileEntityCChess c) {
                c.getChessData().changeSide();
            }
            sneakClick(center.offset(1, 0, -1), first);
            require(ChessPvpBoardCarry.hasBoard(first) && level.getBlockState(center).isAir(),
                    "occupied maid chessboard can be carried");
            // Isolate board removal from the independent once-per-second work schedule check.
            seat.tickCount = 1;
            seat.tick();
            require(seat.getFirstPassenger() == maid && !seat.isRemoved(),
                    "picking up a board preserves the maid seat");
            attack(first, maid);
            TileEntityJoy restored = (TileEntityJoy) level.getBlockEntity(center);
            require(restored != null && !ChessPvpBoardCarry.hasBoard(first)
                            && restored.getSitId().equals(seat.getUUID()) && seat.getFirstPassenger() == maid,
                    "smash restores the occupied board and the same maid seat");
            require(!ChessPvpUtil.hasPvp(restored.getPersistentData()), "maid match stays outside PvP mode");
            if (restored instanceof TileEntityGomoku g) {
                require(g.isPlayerTurn(), "maid gomoku smash grants the human one turn");
                gomokuPoint(center, 4, 4, first);
                require(!g.isPlayerTurn() && g.getChessCounter() == 1, "maid gomoku accepts one move after smash");
            } else if (restored instanceof TileEntityWChess w) {
                require(w.isPlayerTurn(), "maid western smash grants the human one turn");
                westernMove(center, "e2e4", first);
                require(!w.isPlayerTurn() && w.getChessCounter() == 1, "maid western accepts one move after smash");
            } else if (restored instanceof TileEntityCChess c) {
                require(c.isPlayerTurn(), "maid chinese smash grants the human one turn");
                chineseMove(center, 0, 6, 0, 5, first);
                require(!c.isPlayerTurn() && c.getChessCounter() == 1, "maid chinese accepts one move after smash");
            }
            maid.discard();
            seat.discard();
        }
    }

    private static void boardCarryInput() {
        GameType originalMode = first.gameMode.getGameModeForPlayer();
        for (GameType mode : new GameType[]{GameType.SURVIVAL, GameType.CREATIVE}) {
            first.gameMode.changeGameModeForPlayer(mode);
            for (Block block : new Block[]{InitBlocks.GOMOKU.get(), InitBlocks.WCHESS.get(), InitBlocks.CCHESS.get()}) {
                BlockPos center = board(block, 128, Direction.WEST);
                BlockPos edge = center.offset(-1, 0, -1);
                first.setShiftKeyDown(true);
                first.setPos(center.getX() + 32, center.getY() + 1, center.getZ() + .5);
                CommonHooks.onLeftClickBlock(first, edge, Direction.UP,
                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
                require(!ChessPvpBoardCarry.requestCarry(first, edge), "out-of-range carry request is rejected");
                require(!ChessPvpBoardCarry.hasBoard(first) && !level.getBlockState(center).isAir(),
                        "out-of-range mining packets cannot pick up a board");
                first.setShiftKeyDown(false);
                require(!leftClick(edge, first, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK).isCanceled()
                                && !ChessPvpBoardCarry.hasBoard(first),
                        "ordinary left-click does not pick up a board");
                first.setShiftKeyDown(true);
                for (ServerboundPlayerActionPacket.Action action : new ServerboundPlayerActionPacket.Action[]{
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK}) {
                    require(leftClick(edge, first, action).isCanceled() && !ChessPvpBoardCarry.hasBoard(first),
                            "stop and abort cancel mining without picking up a board");
                }
                var hold = new PlayerInteractEvent.LeftClickBlock(first, edge, Direction.UP,
                        PlayerInteractEvent.LeftClickBlock.Action.CLIENT_HOLD);
                NeoForge.EVENT_BUS.post(hold);
                require(hold.isCanceled() && !ChessPvpBoardCarry.hasBoard(first),
                        "hold events never pick up a board");
                click(center, Vec3.atCenterOf(center), first);
                require(!ChessPvpBoardCarry.hasBoard(first)
                                && first.getUUID().equals(ChessPvpUtil.getP1(
                                        ((TileEntityJoy) level.getBlockEntity(center)).getPersistentData())),
                        "sneak-right-click keeps ordinary chess interaction");
                sneakClick(edge, first);
                require(ChessPvpBoardCarry.hasBoard(first), "sneak-left-click picks up all three boards in survival and creative");
                for (GomokuPart part : GomokuPart.values()) {
                    require(level.getBlockState(center.offset(part.getPosX(), 0, part.getPosY())).isAir(),
                            "pickup removes the complete multiblock without mining");
                }
                for (ServerboundPlayerActionPacket.Action action : new ServerboundPlayerActionPacket.Action[]{
                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK}) {
                    leftClick(edge, first, action);
                    require(ChessPvpBoardCarry.hasBoard(first) && level.getBlockState(center).isAir(),
                            "follow-up mining packets do not immediately restore the carried board");
                }
                first.setShiftKeyDown(false);
                attack(first, second);
                require(!ChessPvpBoardCarry.hasBoard(first) && level.getBlockEntity(center) instanceof TileEntityJoy,
                        "carried board restores after attacking a player");
            }
        }
        first.gameMode.changeGameModeForPlayer(originalMode);
        BlockPos stone = new BlockPos(144, 80, 0);
        level.setBlock(stone, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        first.setShiftKeyDown(true);
        require(!leftClick(stone, first, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK).isCanceled(),
                "sneak-left-click leaves unrelated blocks alone");
        first.setShiftKeyDown(false);
    }

    private static void attack(ServerPlayer attacker, net.minecraft.world.entity.Entity target) {
        ChessPvpBoardCarry.onAttackEntity(new AttackEntityEvent(attacker, target));
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
