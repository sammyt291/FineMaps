package com.example.finemaps.core.manager;

import com.example.finemaps.api.map.MultiBlockMap;
import com.example.finemaps.api.map.StoredMap;
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
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles multi-block map placement, breaking, and item drops.
 */
public class MultiBlockMapHandler {

    private final Plugin plugin;
    private final MapManager mapManager;
    
    // Track placed multi-block maps in the world
    // Key: "world:x:y:z" -> group ID
    private final Map<String, Long> placedMaps = new ConcurrentHashMap<>();
    
    // Track all blocks in a multi-block map
    // Key: group ID -> list of block locations
    private final Map<Long, Set<String>> groupBlocks = new ConcurrentHashMap<>();
    
    // Track preview tasks per player
    private final Map<UUID, BukkitTask> playerPreviewTasks = new ConcurrentHashMap<>();
    
    // Track preview display entity per player (for block display previews)
    private final Map<UUID, Integer> playerPreviewDisplay = new ConcurrentHashMap<>();
    
    // Track which players have preview task running
    private final Set<UUID> playersWithPreview = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    // Track last preview state per player for efficient updates
    private final Map<UUID, PreviewState> playerPreviewStates = new ConcurrentHashMap<>();
    
    // Whether to use block displays for preview (modern versions)
    private boolean useBlockDisplays = false;
    
    private final NamespacedKey groupIdKey;
    private final NamespacedKey gridPositionKey;
    private final NamespacedKey placedKey;
    private final NamespacedKey singleMapIdKey;
    
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
        
        // Check if block displays are supported (prefer them over particles)
        this.useBlockDisplays = mapManager.getNmsAdapter().supportsBlockDisplays();
        
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
        
        // Cancel any existing task first
        BukkitTask existingTask = playerPreviewTasks.get(playerId);
        if (existingTask != null) {
            existingTask.cancel();
        }
        
        if (playersWithPreview.contains(playerId)) {
            return; // Already running
        }
        
        playersWithPreview.add(playerId);
        
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                Player p = plugin.getServer().getPlayer(playerId);
                if (p == null || !p.isOnline()) {
                    stopPreviewTask(p != null ? p : player);
                    cancel();
                    return;
                }
                
                ItemStack held = p.getInventory().getItemInMainHand();
                if (held == null || !mapManager.isStoredMap(held)) {
                    clearPreview(p);
                    stopPreviewTask(p);
                    cancel();
                    return;
                }

