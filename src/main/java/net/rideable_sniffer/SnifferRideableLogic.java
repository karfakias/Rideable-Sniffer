package net.rideable_sniffer;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.SnifferEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;

/**
 * Core logic for making sniffers rideable with multi-passenger support.
 * Handles mounting, dismounting, and passenger management (max 3 riders).
 */
public class SnifferRideableLogic {
	private static final int MAX_PASSENGERS = 3;
	
	public static ActionResult handleSnifferInteraction(PlayerEntity player, SnifferEntity sniffer) {
		if (player.isSpectator()) {
			return ActionResultCompat.pass();
		}
		
		// Check if player is already riding this sniffer
		if (isPlayerRidingSniffer(player, sniffer)) {
			dismountPlayer(player);
			return ActionResultCompat.pass();
		}
		
		// Try to mount the player
		if (canMountSniffer(sniffer)) {
			mountPlayerOnSniffer(player, sniffer);
			return ActionResultCompat.pass();
		}
		
		return ActionResultCompat.pass();
	}
	
	/**
	 * Mounts a player on the sniffer if there's room.
	 * Passengers are positioned in a staggered manner on the sniffer.
	 */
	private static void mountPlayerOnSniffer(PlayerEntity player, SnifferEntity sniffer) {
		int passengerCount = getPassengerCount(sniffer);
		
		if (passengerCount == 0) {
			// First passenger becomes the main rider
			player.startRiding(sniffer);
		} else {
			// Additional passengers
			player.startRiding(sniffer);
			adjustPassengerPositions(sniffer);
		}
	}
	
	/**
	 * Dismounts a player from the sniffer they're riding.
	 */
	private static void dismountPlayer(PlayerEntity player) {
		Entity vehicle = player.getVehicle();
		if (vehicle instanceof SnifferEntity) {
			player.stopRiding();
		}
	}
	
	/**
	 * Checks if a player is currently riding a specific sniffer.
	 */
	private static boolean isPlayerRidingSniffer(PlayerEntity player, SnifferEntity sniffer) {
		Entity vehicle = player.getVehicle();
		return vehicle == sniffer;
	}
	
	/**
	 * Checks if the sniffer can accept more passengers (max 3).
	 */
	private static boolean canMountSniffer(SnifferEntity sniffer) {
		return getPassengerCount(sniffer) < MAX_PASSENGERS;
	}
	
	/**
	 * Gets the current number of passengers on the sniffer.
	 */
	private static int getPassengerCount(SnifferEntity sniffer) {
		return sniffer.getPassengerList().size();
	}
	
	/**
	 * Adjusts passenger positions to distribute them on the sniffer's body.
	 * Positions passengers in a staggered manner to avoid overlap.
	 */
	private static void adjustPassengerPositions(SnifferEntity sniffer) {
		java.util.List<Entity> passengers = sniffer.getPassengerList();
		
		// Reposition passengers based on their index
		for (int i = 0; i < passengers.size(); i++) {
			Entity passenger = passengers.get(i);
			if (passenger instanceof LivingEntity livingPassenger) {
				// Offset each passenger slightly along the Z axis
				double offsetZ = -0.3 * i;
				
				// Set position relative to the sniffer
				Vec3d snifferPos = sniffer.getPos();
				passenger.setPos(
					snifferPos.x,
					snifferPos.y,
					snifferPos.z + offsetZ
				);
			}
		}
	}
}
