package com.zawu.chesspvp.test;

import com.github.tartaricacid.touhoulittlemaid.block.BlockGomoku;
import com.github.tartaricacid.touhoulittlemaid.init.InitBlocks;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntitySit;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.tileentity.*;
import com.github.tartaricacid.touhoulittlemaid.api.game.gomoku.Statue;
import com.zawu.chesspvp.ChessPvpBoardCarry;
import com.zawu.chesspvp.client.CarriedBoardState;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CameraType;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Opt-in real client and integrated-server input/render smoke test; excluded from release jars. */
@EventBusSubscriber(modid = "chesspvp", value = Dist.CLIENT)
public final class CarryClientIntegration {
    private static final BlockPos CENTER = new BlockPos(128, 80, 0);
    private static int stage, age, boardType, totalTicks;
    private static volatile boolean ready, checked;
    private static volatile int maidId;
    private static volatile Throwable serverFailure;
    private static EntityMaid maid;
    private static EntitySit seat;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        try {
            if (++totalTicks > 1800) throw new AssertionError("client smoke timed out at stage " + stage);
            if (serverFailure != null) throw new AssertionError("integrated server assertion failed", serverFailure);
            age++;
            mc.options.hideGui = true;
            mc.player.setYRot(180);
            mc.player.yRotO = 180;
            mc.player.setXRot(28);
            mc.player.xRotO = 28;
            switch (stage) {
                case 0 -> {
                    ready = false;
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    mc.getSingleplayerServer().execute(() -> {
                        try {
                            var level = mc.getSingleplayerServer().overworld();
                            var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                            if (player == null) throw new AssertionError("real server player is missing");
                            player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
                            player.setInvulnerable(true);
                            player.connection.teleport(128.5, 80, 2.8, 180, 28);
                            for (int x=-3; x<=3; x++) for (int z=-3; z<=4; z++) {
                                level.setBlock(CENTER.offset(x,-1,z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                            }
                            if (maid != null) maid.discard();
                            if (seat != null) seat.discard();
                            for (int x=-1; x<=1; x++) for (int z=-1; z<=1; z++) {
                                level.setBlock(CENTER.offset(x,0,z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                            }
                            Block block = switch(boardType) {
                                case 0 -> InitBlocks.GOMOKU.get();
                                case 1 -> InitBlocks.WCHESS.get();
                                default -> InitBlocks.CCHESS.get();
                            };
                            var state = block.defaultBlockState().setValue(BlockGomoku.FACING, Direction.NORTH);
                            level.setBlock(CENTER, state, Block.UPDATE_ALL);
                            block.setPlacedBy(level, CENTER, state, player, new net.minecraft.world.item.ItemStack(block));
                            TileEntityJoy board = (TileEntityJoy) level.getBlockEntity(CENTER);
                            seat = new EntitySit(level, Vec3.atCenterOf(CENTER), "", CENTER);
                            maid = new EntityMaid(level);
                            if (!level.addFreshEntity(seat) || !level.addFreshEntity(maid) || !maid.startRiding(seat)) {
                                throw new AssertionError("cannot seat the maid");
                            }
                            maid.setInvulnerable(true);
                            maidId = maid.getId();
                            board.setSitId(seat.getUUID());
                            if (board instanceof TileEntityGomoku g) { g.setStatue(Statue.IN_PROGRESS); g.setPlayerTurn(false); }
                            else if (board instanceof TileEntityWChess w) w.getChessData().changeSide();
                            else if (board instanceof TileEntityCChess c) c.getChessData().changeSide();
                            board.refresh();
                            ready = true;
                        } catch(Throwable failure) { serverFailure = failure; }
                    });
                    next(1);
                }
                case 1 -> {
                    if (ready && age > 40 && mc.level.getBlockState(CENTER).getBlock() instanceof com.github.tartaricacid.touhoulittlemaid.block.BlockGomoku
                            || ready && age > 40 && !mc.level.getBlockState(CENTER).isAir()) {
                        mc.options.keyShift.setDown(true);
                        next(2);
                    }
                }
                case 2 -> {
                    if (age > 4) {
                        if (!mc.player.isShiftKeyDown()) throw new AssertionError("real client did not register the sneak key");
                        mc.gameMode.startDestroyBlock(CENTER, Direction.UP);
                        next(3);
                    }
                }
                case 3 -> {
                    mc.options.keyShift.setDown(false);
                    if (CarriedBoardState.get(mc.player.getUUID()) != null && mc.level.getBlockState(CENTER).isAir()) next(4);
                    else if (age > 160) throw new AssertionError("real left-click did not pick up board " + boardType);
                }
                case 4 -> {
                    if (age > 15) {
                        Screenshot.grab(mc.gameDirectory, "carry-"+boardType+"-first-person.png", mc.getMainRenderTarget(), ignored -> {});
                        mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                        next(5);
                    }
                }
                case 5 -> {
                    if (age > 20) {
                        Screenshot.grab(mc.gameDirectory, "carry-"+boardType+"-third-person.png", mc.getMainRenderTarget(), ignored -> {});
                        var target = mc.level.getEntity(maidId);
                        if (target == null) throw new AssertionError("maid target not tracked by real client");
                        mc.gameMode.attack(mc.player, target);
                        next(6);
                    }
                }
                case 6 -> {
                    if (CarriedBoardState.get(mc.player.getUUID()) == null && !mc.level.getBlockState(CENTER).isAir()) {
                        checked = false;
                        mc.getSingleplayerServer().execute(() -> {
                            try {
                                var server = mc.getSingleplayerServer();
                                var player = server.getPlayerList().getPlayer(mc.player.getUUID());
                                var board = (TileEntityJoy) server.overworld().getBlockEntity(CENTER);
                                if (ChessPvpBoardCarry.hasBoard(player) || board == null || !board.getSitId().equals(seat.getUUID())) {
                                    throw new AssertionError("real attack failed to restore the board or maid seat");
                                }
                                checked = true;
                            } catch(Throwable failure) { serverFailure = failure; }
                        });
                        next(7);
                    } else if (age > 160) throw new AssertionError("real attack did not restore board " + boardType);
                }
                case 7 -> {
                    if (checked && age > 10) {
                        LogUtils.getLogger().info("CHESSPVP_CLIENT_BOARD PASS: {} real left-click, state sync, first/third person render, attack and restore", boardType);
                        if (++boardType == 3) {
                            LogUtils.getLogger().info("CHESSPVP_CLIENT_INTEGRATION PASS: all three boards, real client input, six model screenshots");
                            mc.stop();
                        } else next(0);
                    }
                }
            }
        } catch(Throwable failure) {
            LogUtils.getLogger().error("CHESSPVP_CLIENT_INTEGRATION FAIL", failure);
            mc.stop();
        }
    }
    private static void next(int value) { stage = value; age = 0; }
}
