package org.finetree.finemaps.plugin.recovery;

import org.finetree.finemaps.core.manager.MapManager;
import org.finetree.finemaps.core.util.FineMapsScheduler;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages pending map deliveries for players who disconnect mid-raster/download.
 * Maps are persisted to disk and delivered when the player rejoins.
 */
public class PendingMapRecovery {

    private final Plugin plugin;
    private final MapManager mapManager;
    private final File dataFile;

    /**
     * Entry representing a pending map to be delivered.
     */
    public static class PendingMap {
        public final String artName;
        public final long mapId;      // For single maps (-1 if multi-block)
        public final long groupId;    // For multi-block maps (-1 if single)
        public final int width;       // For multi-block
        public final int height;      // For multi-block
        public final long createdAt;

        public PendingMap(String artName, long mapId, long groupId, int width, int height) {
            this.artName = artName;
            this.mapId = mapId;
            this.groupId = groupId;
            this.width = width;
            this.height = height;
            this.createdAt = System.currentTimeMillis();
        }

        public PendingMap(String artName, long mapId, long groupId, int width, int height, long createdAt) {
            this.artName = artName;
            this.mapId = mapId;
            this.groupId = groupId;
            this.width = width;
            this.height = height;
            this.createdAt = createdAt;
        }

        public boolean isSingleMap() {
            return mapId > 0 && groupId <= 0;
        }

        public boolean isMultiBlockMap() {
            return groupId > 0;
        }
    }

    // In-memory queue per player UUID
    private final Map<UUID, List<PendingMap>> pendingMaps = new ConcurrentHashMap<>();

    public PendingMapRecovery(Plugin plugin, MapManager mapManager) {
        this.plugin = plugin;
        this.mapManager = mapManager;
        this.dataFile = new File(plugin.getDataFolder(), "pending_maps.yml");
        load();
    }

    /**
     * Adds a pending single map for a player.
     */
    public void addPendingSingleMap(UUID playerId, String artName, long mapId) {
        if (playerId == null || mapId <= 0) return;
        PendingMap entry = new PendingMap(artName, mapId, -1, 1, 1);
        addPending(playerId, entry);
        save();
    }

    /**
     * Adds a pending multi-block map for a player.
     */
    public void addPendingMultiBlockMap(UUID playerId, String artName, long groupId, int width, int height) {
        if (playerId == null || groupId <= 0) return;
        PendingMap entry = new PendingMap(artName, -1, groupId, width, height);
        addPending(playerId, entry);
        save();
    }

    private void addPending(UUID playerId, PendingMap entry) {
        pendingMaps.computeIfAbsent(playerId, k -> Collections.synchronizedList(new ArrayList<>())).add(entry);
    }

