package com.example.finemaps.plugin.listener;

import com.example.finemaps.core.manager.MapManager;
import com.example.finemaps.plugin.FineMapsPlugin;
import org.bukkit.Chunk;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;

/**
 * Handles chunk loading to send map data when maps come into view.
 * Includes loop prevention for chain loading scenarios.
 */
public class ChunkListener implements Listener {

    private final FineMapsPlugin plugin;
    private final MapManager mapManager;
    
    // Track chunks being processed to prevent loops
    private final Set<String> processingChunks = new HashSet<>();

    public ChunkListener(FineMapsPlugin plugin) {
        this.plugin = plugin;
        this.mapManager = plugin.getMapManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        String chunkKey = getChunkKey(chunk);
        
        // Prevent recursive processing
        if (!processingChunks.add(chunkKey)) {
            return; // Already processing this chunk
        }
        
        try {
            // Process chunk asynchronously to avoid blocking
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                processChunkMaps(chunk, chunkKey);
            });
        } finally {
            // Clean up after a delay to handle rapid chunk loading
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                processingChunks.remove(chunkKey);
            }, 20L);
        }
    }

    private void processChunkMaps(Chunk chunk, String chunkKey) {
        // Start chunk processing in manager (for additional loop prevention)
        if (!mapManager.startChunkProcessing(chunkKey)) {
            return;
        }
        
        try {
            // Find all item frames with stored maps in this chunk
            Set<Long> mapsToLoad = new HashSet<>();
            
            // Must access entities on main thread for some versions
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof ItemFrame) {
                        ItemFrame frame = (ItemFrame) entity;
                        ItemStack item = frame.getItem();
                        
                        if (item != null && mapManager.isStoredMap(item)) {
                            // Re-bind the existing Bukkit map id in this frame after restart.
                            try {
                                mapManager.bindMapViewToItem(item);
                            } catch (Throwable ignored) {
                            }
                            long mapId = mapManager.getMapIdFromItem(item);
                            if (mapId != -1) {
                                mapsToLoad.add(mapId);
                            }
                        }
                    }
                }
                
                // Send map data to nearby players
                if (!mapsToLoad.isEmpty()) {
                    sendMapsToNearbyPlayers(chunk, mapsToLoad);
                }
                
                // End chunk processing
                mapManager.endChunkProcessing(chunkKey);
            });
        } catch (Exception e) {
            mapManager.endChunkProcessing(chunkKey);
        }
    }

    private void sendMapsToNearbyPlayers(Chunk chunk, Set<Long> mapIds) {
        // Get players who can see this chunk
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        
        for (Player player : chunk.getWorld().getPlayers()) {
            // Check if player is within view distance
            int playerChunkX = player.getLocation().getBlockX() >> 4;
            int playerChunkZ = player.getLocation().getBlockZ() >> 4;
            
            int viewDistance = plugin.getServer().getViewDistance();
            
            if (Math.abs(playerChunkX - chunkX) <= viewDistance &&
                Math.abs(playerChunkZ - chunkZ) <= viewDistance) {
                
                // Send map data to this player
                for (long mapId : mapIds) {
                    mapManager.sendMapToPlayer(player, mapId);
                }
            }
        }
    }

    private String getChunkKey(Chunk chunk) {
        return chunk.getWorld().getName() + ":" + chunk.getX() + ":" + chunk.getZ();
    }
}
