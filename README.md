# MapDB - Database-Backed Map Storage System

A Minecraft server plugin that stores map pixel and palette data in a database (SQLite or MySQL) with RLE compression, supporting 64-bit map IDs to bypass the vanilla ~32,000 map limit.

## Features

- **Unlimited Maps**: Store maps with 64-bit IDs in a database, far exceeding vanilla's ~32,000 map limit
- **Database Support**: SQLite (default) or MySQL with RLE + DEFLATE compression
- **Multi-Version Support**: Works with Spigot, Paper, and Folia from 1.12.2 to 1.21.x
- **Virtual ID System**: Dynamically assigns vanilla-compatible IDs (0-32000) for rendering
- **Multi-Block Maps**: Create large images spanning multiple map blocks
- **Image Import**: Create maps from URLs with automatic resizing and dithering
- **API for Developers**: Full API for other plugins to store and retrieve maps
- **Map Preview**: Block displays (1.19.4+) or particle outlines for map preview while holding
- **Permission-Based Limits**: Configurable per-player/group map creation limits

## Supported Versions

| Minecraft | Status | Notes |
|-----------|--------|-------|
| 1.21.x | ✅ Full Support | Includes Folia support |
| 1.20.x | ✅ Full Support | Block displays available |
| 1.19.4+ | ✅ Full Support | Block displays available |
| 1.19.x | ✅ Full Support | Particle previews |
| 1.18.x | ✅ Full Support | Particle previews |
| 1.17.x | ✅ Full Support | Particle previews |
| 1.16.x | ✅ Full Support | Particle previews |
| 1.13-1.15 | ✅ Full Support | Particle previews |
| 1.12.2 | ✅ Full Support | Legacy map system |

## Requirements

- Java 8+ (Java 17+ recommended for 1.18+, Java 21 for 1.21+)
- Spigot, Paper, or Folia server
- ProtocolLib (optional - see below)

### ProtocolLib Support

ProtocolLib is **optional** but enables the virtual ID system for unlimited maps. Without ProtocolLib, the plugin operates in **basic mode** with standard Bukkit map IDs (limited to ~32,000 maps).

| Feature | With ProtocolLib | Without ProtocolLib |
|---------|------------------|---------------------|
| Virtual ID System | ✅ Unlimited maps | ❌ Limited to ~32,000 |
| Map Packet Interception | ✅ Full control | ❌ Not available |
| Database Storage | ✅ Full | ✅ Full |
| Multi-Block Maps | ✅ Full | ✅ Full |
| Image Import | ✅ Full | ✅ Full |

**Versions supported without ProtocolLib:**

| Minecraft | Status |
|-----------|--------|
| 1.21.x | ✅ Basic mode |
| 1.20.x | ✅ Basic mode |
| 1.19.x | ✅ Basic mode |
| 1.18.x | ✅ Basic mode |
| 1.17.x | ✅ Basic mode |
| 1.16.x | ✅ Basic mode |
| 1.13-1.15 | ✅ Basic mode |
| 1.12.2 | ✅ Basic mode |

All versions from 1.12.2 to 1.21.x work in basic mode without ProtocolLib using native NMS adapters.

## Installation

1. Download the latest release from the releases page
2. Place `MapDB.jar` in your server's `plugins` folder
3. (Optional) Install ProtocolLib for unlimited map support via virtual IDs
4. Restart your server
5. Configure `plugins/MapDB/config.yml` as needed

## Configuration

```yaml
# Database configuration
database:
  type: sqlite  # or 'mysql'
  sqlite:
    file: maps.db
  mysql:
    host: localhost
    port: 3306
    database: mapdb
    username: root
    password: ""
    use-ssl: false

# Permission settings
permissions:
  default-limit: 100  # -1 for unlimited
  group-limits:
    vip: 500
    premium: 1000
    staff: -1
  allow-url-import: true
  max-import-size: 4096
  allowed-domains: []  # Empty = allow all

# Map settings
maps:
  max-virtual-ids: 30000
  cleanup-interval: 6000
  stale-time: 86400000
  use-particles-legacy: true
  use-block-displays: true

# Image processing
images:
  default-dither: true
  max-width: 10
  max-height: 10
  connection-timeout: 10000
  read-timeout: 30000
```

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/mapdb url <url> [width] [height] [dither]` | Create map from URL | `mapdb.url` |
| `/mapdb get <mapId>` | Get a map item by ID | `mapdb.get` |
| `/mapdb delete <mapId>` | Delete a map | `mapdb.delete` |
| `/mapdb list [pluginId]` | List stored maps | `mapdb.list` |
| `/mapdb info <mapId>` | Show map information | `mapdb.info` |
| `/mapdb stats` | Show plugin statistics | `mapdb.admin` |
| `/mapdb reload` | Reload configuration | `mapdb.admin` |

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `mapdb.use` | Basic command access | true |
| `mapdb.create` | Create blank maps | op |
| `mapdb.url` | Create maps from URLs | op |
| `mapdb.get` | Get map items | op |
| `mapdb.delete` | Delete maps | op |
| `mapdb.list` | List maps | op |
| `mapdb.info` | View map info | op |
| `mapdb.admin` | Full admin access | op |
| `mapdb.unlimited` | No map creation limit | op |
| `mapdb.limit.<group>` | Use group-specific limit | false |

## API Usage

### Getting the API

```java
import com.example.mapdb.api.MapDBAPI;

