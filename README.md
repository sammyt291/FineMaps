# FineMaps - Database-Backed Map Storage System

A Minecraft server plugin that stores map pixel data in a database (SQLite or MySQL) with compression, and renders maps via custom MapViews/MapRenderers. FineMaps also supports multi-block “map arts”, URL image imports, vanilla map importing, and optional Vault economy/GUI features.

## Features

- **Database-backed storage**: Store map pixel data in SQLite (default) or MySQL
- **Compression**: RLE + DEFLATE on stored pixel data
- **Multi-block map arts**: Create large images split across multiple maps (e.g. 3x2)
- **URL image import**: Create 1x1 or multi-block arts from an image URL (size limits + allowlist supported)
- **Vanilla map importing**: Import a vanilla filled map (held or by map id) into FineMaps
- **Bulk vanilla import**: Import all `map_*.dat` files from one world or all worlds
- **Art naming + lookup**: Store a short “art name” and retrieve/delete by name
- **Art browser GUI**: Paginated in-game art browser (optional, configurable)
- **Vault economy integration**: Optional purchasing via `/finemaps buy` and GUI buying
- **Map previews**: Particle preview outline, plus optional display-based previews when supported
- **Permission-based limits**: Global default limit, group limits via `finemaps.limit.<group>`, or unlimited via `finemaps.unlimited`
- **API for developers**: Create/retrieve maps, create items, and listen for map events

## Supported Versions

| Minecraft | Status | Notes |
|-----------|--------|-------|
| 1.21.x | ✅ Supported | Folia supported (`folia-supported: true`) |
| 1.20.x | ✅ Supported |  |
| 1.19.4+ | ✅ Supported | Display entities available, particle preview still supported |
| 1.19.0–1.19.3 | ✅ Supported | Particle previews |
| 1.18.x | ✅ Supported | Particle previews |
| 1.17.x | ✅ Supported | Particle previews |
| 1.16.x | ✅ Supported | Particle previews |
| 1.15.x | ✅ Supported | Particle previews |
| 1.14.x | ✅ Supported | Minimum supported version |

## Requirements

- Minecraft server: Spigot/Paper/Folia **1.14+**
- Java: **8+** (use the server-recommended Java for your MC version; e.g. Java 17 for 1.18+)
- Optional: ProtocolLib (recommended)
- Optional: Vault + an economy plugin (for buy/economy features)

### ProtocolLib Support

ProtocolLib is **optional**. FineMaps works without it using Bukkit `MapView` + `MapRenderer`. When ProtocolLib is present, FineMaps can use packet-based map sending capabilities provided by the ProtocolLib-backed adapter.

| Feature | With ProtocolLib | Without ProtocolLib |
|---------|------------------|---------------------|
| Database storage | ✅ | ✅ |
| Map packet interception | ✅ (available) | ❌ |
| Partial map packets | ✅ | ❌ |
| Multi-block maps | ✅ | ✅ |
| URL import | ✅ | ✅ |

## Installation

1. Download the latest release from the releases page
2. Place `FineMaps.jar` in your server's `plugins` folder
3. (Optional) Install ProtocolLib (recommended)
4. (Optional) Install Vault + an economy plugin (if you want `/finemaps buy` and GUI buying)
5. Restart your server
6. Configure `plugins/FineMaps/config.yml` as needed

## Configuration

This is the default `config.yml` shape shipped with the plugin:

```yaml
database:
  type: sqlite  # or 'mysql'
  sqlite-file: maps.db
  mysql:
    host: localhost
    port: 3306
    database: finemaps
    username: root
    password: ""
    use-ssl: false

permissions:
  default-limit: 100  # -1 for unlimited
  group-limits:
    vip: 500
    premium: 1000
    staff: -1
  allow-url-import: true
  max-import-size: 4096
  allowed-domains: []  # Empty = allow all

maps:
  max-virtual-ids: 30000
  cleanup-interval: 6000
  stale-time: 86400000
  use-particles-legacy: true
  use-block-displays: true

images:
  default-dither: true
  max-width: 10
  max-height: 10
  connection-timeout: 10000
  read-timeout: 30000

# Vault economy integration
economy:
  enabled: false
  enable-buy-command: true
  cost-per-map: 100.0
  multiply-by-tiles: true

# GUI browser
gui:
  enabled: true
  show-cost-in-tooltip: true
```

## Commands

Main command: `/finemaps` (aliases: `/fm`, `/maps`)

