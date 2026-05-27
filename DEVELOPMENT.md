# Development Guide - Rideable Sniffer Mod

## Prerequisites

- **Java 21 or higher** - Download from [java.com](https://www.oracle.com/java/technologies/downloads/) or use [Amazon Corretto](https://aws.amazon.com/corretto/)
- **Gradle** - Will be downloaded automatically by the gradle wrapper
- **Git** (optional, for version control)

## Project Structure

```
rideable-sniffer-mod/
├── src/
│   └── main/
│       ├── java/net/rideable_sniffer/
│       │   ├── RideableSnifferMod.java       (Main mod entry point)
│       │   ├── SnifferPassengerManager.java  (Passenger management)
│       │   ├── SnifferEventHandler.java      (Event handling)
│       │   └── SnifferRideableLogic.java     (Core logic - can be removed if using manager)
│       └── resources/
│           ├── fabric.mod.json               (Mod metadata)
│           └── rideable_sniffer.mixins.json  (Mixin config)
├── build.gradle                  (Build configuration)
├── gradle.properties             (Gradle properties & versions)
├── settings.gradle               (Gradle project settings)
├── gradlew / gradlew.bat        (Gradle wrapper - auto-downloads gradle)
└── README.md                     (This file)
```

## Building the Mod

### Quick Start (Windows)
Simply run the provided batch script:
```bash
setup.bat
```

### Quick Start (Linux/Mac)
```bash
chmod +x setup.sh
./setup.sh
```

### Manual Build

1. **Create project structure:**
   ```bash
   mkdir -p src/main/java/net/rideable_sniffer
   mkdir -p src/main/resources
   ```

2. **Copy source files:**
   ```bash
   cp RideableSnifferMod.java src/main/java/net/rideable_sniffer/
   cp SnifferPassengerManager.java src/main/java/net/rideable_sniffer/
   cp SnifferEventHandler.java src/main/java/net/rideable_sniffer/
   ```

3. **Copy resource files:**
   ```bash
   cp fabric.mod.json src/main/resources/
   cp rideable_sniffer.mixins.json src/main/resources/
   ```

4. **Build with Gradle:**
   ```bash
   ./gradlew build
   ```

   Or on Windows:
   ```bash
   .\gradlew.bat build
   ```

5. **Find your JAR:**
   ```
   build/libs/rideable-sniffer-mod-1.0.0.jar
   ```

## Installation on Server

1. **Copy the JAR file:**
   ```bash
   cp build/libs/rideable-sniffer-mod-1.0.0.jar /path/to/server/mods/
   ```

2. **Restart your Fabric server:**
   ```bash
   # Server start command varies by setup, typically:
   java -Xmx4G -Xms2G -jar fabric-server-launcher.jar nogui
   ```

3. **Verify the mod loaded:**
   Check server logs for:
   ```
   [Rideable Sniffer Mod] Initializing Rideable Sniffer Mod v1.0.0
   [Rideable Sniffer Mod] Rideable Sniffer Mod initialized successfully!
   ```

## Code Organization

### RideableSnifferMod.java
The main entry point. Registers event listeners:
- **UseEntityCallback** - Handles right-click on sniffers
- **ServerTickEvents.END_SERVER_TICK** - Handles sneak-dismounting

### SnifferPassengerManager.java
Core manager for passenger logic:
- `addPassenger()` - Mounts a player
- `removePassenger()` - Dismounts a player
- `getPassengerCount()` - Get riders on sniffer
- `canAcceptPassenger()` - Check if sniffer has room
- `repositionPassengers()` - Adjust player positions on sniffer

### SnifferEventHandler.java
Handles event logic:
- `handleSnifferClick()` - Mount/dismount on right-click
- `handleSneak()` - Dismount when sneaking

### SnifferRideableLogic.java
Alternative/legacy implementation (can be removed if using manager)

## Configuration

### Change Max Passengers
Edit `SnifferPassengerManager.java`:
```java
private static final int MAX_PASSENGERS = 3;  // Change this value
```

Then rebuild:
```bash
./gradlew build
```

### Change Passenger Spacing
Edit `SnifferPassengerManager.java`:
```java
private static final double PASSENGER_SPACING = 0.5;  // Adjust spacing
```

## Troubleshooting

### Build Fails - "Java version mismatch"
Ensure Java 21+ is installed and in your PATH:
```bash
java -version  # Should show version 21 or higher
```

### Gradle Won't Download Dependencies
Check your internet connection and ensure Maven repositories are accessible:
- https://maven.fabricmc.net/
- https://maven.ladysnake.org/release
- https://maven.terraformersmc.com/

### Server won't load mod
1. Verify JAR is in `mods/` folder
2. Check mod name matches `rideable-sniffer-mod-*.jar`
3. Verify Fabric Loader is installed on server
4. Check server logs for error messages

## Minecraft Compatibility

| Version | Supported |
|---------|-----------|
| 1.21    | ✅ Yes    |
| 1.20.x  | ❓ Maybe  |
| 1.19.x  | ❌ No     |

To support other versions, update `gradle.properties`:
```properties
minecraft_version=1.20.4
yarn_mappings=1.20.4+build.2
```

Then rebuild.

## Development Tips

- **Hot-reload testing:** Use Fabric's development server for faster iteration
- **Logging:** Use `RideableSnifferMod.LOGGER` for debug output
- **Event handling:** Register handlers in `registerEventListeners()` method
- **Testing:** Install on a test server first before deploying

## Contributing

Feel free to submit improvements, bug fixes, or feature requests!

## Resources

- [Fabric Mod Development](https://fabricmc.net/wiki/tutorial:setup)
- [Minecraft Server Wiki](https://minecraft.wiki/w/Server)
- [Gradle Documentation](https://docs.gradle.org/)
