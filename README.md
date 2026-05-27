# Rideable Sniffer Mod

A 100% **server-side only** Fabric Minecraft mod that allows up to **3 players to ride sniffers simultaneously**!

## Features

✨ **Saddle Required** - Sniffers need saddles to be rideable  
✨ **Multi-Passenger Support** - Ride sniffers with up to 3 players at once  
🖱️ **Simple Controls** - Right-click with saddle to equip, right-click to mount, sneak to dismount  
📦 **Server-Side Only** - No client-side installation required! Works with vanilla Minecraft clients  
🌍 **Full Synchronization** - All players see the saddles and passengers  
⚡ **Lightweight** - Minimal performance impact, uses vanilla Minecraft entity mechanics  

## Installation

### Prerequisites
- Fabric Loader 0.15.0 or higher
- Fabric API 0.100.0 or higher
- Minecraft 1.21+

### Setup

1. **Build the mod:**
   ```bash
   ./gradlew build
   ```

2. **Locate the compiled JAR:**
   The mod JAR will be in `build/libs/rideable-sniffer-mod-1.0.0.jar`

3. **Install on your server:**
   - Copy the JAR to your server's `mods` folder
   - Restart the server
   - No client mods needed!

## How to Use

1. **Get a Saddle:**
   - Find one in loot, crafting, or creative mode
   - You need one saddle per sniffer

2. **Equip the Saddle:**
   - Right-click a sniffer with a saddle in hand
   - ✅ Saddle is now visible on the sniffer

3. **Mount the Sniffer:**
   - Right-click on the saddled sniffer to mount
   - Second player right-clicks same sniffer to join
   - Third player joins (max 3 riders)

4. **Dismount:**
   - Hold Shift (Sneak) and jump
   - Or right-click the sniffer again

## Technical Details

### Architecture

- **Event System**: Uses Fabric's `UseEntityCallback` to intercept entity interactions
- **Saddle Tracking**: Minecraft's built-in `SADDLED` data tracker property
- **Entity Passengers**: Leverages Minecraft's built-in entity passenger system (no custom packets)
- **Synchronization**: All state is handled by vanilla entity sync - works with unmodded clients
- **Saddle Visibility**: Server syncs saddle NBT data; clients auto-render saddle texture
- **Max Passengers**: Hardcoded to 3 per sniffer (configurable in source code)
- **Saddle Requirement**: Sniffers must have saddle equipped before mounting

### Files

- `RideableSnifferMod.java` - Main mod entry point
- `SnifferRideableLogic.java` - Core mounting/dismounting logic
- `build.gradle` - Gradle build configuration
- `fabric.mod.json` - Mod metadata
- `rideable_sniffer.mixins.json` - Mixin configuration

## Compatibility

- ✅ Works with vanilla clients (no mod installation needed)
- ✅ Works on dedicated servers
- ✅ Works in single-player (with Fabric Loader installed)
- ✅ Compatible with other Fabric mods

## Configuration

To change the maximum number of passengers per sniffer, edit `SnifferRideableLogic.java`:

```java
private static final int MAX_PASSENGERS = 3;  // Change this value
```

Then rebuild: `./gradlew build`

## Troubleshooting

**Issue**: Mod doesn't load
- Ensure Fabric Loader and Fabric API are installed
- Check server logs for error messages

**Issue**: Can't mount sniffers
- Ensure the sniffer is not already at the passenger limit (3)
- Check that the mod jar is in the `mods` folder

**Issue**: Passengers not visible on client
- This is normal if the client doesn't have the mod - server state will sync when they join
- Restart the client to refresh

## License

MIT License - See LICENSE file for details

## Credits

Created for the Minecraft community to enable fun cooperative gameplay on Fabric servers!
