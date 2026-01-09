package com.example.finemaps.core.manager;

import com.example.finemaps.api.map.MultiBlockMap;
import com.example.finemaps.api.map.StoredMap;
import com.example.finemaps.core.util.FineMapsScheduler;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.inventory.EquipmentSlot;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles multi-block map placement, breaking, and item drops.
 */
public class MultiBlockMapHandler {

    private final Plugin plugin;
    private final MapManager mapManager;
    
    /**
     * We must distinguish between the "art identity" (groupId) and a particular placed copy in the world.
     *
     * Multiple copies of the same art (same groupId) can be placed; breaking one should only break that copy.
     * Therefore, we track each placement by a unique instanceId.
     */
    private static final class PlacementInfo {
        final long instanceId;
        final long groupId;
        final PlacementMode mode;
        PlacementInfo(long instanceId, long groupId, PlacementMode mode) {
            this.instanceId = instanceId;
            this.groupId = groupId;
            this.mode = mode;
        }
    }

    private enum PlacementMode {
        /** FineMaps spawned the item frames; when broken we remove frames and drop only the map item. */
        SPAWNED_FRAMES((byte) 1),
        /** Player provided the item frames; when broken we clear maps and keep frames in-place. */
        EXISTING_FRAMES((byte) 2);

        final byte id;
        PlacementMode(byte id) {
            this.id = id;
        }

        static PlacementMode fromId(Byte b) {
            if (b == null) return SPAWNED_FRAMES;
            if (b == 2) return EXISTING_FRAMES;
            return SPAWNED_FRAMES;
        }
    }

    // Track placed multi-block maps in the world
    // Key: "world:x:y:z" -> placement info (instanceId + groupId + mode)
    private final Map<String, PlacementInfo> placedMaps = new ConcurrentHashMap<>();

    // Track all blocks in a multi-block placement instance
    // Key: instanceId -> set of block location keys
    private final Map<Long, Set<String>> instanceBlocks = new ConcurrentHashMap<>();
    private final Map<Long, Long> instanceToGroup = new ConcurrentHashMap<>();
    private final Map<Long, PlacementMode> instanceToMode = new ConcurrentHashMap<>();
    
    // Track preview tasks per player
    private final Map<UUID, FineMapsScheduler.Task> playerPreviewTasks = new ConcurrentHashMap<>();
    
    // Track preview display entity per player (for block display previews)
    private final Map<UUID, Integer> playerPreviewDisplay = new ConcurrentHashMap<>();
    
    // Track which players have preview task running (redundant with playerPreviewTasks, but kept for quick checks)
    private final Set<UUID> playersWithPreview = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    // Track last preview state per player for efficient updates
    private final Map<UUID, PreviewState> playerPreviewStates = new ConcurrentHashMap<>();
    
    // Whether to use block displays for preview (modern versions)
    private boolean useBlockDisplays = false;
    
    private final NamespacedKey groupIdKey;
    private final NamespacedKey gridPositionKey;
    private final NamespacedKey placedKey;
    private final NamespacedKey singleMapIdKey;
    private final NamespacedKey instanceIdKey;
    private final NamespacedKey placementModeKey;
    private final NamespacedKey widthKey;
    private final NamespacedKey heightKey;
    private final NamespacedKey rotationKey;
    
    // Preview particle colors - using Particle.DustOptions if available
    private Object validPreviewDust;
    private Object invalidPreviewDust;
    private Particle dustParticle;
    private boolean hasDustOptions = false;

    public MultiBlockMapHandler(Plugin plugin, MapManager mapManager) {
        this.plugin = plugin;
        this.mapManager = mapManager;
        this.groupIdKey = new NamespacedKey(plugin, "finemaps_group");
        this.gridPositionKey = new NamespacedKey(plugin, "finemaps_grid");
        this.placedKey = new NamespacedKey(plugin, "finemaps_placed");
        this.singleMapIdKey = new NamespacedKey(plugin, "finemaps_map");
        this.instanceIdKey = new NamespacedKey(plugin, "finemaps_instance");
        this.placementModeKey = new NamespacedKey(plugin, "finemaps_place_mode");
        this.widthKey = new NamespacedKey(plugin, "finemaps_width");
        this.heightKey = new NamespacedKey(plugin, "finemaps_height");
        this.rotationKey = new NamespacedKey(plugin, "finemaps_rotation");
        
        // Check if block displays are supported (prefer them over particles) AND enabled in config.
        boolean configAllowsBlockDisplays = true;
        try {
            if (mapManager.getConfig() != null && mapManager.getConfig().getMaps() != null) {
                configAllowsBlockDisplays = mapManager.getConfig().getMaps().isUseBlockDisplays();
            }
        } catch (Throwable ignored) {
        }
        this.useBlockDisplays = configAllowsBlockDisplays && mapManager.getNmsAdapter().supportsBlockDisplays();
        
        // Find the dust particle (DUST in newer versions, REDSTONE in older)
        this.dustParticle = findDustParticle();
        
        // Setup dust options for particles
        setupDustOptions();
    }

    /**
     * Holds state for a preview to detect when it needs to be updated.
     */
    private static class PreviewState {
        final Location location;
        final BlockFace facing;
        final boolean valid;
        final int width;
        final int height;

        PreviewState(Location location, BlockFace facing, boolean valid, int width, int height) {
            this.location = location;
            this.facing = facing;
            this.valid = valid;
            this.width = width;
            this.height = height;
        }

        boolean matches(Location loc, BlockFace face, boolean isValid, int w, int h) {
            return location != null && loc != null &&
                   location.getBlockX() == loc.getBlockX() &&
                   location.getBlockY() == loc.getBlockY() &&
                   location.getBlockZ() == loc.getBlockZ() &&
                   facing == face && valid == isValid && width == w && height == h;
        }
    }
    
    /**
     * Sets up the dust options for particle preview.
     */
    private void setupDustOptions() {
        try {
            Class<?> dustOptionsClass = Class.forName("org.bukkit.Particle$DustOptions");
            this.validPreviewDust = dustOptionsClass
                .getConstructor(Color.class, float.class)
                .newInstance(Color.fromRGB(0, 255, 0), 1.0f);
            this.invalidPreviewDust = dustOptionsClass
                .getConstructor(Color.class, float.class)
                .newInstance(Color.fromRGB(255, 0, 0), 1.0f);
            this.hasDustOptions = true;
        } catch (Exception e) {
            // Older version without DustOptions
            this.validPreviewDust = null;
            this.invalidPreviewDust = null;
            this.hasDustOptions = false;
        }
    }
    
    /**
     * Finds the dust particle type (varies by version).
     */
    private Particle findDustParticle() {
        // Try DUST first (1.20.5+), then REDSTONE (older versions)
        for (String name : new String[]{"DUST", "REDSTONE"}) {
            try {
                return Particle.valueOf(name);
            } catch (IllegalArgumentException ignored) {
            }
        }
        // Fallback to first available particle
        return Particle.values()[0];
    }
    
    /**
     * Starts the preview task for a player holding a stored map item (single or multi-block).
     *
     * @param player The player
     */
    public void startPreviewTask(Player player) {
        UUID playerId = player.getUniqueId();

        // If already running, don't restart (important: this can be called by pickup/inventory events).
        FineMapsScheduler.Task existingTask = playerPreviewTasks.get(playerId);
        if (existingTask != null) {
            playersWithPreview.add(playerId);
            return;
        }

        playersWithPreview.add(playerId);
        
        FineMapsScheduler.Task task = FineMapsScheduler.runForEntityRepeating(plugin, player, () -> {
            Player p = plugin.getServer().getPlayer(playerId);
            if (p == null || !p.isOnline()) {
                stopPreviewTask(p != null ? p : player);
                return;
            }

            // Prefer main-hand, fall back to off-hand
            ItemStack held = p.getInventory().getItemInMainHand();
            if (held == null || !mapManager.isStoredMap(held)) {
                held = p.getInventory().getItemInOffHand();
            }
            if (held == null || !mapManager.isStoredMap(held)) {
                clearPreview(p);
                stopPreviewTask(p);
                return;
            }

            // Show preview outline (1x1 for single maps, WxH for multi-block items)
            long groupId = mapManager.getGroupIdFromItem(held);
            int width = groupId > 0 ? mapManager.getMultiBlockWidth(held) : 1;
            int height = groupId > 0 ? mapManager.getMultiBlockHeight(held) : 1;
            showPlacementPreview(p, held, width, height);
        }, 0L, 4L); // Update every 4 ticks (0.2s)

        // Some schedulers (especially on Folia builds with differing APIs) may fail to schedule and return null.
        // ConcurrentHashMap does not allow null values, so treat this as "preview unavailable" and bail quietly.
        if (task == null) {
            playersWithPreview.remove(playerId);
            return;
        }

        playerPreviewTasks.put(playerId, task);
    }
    
    /**
     * Stops the preview task for a player.
     *
     * @param player The player
     */
    public void stopPreviewTask(Player player) {
        if (player == null) return;
        
        UUID playerId = player.getUniqueId();
        playersWithPreview.remove(playerId);
        
        // Cancel the task
        FineMapsScheduler.Task task = playerPreviewTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
        
        clearPreview(player);
    }
    
