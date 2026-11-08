package com.zawu.chesspvp.network;

import com.zawu.chesspvp.ChessPvpMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;

public record BoardCarryState(UUID player, CompoundTag board) implements CustomPacketPayload {
    public static final Type<BoardCarryState> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ChessPvpMod.MODID, "carry_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BoardCarryState> STREAM_CODEC = new StreamCodec<>() {
        public BoardCarryState decode(RegistryFriendlyByteBuf buf) { return new BoardCarryState(buf.readUUID(), java.util.Objects.requireNonNull(buf.readNbt())); }
        public void encode(RegistryFriendlyByteBuf buf, BoardCarryState value) { buf.writeUUID(value.player()); buf.writeNbt(value.board()); }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
