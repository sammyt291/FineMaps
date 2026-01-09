package org.finetree.finemaps.plugin.listener;

import org.finetree.finemaps.core.manager.MapManager;
import org.finetree.finemaps.core.util.FineMapsScheduler;
import org.finetree.finemaps.plugin.FineMapsPlugin;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Handles chunk loading to send map data when maps come into view.
 * Includes loop prevention for chain loading scenarios.
 */
public class ChunkListener implements Listener {

    private final FineMapsPlugin plugin;
    private final MapManager mapManager;
    private final NamespacedKey placedKey;
    private final NamespacedKey groupIdKey;
    
    // Track chunks being processed to prevent loops
    private final Set<String> processingChunks = new HashSet<>();

    public ChunkListener(FineMapsPlugin plugin) {
        this.plugin = plugin;
        this.mapManager = plugin.getMapManager();
        this.placedKey = new NamespacedKey(plugin, "finemaps_placed");
        this.groupIdKey = new NamespacedKey(plugin, "finemaps_group");
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
            // On Folia, BukkitScheduler is unsupported; also chunk/entity access must remain on the region thread.
            // Process immediately on the calling thread (region thread on Folia, main thread elsewhere).
            processChunkMaps(chunk, chunkKey);
        } finally {
            // Clean up after a delay to handle rapid chunk loading
            FineMapsScheduler.runLaterWallClock(() -> processingChunks.remove(chunkKey), 1L, TimeUnit.SECONDS);
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
            Set<Player> playersInChunk = new HashSet<>();

            for (Entity entity : chunk.getEntities()) {
                if (entity instanceof Player) {
                    playersInChunk.add((Player) entity);
                    continue;
                }

                if (entity instanceof ItemFrame) {
                    ItemFrame frame = (ItemFrame) entity;
                    ItemStack item = frame.getItem();

                    if (item != null && mapManager.isStoredMap(item)) {
                        // Migration/repair: older FineMaps versions marked spawned frames as "fixed",
                        // which makes them unbreakable (players can't pick the map back up).
                        // If this looks like a FineMaps-managed frame, attempt to unfix it.
                        tryUnfixFrame(frame);

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

            if (!mapsToLoad.isEmpty() && !playersInChunk.isEmpty()) {
                for (Player player : playersInChunk) {
                    // Schedule on the player's entity scheduler on Folia, fallback otherwise.
                    FineMapsScheduler.runForEntity(plugin, player, () -> {
                        for (long mapId : mapsToLoad) {
                            mapManager.sendMapToPlayer(player, mapId);
                        }
                    });
                }
            }
        } finally {
            mapManager.endChunkProcessing(chunkKey);
        }
    }

    private String getChunkKey(Chunk chunk) {
        return chunk.getWorld().getName() + ":" + chunk.getX() + ":" + chunk.getZ();
    }

    private void tryUnfixFrame(ItemFrame frame) {
        if (frame == null) return;
        try {
            PersistentDataContainer pdc = frame.getPersistentDataContainer();
            boolean fineMapsManaged =
                (pdc.get(placedKey, PersistentDataType.BYTE) != null) ||
                (pdc.get(groupIdKey, PersistentDataType.LONG) != null);
            if (!fineMapsManaged) return;

            // ItemFrame#setFixed(boolean) exists on modern APIs only; use reflection for compatibility.
            frame.getClass().getMethod("setFixed", boolean.class).invoke(frame, false);
        } catch (Throwable ignored) {
        }
    }
}
