package com.zawu.chesspvp.network;

import com.zawu.chesspvp.ChessPvpMod;
import com.zawu.chesspvp.ChessPvpBoardCarry;
import com.zawu.chesspvp.client.CarriedBoardState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ChessPvpNetwork {
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(ChessPvpMod.MODID, "carry")).networkProtocolVersion(() -> "2")
            .clientAcceptedVersions("2"::equals).serverAcceptedVersions("2"::equals).simpleChannel();
    public static void init() {
        CHANNEL.messageBuilder(BoardCarryRequest.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((message, buf) -> buf.writeBlockPos(message.pos()))
                .decoder(buf -> new BoardCarryRequest(buf.readBlockPos()))
                .consumerMainThread((message, context) -> {
                    ServerPlayer player = context.get().getSender();
                    if (player != null) ChessPvpBoardCarry.requestCarry(player, message.pos());
                    context.get().setPacketHandled(true);
                }).add();
        CHANNEL.messageBuilder(BoardCarryState.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((message, buf) -> { buf.writeUUID(message.player()); buf.writeNbt(message.board()); })
                .decoder(buf -> new BoardCarryState(buf.readUUID(), java.util.Objects.requireNonNull(buf.readNbt())))
                .consumerMainThread((message, context) -> {
                    CarriedBoardState.accept(message.player(), message.board());
                    context.get().setPacketHandled(true);
                }).add();
    }
    public static void request(BlockPos pos) { CHANNEL.sendToServer(new BoardCarryRequest(pos)); }
    public static void sync(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                new BoardCarryState(player.getUUID(), ChessPvpBoardCarry.getCarriedData(player)));
    }
    public static void syncTo(ServerPlayer viewer, ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> viewer),
                new BoardCarryState(player.getUUID(), ChessPvpBoardCarry.getCarriedData(player)));
    }
}
