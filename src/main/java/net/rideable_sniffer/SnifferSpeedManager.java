package net.rideable_sniffer;

import net.minecraft.entity.passive.SnifferEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.attribute.EntityAttributeInstance;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SnifferSpeedManager {
    private static final Map<UUID, Double> originalSpeeds = new ConcurrentHashMap<>();

    public static void applyDriverSpeed(SnifferEntity sniffer) {
        if (sniffer == null) return;
        UUID id = sniffer.getUuid();
        EntityAttributeInstance inst = sniffer.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (inst == null) return;
        // store original if not stored
        originalSpeeds.putIfAbsent(id, inst.getBaseValue());
        // set to configured driver speed
        inst.setBaseValue(SnifferConfig.getDriverSpeed());
    }

    public static void restoreOriginalSpeed(SnifferEntity sniffer) {
        if (sniffer == null) return;
        UUID id = sniffer.getUuid();
        EntityAttributeInstance inst = sniffer.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (inst == null) return;
        Double orig = originalSpeeds.remove(id);
        if (orig != null) inst.setBaseValue(orig);
    }

    public static void restoreIfTrackedAndUnridden(SnifferEntity sniffer) {
        if (sniffer == null) return;
        if (SnifferPassengerManager.getPassengerCount(sniffer) == 0 && originalSpeeds.containsKey(sniffer.getUuid())) {
            restoreOriginalSpeed(sniffer);
        }
    }

    // Remove any stored original speed for a sniffer UUID (cleanup to avoid leaks)
    public static void cleanup(UUID id) {
        if (id == null) return;
        originalSpeeds.remove(id);
    }
}
