package com.example.mapdb.plugin.listener;

import com.example.mapdb.core.manager.MapManager;
import com.example.mapdb.core.manager.MultiBlockMapHandler;
import com.example.mapdb.plugin.MapDBPlugin;
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

    private final MapDBPlugin plugin;
    private final MapManager mapManager;
    private final MultiBlockMapHandler multiBlockHandler;

    public ItemFrameListener(MapDBPlugin plugin) {
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
                // This is part of a multi-block map - handle placement
                multiBlockHandler.onMapPlace(frame, mainHand, player);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHangingBreak(HangingBreakEvent event) {
        if (!(event.getEntity() instanceof ItemFrame)) {
            return;
        }
        
        ItemFrame frame = (ItemFrame) event.getEntity();
        
        // Check if this is part of a multi-block map
        if (event instanceof HangingBreakByEntityEvent) {
            HangingBreakByEntityEvent byEntity = (HangingBreakByEntityEvent) event;
            Entity remover = byEntity.getRemover();
            Player player = remover instanceof Player ? (Player) remover : null;
            
            if (multiBlockHandler.onMapBreak(frame, player)) {
                // Multi-block map was broken - cancel default drop
                event.setCancelled(true);
            }
        } else {
            // Non-entity break (explosion, etc.)
            if (multiBlockHandler.onMapBreak(frame, null)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame)) {
            return;
        }
        
        ItemFrame frame = (ItemFrame) event.getEntity();
        Entity damager = event.getDamager();
        
        // Check if this frame has a stored map and is being hit by a player
        ItemStack frameItem = frame.getItem();
        if (frameItem != null && mapManager.isStoredMap(frameItem)) {
            if (damager instanceof Player) {
                Player player = (Player) damager;
                
                // Check if this is part of a multi-block map
                long groupId = mapManager.getGroupIdFromItem(frameItem);
                if (groupId > 0) {
                    // Cancel the normal item frame behavior and handle multi-block break
                    event.setCancelled(true);
                    multiBlockHandler.onMapBreak(frame, player);
                }
            }
        }
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
        if (frameItem == null || frameItem.getType().isAir()) {
            if (mapManager.isStoredMap(handItem)) {
                // Let the placement happen, then notify multiblock handler
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