    /**
     * Checks if a player has pending maps and delivers them.
     * Call this when a player joins.
     */
    public void onPlayerJoin(Player player) {
        if (player == null) return;
        UUID playerId = player.getUniqueId();
        
        List<PendingMap> pending = pendingMaps.remove(playerId);
        if (pending == null || pending.isEmpty()) return;

        // Deliver maps after a short delay to ensure player is fully loaded
        FineMapsScheduler.runForEntityDelayed(plugin, player, () -> {
            if (!player.isOnline()) {
                // Player left again, re-queue
                pendingMaps.put(playerId, pending);
                save();
                return;
            }

            int recovered = 0;
            for (PendingMap entry : pending) {
                try {
                    boolean delivered = deliverMap(player, entry);
                    if (delivered) {
                        recovered++;
                    }
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING, "Failed to deliver recovered map to " + player.getName(), t);
                }
            }

            if (recovered > 0) {
                String msg = recovered == 1 
                    ? ChatColor.GREEN + "A map that was processing when you left has been recovered!"
                    : ChatColor.GREEN.toString() + recovered + " maps that were processing when you left have been recovered!";
                player.sendMessage(msg);
            }

            save();
        }, 40L); // 2 second delay
    }

    /**
     * Delivers a pending map to a player.
     * Tries to add to inventory, falls back to dropping at player's feet.
     */
    private boolean deliverMap(Player player, PendingMap entry) {
        if (entry.isSingleMap()) {
            ItemStack item = mapManager.createMapItemWithName(entry.mapId, entry.artName);
            if (item == null) {
                plugin.getLogger().warning("Failed to create map item for recovery: mapId=" + entry.mapId);
                return false;
            }
            return giveOrDrop(player, item, entry.artName);
        } else if (entry.isMultiBlockMap()) {
            // For multi-block, give the combined item
            mapManager.getMultiBlockMap(entry.groupId).thenAccept(optMap -> {
                if (!optMap.isPresent()) {
                    plugin.getLogger().warning("Failed to find multi-block map for recovery: groupId=" + entry.groupId);
                    return;
                }
                FineMapsScheduler.runForEntity(plugin, player, () -> {
                    if (!player.isOnline()) return;
                    mapManager.giveMultiBlockMapToPlayerWithName(player, entry.groupId, entry.artName);
                    // The give method handles inventory/drop automatically
                });
            });
            return true;
        }
        return false;
    }

    /**
     * Tries to add item to player inventory, drops on ground if full.
     */
    private boolean giveOrDrop(Player player, ItemStack item, String artName) {
        if (player == null || item == null) return false;

        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (!overflow.isEmpty()) {
            // Inventory full, drop on ground
            for (ItemStack drop : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            player.sendMessage(ChatColor.YELLOW + "Your inventory was full - map '" + 
                (artName != null ? artName : "recovered") + "' was dropped at your feet.");
        }
        return true;
    }

    /**
     * Saves pending maps to disk.
     */
    public void save() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            
            for (Map.Entry<UUID, List<PendingMap>> entry : pendingMaps.entrySet()) {
                String uuidStr = entry.getKey().toString();
                List<Map<String, Object>> mapsList = new ArrayList<>();
                
                for (PendingMap pm : entry.getValue()) {
                    Map<String, Object> mapData = new LinkedHashMap<>();
                    if (pm.artName != null) mapData.put("artName", pm.artName);
                    mapData.put("mapId", pm.mapId);
                    mapData.put("groupId", pm.groupId);
                    mapData.put("width", pm.width);
                    mapData.put("height", pm.height);
                    mapData.put("createdAt", pm.createdAt);
                    mapsList.add(mapData);
                }
                
                yaml.set("pending." + uuidStr, mapsList);
            }
            
            yaml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save pending maps", e);
        }
    }

    /**
     * Loads pending maps from disk.
     */
    public void load() {
        if (!dataFile.exists()) return;
        
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
            
            if (!yaml.contains("pending")) return;
            
            for (String uuidStr : yaml.getConfigurationSection("pending").getKeys(false)) {
                try {
                    UUID playerId = UUID.fromString(uuidStr);
                    List<?> mapsList = yaml.getList("pending." + uuidStr);
                    if (mapsList == null) continue;
                    
                    List<PendingMap> pending = Collections.synchronizedList(new ArrayList<>());
                    for (Object obj : mapsList) {
                        if (obj instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> mapData = (Map<String, Object>) obj;
                            
                            String artName = (String) mapData.get("artName");
                            long mapId = ((Number) mapData.getOrDefault("mapId", -1L)).longValue();
                            long groupId = ((Number) mapData.getOrDefault("groupId", -1L)).longValue();
                            int width = ((Number) mapData.getOrDefault("width", 1)).intValue();
                            int height = ((Number) mapData.getOrDefault("height", 1)).intValue();
                            long createdAt = ((Number) mapData.getOrDefault("createdAt", System.currentTimeMillis())).longValue();
                            
                            pending.add(new PendingMap(artName, mapId, groupId, width, height, createdAt));
                        }
                    }
                    
                    if (!pending.isEmpty()) {
                        pendingMaps.put(playerId, pending);
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid UUID in pending maps: " + uuidStr);
                }
            }
            
            plugin.getLogger().info("Loaded " + pendingMaps.size() + " players with pending map recoveries");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load pending maps", e);
        }
    }

    /**
     * Checks if a player has pending maps.
     */
    public boolean hasPendingMaps(UUID playerId) {
        List<PendingMap> pending = pendingMaps.get(playerId);
        return pending != null && !pending.isEmpty();
    }

    /**
     * Gets the count of pending maps for a player.
     */
    public int getPendingCount(UUID playerId) {
        List<PendingMap> pending = pendingMaps.get(playerId);
        return pending != null ? pending.size() : 0;
    }

    /**
     * Clears all pending maps (for testing/admin purposes).
     */
    public void clearAll() {
        pendingMaps.clear();
        save();
    }
}
