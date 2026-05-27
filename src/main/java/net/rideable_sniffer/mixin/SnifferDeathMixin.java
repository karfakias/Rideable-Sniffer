package net.rideable_sniffer.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.SnifferEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.rideable_sniffer.SnifferPassengerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class SnifferDeathMixin {

    @Inject(method = "onDeath", at = @At("TAIL"))
    private void rideableSniffer$onDeath(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof SnifferEntity sniffer)) return;

        try {
            if (SnifferPassengerManager.hasSaddle(sniffer)) {
                // Remove persisted/ runtime saddle state and drop a saddle item
                try { SnifferPassengerManager.setSaddle(sniffer, false); } catch (Throwable ignored) {}
                try { SnifferPassengerManager.dropItemAtSniffer(sniffer, new ItemStack(Items.SADDLE)); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
}
