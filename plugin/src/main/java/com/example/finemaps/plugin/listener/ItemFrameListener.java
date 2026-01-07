package com.example.finemaps.plugin.listener;

import com.example.finemaps.core.manager.MapManager;
import com.example.finemaps.core.manager.MultiBlockMapHandler;
import com.example.finemaps.plugin.FineMapsPlugin;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Handles item frame events for multi-block map management.
 */
public class ItemFrameListener implements Listener {

    private final FineMapsPlugin plugin;
    private final MapManager mapManager;
    private final MultiBlockMapHandler multiBlockHandler;

    public ItemFrameListener(FineMapsPlugin plugin) {
        this.plugin = plugin;
        this.mapManager = plugin.getMapManager();
        this.multiBlockHandler = plugin.getMultiBlockHandler();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (!(event.getEntity() instanceof ItemFrame)) {
            return;
        }
        
        ItemFrame frame = (ItemFrame) event.getEntity();
        Player player = event.getPlayer();
        
        // Check if player is placing a stored map
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (mapManager.isStoredMap(mainHand)) {
            long groupId = mapManager.getGroupIdFromItem(mainHand);
            if (groupId > 0) {
                // This is a multi-block map item - don't allow placing as regular item frame
                // Multi-block maps are placed via PlayerInteractEvent in PlayerListener
                event.setCancelled(true);
                return;
            }
            
            // Single map - track the placement
            multiBlockHandler.onMapPlace(frame, mainHand, player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHangingBreak(HangingBreakEvent event) {
        if (!(event.getEntity() instanceof ItemFrame)) {
            return;
        }
        
        ItemFrame frame = (ItemFrame) event.getEntity();
        
        // Check if the item in the frame is a multi-block map
        ItemStack frameItem = frame.getItem();
        if (frameItem == null || !mapManager.isStoredMap(frameItem)) {
            return;
        }
        
        long groupId = mapManager.getGroupIdFromItem(frameItem);
        if (groupId <= 0) {
            // Not a multi-block map, just a single map - allow normal break
            return;
        }
        
        // This is a multi-block map - we need to break all frames
        event.setCancelled(true);
        
        // Determine the player who broke it
        Player player = null;
        if (event instanceof HangingBreakByEntityEvent) {
            HangingBreakByEntityEvent byEntity = (HangingBreakByEntityEvent) event;
            Entity remover = byEntity.getRemover();
            if (remover instanceof Player) {
                player = (Player) remover;
            }
        }
        
        // Break all connected frames (run on next tick to avoid concurrent modification)
        final Player finalPlayer = player;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            multiBlockHandler.onMapBreak(frame, finalPlayer);
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame)) {
            return;
        }
        
        ItemFrame frame = (ItemFrame) event.getEntity();
        Entity damager = event.getDamager();
        
        // Check if this frame has a stored map
        ItemStack frameItem = frame.getItem();
        if (frameItem == null || !mapManager.isStoredMap(frameItem)) {
            return;
        }
        
        // Check if this is part of a multi-block map
        long groupId = mapManager.getGroupIdFromItem(frameItem);
        if (groupId <= 0) {
            return; // Single map, let normal behavior happen
        }
        
        // This is a multi-block map - cancel and handle ourselves
        event.setCancelled(true);
        
        Player player = (damager instanceof Player) ? (Player) damager : null;
        
        // Break all frames on next tick
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            multiBlockHandler.onMapBreak(frame, player);
        });
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame)) {
            return;
        }
        
        // Only handle main hand
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        
        ItemFrame frame = (ItemFrame) event.getRightClicked();
        Player player = event.getPlayer();
        
        // Check if frame contains a stored map
        ItemStack frameItem = frame.getItem();
        if (frameItem != null && mapManager.isStoredMap(frameItem)) {
            // Check if this is part of a multi-block map
            long groupId = mapManager.getGroupIdFromItem(frameItem);
            if (groupId > 0) {
                // Don't allow rotation of multi-block map pieces
                event.setCancelled(true);
                return;
            }
        }
        
        // Check if player is placing a stored map into an empty frame
        ItemStack handItem = player.getInventory().getItemInMainHand();
        if (handItem != null && mapManager.isStoredMap(handItem)) {
            // Check if this is a multi-block map item
            long groupId = mapManager.getGroupIdFromItem(handItem);
            if (groupId > 0) {
                // Don't allow placing multi-block map items into frames
                // They need to be placed via the dedicated placement system
                event.setCancelled(true);
                return;
            }
            
            // Single map - let placement happen, then track it
            if (frameItem == null || frameItem.getType().isAir()) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    ItemStack newFrameItem = frame.getItem();
                    if (newFrameItem != null && mapManager.isStoredMap(newFrameItem)) {
                        multiBlockHandler.onMapPlace(frame, newFrameItem, player);
                    }
                }, 1L);
            }
        }
    }
}
