package com.zawu.chesspvp.client;

import net.minecraft.nbt.CompoundTag;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client-only snapshots; never read or written as authoritative game state. */
public final class CarriedBoardState {
    private static final Map<UUID, CompoundTag> BOARDS = new HashMap<>();
    public static void accept(UUID player, CompoundTag board) {
        if (board.isEmpty()) BOARDS.remove(player);
        else BOARDS.put(player, board.copy());
    }
    public static CompoundTag get(UUID player) { return BOARDS.get(player); }
    public static void remove(UUID player) { BOARDS.remove(player); }
    public static void clear() { BOARDS.clear(); }
}