    /**
     * Clears preview displays for a player.
     *
     * @param player The player
     */
    private void clearPreview(Player player) {
        // Clear preview state and display
        playerPreviewStates.remove(player.getUniqueId());
        clearPreviewDisplayOnly(player);
    }
    
    /**
     * Clears only the preview display entity (keeps preview state intact).
     */
    private void clearPreviewDisplayOnly(Player player) {
        Integer displayId = playerPreviewDisplay.remove(player.getUniqueId());
        if (displayId != null) {
            mapManager.getNmsAdapter().removePreviewDisplay(displayId);
        }
    }

    /**
     * Shows a placement preview for a stored map item.
     *
     * @param player The player
     * @param item The map item
     * @param width The width in blocks
     * @param height The height in blocks
     */
    private void showPlacementPreview(Player player, ItemStack item, int width, int height) {
        // Ray trace to find where player is looking
        RayTraceResult result = player.rayTraceBlocks(5.0);
        if (result == null || result.getHitBlock() == null || result.getHitBlockFace() == null) {
            clearPreview(player);
            return;
        }
        
        Block hitBlock = result.getHitBlock();
        BlockFace hitFace = result.getHitBlockFace();
        
        // Calculate placement area (anchor is the air block the frame(s) would occupy)
        Location anchorLoc = hitBlock.getRelative(hitFace).getLocation();
        PlacementGeometry placement = calculatePlacementGeometry(player, anchorLoc, hitFace, width, height);
        if (placement == null) {
            clearPreview(player);
            return;
        }

        // Check if all positions are valid
        boolean canPlace = true;
        List<Location> previewLocations = new ArrayList<>();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Location loc = placement.locationAt(x, y);
                previewLocations.add(loc.clone());

                Block previewBlock = loc.getBlock();
                Block behindBlock = previewBlock.getRelative(hitFace.getOppositeFace());

                // Check if space is air and has solid block behind
                if (!previewBlock.getType().isAir() || !behindBlock.getType().isSolid()) {
                    canPlace = false;
                }

                // Check for existing item frames
                Location checkLoc = loc.clone().add(0.5, 0.5, 0.5);
                for (Entity entity : loc.getWorld().getNearbyEntities(checkLoc, 0.5, 0.5, 0.5)) {
                    if (entity instanceof ItemFrame) {
                        canPlace = false;
                        break;
                    }
                }
            }
        }
        
        // Check if we need to update the preview (optimization)
        PreviewState currentState = playerPreviewStates.get(player.getUniqueId());
        if (currentState != null && currentState.matches(placement.startLocation, hitFace, canPlace, width, height)) {
            // No change, skip update (but still spawn particles if not using block displays)
            if (!useBlockDisplays) {
                showOutlineParticles(player, previewLocations, hitFace, canPlace);
            }
            return;
        }
        
        // Update state
        playerPreviewStates.put(player.getUniqueId(), new PreviewState(placement.startLocation, hitFace, canPlace, width, height));
        
        // Show preview using block displays (modern) or particles (legacy)
        if (useBlockDisplays) {
            boolean ok = showBlockDisplayPreview(player, placement, hitFace, width, height, canPlace);
            if (!ok) {
                // Safety fallback: if display spawning fails (e.g., unsupported adapter or region/thread restriction on Folia),
                // still show something.
                showOutlineParticles(player, previewLocations, hitFace, canPlace);
            }
        } else {
            showOutlineParticles(player, previewLocations, hitFace, canPlace);
        }
    }

    /**
     * Geometry for multi-block placement across wall/floor/ceiling.
     */
    private static class PlacementGeometry {
        final Location startLocation;
        final BlockFace right;
        final BlockFace down; // null for wall placement (uses Y axis)
        final boolean isWall;

        private PlacementGeometry(Location startLocation, BlockFace right, BlockFace down, boolean isWall) {
            this.startLocation = startLocation;
            this.right = right;
            this.down = down;
            this.isWall = isWall;
        }

        Location locationAt(int x, int y) {
            Location loc = startLocation.clone().add(right.getModX() * x, 0, right.getModZ() * x);
            if (isWall) {
                return loc.add(0, -y, 0);
            }
            return loc.add(down.getModX() * y, 0, down.getModZ() * y);
        }
    }

    private PlacementGeometry calculatePlacementGeometry(Player player, Location anchorLoc, BlockFace facing, int width, int height) {
        if (facing == BlockFace.UP || facing == BlockFace.DOWN) {
            // Floor/ceiling placement: rotate by player cardinal direction.
            // We define "up" (top of the image) as the direction the player is facing.
            BlockFace desiredUp = (player != null)
                ? getHorizontalFacing(player.getLocation().getYaw())
                : BlockFace.NORTH;

            // Image coordinate system:
            // - x axis: rightDir (clockwise from desiredUp)
            // - y axis: downDir (opposite of desiredUp)
            BlockFace downDir = desiredUp.getOppositeFace();
            BlockFace rightDir = getRightOfPlayerFacing(desiredUp);

            // Ceiling placement is mirrored (viewer is underneath), so flip horizontal axis.
            if (facing == BlockFace.DOWN) {
                rightDir = rightDir.getOppositeFace();
            }

            Location startLoc = calculateStartLocationHorizontal(anchorLoc, rightDir, downDir, width, height);
            return new PlacementGeometry(startLoc, rightDir, downDir, false);
        }

        // Wall placement (existing behavior)
        Location startLoc = calculateStartLocation(anchorLoc, facing, width, height);
        BlockFace rightDir = getRight(facing);
        return new PlacementGeometry(startLoc, rightDir, null, true);
    }

    /**
     * Shows a preview using block display entities (modern versions).
     *
     * @param player The player
     * @param anchorLocation The anchor location the player targeted
     * @param valid Whether placement is valid
     */
    private boolean showBlockDisplayPreview(Player player, PlacementGeometry placement, BlockFace facing, int width, int height, boolean valid) {
        // Clear existing display entity (keep state so we can avoid respawns)
        clearPreviewDisplayOnly(player);

        // Spawn a single scaled block display that covers the whole multi-block area.
        Location center = placement.startLocation.clone().add(0.5, 0.5, 0.5);
        double halfW = (width - 1) / 2.0;
        double halfH = (height - 1) / 2.0;

        center.add(placement.right.getModX() * halfW, 0, placement.right.getModZ() * halfW);
        if (placement.isWall) {
            center.add(0, -halfH, 0);
        } else if (placement.down != null) {
            center.add(placement.down.getModX() * halfH, 0, placement.down.getModZ() * halfH);
        }

        final float thickness = 0.02f;
        float scaleX;
        float scaleY;
        float scaleZ;
        if (placement.isWall) {
            scaleY = height;
            if (facing == BlockFace.EAST || facing == BlockFace.WEST) {
                // Wall normal is X; map spans Z and Y
                scaleX = thickness;
                scaleZ = width;
            } else {
                // Wall normal is Z; map spans X and Y
                scaleX = width;
                scaleZ = thickness;
            }
        } else {
            // Floor/ceiling: normal is Y; map spans X/Z depending on right/down
            scaleY = thickness;
            scaleX = (float) (Math.abs(placement.right.getModX()) * width + Math.abs(placement.down.getModX()) * height);
            scaleZ = (float) (Math.abs(placement.right.getModZ()) * width + Math.abs(placement.down.getModZ()) * height);
        }

        // The anchor/start locations are in the air blocks where the frames would go.
        // The visual "surface" we want to be flush with is the block face behind that air block,
        // which is 0.5 blocks away from the air-block center. Offset back toward the supporting block,
        // leaving half the display thickness on the air side so it doesn't clip into the block.
        final double normalOffset = 0.5 - (thickness / 2.0);
        BlockFace supportFace = facing.getOppositeFace();
        center.add(
            supportFace.getModX() * normalOffset,
            supportFace.getModY() * normalOffset,
            supportFace.getModZ() * normalOffset
        );

        int displayId = mapManager.getNmsAdapter().spawnPreviewBlockDisplay(center, valid, scaleX, scaleY, scaleZ);
        if (displayId != -1) {
            playerPreviewDisplay.put(player.getUniqueId(), displayId);
            return true;
        }
        return false;
    }

    /**
     * Computes the rotation needed for item frames placed on the floor/ceiling so that
     * the image "top" points toward the desired direction.
     *
     * Notes:
     * - Bukkit exposes an 8-step Rotation enum, but maps visually rotate in 90° steps.
     * - We treat the effective rotation as (rotation.ordinal() % 4).
     * - For floor-mounted frames (facing UP), Rotation.NONE corresponds to map-top = NORTH.
     * - For ceiling-mounted frames (facing DOWN), Rotation.NONE corresponds to map-top = SOUTH
     *   when viewed from below.
     *
     * We intentionally choose the first 4 enum constants (NONE, CLOCKWISE_45, CLOCKWISE, CLOCKWISE_135)
     * to represent quarter-turns 0..3 for maps.
     */
    private Rotation rotationForFloorCeiling(BlockFace desiredUp, boolean ceiling) {
        // Normalize
        if (desiredUp == null) desiredUp = BlockFace.NORTH;

        int quarterTurns;
        if (!ceiling) {
            // FLOOR (facing UP): NONE => top=NORTH, then +1 rotates clockwise when viewed from above
            // N -> E -> S -> W
            switch (desiredUp) {
                case NORTH: quarterTurns = 0; break;
                case EAST: quarterTurns = 1; break;
                case SOUTH: quarterTurns = 2; break;
                case WEST: quarterTurns = 3; break;
                default: quarterTurns = 0; break;
            }
        } else {
            // CEILING (facing DOWN): NONE => top=SOUTH (as seen from below),
            // then +1 advances like clockwise when viewed from ABOVE: S -> E -> N -> W
            // (empirically matches vanilla map rendering in ceiling frames)
            switch (desiredUp) {
                case SOUTH: quarterTurns = 0; break;
                case EAST: quarterTurns = 1; break;
                case NORTH: quarterTurns = 2; break;
                case WEST: quarterTurns = 3; break;
                default: quarterTurns = 0; break;
            }
        }

        switch (quarterTurns & 3) {
            case 0: return Rotation.NONE;
            case 1: return Rotation.CLOCKWISE_45;   // maps: visually 90°
            case 2: return Rotation.CLOCKWISE;      // maps: visually 180°
            case 3: return Rotation.CLOCKWISE_135;  // maps: visually 270°
            default: return Rotation.NONE;
        }
    }

    private int quarterTurnsFromFrameRotation(Rotation rotation) {
        if (rotation == null) return 0;
        try {
            return rotation.ordinal() % 4;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Maps quarter-turns (0..3) to a Bukkit Rotation constant that produces the correct 90° steps for maps.
     * We intentionally use the first 4 enum constants; see {@link #rotationForFloorCeiling(BlockFace, boolean)}.
     */
    private Rotation frameRotationFromQuarterTurns(int quarterTurns) {
        switch (quarterTurns & 3) {
            case 0: return Rotation.NONE;
            case 1: return Rotation.CLOCKWISE_45;   // maps: visually 90°
            case 2: return Rotation.CLOCKWISE;      // maps: visually 180°
            case 3: return Rotation.CLOCKWISE_135;  // maps: visually 270°
            default: return Rotation.NONE;
        }
    }
    
    /**
     * Shows particle outline for placement preview.
     *
     * @param player The player
     * @param locations The locations to outline
     * @param facing The facing direction
     * @param valid Whether placement would be valid
     */
    private void showOutlineParticles(Player player, List<Location> locations, BlockFace facing, boolean valid) {
        for (Location loc : locations) {
            // Draw outline around each block position
            drawBlockOutline(player, loc, facing, valid);
        }
    }
    
    /**
     * Draws particle outline around a block face.
     *
     * @param player The player
     * @param loc The location
     * @param facing The facing direction
     * @param valid Whether placement is valid (green) or invalid (red)
     */
    private void drawBlockOutline(Player player, Location loc, BlockFace facing, boolean valid) {
        World world = loc.getWorld();
        if (world == null) return;
        
        double offset = 0.05; // Slight offset from wall
        double x = loc.getBlockX();
        double y = loc.getBlockY();
        double z = loc.getBlockZ();
        
        Object dustOptions = valid ? validPreviewDust : invalidPreviewDust;
        
        // Draw the 4 edges of the block face with particles
        double step = 0.25;
        
        try {
            for (double i = 0; i <= 1; i += step) {
                if (hasDustOptions && dustOptions != null && dustParticle != null) {
                    // Use colored dust particles
                    spawnDustParticle(player, facing, x, y, z, i, offset, dustOptions);
                } else {
                    // Fallback: use FLAME or HAPPY_VILLAGER particles
                    spawnFallbackParticle(player, facing, x, y, z, i, offset, valid);
                }
            }
        } catch (Exception e) {
            // If all else fails, try basic flame particles
            try {
                Particle flame = Particle.valueOf("FLAME");
                for (double i = 0; i <= 1; i += step) {
                    if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) {
                        double faceZ = facing == BlockFace.NORTH ? z : z + 1;
                        player.spawnParticle(flame, x + i, y, faceZ, 1, 0, 0, 0, 0);
                        player.spawnParticle(flame, x + i, y + 1, faceZ, 1, 0, 0, 0, 0);
                    } else if (facing == BlockFace.EAST || facing == BlockFace.WEST) {
                        double faceX = facing == BlockFace.WEST ? x : x + 1;
                        player.spawnParticle(flame, faceX, y, z + i, 1, 0, 0, 0, 0);
                        player.spawnParticle(flame, faceX, y + 1, z + i, 1, 0, 0, 0, 0);
                    }
                }
            } catch (Exception ignored) {
                // Give up on particles
            }
        }
    }
    
    /**
     * Spawns dust particles for the outline.
     */
    private void spawnDustParticle(Player player, BlockFace facing, double x, double y, double z, 
                                    double i, double offset, Object dustOptions) {
        if (facing == BlockFace.NORTH) {
            double faceZ = z + offset;
            player.spawnParticle(dustParticle, x + i, y, faceZ, 1, dustOptions);
            player.spawnParticle(dustParticle, x + i, y + 1, faceZ, 1, dustOptions);
            player.spawnParticle(dustParticle, x, y + i, faceZ, 1, dustOptions);
            player.spawnParticle(dustParticle, x + 1, y + i, faceZ, 1, dustOptions);
        } else if (facing == BlockFace.SOUTH) {
            double faceZ = z + 1 - offset;
            player.spawnParticle(dustParticle, x + i, y, faceZ, 1, dustOptions);
            player.spawnParticle(dustParticle, x + i, y + 1, faceZ, 1, dustOptions);
            player.spawnParticle(dustParticle, x, y + i, faceZ, 1, dustOptions);
            player.spawnParticle(dustParticle, x + 1, y + i, faceZ, 1, dustOptions);
        } else if (facing == BlockFace.WEST) {
            double faceX = x + offset;
            player.spawnParticle(dustParticle, faceX, y, z + i, 1, dustOptions);
            player.spawnParticle(dustParticle, faceX, y + 1, z + i, 1, dustOptions);
            player.spawnParticle(dustParticle, faceX, y + i, z, 1, dustOptions);
            player.spawnParticle(dustParticle, faceX, y + i, z + 1, 1, dustOptions);
        } else if (facing == BlockFace.EAST) {
            double faceX = x + 1 - offset;
            player.spawnParticle(dustParticle, faceX, y, z + i, 1, dustOptions);
            player.spawnParticle(dustParticle, faceX, y + 1, z + i, 1, dustOptions);
            player.spawnParticle(dustParticle, faceX, y + i, z, 1, dustOptions);
            player.spawnParticle(dustParticle, faceX, y + i, z + 1, 1, dustOptions);
        } else if (facing == BlockFace.UP) {
            double faceY = y + 1 - offset;
            player.spawnParticle(dustParticle, x + i, faceY, z, 1, dustOptions);
            player.spawnParticle(dustParticle, x + i, faceY, z + 1, 1, dustOptions);
            player.spawnParticle(dustParticle, x, faceY, z + i, 1, dustOptions);
            player.spawnParticle(dustParticle, x + 1, faceY, z + i, 1, dustOptions);
        } else if (facing == BlockFace.DOWN) {
            double faceY = y + offset;
            player.spawnParticle(dustParticle, x + i, faceY, z, 1, dustOptions);
            player.spawnParticle(dustParticle, x + i, faceY, z + 1, 1, dustOptions);
            player.spawnParticle(dustParticle, x, faceY, z + i, 1, dustOptions);
            player.spawnParticle(dustParticle, x + 1, faceY, z + i, 1, dustOptions);
        }
    }
    
    /**
     * Spawns fallback particles when dust options aren't available.
     */
    private void spawnFallbackParticle(Player player, BlockFace facing, double x, double y, double z,
                                        double i, double offset, boolean valid) {
        // Use different particles for valid/invalid
        Particle particle;
        try {
            particle = valid ? Particle.valueOf("HAPPY_VILLAGER") : Particle.valueOf("FLAME");
        } catch (IllegalArgumentException e) {
            particle = Particle.values()[0];
        }
        
        if (facing == BlockFace.NORTH) {
            double faceZ = z + offset;
            player.spawnParticle(particle, x + i, y, faceZ, 1, 0, 0, 0, 0);
            player.spawnParticle(particle, x + i, y + 1, faceZ, 1, 0, 0, 0, 0);
        } else if (facing == BlockFace.SOUTH) {
            double faceZ = z + 1 - offset;
            player.spawnParticle(particle, x + i, y, faceZ, 1, 0, 0, 0, 0);
            player.spawnParticle(particle, x + i, y + 1, faceZ, 1, 0, 0, 0, 0);
        } else if (facing == BlockFace.WEST) {
            double faceX = x + offset;
            player.spawnParticle(particle, faceX, y, z + i, 1, 0, 0, 0, 0);
            player.spawnParticle(particle, faceX, y + 1, z + i, 1, 0, 0, 0, 0);
        } else if (facing == BlockFace.EAST) {
            double faceX = x + 1 - offset;
            player.spawnParticle(particle, faceX, y, z + i, 1, 0, 0, 0, 0);
            player.spawnParticle(particle, faceX, y + 1, z + i, 1, 0, 0, 0, 0);
        } else if (facing == BlockFace.UP) {
            double faceY = y + 1 - offset;
            player.spawnParticle(particle, x + i, faceY, z, 1, 0, 0, 0, 0);
            player.spawnParticle(particle, x + i, faceY, z + 1, 1, 0, 0, 0, 0);
        } else if (facing == BlockFace.DOWN) {
            double faceY = y + offset;
            player.spawnParticle(particle, x + i, faceY, z, 1, 0, 0, 0, 0);
            player.spawnParticle(particle, x + i, faceY, z + 1, 1, 0, 0, 0, 0);
        }
    }
    
    /**
     * Attempts to place a multi-block map where player is looking.
     *
     * @param player The player
     * @param item The map item being placed
     * @return true if placed successfully
     */
    public boolean tryPlaceMultiBlockMap(Player player, ItemStack item) {
        long groupId = mapManager.getGroupIdFromItem(item);
        if (groupId <= 0) {
            return false;
        }
        
        // Ray trace to find where player is looking
        RayTraceResult result = player.rayTraceBlocks(5.0);
        if (result == null || result.getHitBlock() == null || result.getHitBlockFace() == null) {
            player.sendMessage(ChatColor.RED + "Look at a wall to place the map.");
            return false;
        }
        
        Block hitBlock = result.getHitBlock();
        BlockFace hitFace = result.getHitBlockFace();
        
        // Get dimensions to calculate proper start location
        int width = mapManager.getMultiBlockWidth(item);
        int height = mapManager.getMultiBlockHeight(item);
        int rotationDeg = mapManager.getMultiBlockRotationDegrees(item);
        // If old items are missing stored dimensions, fall back to DB size (and apply rotation).
        try {
            if (item.hasItemMeta() && item.getItemMeta() != null) {
                PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
                Integer storedW = pdc.get(widthKey, PersistentDataType.INTEGER);
                Integer storedH = pdc.get(heightKey, PersistentDataType.INTEGER);
                if (storedW == null || storedH == null) {
                    Optional<MultiBlockMap> optMap = mapManager.getMultiBlockMap(groupId).join();
                    if (optMap.isPresent()) {
                        int rot = normalizeRotationDegrees(rotationDeg);
                        int baseW = optMap.get().getWidth();
                        int baseH = optMap.get().getHeight();
                        if ((rot % 180) != 0) {
                            width = baseH;
                            height = baseW;
                        } else {
                            width = baseW;
                            height = baseH;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        
        // Calculate the start location based on the clicked position
        Location clickedLoc = hitBlock.getRelative(hitFace).getLocation();
        PlacementGeometry placement = calculatePlacementGeometry(player, clickedLoc, hitFace, width, height);
        if (placement == null) {
            player.sendMessage(ChatColor.RED + "Cannot place map here.");
            return false;
        }
        
        // Try to place the map
        boolean success = placeMultiBlockMap(groupId, placement.startLocation, hitFace, player, width, height, rotationDeg);
        
        if (success) {
            // Remove item from player's hand
            ItemStack handItem = player.getInventory().getItemInMainHand();
            if (handItem.getAmount() > 1) {
                handItem.setAmount(handItem.getAmount() - 1);
            } else {
                player.getInventory().setItemInMainHand(null);
            }
            
            player.sendMessage(ChatColor.GREEN + "Map placed!");
            stopPreviewTask(player);
            restartPreviewNextTick(player);
        }
        
        return success;
    }

    /**
     * Attempts to place a stored map item (single or multi-block) where player is looking.
     *
     * @param player The player
     * @param item The held item
     * @return true if placed successfully
     */
    public boolean tryPlaceStoredMap(Player player, ItemStack item) {
        return tryPlaceStoredMap(player, item, EquipmentSlot.HAND);
    }

    public boolean tryPlaceStoredMap(Player player, ItemStack item, EquipmentSlot hand) {
        if (player == null || item == null || !mapManager.isStoredMap(item)) {
            return false;
        }

        long groupId = mapManager.getGroupIdFromItem(item);
        if (groupId > 0) {
            // Multi-block placement already consumes from main hand; if item is in offhand, consume there.
            boolean success = tryPlaceMultiBlockMap(player, item);
            if (success && hand == EquipmentSlot.OFF_HAND) {
                consumeFromHand(player, item, hand);
            }
            return success;
        }

        long mapId = mapManager.getMapIdFromItem(item);
        if (mapId <= 0) {
            return false;
        }

        boolean success = tryPlaceSingleMap(player, mapId);
        if (success) {
            consumeFromHand(player, item, hand);

            player.sendMessage(ChatColor.GREEN + "Map placed!");
            stopPreviewTask(player);
            restartPreviewNextTick(player);
        }
        return success;
    }

    /**
     * Places a multi-block map into an existing grid of item frames starting from the clicked frame.
     * If there are not enough frames (or they are occupied), placement fails and nothing changes.
     *
     * Frames are left behind when the map is broken (PlacementMode.EXISTING_FRAMES).
     */
    public boolean tryPlaceMultiBlockMapIntoExistingFrames(Player player, ItemFrame clickedFrame, ItemStack item, EquipmentSlot hand) {
        if (player == null || clickedFrame == null || item == null) return false;
        long groupId = mapManager.getGroupIdFromItem(item);
        if (groupId <= 0) return false;

        ItemStack existing = clickedFrame.getItem();
        if (existing != null && existing.getType() != Material.AIR) {
            player.sendMessage(ChatColor.RED + "That item frame is not empty.");
            return false;
        }

        Optional<MultiBlockMap> optMap = mapManager.getMultiBlockMap(groupId).join();
        if (!optMap.isPresent()) {
            player.sendMessage(ChatColor.RED + "Map group not found.");
            return false;
        }
        MultiBlockMap multiMap = optMap.get();

        int width = mapManager.getMultiBlockWidth(item);
        int height = mapManager.getMultiBlockHeight(item);
        int rotationDeg = mapManager.getMultiBlockRotationDegrees(item);
        // Trust DB dimensions if the item is missing them.
        try {
            boolean missing = true;
            if (item.hasItemMeta() && item.getItemMeta() != null) {
                PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
                missing = pdc.get(widthKey, PersistentDataType.INTEGER) == null || pdc.get(heightKey, PersistentDataType.INTEGER) == null;
            }
            if (missing || width <= 0 || height <= 0) {
                int rot = normalizeRotationDegrees(rotationDeg);
                if ((rot % 180) != 0) {
                    width = multiMap.getHeight();
                    height = multiMap.getWidth();
                } else {
                    width = multiMap.getWidth();
                    height = multiMap.getHeight();
                }
            }
        } catch (Throwable ignored) {
        }

        BlockFace facing = clickedFrame.getFacing();
        Location anchor = clickedFrame.getLocation();
        PlacementGeometry placement = calculatePlacementGeometry(player, anchor, facing, width, height);
        if (placement == null) {
            player.sendMessage(ChatColor.RED + "Cannot place map here.");
            return false;
        }

        // Validate the required frames exist and are empty.
        Map<String, ItemFrame> framesByGrid = new HashMap<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Location loc = placement.locationAt(x, y);
                ItemFrame frame = findFrameAt(loc, facing);
                if (frame == null) {
                    player.sendMessage(ChatColor.RED + "Not enough item frames to place this map (" + width + "x" + height + ").");
                    return false;
                }
                ItemStack fi = frame.getItem();
                if (fi != null && fi.getType() != Material.AIR) {
                    player.sendMessage(ChatColor.RED + "An item frame in the placement area is not empty.");
                    return false;
                }
                framesByGrid.put(x + ":" + y, frame);
            }
        }

        long instanceId = newInstanceId();
        instanceToGroup.put(instanceId, groupId);
        instanceToMode.put(instanceId, PlacementMode.EXISTING_FRAMES);

        Rotation floorCeilingRotation = null;
        if (facing == BlockFace.UP || facing == BlockFace.DOWN) {
            BlockFace desiredUp = getHorizontalFacing(player.getLocation().getYaw());
            floorCeilingRotation = rotationForFloorCeiling(desiredUp, facing == BlockFace.DOWN);
        }
        int rotQt = (normalizeRotationDegrees(rotationDeg) / 90) & 3;

        World world = clickedFrame.getWorld();
        Collection<Player> nearbyPlayers = world.getNearbyEntities(clickedFrame.getLocation(), 64, 64, 64).stream()
            .filter(e -> e instanceof Player)
            .map(e -> (Player) e)
            .collect(java.util.stream.Collectors.toList());

        // Apply items to frames.
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int[] src = mapCoordsForRotation(rotationDeg, x, y, multiMap.getWidth(), multiMap.getHeight());
                StoredMap map = (src != null) ? multiMap.getMapAt(src[0], src[1]) : null;
                if (map == null) continue;

                ItemFrame frame = framesByGrid.get(x + ":" + y);
                if (frame == null) continue;

                ItemStack mapItem = mapManager.createMapItem(map.getId());
                ItemMeta meta = mapItem.getItemMeta();
                if (meta != null) {
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    pdc.set(groupIdKey, PersistentDataType.LONG, groupId);
                    pdc.set(gridPositionKey, PersistentDataType.STRING, x + ":" + y);
                    pdc.set(instanceIdKey, PersistentDataType.LONG, instanceId);
                    pdc.set(rotationKey, PersistentDataType.INTEGER, normalizeRotationDegrees(rotationDeg));
                    mapItem.setItemMeta(meta);
                }

                frame.setItem(mapItem);

                // Apply rotation after item is set (some implementations reset rotation).
                Rotation base = (facing == BlockFace.UP || facing == BlockFace.DOWN)
                    ? floorCeilingRotation
                    : Rotation.NONE;
                int baseQt = quarterTurnsFromFrameRotation(base);
                Rotation finalRot = frameRotationFromQuarterTurns(baseQt + rotQt);
                try {
                    frame.setRotation(finalRot);
                } catch (Throwable ignored) {
                }
                try {
                    frame.setRotation(finalRot);
                } catch (Throwable ignored) {
                }

                recordPlacementAt(frame, groupId, instanceId, PlacementMode.EXISTING_FRAMES, x + ":" + y, rotationDeg);

                for (Player nearbyPlayer : nearbyPlayers) {
                    mapManager.sendMapToPlayer(nearbyPlayer, map.getId());
                }
            }
        }

        // Consume one item from the correct hand.
        consumeFromHand(player, item, hand);
        stopPreviewTask(player);
        restartPreviewNextTick(player);
        player.sendMessage(ChatColor.GREEN + "Map placed into item frames!");
        return true;
    }

    private void consumeFromHand(Player player, ItemStack item, EquipmentSlot hand) {
        if (player == null) return;
        ItemStack handItem = (hand == EquipmentSlot.OFF_HAND)
            ? player.getInventory().getItemInOffHand()
            : player.getInventory().getItemInMainHand();
        if (handItem == null || !handItem.isSimilar(item)) return;

        if (handItem.getAmount() > 1) {
            handItem.setAmount(handItem.getAmount() - 1);
        } else {
            if (hand == EquipmentSlot.OFF_HAND) {
                player.getInventory().setItemInOffHand(null);
            } else {
                player.getInventory().setItemInMainHand(null);
            }
        }
    }

    private void restartPreviewNextTick(Player player) {
        if (player == null) return;
        FineMapsScheduler.runForEntityDelayed(plugin, player, () -> {
            try {
                if (!player.isOnline()) return;
                ItemStack main = player.getInventory().getItemInMainHand();
                ItemStack off = player.getInventory().getItemInOffHand();
                boolean holdingStored =
                    (main != null && mapManager.isStoredMap(main)) ||
                    (off != null && mapManager.isStoredMap(off));
                if (holdingStored) {
                    startPreviewTask(player);
                }
            } catch (Throwable ignored) {
            }
        }, 1L);
    }

    private boolean tryPlaceSingleMap(Player player, long mapId) {
        // Ray trace to find where player is looking
        RayTraceResult result = player.rayTraceBlocks(5.0);
        if (result == null || result.getHitBlock() == null || result.getHitBlockFace() == null) {
            player.sendMessage(ChatColor.RED + "Look at a block face to place the map.");
            return false;
        }

        Block hitBlock = result.getHitBlock();
        BlockFace hitFace = result.getHitBlockFace();
        Location clickedLoc = hitBlock.getRelative(hitFace).getLocation();

        PlacementGeometry placement = calculatePlacementGeometry(player, clickedLoc, hitFace, 1, 1);
        if (placement == null) {
            player.sendMessage(ChatColor.RED + "Cannot place map here.");
            return false;
        }

        World world = clickedLoc.getWorld();
        if (world == null) return false;

        Location loc = placement.locationAt(0, 0);
        Block airBlock = loc.getBlock();
        Block behindBlock = airBlock.getRelative(hitFace.getOppositeFace());

        if (!airBlock.getType().isAir()) {
            player.sendMessage(ChatColor.RED + "Not enough space to place map.");
            return false;
        }
        if (!behindBlock.getType().isSolid()) {
            player.sendMessage(ChatColor.RED + "Need a solid block behind.");
            return false;
        }
        for (Entity entity : world.getNearbyEntities(loc.clone().add(0.5, 0.5, 0.5), 0.5, 0.5, 0.5)) {
            if (entity instanceof ItemFrame) {
                player.sendMessage(ChatColor.RED + "There's already an item frame in the way.");
                return false;
            }
        }

        // Collect nearby players so map renders immediately
        Collection<Player> nearbyPlayers = world.getNearbyEntities(loc, 64, 64, 64).stream()
            .filter(e -> e instanceof Player)
            .map(e -> (Player) e)
            .collect(java.util.stream.Collectors.toList());

        // Spawn item frame
        ItemFrame frame = world.spawn(loc, ItemFrame.class);
        frame.setFacingDirection(hitFace);

        ItemStack mapItem = mapManager.createMapItem(mapId);
        frame.setItem(mapItem);
        frame.setVisible(false);

        if (hitFace == BlockFace.UP || hitFace == BlockFace.DOWN) {
            // For floor/ceiling frames, ensure rotation is applied *after* item/fixed setup,
            // since some server implementations reset rotation during initialization.
            BlockFace desiredUp = getHorizontalFacing(player.getLocation().getYaw());
            Rotation rot = rotationForFloorCeiling(desiredUp, hitFace == BlockFace.DOWN);
            try {
                frame.setRotation(rot);
            } catch (Throwable ignored) {
            }
            // And once more after fixed flag, just in case setFixed() resets rotation.
            try {
                frame.setRotation(rot);
            } catch (Throwable ignored) {
            }
        }

        // Mark as placed by FineMaps so we can break without dropping a frame item.
        PersistentDataContainer framePdc = frame.getPersistentDataContainer();
        framePdc.set(placedKey, PersistentDataType.BYTE, (byte) 1);
        framePdc.set(singleMapIdKey, PersistentDataType.LONG, mapId);

        for (Player nearbyPlayer : nearbyPlayers) {
            mapManager.sendMapToPlayer(nearbyPlayer, mapId);
        }

        return true;
    }

    /**
     * Handles breaking a FineMaps-placed single map (no item frame drops).
     *
     * @return true if handled
     */
    public boolean onPlacedSingleMapBreak(ItemFrame itemFrame, Player player) {
        if (itemFrame == null) return false;

        PersistentDataContainer pdc = itemFrame.getPersistentDataContainer();
        Byte placed = pdc.get(placedKey, PersistentDataType.BYTE);
        Long mapId = pdc.get(singleMapIdKey, PersistentDataType.LONG);
        if (placed == null || placed == 0 || mapId == null || mapId <= 0) {
            return false;
        }

        ItemStack item = itemFrame.getItem();
        if (item == null || !mapManager.isStoredMap(item) || mapManager.getGroupIdFromItem(item) > 0) {
            return false;
        }

        Location loc = itemFrame.getLocation();
        World world = loc.getWorld();
        if (world == null) return false;

        // Remove the frame and drop only the map item
        itemFrame.remove();
        ItemStack drop = item.clone();
        drop.setAmount(1);
        world.dropItemNaturally(loc, drop);
        return true;
    }

    /**
     * Called when a map item frame is placed.
     *
     * @param itemFrame The item frame
     * @param item The map item
     * @param player The player who placed it
     */
    public void onMapPlace(ItemFrame itemFrame, ItemStack item, Player player) {
        long groupId = mapManager.getGroupIdFromItem(item);
        if (groupId <= 0) {
            return; // Not a multi-block map
        }

        // This hook is for "manual placement into an existing frame".
        // Treat each frame as its own instance unless the placement system groups them.
        long instanceId = newInstanceId();
        recordPlacementAt(itemFrame, groupId, instanceId, PlacementMode.EXISTING_FRAMES, "0:0", 0);
    }

    /**
     * Called when a map item frame is broken.
     * If part of a multi-block map, breaks all connected frames and drops one item.
     *
     * @param itemFrame The item frame being broken
     * @param player The player breaking it (null if not by player)
     * @return true if this was a multi-block map and was handled
     */
    public boolean onMapBreak(ItemFrame itemFrame, Player player) {
        PersistentDataContainer framePdc = itemFrame.getPersistentDataContainer();

        // Prefer instance id (correctly distinguishes multiple copies).
        Long instanceId = framePdc.get(instanceIdKey, PersistentDataType.LONG);
        PlacementMode mode = PlacementMode.fromId(framePdc.get(placementModeKey, PersistentDataType.BYTE));

        // Determine groupId (art identity) from frame, item, or tracking.
        Long groupId = framePdc.get(groupIdKey, PersistentDataType.LONG);
        if (groupId == null || groupId <= 0) {
            ItemStack frameItem = itemFrame.getItem();
            if (frameItem != null) {
                groupId = mapManager.getGroupIdFromItem(frameItem);
                if ((instanceId == null || instanceId <= 0) && frameItem.hasItemMeta() && frameItem.getItemMeta() != null) {
                    Long itemInstance = frameItem.getItemMeta().getPersistentDataContainer().get(instanceIdKey, PersistentDataType.LONG);
                    if (itemInstance != null && itemInstance > 0) {
                        instanceId = itemInstance;
                    }
                }
            }
        }

        // Fallback: location lookup (only works for current-session tracking).
        if ((instanceId == null || instanceId <= 0) || (groupId == null || groupId <= 0)) {
            String locKey = getLocationKey(itemFrame.getLocation());
            PlacementInfo info = placedMaps.get(locKey);
            if (info != null) {
                if (instanceId == null || instanceId <= 0) instanceId = info.instanceId;
                if (groupId == null || groupId <= 0) groupId = info.groupId;
                mode = info.mode != null ? info.mode : mode;
            }
        }

        if (groupId == null || groupId <= 0) {
            return false; // Not a multi-block map
        }

        // Break only this placement instance.
        if (instanceId == null || instanceId <= 0) {
            // Backward-compat fallback (old worlds): compute a connected component near the origin frame
            // so we don't destroy all copies of the same groupId.
            breakMultiBlockMapComponentFallback(groupId, itemFrame, player);
            return true;
        }

        breakMultiBlockMapInstance(instanceId, groupId, mode, itemFrame.getLocation(), player);
        return true;
    }

    /**
     * Breaks all item frames in a multi-block placement instance.
     *
     * @param instanceId The placement instance id
     * @param groupId The art group id
     * @param mode Placement mode (spawned frames vs existing frames)
     * @param originLocation The location where breaking started
     * @param player The player (for drops)
     */
    private void breakMultiBlockMapInstance(long instanceId, long groupId, PlacementMode mode, Location originLocation, Player player) {
        Set<String> blocks = instanceBlocks.remove(instanceId);
        instanceToGroup.remove(instanceId);
        instanceToMode.remove(instanceId);

        World world = originLocation.getWorld();
        if (world == null) return;
        
        boolean droppedItem = false;
        int rotationDeg = 0;
        boolean rotationKnown = false;
        Set<ItemFrame> framesToRemove = new HashSet<>();
        
        // If we have tracked blocks, use those
        if (blocks != null && !blocks.isEmpty()) {
            for (String locKey : blocks) {
                placedMaps.remove(locKey);
                
                Location loc = parseLocationKey(locKey, world);
                if (loc == null) continue;
                
                // Find item frame at this location
                for (Entity entity : world.getNearbyEntities(loc.clone().add(0.5, 0.5, 0.5), 0.6, 0.6, 0.6)) {
                    if (entity instanceof ItemFrame) {
                        ItemFrame frame = (ItemFrame) entity;
                        // Extra safety: only consider frames that are actually in this exact block-space.
                        // Nearby-entity queries can include edge-adjacent frames depending on hitboxes.
                        Location fl = frame.getLocation();
                        if (fl.getBlockX() != loc.getBlockX() || fl.getBlockY() != loc.getBlockY() || fl.getBlockZ() != loc.getBlockZ()) {
                            continue;
                        }
                        // Critical: only handle frames that belong to this placement instance.
                        if (isFrameInPlacementInstance(frame, instanceId, groupId)) {
                            framesToRemove.add(frame);
                        }
                    }
                }
            }
        }
        
        // Also scan nearby area for any frames with this instance id that we might have missed
        // (handles server restarts where in-memory tracking is empty).
        for (Entity entity : world.getNearbyEntities(originLocation, 64, 64, 64)) {
            if (entity instanceof ItemFrame) {
                ItemFrame frame = (ItemFrame) entity;
                if (isFrameInPlacementInstance(frame, instanceId, groupId)) {
                    framesToRemove.add(frame);
                }
            }
        }
        
        // Now handle all the frames
        for (ItemFrame frame : framesToRemove) {
            // Remove tracking
            String locKey = getLocationKey(frame.getLocation());
            placedMaps.remove(locKey);
            
            // Get item for potential drop
            ItemStack item = frame.getItem();

            // Remove maps (and optionally the frames themselves)
            frame.setItem(null);
            clearFramePlacementMarkers(frame);

            if (mode == PlacementMode.SPAWNED_FRAMES) {
                frame.remove();
            }

            // Drop only one item for the whole multi-block map
            if (!droppedItem && item != null && item.getType() != Material.AIR) {
                if (!rotationKnown) {
                    rotationDeg = readRotationFromFrameOrItem(frame, item);
                    rotationKnown = true;
                }
                ItemStack dropItem = createMultiBlockDropItem(groupId, rotationDeg);
                if (dropItem != null) {
                    world.dropItemNaturally(originLocation, dropItem);
                    droppedItem = true;
                }
            }
        }
        
        // If nothing was dropped but we should drop something
        if (!droppedItem && !framesToRemove.isEmpty()) {
            ItemStack dropItem = createMultiBlockDropItem(groupId, rotationKnown ? rotationDeg : 0);
            if (dropItem != null) {
                world.dropItemNaturally(originLocation, dropItem);
            }
        }
    }

    /**
     * Returns true if the given item frame is part of the specified placement instance.
     */
    private boolean isFrameInPlacementInstance(ItemFrame frame, long instanceId, long groupId) {
        if (frame == null || instanceId <= 0 || groupId <= 0) return false;

        try {
            PersistentDataContainer framePdc = frame.getPersistentDataContainer();
            Long frameGroupId = framePdc.get(groupIdKey, PersistentDataType.LONG);
            Long frameInstanceId = framePdc.get(instanceIdKey, PersistentDataType.LONG);
            if (frameGroupId != null && frameGroupId == groupId && frameInstanceId != null && frameInstanceId == instanceId) {
                return true;
            }
        } catch (Throwable ignored) {
            // Best-effort; fall back to item meta check
        }

        ItemStack item = frame.getItem();
        if (item == null) return false;
        if (mapManager.getGroupIdFromItem(item) != groupId) return false;
        if (!item.hasItemMeta() || item.getItemMeta() == null) return false;
        Long itemInstanceId = item.getItemMeta().getPersistentDataContainer().get(instanceIdKey, PersistentDataType.LONG);
        return itemInstanceId != null && itemInstanceId == instanceId;
    }

    /**
     * Creates a single item to represent a multi-block map when dropped.
     *
     * @param groupId The group ID
     * @return The drop item, or null if group not found
     */
    private ItemStack createMultiBlockDropItem(long groupId, int rotationDeg) {
        Optional<MultiBlockMap> optMap = mapManager.getMultiBlockMap(groupId).join();
        if (!optMap.isPresent()) {
            return null;
        }
        
        MultiBlockMap multiMap = optMap.get();
        int rot = normalizeRotationDegrees(rotationDeg);
        int effectiveW = multiMap.getWidth();
        int effectiveH = multiMap.getHeight();
        if ((rot % 180) != 0) {
            effectiveW = multiMap.getHeight();
            effectiveH = multiMap.getWidth();
        }
        
        // Use the first map in the group as the display item
        if (multiMap.getMaps().isEmpty()) {
            return null;
        }
        
        StoredMap firstMap = multiMap.getMapAt(0, 0);
        if (firstMap == null) {
            firstMap = multiMap.getMaps().get(0);
        }
        
        ItemStack item = mapManager.createMapItem(firstMap.getId());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // Add group info and dimensions
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(groupIdKey, PersistentDataType.LONG, groupId);
            pdc.set(widthKey, PersistentDataType.INTEGER, effectiveW);
            pdc.set(heightKey, PersistentDataType.INTEGER, effectiveH);
            pdc.set(rotationKey, PersistentDataType.INTEGER, rot);
            
            // Add display name showing art name (size stays in lore)
            String artName = multiMap.getMetadata();
            if (artName != null && !artName.isEmpty()) {
                meta.setDisplayName(ChatColor.GOLD + "Map: " + artName);
            } else {
                meta.setDisplayName(ChatColor.GOLD + "Map");
            }
            
            // Add lore with size only (plus usage hints)
            mapManager.applyMultiBlockLore(meta, effectiveW, effectiveH, rot, true);
            
            item.setItemMeta(meta);
        }
        
        return item;
    }

    /**
     * Checks if a location is part of a placed multi-block map.
     *
     * @param location The location
     * @return true if part of a multi-block map
     */
    public boolean isPartOfMultiBlockMap(Location location) {
        return placedMaps.containsKey(getLocationKey(location));
    }

    /**
     * Gets the group ID at a location.
     *
     * @param location The location
     * @return The group ID, or -1 if not found
     */
    public long getGroupIdAt(Location location) {
        PlacementInfo info = placedMaps.get(getLocationKey(location));
        return info != null ? info.groupId : -1;
    }

    /**
     * Places a multi-block map starting at a location.
     *
     * @param groupId The group ID
     * @param startLocation The starting location
     * @param facing The direction the maps face
     * @param player The player placing
     * @return true if placed successfully
     */
    public boolean placeMultiBlockMap(long groupId, Location startLocation, BlockFace facing, Player player) {
        return placeMultiBlockMap(groupId, startLocation, facing, player, -1, -1, 0);
    }

    public boolean placeMultiBlockMap(long groupId, Location startLocation, BlockFace facing, Player player,
                                     int effectiveWidth, int effectiveHeight, int rotationDeg) {
        Optional<MultiBlockMap> optMap = mapManager.getMultiBlockMap(groupId).join();
        if (!optMap.isPresent()) {
            return false;
        }
        
        MultiBlockMap multiMap = optMap.get();
        World world = startLocation.getWorld();
        if (world == null) return false;

        int rot = normalizeRotationDegrees(rotationDeg);
        int w = effectiveWidth > 0 ? effectiveWidth : multiMap.getWidth();
        int h = effectiveHeight > 0 ? effectiveHeight : multiMap.getHeight();
        
        // `startLocation` is already the computed top-left for the placement.
        // Do NOT recompute it again (that causes preview/placement mismatches).
        PlacementGeometry placement;
        if (facing == BlockFace.UP || facing == BlockFace.DOWN) {
            BlockFace desiredUp = (player != null)
                ? getHorizontalFacing(player.getLocation().getYaw())
                : BlockFace.NORTH;
            BlockFace downDir = desiredUp.getOppositeFace();
            BlockFace rightDir = getRightOfPlayerFacing(desiredUp);
            if (facing == BlockFace.DOWN) rightDir = rightDir.getOppositeFace();
            placement = new PlacementGeometry(startLocation, rightDir, downDir, false);
        } else {
            placement = new PlacementGeometry(startLocation, getRight(facing), null, true);
        }
        
        // Check if all positions are valid
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                Location loc = placement.locationAt(x, y);
                Block airBlock = loc.getBlock();
                Block behindBlock = airBlock.getRelative(facing.getOppositeFace());
                
                // Check if space is air and has solid block behind
                if (!airBlock.getType().isAir()) {
                    if (player != null) {
                        player.sendMessage(ChatColor.RED + "Not enough space to place map.");
                    }
                    return false;
                }
                if (!behindBlock.getType().isSolid()) {
                    if (player != null) {
                        player.sendMessage(ChatColor.RED + "Need solid wall behind all map positions.");
                    }
                    return false;
                }
                
                // Check for existing item frames
                for (Entity entity : world.getNearbyEntities(loc.clone().add(0.5, 0.5, 0.5), 0.5, 0.5, 0.5)) {
                    if (entity instanceof ItemFrame) {
                        if (player != null) {
                            player.sendMessage(ChatColor.RED + "There's already an item frame in the way.");
                        }
                        return false;
                    }
                }
            }
        }
        
        // Collect all nearby players to send map data to
        Collection<Player> nearbyPlayers = world.getNearbyEntities(startLocation, 64, 64, 64).stream()
            .filter(e -> e instanceof Player)
            .map(e -> (Player) e)
            .collect(java.util.stream.Collectors.toList());
        
        // Place all item frames
        Rotation floorCeilingRotation = null;
        if (facing == BlockFace.UP || facing == BlockFace.DOWN) {
            // Keep a consistent world orientation (north-up) for floor/ceiling frames.
            // When viewed from below (ceiling), the rotation direction appears inverted.
            BlockFace desiredUp = (player != null)
                ? getHorizontalFacing(player.getLocation().getYaw())
                : BlockFace.NORTH;
            floorCeilingRotation = rotationForFloorCeiling(desiredUp, facing == BlockFace.DOWN);
        }
        int rotQt = (normalizeRotationDegrees(rot) / 90) & 3;

        long instanceId = newInstanceId();
        instanceToGroup.put(instanceId, groupId);
        instanceToMode.put(instanceId, PlacementMode.SPAWNED_FRAMES);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int[] src = mapCoordsForRotation(rot, x, y, multiMap.getWidth(), multiMap.getHeight());
                StoredMap map = (src != null) ? multiMap.getMapAt(src[0], src[1]) : null;
                if (map == null) continue;
                
                Location loc = placement.locationAt(x, y);
                
                // Spawn item frame
                ItemFrame frame = world.spawn(loc, ItemFrame.class);
                frame.setFacingDirection(facing);
                
                // Create and set map item
                ItemStack mapItem = mapManager.createMapItem(map.getId());
                ItemMeta meta = mapItem.getItemMeta();
                if (meta != null) {
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    pdc.set(groupIdKey, PersistentDataType.LONG, groupId);
                    pdc.set(gridPositionKey, PersistentDataType.STRING, x + ":" + y);
                    pdc.set(instanceIdKey, PersistentDataType.LONG, instanceId);
                    pdc.set(rotationKey, PersistentDataType.INTEGER, rot);
                    mapItem.setItemMeta(meta);
                }
                frame.setItem(mapItem);
                
                // Make invisible to show just the map
                frame.setVisible(false);

                // Apply final rotation last (some implementations reset it when setting item/fixed).
                Rotation base = (facing == BlockFace.UP || facing == BlockFace.DOWN)
                    ? floorCeilingRotation
                    : Rotation.NONE;
                int baseQt = quarterTurnsFromFrameRotation(base);
                Rotation finalRot = frameRotationFromQuarterTurns(baseQt + rotQt);
                try {
                    frame.setRotation(finalRot);
                } catch (Throwable ignored) {
                }
                try {
                    frame.setRotation(finalRot);
                } catch (Throwable ignored) {
                }
                
                // Store placement markers on the entity PDC and track placement
                PersistentDataContainer framePdc = frame.getPersistentDataContainer();
                recordPlacementAt(frame, groupId, instanceId, PlacementMode.SPAWNED_FRAMES, x + ":" + y, rot);
                
                // Send map data to nearby players so it renders immediately
                for (Player nearbyPlayer : nearbyPlayers) {
                    mapManager.sendMapToPlayer(nearbyPlayer, map.getId());
                }
            }
        }
        
        return true;
    }

    /**
     * Gets the direction we advance X+ for wall placement.
     * 
     * Note: Map rendering in item frames effectively mirrors left/right on walls across versions,
     * and this mirroring is not consistent across all facings (N/S vs E/W).
     * 
     * Empirically (client rendering of maps-in-frames):
     * - For NORTH/SOUTH-facing frames, advancing X+ must follow the viewer's RIGHT.
     * - For EAST/WEST-facing frames, advancing X+ must follow the viewer's LEFT.
     * 
     * This keeps the stored tile grid (x=0..w-1 from the source image) in visual left-to-right order.
     */
    private BlockFace getRight(BlockFace facing) {
        switch (facing) {
            // NORTH/SOUTH: X+ = viewerRight
            case NORTH: return BlockFace.WEST;  // Viewer looks NORTH, right is WEST
            case SOUTH: return BlockFace.EAST;  // Viewer looks SOUTH, right is EAST

            // EAST/WEST: X+ = viewerLeft
            case EAST: return BlockFace.NORTH;  // Viewer looks EAST, left is NORTH
            case WEST: return BlockFace.SOUTH;  // Viewer looks WEST, left is SOUTH
            default: return BlockFace.WEST;
        }
    }

    /**
     * Gets the cardinal horizontal facing (N/E/S/W) from a yaw value.
     */
    private BlockFace getHorizontalFacing(float yaw) {
        // Normalize yaw to [0, 360)
        float y = yaw % 360.0f;
        if (y < 0) y += 360.0f;

        // Minecraft yaw: 0 = South, 90 = West, 180 = North, 270 = East
        if (y >= 315 || y < 45) return BlockFace.SOUTH;
        if (y < 135) return BlockFace.WEST;
        if (y < 225) return BlockFace.NORTH;
        return BlockFace.EAST;
    }

    /**
     * Gets the player's RIGHT direction given their facing direction.
     */
    private BlockFace getRightOfPlayerFacing(BlockFace facing) {
        switch (facing) {
            case NORTH: return BlockFace.EAST;
            case SOUTH: return BlockFace.WEST;
            case EAST: return BlockFace.SOUTH;
            case WEST: return BlockFace.NORTH;
            default: return BlockFace.EAST;
        }
    }

    /**
     * Calculates the top-left start location for placement based on where the player clicked.
     * 
     * The player clicks where they want the anchor to be:
     * - For odd width: bottom-middle block (center of bottom row)
     * - For even width: bottom-right of center (right-of-center in bottom row)
     * 
     * This method calculates where the top-left corner should be.
     *
     * @param clickedLoc The location the player clicked (where they want the anchor)
     * @param facing The direction the map will face
     * @param width The width of the multi-block map
     * @param height The height of the multi-block map
     * @return The top-left start location for placement
     */
    private Location calculateStartLocation(Location clickedLoc, BlockFace facing, int width, int height) {
        // Anchor the placement at the player's cursor location:
        // the block they are targeting is the BOTTOM-LEFT tile of the image.
        //
        // For wall placement, y increases downward in `locationAt(x, y)` (via -y).
        // Bottom-left is (x=0, y=height-1), so:
        // anchor = startLoc - (0, height-1, 0)  =>  startLoc = anchor + (0, height-1, 0)
        return clickedLoc.clone().add(0, height - 1, 0);
    }

    /**
     * Calculates the start location for floor/ceiling placement.
     *
     * The coordinate system is:
     * - x axis: rightDir
     * - y axis: downDir (horizontal, toward the bottom of the image)
     */
    private Location calculateStartLocationHorizontal(Location clickedLoc, BlockFace rightDir, BlockFace downDir, int width, int height) {
        // Anchor the placement at the player's cursor location:
        // the block they are targeting is the BOTTOM-LEFT tile of the image.
        //
        // For floor/ceiling placement, bottom-left is (x=0, y=height-1) in our (right, down) axes:
        // anchor = startLoc + downDir*(height-1)  =>  startLoc = anchor - downDir*(height-1)
        BlockFace upDir = downDir.getOppositeFace();
        return clickedLoc.clone().add(upDir.getModX() * (height - 1), 0, upDir.getModZ() * (height - 1));
    }

    /**
     * Creates a location key string.
     */
    private String getLocationKey(Location loc) {
        return loc.getWorld().getName() + ":" + 
               loc.getBlockX() + ":" + 
               loc.getBlockY() + ":" + 
               loc.getBlockZ();
    }

    /**
     * Parses a location key back to a Location.
     */
    private Location parseLocationKey(String key, World defaultWorld) {
        String[] parts = key.split(":");
        if (parts.length != 4) return null;
        
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) world = defaultWorld;
        
        try {
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return new Location(world, x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Saves placement data to disk.
     */
    public void save() {
        // In a full implementation, this would persist the placement data
        // For now, tracked data is in-memory only
    }

    /**
     * Loads placement data from disk.
     */
    public void load() {
        // In a full implementation, this would load persisted data
    }

    /**
     * Clears all tracking data.
     */
    public void clear() {
        placedMaps.clear();
        instanceBlocks.clear();
        instanceToGroup.clear();
        instanceToMode.clear();
    }

    private long newInstanceId() {
        long id;
        do {
            id = java.util.concurrent.ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
        } while (id <= 0);
        return id;
    }

    private void recordPlacementAt(ItemFrame frame, long groupId, long instanceId, PlacementMode mode, String grid, int rotationDeg) {
        if (frame == null) return;
        Location loc = frame.getLocation();
        String locKey = getLocationKey(loc);
        placedMaps.put(locKey, new PlacementInfo(instanceId, groupId, mode));
        instanceBlocks.computeIfAbsent(instanceId, k -> new HashSet<>()).add(locKey);
        instanceToGroup.put(instanceId, groupId);
        instanceToMode.put(instanceId, mode);

        PersistentDataContainer framePdc = frame.getPersistentDataContainer();
        framePdc.set(groupIdKey, PersistentDataType.LONG, groupId);
        framePdc.set(instanceIdKey, PersistentDataType.LONG, instanceId);
        framePdc.set(placementModeKey, PersistentDataType.BYTE, mode.id);
        framePdc.set(rotationKey, PersistentDataType.INTEGER, normalizeRotationDegrees(rotationDeg));
        if (grid != null) {
            framePdc.set(gridPositionKey, PersistentDataType.STRING, grid);
        }
    }

    private void clearFramePlacementMarkers(ItemFrame frame) {
        if (frame == null) return;
        try {
            PersistentDataContainer pdc = frame.getPersistentDataContainer();
            pdc.remove(groupIdKey);
            pdc.remove(gridPositionKey);
            pdc.remove(instanceIdKey);
            pdc.remove(placementModeKey);
            pdc.remove(rotationKey);
        } catch (Throwable ignored) {
        }
    }

    private int readRotationFromFrameOrItem(ItemFrame frame, ItemStack item) {
        try {
            if (frame != null) {
                PersistentDataContainer fpdc = frame.getPersistentDataContainer();
                Integer r = fpdc.get(rotationKey, PersistentDataType.INTEGER);
                if (r != null) return normalizeRotationDegrees(r);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (item != null && item.hasItemMeta() && item.getItemMeta() != null) {
                Integer r = item.getItemMeta().getPersistentDataContainer().get(rotationKey, PersistentDataType.INTEGER);
                if (r != null) return normalizeRotationDegrees(r);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * Maps an (x,y) position in the rotated view back to the original (x,y) in the stored multi-map.
     * Rotation is clockwise in degrees (0/90/180/270).
     */
    private int[] mapCoordsForRotation(int rotationDeg, int x, int y, int originalW, int originalH) {
        int rot = normalizeRotationDegrees(rotationDeg);
        if (originalW <= 0 || originalH <= 0) return null;
        switch (rot) {
            case 0:
                return new int[]{x, y};
            case 90:
                // newW=H, newH=W
                return new int[]{y, originalH - 1 - x};
            case 180:
                return new int[]{originalW - 1 - x, originalH - 1 - y};
            case 270:
                // newW=H, newH=W
                return new int[]{originalW - 1 - y, x};
            default:
                return new int[]{x, y};
        }
    }

    private int normalizeRotationDegrees(int deg) {
        int d = deg % 360;
        if (d < 0) d += 360;
        d = ((d + 45) / 90) * 90;
        d = d % 360;
        return d;
    }

    /**
     * Backward-compat: old placements used only groupId, so multiple copies could not be distinguished.
     * We compute a connected component starting from the broken frame based on adjacency + facing.
     */
    private void breakMultiBlockMapComponentFallback(long groupId, ItemFrame origin, Player player) {
        if (origin == null) return;
        World world = origin.getWorld();
        if (world == null) return;
        BlockFace facing = origin.getFacing();

        // BFS in block-space across adjacent frames that match groupId + facing.
        Set<String> visited = new HashSet<>();
        ArrayDeque<Location> queue = new ArrayDeque<>();
        Location originLoc = origin.getLocation();
        queue.add(originLoc);

        while (!queue.isEmpty()) {
            Location loc = queue.poll();
            String key = getLocationKey(loc);
            if (!visited.add(key)) continue;

            // Explore 4-neighborhood in the plane (X/Z and Y for walls, X/Z for floor/ceiling).
            for (Location n : neighborsInPlane(loc, facing)) {
                String nk = getLocationKey(n);
                if (visited.contains(nk)) continue;
                ItemFrame frame = findFrameAt(n, facing);
                if (frame == null) continue;
                ItemStack item = frame.getItem();
                if (item == null || mapManager.getGroupIdFromItem(item) != groupId) continue;
                queue.add(n);
            }
        }

        // Create a temporary instance and break it like a spawned-frame placement (historical behavior).
        long instanceId = newInstanceId();
        for (String key : visited) {
            placedMaps.put(key, new PlacementInfo(instanceId, groupId, PlacementMode.SPAWNED_FRAMES));
        }
        instanceBlocks.put(instanceId, visited);
        breakMultiBlockMapInstance(instanceId, groupId, PlacementMode.SPAWNED_FRAMES, originLoc, player);
    }

    private List<Location> neighborsInPlane(Location loc, BlockFace facing) {
        List<Location> out = new ArrayList<>(4);
        if (loc == null) return out;
        if (facing == BlockFace.UP || facing == BlockFace.DOWN) {
            // Floor/ceiling frames: plane is X/Z
            out.add(loc.clone().add(1, 0, 0));
            out.add(loc.clone().add(-1, 0, 0));
            out.add(loc.clone().add(0, 0, 1));
            out.add(loc.clone().add(0, 0, -1));
            return out;
        }
        // Wall frames: plane is X/Y or Z/Y depending on facing
        out.add(loc.clone().add(0, 1, 0));
        out.add(loc.clone().add(0, -1, 0));
        out.add(loc.clone().add(1, 0, 0));
        out.add(loc.clone().add(-1, 0, 0));
        out.add(loc.clone().add(0, 0, 1));
        out.add(loc.clone().add(0, 0, -1));
        return out;
    }

    private ItemFrame findFrameAt(Location loc, BlockFace facing) {
        if (loc == null || loc.getWorld() == null) return null;
        for (Entity entity : loc.getWorld().getNearbyEntities(loc.clone().add(0.5, 0.5, 0.5), 0.6, 0.6, 0.6)) {
            if (!(entity instanceof ItemFrame)) continue;
            ItemFrame frame = (ItemFrame) entity;
            Location fl = frame.getLocation();
            if (fl.getBlockX() != loc.getBlockX() || fl.getBlockY() != loc.getBlockY() || fl.getBlockZ() != loc.getBlockZ()) continue;
            if (facing != null && frame.getFacing() != facing) continue;
            return frame;
        }
        return null;
    }
}
