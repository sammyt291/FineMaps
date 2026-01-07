package com.example.finemaps.plugin.listener;

import com.example.finemaps.core.manager.MapManager;
import com.example.finemaps.core.manager.MultiBlockMapHandler;
import com.example.finemaps.plugin.FineMapsPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
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
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * Handles item frame events for multi-block map management.
 */
public class ItemFrameListener implements Listener {

    private final FineMapsPlugin plugin;
    private final MapManager mapManager;
    private final MultiBlockMapHandler multiBlockHandler;
    private final NamespacedKey placedKey;
    private final NamespacedKey groupIdKey;
    private final NamespacedKey gridPositionKey;
    private final NamespacedKey singleMapIdKey;

    public ItemFrameListener(FineMapsPlugin plugin) {
        this.plugin = plugin;
        this.mapManager = plugin.getMapManager();
        this.multiBlockHandler = plugin.getMultiBlockHandler();
        this.placedKey = new NamespacedKey(plugin, "finemaps_placed");
        this.groupIdKey = new NamespacedKey(plugin, "finemaps_group");
        this.gridPositionKey = new NamespacedKey(plugin, "finemaps_grid");
        this.singleMapIdKey = new NamespacedKey(plugin, "finemaps_map");
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
            // Single map: only handle frames placed by FineMaps (marker on entity)
            PersistentDataContainer pdc = frame.getPersistentDataContainer();
            Byte placed = pdc.get(placedKey, PersistentDataType.BYTE);
            if (placed == null || placed == 0) {
                return; // Vanilla single-map-in-frame behavior
            }

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

            final Player finalPlayer = player;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                multiBlockHandler.onPlacedSingleMapBreak(frame, finalPlayer);
            });
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
            // Single map: if placed by FineMaps, break without dropping a frame
            PersistentDataContainer pdc = frame.getPersistentDataContainer();
            Byte placed = pdc.get(placedKey, PersistentDataType.BYTE);
            if (placed == null || placed == 0) {
                return;
            }
            Player player = (damager instanceof Player) ? (Player) damager : null;
            event.setCancelled(true);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                multiBlockHandler.onPlacedSingleMapBreak(frame, player);
            });
            return;
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

        // Debug inspect (stick + toggle)
        if (plugin.isDebug(player)) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand != null && hand.getType() == Material.STICK) {
                printFrameDebug(player, frame);
                event.setCancelled(true); // prevent rotating the map while inspecting
                return;
            }
        }
        
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
                        // Apply FineMaps orientation rules for floor/ceiling frames, so placed maps are consistent.
                        if (frame.getFacing() == BlockFace.UP || frame.getFacing() == BlockFace.DOWN) {
                            BlockFace desiredUp = getHorizontalFacing(player.getLocation().getYaw());
                            org.bukkit.Rotation rot = rotationForFloorCeiling(desiredUp, frame.getFacing() == BlockFace.DOWN);
                            try {
                                frame.setRotation(rot);
                            } catch (Throwable ignored) {
                            }
                            try {
                                frame.setRotation(rot);
                            } catch (Throwable ignored) {
                            }
                        }
                        multiBlockHandler.onMapPlace(frame, newFrameItem, player);
                    }
                }, 1L);
            }
        }
    }

    private void printFrameDebug(Player player, ItemFrame frame) {
        Location loc = frame.getLocation();
        Vector dir = frame.getLocation().getDirection();
        PersistentDataContainer entityPdc = frame.getPersistentDataContainer();

        Long entityGroupId = entityPdc.get(groupIdKey, PersistentDataType.LONG);
        String entityGrid = entityPdc.get(gridPositionKey, PersistentDataType.STRING);
        Byte placed = entityPdc.get(placedKey, PersistentDataType.BYTE);
        Long singleMapId = entityPdc.get(singleMapIdKey, PersistentDataType.LONG);

        ItemStack item = frame.getItem();
        long itemMapId = (item != null) ? mapManager.getMapIdFromItem(item) : -1;
        long itemGroupId = (item != null) ? mapManager.getGroupIdFromItem(item) : -1;
        String itemGrid = null;
        if (item != null && item.hasItemMeta() && item.getItemMeta() != null) {
            itemGrid = item.getItemMeta().getPersistentDataContainer().get(gridPositionKey, PersistentDataType.STRING);
        }

        int bukkitMapId = -1;
        try {
            if (itemMapId > 0) {
                bukkitMapId = plugin.getMapManager().getMapViewManager().getBukkitMapId(itemMapId);
            }
        } catch (Throwable ignored) {
        }

        // Rotation diagnostics:
        // Bukkit exposes ItemFrame rotation, which is effectively the rotation of the displayed item.
        // For maps, visuals are in 90° steps even though Rotation has 8 values.
        org.bukkit.Rotation rot = frame.getRotation();
        int rotOrdinal = (rot != null) ? rot.ordinal() : -1;
        int quarterTurns = rotOrdinal >= 0 ? (rotOrdinal % 4) : -1;

        BlockFace facing = frame.getFacing();
        BlockFace wallRight = rightFromViewer(facing);
        BlockFace placementXPlus = placementXPlusForWallFacing(facing);
        BlockFace wallTop = topDirectionOnWall(wallRight, quarterTurns);

        BlockFace floorTop = topDirectionOnFloor(quarterTurns);
        BlockFace ceilingTopCwFromBelow = topDirectionOnCeilingCwFromBelow(quarterTurns);
        BlockFace ceilingTopCwFromAbove = topDirectionOnCeilingCwFromAbove(quarterTurns);

        player.sendMessage(ChatColor.GOLD + "=== FineMaps Frame Debug ===");
        player.sendMessage(ChatColor.YELLOW + "World: " + ChatColor.WHITE + (loc.getWorld() != null ? loc.getWorld().getName() : "<null>"));
        player.sendMessage(ChatColor.YELLOW + "Block: " + ChatColor.WHITE + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
        player.sendMessage(ChatColor.YELLOW + "Facing: " + ChatColor.WHITE + frame.getFacing() +
            ChatColor.GRAY + " | Rotation: " + ChatColor.WHITE + frame.getRotation());
        player.sendMessage(ChatColor.YELLOW + "Dir: " + ChatColor.WHITE +
            String.format("%.2f, %.2f, %.2f", dir.getX(), dir.getY(), dir.getZ()));
        player.sendMessage(ChatColor.YELLOW + "Item: " + ChatColor.WHITE + (item != null ? item.getType().name() : "<null>") +
            ChatColor.GRAY + " | storedMap=" + ChatColor.WHITE + (item != null && mapManager.isStoredMap(item)));

        player.sendMessage(ChatColor.YELLOW + "Item mapId: " + ChatColor.WHITE + itemMapId +
            ChatColor.GRAY + " | bukkitMapId: " + ChatColor.WHITE + bukkitMapId +
            ChatColor.GRAY + " | item groupId: " + ChatColor.WHITE + itemGroupId +
            ChatColor.GRAY + " | item grid: " + ChatColor.WHITE + (itemGrid != null ? itemGrid : "<none>"));

        player.sendMessage(ChatColor.YELLOW + "Entity groupId: " + ChatColor.WHITE + (entityGroupId != null ? entityGroupId : -1) +
            ChatColor.GRAY + " | entity grid: " + ChatColor.WHITE + (entityGrid != null ? entityGrid : "<none>") +
            ChatColor.GRAY + " | placed: " + ChatColor.WHITE + ((placed != null && placed != 0) ? "1" : "0") +
            ChatColor.GRAY + " | singleMapId: " + ChatColor.WHITE + (singleMapId != null ? singleMapId : -1));

        player.sendMessage(ChatColor.YELLOW + "Rotation raw: " + ChatColor.WHITE + (rot != null ? rot.name() : "<null>") +
            ChatColor.GRAY + " | ordinal=" + ChatColor.WHITE + rotOrdinal +
            ChatColor.GRAY + " | quarterTurns(ordinal%4)=" + ChatColor.WHITE + quarterTurns);

        if (facing == BlockFace.UP) {
            player.sendMessage(ChatColor.YELLOW + "Map top dir (FLOOR, NONE baseline≈NORTH): " + ChatColor.WHITE + floorTop);
        } else if (facing == BlockFace.DOWN) {
            player.sendMessage(ChatColor.YELLOW + "Map top dir (CEILING, NONE baseline≈SOUTH): " + ChatColor.WHITE + "cw-from-below=" + ceilingTopCwFromBelow +
                ChatColor.GRAY + " | cw-from-above=" + ChatColor.WHITE + ceilingTopCwFromAbove);
        } else {
            player.sendMessage(ChatColor.YELLOW + "Map top dir (WALL, NONE baseline=UP): " + ChatColor.WHITE + wallTop +
                ChatColor.GRAY + " | viewerRight=" + ChatColor.WHITE + wallRight +
                ChatColor.GRAY + " | placementX+=" + ChatColor.WHITE + placementXPlus);
        }
    }

    /**
     * Viewer-right direction given the wall-facing of the item frame.
     * This matches the placement logic used for multi-block ordering.
     */
    private BlockFace rightFromViewer(BlockFace facing) {
        switch (facing) {
            case NORTH: return BlockFace.WEST;
            case SOUTH: return BlockFace.EAST;
            case EAST: return BlockFace.SOUTH;
            case WEST: return BlockFace.NORTH;
            default: return BlockFace.EAST;
        }
    }

    private BlockFace topDirectionOnWall(BlockFace viewerRight, int quarterTurns) {
        if (quarterTurns < 0) return null;
        switch (quarterTurns) {
            case 0: return BlockFace.UP;
            case 1: return viewerRight;
            case 2: return BlockFace.DOWN;
            case 3: return viewerRight.getOppositeFace();
            default: return BlockFace.UP;
        }
    }

    /**
     * Must mirror the wall placement X+ direction used in MultiBlockMapHandler#getRight().
     */
    private BlockFace placementXPlusForWallFacing(BlockFace facing) {
        switch (facing) {
            // NORTH/SOUTH: X+ = viewerRight
            case NORTH: return BlockFace.WEST;
            case SOUTH: return BlockFace.EAST;
            // EAST/WEST: X+ = viewerLeft
            case EAST: return BlockFace.NORTH;
            case WEST: return BlockFace.SOUTH;
            default: return null;
        }
    }

    /**
     * Floor frames (facing UP): per user observation, NONE makes map-top point NORTH.
     * Each effective quarter turn rotates 90° clockwise around +Y when viewed from above.
     */
    private BlockFace topDirectionOnFloor(int quarterTurns) {
        if (quarterTurns < 0) return null;
        switch (quarterTurns & 3) {
            case 0: return BlockFace.NORTH;
            case 1: return BlockFace.EAST;
            case 2: return BlockFace.SOUTH;
            case 3: return BlockFace.WEST;
            default: return BlockFace.NORTH;
        }
    }

    /**
     * Ceiling frames (facing DOWN): per user observation, NONE makes map-top point SOUTH.
     * "Clockwise" is from the viewer's perspective (typically from below).
     */
    private BlockFace topDirectionOnCeilingCwFromBelow(int quarterTurns) {
        if (quarterTurns < 0) return null;
        // If the viewer is below, clockwise around -Y is opposite the "from above" direction.
        switch (quarterTurns & 3) {
            case 0: return BlockFace.SOUTH;
            case 1: return BlockFace.WEST;
            case 2: return BlockFace.NORTH;
            case 3: return BlockFace.EAST;
            default: return BlockFace.SOUTH;
        }
    }

    /**
     * Alternate ceiling interpretation (clockwise as if viewed from above).
     * Included because different clients/versions can feel inverted.
     */
    private BlockFace topDirectionOnCeilingCwFromAbove(int quarterTurns) {
        if (quarterTurns < 0) return null;
        switch (quarterTurns & 3) {
            case 0: return BlockFace.SOUTH;
            case 1: return BlockFace.EAST;
            case 2: return BlockFace.NORTH;
            case 3: return BlockFace.WEST;
            default: return BlockFace.SOUTH;
        }
    }

    private BlockFace getHorizontalFacing(float yaw) {
        float y = yaw % 360.0f;
        if (y < 0) y += 360.0f;
        // Minecraft yaw: 0 = South, 90 = West, 180 = North, 270 = East
        if (y >= 315 || y < 45) return BlockFace.SOUTH;
        if (y < 135) return BlockFace.WEST;
        if (y < 225) return BlockFace.NORTH;
        return BlockFace.EAST;
    }

    /**
     * Same placement rotation rules used by FineMaps frame spawning:
     * - Maps visually rotate in 90° steps; we use rotation.ordinal()%4.
     * - Floor: NONE => top=NORTH (N->E->S->W)
     * - Ceiling: NONE => top=SOUTH when viewed from below (S->W->N->E)
     */
    private org.bukkit.Rotation rotationForFloorCeiling(BlockFace desiredUp, boolean ceiling) {
        if (desiredUp == null) desiredUp = BlockFace.NORTH;
        int quarterTurns;
        if (!ceiling) {
            switch (desiredUp) {
                case NORTH: quarterTurns = 0; break;
                case EAST: quarterTurns = 1; break;
                case SOUTH: quarterTurns = 2; break;
                case WEST: quarterTurns = 3; break;
                default: quarterTurns = 0; break;
            }
        } else {
            switch (desiredUp) {
                case SOUTH: quarterTurns = 0; break;
                case EAST: quarterTurns = 1; break;
                case NORTH: quarterTurns = 2; break;
                case WEST: quarterTurns = 3; break;
                default: quarterTurns = 0; break;
            }
        }

        switch (quarterTurns & 3) {
            case 0: return org.bukkit.Rotation.NONE;
            case 1: return org.bukkit.Rotation.CLOCKWISE_45;
            case 2: return org.bukkit.Rotation.CLOCKWISE;
            case 3: return org.bukkit.Rotation.CLOCKWISE_135;
            default: return org.bukkit.Rotation.NONE;
        }
    }
}