                // Show preview outline (1x1 for single maps, WxH for multi-block items)
                long groupId = mapManager.getGroupIdFromItem(held);
                int width = groupId > 0 ? mapManager.getMultiBlockWidth(held) : 1;
                int height = groupId > 0 ? mapManager.getMultiBlockHeight(held) : 1;
                showPlacementPreview(p, held, width, height);
            }
        }.runTaskTimer(plugin, 0L, 4L); // Update every 4 ticks (0.2s)
        
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
        BukkitTask task = playerPreviewTasks.remove(playerId);
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
            showBlockDisplayPreview(player, placement, hitFace, width, height, canPlace);
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
            // Floor/ceiling placement: allow rotating by player cardinal direction.
            // We define "up" (top of the image) as the direction the player is facing:
            // - NORTH: 0°
            // - EAST: 90°
            // - SOUTH: 180°
            // - WEST: 270°
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
        // User preference: walls should be 2 blocks lower.
        startLoc.add(0, -2, 0);
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
    private void showBlockDisplayPreview(Player player, PlacementGeometry placement, BlockFace facing, int width, int height, boolean valid) {
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

        int displayId = mapManager.getNmsAdapter().spawnPreviewBlockDisplay(center, valid, scaleX, scaleY, scaleZ);
        if (displayId != -1) {
            playerPreviewDisplay.put(player.getUniqueId(), displayId);
        }
    }

    /**
     * Computes the rotation needed for item frames placed on the floor/ceiling so that
     * the image "top" points toward the desired direction.
     *
     * Assumes the base (Rotation.NONE) has the map "top" pointing NORTH.
     */
    private Rotation rotationForFloorCeiling(BlockFace desiredUp, boolean ceiling) {
        Rotation rot;
        switch (desiredUp) {
            case NORTH: rot = Rotation.NONE; break;
            case EAST: rot = Rotation.CLOCKWISE; break;
            case SOUTH: rot = Rotation.FLIPPED; break;
            case WEST: rot = Rotation.COUNTER_CLOCKWISE; break;
            default: rot = Rotation.NONE; break;
        }

        // When viewed from below (ceiling), rotation direction appears inverted.
        if (ceiling) {
            if (rot == Rotation.CLOCKWISE) return Rotation.COUNTER_CLOCKWISE;
            if (rot == Rotation.COUNTER_CLOCKWISE) return Rotation.CLOCKWISE;
        }
        return rot;
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
        
        // Calculate the start location based on the clicked position
        Location clickedLoc = hitBlock.getRelative(hitFace).getLocation();
        PlacementGeometry placement = calculatePlacementGeometry(player, clickedLoc, hitFace, width, height);
        if (placement == null) {
            player.sendMessage(ChatColor.RED + "Cannot place map here.");
            return false;
        }
        
        // Try to place the map
        boolean success = placeMultiBlockMap(groupId, placement.startLocation, hitFace, player);
        
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
        if (player == null || item == null || !mapManager.isStoredMap(item)) {
            return false;
        }

        long groupId = mapManager.getGroupIdFromItem(item);
        if (groupId > 0) {
            return tryPlaceMultiBlockMap(player, item);
        }

        long mapId = mapManager.getMapIdFromItem(item);
        if (mapId <= 0) {
            return false;
        }

        boolean success = tryPlaceSingleMap(player, mapId);
        if (success) {
            // Remove item from player's hand
            ItemStack handItem = player.getInventory().getItemInMainHand();
            if (handItem != null && handItem.isSimilar(item)) {
                if (handItem.getAmount() > 1) {
                    handItem.setAmount(handItem.getAmount() - 1);
                } else {
                    player.getInventory().setItemInMainHand(null);
                }
            }

            player.sendMessage(ChatColor.GREEN + "Map placed!");
            stopPreviewTask(player);
        }
        return success;
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

        if (hitFace == BlockFace.UP || hitFace == BlockFace.DOWN) {
            BlockFace desiredUp = getHorizontalFacing(player.getLocation().getYaw());
            Rotation rot = rotationForFloorCeiling(desiredUp, hitFace == BlockFace.DOWN);
            try {
                frame.setRotation(rot);
            } catch (Throwable ignored) {
            }
        }

        try {
            frame.setFixed(true);
        } catch (NoSuchMethodError ignored) {
        }

        ItemStack mapItem = mapManager.createMapItem(mapId);
        frame.setItem(mapItem);
        frame.setVisible(false);

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
        
        String locKey = getLocationKey(itemFrame.getLocation());
        placedMaps.put(locKey, groupId);
        groupBlocks.computeIfAbsent(groupId, k -> new HashSet<>()).add(locKey);
        
        // Store group info on the item frame entity
        PersistentDataContainer pdc = itemFrame.getPersistentDataContainer();
        pdc.set(groupIdKey, PersistentDataType.LONG, groupId);
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
        // First check PDC on the item frame itself
        PersistentDataContainer framePdc = itemFrame.getPersistentDataContainer();
        Long groupId = framePdc.get(groupIdKey, PersistentDataType.LONG);
        
        // If not on frame, check the item inside
        if (groupId == null || groupId <= 0) {
            ItemStack frameItem = itemFrame.getItem();
            if (frameItem != null) {
                groupId = mapManager.getGroupIdFromItem(frameItem);
            }
        }
        
        // If still not found, check by location in our tracking map
        if (groupId == null || groupId <= 0) {
            String locKey = getLocationKey(itemFrame.getLocation());
            groupId = placedMaps.get(locKey);
        }
        
        if (groupId == null || groupId <= 0) {
            return false; // Not a multi-block map
        }
        
        // Break all connected frames
        breakMultiBlockMap(groupId, itemFrame.getLocation(), player);
        return true;
    }

    /**
     * Breaks all item frames in a multi-block map group.
     *
     * @param groupId The group ID
     * @param originLocation The location where breaking started
     * @param player The player (for drops)
     */
    private void breakMultiBlockMap(long groupId, Location originLocation, Player player) {
        Set<String> blocks = groupBlocks.remove(groupId);
        
        World world = originLocation.getWorld();
        if (world == null) return;
        
        boolean droppedItem = false;
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
                        framesToRemove.add((ItemFrame) entity);
                    }
                }
            }
        }
        
        // Also scan nearby area for any frames with this group ID that we might have missed
        for (Entity entity : world.getNearbyEntities(originLocation, 32, 32, 32)) {
            if (entity instanceof ItemFrame) {
                ItemFrame frame = (ItemFrame) entity;
                
                // Check frame's PDC
                PersistentDataContainer framePdc = frame.getPersistentDataContainer();
                Long frameGroupId = framePdc.get(groupIdKey, PersistentDataType.LONG);
                
                if (frameGroupId != null && frameGroupId == groupId) {
                    framesToRemove.add(frame);
                    continue;
                }
                
                // Check item's PDC
                ItemStack frameItem = frame.getItem();
                if (frameItem != null) {
                    long itemGroupId = mapManager.getGroupIdFromItem(frameItem);
                    if (itemGroupId == groupId) {
                        framesToRemove.add(frame);
                    }
                }
            }
        }
        
        // Now remove all the frames
        for (ItemFrame frame : framesToRemove) {
            // Remove tracking
            String locKey = getLocationKey(frame.getLocation());
            placedMaps.remove(locKey);
            
            // Get item for potential drop
            ItemStack item = frame.getItem();
            
            // Remove the item and frame
            frame.setItem(null);
            frame.remove();
            
            // Drop only one item for the whole multi-block map
            if (!droppedItem && item != null && item.getType() != Material.AIR) {
                ItemStack dropItem = createMultiBlockDropItem(groupId);
                if (dropItem != null) {
                    world.dropItemNaturally(originLocation, dropItem);
                    droppedItem = true;
                }
            }
        }
        
        // If nothing was dropped but we should drop something
        if (!droppedItem && !framesToRemove.isEmpty()) {
            ItemStack dropItem = createMultiBlockDropItem(groupId);
            if (dropItem != null) {
                world.dropItemNaturally(originLocation, dropItem);
            }
        }
    }

    /**
     * Creates a single item to represent a multi-block map when dropped.
     *
     * @param groupId The group ID
     * @return The drop item, or null if group not found
     */
    private ItemStack createMultiBlockDropItem(long groupId) {
        Optional<MultiBlockMap> optMap = mapManager.getMultiBlockMap(groupId).join();
        if (!optMap.isPresent()) {
            return null;
        }
        
        MultiBlockMap multiMap = optMap.get();
        
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
            pdc.set(new NamespacedKey(plugin, "finemaps_width"), PersistentDataType.INTEGER, multiMap.getWidth());
            pdc.set(new NamespacedKey(plugin, "finemaps_height"), PersistentDataType.INTEGER, multiMap.getHeight());
            
            // Add display name showing dimensions
            meta.setDisplayName(ChatColor.GOLD + "Map (" + multiMap.getWidth() + "x" + multiMap.getHeight() + ")");
            
            // Add lore with info
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Multi-block map");
            lore.add(ChatColor.GRAY + "Size: " + multiMap.getWidth() + "x" + multiMap.getHeight() + " blocks");
            lore.add(ChatColor.GRAY + "Group ID: " + groupId);
            lore.add("");
            lore.add(ChatColor.YELLOW + "Look at a wall to see preview");
            lore.add(ChatColor.YELLOW + "Right-click to place");
            meta.setLore(lore);
            
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
        Long groupId = placedMaps.get(getLocationKey(location));
        return groupId != null ? groupId : -1;
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
        Optional<MultiBlockMap> optMap = mapManager.getMultiBlockMap(groupId).join();
        if (!optMap.isPresent()) {
            return false;
        }
        
        MultiBlockMap multiMap = optMap.get();
        World world = startLocation.getWorld();
        if (world == null) return false;
        
        // Calculate placement geometry based on facing direction.
        // For floor/ceiling placement, orientation is taken from the player if available.
        PlacementGeometry placement = calculatePlacementGeometry(player, startLocation, facing, multiMap.getWidth(), multiMap.getHeight());
        if (placement == null) {
            if (player != null) {
                player.sendMessage(ChatColor.RED + "Cannot place map here.");
            }
            return false;
        }
        
        // Check if all positions are valid
        for (int y = 0; y < multiMap.getHeight(); y++) {
            for (int x = 0; x < multiMap.getWidth(); x++) {
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

        for (int y = 0; y < multiMap.getHeight(); y++) {
            for (int x = 0; x < multiMap.getWidth(); x++) {
                StoredMap map = multiMap.getMapAt(x, y);
                if (map == null) continue;
                
                Location loc = placement.locationAt(x, y);
                
                // Spawn item frame
                ItemFrame frame = world.spawn(loc, ItemFrame.class);
                frame.setFacingDirection(facing);
                if (floorCeilingRotation != null) {
                    try {
                        frame.setRotation(floorCeilingRotation);
                    } catch (Throwable ignored) {
                    }
                }
                
                // Try to set fixed (prevents rotation) - may not exist on older versions
                try {
                    frame.setFixed(true);
                } catch (NoSuchMethodError ignored) {
                }
                
                // Create and set map item
                ItemStack mapItem = mapManager.createMapItem(map.getId());
                ItemMeta meta = mapItem.getItemMeta();
                if (meta != null) {
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    pdc.set(groupIdKey, PersistentDataType.LONG, groupId);
                    pdc.set(gridPositionKey, PersistentDataType.STRING, x + ":" + y);
                    mapItem.setItemMeta(meta);
                }
                frame.setItem(mapItem);
                
                // Make invisible to show just the map
                frame.setVisible(false);
                
                // Track placement
                String locKey = getLocationKey(loc);
                placedMaps.put(locKey, groupId);
                groupBlocks.computeIfAbsent(groupId, k -> new HashSet<>()).add(locKey);
                
                // Store group ID on the entity's PDC
                PersistentDataContainer framePdc = frame.getPersistentDataContainer();
                framePdc.set(groupIdKey, PersistentDataType.LONG, groupId);
                framePdc.set(gridPositionKey, PersistentDataType.STRING, x + ":" + y);
                
                // Send map data to nearby players so it renders immediately
                for (Player nearbyPlayer : nearbyPlayers) {
                    mapManager.sendMapToPlayer(nearbyPlayer, map.getId());
                }
            }
        }
        
        return true;
    }

    /**
     * Gets the right direction from the VIEWER's perspective when looking at a map.
     * 
     * When a map faces NORTH (the map is on a wall, pointing north toward the viewer),
     * the viewer is standing to the south looking north.
     * The viewer's RIGHT is WEST (not EAST).
     * 
     * This ensures that column 0 (left side of the image) appears on the left
     * side of the placed map from the viewer's perspective.
     */
    private BlockFace getRight(BlockFace facing) {
        switch (facing) {
            case NORTH: return BlockFace.WEST;  // Viewer looking north, their right is west
            case SOUTH: return BlockFace.EAST;  // Viewer looking south, their right is east
            case EAST: return BlockFace.NORTH;  // Viewer looking east, their right is north
            case WEST: return BlockFace.SOUTH;  // Viewer looking west, their right is south
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
        // Determine anchor position in grid coordinates
        // For odd width: center column (width/2 with integer division)
        // For even width: right-of-center column (width/2)
        // Both cases: bottom row (height-1)
        int anchorX = width / 2;  // Works for both odd and even
        int anchorY = height - 1; // Bottom row
        
        // Get the right direction (from viewer's perspective)
        BlockFace rightDir = getRight(facing);
        
        // Calculate the offset from the start location to the anchor
        // The placement code does: loc = startLoc + rightDir * x - (0, y, 0)
        // So: anchor = startLoc + rightDir * anchorX - (0, anchorY, 0)
        // Therefore: startLoc = anchor - rightDir * anchorX + (0, anchorY, 0)
        
        Location startLoc = clickedLoc.clone();
        
        // Move LEFT (opposite of right) by anchorX blocks
        startLoc.add(-rightDir.getModX() * anchorX, 0, -rightDir.getModZ() * anchorX);
        
        // Move UP by anchorY blocks (since placement goes down with -y)
        startLoc.add(0, anchorY, 0);
        
        return startLoc;
    }

    /**
     * Calculates the start location for floor/ceiling placement.
     *
     * The coordinate system is:
     * - x axis: rightDir
     * - y axis: downDir (horizontal, toward the bottom of the image)
     */
    private Location calculateStartLocationHorizontal(Location clickedLoc, BlockFace rightDir, BlockFace downDir, int width, int height) {
        int anchorX = width / 2;
        int anchorY = height - 1;

        Location startLoc = clickedLoc.clone();

        // Move LEFT by anchorX
        startLoc.add(-rightDir.getModX() * anchorX, 0, -rightDir.getModZ() * anchorX);

        // Move "UP" (opposite of down) by anchorY
        BlockFace upDir = downDir.getOppositeFace();
        startLoc.add(upDir.getModX() * anchorY, 0, upDir.getModZ() * anchorY);

        return startLoc;
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
        groupBlocks.clear();
    }
}
