package net.rideable_sniffer.mixin;

import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.rideable_sniffer.DriverInputManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.server.network.ServerPlayNetworkHandler.class)
public class ServerPlayNetworkHandlerMixin {
    @Shadow @Final public ServerPlayerEntity player;

    @Inject(method = "onPlayerInput(Lnet/minecraft/network/packet/c2s/play/PlayerInputC2SPacket;)V", at = @At("HEAD"))
    private void rideableSniffer$captureInput(PlayerInputC2SPacket packet, CallbackInfo ci) {
        DriverInputManager.update(this.player, packet);
    }
}
