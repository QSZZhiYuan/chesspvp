package com.zawu.chesspvp.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class ChessPvpUtil {
    private static final String PVP_TURN = "ChessPvpTurn";

    private ChessPvpUtil() {
    }

    public static UUID getP1(CompoundTag tag) {
        return tag.read(ChessPvpKeys.PVP_P1, UUIDUtil.CODEC).orElse(null);
    }

    public static UUID getP2(CompoundTag tag) {
        return tag.read(ChessPvpKeys.PVP_P2, UUIDUtil.CODEC).orElse(null);
    }

    public static boolean hasPvp(CompoundTag tag) {
        return getP1(tag) != null || getP2(tag) != null;
    }

    public static boolean hasBoth(CompoundTag tag) {
        return getP1(tag) != null && getP2(tag) != null;
    }

    public static boolean isP1(CompoundTag tag, ServerPlayer player) {
        UUID p1 = getP1(tag);
        return p1 != null && p1.equals(player.getUUID());
    }

    public static boolean isP2(CompoundTag tag, ServerPlayer player) {
        UUID p2 = getP2(tag);
        return p2 != null && p2.equals(player.getUUID());
    }

    public static void setP1(CompoundTag tag, UUID uuid) {
        tag.store(ChessPvpKeys.PVP_P1, UUIDUtil.CODEC, uuid);
    }

    public static void setP2(CompoundTag tag, UUID uuid) {
        tag.store(ChessPvpKeys.PVP_P2, UUIDUtil.CODEC, uuid);
    }

    public static void clearPvp(CompoundTag tag) {
        tag.remove(ChessPvpKeys.PVP_P1);
        tag.remove(ChessPvpKeys.PVP_P2);
        tag.remove(PVP_TURN);
    }

    public static int getTurn(CompoundTag tag) {
        return tag.getIntOr(PVP_TURN, 0);
    }

    public static void setTurn(CompoundTag tag, int turn) {
        tag.putInt(PVP_TURN, turn);
    }

    public static void ensureTurnInitialized(CompoundTag tag) {
        if (!tag.contains(PVP_TURN)) {
            setTurn(tag, 0);
        }
    }

    public static void toggleTurn(CompoundTag tag) {
        setTurn(tag, getTurn(tag) == 0 ? 1 : 0);
    }

    public static void awardChessSage(ServerPlayer player) {
        var advancementId = Identifier.fromNamespaceAndPath("chesspvp", "chess_sage");
        var advancement = player.level().getServer().getAdvancements().get(advancementId);
        if (advancement != null) {
            player.getAdvancements().award(advancement, "win");
        }
    }
}
