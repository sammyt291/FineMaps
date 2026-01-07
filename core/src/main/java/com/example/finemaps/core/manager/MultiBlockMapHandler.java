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
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

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
    
    // Track preview display entities per player
    private final Map<UUID, List<Integer>> playerPreviewDisplays = new ConcurrentHashMap<>();
    
    // Track which players have preview task running
    private final Set<UUID> playersWithPreview = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    private final NamespacedKey groupIdKey;
    private final NamespacedKey gridPositionKey;
    
    // Preview particle colors
    private Object validPreviewDust;
    private Object invalidPreviewDust;
    private Particle dustParticle;

    public MultiBlockMapHandler(Plugin plugin, MapManager mapManager) {
        this.plugin = plugin;
        this.mapManager = mapManager;
        this.groupIdKey = new NamespacedKey(plugin, "finemaps_group");
        this.gridPositionKey = new NamespacedKey(plugin, "finemaps_grid");
        
        // Find the dust particle (DUST in newer versions, REDSTONE in older)
        this.dustParticle = findDustParticle();
        
        // Setup dust options for particles
        try {
            Class<?> dustOptionsClass = Class.forName("org.bukkit.Particle$DustOptions");
            this.validPreviewDust = dustOptionsClass
                .getConstructor(Color.class, float.class)
                .newInstance(Color.fromRGB(0, 255, 0), 0.5f);
            this.invalidPreviewDust = dustOptionsClass
                .getConstructor(Color.class, float.class)
                .newInstance(Color.fromRGB(255, 0, 0), 0.5f);
        } catch (Exception e) {
            // Older version without DustOptions
            this.validPreviewDust = null;
            this.invalidPreviewDust = null;
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
     * Starts the preview task for a player holding a multi-block map.
     *
     * @param player The player
     */
    public void startPreviewTask(Player player) {
        if (playersWithPreview.contains(player.getUniqueId())) {
            return; // Already running
        }
        
        playersWithPreview.add(player.getUniqueId());
        
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    stopPreviewTask(player);
                    cancel();
                    return;
                }
                
                ItemStack held = player.getInventory().getItemInMainHand();
                long groupId = mapManager.getGroupIdFromItem(held);
                
                if (groupId <= 0) {
                    clearPreview(player);
                    stopPreviewTask(player);
                    cancel();
                    return;
                }
                
                // Show preview outline
                showPlacementPreview(player, held, groupId);
            }
        }.runTaskTimer(plugin, 0L, 5L); // Update every 5 ticks (0.25s)
    }
    
    /**
     * Stops the preview task for a player.
     *
     * @param player The player
     */
    public void stopPreviewTask(Player player) {
        playersWithPreview.remove(player.getUniqueId());
        clearPreview(player);
    }
    
    /**
     * Clears preview displays for a player.
     *
     * @param player The player
     */
    private void clearPreview(Player player) {
        List<Integer> displays = playerPreviewDisplays.remove(player.getUniqueId());
        if (displays != null) {
            for (int displayId : displays) {
                mapManager.getNmsAdapter().removeDisplay(displayId);
            }
        }
    }
    
    /**
     * Shows a placement preview for the multi-block map.
     *
     * @param player The player
     * @param item The map item
     * @param groupId The group ID
     */
    private void showPlacementPreview(Player player, ItemStack item, long groupId) {
        // Ray trace to find where player is looking
        RayTraceResult result = player.rayTraceBlocks(5.0);
        if (result == null || result.getHitBlock() == null || result.getHitBlockFace() == null) {
            clearPreview(player);
            return;
        }
        
        Block hitBlock = result.getHitBlock();
        BlockFace hitFace = result.getHitBlockFace();
        
        // Only allow placement on vertical surfaces (walls)
        if (hitFace == BlockFace.UP || hitFace == BlockFace.DOWN) {
            clearPreview(player);
            return;
        }
        
        // Get dimensions from item
        int width = mapManager.getMultiBlockWidth(item);
        int height = mapManager.getMultiBlockHeight(item);
        
        // Calculate placement area
        Location startLoc = hitBlock.getRelative(hitFace).getLocation();
        BlockFace rightDir = getRight(hitFace);
        
        // Check if all positions are valid
        boolean canPlace = true;
        List<Location> previewLocations = new ArrayList<>();
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Location loc = startLoc.clone()
                    .add(rightDir.getModX() * x, -y, rightDir.getModZ() * x);
                    
                previewLocations.add(loc);
                
                Block previewBlock = loc.getBlock();
                Block behindBlock = previewBlock.getRelative(hitFace.getOppositeFace());
                
                // Check if space is air and has solid block behind
                if (!previewBlock.getType().isAir() || !behindBlock.getType().isSolid()) {
                    canPlace = false;
                }
                
                // Check for existing item frames
                for (Entity entity : loc.getWorld().getNearbyEntities(loc.add(0.5, 0.5, 0.5), 0.5, 0.5, 0.5)) {
                    if (entity instanceof ItemFrame) {
                        canPlace = false;
                        break;
                    }
                }
            }
        }
        
        // Show particle outline
        showOutlineParticles(player, previewLocations, hitFace, canPlace);
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
        Object dustOptions = valid ? validPreviewDust : invalidPreviewDust;
        
        for (Location loc : locations) {
            // Draw outline around each block position
            drawBlockOutline(player, loc, facing, dustOptions);
        }
    }
    
    /**
     * Draws particle outline around a block face.
     *
     * @param player The player
     * @param loc The location
     * @param facing The facing direction
     * @param dustOptions The dust options (color/size)
     */
    private void drawBlockOutline(Player player, Location loc, BlockFace facing, Object dustOptions) {
        World world = loc.getWorld();
        if (world == null) return;
        
        double offset = 0.01; // Slight offset from wall
        double x = loc.getBlockX();
        double y = loc.getBlockY();
        double z = loc.getBlockZ();
        
        // Calculate face offset based on facing direction
        double faceOffsetX = facing.getModX() * offset;
        double faceOffsetZ = facing.getModZ() * offset;
        
        // Draw the 4 edges of the block face
        for (double i = 0; i <= 1; i += 0.2) {
            try {
                if (dustOptions != null && dustParticle != null) {
                    // Horizontal edges
                    if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) {
                        double faceZ = facing == BlockFace.NORTH ? z : z + 1;
                        player.spawnParticle(dustParticle, x + i, y, faceZ + faceOffsetZ, 1, dustOptions);
                        player.spawnParticle(dustParticle, x + i, y + 1, faceZ + faceOffsetZ, 1, dustOptions);
                        player.spawnParticle(dustParticle, x, y + i, faceZ + faceOffsetZ, 1, dustOptions);
                        player.spawnParticle(dustParticle, x + 1, y + i, faceZ + faceOffsetZ, 1, dustOptions);
                    } else {
                        double faceX = facing == BlockFace.WEST ? x : x + 1;
                        player.spawnParticle(dustParticle, faceX + faceOffsetX, y, z + i, 1, dustOptions);
                        player.spawnParticle(dustParticle, faceX + faceOffsetX, y + 1, z + i, 1, dustOptions);
                        player.spawnParticle(dustParticle, faceX + faceOffsetX, y + i, z, 1, dustOptions);
                        player.spawnParticle(dustParticle, faceX + faceOffsetX, y + i, z + 1, 1, dustOptions);
                    }
                } else {
                    // Fallback for older versions without DustOptions
                    if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) {
                        double faceZ = facing == BlockFace.NORTH ? z : z + 1;
                        player.spawnParticle(Particle.FLAME, x + i, y, faceZ, 1, 0, 0, 0, 0);
                        player.spawnParticle(Particle.FLAME, x + i, y + 1, faceZ, 1, 0, 0, 0, 0);
                    } else {
                        double faceX = facing == BlockFace.WEST ? x : x + 1;
                        player.spawnParticle(Particle.FLAME, faceX, y, z + i, 1, 0, 0, 0, 0);
                        player.spawnParticle(Particle.FLAME, faceX, y + 1, z + i, 1, 0, 0, 0, 0);
                    }
                }
            } catch (Exception e) {
                // Particle not supported, ignore
            }
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
        
        // Only allow placement on vertical surfaces (walls)
        if (hitFace == BlockFace.UP || hitFace == BlockFace.DOWN) {
            player.sendMessage(ChatColor.RED + "Maps can only be placed on walls.");
            return false;
        }
        
        Location startLoc = hitBlock.getRelative(hitFace).getLocation();
        
        // Try to place the map
        boolean success = placeMultiBlockMap(groupId, startLoc, hitFace, player);
        
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
        PersistentDataContainer pdc = itemFrame.getPersistentDataContainer();
        Long groupId = pdc.get(groupIdKey, PersistentDataType.LONG);
        
        if (groupId == null || groupId <= 0) {
            // Check by location
            String locKey = getLocationKey(itemFrame.getLocation());
            groupId = placedMaps.get(locKey);
            
            if (groupId == null) {
                return false; // Not a multi-block map
            }
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
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        
        World world = originLocation.getWorld();
        if (world == null) return;
        
        boolean droppedItem = false;
        
        for (String locKey : blocks) {
            placedMaps.remove(locKey);
            
            Location loc = parseLocationKey(locKey, world);
            if (loc == null) continue;
            
            // Find and remove item frame at this location
            for (Entity entity : world.getNearbyEntities(loc, 0.5, 0.5, 0.5)) {
                if (entity instanceof ItemFrame) {
                    ItemFrame frame = (ItemFrame) entity;
                    
                    // Remove the item without dropping
                    ItemStack item = frame.getItem();
                    frame.setItem(null);
                    frame.remove();
                    
                    // Drop only one item for the whole multi-block map
                    if (!droppedItem && item != null && item.getType() != Material.AIR) {
                        // Create a special item representing the whole multi-block map
                        ItemStack dropItem = createMultiBlockDropItem(groupId);
                        if (dropItem != null) {
                            world.dropItemNaturally(originLocation, dropItem);
                            droppedItem = true;
                        }
                    }
                }
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
        
        // Calculate placement positions based on facing direction
        BlockFace rightDirection = getRight(facing);
        
        // Check if all positions are valid
        for (int y = 0; y < multiMap.getHeight(); y++) {
            for (int x = 0; x < multiMap.getWidth(); x++) {
                Location loc = startLocation.clone()
                    .add(rightDirection.getModX() * x, -y, rightDirection.getModZ() * x);
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
        for (int y = 0; y < multiMap.getHeight(); y++) {
            for (int x = 0; x < multiMap.getWidth(); x++) {
                StoredMap map = multiMap.getMapAt(x, y);
                if (map == null) continue;
                
                Location loc = startLocation.clone()
                    .add(rightDirection.getModX() * x, -y, rightDirection.getModZ() * x);
                
                // Spawn item frame
                ItemFrame frame = world.spawn(loc, ItemFrame.class);
                frame.setFacingDirection(facing);
                frame.setFixed(true); // Prevent rotation
                
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
                
                // Track placement
                String locKey = getLocationKey(loc);
                placedMaps.put(locKey, groupId);
                groupBlocks.computeIfAbsent(groupId, k -> new HashSet<>()).add(locKey);
                
                // Store on entity
                PersistentDataContainer framePdc = frame.getPersistentDataContainer();
                framePdc.set(groupIdKey, PersistentDataType.LONG, groupId);
                
                // Send map data to nearby players so it renders immediately
                for (Player nearbyPlayer : nearbyPlayers) {
                    mapManager.sendMapToPlayer(nearbyPlayer, map.getId());
                }
            }
        }
        
        return true;
    }

    /**
     * Gets the right direction relative to a facing direction.
     */
    private BlockFace getRight(BlockFace facing) {
        switch (facing) {
            case NORTH: return BlockFace.EAST;
            case SOUTH: return BlockFace.WEST;
            case EAST: return BlockFace.SOUTH;
            case WEST: return BlockFace.NORTH;
            default: return BlockFace.EAST;
        }
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
