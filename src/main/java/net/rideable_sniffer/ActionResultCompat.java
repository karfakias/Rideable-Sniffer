package net.rideable_sniffer;

import net.minecraft.util.ActionResult;

public final class ActionResultCompat {
    private ActionResultCompat() {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ActionResult pass() {
        Class<ActionResult> cls = ActionResult.class;

        if (cls.isEnum()) {
            return (ActionResult) Enum.valueOf((Class) cls, "PASS");
        }

        // Try known static field aliases across named/intermediary/official namespaces.
        String[] passFieldNames = new String[] { "PASS", "field_5811", "f_19070_", "e" };
        for (String fieldName : passFieldNames) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object value = f.get(null);
                if (value instanceof ActionResult ar) {
                    return ar;
                }
            } catch (ReflectiveOperationException ignored) {
                // Try next candidate.
            }
        }

        // Try declared classes (named or obfuscated) with no-arg constructors.
        for (Class<?> nested : cls.getDeclaredClasses()) {
            if (!ActionResult.class.isAssignableFrom(nested)) {
                continue;
            }
            try {
                java.lang.reflect.Constructor<?> ctor = nested.getDeclaredConstructor();
                ctor.setAccessible(true);
                Object value = ctor.newInstance();
                if (value instanceof ActionResult ar) {
                    return ar;
                }
            } catch (ReflectiveOperationException ignored) {
                // Continue searching.
            }
        }

        // Last resort: any static field that already holds an ActionResult.
        for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (!ActionResult.class.isAssignableFrom(f.getType())) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object value = f.get(null);
                if (value instanceof ActionResult ar) {
                    return ar;
                }
            } catch (ReflectiveOperationException ignored) {
                // Continue searching.
            }
        }

        throw new IllegalStateException("Unable to resolve ActionResult instance for runtime " + cls.getName());
    }
}
