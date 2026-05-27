package net.rideable_sniffer;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.entity.passive.SnifferEntity;
import net.minecraft.entity.Entity;
import java.util.UUID;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import static net.minecraft.server.command.CommandManager.literal;
import static net.minecraft.server.command.CommandManager.argument;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RideableSnifferMod implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("rideable-sniffer");
	public static final String MOD_ID = "rideable_sniffer_mod";

	public static boolean isDebugLoggingEnabled() {
		try {
			return SnifferConfig.isDebugMode();
		} catch (Throwable ignored) {
			return false;
		}
	}

	public static void logDebug(String message, Object... args) {
		if (!isDebugLoggingEnabled()) return;
		LOGGER.info(message, args);
	}

	public static void logInfo(String message, Object... args) {
		if (!isDebugLoggingEnabled()) return;
		LOGGER.info(message, args);
	}

	public static void logWarn(String message, Object... args) {
		if (!isDebugLoggingEnabled()) return;
		LOGGER.warn(message, args);
	}

	@Override
	public void onInitialize() {
		// Load config
		SnifferConfig.loadConfig();
		logInfo("Initializing Rideable Sniffer Mod v1.0.0");
		// Register commands
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(literal("sniffer_speed")
				.then(literal("get").executes(ctx -> {
					ctx.getSource().sendFeedback(() -> Text.literal("Current sniffer driver speed: " + SnifferConfig.getDriverSpeed()), false);
					return 1;
				}))
				.then(literal("set").then(argument("value", DoubleArgumentType.doubleArg(0.1, 10.0)).executes(ctx -> {
					double v = DoubleArgumentType.getDouble(ctx, "value");
					SnifferConfig.setDriverSpeed(v);
					applySpeedToCurrentDrivenSniffer(ctx.getSource());
					ctx.getSource().sendFeedback(() -> Text.literal("Set sniffer driver speed to " + SnifferConfig.getDriverSpeed() + " and saved to config."), true);
					return 1;
				})))
				.then(literal("add").then(argument("delta", DoubleArgumentType.doubleArg(-5.0, 5.0)).executes(ctx -> {
					double delta = DoubleArgumentType.getDouble(ctx, "delta");
					double updated = SnifferConfig.getDriverSpeed() + delta;
					SnifferConfig.setDriverSpeed(updated);
					applySpeedToCurrentDrivenSniffer(ctx.getSource());
					ctx.getSource().sendFeedback(() -> Text.literal("Adjusted sniffer driver speed to " + SnifferConfig.getDriverSpeed() + " and saved to config."), true);
					return 1;
				})))
				.then(literal("reset").executes(ctx -> {
					SnifferConfig.setDriverSpeed(SnifferConfig.getDefaultDriverSpeed());
					applySpeedToCurrentDrivenSniffer(ctx.getSource());
					ctx.getSource().sendFeedback(() -> Text.literal("Reset sniffer driver speed to default (" + SnifferConfig.getDriverSpeed() + ") and saved to config."), true);
					return 1;
				}))
						// water speed controls: horizontal multiplier when sniffer is in water
						.then(literal("water")
							.then(literal("get").executes(ctx -> {
								ctx.getSource().sendFeedback(() -> Text.literal("Current sniffer water horizontal multiplier: " + SnifferConfig.getWaterSpeedFactor()), false);
								return 1;
							}))
							.then(literal("set").then(argument("value", DoubleArgumentType.doubleArg(0.05, 1.0)).executes(ctx -> {
								double v = DoubleArgumentType.getDouble(ctx, "value");
								SnifferConfig.setWaterSpeedFactor(v);
								applySpeedToCurrentDrivenSniffer(ctx.getSource());
								ctx.getSource().sendFeedback(() -> Text.literal("Set sniffer water horizontal multiplier to " + SnifferConfig.getWaterSpeedFactor() + " and saved to config."), true);
								return 1;
							})))
							.then(literal("add").then(argument("delta", DoubleArgumentType.doubleArg(-1.0, 1.0)).executes(ctx -> {
								double delta = DoubleArgumentType.getDouble(ctx, "delta");
								double updated = SnifferConfig.getWaterSpeedFactor() + delta;
								SnifferConfig.setWaterSpeedFactor(updated);
								applySpeedToCurrentDrivenSniffer(ctx.getSource());
								ctx.getSource().sendFeedback(() -> Text.literal("Adjusted sniffer water horizontal multiplier to " + SnifferConfig.getWaterSpeedFactor() + " and saved to config."), true);
								return 1;
							})))
							.then(literal("reset").executes(ctx -> {
								SnifferConfig.setWaterSpeedFactor(SnifferConfig.getDefaultWaterSpeedFactor());
								applySpeedToCurrentDrivenSniffer(ctx.getSource());
								ctx.getSource().sendFeedback(() -> Text.literal("Reset sniffer water multiplier to default (" + SnifferConfig.getWaterSpeedFactor() + ") and saved to config."), true);
								return 1;
							}))
						)
			);

			// Alias for easier typing.
			dispatcher.register(literal("snifferspeed")
				.then(literal("get").executes(ctx -> {
					ctx.getSource().sendFeedback(() -> Text.literal("Current sniffer driver speed: " + SnifferConfig.getDriverSpeed()), false);
					return 1;
				}))
				.then(literal("set").then(argument("value", DoubleArgumentType.doubleArg(0.1, 10.0)).executes(ctx -> {
					double v = DoubleArgumentType.getDouble(ctx, "value");
					SnifferConfig.setDriverSpeed(v);
					applySpeedToCurrentDrivenSniffer(ctx.getSource());
					ctx.getSource().sendFeedback(() -> Text.literal("Set sniffer driver speed to " + SnifferConfig.getDriverSpeed() + " and saved to config."), true);
					return 1;
				})))
				.then(literal("add").then(argument("delta", DoubleArgumentType.doubleArg(-5.0, 5.0)).executes(ctx -> {
					double delta = DoubleArgumentType.getDouble(ctx, "delta");
					double updated = SnifferConfig.getDriverSpeed() + delta;
					SnifferConfig.setDriverSpeed(updated);
					applySpeedToCurrentDrivenSniffer(ctx.getSource());
					ctx.getSource().sendFeedback(() -> Text.literal("Adjusted sniffer driver speed to " + SnifferConfig.getDriverSpeed() + " and saved to config."), true);
					return 1;
				})))
				.then(literal("reset").executes(ctx -> {
					SnifferConfig.setDriverSpeed(SnifferConfig.getDefaultDriverSpeed());
					applySpeedToCurrentDrivenSniffer(ctx.getSource());
					ctx.getSource().sendFeedback(() -> Text.literal("Reset sniffer driver speed to default (" + SnifferConfig.getDriverSpeed() + ") and saved to config."), true);
					return 1;
				}))
					
			);
		});
		
		registerEventListeners();
		logInfo("Rideable Sniffer Mod initialized successfully!");
	}
	
	private void registerEventListeners() {
		// Register entity interaction listener for mounting sniffers
		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (!world.isClient() && entity instanceof SnifferEntity sniffer) {
				return SnifferEventHandler.handleSnifferClick(player, sniffer, hand);
			}
			return ActionResultCompat.pass();
		});
		
		// Register server tick listener for driver control, dismounting, and seat repositioning.
		// Register player connection handlers to clean up riding state on join/disconnect.
		// Initialize persisted saddles when server starts
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			try { SnifferPassengerManager.initializePersistedSaddles(server); } catch (Throwable ignored) {}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			try { SnifferPassengerManager.onPlayerJoin(handler.player, server); } catch (Throwable ignored) {}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			try { SnifferPassengerManager.onPlayerDisconnect(handler.player, server); } catch (Throwable ignored) {}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			// Iterate the explicit set of ridden sniffers (avoid scanning all entities)
			for (UUID su : SnifferPassengerManager.getRiddenSniffers()) {
				try {
					SnifferEntity sniffer = SnifferPassengerManager.findSnifferByUuid(server, su);
					if (sniffer == null) { SnifferPassengerManager.removeRiddenSniffer(su); continue; }

					try { if (SnifferPassengerManager.hasSaddle(sniffer)) { try { SnifferPassengerManager.applyEntitySaddleTracker(sniffer, true); } catch (Throwable ignored) {} } } catch (Throwable ignored) {}

					int passengers = SnifferPassengerManager.getPassengerCount(sniffer);
					if (passengers > 0) {
						RideableSnifferMod.logDebug("[MOD DEBUG] Processing ridden Sniffer: {}", sniffer.getUuid());
						PlayerEntity driver = SnifferPassengerManager.getDriver(sniffer);
						if (driver != null) {
							SnifferEventHandler.handleDriverControl(driver, sniffer);
						}
						SnifferPassengerManager.updateSeatPositions(sniffer);
						try { SnifferPassengerManager.tickPromotionCheck(sniffer); } catch (Throwable ignored) {}
						java.util.List<PlayerEntity> riders = SnifferPassengerManager.getRiders(sniffer);
						for (PlayerEntity p : riders) {
							SnifferEventHandler.handleSneak(p);
						}
					}

					SnifferSpeedManager.restoreIfTrackedAndUnridden(sniffer);
				} catch (Throwable ignored) {}
			}

			// Also ensure persisted saddles are applied for any saddled sniffers (not necessarily ridden)
			for (UUID su : SnifferPassengerManager.getSaddledSniffers()) {
				try {
					SnifferEntity sniffer = SnifferPassengerManager.findSnifferByUuid(server, su);
					if (sniffer == null) continue;
					try { SnifferPassengerManager.applyEntitySaddleTracker(sniffer, true); } catch (Throwable ignored) {}
				} catch (Throwable ignored) {}
			}

			// Process the dismount safety watch list at the end of every tick
			SnifferPassengerManager.processDismountSafety(server);
		});
		
		logDebug("Event listeners registered successfully");
	}

	private static void applySpeedToCurrentDrivenSniffer(ServerCommandSource source) {
		try {
			PlayerEntity player = source.getPlayer();
			// Walk up vehicle chain to find sniffer (support seat entities)
			Entity v = player.getVehicle();
			while (v != null && !(v instanceof SnifferEntity)) v = v.getVehicle();
			if (v instanceof SnifferEntity sniffer && SnifferPassengerManager.isDriver(player, sniffer)) {
				SnifferSpeedManager.applyDriverSpeed(sniffer);
			}
		} catch (Exception ignored) {
			// Command may be run from console/command blocks; nothing to update immediately.
		}
	}
}

