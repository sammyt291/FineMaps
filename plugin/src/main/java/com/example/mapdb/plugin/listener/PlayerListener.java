package com.example.mapdb.plugin.listener;

import com.example.mapdb.core.manager.MapManager;
import com.example.mapdb.plugin.MapDBPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Handles player join/quit events for virtual ID management.
 */
public class PlayerListener implements Listener {

    private final MapDBPlugin plugin;
    private final MapManager mapManager;

    public PlayerListener(MapDBPlugin plugin) {
        this.plugin = plugin;
        this.mapManager = plugin.getMapManager();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Schedule delayed task to send map data for held maps
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            
            // Check inventory for stored maps and send their data
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && mapManager.isStoredMap(item)) {
                    long mapId = mapManager.getMapIdFromItem(item);
                    if (mapId != -1) {
                        mapManager.sendMapToPlayer(player, mapId);
                    }
                }
            }
        }, 20L); // 1 second delay
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up virtual IDs for this player
        mapManager.onPlayerQuit(event.getPlayer().getUniqueId());
    }
}
