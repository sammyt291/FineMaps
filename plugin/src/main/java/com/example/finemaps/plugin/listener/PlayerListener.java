package com.example.finemaps.plugin.listener;

import com.example.finemaps.core.manager.MapManager;
import com.example.finemaps.core.manager.MultiBlockMapHandler;
import com.example.finemaps.plugin.FineMapsPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Handles player events for map management, preview, and placement.
 */
public class PlayerListener implements Listener {

    private final FineMapsPlugin plugin;
    private final MapManager mapManager;
    private final MultiBlockMapHandler multiBlockHandler;

    public PlayerListener(FineMapsPlugin plugin) {
        this.plugin = plugin;
        this.mapManager = plugin.getMapManager();
        this.multiBlockHandler = plugin.getMultiBlockHandler();
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
                    
                    // Also send data for multi-block maps
                    long groupId = mapManager.getGroupIdFromItem(item);
                    if (groupId > 0) {
                        mapManager.getMultiBlockMap(groupId).thenAccept(optMap -> {
                            optMap.ifPresent(multiMap -> {
                                for (com.example.finemaps.api.map.StoredMap map : multiMap.getMaps()) {
                                    mapManager.sendMapToPlayer(player, map.getId());
                                }
                            });
                        });
                    }
                }
            }
            
            // Check if currently held item is a multi-block map
            checkAndStartPreview(player);
        }, 20L); // 1 second delay
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        
        // Stop preview task
        multiBlockHandler.stopPreviewTask(player);
        
        // Clean up virtual IDs for this player
        mapManager.onPlayerQuit(player.getUniqueId());
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemHeldChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        
        // Stop current preview
        multiBlockHandler.stopPreviewTask(player);
        
        // Check if new item is a multi-block map (delayed to get actual item)
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            checkAndStartPreview(player);
        }, 1L);
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Only handle right-click actions
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        
        // Only handle main hand
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        // Check if holding a stored map (single or multi-block)
        if (item == null || !mapManager.isStoredMap(item)) {
            return;
        }
        
        // This is a stored map - try to place it using the placement system
        event.setCancelled(true);

        multiBlockHandler.tryPlaceStoredMap(player, item);
    }
    
    /**
     * Checks if player is holding a multi-block map and starts preview if so.
     *
     * @param player The player
     */
    private void checkAndStartPreview(Player player) {
        if (!player.isOnline()) return;
        
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held != null && mapManager.isStoredMap(held)) {
            // Start preview for stored map (single or multi-block)
            multiBlockHandler.startPreviewTask(player);
        }
    }
}
