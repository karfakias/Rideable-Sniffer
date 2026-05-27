package net.rideable_sniffer;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.SnifferEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.world.World;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages multi-passenger riding on sniffers.
 * Handles passenger positioning, mounting, dismounting, and saddle requirements.
 */
public class SnifferPassengerManager {
    private static final int MAX_PASSENGERS = 3;
    private static final Set<UUID> SADDLED_SNIFFERS = ConcurrentHashMap.newKeySet();
    // Ensure persisted saddles are loaded once per server lifecycle
    private static volatile boolean PERSISTED_LOADED = false;
    // Entities per sniffer (server-side only). Includes invisible ArmorStands for seats.
    private static final Map<UUID, java.util.List<Entity>> SEAT_ENTITIES = new ConcurrentHashMap<>();
    private static final Map<UUID, DismountInfo> DISMOUNT_WATCH = new ConcurrentHashMap<>();

    private static final Map<UUID, RidingInfo> RIDING_PLAYERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Vec3d> DISCONNECT_SAFE_POSITIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, PromotionRetry> PROMOTION_RETRIES = new ConcurrentHashMap<>();
    private static final Map<UUID, Vec3d> LAST_SNIFFER_POSITIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> RECENT_MOUNTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> PROMOTION_SCHEDULE = new ConcurrentHashMap<>();
    // Scheduled sounds for saddle removal: ticks remaining and the player who removed it
    private static final Map<UUID, Integer> SADDLE_SOUND_TICKS = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> SADDLE_SOUND_PLAYER = new ConcurrentHashMap<>();
    // Scheduled saddle validation: ticks remaining and the player who initiated the saddle (optional)
    private static final Map<UUID, Integer> SADDLE_VALIDATE_TICKS = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> SADDLE_VALIDATE_PLAYER = new ConcurrentHashMap<>();
    // Ridden sniffers set (tracked to avoid scanning world entities each tick)
    private static final Set<UUID> RIDDEN_SNIFFERS = ConcurrentHashMap.newKeySet();
    // Per-sniffer tick counter used to periodically check and promote drivers
    private static final Map<UUID, Integer> PROMOTION_CHECK_TICKS = new ConcurrentHashMap<>();
    // Scheduled shift actions when seats free up
    private static final Map<UUID, Integer> SHIFT_SCHEDULE = new ConcurrentHashMap<>();
    // Retries for seats that could not be removed immediately (graveyard cleanup)
    private static final Map<UUID, Integer> STALE_REMOVAL_RETRIES = new ConcurrentHashMap<>();
    private static final int MAX_STALE_REMOVES = 5;

    private static final class DismountInfo {
        public final Vec3d target;
        public final Vec3d snifferPos;
        public int attempts;
        DismountInfo(Vec3d target, Vec3d snifferPos, int attempts) {
            this.target = target;
            this.snifferPos = snifferPos;
            this.attempts = attempts;
        }
    }

    private static final class RidingInfo {
        public final UUID snifferUuid;
        public final boolean driver;
        public double lastY;
        RidingInfo(UUID snifferUuid, boolean driver, double lastY) {
            this.snifferUuid = snifferUuid;
            this.driver = driver;
            this.lastY = lastY;
        }
    }

    private static final class PromotionRetry {
        public final UUID seatUuid;
        public int attempts;
        PromotionRetry(UUID seatUuid, int attempts) { this.seatUuid = seatUuid; this.attempts = attempts; }
    }

    private static Vec3d getValidatedSnifferExitTarget(SnifferEntity sniffer, Entity seat) {
        if (sniffer == null) return Vec3d.ZERO;

        double sx = sniffer.getX();
        double sy = sniffer.getY();
        double sz = sniffer.getZ();
        float yaw = sniffer.getYaw();

        double sideYawRad = (yaw + 90.0) * (Math.PI / 180.0);
        double ox = -Math.sin(sideYawRad) * 1.5;
        double oz = Math.cos(sideYawRad) * 1.5;
        double baseY = seat != null ? seat.getY() : sy;

        Vec3d snifferPos = new Vec3d(sx, sy, sz);
        Vec3d target = new Vec3d(sx + ox, baseY + 0.5, sz + oz);
        return sanitizeExitTarget(target, snifferPos);
    }

    private static Vec3d sanitizeExitTarget(Vec3d target, Vec3d snifferPos) {
        if (target == null || snifferPos == null) return Vec3d.ZERO;

        if (!Double.isFinite(target.x) || !Double.isFinite(target.y) || !Double.isFinite(target.z)) {
            return new Vec3d(snifferPos.x, snifferPos.y + 1.0, snifferPos.z);
        }

        if (Math.abs(target.y - snifferPos.y) > 2.5 || target.y < 5.0) {
            return new Vec3d(snifferPos.x, snifferPos.y + 1.0, snifferPos.z);
        }

        return target;
    }

