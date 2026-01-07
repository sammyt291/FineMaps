package com.example.mapdb.core.nms;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;

import java.util.logging.Logger;

/**
 * Fallback NMS adapter that provides basic functionality without ProtocolLib.
 * Uses only Bukkit API - no packet manipulation available.
 */
public class FallbackNMSAdapter implements NMSAdapter {

    private final Logger logger;
    private final String version;
    private final int majorVersion;
    private final int minorVersion;

    public FallbackNMSAdapter(Logger logger, String version) {
        this.logger = logger;
        this.version = version;
        this.majorVersion = NMSAdapterFactory.getMajorVersion();
        this.minorVersion = NMSAdapterFactory.getMinorVersion();
        logger.warning("Using fallback adapter - virtual ID system not available");
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public void sendMapUpdate(Player player, int mapId, byte[] pixels) {
        // Not available without ProtocolLib
        // Maps will use standard Bukkit rendering
    }

    @Override
    public void sendPartialMapUpdate(Player player, int mapId, int startX, int startY,
                                      int width, int height, byte[] pixels) {
        // Not available without ProtocolLib
    }

    @Override
    public ItemStack createMapItem(int mapId) {
        ItemStack item;
        
        if (majorVersion >= 13) {
            item = new ItemStack(Material.FILLED_MAP);
            MapMeta meta = (MapMeta) item.getItemMeta();
            if (meta != null) {
                meta.setMapId(mapId);
                item.setItemMeta(meta);
            }
        } else {
            // 1.12.x uses MAP with durability as ID
            item = new ItemStack(Material.valueOf("MAP"), 1, (short) mapId);
        }
        
        return item;
    }

    @Override
    public int getMapId(ItemStack item) {
        if (item == null) {
            return -1;
        }
        
        if (majorVersion >= 13) {
            if (item.getType() != Material.FILLED_MAP) {
                return -1;
            }
            MapMeta meta = (MapMeta) item.getItemMeta();
            if (meta != null && meta.hasMapId()) {
                return meta.getMapId();
            }
        } else {
            if (item.getType().name().equals("MAP")) {
                return item.getDurability();
            }
        }
        
        return -1;
    }

    @Override
    public ItemStack setMapId(ItemStack item, int mapId) {
        if (majorVersion >= 13) {
            MapMeta meta = (MapMeta) item.getItemMeta();
            if (meta != null) {
                meta.setMapId(mapId);
                item.setItemMeta(meta);
            }
        } else {
            item.setDurability((short) mapId);
        }
        return item;
    }

    @Override
    public boolean isFilledMap(ItemStack item) {
        if (item == null) {
            return false;
        }
        
        if (majorVersion >= 13) {
            return item.getType() == Material.FILLED_MAP;
        } else {
            return item.getType().name().equals("MAP");
        }
    }

    @Override
    public int spawnMapDisplay(Location location, int mapId) {
        // Display entities require version-specific code
        return -1;
    }

    @Override
    public void removeDisplay(int entityId) {
        // No-op in fallback mode
    }

    @Override
    public boolean supportsBlockDisplays() {
        return false;
    }

    @Override
    public void showParticleOutline(Player player, Location location) {
        World world = location.getWorld();
        if (world == null) return;
        
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        
        Particle particleType;
        try {
            particleType = Particle.valueOf("FLAME");
        } catch (IllegalArgumentException e) {
            particleType = Particle.values()[0];
        }
        
        for (double i = 0; i <= 1; i += 0.1) {
            player.spawnParticle(particleType, x + i, y + 1, z, 1, 0, 0, 0, 0);
            player.spawnParticle(particleType, x + i, y, z, 1, 0, 0, 0, 0);
            player.spawnParticle(particleType, x, y + i, z, 1, 0, 0, 0, 0);
            player.spawnParticle(particleType, x + 1, y + i, z, 1, 0, 0, 0, 0);
        }
    }

    @Override
    public void registerPacketInterceptor(MapPacketListener listener) {
        // Not available without ProtocolLib
        logger.info("Packet interception not available in fallback mode");
    }

    @Override
    public void unregisterPacketInterceptor() {
        // No-op
    }

    @Override
    public boolean supportsFolia() {
        return NMSAdapterFactory.isFolia();
    }

    @Override
    public int getMajorVersion() {
        return majorVersion;
    }

    @Override
    public int getMinorVersion() {
        return minorVersion;
    }
}
