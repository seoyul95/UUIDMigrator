# FullDataRestore

A Bukkit/Paper plugin for offline servers that
copies online‑mode player data (inventory, advancements, stats) and
applies the correct skin texture for cracked players. Includes a `/restoreall`
command and automatic processing on enable.

## Usage
1. Build with Maven: `mvn clean package`.
2. Place the generated JAR (`target/UUIDMigrator-1.0.jar`) into your server's `plugins` directory.
3. Start the server; the plugin will automatically restore existing cracked data.
4. Use `/restoreall` (permission `fulldatarestore.restoreall`) to process all offline files.

## Development
- Java 17+ (maven)
- Paper 1.21.11 API


## Source
This repository contains the plugin source; feel free to open issues or contribute.