package com.example.finemaps.core.nms;

import com.example.finemaps.api.nms.NMSAdapter;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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

    // Display entity support (ItemDisplay/BlockDisplay) - implemented via Bukkit reflection.
    private final Class<?> itemDisplayClass;
    private final Class<?> blockDisplayClass;
    private final boolean hasItemDisplay;
    private final Map<Integer, Entity> displayEntities = new ConcurrentHashMap<>();
    private final Map<Integer, Entity> previewDisplayEntities = new ConcurrentHashMap<>();
    private final AtomicInteger nextDisplayId = new AtomicInteger(Integer.MAX_VALUE - 1000000);
    private final AtomicInteger nextPreviewDisplayId = new AtomicInteger(Integer.MAX_VALUE - 2000000);
    
    private PacketAdapter packetAdapter;
    private MapPacketListener mapPacketListener;

    public ProtocolLibAdapter(Logger logger, String version, boolean isFolia) {
        this.logger = logger;
        this.version = version;
        this.isFolia = isFolia;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        this.majorVersion = NMSAdapterFactory.getMajorVersion();
        this.minorVersion = NMSAdapterFactory.getMinorVersion();

        Class<?> item = null;
        Class<?> block = null;
        boolean ok = false;
        try {
            item = Class.forName("org.bukkit.entity.ItemDisplay");
            ok = true;
            try {
                block = Class.forName("org.bukkit.entity.BlockDisplay");
            } catch (ClassNotFoundException ignored) {
                block = null;
            }
        } catch (ClassNotFoundException ignored) {
            ok = false;
            item = null;
            block = null;
        } catch (Throwable t) {
            ok = false;
            item = null;
            block = null;
        }
        this.itemDisplayClass = item;
        this.blockDisplayClass = block;
        this.hasItemDisplay = ok;
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
        if (!hasItemDisplay || itemDisplayClass == null || location == null) {
            return -1;
        }

        try {
            World world = location.getWorld();
            if (world == null) return -1;

            Entity entity = spawnItemDisplayReflection(world, location, mapId);
            if (entity == null) return -1;

            int id = nextDisplayId.getAndIncrement();
            displayEntities.put(id, entity);
            return id;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to spawn map display", e);
        }
        
        return -1;
    }

    @Override
    public void removeDisplay(int entityId) {
        Entity entity = displayEntities.remove(entityId);
        if (entity != null) {
            try {
                if (entity.isValid()) {
                    entity.remove();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public boolean supportsBlockDisplays() {
        // Prefer real capability detection (Bukkit may be newer than ProtocolLib's packet impl).
        return hasItemDisplay && blockDisplayClass != null;
    }

    @Override
    public int spawnPreviewBlockDisplay(Location location, boolean valid, float scaleX, float scaleY, float scaleZ) {
        if (blockDisplayClass == null || location == null) {
            return -1;
        }

        try {
            World world = location.getWorld();
            if (world == null) return -1;

            Entity entity = spawnBlockDisplayReflection(world, location, valid, scaleX, scaleY, scaleZ);
            if (entity == null) return -1;

            int id = nextPreviewDisplayId.getAndIncrement();
            previewDisplayEntities.put(id, entity);
            return id;
        } catch (Throwable t) {
            logger.log(Level.FINE, "Failed to spawn preview block display", t);
            return -1;
        }
    }

    @Override
    public void removePreviewDisplay(int entityId) {
        Entity entity = previewDisplayEntities.remove(entityId);
        if (entity != null) {
            try {
                if (entity.isValid()) {
                    entity.remove();
                }
            } catch (Throwable ignored) {
            }
        }
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

    @SuppressWarnings("unchecked")
    private Entity spawnItemDisplayReflection(World world, Location location, int mapId) {
        try {
            // Create the item to display (mapId is the Bukkit map id)
            ItemStack mapItem = createMapItem(mapId);

            // Spawn: World#spawn(Location, Class)
            Method simpleSpawn = World.class.getMethod("spawn", Location.class, Class.class);
            Entity entity = (Entity) simpleSpawn.invoke(world, location, itemDisplayClass);

            // Configure the display - set the item
            try {
                Method setItemStack = itemDisplayClass.getMethod("setItemStack", ItemStack.class);
                setItemStack.invoke(entity, mapItem);
            } catch (NoSuchMethodException ignored) {
            }

            // Billboard FIXED
            try {
                Class<?> displayClass = Class.forName("org.bukkit.entity.Display");
                Class<?> billboardClass = Class.forName("org.bukkit.entity.Display$Billboard");
                Method setBillboard = displayClass.getMethod("setBillboard", billboardClass);
                Object fixedBillboard = Enum.valueOf((Class<Enum>) billboardClass, "FIXED");
                setBillboard.invoke(entity, fixedBillboard);
            } catch (Throwable ignored) {
            }

            // Transform FIXED (if available)
            try {
                Class<?> transformClass = Class.forName("org.bukkit.entity.ItemDisplay$ItemDisplayTransform");
                Method setTransform = itemDisplayClass.getMethod("setItemDisplayTransform", transformClass);
                Object fixedTransform = Enum.valueOf((Class<Enum>) transformClass, "FIXED");
                setTransform.invoke(entity, fixedTransform);
            } catch (Throwable ignored) {
            }

            // View range
            try {
                Class<?> displayClass = Class.forName("org.bukkit.entity.Display");
                Method setViewRange = displayClass.getMethod("setViewRange", float.class);
                setViewRange.invoke(entity, 64.0f);
            } catch (Throwable ignored) {
            }

            return entity;
        } catch (Throwable t) {
            logger.log(Level.FINE, "Could not spawn ItemDisplay via reflection", t);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Entity spawnBlockDisplayReflection(World world, Location location, boolean valid, float scaleX, float scaleY, float scaleZ) {
        try {
            Method simpleSpawn = World.class.getMethod("spawn", Location.class, Class.class);
            Entity entity = (Entity) simpleSpawn.invoke(world, location, blockDisplayClass);

            // Select material
            Material blockMaterial;
            if (valid) {
                try {
                    blockMaterial = Material.valueOf("LIME_STAINED_GLASS");
                } catch (IllegalArgumentException e) {
                    try {
                        blockMaterial = Material.valueOf("LIME_CONCRETE");
                    } catch (IllegalArgumentException e2) {
                        blockMaterial = Material.valueOf("EMERALD_BLOCK");
                    }
                }
            } else {
                try {
                    blockMaterial = Material.valueOf("RED_STAINED_GLASS");
                } catch (IllegalArgumentException e) {
                    try {
                        blockMaterial = Material.valueOf("RED_CONCRETE");
                    } catch (IllegalArgumentException e2) {
                        blockMaterial = Material.valueOf("REDSTONE_BLOCK");
                    }
                }
            }

            // Set block data
            try {
                org.bukkit.block.data.BlockData blockData = blockMaterial.createBlockData();
                Method setBlock = blockDisplayClass.getMethod("setBlock", org.bukkit.block.data.BlockData.class);
                setBlock.invoke(entity, blockData);
            } catch (Throwable ignored) {
            }

            // Billboard FIXED
            try {
                Class<?> displayClass = Class.forName("org.bukkit.entity.Display");
                Class<?> billboardClass = Class.forName("org.bukkit.entity.Display$Billboard");
                Method setBillboard = displayClass.getMethod("setBillboard", billboardClass);
                Object fixedBillboard = Enum.valueOf((Class<Enum>) billboardClass, "FIXED");
                setBillboard.invoke(entity, fixedBillboard);
            } catch (Throwable ignored) {
            }

            // Transformation: keep centered and scaled (BlockDisplay models are in [0..1] space)
            try {
                Class<?> displayClass = Class.forName("org.bukkit.entity.Display");
                Class<?> transformationClass = Class.forName("org.bukkit.util.Transformation");
                Class<?> vector3fClass = Class.forName("org.joml.Vector3f");
                Class<?> quaternionfClass = Class.forName("org.joml.Quaternionf");

                Object scale = vector3fClass.getConstructor(float.class, float.class, float.class)
                    .newInstance(scaleX, scaleY, scaleZ);
                Object translation = vector3fClass.getConstructor(float.class, float.class, float.class)
                    .newInstance(-scaleX / 2.0f, -scaleY / 2.0f, -scaleZ / 2.0f);
                Object leftRotation = quaternionfClass.getConstructor().newInstance();
                Object rightRotation = quaternionfClass.getConstructor().newInstance();

                Object transformation = transformationClass.getConstructor(
                    vector3fClass, quaternionfClass, vector3fClass, quaternionfClass
                ).newInstance(translation, leftRotation, scale, rightRotation);

                Method setTransformation = displayClass.getMethod("setTransformation", transformationClass);
                setTransformation.invoke(entity, transformation);
            } catch (Throwable ignored) {
            }

            // View range
            try {
                Class<?> displayClass = Class.forName("org.bukkit.entity.Display");
                Method setViewRange = displayClass.getMethod("setViewRange", float.class);
                setViewRange.invoke(entity, 32.0f);
            } catch (Throwable ignored) {
            }

            // Glow for visibility
            try {
                Method setGlowing = Entity.class.getMethod("setGlowing", boolean.class);
                setGlowing.invoke(entity, true);
            } catch (Throwable ignored) {
            }

            return entity;
        } catch (Throwable t) {
            logger.log(Level.FINE, "Could not spawn BlockDisplay via reflection", t);
            return null;
        }
    }
}
