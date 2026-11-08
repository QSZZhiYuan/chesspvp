package com.zawu.chesspvp.network;

import com.zawu.chesspvp.ChessPvpBoardCarry;
import com.zawu.chesspvp.client.CarriedBoardState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class ChessPvpNetwork {
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2");
        registrar.playToServer(BoardCarryRequest.TYPE, BoardCarryRequest.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                ChessPvpBoardCarry.requestCarry(player, payload.pos());
            }
        });
        registrar.playToClient(BoardCarryState.TYPE, BoardCarryState.STREAM_CODEC,
                (payload, context) -> CarriedBoardState.accept(payload.player(), payload.board()));
    }
    public static void request(BlockPos pos) { PacketDistributor.sendToServer(new BoardCarryRequest(pos)); }
    public static void sync(ServerPlayer player) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new BoardCarryState(player.getUUID(), ChessPvpBoardCarry.getCarriedData(player)));
    }
    public static void syncTo(ServerPlayer viewer, ServerPlayer player) {
        PacketDistributor.sendToPlayer(viewer,
                new BoardCarryState(player.getUUID(), ChessPvpBoardCarry.getCarriedData(player)));
    }
}