MapDBAPI api = MapDBAPI.getInstance();
if (api != null) {
    // API is available
}
```

### Creating Maps

```java
// Create a map from pixel data
byte[] pixels = new byte[16384]; // 128x128
CompletableFuture<StoredMap> future = api.createMap("myplugin", pixels);

// Create a map from an image
BufferedImage image = ImageIO.read(new File("image.png"));
CompletableFuture<StoredMap> future = api.createMapFromImage("myplugin", image, true);

// Create a multi-block map (e.g., 3x2)
CompletableFuture<MultiBlockMap> future = api.createMultiBlockMap("myplugin", image, 3, 2, true);
```

### Retrieving Maps

```java
// Get a map by ID
CompletableFuture<Optional<StoredMap>> future = api.getMap(12345L);

// Get pixel data
CompletableFuture<Optional<MapData>> future = api.getMapData(12345L);

// Get all maps for a plugin
CompletableFuture<List<StoredMap>> future = api.getMapsByPlugin("myplugin");
```

### Creating Map Items

```java
// Create a map ItemStack
ItemStack mapItem = api.createMapItem(12345L);

// Give a map to a player
api.giveMapToPlayer(player, 12345L);

// Check if an item is a stored map
if (api.isStoredMap(item)) {
    long mapId = api.getMapIdFromItem(item);
}
```

### Listening to Events

```java
@EventHandler
public void onMapCreate(MapCreateEvent event) {
    StoredMap map = event.getMap();
    Player creator = event.getCreator();
    // Cancel if needed: event.setCancelled(true);
}

@EventHandler
public void onMapLoad(MapLoadEvent event) {
    StoredMap map = event.getMap();
    Player player = event.getPlayer();
    int virtualId = event.getVirtualId();
}
```

## Technical Details

### Database Schema

The plugin creates the following tables:

- `mapdb_maps` - Map metadata (ID, plugin, creator, timestamps, etc.)
- `mapdb_data` - Compressed pixel data
- `mapdb_groups` - Multi-block map group information
- `mapdb_ids` - ID sequence counters

### Compression

Map data is compressed using a two-stage approach:
1. Run-Length Encoding (RLE) - Efficient for maps with repeated colors
2. DEFLATE - Standard compression on top of RLE

This typically achieves 70-90% compression on most maps.

### Virtual ID System

Since vanilla Minecraft uses ~16-bit map IDs internally, this plugin:
1. Stores maps with 64-bit IDs in the database
2. Dynamically assigns virtual IDs (1000-30000) when maps are viewed
3. Each player can have their own virtual ID space
4. Intercepts map packets and serves custom data

### Multi-Block Maps

Large images can be split into multiple 128x128 maps:
- All maps in a group share a `group_id`
- Breaking any map in a group breaks all and drops one item
- Grid positions (`grid_x`, `grid_y`) track arrangement

## Building from Source

```bash
git clone https://github.com/example/mapdb.git
cd mapdb
./gradlew build
```

The built JAR will be in `plugin/build/libs/MapDB-1.0.0.jar`.

### Gradle Tasks

| Task | Description |
|------|-------------|
| `./gradlew build` | Build the plugin JAR |
| `./gradlew :plugin:shadowJar` | Build shaded JAR with dependencies |
| `./gradlew clean` | Clean build directories |
| `./gradlew :api:jar` | Build only the API module |

### Dependencies

The project uses the following external dependencies:

**ProtocolLib** (from Maven Central):
```kotlin
compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
```

**RedLib** (from redempt.dev):
```kotlin
repositories {
    maven("https://redempt.dev")
}
dependencies {
    implementation("com.github.Redempt:RedLib:6.5.8")
}
```

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
