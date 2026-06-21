package net.rideable_sniffer.mixin;

import net.minecraft.block.Portal;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.rideable_sniffer.SnifferPassengerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class EntityPortalMixin {
    @Inject(method = "tryUsePortal", at = @At("HEAD"), cancellable = true)
    private void rideableSniffer$handlePortalContact(Portal portal, BlockPos pos, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (SnifferPassengerManager.handlePortalContact(self)) {
            ci.cancel();
        }
    }
}
