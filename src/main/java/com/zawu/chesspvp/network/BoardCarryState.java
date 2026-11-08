package com.zawu.chesspvp.network;
import net.minecraft.nbt.CompoundTag;
import java.util.UUID;
public record BoardCarryState(UUID player, CompoundTag board) {}
