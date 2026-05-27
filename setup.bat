@echo off
setlocal enabledelayedexpansion

echo Setting up Fabric Mod Project Structure...

REM Create directories
if not exist src\main\java\net\rideable_sniffer mkdir src\main\java\net\rideable_sniffer
if not exist src\main\resources mkdir src\main\resources

REM Copy source files
copy RideableSnifferMod.java src\main\java\net\rideable_sniffer\
copy SnifferPassengerManager.java src\main\java\net\rideable_sniffer\
copy SnifferRideableLogic.java src\main\java\net\rideable_sniffer\
copy SnifferEventHandler.java src\main\java\net\rideable_sniffer\

REM Copy resources
copy fabric.mod.json src\main\resources\
copy rideable_sniffer.mixins.json src\main\resources\

echo Structure created successfully!
echo.
echo Building mod with gradle...

REM Check if gradlew exists, if not create it
if not exist gradlew.bat (
    echo Creating gradle wrapper...
    call .\gradlew.bat wrapper --gradle-version 8.7
)

REM Build with gradle
call .\gradlew.bat build

echo.
echo Build complete! JAR file should be in build\libs\
echo.
echo Next steps:
echo 1. Copy the JAR file from build\libs\ to your server's mods folder
echo 2. Restart your Fabric server
echo 3. No client mods needed - works with vanilla Minecraft!

endlocal
pause
