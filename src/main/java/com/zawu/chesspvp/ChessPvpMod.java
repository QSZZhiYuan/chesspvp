package com.zawu.chesspvp;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(ChessPvpMod.MODID)
public final class ChessPvpMod {
    public static final String MODID = "chesspvp";

    public ChessPvpMod(ModContainer container, net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(com.zawu.chesspvp.network.ChessPvpNetwork::register);
        container.registerConfig(ModConfig.Type.COMMON, ChessPvpConfig.SPEC);
    }
}
