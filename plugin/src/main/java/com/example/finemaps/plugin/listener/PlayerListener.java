package com.example.finemaps.plugin.listener;

import com.example.finemaps.core.manager.MapManager;
import com.example.finemaps.core.manager.MultiBlockMapHandler;
import com.example.finemaps.plugin.FineMapsPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
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

    /**
     * If a stored map item is picked up into the currently-held slot, Bukkit does not fire
     * {@link PlayerItemHeldEvent}. We re-check the held items after pickup to start the preview immediately.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();

        ItemStack stack = event.getItem() != null ? event.getItem().getItemStack() : null;
        if (stack == null || !mapManager.isStoredMap(stack)) {
            return;
        }

        // Delay 1 tick so inventory changes are applied.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> checkAndStartPreview(player), 1L);
    }

    /**
     * Starting preview when maps are swapped between hands.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        // Delay 1 tick so the swap is applied.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> checkAndStartPreview(player), 1L);
    }

    /**
     * Covers inventory-click moves that place a stored map into the active slot without changing the held slot index.
     * We only react when a stored map is involved to keep this lightweight.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();

        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        boolean involvesStoredMap =
            (cursor != null && mapManager.isStoredMap(cursor)) ||
            (current != null && mapManager.isStoredMap(current));
        if (!involvesStoredMap) {
            return;
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> checkAndStartPreview(player), 1L);
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Only handle right-click actions
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        
        // Only handle hands (ignore physical, etc.)
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) {
            return;
        }
        
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null) {
            item = (event.getHand() == EquipmentSlot.OFF_HAND)
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        }

        // Check if holding a stored map (single or multi-block)
        if (item == null || !mapManager.isStoredMap(item)) {
            return;
        }
        
        // This is a stored map - try to place it using the placement system
        event.setCancelled(true);

        multiBlockHandler.tryPlaceStoredMap(player, item, event.getHand());
    }
    
    /**
     * Checks if player is holding a multi-block map and starts preview if so.
     *
     * @param player The player
     */
    private void checkAndStartPreview(Player player) {
        if (!player.isOnline()) return;
        
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        ItemStack held = (main != null && mapManager.isStoredMap(main)) ? main :
                         (off != null && mapManager.isStoredMap(off)) ? off : null;
        if (held != null) {
            // Start preview for stored map (single or multi-block)
            multiBlockHandler.startPreviewTask(player);
        }
    }
}
