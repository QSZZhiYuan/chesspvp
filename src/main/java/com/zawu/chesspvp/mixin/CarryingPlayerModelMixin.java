package com.zawu.chesspvp.mixin;

import com.zawu.chesspvp.client.CarriedBoardState;
import net.minecraft.client.model.player.PlayerModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerModel.class)
public abstract class CarryingPlayerModelMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
    private void chesspvp$carrying(net.minecraft.client.renderer.entity.state.AvatarRenderState state,
                                    CallbackInfo ci) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(state.id) instanceof net.minecraft.world.entity.player.Player player)) return;
        if (CarriedBoardState.get(player.getUUID()) == null) return;
        PlayerModel model = (PlayerModel) (Object) this;
        model.rightArm.xRot = -1.15F;
        model.leftArm.xRot = -1.15F;
        model.rightArm.yRot = -0.12F;
        model.leftArm.yRot = 0.12F;
        model.rightArm.zRot = 0.18F;
        model.leftArm.zRot = -0.18F;
    }
}
