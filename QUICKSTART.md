# Quick Start Guide - Rideable Sniffer Mod

## What You Got

A complete, ready-to-build **Fabric Minecraft server-side mod** that enables up to 3 players to ride sniffers simultaneously with zero client-side installation required!

## Files Included

```
✅ build.gradle              - Gradle build configuration
✅ gradle.properties         - Project versions & settings
✅ settings.gradle           - Gradle project setup
✅ fabric.mod.json           - Mod metadata
✅ rideable_sniffer.mixins.json - Mixin configuration
✅ src/main/java/...         - Java source files
✅ README.md                 - Feature documentation
✅ DEVELOPMENT.md            - Development guide
✅ setup.bat / setup.sh      - Automated setup scripts
```

## Building the Mod (3 Simple Steps)

### Step 1: Ensure Java 21 is Installed
```bash
java -version
# Should show: openjdk version "21" or higher
```
If not installed, download from: https://www.oracle.com/java/technologies/downloads/

### Step 2: Run the Setup Script

**Windows:**
```bash
setup.bat
```

**Linux/Mac:**
```bash
chmod +x setup.sh
./setup.sh
```

**Manual (All Platforms):**
```bash
mkdir -p src/main/java/net/rideable_sniffer
mkdir -p src/main/resources
cp *.java src/main/java/net/rideable_sniffer/
cp fabric.mod.json src/main/resources/
cp rideable_sniffer.mixins.json src/main/resources/
./gradlew build
```

### Step 3: Deploy to Your Server

1. **Locate the JAR:**
   ```
   build/libs/rideable-sniffer-mod-1.0.0.jar
   ```

2. **Copy to server:**
   ```bash
   cp build/libs/rideable-sniffer-mod-1.0.0.jar /path/to/server/mods/
   ```

3. **Restart server** - No client mods needed!

## How Players Use It

1. **Mount:** Right-click a sniffer (empty hand)
2. **Passenger 2:** Another player right-clicks the same sniffer
3. **Passenger 3:** Third player joins the ride
4. **Dismount:** Hold Shift + Jump OR right-click the sniffer again

## Project Structure

```
RideableSnifferMod.java
├─ Main entry point
└─ Registers event listeners

SnifferPassengerManager.java
├─ Manages up to 3 passengers per sniffer
└─ Handles mounting/dismounting

SnifferEventHandler.java
├─ Handles player interactions
└─ Processes sneak/dismount

SnifferRideableLogic.java
└─ Core rideable logic (optional, can be removed)
```

## Features

✨ **100% Server-Side** - Zero client modifications needed  
🐢 **Multi-Rider** - Up to 3 players on one sniffer  
🖱️ **Simple Controls** - Right-click to mount, sneak to dismount  
🌐 **Full Sync** - All clients see all passengers  
⚡ **Lightweight** - Uses vanilla Minecraft mechanics  

## Troubleshooting

| Issue | Solution |
|-------|----------|
| "Java not found" | Install Java 21: https://www.oracle.com/java/technologies/downloads/ |
| Gradle wrapper fails | Run: `java -version` to verify Java 21+ |
| Mod won't load | Check server logs, ensure `mods/` folder exists |
| Can't mount sniffer | Verify mod is in server's mods folder, restart server |
| Only 1 player can ride | Confirm you have 3 attempts to mount (limit is 3 passengers max) |

## Customization

### Change Max Passengers

Edit `src/main/java/net/rideable_sniffer/SnifferPassengerManager.java`:
```java
private static final int MAX_PASSENGERS = 3;  // ← Change this
```

Then rebuild:
```bash
./gradlew build
```

### Support Different Minecraft Versions

Edit `gradle.properties`:
```properties
minecraft_version=1.21          # ← Change this
yarn_mappings=1.21+build.9      # ← And this
```

## Next Steps

1. Build the mod using the steps above
2. Deploy to your server
3. Restart the server
4. Hop on a sniffer and waddle with your friends!

## Support

For issues or questions:
- Check `DEVELOPMENT.md` for detailed dev guide
- Review `README.md` for full documentation
- Check server logs: `logs/latest.log`

---

**Enjoy your rideable sniffers!** 🐢✨

Made for fun server-side gameplay with zero mod installation required for players.