| Command | Description | Permission |
|---------|-------------|------------|
| `/finemaps create` | Create a blank 1x1 FineMaps map and give it to you | `finemaps.create` |
| `/finemaps url <name> <url> [width] [height] [dither]` | Create a 1x1 or multi-block art from an image URL | `finemaps.url` |
| `/finemaps import [mapId] [name]` | Import a vanilla filled map (held or by id) into FineMaps | `finemaps.import` |
| `/finemaps convert [mapId] [name]` | Alias of `import` | `finemaps.import` |
| `/finemaps importall [world]` | Bulk-import all vanilla `map_*.dat` files (optionally for one world) | `finemaps.importall` |
| `/finemaps convertall [world]` | Alias of `importall` | `finemaps.importall` |
| `/finemaps get <name>` | Get a stored map art item by name (single or multi-block) | `finemaps.get` |
| `/finemaps give <name>` | Alias of `get` | `finemaps.get` |
| `/finemaps delete <name>` | Delete a stored map art by name (single or multi-block) | `finemaps.delete` |
| `/finemaps remove <name>` | Alias of `delete` | `finemaps.delete` |
| `/finemaps list [pluginId]` | List stored maps for a plugin namespace (defaults to `finemaps`) | `finemaps.list` |
| `/finemaps info <name>` | Show stored map art info (type, size, IDs, timestamps) | `finemaps.info` |
| `/finemaps stats` | Show plugin statistics | `finemaps.stats` |
| `/finemaps reload` | Reload configuration | `finemaps.reload` |
| `/finemaps gui` | Open the paginated art browser GUI | `finemaps.gui` |
| `/finemaps gallery` / `/finemaps arts` | Aliases of `gui` | `finemaps.gui` |
| `/finemaps buy <name>` | Buy a map art (requires economy enabled + Vault) | `finemaps.buy` |
| `/finemaps config get <path>` | Read a config value by path | `finemaps.config` |
| `/finemaps config set <path> <value>` | Set a config value by path (reloads config) | `finemaps.config` |
| `/finemaps config reset <path>` | Reset a config value by path to default | `finemaps.config` |
| `/finemaps debug <...>` | Debug/load-testing suite | `finemaps.admin` |

### Notes

- **Art names**: for `/finemaps url` and optional import naming, names must match `^[a-zA-Z0-9_-]+$` and be **≤ 32 chars**.
- **Bulk vanilla imports**: named imports use the format `v_<world>_<id>` when possible (collisions fall back to unnamed imports).

### Debug / load testing

All debug subcommands require `finemaps.admin`:

- `/finemaps debug seed <num>`: seed `<num>` maps into the DB (plugin_id=`debug`)
- `/finemaps debug placemaps <mapsPerSecond>`: place 2x2 walls continuously using the seeded maps
- `/finemaps debug stop`: stop an active placement task
- `/finemaps debug inspect [on|off|toggle]`: toggle stick right-click inspection output on item frames

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `finemaps.use` | Basic command access | true |
| `finemaps.create` | Create blank maps | op |
| `finemaps.import` | Import a vanilla filled map into FineMaps | op |
| `finemaps.importall` | Bulk import vanilla `map_*.dat` files | op |
| `finemaps.url` | Create maps from URLs | op |
| `finemaps.get` | Get map items | op |
| `finemaps.delete` | Delete maps | op |
| `finemaps.list` | List maps | op |
| `finemaps.info` | View map info | op |
| `finemaps.reload` | Reload configuration | op |
| `finemaps.stats` | View statistics | op |
| `finemaps.buy` | Buy map arts (and buy via GUI) | true |
| `finemaps.gui` | Open the art browser GUI | true |
| `finemaps.config` | Use `/finemaps config get/set/reset` | op |
| `finemaps.admin` | Full admin access | op |
| `finemaps.unlimited` | No map creation limit | op |
| `finemaps.limit.<group>` | Use group-specific limit (based on `permissions.group-limits`) | false

## API Usage

### Getting the API

```java
import com.example.finemaps.api.FineMapsAPI;

FineMapsAPI api = FineMapsAPI.getInstance();
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

- `finemaps_maps` - Map metadata (ID, plugin, creator, timestamps, etc.)
- `finemaps_data` - Compressed pixel data
- `finemaps_groups` - Multi-block map group information
- `finemaps_ids` - ID sequence counters

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
git clone https://github.com/example/finemaps.git
cd finemaps
./gradlew build
```

The built JAR will be in `plugin/build/libs/FineMaps-1.0.0.jar`.

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
