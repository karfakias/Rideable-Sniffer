package net.rideable_sniffer.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.SnifferEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.rideable_sniffer.SnifferPassengerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MobEntity.class)
public class MobControlMixin {
    @Inject(method = "getControllingPassenger()Lnet/minecraft/entity/LivingEntity;", at = @At("HEAD"), cancellable = true)
    private void rideableSniffer$controller(CallbackInfoReturnable<LivingEntity> cir) {
        MobEntity self = (MobEntity) (Object) this;
        if (!(self instanceof SnifferEntity sniffer)) {
            return;
        }

        PlayerEntity driver = SnifferPassengerManager.getDriver(sniffer);
        if (driver != null) {
            cir.setReturnValue(driver);
        }
    }

}
