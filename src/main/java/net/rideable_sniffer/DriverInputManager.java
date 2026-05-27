package net.rideable_sniffer;

import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DriverInputManager {
    public record DriverInput(float sideways, float forward, boolean jumping, boolean sneaking) {}

    private static final Map<UUID, DriverInput> INPUTS = new ConcurrentHashMap<>();

    private DriverInputManager() {}

    public static void update(ServerPlayerEntity player, PlayerInputC2SPacket packet) {
        if (player == null || packet == null) return;
        DriverInput decoded = decodeInputRecord(packet);
        if (decoded == null) {
            decoded = new DriverInput(
                readFloat(packet, new String[] {"getSideways", "method_12372", "b"}, new String[] {"sideways", "field_12995", "d"}),
                readFloat(packet, new String[] {"getForward", "method_12373", "e"}, new String[] {"forward", "field_12994", "e"}),
                readBoolean(packet, new String[] {"isJumping", "method_12371", "f"}, new String[] {"jumping", "field_12997", "f"}),
                readBoolean(packet, new String[] {"isSneaking", "method_12370", "g"}, new String[] {"sneaking", "field_12996", "g"})
            );
        }
        // Apply inputs directly to the server player fields so ridden entities
        // that read the controlling passenger's `forwardSpeed`/`sidewaysSpeed`
        // receive the correct values during the entity tick.
        try {
            player.sidewaysSpeed = decoded.sideways();
            player.forwardSpeed = decoded.forward();
        } catch (Throwable ignored) {
            // If fields move between mappings, keep functionality graceful.
        }
        INPUTS.put(player.getUuid(), decoded);
    }

    public static void updateFromPlayerState(ServerPlayerEntity player) {
        if (player == null) return;
        INPUTS.put(player.getUuid(), new DriverInput(
            player.sidewaysSpeed,
            player.forwardSpeed,
            false,
            player.isSneaking()
        ));
    }

    public static DriverInput get(ServerPlayerEntity player) {
        if (player == null) return null;
        return INPUTS.get(player.getUuid());
    }

    private static float readFloat(Object target, String[] methodNames, String[] fieldNames) {
        for (String methodName : methodNames) {
            try {
                Method m = target.getClass().getMethod(methodName);
                m.setAccessible(true);
                Object value = m.invoke(target);
                if (value instanceof Float f) return f;
            } catch (ReflectiveOperationException ignored) {
                // Try next.
            }
        }
        for (String fieldName : fieldNames) {
            try {
                Field f = target.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object value = f.get(target);
                if (value instanceof Float fl) return fl;
            } catch (ReflectiveOperationException ignored) {
                // Try next.
            }
        }
        return 0.0F;
    }

    private static boolean readBoolean(Object target, String[] methodNames, String[] fieldNames) {
        for (String methodName : methodNames) {
            try {
                Method m = target.getClass().getMethod(methodName);
                m.setAccessible(true);
                Object value = m.invoke(target);
                if (value instanceof Boolean b) return b;
            } catch (ReflectiveOperationException ignored) {
                // Try next.
            }
        }
        for (String fieldName : fieldNames) {
            try {
                Field f = target.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object value = f.get(target);
                if (value instanceof Boolean b) return b;
            } catch (ReflectiveOperationException ignored) {
                // Try next.
            }
        }
        return false;
    }

    private static DriverInput decodeInputRecord(PlayerInputC2SPacket packet) {
        Object input = readObject(packet, new String[] {"input", "comp_3139", "b", "f_346536_"},
            new String[] {"input", "field_346536_", "b"});
        if (input == null) {
            return null;
        }

        boolean forward = readBoolean(input,
            new String[] {"forward", "comp_3159", "a", "f_347331_"},
            new String[] {"forward", "comp_3159", "c", "f_347331_"});
        boolean backward = readBoolean(input,
            new String[] {"backward", "comp_3160", "b", "f_346889_"},
            new String[] {"backward", "comp_3160", "d", "f_346889_"});
        boolean left = readBoolean(input,
            new String[] {"left", "comp_3161", "c", "f_349339_"},
            new String[] {"left", "comp_3161", "e", "f_349339_"});
        boolean right = readBoolean(input,
            new String[] {"right", "comp_3162", "d", "f_347707_"},
            new String[] {"right", "comp_3162", "f", "f_347707_"});
        boolean jump = readBoolean(input,
            new String[] {"jump", "comp_3163", "e", "f_348085_"},
            new String[] {"jump", "comp_3163", "g", "f_348085_"});
        boolean sneak = readBoolean(input,
            new String[] {"sneak", "shift", "comp_3164", "f", "f_347762_"},
            new String[] {"sneak", "shift", "comp_3164", "h", "f_347762_"});

        float forwardValue = (forward ? 1.0F : 0.0F) + (backward ? -1.0F : 0.0F);
        float sidewaysValue = (left ? 1.0F : 0.0F) + (right ? -1.0F : 0.0F);
        return new DriverInput(sidewaysValue, forwardValue, jump, sneak);
    }

    private static Object readObject(Object target, String[] methodNames, String[] fieldNames) {
        for (String methodName : methodNames) {
            try {
                Method m = target.getClass().getMethod(methodName);
                m.setAccessible(true);
                Object value = m.invoke(target);
                if (value != null) return value;
            } catch (ReflectiveOperationException ignored) {
                // Try next.
            }
        }
        for (String fieldName : fieldNames) {
            try {
                Field f = target.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object value = f.get(target);
                if (value != null) return value;
            } catch (ReflectiveOperationException ignored) {
                // Try next.
            }
        }
        return null;
    }
}
