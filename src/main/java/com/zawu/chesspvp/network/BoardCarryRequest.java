package com.zawu.chesspvp.network;

import com.zawu.chesspvp.ChessPvpMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;

public record BoardCarryRequest(BlockPos pos) implements CustomPacketPayload {
    public static final Type<BoardCarryRequest> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ChessPvpMod.MODID, "carry_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BoardCarryRequest> STREAM_CODEC = new StreamCodec<>() {
        public BoardCarryRequest decode(RegistryFriendlyByteBuf buf) { return new BoardCarryRequest(buf.readBlockPos()); }
        public void encode(RegistryFriendlyByteBuf buf, BoardCarryRequest value) { buf.writeBlockPos(value.pos()); }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
