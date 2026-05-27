package net.rideable_sniffer;
import net.minecraft.entity.passive.SnifferEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.world.World;
import net.minecraft.sound.SoundEvents;
import net.minecraft.sound.SoundCategory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;

/**
 * Event handlers for sniffer passenger interactions.
 * Manages mounting, dismounting, saddle equipping, and passenger control.
 */
public class SnifferEventHandler {

    private static final Map<UUID, Integer> AIRBORNE_TICKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Double> AIRBORNE_START_Y = new ConcurrentHashMap<>();
    // Track the maximum Y observed while airborne to compute fall distance reliably
    private static final Map<UUID, Double> AIRBORNE_MAX_Y = new ConcurrentHashMap<>();
    private static final Map<UUID, Double> LAST_SNIFFER_Y = new ConcurrentHashMap<>();

    /**
     * Handles right-click interactions with sniffers.
     * - Right-click with saddle: Equip saddle (consumes item)
     * - Right-click without saddle but on saddled sniffer: Mount
     * - Right-click while riding: Dismount
     */
    public static ActionResult handleSnifferClick(PlayerEntity player, SnifferEntity sniffer, Hand hand) {
        if (player.isSpectator()) {
            return ActionResultCompat.pass();
        }

        // Only run server-side to avoid client-side visual-only changes
        try {
            World world = SnifferPassengerManager.getReflectiveWorld(sniffer);
            if (world == null) world = SnifferPassengerManager.getReflectiveWorld(player);
            // If we cannot resolve the world, or we're on the client, do nothing here.
            if (world == null || world.isClient) {
                return ActionResultCompat.pass();
            }
        } catch (Throwable ignored) {}

        // Only handle main-hand interactions to avoid duplicate callbacks
        if (hand != Hand.MAIN_HAND) return ActionResultCompat.pass();

        ItemStack handItem = player.getStackInHand(hand);
        boolean holdingSaddle = handItem.getItem() == Items.SADDLE;

        // If player is already riding this sniffer (via seat or directly), treat right-click as a dismount action.
        if (SnifferPassengerManager.isRidingSniffer(player, sniffer)) {
            // Ignore immediate dismount events that happen within a short window after mounting (duplicate callbacks).
            if (SnifferPassengerManager.wasRecentlyMounted(player, 500)) {
                try { RideableSnifferMod.logDebug("[DISMOUNT] Ignoring immediate dismount for {} (recent mount)", player.getName().getString()); } catch (Throwable ignored) {}
                return ActionResultCompat.pass();
            }
            try { RideableSnifferMod.logInfo("[DISMOUNT] Player {} right-click dismount on sniffer {}", player.getName().getString(), sniffer.getUuid()); } catch (Throwable ignored) {}
            SnifferPassengerManager.removePassenger(player);
            return ActionResultCompat.pass();
        }

        if (holdingSaddle) {
            return equipSaddle(player, sniffer, handItem);
        }

        // Right-click with shears: remove saddle and drop it
        boolean holdingShears = handItem.getItem() == Items.SHEARS;
        if (holdingShears) {
            if (!SnifferPassengerManager.hasSaddle(sniffer)) {
                player.sendMessage(Text.literal("This sniffer has no saddle."), true);
                return ActionResultCompat.pass();
            }

            // Remove saddle state and drop a saddle item at the sniffer
            SnifferPassengerManager.setSaddle(sniffer, false);
            try {
                SnifferPassengerManager.dropItemAtSniffer(sniffer, new ItemStack(Items.SADDLE));
            } catch (Throwable ignored) {}

            player.sendMessage(Text.literal("You removed the saddle from the sniffer."), false);
            try { RideableSnifferMod.logInfo("[SHEAR] Player {} ({}) removed saddle from sniffer {}", player.getName().getString(), player.getUuid(), sniffer.getUuid()); } catch (Throwable ignored) {}
            // Play the sound immediately for the actor and schedule the nearby world sound for everyone else.
            try {
                try { RideableSnifferMod.logInfo("[SHEAR] attempting immediate targeted sound for player {}", player.getName().getString()); } catch (Throwable ignored) {}
                SnifferPassengerManager.playSoundToPlayer(player, SoundEvents.ENTITY_SHEEP_SHEAR, SoundCategory.NEUTRAL, 1.0F, 1.0F);
            } catch (Throwable ignored) {}
            // Schedule saddle-removal sound a couple ticks later to ensure nearby players hear it too
            try {
                try { RideableSnifferMod.logInfo("[SHEAR] scheduling saddle removal sound for sniffer={} player={}", sniffer.getUuid(), player.getUuid()); } catch (Throwable ignored) {}
                SnifferPassengerManager.scheduleSaddleRemovalSound(sniffer, player, 2);
            } catch (Throwable ignored) {}
            return ActionResultCompat.pass();
        }

        // Prevent mounting baby sniffers if NBT age indicates a baby (-48000..-1)
        try {
            Integer age = SnifferPassengerManager.getSnifferNbtAge(sniffer);
            if (age != null) {
                if (age.intValue() < 0) {
                    player.sendMessage(Text.literal("You cannot ride a baby sniffer."), true);
                    return ActionResultCompat.pass();
                }
            } else {
                if (SnifferPassengerManager.isBaby(sniffer)) {
                    player.sendMessage(Text.literal("You cannot ride a baby sniffer."), true);
                    return ActionResultCompat.pass();
                }
            }
        } catch (Throwable ignored) {}

        if (SnifferPassengerManager.canAcceptPassenger(sniffer)) {
            if (!SnifferPassengerManager.hasSaddle(sniffer)) {
                player.sendMessage(Text.literal("This sniffer needs a saddle first! Right-click with a saddle."), true);
                return ActionResultCompat.pass();
            }

            if (SnifferPassengerManager.addPassenger(player, sniffer)) {
                int count = SnifferPassengerManager.getPassengerCount(sniffer);
                int max = SnifferPassengerManager.getMaxPassengers();

                boolean isDriver = SnifferPassengerManager.isDriver(player, sniffer);
                if (isDriver) {
                    player.sendMessage(Text.literal("You are the driver! You can steer the sniffer."), false);
                } else {
                    PlayerEntity currentDriver = SnifferPassengerManager.getDriver(sniffer);
                    if (currentDriver != null) {
                        player.sendMessage(Text.literal(currentDriver.getName().getString() + " is driving. You are a passenger."), false);
                    }
                }

                RideableSnifferMod.logInfo(
                    "Player {} mounted sniffer ({}/{}) - Driver: {}",
                    player.getName().getString(),
                    count,
                    max,
                    isDriver
                );
                return ActionResultCompat.pass();
            }
        } else {
            player.sendMessage(
                Text.literal("This sniffer is full! (" + SnifferPassengerManager.getMaxPassengers() + " riders max)"),
                true
            );
        }

        return ActionResultCompat.pass();
    }