    public static SnifferEntity findSnifferByUuid(net.minecraft.server.MinecraftServer server, UUID id) {
        if (server == null || id == null) return null;
        try {
            for (var world : server.getWorlds()) {
                for (Entity e : world.iterateEntities()) {
                    if (e instanceof SnifferEntity s && s.getUuid().equals(id)) return s;
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    public static java.util.Set<UUID> getRiddenSniffers() {
        try { return new java.util.HashSet<>(RIDDEN_SNIFFERS); } catch (Throwable ignored) { return java.util.Collections.emptySet(); }
    }

    public static java.util.Set<UUID> getSaddledSniffers() {
        try { return new java.util.HashSet<>(SADDLED_SNIFFERS); } catch (Throwable ignored) { return java.util.Collections.emptySet(); }
    }

    public static void removeRiddenSniffer(UUID id) {
        try { if (id != null) RIDDEN_SNIFFERS.remove(id); } catch (Throwable ignored) {}
    }

    public static boolean hasSaddle(SnifferEntity sniffer) {
        return sniffer != null && SADDLED_SNIFFERS.contains(sniffer.getUuid());
    }

    public static void setSaddle(SnifferEntity sniffer, boolean saddled) {
        if (sniffer == null) return;
        RideableSnifferMod.logDebug("[MOD DEBUG] setSaddle called for sniffer {} saddled={}", sniffer.getUuid(), saddled);
        // Only modify server-side state; ignore client-side calls so visuals come from server tracker updates
        try {
            World w = getReflectiveWorld(sniffer);
            if (w != null && w.isClient) {
                try { RideableSnifferMod.logDebug("[SADDLE] Ignoring setSaddle on client for sniffer {}", sniffer.getUuid()); } catch (Throwable ignored) {}
                return;
            }
        } catch (Throwable ignored) {}

        // Debug: log age/isBaby at the start of any saddle state change attempt
        try {
            Integer dbgAge = null;
            boolean dbgBaby = false;
            try { dbgAge = getSnifferNbtAge(sniffer); } catch (Throwable ignored) {}
            try { dbgBaby = isBaby(sniffer); } catch (Throwable ignored) {}
            try { RideableSnifferMod.logDebug("[SADDLE DEBUG] Attempt setSaddle sniffer={} saddled={} nbtAge={} isBaby={}", sniffer.getUuid(), saddled, dbgAge == null ? "null" : dbgAge, dbgBaby); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}

        // Prevent saddling baby sniffers if NBT shows a non-zero age (some mappings use positive values)
        if (saddled) {
            try {
                Integer age = getSnifferNbtAge(sniffer);
                if (age != null) {
                    if (age.intValue() < 0) {
                        try { RideableSnifferMod.logInfo("[SADDLE] Refusing to saddle sniffer {} due to NBT age={}", sniffer.getUuid(), age); } catch (Throwable ignored) {}
                        return;
                    }
                } else {
                    if (isBaby(sniffer)) {
                        try { RideableSnifferMod.logInfo("[SADDLE] Refusing to saddle baby sniffer {}", sniffer.getUuid()); } catch (Throwable ignored) {}
                        return;
                    }
                }
            } catch (Throwable ignored) {}
            SADDLED_SNIFFERS.add(sniffer.getUuid());
            try { SnifferConfig.addPersistedSaddle(sniffer.getUuid()); } catch (Throwable ignored) {}
        } else {
            SADDLED_SNIFFERS.remove(sniffer.getUuid());
            try { SnifferConfig.removePersistedSaddle(sniffer.getUuid()); } catch (Throwable ignored) {}
        }
        // Update the sniffer entity's tracked SADDLED flag so clients render the saddle
        try { applyEntitySaddleTracker(sniffer, saddled); } catch (Throwable ignored) {}
    }

    /**
     * Initialize runtime saddle state from persisted config. Should be called once when the server starts.
     */
    public static void initializePersistedSaddles(net.minecraft.server.MinecraftServer server) {
        if (PERSISTED_LOADED) return;
        PERSISTED_LOADED = true;
        try {
            java.util.Set<UUID> persisted = SnifferConfig.getPersistedSaddles();
            if (persisted != null) {
                for (UUID u : persisted) {
                    try { if (u != null) SADDLED_SNIFFERS.add(u); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        if (server == null) return;
        try {
            for (var world : server.getWorlds()) {
                for (Entity e : world.iterateEntities()) {
                    if (e instanceof SnifferEntity s) {
                        try {
                            if (SADDLED_SNIFFERS.contains(s.getUuid())) {
                                try { applyEntitySaddleTracker(s, true); } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {}
                    }
                    
                }
            }
        } catch (Throwable ignored) {}
    }

    public static boolean addPassenger(PlayerEntity player, SnifferEntity sniffer) {
        // Do not allow riding baby sniffers (vanilla uses negative age for babies).
        try {
            Integer age = getSnifferNbtAge(sniffer);
            if (age != null) {
                if (age.intValue() < 0) {
                    try { RideableSnifferMod.logInfo("[MOUNT] addPassenger refused sniffer={} player={} reason=nbtAge={} (baby <0)", sniffer.getUuid(), player.getUuid(), age); } catch (Throwable ignored) {}
                    return false;
                }
            } else {
                if (isBaby(sniffer)) return false;
            }
        } catch (Throwable ignored) {}
        // If there's a pending validation for this sniffer, refuse mounting until validation completes
        try {
            if (sniffer != null && SADDLE_VALIDATE_TICKS.containsKey(sniffer.getUuid())) {
                try { player.sendMessage(Text.literal("Saddle validation in progress - please wait a moment."), true); } catch (Throwable ignored) {}
                return false;
            }
        } catch (Throwable ignored) {}
        if (!hasSaddle(sniffer)) return false;
        int currentPlayers = getPassengerCount(sniffer);
        if (currentPlayers >= MAX_PASSENGERS) return false;

        java.util.List<Entity> entities = ensureSeats(sniffer);
        ArmorStandEntity freeSeat = null;
        for (Entity e : entities) {
            if (e instanceof ArmorStandEntity seat) {
                if (seat.getPassengerList().isEmpty()) { freeSeat = seat; break; }
            }
        }
        if (freeSeat == null) return false;
        if (!player.startRiding(freeSeat)) return false;

        // Mark which seat the player is occupying so we can clean up on disconnect
        try { freeSeat.addCommandTag("SnifferOccupant:" + player.getUuid()); } catch (Throwable ignored) {}

        boolean isDriver = (currentPlayers == 0);
        if (isDriver) SnifferSpeedManager.applyDriverSpeed(sniffer);

        RIDING_PLAYERS.put(player.getUuid(), new RidingInfo(sniffer.getUuid(), isDriver, player.getY()));
        try { RECENT_MOUNTS.put(player.getUuid(), System.currentTimeMillis()); } catch (Throwable ignored) {}
        try { RIDDEN_SNIFFERS.add(sniffer.getUuid()); } catch (Throwable ignored) {}
        return true;
    }

    public static boolean wasRecentlyMounted(PlayerEntity player, long thresholdMillis) {
        if (player == null) return false;
        Long t = RECENT_MOUNTS.get(player.getUuid());
        if (t == null) return false;
        long now = System.currentTimeMillis();
        if (now - t <= thresholdMillis) return true;
        RECENT_MOUNTS.remove(player.getUuid());
        return false;
    }

    public static void removePassenger(PlayerEntity player) {
        Entity vehicle = player.getVehicle();
        // Try to resolve the sniffer for this vehicle via several fallbacks
        SnifferEntity sniffer = null;
        try {
            if (vehicle instanceof SnifferEntity sDirect) sniffer = sDirect;
            else if (vehicle instanceof ArmorStandEntity) sniffer = getSnifferForSeat(vehicle);
            else {
                try { Entity top = vehicle != null ? vehicle.getVehicle() : null; if (top instanceof SnifferEntity sTop) sniffer = sTop; } catch (Throwable ignored) {}
                if (sniffer == null && vehicle instanceof ArmorStandEntity) sniffer = getSnifferForSeat(vehicle);
            }
        } catch (Throwable ignored) {}

        try { RideableSnifferMod.logInfo("[DISMOUNT] Player {} dismount requested. vehicle={} resolvedSniffer={}", player.getName().getString(), vehicle == null ? "null" : vehicle.getUuid(), sniffer == null ? "null" : sniffer.getUuid()); } catch (Throwable ignored) {}

        RIDING_PLAYERS.remove(player.getUuid());

        // Schedule a short delayed shift so remaining passengers move forward
        try { if (sniffer != null) scheduleShift(sniffer, 2); } catch (Throwable ignored) {}

        if (sniffer != null) {
            ArmorStandEntity seat = (vehicle instanceof ArmorStandEntity) ? (ArmorStandEntity) vehicle : null;
            boolean wasDriver = isDriver(player, sniffer);

            final double sx = sniffer.getX();
            final double sy = sniffer.getY();
            final double sz = sniffer.getZ();
            final float yaw = sniffer.getYaw();

            // Remove occupant tag from the seat we're leaving
            try {
                if (seat != null) {
                    try { seat.getCommandTags().remove("SnifferOccupant:" + player.getUuid()); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}

            try { player.stopRiding(); } catch (Throwable ignored) {}

            Vec3d safeTarget = getValidatedSnifferExitTarget(sniffer, seat);

            resetFallDistance(player);
            player.setVelocity(Vec3d.ZERO);

            DISMOUNT_WATCH.put(player.getUuid(), new DismountInfo(safeTarget, new Vec3d(sx, sy, sz), 3));

            if (wasDriver) {
                int remaining = getPassengerCount(sniffer);
                try { RideableSnifferMod.logInfo("[PROMOTE] Driver {} dismounted from sniffer {} â€” remaining passengers: {}/{}", player.getName().getString(), sniffer.getUuid(), remaining, getMaxPassengers()); } catch (Throwable ignored) {}
                // We schedule a seat-shift earlier; it will move remaining passengers forward.
                if (remaining > 0) {
                    try { RideableSnifferMod.logInfo("[PROMOTE] Driver left; seat-shift scheduled for sniffer {}", sniffer.getUuid()); } catch (Throwable ignored) {}
                } else {
                    try {
                        java.util.List<Entity> seats = SEAT_ENTITIES.get(sniffer.getUuid());
                        if (seats != null && !seats.isEmpty()) {
                            double yawRad = yaw * ((double) Math.PI / 180.0);
                            double sin = Math.sin(yawRad), cos = Math.cos(yawRad);
                            double[] fOffs = {1.10, 0.00, -1.10};
                            double[] yOffs = {0.06, 0.00, -0.03};
                            double baseYOffset = getReflectiveFloat(sniffer, "getHeight", "method_17682", "field_6008", "height") * 0.04;
                            for (Entity e : seats) {
                                int idx = getLogicalSeatIndex(sniffer, e);
                                double x = sx + (-sin * fOffs[idx]);
                                double z = sz + (cos * fOffs[idx]);
                                e.setPos(x, sy + baseYOffset + yOffs[idx], z);
                                e.setYaw(yaw);
                            }
                        }
                    } catch (Throwable ignored) {}
                    removeSeats(sniffer);
                    SnifferSpeedManager.restoreOriginalSpeed(sniffer);
                }
            }

            try { if (getPassengerCount(sniffer) <= 0) RIDDEN_SNIFFERS.remove(sniffer.getUuid()); else RIDDEN_SNIFFERS.add(sniffer.getUuid()); } catch (Throwable ignored) {}
        } else {
            // No sniffer resolved: ensure the player is stopped riding and clear any occupant tag on the vehicle
            try {
                if (vehicle != null) {
                    if (vehicle instanceof ArmorStandEntity ase) {
                        try { ase.getCommandTags().remove("SnifferOccupant:" + player.getUuid()); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
            try { player.stopRiding(); } catch (Throwable ignored) {}
        }
    }

    public static void schedulePromotion(SnifferEntity sniffer, int delayTicks) {
        if (sniffer == null) return;
        // Ensure seats exist immediately so promotion has targets when it runs
        try { ensureSeats(sniffer); } catch (Throwable ignored) {}
        try { PROMOTION_SCHEDULE.put(sniffer.getUuid(), Math.max(1, delayTicks)); } catch (Throwable ignored) {}
        try { RideableSnifferMod.logDebug("[PROMOTE] Scheduled promotion for sniffer {} in {} ticks", sniffer.getUuid(), delayTicks); } catch (Throwable ignored) {}
    }

    public static void scheduleShift(SnifferEntity sniffer, int delayTicks) {
        if (sniffer == null) return;
        try { ensureSeats(sniffer); } catch (Throwable ignored) {}
        try { SHIFT_SCHEDULE.put(sniffer.getUuid(), Math.max(1, delayTicks)); } catch (Throwable ignored) {}
        try { RideableSnifferMod.logDebug("[PROMOTE] Scheduled seat-shift for sniffer {} in {} ticks", sniffer.getUuid(), delayTicks); } catch (Throwable ignored) {}
    }

    public static void playSoundToPlayer(PlayerEntity player, SoundEvent sound, SoundCategory category, float volume, float pitch) {
        if (player == null || sound == null || category == null) return;
        try {
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity spe) {
                spe.playSoundToPlayer(sound, category, volume, pitch);
            } else {
                player.playSound(sound, volume, pitch);
            }
        } catch (Throwable ignored) {}
    }

    public static void processDismountSafety(net.minecraft.server.MinecraftServer server) {
        if (!RIDING_PLAYERS.isEmpty()) {
            java.util.List<UUID> tracked = new java.util.ArrayList<>(RIDING_PLAYERS.keySet());
            for (UUID uuid : tracked) {
                RidingInfo rinfo = RIDING_PLAYERS.get(uuid);
                PlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                if (player == null) { RIDING_PLAYERS.remove(uuid); continue; }

                double curY = player.getY();
                if (Math.abs(curY - rinfo.lastY) > 6.0) {
                    SnifferEntity sniffer = findSnifferByUuid(server, rinfo.snifferUuid);
                    if (sniffer != null) {
                        double sx = sniffer.getX();
                        double sy = sniffer.getY();
                        double sz = sniffer.getZ();
                        double tx = sx;
                        double ty = sy + 1.0;
                        double tz = sz;
                        player.refreshPositionAndAngles(tx, ty, tz, player.getYaw(), player.getPitch());
                        if (player instanceof net.minecraft.server.network.ServerPlayerEntity spe) {
                            spe.networkHandler.requestTeleport(tx, ty, tz, player.getYaw(), player.getPitch());
                        }
                        resetFallDistance(player);
                        player.setVelocity(Vec3d.ZERO);
                        DISMOUNT_WATCH.put(uuid, new DismountInfo(new Vec3d(tx, ty, tz), new Vec3d(sx, sy, sz), 2));
                    }
                    RIDING_PLAYERS.remove(uuid);
                    continue;
                }

                rinfo.lastY = curY;

                Entity vehicle = player.getVehicle();
                boolean stillRiding = false;
                if (vehicle instanceof ArmorStandEntity seat) {
                    SnifferEntity s = getSnifferForSeat(seat);
                    if (s != null && s.getUuid().equals(rinfo.snifferUuid)) stillRiding = true;
                } else if (vehicle instanceof SnifferEntity s2) {
                    if (s2.getUuid().equals(rinfo.snifferUuid)) stillRiding = true;
                }

                if (!stillRiding) {
                    SnifferEntity sniffer = findSnifferByUuid(server, rinfo.snifferUuid);
                    if (sniffer != null) {
                        double sx = sniffer.getX();
                        double sy = sniffer.getY();
                        double sz = sniffer.getZ();
                        float yaw = sniffer.getYaw();

                        double sideYawRad = (yaw + 90.0) * (Math.PI / 180.0);
                        double ox = -Math.sin(sideYawRad) * 1.5;
                        double oz = Math.cos(sideYawRad) * 1.5;

                        double fx = sx + ox;
                        double fy = sy + 0.5;
                        double fz = sz + oz;

                        DISMOUNT_WATCH.put(uuid, new DismountInfo(new Vec3d(fx, fy, fz), new Vec3d(sx, sy, sz), 3));

                        if (rinfo.driver) {
                            if (getPassengerCount(sniffer) > 0) {
                                    schedulePromotion(sniffer, 4);
                            } else {
                                removeSeats(sniffer);
                                SnifferSpeedManager.restoreOriginalSpeed(sniffer);
                            }
                        }
                    }
                    RIDING_PLAYERS.remove(uuid);
                }
            }
        }

        java.util.List<UUID> watchKeys = new java.util.ArrayList<>(DISMOUNT_WATCH.keySet());
        for (UUID uuid : watchKeys) {
            PlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            DismountInfo info = DISMOUNT_WATCH.remove(uuid);
            if (player != null && info != null) {
                Vec3d target = info.target;
                Vec3d snifferPos = info.snifferPos;

                double dx = player.getX() - target.x;
                double dy = player.getY() - target.y;
                double dz = player.getZ() - target.z;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

                if (Math.abs(target.y - snifferPos.y) > 2.5 || target.y < 5.0 || Math.abs(player.getY() - target.y) > 3.0) {
                    double tx = snifferPos.x;
                    double ty = snifferPos.y + 1.0;
                    double tz = snifferPos.z;
                    player.refreshPositionAndAngles(tx, ty, tz, player.getYaw(), player.getPitch());
                    if (player instanceof net.minecraft.server.network.ServerPlayerEntity spe) {
                        spe.networkHandler.requestTeleport(tx, ty, tz, player.getYaw(), player.getPitch());
                    }
                    resetFallDistance(player);
                    player.setVelocity(Vec3d.ZERO);
                    if (info.attempts > 1) DISMOUNT_WATCH.put(uuid, new DismountInfo(target, snifferPos, info.attempts - 1));
                } else if (dist > 1.0) {
                    player.refreshPositionAndAngles(target.x, target.y + 0.2, target.z, player.getYaw(), player.getPitch());
                    if (player instanceof net.minecraft.server.network.ServerPlayerEntity spe) {
                        spe.networkHandler.requestTeleport(target.x, target.y + 0.2, target.z, player.getYaw(), player.getPitch());
                    }
                    resetFallDistance(player);
                    player.setVelocity(Vec3d.ZERO);
                    if (info.attempts > 1) DISMOUNT_WATCH.put(uuid, new DismountInfo(target, snifferPos, info.attempts - 1));
                }
            }
        }

        // Process scheduled promotions (decrement timers and run when ready) - reliable per-tick processing
        try {
            java.util.List<UUID> schedKeys = new java.util.ArrayList<>(PROMOTION_SCHEDULE.keySet());
            for (UUID su : schedKeys) {
                try {
                    Integer rem = PROMOTION_SCHEDULE.get(su);
                    if (rem == null) { PROMOTION_SCHEDULE.remove(su); continue; }
                    rem = rem - 1;
                    if (rem <= 0) {
                        PROMOTION_SCHEDULE.remove(su);
                        SnifferEntity s = findSnifferByUuid(server, su);
                        if (s != null) {
                            try { RideableSnifferMod.logDebug("[PROMOTE] Running scheduled promotion for sniffer {}", su); } catch (Throwable ignored) {}
                            promoteNextDriver(s);
                        }
                    } else {
                        PROMOTION_SCHEDULE.put(su, rem);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // Process scheduled seat shifts (decrement timers and run when ready)
        try {
            java.util.List<UUID> shiftKeys = new java.util.ArrayList<>(SHIFT_SCHEDULE.keySet());
            for (UUID su : shiftKeys) {
                try {
                    Integer rem = SHIFT_SCHEDULE.get(su);
                    if (rem == null) { SHIFT_SCHEDULE.remove(su); continue; }
                    rem = rem - 1;
                    if (rem <= 0) {
                        SHIFT_SCHEDULE.remove(su);
                        SnifferEntity s = findSnifferByUuid(server, su);
                        if (s != null) {
                            try { RideableSnifferMod.logDebug("[PROMOTE] Running scheduled seat-shift for sniffer {}", su); } catch (Throwable ignored) {}
                            try { shiftPassengersForward(s); } catch (Throwable ignored) {}
                        }
                    } else {
                        SHIFT_SCHEDULE.put(su, rem);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // Process pending promotion retries: try seating promoted players into their front seats.
        try {
            java.util.List<UUID> promoKeys = new java.util.ArrayList<>(PROMOTION_RETRIES.keySet());
            RideableSnifferMod.logDebug("[MOD DEBUG] Processing {} promotion retries", promoKeys.size());
            for (UUID pu : promoKeys) {
                try {
                    PromotionRetry pr = PROMOTION_RETRIES.get(pu);
                    if (pr == null) { PROMOTION_RETRIES.remove(pu); continue; }
                    PlayerEntity player = server.getPlayerManager().getPlayer(pu);
                    if (player == null) { PROMOTION_RETRIES.remove(pu); continue; }

                    Entity seatEntity = null;
                    for (var w : server.getWorlds()) {
                        for (Entity e : w.iterateEntities()) {
                            if (e != null && e.getUuid().equals(pr.seatUuid)) { seatEntity = e; break; }
                        }
                        if (seatEntity != null) break;
                    }
                    if (seatEntity instanceof ArmorStandEntity ase) {
                        RideableSnifferMod.logDebug("[MOD DEBUG] Retry: player {} trying to mount seat {} (attempts={})",
                            player.getName().getString(), ase.getUuid(), pr.attempts);
                        // Teleport player close to the seat before attempting to mount to improve reliability
                        try {
                            player.refreshPositionAndAngles(ase.getX(), ase.getY() + 0.1, ase.getZ(), player.getYaw(), player.getPitch());
                            if (player instanceof net.minecraft.server.network.ServerPlayerEntity spe) {
                                try { spe.networkHandler.requestTeleport(ase.getX(), ase.getY() + 0.1, ase.getZ(), player.getYaw(), player.getPitch()); } catch (Throwable ignored) {}
                            }
                            resetFallDistance(player);
                            player.setVelocity(Vec3d.ZERO);
                        } catch (Throwable ignored) {}

                        boolean seated = false;
                        try { seated = player.startRiding(ase); } catch (Throwable ignored) {}
                        if (!seated && player.getVehicle() != ase) {
                            // try reflective forced mount
                            try {
                                java.lang.reflect.Method m = player.getClass().getMethod("startRiding", Entity.class, boolean.class);
                                m.setAccessible(true);
                                try { m.invoke(player, ase, true); } catch (Throwable ignored) {}
                                seated = player.getVehicle() == ase;
                            } catch (Throwable ignored) {}
                        }
                        if (seated || player.getVehicle() == ase) {
                            try { ase.addCommandTag("SnifferOccupant:" + player.getUuid()); } catch (Throwable ignored) {}
                            SnifferEntity s = getSnifferForSeat(ase);
                            if (s != null) {
                                java.util.List<PlayerEntity> riders = getRiders(s);
                                for (PlayerEntity p : riders) RIDING_PLAYERS.put(p.getUuid(), new RidingInfo(s.getUuid(), p.getUuid().equals(player.getUuid()), p.getY()));
                            }
                            PROMOTION_RETRIES.remove(pu);
                            RideableSnifferMod.logDebug("[MOD DEBUG] Retry success: player {} seated on {}", player.getName().getString(), ase.getUuid());
                        } else {
                            pr.attempts--;
                            RideableSnifferMod.logDebug("[MOD DEBUG] Retry failed for {} (remaining attempts={})", player.getName().getString(), pr.attempts);
                            if (pr.attempts <= 0) PROMOTION_RETRIES.remove(pu);
                            else PROMOTION_RETRIES.put(pu, pr);
                        }
                    } else {
                        pr.attempts--;
                        if (pr.attempts <= 0) PROMOTION_RETRIES.remove(pu);
                        else PROMOTION_RETRIES.put(pu, pr);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        try {
            java.util.List<UUID> seatKeys = new java.util.ArrayList<>(SEAT_ENTITIES.keySet());
            for (UUID su : seatKeys) {
                try {
                    java.util.List<Entity> seats = SEAT_ENTITIES.get(su);
                    if (seats == null || seats.isEmpty()) { SEAT_ENTITIES.remove(su); continue; }
                    SnifferEntity s = findSnifferByUuid(server, su);
                    boolean shouldRemove = false;
                    if (s == null) {
                        shouldRemove = true;
                    } else {
                        try {
                            if (s.isRemoved() || !s.isAlive()) {
                                // If this sniffer had a saddle, drop it now and remove persisted state
                                try {
                                    boolean was = false;
                                    try { was = SADDLED_SNIFFERS.remove(s.getUuid()); } catch (Throwable ignored) {}
                                    if (was) {
                                        try { SnifferConfig.removePersistedSaddle(s.getUuid()); } catch (Throwable ignored) {}
                                        try { dropItemAtSniffer(s, new ItemStack(Items.SADDLE)); } catch (Throwable ignored) {}
                                    }
                                } catch (Throwable ignored) {}
                                shouldRemove = true;
                            } else {
                                int pc = getPassengerCount(s);
                                if (pc == 0) shouldRemove = true;
                            }
                        } catch (Throwable ignored) {}
                    }
                    if (shouldRemove) removeSeatsByUuid(su);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // Process scheduled saddle-validations: decrement timers and validate saddles (remove if sniffer is a baby)
        try {
            java.util.List<UUID> validateKeys = new java.util.ArrayList<>(SADDLE_VALIDATE_TICKS.keySet());
            for (UUID su : validateKeys) {
                try {
                    Integer rem = SADDLE_VALIDATE_TICKS.get(su);
                    if (rem == null) { SADDLE_VALIDATE_TICKS.remove(su); SADDLE_VALIDATE_PLAYER.remove(su); continue; }
                    rem = rem - 1;
                    if (rem <= 0) {
                        SADDLE_VALIDATE_TICKS.remove(su);
                        UUID pu = SADDLE_VALIDATE_PLAYER.remove(su);
                        SnifferEntity s = findSnifferByUuid(server, su);
                        if (s != null) {
                            try {
                                Integer age = getSnifferNbtAge(s);
                                boolean baby = false;
                                if (age != null) baby = age.intValue() < 0;
                                else baby = isBaby(s);
                                if (baby) {
                                    try { RideableSnifferMod.logDebug("[SADDLE VALIDATION] Removing saddle on sniffer {} discovered baby age={}", su, age == null ? "null" : age); } catch (Throwable ignored) {}
                                    try { setSaddle(s, false); } catch (Throwable ignored) {}
                                    try { dropItemAtSniffer(s, new ItemStack(Items.SADDLE)); } catch (Throwable ignored) {}
                                    if (pu != null) {
                                        try {
                                            PlayerEntity p = server.getPlayerManager().getPlayer(pu);
                                            if (p != null) {
                                                try { p.sendMessage(Text.literal("Saddle removed: the sniffer is a baby."), false); } catch (Throwable ignored) {}
                                                playSoundToPlayer(p, SoundEvents.ENTITY_SHEEP_SHEAR, SoundCategory.NEUTRAL, 1.0F, 1.0F);
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                } else {
                                    try { RideableSnifferMod.logDebug("[SADDLE VALIDATION] Sniffer {} validated as adult (age={})", su, age == null ? "null" : age); } catch (Throwable ignored) {}
                                }
                            } catch (Throwable ignored) {}
                        }
                    } else {
                        SADDLE_VALIDATE_TICKS.put(su, rem);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // Process scheduled saddle-removal sounds: decrement timers and play when ready
        try {
            java.util.List<UUID> soundKeys = new java.util.ArrayList<>(SADDLE_SOUND_TICKS.keySet());
            for (UUID su : soundKeys) {
                try {
                    Integer rem = SADDLE_SOUND_TICKS.get(su);
                    if (rem == null) { SADDLE_SOUND_TICKS.remove(su); SADDLE_SOUND_PLAYER.remove(su); continue; }
                    rem = rem - 1;
                    if (rem <= 0) {
                        SADDLE_SOUND_TICKS.remove(su);
                        UUID pu = SADDLE_SOUND_PLAYER.remove(su);
                        try { RideableSnifferMod.logDebug("[SOUND] firing scheduled saddle sound for sniffer={} player={}", su, pu == null ? "null" : pu); } catch (Throwable ignored) {}
                        PlayerEntity actor = null;
                        if (pu != null) {
                            try { actor = server.getPlayerManager().getPlayer(pu); } catch (Throwable ignored) {}
                        }
                        SnifferEntity s = findSnifferByUuid(server, su);
                        if (s != null) {
                            try {
                                World w = getReflectiveWorld(s);
                                try { RideableSnifferMod.logDebug("[SOUND] playing world sound at sniffer {} (world={})", su, w == null ? "null" : w.getClass().getName()); } catch (Throwable ignored) {}
                                if (w instanceof net.minecraft.server.world.ServerWorld sw) {
                                    try { sw.playSound(actor, s.getBlockPos(), SoundEvents.ENTITY_SHEEP_SHEAR, SoundCategory.NEUTRAL, 1.0F, 1.0F); } catch (Throwable ignored) {}
                                } else if (w != null) {
                                    try { w.playSound(actor, s.getBlockPos(), SoundEvents.ENTITY_SHEEP_SHEAR, SoundCategory.NEUTRAL, 1.0F, 1.0F); } catch (Throwable ignored) {}
                                }
                            } catch (Throwable ignored) {}
                        }
                        if (pu != null) {
                            try {
                                if (actor == null) {
                                            try { RideableSnifferMod.logDebug("[SOUND] actor player {} not found on server when firing sound", pu); } catch (Throwable ignored) {}
                                } else {
                                    try { RideableSnifferMod.logDebug("[SOUND] excluded actor {} from scheduled world sound; immediate targeted sound should already have played", actor.getName().getString()); } catch (Throwable ignored) {}
                                }
                            } catch (Throwable ignored) {}
                        }
                    } else {
                        SADDLE_SOUND_TICKS.put(su, rem);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Periodically check whether the front/driver seat is empty and promote
     * a waiting passenger into it. This is a safety fallback run every ~10
     * server ticks to handle missed dismount events.
     */
    public static void tickPromotionCheck(SnifferEntity sniffer) {
        if (sniffer == null) return;
        UUID su = sniffer.getUuid();
        try {
            int t = PROMOTION_CHECK_TICKS.getOrDefault(su, 0) + 1;
            if (t < 20) { PROMOTION_CHECK_TICKS.put(su, t); return; }
            PROMOTION_CHECK_TICKS.put(su, 0);

            int pc = getPassengerCount(sniffer);
            if (pc <= 0) { PROMOTION_CHECK_TICKS.remove(su); return; }

            java.util.List<Entity> seats = SEAT_ENTITIES.get(su);
            if (seats == null || seats.isEmpty()) seats = ensureSeats(sniffer);

            // Normalize seating: try to shift any riders forward into earlier empty seats
            try { shiftPassengersForward(sniffer); } catch (Throwable ignored) {}

            // Find front seat and check occupancy
            boolean frontOccupied = false;
            if (seats != null && !seats.isEmpty()) {
                for (Entity e : seats) {
                    try { if (getLogicalSeatIndex(sniffer, e) == 0) { if (!e.getPassengerList().isEmpty()) frontOccupied = true; break; } } catch (Throwable ignored) {}
                }
            }

            if (!frontOccupied) {
                java.util.List<PlayerEntity> riders = getRiders(sniffer);
                if (riders != null && !riders.isEmpty()) {
                    try { promoteNextDriver(sniffer); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    public static boolean isRidingSniffer(PlayerEntity player, SnifferEntity sniffer) {
        Entity v = player.getVehicle();
        if (v instanceof ArmorStandEntity) return sniffer.getUuid().equals(getSnifferUuidForSeat(v));
        return v == sniffer;
    }

    /**
     * Ensure the sniffer entity's DataTracker reflects saddle state. Uses reflection to find
     * the correct tracked field (handles different mappings across versions).
     */
    public static void applyEntitySaddleTracker(SnifferEntity sniffer, boolean saddled) {
        if (sniffer == null) return;
        try {
            Class<?> cls = SnifferEntity.class;
            java.lang.reflect.Field targetField = null;
            // Try common field name first
            try { targetField = cls.getDeclaredField("SADDLED"); targetField.setAccessible(true); } catch (Throwable ignored) {}
            // Fallback: find any static TrackedData field on the class
            if (targetField == null) {
                try {
                    for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                        try {
                            f.setAccessible(true);
                            Object val = f.get(null);
                            if (val != null && val.getClass().getName().contains("TrackedData")) { targetField = f; break; }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }

            if (targetField != null) {
                Object tracked = targetField.get(null);
                java.lang.reflect.Method getDT = sniffer.getClass().getMethod("getDataTracker");
                Object dt = getDT.invoke(sniffer);
                if (dt != null && tracked != null) {
                    try {
                        java.lang.reflect.Method set = dt.getClass().getMethod("set", Class.forName("net.minecraft.entity.data.TrackedData"), Object.class);
                        set.invoke(dt, tracked, saddled ? Boolean.TRUE : Boolean.FALSE);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Determine whether the given sniffer is a baby.
     * Many mappings use a negative 'age' or 'growingAge' int for babies; other mappings expose a boolean isBaby/isChild.
     */
    public static boolean isBaby(SnifferEntity sniffer) {
        if (sniffer == null) return false;
        try {
            // First try to get explicit NBT age value if available
            Integer nbtAge = getSnifferNbtAge(sniffer);
            if (nbtAge != null) return nbtAge.intValue() < 0;

            // Common boolean methods
            try { java.lang.reflect.Method m = sniffer.getClass().getMethod("isBaby"); m.setAccessible(true); Object r = m.invoke(sniffer); if (r instanceof Boolean) return (Boolean) r; } catch (Throwable ignored) {}
            try { java.lang.reflect.Method m = sniffer.getClass().getMethod("isChild"); m.setAccessible(true); Object r = m.invoke(sniffer); if (r instanceof Boolean) return (Boolean) r; } catch (Throwable ignored) {}

            // Common age getters that are negative for baby
            try { java.lang.reflect.Method m = sniffer.getClass().getMethod("getAge"); m.setAccessible(true); Object r = m.invoke(sniffer); if (r instanceof Number) return ((Number) r).intValue() < 0; } catch (Throwable ignored) {}
            try { java.lang.reflect.Method m = sniffer.getClass().getMethod("getGrowingAge"); m.setAccessible(true); Object r = m.invoke(sniffer); if (r instanceof Number) return ((Number) r).intValue() < 0; } catch (Throwable ignored) {}

            // Fallback: scan int fields for names that look like age/growth
            Class<?> clazz = sniffer.getClass();
            while (clazz != null && clazz != Object.class) {
                for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        if (f.getType() == int.class || f.getType() == Integer.TYPE) {
                            String fname = f.getName().toLowerCase();
                            if (fname.contains("age") || fname.contains("grow") || fname.contains("baby") || fname.contains("growing")) {
                                Object val = f.get(sniffer);
                                if (val instanceof Number) return ((Number) val).intValue() < 0;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Attempt to read the sniffer's age value from its NBT data.
     * Returns Integer age if found, or null if unavailable.
     */
    public static Integer getSnifferNbtAge(SnifferEntity sniffer) {
        if (sniffer == null) return null;
        try {
            // Try common getter methods first
            try {
                java.lang.reflect.Method m = sniffer.getClass().getMethod("getAge"); m.setAccessible(true);
                Object r = m.invoke(sniffer);
                if (r instanceof Number) {
                    Integer val = Integer.valueOf(((Number) r).intValue());
                    try { RideableSnifferMod.logDebug("[AGE] getSnifferNbtAge sniffer={} age={} via getAge", sniffer.getUuid(), val); } catch (Throwable ignored) {}
                    return val;
                }
            } catch (Throwable ignored) {}
            try {
                java.lang.reflect.Method m = sniffer.getClass().getMethod("getGrowingAge"); m.setAccessible(true);
                Object r = m.invoke(sniffer);
                if (r instanceof Number) {
                    Integer val = Integer.valueOf(((Number) r).intValue());
                    try { RideableSnifferMod.logDebug("[AGE] getSnifferNbtAge sniffer={} age={} via getGrowingAge", sniffer.getUuid(), val); } catch (Throwable ignored) {}
                    return val;
                }
            } catch (Throwable ignored) {}

            // Try reading tracked data static fields (TrackedData) from the entity's DataTracker
            try {
                java.lang.reflect.Method getDT = null;
                try { getDT = sniffer.getClass().getMethod("getDataTracker"); } catch (Throwable ignored) {}
                Object dt = null;
                if (getDT != null) {
                    try { dt = getDT.invoke(sniffer); } catch (Throwable ignored) {}
                } else {
                    // fallback: try to find a field named dataTracker
                    try {
                        java.lang.reflect.Field dtField = null;
                        Class<?> cc = sniffer.getClass();
                        while (cc != null && cc != Object.class) {
                            try { dtField = cc.getDeclaredField("dataTracker"); dtField.setAccessible(true); break; } catch (Throwable ignored) {}
                            cc = cc.getSuperclass();
                        }
                        if (dtField != null) dt = dtField.get(sniffer);
                    } catch (Throwable ignored) {}
                }

                if (dt != null) {
                    java.lang.reflect.Method dtGet = null;
                    for (java.lang.reflect.Method m : dt.getClass().getMethods()) {
                        try { if (m.getName().equals("get") && m.getParameterCount() == 1) { dtGet = m; break; } } catch (Throwable ignored) {}
                    }
                    if (dtGet != null) {
                        Class<?> cc = sniffer.getClass();
                        while (cc != null && cc != Object.class) {
                            for (java.lang.reflect.Field f : cc.getDeclaredFields()) {
                                try {
                                    int mods = f.getModifiers();
                                    if (!java.lang.reflect.Modifier.isStatic(mods)) continue;
                                    f.setAccessible(true);
                                    Object tracked = f.get(null);
                                    if (tracked == null) continue;
                                    Object val = null;
                                    try { val = dtGet.invoke(dt, tracked); } catch (Throwable ignored) {}
                                    if (val instanceof Number) {
                                        int vi = ((Number) val).intValue();
                                        String fname = f.getName().toLowerCase();
                                        if (fname.contains("age") || fname.contains("grow") || fname.contains("growing") || fname.contains("baby")) {
                                            try { RideableSnifferMod.logDebug("[AGE] found tracked-age via field {}={} => {}", f.getName(), tracked.getClass().getName(), vi); } catch (Throwable ignored) {}
                                            return Integer.valueOf(vi);
                                        }
                                        if (vi < 0) {
                                            try { RideableSnifferMod.logDebug("[AGE] found negative tracked value via field {}={} => {}", f.getName(), tracked.getClass().getName(), vi); } catch (Throwable ignored) {}
                                            return Integer.valueOf(vi);
                                        }
                                    } else if (val instanceof Boolean) {
                                        // Some mappings expose a tracked boolean child flag (CHILD). Use it when present.
                                        boolean bv = (Boolean) val;
                                        String fname = f.getName().toLowerCase();
                                        if (fname.contains("child") || fname.contains("baby") || fname.contains("is_child") || fname.contains("childflag")) {
                                            try { RideableSnifferMod.logDebug("[AGE] found tracked-child boolean via field {}={} => {}", f.getName(), tracked.getClass().getName(), bv); } catch (Throwable ignored) {}
                                            return Integer.valueOf(bv ? -1 : 0);
                                        }
                                    }
                                } catch (Throwable ignored) {}
                            }
                            cc = cc.getSuperclass();
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // Try methods that return an NBT compound
            String[] returnNbtNames = new String[]{"writeNbt","toTag","save","saveWithoutId","serializeNbt","getNbt","toNbt","saveNbt","asCompound"};
            for (String name : returnNbtNames) {
                for (java.lang.reflect.Method m : sniffer.getClass().getMethods()) {
                    try {
                        if (!m.getName().equals(name)) continue;
                        if (m.getParameterCount() != 0) continue;
                        Object nbt = m.invoke(sniffer);
                        if (nbt != null) {
                            Integer v = extractAgeFromNbtObject(nbt);
                            if (v != null) {
                                try { RideableSnifferMod.logDebug("[AGE] getSnifferNbtAge sniffer={} age={} via NBT method {}", sniffer.getUuid(), v, name); } catch (Throwable ignored) {}
                                return v;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }

            // Try to locate common NBT compound classes across mappings
            Class<?> nbtCls = null;
            String[] nbtClassNames = new String[]{"net.minecraft.nbt.NbtCompound", "net.minecraft.nbt.CompoundTag", "net.minecraft.nbt.CompoundNBT", "net.minecraft.nbt.NBTTagCompound", "net.minecraft.nbt.Tag"};
            for (String cn : nbtClassNames) {
                try { nbtCls = Class.forName(cn); if (nbtCls != null) break; } catch (Throwable ignored) {}
            }
            Object nbtInstance = null;
            if (nbtCls != null) {
                try { java.lang.reflect.Constructor<?> c = nbtCls.getDeclaredConstructor(); c.setAccessible(true); nbtInstance = c.newInstance(); } catch (Throwable ignored) {}
            }

            String[] writeNbtNames = new String[]{"writeNbt","writeCustomDataToNbt","save","saveWithoutId","saveNbt","writeToNbt"};
            for (String name : writeNbtNames) {
                for (java.lang.reflect.Method m : sniffer.getClass().getMethods()) {
                    try {
                        if (!m.getName().equals(name)) continue;
                        if (m.getParameterCount() != 1) continue;
                        Class<?> ptype = m.getParameterTypes()[0];
                        if (nbtCls != null && ptype.isAssignableFrom(nbtCls) && nbtInstance != null) {
                            m.invoke(sniffer, nbtInstance);
                            Integer v = extractAgeFromNbtObject(nbtInstance);
                            if (v != null) {
                                try { RideableSnifferMod.logDebug("[AGE] getSnifferNbtAge sniffer={} age={} via writeNbt method {}", sniffer.getUuid(), v, name); } catch (Throwable ignored) {}
                                return v;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }

            // Fallback: attempt to find any zero-arg methods that return an NBT-like object and extract age
            try {
                for (java.lang.reflect.Method m : sniffer.getClass().getMethods()) {
                    try {
                        if (m.getParameterCount() != 0) continue;
                        Class<?> rt = m.getReturnType();
                        if (rt == null) continue;
                        String rn = rt.getName().toLowerCase();
                        if (rn.contains("nbt") || rn.contains("compound") || rn.contains("tag")) {
                            try {
                                Object obj = m.invoke(sniffer);
                                if (obj != null) {
                                    Integer v = extractAgeFromNbtObject(obj);
                                    if (v != null) {
                                        try { RideableSnifferMod.logDebug("[AGE] getSnifferNbtAge sniffer={} age={} via fallback method {} (ret={})", sniffer.getUuid(), v, m.getName(), rt.getName()); } catch (Throwable ignored) {}
                                        return v;
                                    }
                                }
                            } catch (Throwable ex) {
                                try { RideableSnifferMod.logDebug("[AGE] fallback read method {} threw {}", m.getName(), ex == null ? "null" : ex.getClass().getName() + ": " + ex.getMessage()); } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}

            // Fallback: attempt writer methods that accept an NBT-like parameter by constructing a new NBT instance reflectively
            try {
                for (java.lang.reflect.Method m : sniffer.getClass().getMethods()) {
                    try {
                        if (m.getParameterCount() != 1) continue;
                        Class<?> p0 = m.getParameterTypes()[0];
                        String pn = p0.getName().toLowerCase();
                        if (pn.contains("nbt") || pn.contains("compound") || pn.contains("tag")) {
                            Object inst = null;
                            try {
                                java.lang.reflect.Constructor<?> c = null;
                                try { c = p0.getDeclaredConstructor(); } catch (Throwable ignoreCtor) {}
                                if (c == null) {
                                    try { c = p0.getConstructor(); } catch (Throwable ignoreCtor2) {}
                                }
                                if (c != null) { c.setAccessible(true); inst = c.newInstance(); }
                            } catch (Throwable ignored) {}
                            if (inst != null) {
                                try {
                                    m.invoke(sniffer, inst);
                                    Integer v = extractAgeFromNbtObject(inst);
                                    if (v != null) {
                                        try { RideableSnifferMod.logDebug("[AGE] getSnifferNbtAge sniffer={} age={} via fallback writer {} (param={})", sniffer.getUuid(), v, m.getName(), p0.getName()); } catch (Throwable ignored) {}
                                        return v;
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}

            // If we reach here, we couldn't find age via the usual methods; dump candidate methods/fields for debugging
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("methods:");
                for (java.lang.reflect.Method m : sniffer.getClass().getMethods()) {
                    try {
                        String n = m.getName().toLowerCase();
                        if (n.contains("age") || n.contains("nbt") || n.contains("compound") || n.contains("tag") || n.contains("save") || n.contains("to") || n.contains("serialize")) {
                            sb.append(m.getName()).append("->").append(m.getReturnType().getName()).append(",");
                        }
                    } catch (Throwable ignored) {}
                }
                sb.append(" fields:");
                Class<?> cc = sniffer.getClass();
                while (cc != null && cc != Object.class) {
                    for (java.lang.reflect.Field f : cc.getDeclaredFields()) {
                        try {
                            String fn = f.getName().toLowerCase();
                            if (fn.contains("age") || fn.contains("grow") || fn.contains("baby") || fn.contains("growing")) {
                                f.setAccessible(true);
                                Object v = null;
                                try { v = f.get(sniffer); } catch (Throwable ignored) {}
                                sb.append(f.getName()).append(":").append(f.getType().getName()).append("=").append(v == null ? "null" : v.toString()).append(",");
                            }
                        } catch (Throwable ignored) {}
                    }
                    cc = cc.getSuperclass();
                }
                try { RideableSnifferMod.logDebug("[AGE] fallback candidates for sniffer {} -> {}", sniffer.getUuid(), sb.toString()); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}

            // Final fallback: scan numeric instance fields for negative values, but only accept
            // fields that are likely to represent age (name contains age/grow/baby) or have a
            // sufficiently large negative magnitude. This avoids false positives when unrelated
            // obfuscated fields happen to be negative (see reported case field_6191 = -119).
            try {
                final int NEGATIVE_AGE_MIN_ABS = 1000; // require magnitude >= this for unnamed fields
                Class<?> cc = sniffer.getClass();
                for (; cc != null && cc != Object.class; cc = cc.getSuperclass()) {
                    for (java.lang.reflect.Field f : cc.getDeclaredFields()) {
                        try {
                            // Skip static fields (class defaults/constants) â€” they are shared across instances
                            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                            f.setAccessible(true);
                            Class<?> t = f.getType();
                            Object val = null;
                            try { val = f.get(sniffer); } catch (Throwable ignored) {}
                            if (val == null) continue;
                            String fname = f.getName().toLowerCase();
                            boolean nameLooksLikeAge = fname.contains("age") || fname.contains("grow") || fname.contains("growing") || fname.contains("baby");

                            if (t == int.class || t == Integer.class) {
                                int vi = ((Number) val).intValue();
                                if (vi < 0) {
                                    if (nameLooksLikeAge || Math.abs(vi) >= NEGATIVE_AGE_MIN_ABS) {
                                        try { RideableSnifferMod.logDebug("[AGE] found negative numeric instance field {} (declaring={}) = {} -> using as Age", f.getName(), f.getDeclaringClass().getName(), vi); } catch (Throwable ignored) {}
                                        return Integer.valueOf(vi);
                                    } else {
                                        try { RideableSnifferMod.logDebug("[AGE] ignoring small/unlabeled negative field {}={} (declaring={})" , f.getName(), vi, f.getDeclaringClass().getName()); } catch (Throwable ignored) {}
                                    }
                                }
                            } else if (t == long.class || t == Long.class) {
                                long vl = ((Number) val).longValue();
                                if (vl < 0 && vl >= Integer.MIN_VALUE && vl <= Integer.MAX_VALUE) {
                                    int vi = (int) vl;
                                    if (nameLooksLikeAge || Math.abs(vi) >= NEGATIVE_AGE_MIN_ABS) {
                                        try { RideableSnifferMod.logDebug("[AGE] found negative numeric instance field {} (declaring={}) = {} -> using as Age", f.getName(), f.getDeclaringClass().getName(), vl); } catch (Throwable ignored) {}
                                        return Integer.valueOf(vi);
                                    } else {
                                        try { RideableSnifferMod.logDebug("[AGE] ignoring small/unlabeled negative field {}={} (declaring={})" , f.getName(), vi, f.getDeclaringClass().getName()); } catch (Throwable ignored) {}
                                    }
                                }
                            } else if (t == short.class || t == Short.class) {
                                short vs = ((Number) val).shortValue();
                                if (vs < 0) {
                                    int vi = (int) vs;
                                    if (nameLooksLikeAge || Math.abs(vi) >= NEGATIVE_AGE_MIN_ABS) {
                                        try { RideableSnifferMod.logDebug("[AGE] found negative numeric instance field {} (declaring={}) = {} -> using as Age", f.getName(), f.getDeclaringClass().getName(), vs); } catch (Throwable ignored) {}
                                        return Integer.valueOf(vi);
                                    } else {
                                        try { RideableSnifferMod.logDebug("[AGE] ignoring small/unlabeled negative field {}={} (declaring={})" , f.getName(), vi, f.getDeclaringClass().getName()); } catch (Throwable ignored) {}
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return null;
    }

    private static Integer extractAgeFromNbtObject(Object nbt) {
        if (nbt == null) return null;
        try {
            Class<?> cls = nbt.getClass();
            // try getInt
            try {
                java.lang.reflect.Method getInt = cls.getMethod("getInt", String.class);
                for (String key : new String[]{"Age","age","GrowingAge","growingAge","AgeTag"}) {
                    try {
                        Object val = getInt.invoke(nbt, key);
                        if (val instanceof Number) {
                            Integer found = Integer.valueOf(((Number) val).intValue());
                            try { RideableSnifferMod.logDebug("[AGE] extractAgeFromNbtObject found age={} (key={}, nbtClass={})", found, key, cls.getName()); } catch (Throwable ignored) {}
                            return found;
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            // try getShort/getLong
            try {
                java.lang.reflect.Method getShort = cls.getMethod("getShort", String.class);
                for (String key : new String[]{"Age","age","GrowingAge","growingAge"}) {
                    try {
                        Object val = getShort.invoke(nbt, key);
                        if (val instanceof Number) {
                            Integer found = Integer.valueOf(((Number) val).intValue());
                            try { RideableSnifferMod.logDebug("[AGE] extractAgeFromNbtObject found age={} (key={}, nbtClass={})", found, key, cls.getName()); } catch (Throwable ignored) {}
                            return found;
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            try {
                java.lang.reflect.Method getLong = cls.getMethod("getLong", String.class);
                for (String key : new String[]{"Age","age","GrowingAge","growingAge"}) {
                    try {
                        Object val = getLong.invoke(nbt, key);
                        if (val instanceof Number) {
                            Integer found = Integer.valueOf(((Number) val).intValue());
                            try { RideableSnifferMod.logDebug("[AGE] extractAgeFromNbtObject found age={} (key={}, nbtClass={})", found, key, cls.getName()); } catch (Throwable ignored) {}
                            return found;
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            // some NBT implementations have a 'contains' and 'get' pattern
            try {
                java.lang.reflect.Method contains = cls.getMethod("contains", String.class);
                java.lang.reflect.Method get = cls.getMethod("get", String.class);
                for (String key : new String[]{"Age","age","GrowingAge","growingAge"}) {
                    try {
                        Object has = contains.invoke(nbt, key);
                        if (has instanceof Boolean && ((Boolean) has)) {
                            Object v = get.invoke(nbt, key);
                            if (v instanceof Number) return Integer.valueOf(((Number) v).intValue());
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return null;
    }

    public static void promoteNextDriver(SnifferEntity sniffer) {
        if (sniffer == null) return;
        int pc = getPassengerCount(sniffer);
        RideableSnifferMod.logInfo("[PROMOTE] promoteNextDriver called for sniffer {} with {} passenger(s)", sniffer.getUuid(), pc);
        if (pc <= 0) { SnifferSpeedManager.restoreOriginalSpeed(sniffer); return; }

        java.util.List<Entity> seats = SEAT_ENTITIES.get(sniffer.getUuid());
        if (seats == null || seats.isEmpty()) seats = ensureSeats(sniffer);

        Entity frontSeat = null;
        if (seats != null) {
            for (Entity e : seats) {
                try { if (getLogicalSeatIndex(sniffer, e) == 0) { frontSeat = e; break; } } catch (Throwable ignored) {}
            }
        }

        java.util.List<PlayerEntity> riders = getRiders(sniffer);
        if (riders == null || riders.isEmpty()) {
            SnifferSpeedManager.applyDriverSpeed(sniffer);
            return;
        }

        PlayerEntity promote = null;
        if (seats != null && !seats.isEmpty()) {
            int bestIdx = Integer.MAX_VALUE;
            for (PlayerEntity p : riders) {
                try {
                    int idx = getLogicalSeatIndex(sniffer, p);
                    if (idx < bestIdx) { bestIdx = idx; promote = p; }
                } catch (Throwable ignored) {}
            }
        }

        if (promote == null) promote = riders.get(0);

        try { RideableSnifferMod.logInfo("[PROMOTE] Selected promotion candidate: {} ({})", promote.getName().getString(), promote.getUuid()); } catch (Throwable ignored) {}

        Entity currentSeat = (promote.getVehicle() != null && !(promote.getVehicle() instanceof SnifferEntity)) ? promote.getVehicle() : null;

        if (frontSeat != null) {
            try {
                if (currentSeat == null || currentSeat != frontSeat) {
                    try { if (currentSeat instanceof ArmorStandEntity ase) { try { ase.getCommandTags().remove("SnifferOccupant:" + promote.getUuid()); } catch (Throwable ignored) {} } } catch (Throwable ignored) {}

                    try { promote.stopRiding(); } catch (Throwable ignored) {}

                    double sx = getReflectiveDouble(sniffer, "getX", "method_23317", "field_6038", "x");
                    double sy = getReflectiveDouble(sniffer, "getY", "method_23318", "field_6039", "y");
                    double sz = getReflectiveDouble(sniffer, "getZ", "method_23321", "field_6036", "z");
                    float yaw = getReflectiveFloat(sniffer, "getYaw", "method_5705", "field_6031", "yaw");
                    float yawRad = yaw * ((float) Math.PI / 180.0F);
                    double sin = Math.sin(yawRad);
                    double cos = Math.cos(yawRad);
                    double baseYOffset = getReflectiveFloat(sniffer, "getHeight", "method_17682", "field_6008", "height") * 0.04;

                    double fx = sx + (-sin * 1.10);
                    double fy = sy + baseYOffset + 0.06;
                    double fz = sz + (cos * 1.10);

                    try {
                        frontSeat.setPos(fx, fy, fz);
                        frontSeat.setYaw(yaw);
                        if (frontSeat instanceof LivingEntity le) { le.setBodyYaw(yaw); le.setHeadYaw(yaw); }
                    } catch (Throwable ignored) {}

                    try {
                        promote.refreshPositionAndAngles(fx, fy + 0.1, fz, promote.getYaw(), promote.getPitch());
                        if (promote instanceof net.minecraft.server.network.ServerPlayerEntity spe) {
                            try { spe.networkHandler.requestTeleport(fx, fy + 0.1, fz, promote.getYaw(), promote.getPitch()); } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                    resetFallDistance(promote);
                    promote.setVelocity(Vec3d.ZERO);

                    boolean seated = false;
                    try { seated = promote.startRiding(frontSeat); } catch (Throwable ignored) {}
                    if (!seated && promote.getVehicle() != frontSeat) {
                        // Try reflective forced mount
                        try {
                            java.lang.reflect.Method m = promote.getClass().getMethod("startRiding", Entity.class, boolean.class);
                            m.setAccessible(true);
                            try { m.invoke(promote, frontSeat, true); } catch (Throwable ignored) {}
                            seated = promote.getVehicle() == frontSeat;
                        } catch (Throwable ignored) {}
                    }

                    if (!seated && promote.getVehicle() != frontSeat) {
                        try { promote.startRiding(sniffer); } catch (Throwable ignored) {}
                        DISMOUNT_WATCH.put(promote.getUuid(), new DismountInfo(new Vec3d(fx, fy + 0.1, fz), new Vec3d(sx, sy, sz), 3));
                        try { PROMOTION_RETRIES.put(promote.getUuid(), new PromotionRetry(frontSeat.getUuid(), 20)); } catch (Throwable ignored) {}
                        try { RideableSnifferMod.logInfo("[PROMOTE] Could not seat {} on frontSeat {}, mounted sniffer and queued retry", promote.getName().getString(), frontSeat.getUuid()); } catch (Throwable ignored) {}
                    } else {
                        try { frontSeat.addCommandTag("SnifferOccupant:" + promote.getUuid()); } catch (Throwable ignored) {}
                        try { PROMOTION_RETRIES.remove(promote.getUuid()); } catch (Throwable ignored) {}
                        try { RideableSnifferMod.logInfo("[PROMOTE] Promoted {} to frontSeat {}", promote.getName().getString(), frontSeat.getUuid()); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        } else {
            try {
                if (promote.getVehicle() != sniffer) {
                    try { promote.stopRiding(); } catch (Throwable ignored) {}
                    try { promote.startRiding(sniffer); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }

        java.util.List<PlayerEntity> allRiders = getRiders(sniffer);
        for (PlayerEntity p : allRiders) {
            boolean isDriver = p.getUuid().equals(promote.getUuid());
            RIDING_PLAYERS.put(p.getUuid(), new RidingInfo(sniffer.getUuid(), isDriver, p.getY()));
        }

        SnifferSpeedManager.applyDriverSpeed(sniffer);
    }

    public static java.util.List<PlayerEntity> getRiders(SnifferEntity sniffer) {
        java.util.List<PlayerEntity> out = new java.util.ArrayList<>();
        java.util.List<Entity> entities = SEAT_ENTITIES.get(sniffer.getUuid());
        if (entities != null && !entities.isEmpty()) {
            for (Entity e : entities) for (Entity passenger : e.getPassengerList()) if (passenger instanceof PlayerEntity p) out.add(p);
            return out;
        }
        for (Entity e : sniffer.getPassengerList()) if (e instanceof PlayerEntity p) out.add(p);
        return out;
    }

    /**
     * Shift passengers forward to fill any empty earlier seats (normalize seating order).
     */
    public static void shiftPassengersForward(SnifferEntity sniffer) {
        if (sniffer == null) return;
        java.util.List<Entity> seats = SEAT_ENTITIES.get(sniffer.getUuid());
        if (seats == null || seats.isEmpty()) seats = ensureSeats(sniffer);

        if (seats == null || seats.isEmpty()) return;

        // Map seats by their logical index to ensure consistent order (0..MAX_PASSENGERS-1)
        Entity[] idxSeats = new Entity[MAX_PASSENGERS];
        for (Entity s : seats) {
            try {
                int idx = getLogicalSeatIndex(sniffer, s);
                if (idx >= 0 && idx < MAX_PASSENGERS) idxSeats[idx] = s;
            } catch (Throwable ignored) {}
        }

        double sx = getReflectiveDouble(sniffer, "getX", "method_23317", "field_6038", "x");
        double sy = getReflectiveDouble(sniffer, "getY", "method_23318", "field_6039", "y");
        double sz = getReflectiveDouble(sniffer, "getZ", "method_23321", "field_6036", "z");
        float yaw = getReflectiveFloat(sniffer, "getYaw", "method_5705", "field_6031", "yaw");
        float yawRad = yaw * ((float) Math.PI / 180.0F);
        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);
        double baseYOffset = getReflectiveFloat(sniffer, "getHeight", "method_17682", "field_6008", "height") * 0.04;
        double[] fOffs = {1.10, 0.00, -1.10}, yOffs = {0.06, 0.00, -0.03};

        for (int i = 0; i < MAX_PASSENGERS; i++) {
            Entity targetSeat = idxSeats[i];
            if (targetSeat == null) continue;
            // If occupied, skip
            if (!targetSeat.getPassengerList().isEmpty()) continue;

            // Find next occupied seat with a player
            PlayerEntity candidate = null;
            int srcIdx = -1;
            for (int j = i + 1; j < MAX_PASSENGERS; j++) {
                Entity s = idxSeats[j];
                if (s == null) continue;
                java.util.List<Entity> pls = s.getPassengerList();
                if (!pls.isEmpty()) {
                    Entity p = pls.get(0);
                    if (p instanceof PlayerEntity pe) { candidate = pe; srcIdx = j; break; }
                }
            }
            if (candidate == null) continue;

            try {
                // remove occupant tag from source seat
                try {
                    Entity srcSeat = idxSeats[srcIdx];
                    if (srcSeat != null) srcSeat.getCommandTags().remove("SnifferOccupant:" + candidate.getUuid());
                } catch (Throwable ignored) {}

                try { candidate.stopRiding(); } catch (Throwable ignored) {}

                int logicalIdx = i;
                double fx = sx + (-sin * fOffs[logicalIdx]);
                double fy = sy + baseYOffset + yOffs[logicalIdx];
                double fz = sz + (cos * fOffs[logicalIdx]);

                try {
                    targetSeat.setPos(fx, fy, fz);
                    targetSeat.setYaw(yaw);
                    if (targetSeat instanceof LivingEntity le) { le.setBodyYaw(yaw); le.setHeadYaw(yaw); }
                } catch (Throwable ignored) {}

                try {
                    candidate.refreshPositionAndAngles(fx, fy + 0.1, fz, candidate.getYaw(), candidate.getPitch());
                    if (candidate instanceof net.minecraft.server.network.ServerPlayerEntity spe) {
                        try { spe.networkHandler.requestTeleport(fx, fy + 0.1, fz, candidate.getYaw(), candidate.getPitch()); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}

                resetFallDistance(candidate);
                candidate.setVelocity(Vec3d.ZERO);

                boolean seated = false;
                try { seated = candidate.startRiding(targetSeat); } catch (Throwable ignored) {}
                if (!seated && candidate.getVehicle() != targetSeat) {
                    try {
                        java.lang.reflect.Method m = candidate.getClass().getMethod("startRiding", Entity.class, boolean.class);
                        m.setAccessible(true);
                        try { m.invoke(candidate, targetSeat, true); } catch (Throwable ignored) {}
                        seated = candidate.getVehicle() == targetSeat;
                    } catch (Throwable ignored) {}
                }

                if (!seated && candidate.getVehicle() != targetSeat) {
                    try { candidate.startRiding(sniffer); } catch (Throwable ignored) {}
                    DISMOUNT_WATCH.put(candidate.getUuid(), new DismountInfo(new Vec3d(fx, fy + 0.1, fz), new Vec3d(sx, sy, sz), 3));
                    try { PROMOTION_RETRIES.put(candidate.getUuid(), new PromotionRetry(targetSeat.getUuid(), 20)); } catch (Throwable ignored) {}
                } else {
                    try { targetSeat.addCommandTag("SnifferOccupant:" + candidate.getUuid()); } catch (Throwable ignored) {}
                    try { PROMOTION_RETRIES.remove(candidate.getUuid()); } catch (Throwable ignored) {}
                }

            } catch (Throwable ignored) {}
        }

        // refresh RIDING_PLAYERS info and speed
        java.util.List<PlayerEntity> all = getRiders(sniffer);
        for (PlayerEntity p : all) {
            boolean isDriver = false;
            try { isDriver = (getLogicalSeatIndex(sniffer, p) == 0); } catch (Throwable ignored) {}
            RIDING_PLAYERS.put(p.getUuid(), new RidingInfo(sniffer.getUuid(), isDriver, p.getY()));
        }
        if (getPassengerCount(sniffer) <= 0) SnifferSpeedManager.restoreOriginalSpeed(sniffer); else SnifferSpeedManager.applyDriverSpeed(sniffer);
    }

    public static int getPassengerCount(SnifferEntity sniffer) {
        java.util.List<Entity> entities = SEAT_ENTITIES.get(sniffer.getUuid());
        if (entities == null) {
            int count = 0; for (Entity e : sniffer.getPassengerList()) if (e instanceof PlayerEntity) count++; return count;
        }
        int cnt = 0; for (Entity e : entities) for (Entity p : e.getPassengerList()) if (p instanceof PlayerEntity) cnt++; return cnt;
    }

    public static boolean canAcceptPassenger(SnifferEntity sniffer) { return getPassengerCount(sniffer) < MAX_PASSENGERS; }
    public static int getMaxPassengers() { return MAX_PASSENGERS; }

    public static PlayerEntity getDriver(SnifferEntity sniffer) {
        java.util.List<Entity> entities = SEAT_ENTITIES.get(sniffer.getUuid());
        if (entities != null && !entities.isEmpty()) {
            // Prefer the designated front seat (SnifferSeat:0) as the driver
            for (Entity e : entities) {
                try { if (getLogicalSeatIndex(sniffer, e) == 0) {
                    java.util.List<Entity> passengers = e.getPassengerList();
                    if (!passengers.isEmpty()) { Entity p = passengers.get(0); return p instanceof PlayerEntity ? (PlayerEntity) p : null; }
                } } catch (Throwable ignored) {}
            }
            // Fallback: return the first passenger found in any seat
            for (Entity e : entities) {
                java.util.List<Entity> passengers = e.getPassengerList();
                if (!passengers.isEmpty()) { Entity p = passengers.get(0); return p instanceof PlayerEntity ? (PlayerEntity) p : null; }
            }
            return null;
        }
        java.util.List<Entity> passengers = sniffer.getPassengerList(); if (passengers.isEmpty()) return null; Entity driver = passengers.get(0); return driver instanceof PlayerEntity ? (PlayerEntity) driver : null;
    }

    public static boolean isDriver(PlayerEntity player, SnifferEntity sniffer) { PlayerEntity driver = getDriver(sniffer); return driver != null && driver == player; }

    public static int getLogicalSeatIndex(SnifferEntity sniffer, Entity passenger) {
        Entity target = (passenger.getVehicle() != null && !(passenger.getVehicle() instanceof SnifferEntity)) ? passenger.getVehicle() : passenger;
        for (String tag : target.getCommandTags()) {
            if (tag.startsWith("SnifferSeat:")) {
                try { return Integer.parseInt(tag.substring(12)); } catch (NumberFormatException ignored) {}
            }
        }
        return 0;
    }

    public static void updateSeatPositions(SnifferEntity sniffer) {
        if (sniffer.isRemoved() || !sniffer.isAlive()) { removeSeats(sniffer); return; }
        java.util.List<Entity> entities = SEAT_ENTITIES.get(sniffer.getUuid()); if (entities == null) return;
        double sx = getReflectiveDouble(sniffer, "getX", "method_23317", "field_6038", "x");
        double sy = getReflectiveDouble(sniffer, "getY", "method_23318", "field_6039", "y");
        double sz = getReflectiveDouble(sniffer, "getZ", "method_23321", "field_6036", "z");
        float yaw = getReflectiveFloat(sniffer, "getYaw", "method_5705", "field_6031", "yaw");
        float yawRad = yaw * ((float) Math.PI / 180.0F);
        double sin = Math.sin(yawRad), cos = Math.cos(yawRad);
        double baseY = getReflectiveFloat(sniffer, "getHeight", "method_17682", "field_6008", "height") * 0.04;
        double[] fOffs = {1.10, 0.00, -1.10}, yOffs = {0.06, 0.00, -0.03};
        // Read the sniffer vitals once per tick so every seat mirrors the same HUD state.
        Float sh = null;
        float snifferMaxHealth = 0.0f;
        try { sh = sniffer.getHealth(); } catch (Throwable ignored) {}
        try { snifferMaxHealth = sniffer.getMaxHealth(); } catch (Throwable ignored) {}

        for (Entity e : entities) {
            int idx = getLogicalSeatIndex(sniffer, e);
            double x = sx + (-sin * fOffs[idx]);
            double z = sz + (cos * fOffs[idx]);
            e.setPos(x, sy + baseY + yOffs[idx], z);
            e.setYaw(yaw);
            if (e instanceof LivingEntity le) { le.setBodyYaw(yaw); le.setHeadYaw(yaw); }
            try { syncSeatHealthFromSniffer(e, snifferMaxHealth, sh); } catch (Throwable ignored) {}
        }

        // Do not mirror sniffer health to players (riders should not take damage).
    }

    public static SnifferEntity getSnifferForSeat(Entity seat) {
        if (seat == null) return null;
        UUID id = getSnifferUuidForSeat(seat); if (id == null) return null;
        World w = getReflectiveWorld(seat); if (w == null) return null;
        Object entity = invokeReflective(w, new Object[]{id}, "getEntity", "method_14192"); if (entity instanceof SnifferEntity s) return s;
        try { for (Entity e : ((net.minecraft.server.world.ServerWorld) w).iterateEntities()) { if (e instanceof SnifferEntity s2 && s2.getUuid().equals(id)) return s2; } } catch (Throwable ignored) {}
        return null;
    }

    public static UUID getSnifferUuidForSeat(Entity seat) {
        for (String tag : seat.getCommandTags()) { if (tag.startsWith("SnifferParent:")) { try { return UUID.fromString(tag.substring(14)); } catch (Exception ignored) {} } }
        return null;
    }

    public static java.util.List<Entity> ensureSeats(SnifferEntity sniffer) {
        java.util.List<Entity> seats = SEAT_ENTITIES.get(sniffer.getUuid()); if (seats == null) { seats = createSeats(sniffer); SEAT_ENTITIES.put(sniffer.getUuid(), seats); } return seats;
    }

    private static java.util.List<Entity> createSeats(SnifferEntity sniffer) {
        java.util.List<Entity> entities = new java.util.ArrayList<>();
        World world = getReflectiveWorld(sniffer); if (world == null) return entities;
        double snifferX = getReflectiveDouble(sniffer, "getX", "method_23317", "field_6038", "x");
        double snifferY = getReflectiveDouble(sniffer, "getY", "method_23318", "field_6039", "y");
        double snifferZ = getReflectiveDouble(sniffer, "getZ", "method_23321", "field_6036", "z");
        float yaw = getReflectiveFloat(sniffer, "getYaw", "method_5705", "field_6031", "yaw");
        float yawRad = yaw * ((float) Math.PI / 180.0F);
        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);
        double baseYOffset = getReflectiveFloat(sniffer, "getHeight", "method_17682", "field_6008", "height") * 0.04;
        double[] extraY = new double[] {0.06, 0.00, -0.03};
        double[] forwardOffsets = new double[] {1.10, 0.00, -1.10};

        // First, try to find any existing ArmorStand seats in this world that belong to this sniffer
        try {
            if (world instanceof net.minecraft.server.world.ServerWorld sw) {
                for (Entity e : sw.iterateEntities()) {
                    if (e instanceof ArmorStandEntity ase) {
                        for (String tag : ase.getCommandTags()) {
                            if (tag != null && tag.startsWith("SnifferParent:")) {
                                try { UUID pu = UUID.fromString(tag.substring(14)); if (pu.equals(sniffer.getUuid())) { entities.add(ase); break; } } catch (Throwable ignored) {}
                            }
                        }
                    }
                    if (entities.size() >= MAX_PASSENGERS) break;
                }
            }
        } catch (Throwable ignored) {}

        // If we found some seats, ensure they have seat index tags and are configured.
        boolean[] used = new boolean[MAX_PASSENGERS];
        try {
            for (Entity e : entities) {
                int idx = getLogicalSeatIndex(sniffer, e);
                if (idx >= 0 && idx < MAX_PASSENGERS) used[idx] = true;
            }
            int nextIdx = 0;
            for (Entity e : entities) {
                int idx = getLogicalSeatIndex(sniffer, e);
                if (idx < 0 || idx >= MAX_PASSENGERS) {
                    while (nextIdx < MAX_PASSENGERS && used[nextIdx]) nextIdx++;
                    if (nextIdx < MAX_PASSENGERS) {
                        try { e.addCommandTag("SnifferSeat:" + nextIdx); used[nextIdx] = true; } catch (Throwable ignored) {}
                        nextIdx++;
                    }
                }
                try { e.setInvisible(true); e.setInvulnerable(true); try { java.lang.reflect.Method setMarker = e.getClass().getMethod("setMarker", boolean.class); setMarker.setAccessible(true); setMarker.invoke(e, true); } catch (Throwable ignored) {} } catch (Throwable ignored) {}
                try { syncSeatHealthFromSniffer(e, sniffer.getMaxHealth(), sniffer.getHealth()); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // Create missing seats
        for (int i = 0; i < MAX_PASSENGERS; i++) {
            if (!used[i]) {
                try {
                    ArmorStandEntity seat = new ArmorStandEntity(EntityType.ARMOR_STAND, world);
                    seat.addCommandTag("SnifferSeat:" + i);
                    seat.addCommandTag("SnifferParent:" + sniffer.getUuid());
                    seat.setPos(snifferX, snifferY, snifferZ);
                    seat.setInvisible(true);
                    try { seat.setNoGravity(true); } catch (Throwable ignored) {}
                    seat.setInvulnerable(true);
                    try { java.lang.reflect.Method setMarker = seat.getClass().getMethod("setMarker", boolean.class); setMarker.setAccessible(true); setMarker.invoke(seat, true); } catch (Throwable ignored) {}
                    entities.add(seat);
                    world.spawnEntity(seat);
                    try { syncSeatHealthFromSniffer(seat, sniffer.getMaxHealth(), sniffer.getHealth()); } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
            }
        }

        try {
            StringBuilder sb = new StringBuilder();
            for (Entity e : entities) { if (e == null) continue; sb.append(e.getUuid()).append("(").append(e.getClass().getSimpleName()).append(") "); }
            RideableSnifferMod.logDebug("[MOD DEBUG] Created/Found {} seats for sniffer {}: {}", entities.size(), sniffer.getUuid(), sb.toString());
        } catch (Throwable ignored) {}

        return entities;
    }

    public static float getReflectiveFloat(Entity e, String... names) {
        boolean isHeight = false, isYaw = false;
        for (String n : names) { String nl = n.toLowerCase(); if (nl.contains("height")) isHeight = true; if (nl.contains("yaw")) isYaw = true; }
        Class<?> clazz = e.getClass();
        while (clazz != null && clazz != Object.class) {
            for (String name : names) {
                try { java.lang.reflect.Method m = clazz.getDeclaredMethod(name); m.setAccessible(true); return ((Number) m.invoke(e)).floatValue(); } catch (Throwable ignored) {}
                try { java.lang.reflect.Field f = clazz.getDeclaredField(name); f.setAccessible(true); return ((Number) f.get(e)).floatValue(); } catch (Throwable ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
        if (isHeight) return e.getHeight();
        if (isYaw) return e.getYaw();
        return 0.0f;
    }

    public static double getReflectiveDouble(Entity e, String... names) {
        boolean isX = false, isY = false, isZ = false;
        for (String n : names) { String nl = n.toLowerCase(); if (nl.contains("x")) isX = true; if (nl.contains("y")) isY = true; if (nl.contains("z")) isZ = true; }
        Class<?> clazz = e.getClass();
        while (clazz != null && clazz != Object.class) {
            for (String name : names) {
                try { java.lang.reflect.Method m = clazz.getDeclaredMethod(name); m.setAccessible(true); return ((Number) m.invoke(e)).doubleValue(); } catch (Throwable ignored) {}
                try { java.lang.reflect.Field f = clazz.getDeclaredField(name); f.setAccessible(true); return ((Number) f.get(e)).doubleValue(); } catch (Throwable ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
        if (isX) return e.getX();
        if (isY) return e.getY();
        if (isZ) return e.getZ();
        return 0.0;
    }

    public static Object invokeReflective(Object obj, Object[] args, String... names) {
        if (obj == null) return null;
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (String name : names) {
                try {
                    for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                        if (m.getName().equals(name) && m.getParameterCount() == args.length) { m.setAccessible(true); return m.invoke(obj, args); }
                    }
                } catch (Throwable ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    public static void resetFallDistance(Entity e) {
        if (e == null) return;
        try { java.lang.reflect.Method m = e.getClass().getMethod("setFallDistance", float.class); m.setAccessible(true); m.invoke(e, 0.0f); return; } catch (Throwable ignored) {}
        String[] names = new String[] {"fallDistance", "field_6017", "field_5990"};
        Class<?> clazz = e.getClass();
        while (clazz != null && clazz != Object.class) {
            for (String n : names) { try { java.lang.reflect.Field f = clazz.getDeclaredField(n); f.setAccessible(true); f.setFloat(e, 0.0f); return; } catch (Throwable ignored) {} }
            try { for (java.lang.reflect.Field f : clazz.getDeclaredFields()) { if (f.getType() == float.class && f.getName().toLowerCase().contains("fall")) { f.setAccessible(true); f.setFloat(e, 0.0f); return; } } } catch (Throwable ignored) {}
            clazz = clazz.getSuperclass();
        }
    }

    private static Entity getReflectiveVehicle(Entity e) {
        String[] names = {"getVehicle", "method_5854", "field_6010", "vehicle"};
        Class<?> clazz = e.getClass();
        while (clazz != null && clazz != Object.class) {
            for (String name : names) {
                try { java.lang.reflect.Method m = clazz.getDeclaredMethod(name); m.setAccessible(true); return (Entity) m.invoke(e); } catch (Throwable ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    public static World getReflectiveWorld(Entity e) {
        if (e == null) return null;
        try { java.lang.reflect.Method m = e.getClass().getMethod("getWorld"); m.setAccessible(true); Object w = m.invoke(e); if (w instanceof World) return (World) w; } catch (Throwable ignored) {}
        try { java.lang.reflect.Field f = e.getClass().getDeclaredField("world"); f.setAccessible(true); Object w = f.get(e); if (w instanceof World) return (World) w; } catch (Throwable ignored) {}
        Class<?> clazz = e.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                    try { f.setAccessible(true); Object v = f.get(e); if (v instanceof World) return (World) v; } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /**
     * Best-effort reflective getter for an entity's current health value.
     * Returns a Float if found, or null if unavailable.
     */
    public static Float getReflectiveHealth(Entity e) {
        if (e == null) return null;
        try {
            if (e instanceof LivingEntity living) {
                return living.getHealth();
            }

            // common method
            try { java.lang.reflect.Method m = e.getClass().getMethod("getHealth"); m.setAccessible(true); Object r = m.invoke(e); if (r instanceof Number) return Float.valueOf(((Number) r).floatValue()); } catch (Throwable ignored) {}

            // look for zero-arg methods that mention health
            Class<?> clazz = e.getClass();
            while (clazz != null && clazz != Object.class) {
                for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                    try {
                        String n = m.getName().toLowerCase();
                        if (m.getParameterCount() == 0 && (n.contains("health") || n.equals("health") || n.contains("gethealth"))) {
                            m.setAccessible(true);
                            Object r = m.invoke(e);
                            if (r instanceof Number) return Float.valueOf(((Number) r).floatValue());
                        }
                    } catch (Throwable ignored) {}
                }
                for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                    try {
                        String fn = f.getName().toLowerCase();
                        if (fn.contains("health")) { f.setAccessible(true); Object v = f.get(e); if (v instanceof Number) return Float.valueOf(((Number) v).floatValue()); }
                    } catch (Throwable ignored) {}
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Best-effort reflective setter for an entity's health. Returns true if set succeeded.
     */
    public static boolean setReflectiveHealth(Entity e, float value) {
        if (e == null) return false;
        try {
            if (e instanceof LivingEntity living) {
                living.setHealth(value);
                return true;
            }

            try { java.lang.reflect.Method m = e.getClass().getMethod("setHealth", float.class); m.setAccessible(true); m.invoke(e, value); return true; } catch (Throwable ignored) {}

            Class<?> clazz = e.getClass();
            while (clazz != null && clazz != Object.class) {
                for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                    try {
                        String n = m.getName().toLowerCase();
                        if (m.getParameterCount() == 1 && (n.contains("set") && n.contains("health") || n.equals("sethealth"))) {
                            Class<?> p = m.getParameterTypes()[0];
                            m.setAccessible(true);
                            if (p == float.class || p == Float.class) { m.invoke(e, value); return true; }
                            if (p == double.class || p == Double.class) { m.invoke(e, (double) value); return true; }
                            if (p == int.class || p == Integer.class) { m.invoke(e, (int) value); return true; }
                            if (Number.class.isAssignableFrom(p)) { m.invoke(e, Float.valueOf(value)); return true; }
                        }
                    } catch (Throwable ignored) {}
                }

                for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                    try {
                        String fn = f.getName().toLowerCase();
                        if (fn.contains("health")) {
                            f.setAccessible(true);
                            Class<?> t = f.getType();
                            if (t == float.class || t == Float.class) { f.setFloat(e, value); return true; }
                            if (t == double.class || t == Double.class) { f.setDouble(e, value); return true; }
                            if (t == int.class || t == Integer.class) { f.setInt(e, (int) value); return true; }
                            if (Number.class.isAssignableFrom(t)) { f.set(e, Float.valueOf(value)); return true; }
                        }
                    } catch (Throwable ignored) {}
                }

                clazz = clazz.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void syncSeatHealthFromSniffer(Entity seat, float snifferMaxHealth, Float snifferHealth) {
        if (!(seat instanceof LivingEntity livingSeat)) return;

        float maxHealth = snifferMaxHealth;
        if (maxHealth > 0.0f) {
            try {
                EntityAttributeInstance attr = livingSeat.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
                if (attr != null && Math.abs(attr.getBaseValue() - maxHealth) > 0.001D) {
                    attr.setBaseValue(maxHealth);
                }
            } catch (Throwable ignored) {}
        }

        if (snifferHealth != null) {
            float targetHealth = maxHealth > 0.0f ? Math.min(snifferHealth.floatValue(), maxHealth) : snifferHealth.floatValue();
            try {
                livingSeat.setHealth(Math.max(0.0f, targetHealth));
            } catch (Throwable ignored) {
                try { setReflectiveHealth(seat, targetHealth); } catch (Throwable ignoredAgain) {}
            }
        }
    }

    public static void removeSeats(SnifferEntity sniffer) {
        if (sniffer == null) return;
        removeSeatsByUuid(sniffer.getUuid());
    }

    public static void removeSeatsByUuid(UUID su) {
        if (su == null) return;
        java.util.List<Entity> entities = SEAT_ENTITIES.remove(su);
        if (entities == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (Entity e : entities) { if (e == null) continue; sb.append(e.getUuid()).append("(").append(e.getClass().getSimpleName()).append(") "); }
            RideableSnifferMod.logDebug("[MOD DEBUG] Removing {} seats for sniffer {}: {}", entities.size(), su, sb.toString());
        } catch (Throwable ignored) {}
        for (Entity e : entities) {
            if (e == null) continue;
            boolean removed = tryRemoveEntity(e);

            if (!removed) {
                // Fallback: clear parent tag and move the stale seat out of the world so it won't be reused.
                try {
                    try { e.getCommandTags().remove("SnifferParent:" + su); } catch (Throwable ignored) {}
                    try { e.addCommandTag("SnifferStale"); } catch (Throwable ignored) {}
                    try { e.setPos(0.0, -1000.0, 0.0); } catch (Throwable ignored) {}
                    try { if (e instanceof ArmorStandEntity ase) { ase.setInvisible(true); try { java.lang.reflect.Method setMarker = ase.getClass().getMethod("setMarker", boolean.class); setMarker.setAccessible(true); setMarker.invoke(ase, true); } catch (Throwable ignored) {} } } catch (Throwable ignored) {}
                    try { RideableSnifferMod.logDebug("[MOD DEBUG] Could not fully remove seat {}; moved to graveyard and cleared parent tag", e.getUuid()); } catch (Throwable ignored) {}
                    try { STALE_REMOVAL_RETRIES.put(e.getUuid(), MAX_STALE_REMOVES); } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
            } else {
                try { STALE_REMOVAL_RETRIES.remove(e.getUuid()); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Try to remove an entity using multiple reflective approaches. Returns true if the entity appears removed.
     */
    public static boolean tryRemoveEntity(Entity e) {
        if (e == null) return true;
        boolean removed = false;

        // First, try to force any passengers off this entity
        try { for (Entity p : new java.util.ArrayList<>(e.getPassengerList())) { try { p.stopRiding(); } catch (Throwable ignored) {} } } catch (Throwable ignored) {}

        // Attempt many possible entity-level removal methods via reflection
        try {
            for (java.lang.reflect.Method m : e.getClass().getMethods()) {
                try {
                    String name = m.getName().toLowerCase();
                    if (!(name.contains("remove") || name.contains("discard") || name.contains("kill") || name.contains("despawn") || name.contains("unregister"))) continue;
                    int pc = m.getParameterCount();
                    if (pc == 0) {
                        try { m.invoke(e); } catch (Throwable ignored) {}
                    } else if (pc == 1) {
                        Class<?> ptype = m.getParameterTypes()[0];
                        try {
                            if (ptype.isAssignableFrom(e.getClass()) || ptype == Entity.class) {
                                try { m.invoke(e, e); } catch (Throwable ignored) {}
                            } else if (ptype == UUID.class) {
                                try { m.invoke(e, e.getUuid()); } catch (Throwable ignored) {}
                            } else if (ptype == int.class || ptype == Integer.TYPE) {
                                try {
                                    java.lang.reflect.Method getId = e.getClass().getMethod("getId");
                                    getId.setAccessible(true);
                                    Object id = getId.invoke(e);
                                    if (id instanceof Number) try { m.invoke(e, ((Number) id).intValue()); } catch (Throwable ignored) {}
                                } catch (Throwable ignored) {}
                            } else if (ptype.isEnum()) {
                                try {
                                    Object[] consts = ptype.getEnumConstants();
                                    Object chosen = null;
                                    if (consts != null && consts.length > 0) {
                                        for (Object c : consts) {
                                            String s = c.toString().toLowerCase();
                                            if (s.contains("discard") || s.contains("kill") || s.contains("remove")) { chosen = c; break; }
                                        }
                                        if (chosen == null) chosen = consts[0];
                                    }
                                    if (chosen != null) try { m.invoke(e, chosen); } catch (Throwable ignored) {}
                                } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}

                // Check removed state after each attempt
                try {
                    java.lang.reflect.Method isRem = e.getClass().getMethod("isRemoved");
                    isRem.setAccessible(true);
                    Object v = isRem.invoke(e);
                    if (v instanceof Boolean && ((Boolean) v)) { removed = true; break; }
                } catch (Throwable ignored) {}
                try {
                    java.lang.reflect.Field fr = null;
                    Class<?> cc = e.getClass();
                    while (cc != null && cc != Object.class) {
                        try {
                            for (java.lang.reflect.Field f : cc.getDeclaredFields()) {
                                String fn = f.getName().toLowerCase();
                                if (f.getType() == boolean.class && (fn.contains("removed") || fn.contains("dead") || fn.contains("discard"))) { fr = f; break; }
                            }
                        } catch (Throwable ignored) {}
                        if (fr != null) break;
                        cc = cc.getSuperclass();
                    }
                    if (fr != null) {
                        fr.setAccessible(true);
                        Object val = fr.get(e);
                        if (val instanceof Boolean && ((Boolean) val)) { removed = true; break; }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // If still not removed, try world-level removal methods
        if (!removed) {
            try {
                World w = getReflectiveWorld(e);
                if (w != null) {
                    for (java.lang.reflect.Method m : w.getClass().getMethods()) {
                        try {
                            String name = m.getName().toLowerCase();
                            if (!(name.contains("remove") || name.contains("unregister") || name.contains("discard") || name.contains("despawn"))) continue;
                            int pc = m.getParameterCount();
                            if (pc == 1) {
                                Class<?> ptype = m.getParameterTypes()[0];
                                if (ptype.isAssignableFrom(e.getClass()) || ptype == Entity.class) {
                                    try { m.invoke(w, e); } catch (Throwable ignored) {}
                                } else if (ptype == UUID.class) {
                                    try { m.invoke(w, e.getUuid()); } catch (Throwable ignored) {}
                                } else if (ptype == int.class || ptype == Integer.TYPE) {
                                    try { java.lang.reflect.Method getId = e.getClass().getMethod("getId"); getId.setAccessible(true); Object id = getId.invoke(e); if (id instanceof Number) try { m.invoke(w, ((Number) id).intValue()); } catch (Throwable ignored) {} } catch (Throwable ignored) {}
                                } else if (ptype.isEnum()) {
                                    try { Object[] consts = ptype.getEnumConstants(); if (consts != null && consts.length > 0) try { m.invoke(w, consts[0]); } catch (Throwable ignored) {} } catch (Throwable ignored) {}
                                }
                            } else if (pc == 2) {
                                Class<?> p0 = m.getParameterTypes()[0];
                                Class<?> p1 = m.getParameterTypes()[1];
                                if ((p0.isAssignableFrom(e.getClass()) || p0 == Entity.class) && p1.isEnum()) {
                                    Object[] consts = p1.getEnumConstants(); if (consts != null && consts.length > 0) try { m.invoke(w, e, consts[0]); } catch (Throwable ignored) {}
                                } else if ((p0 == int.class || p0 == Integer.TYPE) && p1.isEnum()) {
                                    try { java.lang.reflect.Method getId = e.getClass().getMethod("getId"); getId.setAccessible(true); Object id = getId.invoke(e); if (id instanceof Number) try { Object[] consts = p1.getEnumConstants(); if (consts != null && consts.length > 0) try { m.invoke(w, ((Number) id).intValue(), consts[0]); } catch (Throwable ignored) {} } catch (Throwable ignored) {} } catch (Throwable ignored) {}
                                }
                            }
                        } catch (Throwable ignored) {}
                        // quick check after each attempt
                        try { java.lang.reflect.Method isRem = e.getClass().getMethod("isRemoved"); isRem.setAccessible(true); Object v = isRem.invoke(e); if (v instanceof Boolean && ((Boolean) v)) { removed = true; break; } } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Final attempt: set removed boolean or call setRemoved(true)
        if (!removed) {
            try {
                java.lang.reflect.Field fr = null;
                Class<?> cc = e.getClass();
                while (cc != null && cc != Object.class) {
                    for (java.lang.reflect.Field f : cc.getDeclaredFields()) {
                        try {
                            String fn = f.getName().toLowerCase();
                            if (f.getType() == boolean.class && (fn.contains("removed") || fn.contains("dead") || fn.contains("discard"))) { fr = f; break; }
                        } catch (Throwable ignored) {}
                    }
                    if (fr != null) break;
                    cc = cc.getSuperclass();
                }
                if (fr != null) {
                    fr.setAccessible(true);
                    try { fr.setBoolean(e, true); } catch (Throwable ignored) {}
                } else {
                    try {
                        java.lang.reflect.Method setRem = e.getClass().getMethod("setRemoved", boolean.class);
                        setRem.setAccessible(true);
                        try { setRem.invoke(e, true); } catch (Throwable ignored) {}
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }

        try { RideableSnifferMod.logDebug("[MOD DEBUG] Seat {} removal attempted: success={}", e.getUuid(), removed); } catch (Throwable ignored) {}

        return removed;
    }

    /**
     * Schedule a saddle-removal sound to play after `ticks` server ticks.
     * Stores the removing player's UUID so we can ensure they hear it too.
     */
    public static void scheduleSaddleRemovalSound(SnifferEntity sniffer, PlayerEntity player, int ticks) {
        if (sniffer == null) return;
        try {
            UUID su = sniffer.getUuid();
            SADDLE_SOUND_TICKS.put(su, Math.max(0, ticks));
            if (player != null) {
                SADDLE_SOUND_PLAYER.put(su, player.getUuid());
                try { RideableSnifferMod.logDebug("[SOUND] scheduleSaddleRemovalSound sniffer={} ticks={} player={} pos={}", su, Math.max(0, ticks), player.getUuid(), player.getPos()); } catch (Throwable ignored) {}
            } else {
                SADDLE_SOUND_PLAYER.remove(su);
                try { RideableSnifferMod.logDebug("[SOUND] scheduleSaddleRemovalSound sniffer={} ticks={} player=null", su, Math.max(0, ticks)); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Schedule a saddle validation check after `ticks` server ticks.
     * If the sniffer is discovered to be a baby, the saddle will be removed and a saddle item dropped.
     */
    public static void scheduleSaddleValidation(SnifferEntity sniffer, PlayerEntity player, int ticks) {
        if (sniffer == null) return;
        try {
            UUID su = sniffer.getUuid();
            SADDLE_VALIDATE_TICKS.put(su, Math.max(0, ticks));
            if (player != null) SADDLE_VALIDATE_PLAYER.put(su, player.getUuid()); else SADDLE_VALIDATE_PLAYER.remove(su);
            try { RideableSnifferMod.logDebug("[SADDLE] scheduleSaddleValidation sniffer={} ticks={} player={}", su, Math.max(0, ticks), player == null ? "null" : player.getUuid()); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    // Periodic attempt to fully remove stale seats placed in the graveyard
    private static void cleanupStaleSeats(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        try {
            java.util.List<UUID> keys = new java.util.ArrayList<>(STALE_REMOVAL_RETRIES.keySet());
            // Try to find and remove entities by UUID first
            for (UUID su : keys) {
                boolean found = false;
                for (var w : server.getWorlds()) {
                    for (Entity e : w.iterateEntities()) {
                        try {
                            if (e != null && e.getUuid().equals(su)) {
                                found = true;
                                boolean removed = tryRemoveEntity(e);
                                if (removed) {
                                    try { e.getCommandTags().remove("SnifferStale"); } catch (Throwable ignored) {}
                                    STALE_REMOVAL_RETRIES.remove(su);
                                } else {
                                    int rem = STALE_REMOVAL_RETRIES.getOrDefault(su, MAX_STALE_REMOVES);
                                    rem = rem - 1;
                                    if (rem <= 0) STALE_REMOVAL_RETRIES.remove(su); else STALE_REMOVAL_RETRIES.put(su, rem);
                                }
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                    if (found) break;
                }
                if (!found) STALE_REMOVAL_RETRIES.remove(su);
            }

            // Additionally, scan for any entities tagged as SnifferStale and attempt removal
            for (var w : server.getWorlds()) {
                for (Entity e : w.iterateEntities()) {
                    try {
                        if (e == null) continue;
                        for (String t : e.getCommandTags()) {
                            if ("SnifferStale".equals(t)) {
                                boolean removed = tryRemoveEntity(e);
                                if (removed) {
                                    try { e.getCommandTags().remove("SnifferStale"); } catch (Throwable ignored) {}
                                    try { STALE_REMOVAL_RETRIES.remove(e.getUuid()); } catch (Throwable ignored) {}
                                } else {
                                    int rem = STALE_REMOVAL_RETRIES.getOrDefault(e.getUuid(), MAX_STALE_REMOVES);
                                    rem = rem - 1;
                                    if (rem <= 0) STALE_REMOVAL_RETRIES.remove(e.getUuid()); else STALE_REMOVAL_RETRIES.put(e.getUuid(), rem);
                                }
                                break;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void dropItemAtSniffer(SnifferEntity sniffer, ItemStack stack) {
        if (sniffer == null || stack == null) return;
        World world = getReflectiveWorld(sniffer);
        if (world == null) return;
        try {
            ItemEntity ie = new ItemEntity(world, getReflectiveDouble(sniffer, "getX", "method_23317", "field_6038", "x"), getReflectiveDouble(sniffer, "getY", "method_23318", "field_6039", "y") + 0.5, getReflectiveDouble(sniffer, "getZ", "method_23321", "field_6036", "z"), stack);
            world.spawnEntity(ie);
        } catch (Throwable ignored) {}
    }

    // Cleanup when a player joins the server: ensure they are not mounted on any stale seats
    public static void onPlayerJoin(net.minecraft.server.network.ServerPlayerEntity player, net.minecraft.server.MinecraftServer server) {
        if (player == null) return;
        try { player.stopRiding(); } catch (Throwable ignored) {}
        RIDING_PLAYERS.remove(player.getUuid());
        DISMOUNT_WATCH.remove(player.getUuid());

        // Teleport to last known safe position if we recorded one on disconnect
        try {
            Vec3d safe = DISCONNECT_SAFE_POSITIONS.remove(player.getUuid());
            if (safe != null) {
                try {
                    player.refreshPositionAndAngles(safe.x, safe.y, safe.z, player.getYaw(), player.getPitch());
                    if (player instanceof net.minecraft.server.network.ServerPlayerEntity spe) {
                        try { spe.networkHandler.requestTeleport(safe.x, safe.y, safe.z, player.getYaw(), player.getPitch()); } catch (Throwable ignored) {}
                    }
                    resetFallDistance(player);
                    player.setVelocity(Vec3d.ZERO);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // Ensure no seat entities still reference this player's UUID as an occupant
        try {
            for (java.util.List<Entity> seats : new java.util.ArrayList<>(SEAT_ENTITIES.values())) {
                if (seats == null) continue;
                for (Entity seat : seats) {
                    if (seat == null) continue;
                    try {
                        // Remove any occupant tag for this player
                        try { seat.getCommandTags().remove("SnifferOccupant:" + player.getUuid()); } catch (Throwable ignored) {}
                        for (Entity p : new java.util.ArrayList<>(seat.getPassengerList())) {
                            if (p instanceof PlayerEntity pe) {
                                if (pe.getUuid().equals(player.getUuid())) {
                                    try { pe.stopRiding(); } catch (Throwable ignored) {}
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    // Cleanup when a player disconnects: remove any riding state and references, and clean up seats if needed
    public static void onPlayerDisconnect(net.minecraft.server.network.ServerPlayerEntity player, net.minecraft.server.MinecraftServer server) {
        if (player == null) return;

        RidingInfo rinfo = RIDING_PLAYERS.remove(player.getUuid());
        DISMOUNT_WATCH.remove(player.getUuid());

        Entity vehicle = null;
        SnifferEntity disconnectSniffer = null;
        ArmorStandEntity disconnectSeat = null;
        try {
            vehicle = player.getVehicle();
            if (vehicle instanceof ArmorStandEntity ase) {
                disconnectSeat = ase;
                disconnectSniffer = getSnifferForSeat(ase);
            } else if (vehicle instanceof SnifferEntity s) {
                disconnectSniffer = s;
            }
        } catch (Throwable ignored) {}
        try {
            if (disconnectSniffer == null && rinfo != null && server != null) {
                disconnectSniffer = findSnifferByUuid(server, rinfo.snifferUuid);
            }
        } catch (Throwable ignored) {}

        // Record a safe teleport position so the player isn't stuck in caves on reconnect.
        try {
            Vec3d safe;
            if (disconnectSniffer != null) {
                safe = getValidatedSnifferExitTarget(disconnectSniffer, disconnectSeat);
            } else {
                safe = new Vec3d(player.getX(), player.getY(), player.getZ());
            }
            DISCONNECT_SAFE_POSITIONS.put(player.getUuid(), safe);
        } catch (Throwable ignored) {}

        try { player.stopRiding(); } catch (Throwable ignored) {}

        // If we knew which sniffer they were riding, try to fix seats and promote others.
        if (rinfo != null && server != null) {
            try {
                SnifferEntity sniffer = findSnifferByUuid(server, rinfo.snifferUuid);
                if (sniffer != null) {
                    java.util.List<Entity> seats = SEAT_ENTITIES.get(sniffer.getUuid());
                    if (seats != null) {
                        for (Entity seat : seats) {
                            if (seat == null) continue;
                            try {
                                // Remove any occupant tag for this player
                                try { seat.getCommandTags().remove("SnifferOccupant:" + player.getUuid()); } catch (Throwable ignored) {}
                                for (Entity p : new java.util.ArrayList<>(seat.getPassengerList())) {
                                    if (p instanceof PlayerEntity pe) {
                                        if (pe.getUuid().equals(player.getUuid())) {
                                            try { pe.stopRiding(); } catch (Throwable ignored) {}
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }

                        // If no remaining passengers, remove seats and restore speed.
                        if (getPassengerCount(sniffer) == 0) {
                            removeSeats(sniffer);
                            SnifferSpeedManager.restoreOriginalSpeed(sniffer);
                        } else if (rinfo.driver) {
                            // Shift remaining passengers forward when the driver disconnected
                            if (getPassengerCount(sniffer) > 0) {
                                try { scheduleShift(sniffer, 2); } catch (Throwable ignored) { try { schedulePromotion(sniffer, 4); } catch (Throwable ignored2) {} }
                            }
                        }
                    } else {
                        if (rinfo.driver) {
                            if (getPassengerCount(sniffer) > 0) promoteNextDriver(sniffer);
                            else SnifferSpeedManager.restoreOriginalSpeed(sniffer);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    }
}

