package com.example.mapdb.nms.v1_21_R1;

import com.example.mapdb.core.nms.NMSAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * NMS adapter for Minecraft 1.21.x.
 * Uses Bukkit API where possible for Paper/Folia compatibility.
 */
public class NMS_v1_21_R1 implements NMSAdapter {

    private final Logger logger;
    private final boolean isFolia;
    private MapPacketListener packetListener;
    
    // Track spawned display entities
    private final Map<Integer, ItemDisplay> displayEntities = new HashMap<>();
    private int nextDisplayId = Integer.MAX_VALUE - 1000000;

    public NMS_v1_21_R1(Logger logger) {
        this.logger = logger;
        this.isFolia = checkFolia();
    }

    private boolean checkFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public String getVersion() {
        return "v1_21_R1";
    }

    @Override
    public void sendMapUpdate(Player player, int mapId, byte[] pixels) {
        // In 1.21, we use ProtocolLib for packet manipulation
        // This method would be called from ProtocolLibAdapter
        // Here we just log if called directly (shouldn't happen)
        logger.warning("Direct sendMapUpdate called - should use ProtocolLib");
    }

    @Override
    public void sendPartialMapUpdate(Player player, int mapId, int startX, int startY,
                                      int width, int height, byte[] pixels) {
        // Same as above - use ProtocolLib
        logger.warning("Direct sendPartialMapUpdate called - should use ProtocolLib");
    }

    @Override
    public ItemStack createMapItem(int mapId) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        if (meta != null) {
            meta.setMapId(mapId);
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public int getMapId(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP) {
            return -1;
        }
        MapMeta meta = (MapMeta) item.getItemMeta();
        if (meta != null && meta.hasMapId()) {
            return meta.getMapId();
        }
        return -1;
    }

    @Override
    public ItemStack setMapId(ItemStack item, int mapId) {
        MapMeta meta = (MapMeta) item.getItemMeta();
        if (meta != null) {
            meta.setMapId(mapId);
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public boolean isFilledMap(ItemStack item) {
        return item != null && item.getType() == Material.FILLED_MAP;
    }

    @Override
    public int spawnMapDisplay(Location location, int mapId) {
        if (!supportsBlockDisplays()) {
            return -1;
        }
        
        try {
            World world = location.getWorld();
            if (world == null) return -1;
            
            // Spawn item display entity
            ItemDisplay display = world.spawn(location, ItemDisplay.class, entity -> {
                ItemStack mapItem = createMapItem(mapId);
                entity.setItemStack(mapItem);
                entity.setBillboard(ItemDisplay.Billboard.FIXED);
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            });
            
            int displayId = nextDisplayId++;
            displayEntities.put(displayId, display);
            
            return displayId;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to spawn map display", e);
            return -1;
        }
    }

    @Override
    public void removeDisplay(int entityId) {
        ItemDisplay display = displayEntities.remove(entityId);
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    @Override
    public boolean supportsBlockDisplays() {
        return true; // 1.21 supports display entities
    }

    @Override
    public void showParticleOutline(Player player, Location location) {
        World world = location.getWorld();
        if (world == null) return;
        
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        
        // Use dust particle for a cleaner look
        Particle.DustOptions dust = new Particle.DustOptions(
            org.bukkit.Color.fromRGB(255, 215, 0), // Gold color
            0.5f
        );
        
        for (double i = 0; i <= 1; i += 0.1) {
            player.spawnParticle(Particle.DUST, x + i, y + 1, z, 1, dust);
            player.spawnParticle(Particle.DUST, x + i, y, z, 1, dust);
            player.spawnParticle(Particle.DUST, x, y + i, z, 1, dust);
            player.spawnParticle(Particle.DUST, x + 1, y + i, z, 1, dust);
        }
    }

    @Override
    public void registerPacketInterceptor(MapPacketListener listener) {
        this.packetListener = listener;
        // Packet interception handled by ProtocolLibAdapter
    }

    @Override
    public void unregisterPacketInterceptor() {
        this.packetListener = null;
    }

    @Override
    public boolean supportsFolia() {
        return isFolia;
    }

    @Override
    public int getMajorVersion() {
        return 21;
    }

    @Override
    public int getMinorVersion() {
        // Parse from Bukkit version
        String version = Bukkit.getBukkitVersion();
        String[] parts = version.split("[.-]");
        if (parts.length >= 3) {
            try {
                return Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                return 1;
            }
        }
        return 1;
    }
}