    /**
     * Approximate the water surface Y near the sniffer by scanning upward
     * from the sniffer's block position for the first non-water block.
     */
    private static double findWaterSurfaceY(SnifferEntity sniffer) {
        if (sniffer == null) return 0.0D;
        try {
              World world = SnifferPassengerManager.getReflectiveWorld(sniffer);
            BlockPos base = sniffer.getBlockPos();
            int x = base.getX();
            int z = base.getZ();
            int startY = base.getY();
            int maxSearch = 8;
            int lastWaterY = startY;
            for (int y = startY; y <= startY + maxSearch; y++) {
                BlockPos p = new BlockPos(x, y, z);
                FluidState fs = world.getFluidState(p);
                if (fs.getFluid() == Fluids.WATER) {
                    lastWaterY = y;
                    continue;
                } else {
                    // first non-water block above water; approximate surface at this Y
                    return (double) y;
                }
            }
            return (double) (lastWaterY + 1);
        } catch (Throwable ignored) {}
        return sniffer.getY();
    }

    /**
     * Equips a saddle on the sniffer and consumes the saddle from player inventory.
     * Only one saddle per sniffer - shows message if already saddled.
     */
    private static ActionResult equipSaddle(PlayerEntity player, SnifferEntity sniffer, ItemStack saddle) {
        if (SnifferPassengerManager.hasSaddle(sniffer)) {
            player.sendMessage(Text.literal("This sniffer already has a saddle!"), true);
            return ActionResultCompat.pass();
        }

        // Prevent saddling baby sniffers when NBT age indicates a baby (-48000..-1)
        try {
            Integer age = SnifferPassengerManager.getSnifferNbtAge(sniffer);
            if (age != null) {
                if (age.intValue() < 0) {
                    player.sendMessage(Text.literal("You cannot saddle a baby sniffer."), true);
                    return ActionResultCompat.pass();
                }
            } else {
                if (SnifferPassengerManager.isBaby(sniffer)) {
                    player.sendMessage(Text.literal("You cannot saddle a baby sniffer."), true);
                    return ActionResultCompat.pass();
                }
            }
        } catch (Throwable ignored) {}

        SnifferPassengerManager.setSaddle(sniffer, true);

        if (!player.getAbilities().creativeMode) {
            saddle.decrement(1);
        }

        // If age detection was inconclusive, schedule a short validation to catch obfuscated/broken mappings
        try {
            Integer ageCheck = SnifferPassengerManager.getSnifferNbtAge(sniffer);
            boolean babyCheck = false;
            try { babyCheck = SnifferPassengerManager.isBaby(sniffer); } catch (Throwable ignored) {}
            if (ageCheck == null && !babyCheck) {
                try { RideableSnifferMod.logInfo("[SADDLE] Age inconclusive - scheduling validation for sniffer {}", sniffer.getUuid()); } catch (Throwable ignored) {}
                try { SnifferPassengerManager.scheduleSaddleValidation(sniffer, player, 2); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        player.sendMessage(Text.literal("You saddled the sniffer! Now you can ride it."), false);
        RideableSnifferMod.logInfo("Player {} equipped saddle on sniffer", player.getName().getString());
        return ActionResultCompat.pass();
    }

    /**
     * Handles dismount when player is sneaking (holding Shift).
     * Called from server tick events.
     * Shows driver promotion messages.
     */
    public static void handleSneak(PlayerEntity player) {
        if (!player.isSneaking()) return;

        Entity v = player.getVehicle();
        if (v == null) return;

        // Entry log for dismount logic (promote debugging)
        try { RideableSnifferMod.logInfo("[DISMOUNT] Sneak detected. Player: {} vehicle={} ", player.getName().getString(), v.getUuid()); } catch (Throwable ignored) {}

        SnifferEntity sniffer = null;
        if (v instanceof SnifferEntity sDirect) {
            sniffer = sDirect;
        } else if (v instanceof ArmorStandEntity) {
            sniffer = SnifferPassengerManager.getSnifferForSeat(v);
        } else {
            // Check the vehicle chain (seat could itself be mounted)
            try { Entity top = v.getVehicle(); if (top instanceof SnifferEntity s2) sniffer = s2; } catch (Throwable ignored) {}
            if (sniffer == null) sniffer = SnifferPassengerManager.getSnifferForSeat(v);
        }

        if (sniffer == null) {
            try { RideableSnifferMod.logInfo("[DISMOUNT] Could not resolve sniffer for vehicle {} (player {})", v.getUuid(), player.getName().getString()); } catch (Throwable ignored) {}
            return;
        }

        boolean wasDriver = SnifferPassengerManager.isDriver(player, sniffer);
        RideableSnifferMod.logDebug("[MOD DEBUG] Proceeding to remove passenger {} from Sniffer {}", player.getName().getString(), sniffer.getUuid());
        SnifferPassengerManager.removePassenger(player);

        if (wasDriver && SnifferPassengerManager.getPassengerCount(sniffer) > 0) {
            PlayerEntity newDriver = SnifferPassengerManager.getDriver(sniffer);
            if (newDriver != null) {
                newDriver.sendMessage(Text.literal("You are now the driver! You can steer the sniffer."), false);
                RideableSnifferMod.logInfo("Player {} is now driving the sniffer", newDriver.getName().getString());
            }
        }
    }

    public static void handleDriverControl(PlayerEntity player, SnifferEntity sniffer) {
        if (!SnifferPassengerManager.isDriver(player, sniffer)) {
            return;
        }

        SnifferSpeedManager.applyDriverSpeed(sniffer);

        float forward = player.forwardSpeed;
        float sideways = player.sidewaysSpeed;

        if (player instanceof ServerPlayerEntity serverPlayer) {
            DriverInputManager.DriverInput input = DriverInputManager.get(serverPlayer);
            if (input != null) {
                forward = input.forward();
                sideways = input.sideways();
                if (input.sneaking()) {
                    SnifferPassengerManager.removePassenger(player);
                    return;
                }
            }
        } else {
            Vec3d movementInput = readMovementInput(player);
            if (movementInput != null) {
                sideways = (float) movementInput.x;
                forward = (float) movementInput.z;
            }
        }

        // Hard-control mode: turn with A/D and translate with W/S each tick.
        // Apply a small constant downward step to keep gravity/step-down behavior reliable.
        float yaw = SnifferPassengerManager.getReflectiveFloat(sniffer, "getYaw", "method_5705", "field_6031", "yaw");
        yaw -= sideways * 4.5F;
        sniffer.setYaw(yaw);
        sniffer.setBodyYaw(yaw);
        sniffer.setHeadYaw(yaw);

        float throttle = Math.max(-1.0F, Math.min(1.0F, forward));
        if (throttle < 0.0F) {
            throttle *= 0.60F; // slightly slower reverse.
        }

        // Restore manual collision-aware movement to ensure the sniffer
        // responds to driver input server-side. Also keep gravity enabled.
        double dx = 0.0D;
        double dz = 0.0D;
        if (throttle != 0.0F) {
            float yawRad = yaw * ((float) Math.PI / 180.0F);
            double speed = SnifferConfig.getDriverSpeed() * 0.30D;
            dx = -Math.sin(yawRad) * speed * throttle;
            dz = Math.cos(yawRad) * speed * throttle;
        }

        // Ensure gravity is enabled.
        sniffer.setNoGravity(false);

        // Track airborne time per sniffer using position deltas (more robust than isOnGround when we control movement).
        UUID id = sniffer.getUuid();
        double currentY = SnifferPassengerManager.getReflectiveDouble(sniffer, "getY", "method_23318", "field_6039", "y");
        boolean onGround = sniffer.isOnGround();
        double lastY = LAST_SNIFFER_Y.getOrDefault(id, currentY);
        boolean inWater = false;
        try {
            try { inWater = sniffer.isTouchingWater(); } catch (Throwable ignored) {}
            if (!inWater) try { inWater = sniffer.isSubmergedInWater(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        // Use a slightly larger threshold to avoid tiny positional noise triggering airborne state
        boolean descending = currentY < lastY - 0.02D;
        int prevAirborne = AIRBORNE_TICKS.getOrDefault(id, 0);
        int airborne = prevAirborne;
        if (inWater) {
            if (prevAirborne > 0) {
                try { RideableSnifferMod.logInfo("[FALL-WATER] Sniffer {} entered water at y={} after {} airborne ticks; clearing fall tracking", id, currentY, prevAirborne); } catch (Throwable ignored) {}
            }
            clearAirborneTracking(id);
            airborne = 0;
            try { SnifferPassengerManager.resetFallDistance(sniffer); } catch (Throwable ignored) {}
        } else if (descending) {
            // Mark the starting Y when the sniffer first begins falling (use lastY as the top)
            if (prevAirborne == 0) {
                AIRBORNE_START_Y.put(id, lastY);
                AIRBORNE_MAX_Y.put(id, lastY);
                try { RideableSnifferMod.logInfo("[FALL-START] Sniffer {} startY={} lastY={} currentY={}", id, lastY, lastY, currentY); } catch (Throwable ignored) {}
            } else {
                // While airborne, update the observed maximum Y so we capture the true drop start
                try {
                    Double mx = AIRBORNE_MAX_Y.get(id);
                    if (mx == null || lastY > mx) AIRBORNE_MAX_Y.put(id, lastY);
                } catch (Throwable ignored) {}
            }
            airborne = prevAirborne + 1;
            AIRBORNE_TICKS.put(id, airborne);
        } else {
            // If we were falling and have stopped descending, consider this a landing and apply damage
            if (prevAirborne > 0) {
                try {
                    Double start = AIRBORNE_MAX_Y.get(id);
                    if (start == null) start = AIRBORNE_START_Y.get(id);
                    if (start == null) start = lastY;
                    double fallDist = start - currentY;
                    try { RideableSnifferMod.logInfo("[FALL-LAND] Sniffer {} landed start={} currentY={} fallDist={} prevAirborne={} onGround={}", id, start, currentY, fallDist, prevAirborne, onGround); } catch (Throwable ignored) {}
                    Float hpBefore = null;
                    try { hpBefore = SnifferPassengerManager.getReflectiveHealth(sniffer); } catch (Throwable ignored) {}
                    if (fallDist > 3.0D) {
                        try { applyFallDamageToEntity(sniffer, fallDist); } catch (Throwable e) { try { RideableSnifferMod.logWarn("[FALL-ERR] applyFallDamageToEntity threw for sniffer {}", id, e); } catch (Throwable ignored) {} }
                        try {
                            Float hpAfter = SnifferPassengerManager.getReflectiveHealth(sniffer);
                            try { RideableSnifferMod.logInfo("[FALL-DMG] Sniffer {} HP before={} after={}", id, hpBefore, hpAfter); } catch (Throwable ignored) {}
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
                clearAirborneTracking(id);
                airborne = 0;
            }
        }
        LAST_SNIFFER_Y.put(id, currentY);

        // Gravity tuning: base gravity per tick, extra added per airborne tick,
        // clamped to a sensible maximum so fall doesn't get out of control.
        double baseGravity = 0.12D;
        double extraPerTick = 0.06D;
        double maxExtra = 1.2D; // caps additional gravity
        double extra = Math.min(airborne * extraPerTick, maxExtra);
        // Restore base gravity when on the ground to let vanilla stepping/gravity behave normally.
        double vertical = onGround ? -baseGravity : -(baseGravity + extra);

        // Water behavior: if the sniffer is touching/inside water, try to float
        // so roughly half the body is submerged and reduce horizontal movement.
        if (inWater) {
                try {
                // Reduce horizontal movement significantly in water using configured multiplier
                double waterHorizFactor = SnifferConfig.getWaterSpeedFactor();
                dx *= waterHorizFactor;
                dz *= waterHorizFactor;

                // Compute approximate water surface Y and target floating Y
                double surfaceY = findWaterSurfaceY(sniffer);
                double height = SnifferPassengerManager.getReflectiveFloat(sniffer, "getHeight", "method_17682", "field_6008", "height");
                double targetY = surfaceY - (height * 0.5D) + 0.05D; // small offset so half body sits above water

                // Gently push towards target Y (clamped)
                double diff = targetY - currentY;
                double push = Math.max(Math.min(diff * 0.25D, 0.18D), -0.18D);
                vertical = push;

                // While in water, reduce horizontal more if deeply submerged
                if (diff > 0.6D) {
                    dx *= 0.6D; dz *= 0.6D;
                }
            } catch (Throwable ignored) {}
        }

        // Horizontal damping while airborne: reduce sideways movement the longer
        // the sniffer remains in the air. This prevents strong lateral glide.
        double minHorizMul = 0.15D;         // minimum horizontal multiplier
        double horizReductionPerTick = 0.06D; // how much multiplier reduces per tick
        double horizMul = 1.0D;
        if (!onGround) {
            horizMul = Math.max(minHorizMul, 1.0D - (airborne * horizReductionPerTick));
        }
        dx *= horizMul;
        dz *= horizMul;

        Vec3d step = new Vec3d(dx, vertical, dz);
        SnifferPassengerManager.invokeReflective(sniffer, 
            new Object[]{MovementType.SELF, step}, 
            "move", "method_5784");
    }

    private static Vec3d readMovementInput(PlayerEntity player) {
        String[] fieldNames = new String[] {"movementInput", "field_47820", "bp"};
        Class<?> cls = player.getClass();
        while (cls != null) {
            try {
                for (String fieldName : fieldNames) {
                    java.lang.reflect.Field f = cls.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    Object value = f.get(player);
                    if (value instanceof Vec3d vec) {
                        return vec;
                    }
                }
            } catch (ReflectiveOperationException ignored) {
                // Keep scanning.
            }
            cls = cls.getSuperclass();
        }

        // Last-chance fallback: first Vec3d-typed field we can read on player hierarchy.
        cls = player.getClass();
        while (cls != null) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (f.getType() != Vec3d.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object value = f.get(player);
                    if (value instanceof Vec3d vec) {
                        return vec;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // Continue scanning.
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static DamageSource resolveFallDamageSource(Entity ent) {
        try {
            if (ent != null) {
                return ent.getDamageSources().fall();
            }
        } catch (Throwable ignored) {}

        try {
            for (java.lang.reflect.Field f : DamageSource.class.getDeclaredFields()) {
                try {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType() != DamageSource.class) continue;
                    String n = f.getName().toLowerCase();
                    if (n.contains("fall")) {
                        f.setAccessible(true);
                        Object v = f.get(null);
                        if (v instanceof DamageSource) return (DamageSource) v;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try { java.lang.reflect.Constructor<?> c = DamageSource.class.getDeclaredConstructor(String.class); c.setAccessible(true); return (DamageSource) c.newInstance("fall"); } catch (Throwable ignored) {}
        return null;
    }

    private static void clearAirborneTracking(UUID id) {
        if (id == null) return;
        try { AIRBORNE_START_Y.remove(id); } catch (Throwable ignored) {}
        try { AIRBORNE_MAX_Y.remove(id); } catch (Throwable ignored) {}
        try { AIRBORNE_TICKS.remove(id); } catch (Throwable ignored) {}
    }

    private static void applyFallDamageToEntity(Entity ent, double fallDist) {
        if (!(ent instanceof LivingEntity living)) return;

        float damage = (float) Math.max(0.0D, fallDist - 3.0D);
        if (damage <= 0.0F) return;

        DamageSource ds = resolveFallDamageSource(ent);
        if (ds == null) return;

        Float hpBefore = null;
        try { hpBefore = SnifferPassengerManager.getReflectiveHealth(ent); } catch (Throwable ignored) {}

        boolean damaged = invokeLivingDamage(living, ds, damage);
        SnifferPassengerManager.resetFallDistance(ent);

        try {
            Float hpAfter = SnifferPassengerManager.getReflectiveHealth(ent);
            RideableSnifferMod.logInfo(
                "[FALL-APPLY] direct fall damage ent={} fallDist={} damage={} applied={} hpBefore={} hpAfter={}",
                ent.getUuid(),
                fallDist,
                damage,
                damaged,
                hpBefore,
                hpAfter
            );
        } catch (Throwable ignored) {}
    }

    private static boolean invokeLivingDamage(LivingEntity living, DamageSource source, float amount) {
        if (living == null || source == null || amount <= 0.0F) return false;

        World world = SnifferPassengerManager.getReflectiveWorld(living);
        ServerWorld serverWorld = world instanceof ServerWorld sw ? sw : null;

        Class<?> cls = living.getClass();
        while (cls != null && cls != Object.class) {
            for (java.lang.reflect.Method method : cls.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                Class<?> returnType = method.getReturnType();
                if (returnType != boolean.class && returnType != Boolean.class) continue;

                try {
                    if (params.length == 3
                        && serverWorld != null
                        && ServerWorld.class.isAssignableFrom(params[0])
                        && DamageSource.class.isAssignableFrom(params[1])
                        && isFloatParameter(params[2])) {
                        method.setAccessible(true);
                        Object result = method.invoke(living, serverWorld, source, amount);
                        return result instanceof Boolean ? ((Boolean) result).booleanValue() : false;
                    }

                    if (params.length == 2
                        && DamageSource.class.isAssignableFrom(params[0])
                        && isFloatParameter(params[1])) {
                        method.setAccessible(true);
                        Object result = method.invoke(living, source, amount);
                        return result instanceof Boolean ? ((Boolean) result).booleanValue() : false;
                    }
                } catch (Throwable e) {
                    try { RideableSnifferMod.logDebug("[FALL-APPLY] damage signature invoke failed on {}: {}", method.getName(), e.toString()); } catch (Throwable ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }

        return false;
    }

    private static boolean isFloatParameter(Class<?> type) {
        return type == float.class || type == Float.class;
    }


}

