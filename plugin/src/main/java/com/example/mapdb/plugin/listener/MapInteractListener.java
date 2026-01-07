package com.example.mapdb.plugin.listener;

import com.example.mapdb.core.config.MapDBConfig;
import com.example.mapdb.core.manager.MapManager;
import com.example.mapdb.api.nms.NMSAdapter;
import com.example.mapdb.plugin.MapDBPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles map interaction events like holding maps and previewing.
 */
public class MapInteractListener implements Listener {

    private final MapDBPlugin plugin;
    private final MapManager mapManager;
    private final NMSAdapter nmsAdapter;
    private final MapDBConfig config;
    
    // Track active preview tasks per player
    private final Map<UUID, BukkitTask> previewTasks = new HashMap<>();
    private final Map<UUID, Integer> activeDisplays = new HashMap<>();

    public MapInteractListener(MapDBPlugin plugin) {
        this.plugin = plugin;
        this.mapManager = plugin.getMapManager();
        this.nmsAdapter = plugin.getNmsAdapter();
        this.config = plugin.getMapDBConfig();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack newItem = player.getInventory().getItem(event.getNewSlot());
        
        // Cancel any existing preview
        cancelPreview(player.getUniqueId());
        
        // Check if new item is a stored map
        if (newItem != null && mapManager.isStoredMap(newItem)) {
            long mapId = mapManager.getMapIdFromItem(newItem);
            if (mapId != -1) {
                // Send map data to player
                mapManager.sendMapToPlayer(player, mapId);
                
                // Start preview if enabled
                startPreview(player, mapId);
            }
        }
    }

    private void startPreview(Player player, long mapId) {
        if (nmsAdapter.supportsBlockDisplays() && config.getMaps().isUseBlockDisplays()) {
            // Use block display for preview (1.19.4+)
            startBlockDisplayPreview(player, mapId);
        } else if (config.getMaps().isUseParticlesLegacy()) {
            // Use particles for older versions
            startParticlePreview(player, mapId);
        }
    }

    private void startBlockDisplayPreview(Player player, long mapId) {
        // Get virtual ID for this map
        // Spawn a temporary display entity in front of the player
        // This would show the map in-world while holding it
        
        // For now, this is a placeholder - full implementation would:
        // 1. Calculate position in front of player
        // 2. Spawn ItemDisplay entity with the map
        // 3. Update position as player moves
        // 4. Remove when player switches items
    }

    private void startParticlePreview(Player player, long mapId) {
        UUID playerId = player.getUniqueId();
        
        // Create repeating task for particle outline
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Player p = plugin.getServer().getPlayer(playerId);
            if (p == null || !p.isOnline()) {
                cancelPreview(playerId);
                return;
            }
            
            // Get position in front of player
            Location loc = p.getEyeLocation().add(p.getLocation().getDirection().multiply(1.5));
            
            // Show particle outline
            nmsAdapter.showParticleOutline(p, loc);
        }, 0L, 5L); // Every 5 ticks (0.25 seconds)
        
        previewTasks.put(playerId, task);
    }

    private void cancelPreview(UUID playerId) {
        // Cancel particle task
        BukkitTask task = previewTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
        
        // Remove display entity
        Integer displayId = activeDisplays.remove(playerId);
        if (displayId != null) {
            nmsAdapter.removeDisplay(displayId);
        }
    }

    /**
     * Clean up all previews on plugin disable.
     */
    public void cleanup() {
        for (BukkitTask task : previewTasks.values()) {
            task.cancel();
        }
        previewTasks.clear();
        
        for (Integer displayId : activeDisplays.values()) {
            nmsAdapter.removeDisplay(displayId);
        }
        activeDisplays.clear();
    }
}
