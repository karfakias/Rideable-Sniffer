package net.rideable_sniffer.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.passive.SnifferEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.util.math.Vec3d;
import net.rideable_sniffer.SnifferPassengerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityPassengerMixin {
    @Inject(method = "canAddPassenger", at = @At("HEAD"), cancellable = true)
    private void rideableSniffer$allowUpToThreePassengers(Entity passenger, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self instanceof SnifferEntity sniffer) {
            // Use the seat-aware passenger count so server-side seat entities
            // don't prevent creating the seats themselves.
            cir.setReturnValue(SnifferPassengerManager.getPassengerCount(sniffer) < SnifferPassengerManager.getMaxPassengers());
        }
    }

    @Inject(method = "getPassengerAttachmentPos", at = @At("HEAD"), cancellable = true)
    private void rideableSniffer$customSnifferSeats(Entity passenger, EntityDimensions dimensions, float scaleFactor, CallbackInfoReturnable<Vec3d> cir) {
        Entity self = (Entity) (Object) this;
        // Manual server-side positioning (SnifferPassengerManager.updateSeatPositions) 
        // handles spreading for vanilla clients. We exit here to avoid conflicts.
        if (self instanceof SnifferEntity) return;
    }
}
