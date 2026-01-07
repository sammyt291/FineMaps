package com.example.finemaps.core.nms;

import com.example.finemaps.api.nms.NMSAdapter;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ProtocolLib-based NMS adapter for cross-version compatibility.
 * Works with most Spigot/Paper/Folia versions from 1.12.2 to 1.21.x.
 */
public class ProtocolLibAdapter implements NMSAdapter {

    private final Logger logger;
    private final String version;
    private final boolean isFolia;
    private final ProtocolManager protocolManager;
    private final int majorVersion;
    private final int minorVersion;
    
    private PacketAdapter packetAdapter;
    private MapPacketListener mapPacketListener;

    public ProtocolLibAdapter(Logger logger, String version, boolean isFolia) {
        this.logger = logger;
        this.version = version;
        this.isFolia = isFolia;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        this.majorVersion = NMSAdapterFactory.getMajorVersion();
        this.minorVersion = NMSAdapterFactory.getMinorVersion();
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public void sendMapUpdate(Player player, int mapId, byte[] pixels) {
        try {
            // Create map data packet
            PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.MAP);
            
            if (majorVersion >= 17) {
                // 1.17+ packet structure
                packet.getIntegers().write(0, mapId);
                packet.getBytes().write(0, (byte) 0); // Scale
                packet.getBooleans().write(0, false); // Locked
                
                if (majorVersion >= 17) {
                    // Write map data
                    packet.getIntegers().write(1, 0); // Start X
                    packet.getIntegers().write(2, 0); // Start Y
                    packet.getIntegers().write(3, 128); // Width
                    packet.getIntegers().write(4, 128); // Height
                    packet.getByteArrays().write(0, pixels);
                }
            } else {
                // Legacy packet structure (1.12-1.16)
                packet.getIntegers().write(0, mapId);
                packet.getBytes().write(0, (byte) 0); // Scale
                packet.getBooleans().write(0, false); // Tracking position
                
                // In older versions, we need to set the columns/rows
                packet.getIntegers().write(1, 128); // Columns
                packet.getIntegers().write(2, 128); // Rows
                packet.getIntegers().write(3, 0); // Start X
                packet.getIntegers().write(4, 0); // Start Y
                packet.getByteArrays().write(0, pixels);
            }
            
            protocolManager.sendServerPacket(player, packet);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to send map update packet", e);
            // Fall back to alternative method
            sendMapUpdateFallback(player, mapId, pixels);
        }
    }

    private void sendMapUpdateFallback(Player player, int mapId, byte[] pixels) {
        try {
            // Use Bukkit API as fallback
            // This requires the map to exist in the world
            // Less efficient but more compatible
            
            // For older versions, we may need reflection
            Class<?> craftMapView = getCraftClass("map.CraftMapView");
            Class<?> renderData = Class.forName("org.bukkit.map.MapView$RenderData");
            
            // This is a simplified fallback - full implementation would be more complex
            logger.warning("Using fallback map update method - may have reduced functionality");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Fallback map update failed", e);
        }
    }

