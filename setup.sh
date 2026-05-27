#!/bin/bash
# Setup and build script for Rideable Sniffer Mod

echo "Setting up Fabric Mod Project Structure..."

# Create directories
mkdir -p src/main/java/net/rideable_sniffer
mkdir -p src/main/resources

# Copy source files
cp RideableSnifferMod.java src/main/java/net/rideable_sniffer/
cp SnifferPassengerManager.java src/main/java/net/rideable_sniffer/
cp SnifferRideableLogic.java src/main/java/net/rideable_sniffer/
cp SnifferEventHandler.java src/main/java/net/rideable_sniffer/

# Copy resources
cp fabric.mod.json src/main/resources/
cp rideable_sniffer.mixins.json src/main/resources/

echo "Structure created successfully!"
echo "Building mod with gradle..."

# Build with gradle
./gradlew build

echo "Build complete! JAR file should be in build/libs/"
