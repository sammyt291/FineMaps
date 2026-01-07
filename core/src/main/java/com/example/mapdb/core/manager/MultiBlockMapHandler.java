package com.example.mapdb.core.manager;

import com.example.mapdb.api.map.MultiBlockMap;
import com.example.mapdb.api.map.StoredMap;
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
    
    private final NamespacedKey groupIdKey;
    private final NamespacedKey gridPositionKey;

    public MultiBlockMapHandler(Plugin plugin, MapManager mapManager) {
        this.plugin = plugin;
        this.mapManager = mapManager;
        this.groupIdKey = new NamespacedKey(plugin, "mapdb_group");
        this.gridPositionKey = new NamespacedKey(plugin, "mapdb_grid");
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
            // Add group info
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(groupIdKey, PersistentDataType.LONG, groupId);
            
            // Add display name showing dimensions
            meta.setDisplayName(ChatColor.GOLD + "Map (" + multiMap.getWidth() + "x" + multiMap.getHeight() + ")");
            
            // Add lore with info
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Multi-block map");
            lore.add(ChatColor.GRAY + "Size: " + multiMap.getWidth() + "x" + multiMap.getHeight() + " blocks");
            lore.add(ChatColor.GRAY + "ID: " + groupId);
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
        BlockFace downDirection = BlockFace.DOWN;
        
        // Check if all positions are valid
        for (int y = 0; y < multiMap.getHeight(); y++) {
            for (int x = 0; x < multiMap.getWidth(); x++) {
                Location loc = startLocation.clone()
                    .add(rightDirection.getModX() * x, -y, rightDirection.getModZ() * x);
                Block block = loc.getBlock().getRelative(facing.getOppositeFace());
                
                if (!block.getType().isSolid()) {
                    return false; // Need solid surface behind
                }
            }
        }
        
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
