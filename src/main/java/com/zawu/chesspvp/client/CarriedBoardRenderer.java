package com.zawu.chesspvp.client;

import com.github.tartaricacid.touhoulittlemaid.block.BlockGomoku;
import com.github.tartaricacid.touhoulittlemaid.init.InitBlocks;
import com.github.tartaricacid.touhoulittlemaid.tileentity.*;
import com.zawu.chesspvp.ChessPvpMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Reuses the original board and piece models, with a separate, read-only block entity. */
@EventBusSubscriber(modid = ChessPvpMod.MODID, value = Dist.CLIENT)
public final class CarriedBoardRenderer {
    private static final Map<UUID, Preview> PREVIEWS = new HashMap<>();
    private record Preview(CompoundTag source, Object level, BlockPos pos, TileEntityJoy board) {}

    @SubscribeEvent
    public static void firstPerson(RenderHandEvent event) {
        Player player = Minecraft.getInstance().player;
        if (player == null || player.isSpectator() || CarriedBoardState.get(player.getUUID()) == null) return;
        event.setCanceled(true);
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        try {
            pose.translate(0, -0.58, -1.18);
            pose.mulPose(Axis.XP.rotationDegrees(18));
            pose.scale(0.42F, 0.42F, 0.42F);
            pose.translate(-0.5, -0.05, -0.5);
            draw(player, event.getPartialTick(), pose, event.getSubmitNodeCollector(), event.getPackedLight());
        } finally { pose.popPose(); }
    }

    @SubscribeEvent
    public static void thirdPerson(RenderPlayerEvent.Post<?> event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(event.getRenderState().id) instanceof Player player)) return;
        if (player.isInvisible() || player.isSpectator() || CarriedBoardState.get(player.getUUID()) == null) return;
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        try {
            pose.translate(0, player.isCrouching() ? 0.85 : 1.05, 0);
            pose.mulPose(Axis.YP.rotationDegrees(-Mth.rotLerp(event.getPartialTick(), player.yBodyRotO, player.yBodyRot)));
            pose.translate(0, 0, 0.62);
            pose.mulPose(Axis.XP.rotationDegrees(-12));
            pose.scale(0.42F, 0.42F, 0.42F);
            pose.translate(-0.5, 0, -0.5);
            draw(player, event.getPartialTick(), pose, event.getSubmitNodeCollector(), event.getRenderState().lightCoords);
        } finally { pose.popPose(); }
    }

    @SuppressWarnings("unchecked")
    private static void draw(Player player, float partialTick, PoseStack pose, SubmitNodeCollector buffers, int light) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        CompoundTag snapshot = CarriedBoardState.get(player.getUUID());
        if (snapshot == null) return;
        BlockPos pos = player.blockPosition();
        Preview cached = PREVIEWS.get(player.getUUID());
        TileEntityJoy preview;
        if (cached != null && cached.source() == snapshot && cached.level() == mc.level && cached.pos().equals(pos)) {
            preview = cached.board();
        } else {
            preview = switch (snapshot.getIntOr("Type", -1)) {
                case 0 -> new TileEntityGomoku(pos, InitBlocks.GOMOKU.get().defaultBlockState().setValue(BlockGomoku.FACING, Direction.NORTH));
                case 1 -> new TileEntityWChess(pos, InitBlocks.WCHESS.get().defaultBlockState().setValue(BlockGomoku.FACING, Direction.NORTH));
                case 2 -> new TileEntityCChess(pos, InitBlocks.CCHESS.get().defaultBlockState().setValue(BlockGomoku.FACING, Direction.NORTH));
                default -> null;
            };
            if (preview == null) return;
            preview.setLevel(mc.level);
            preview.loadAdditional(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, mc.level.registryAccess(), snapshot.getCompoundOrEmpty("BlockEntity")));
            PREVIEWS.put(player.getUUID(), new Preview(snapshot, mc.level, pos, preview));
        }
        var renderer = (BlockEntityRenderer<TileEntityJoy, net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState>)
                mc.getBlockEntityRenderDispatcher().getRenderer(preview);
        if (renderer == null) return;
        var camera = new net.minecraft.client.renderer.state.level.CameraRenderState();
        var mainCamera = mc.gameRenderer.mainCamera();
        camera.pos = mainCamera.position();
        camera.yRot = mainCamera.yRot();
        camera.xRot = mainCamera.xRot();
        camera.orientation.set(mainCamera.rotation());
        var state = renderer.createRenderState();
        renderer.extractRenderState(preview, state, partialTick, camera.pos, null);
        state.lightCoords = light;
        renderer.submit(state, pose, buffers, camera);
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        PREVIEWS.clear();
        CarriedBoardState.clear();
    }

    @SubscribeEvent
    public static void leave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof Player player) {
            PREVIEWS.remove(player.getUUID());
            CarriedBoardState.remove(player.getUUID());
        }
    }
}
