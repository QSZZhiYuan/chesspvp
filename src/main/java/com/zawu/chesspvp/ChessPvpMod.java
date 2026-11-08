package com.zawu.chesspvp;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(ChessPvpMod.MODID)
public class ChessPvpMod {
    public static final String MODID = "chesspvp";

    public ChessPvpMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        com.zawu.chesspvp.network.ChessPvpNetwork.init();
        ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.COMMON, ChessPvpConfig.SPEC);
    }
}
