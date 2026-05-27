package net.rideable_sniffer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.StringJoiner;

public class SnifferConfig {
    private static final File CONFIG_FILE = new File(new File("config"), "rideable_sniffer.properties");
    private static final Properties props = new Properties();
    private static final double DEFAULT_DRIVER_SPEED = 0.5;
    private static final double MIN_DRIVER_SPEED = 0.1;
    private static final double MAX_DRIVER_SPEED = 10.0;
    private static double driverSpeed = DEFAULT_DRIVER_SPEED; // default movement speed when driving
    private static final double DEFAULT_WATER_SPEED_FACTOR = 0.35;
    private static final double MIN_WATER_SPEED_FACTOR = 0.05;
    // Allow the water speed multiplier to increase up to 5x if desired
    private static final double MAX_WATER_SPEED_FACTOR = 5.0;
    private static double waterSpeedFactor = DEFAULT_WATER_SPEED_FACTOR; // multiplier applied to horizontal movement in water
    private static boolean debugMode = false; // toggle verbose debug behavior
    // persisted set of saddled sniffer UUIDs
    private static final Set<UUID> persistedSaddles = ConcurrentHashMap.newKeySet();

    public static void loadConfig() {
        try {
            if (!CONFIG_FILE.exists()) {
                CONFIG_FILE.getParentFile().mkdirs();
                props.setProperty("driverSpeed", Double.toString(driverSpeed));
                props.setProperty("waterSpeedFactor", Double.toString(waterSpeedFactor));
                props.setProperty("debugMode", Boolean.toString(debugMode));
                props.setProperty("saddledSniffers", "");
                try (FileOutputStream out = new FileOutputStream(CONFIG_FILE)) {
                    props.store(out, "Rideable Sniffer Config");
                }
                return;
            }
            try (FileInputStream in = new FileInputStream(CONFIG_FILE)) {
                props.load(in);
            }
            String s = props.getProperty("driverSpeed");
            if (s != null) {
                try {
                    driverSpeed = clampSpeed(Double.parseDouble(s));
                } catch (NumberFormatException e) {
                    // ignore, keep default
                }
            }
            String ws = props.getProperty("waterSpeedFactor");
            if (ws != null) {
                try {
                    waterSpeedFactor = clampWaterFactor(Double.parseDouble(ws));
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
            String db = props.getProperty("debugMode");
            if (db != null) {
                try { debugMode = Boolean.parseBoolean(db); } catch (Throwable ignored) {}
            }
            // load persisted saddled sniffers (comma-separated UUIDs)
            String saddles = props.getProperty("saddledSniffers");
            if (saddles != null && !saddles.isEmpty()) {
                String[] parts = saddles.split(",");
                for (String p : parts) {
                    try {
                        String t = p.trim(); if (t.isEmpty()) continue;
                        persistedSaddles.add(UUID.fromString(t));
                    } catch (Throwable ignored) {}
                }
            }
        } catch (IOException e) {
            RideableSnifferMod.LOGGER.error("Failed to load sniffer config", e);
        }
    }

    public static void saveConfig() {
        try {
            if (!CONFIG_FILE.getParentFile().exists()) CONFIG_FILE.getParentFile().mkdirs();
            props.setProperty("driverSpeed", Double.toString(driverSpeed));
            props.setProperty("waterSpeedFactor", Double.toString(waterSpeedFactor));
            props.setProperty("debugMode", Boolean.toString(debugMode));
            // persist saddled sniffer UUIDs as comma-separated list
            StringJoiner sj = new StringJoiner(",");
            for (UUID u : persistedSaddles) sj.add(u.toString());
            props.setProperty("saddledSniffers", sj.toString());
            try (FileOutputStream out = new FileOutputStream(CONFIG_FILE)) {
                props.store(out, "Rideable Sniffer Config");
            }
        } catch (IOException e) {
            RideableSnifferMod.LOGGER.error("Failed to save sniffer config", e);
        }
    }

    public static double getDriverSpeed() {
        return driverSpeed;
    }

    public static void setDriverSpeed(double speed) {
        driverSpeed = clampSpeed(speed);
        props.setProperty("driverSpeed", Double.toString(driverSpeed));
        saveConfig();
    }

    public static double getWaterSpeedFactor() { return waterSpeedFactor; }

    public static void setWaterSpeedFactor(double value) {
        waterSpeedFactor = clampWaterFactor(value);
        props.setProperty("waterSpeedFactor", Double.toString(waterSpeedFactor));
        saveConfig();
    }

    // Persisted saddles accessors
    public static Set<UUID> getPersistedSaddles() { return new java.util.HashSet<>(persistedSaddles); }

    public static void addPersistedSaddle(UUID id) {
        if (id == null) return;
        persistedSaddles.add(id);
        // update properties and save
        try { props.setProperty("saddledSniffers", String.join(",", persistedSaddles.stream().map(UUID::toString).toArray(String[]::new))); } catch (Throwable ignored) {}
        saveConfig();
    }

    public static void removePersistedSaddle(UUID id) {
        if (id == null) return;
        persistedSaddles.remove(id);
        try { props.setProperty("saddledSniffers", String.join(",", persistedSaddles.stream().map(UUID::toString).toArray(String[]::new))); } catch (Throwable ignored) {}
        saveConfig();
    }

    public static double getDefaultWaterSpeedFactor() { return DEFAULT_WATER_SPEED_FACTOR; }

    public static double getDefaultDriverSpeed() {
        return DEFAULT_DRIVER_SPEED;
    }

    public static boolean isDebugMode() { return debugMode; }

    public static void setDebugMode(boolean v) {
        debugMode = v;
        props.setProperty("debugMode", Boolean.toString(debugMode));
        saveConfig();
    }

    private static double clampSpeed(double speed) {
        return Math.max(MIN_DRIVER_SPEED, Math.min(MAX_DRIVER_SPEED, speed));
    }

    private static double clampWaterFactor(double v) {
        return Math.max(MIN_WATER_SPEED_FACTOR, Math.min(MAX_WATER_SPEED_FACTOR, v));
    }
}