    @Override
    public void sendPartialMapUpdate(Player player, int mapId, int startX, int startY,
                                      int width, int height, byte[] pixels) {
        try {
            PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.MAP);
            
            if (majorVersion >= 17) {
                packet.getIntegers().write(0, mapId);
                packet.getBytes().write(0, (byte) 0);
                packet.getBooleans().write(0, false);
                packet.getIntegers().write(1, startX);
                packet.getIntegers().write(2, startY);
                packet.getIntegers().write(3, width);
                packet.getIntegers().write(4, height);
                packet.getByteArrays().write(0, pixels);
            } else {
                packet.getIntegers().write(0, mapId);
                packet.getBytes().write(0, (byte) 0);
                packet.getBooleans().write(0, false);
                packet.getIntegers().write(1, width);
                packet.getIntegers().write(2, height);
                packet.getIntegers().write(3, startX);
                packet.getIntegers().write(4, startY);
                packet.getByteArrays().write(0, pixels);
            }
            
            protocolManager.sendServerPacket(player, packet);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to send partial map update", e);
        }
    }

    @Override
    public ItemStack createMapItem(int mapId) {
        ItemStack item;
        
        if (majorVersion >= 13) {
            // 1.13+ uses FILLED_MAP
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
        if (!supportsBlockDisplays()) {
            return -1;
        }
        
        try {
            // Use reflection or NMS to spawn item display
            // This is a simplified version - full implementation would handle all entity data
            
            // For 1.19.4+ we can use the API
            if (majorVersion >= 20 || (majorVersion == 19 && minorVersion >= 4)) {
                // Bukkit API for item displays (if available)
                // This would require more specific implementation per version
                return -1; // Placeholder - implement per-version
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to spawn map display", e);
        }
        
        return -1;
    }

    @Override
    public void removeDisplay(int entityId) {
        if (entityId < 0) {
            return;
        }
        
        try {
            // Send entity destroy packet
            PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
            
            if (majorVersion >= 17) {
                // 1.17+ uses IntList
                packet.getIntLists().write(0, Collections.singletonList(entityId));
            } else {
                packet.getIntegerArrays().write(0, new int[]{entityId});
            }
            
            for (Player player : Bukkit.getOnlinePlayers()) {
                protocolManager.sendServerPacket(player, packet);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to remove display entity", e);
        }
    }

    @Override
    public boolean supportsBlockDisplays() {
        return majorVersion >= 20 || (majorVersion == 19 && minorVersion >= 4);
    }

    @Override
    public int spawnPreviewBlockDisplay(Location location, boolean valid, float scaleX, float scaleY, float scaleZ) {
        // ProtocolLib adapter delegates to the spawnMapDisplay approach
        // This would require packet-based entity spawning which is complex
        // For now, return -1 to fall back to particles
        return -1;
    }

    @Override
    public void removePreviewDisplay(int entityId) {
        // Same as removeDisplay since they use the same entity destroy packet
        removeDisplay(entityId);
    }

    @Override
    public void showParticleOutline(Player player, Location location) {
        // Show particle outline for map preview in older versions
        World world = location.getWorld();
        if (world == null) return;
        
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        
        // Draw rectangle outline with particles
        Particle particleType;
        try {
            particleType = Particle.valueOf("FLAME");
        } catch (IllegalArgumentException e) {
            particleType = Particle.values()[0]; // Fallback
        }
        
        for (double i = 0; i <= 1; i += 0.1) {
            // Top edge
            player.spawnParticle(particleType, x + i, y + 1, z, 1, 0, 0, 0, 0);
            // Bottom edge  
            player.spawnParticle(particleType, x + i, y, z, 1, 0, 0, 0, 0);
            // Left edge
            player.spawnParticle(particleType, x, y + i, z, 1, 0, 0, 0, 0);
            // Right edge
            player.spawnParticle(particleType, x + 1, y + i, z, 1, 0, 0, 0, 0);
        }
    }

    @Override
    public void registerPacketInterceptor(MapPacketListener listener) {
        this.mapPacketListener = listener;
        
        packetAdapter = new PacketAdapter(
            ProtocolLibrary.getPlugin(), 
            ListenerPriority.HIGH,
            PacketType.Play.Server.MAP
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (mapPacketListener == null) return;
                
                Player player = event.getPlayer();
                PacketContainer packet = event.getPacket();
                
                try {
                    int mapId = packet.getIntegers().read(0);
                    
                    if (mapPacketListener.onMapPacket(player, mapId)) {
                        event.setCancelled(true);
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error in map packet interceptor", e);
                }
            }
        };
        
        protocolManager.addPacketListener(packetAdapter);
    }

    @Override
    public void unregisterPacketInterceptor() {
        if (packetAdapter != null) {
            protocolManager.removePacketListener(packetAdapter);
            packetAdapter = null;
        }
        mapPacketListener = null;
    }

    @Override
    public boolean supportsFolia() {
        return isFolia;
    }

    @Override
    public int getMajorVersion() {
        return majorVersion;
    }

    @Override
    public int getMinorVersion() {
        return minorVersion;
    }

    private Class<?> getCraftClass(String name) throws ClassNotFoundException {
        String version = Bukkit.getServer().getClass().getPackage().getName();
        version = version.substring(version.lastIndexOf('.') + 1);
        return Class.forName("org.bukkit.craftbukkit." + version + "." + name);
    }
}
