package com.example.finemaps.core.nms;

import com.example.finemaps.api.nms.NMSAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Universal Bukkit-based NMS adapter that works across all versions using reflection.
 * No version-specific modules needed - detects capabilities at runtime.
 */
public class BukkitNMSAdapter implements NMSAdapter {

    private final Logger logger;
    private final int majorVersion;
    private final int minorVersion;
    private final boolean isFolia;
    
    // Cached reflection lookups
    private final Material filledMapMaterial;
    private final boolean hasMapMeta;
    private final boolean hasItemDisplay;
    private final Particle dustParticle;
    private final Particle flameParticle;
    
    // Display entity tracking
    private final Map<Integer, Entity> displayEntities = new HashMap<>();
    private int nextDisplayId = Integer.MAX_VALUE - 1000000;
    
    // Reflection caches
    private Method setMapIdMethod;
    private Method getMapIdMethod;
    private Method hasMapIdMethod;
    private Class<?> itemDisplayClass;
    private Object dustOptions;

    public BukkitNMSAdapter(Logger logger) {
        this.logger = logger;
        this.majorVersion = parseVersion()[0];
        this.minorVersion = parseVersion()[1];
        this.isFolia = detectFolia();
        
        // Detect material name
        this.filledMapMaterial = detectFilledMapMaterial();
        
        // Detect MapMeta capabilities
        this.hasMapMeta = detectMapMeta();
        
        // Detect display entities
        this.hasItemDisplay = detectItemDisplay();
        
        // Detect particles
        this.dustParticle = detectParticle("DUST", "REDSTONE");
        this.flameParticle = detectParticle("FLAME", "FLAME");
        
        // Setup dust options for modern versions
        setupDustOptions();
        
        logger.info("BukkitNMSAdapter initialized for " + majorVersion + "." + minorVersion);
        logger.info("  - FilledMap material: " + filledMapMaterial.name());
        logger.info("  - MapMeta support: " + hasMapMeta);
        logger.info("  - ItemDisplay support: " + hasItemDisplay);
    }

    private int[] parseVersion() {
        String version = Bukkit.getBukkitVersion();
        // Format: "1.21.1-R0.1-SNAPSHOT" or "1.20.4-R0.1-SNAPSHOT"
        try {
            String[] parts = version.split("[.\\-]");
            int major = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int minor = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return new int[]{major, minor};
        } catch (NumberFormatException e) {
            logger.warning("Could not parse version: " + version + ", assuming 1.20.0");
            return new int[]{20, 0};
        }
    }

    private boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private Material detectFilledMapMaterial() {
        // 1.13+ uses FILLED_MAP, 1.12 and below use MAP
        try {
            return Material.valueOf("FILLED_MAP");
        } catch (IllegalArgumentException e) {
            return Material.valueOf("MAP");
        }
    }

    private boolean detectMapMeta() {
        // Check if MapMeta.setMapId exists (1.13+)
        try {
            setMapIdMethod = MapMeta.class.getMethod("setMapId", int.class);
            getMapIdMethod = MapMeta.class.getMethod("getMapId");
            hasMapIdMethod = MapMeta.class.getMethod("hasMapId");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private boolean detectItemDisplay() {
        try {
            itemDisplayClass = Class.forName("org.bukkit.entity.ItemDisplay");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private Particle detectParticle(String... names) {
        for (String name : names) {
            try {
                return Particle.valueOf(name);
            } catch (IllegalArgumentException ignored) {
            }
        }
        // Fallback to first available particle
        return Particle.values()[0];
    }

    private void setupDustOptions() {
        if (dustParticle.name().equals("DUST") || dustParticle.name().equals("REDSTONE")) {
            try {
                // Try to create DustOptions for modern versions
                Class<?> dustOptionsClass = Class.forName("org.bukkit.Particle$DustOptions");
                dustOptions = dustOptionsClass
                    .getConstructor(Color.class, float.class)
                    .newInstance(Color.fromRGB(255, 215, 0), 0.5f);
            } catch (Exception e) {
                dustOptions = null;
            }
        }
    }

    @Override
    public String getVersion() {
        return "1." + majorVersion + "." + minorVersion;
    }

    @Override
    public void sendMapUpdate(Player player, int mapId, byte[] pixels) {
        // Not available without ProtocolLib
        // Maps use standard Bukkit MapRenderer system
    }

    @Override
    public void sendPartialMapUpdate(Player player, int mapId, int startX, int startY,
                                      int width, int height, byte[] pixels) {
        // Not available without ProtocolLib
    }

    @Override
    public ItemStack createMapItem(int mapId) {
        ItemStack item = new ItemStack(filledMapMaterial);
        
        if (hasMapMeta) {
            // 1.13+ - use MapMeta
            MapMeta meta = (MapMeta) item.getItemMeta();
            if (meta != null) {
                try {
                    setMapIdMethod.invoke(meta, mapId);
                    item.setItemMeta(meta);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to set map ID via reflection", e);
                }
            }
        } else {
            // 1.12 and below - use durability
            item.setDurability((short) mapId);
        }
        
        return item;
    }

    @Override
    public int getMapId(ItemStack item) {
        if (item == null || item.getType() != filledMapMaterial) {
            return -1;
        }
        
        if (hasMapMeta) {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof MapMeta) {
                try {
                    Boolean hasId = (Boolean) hasMapIdMethod.invoke(meta);
                    if (hasId) {
                        return (Integer) getMapIdMethod.invoke(meta);
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to get map ID via reflection", e);
                }
            }
        } else {
            // 1.12 and below
            return item.getDurability();
        }
        
        return -1;
    }

    @Override
    public ItemStack setMapId(ItemStack item, int mapId) {
        if (hasMapMeta) {
            MapMeta meta = (MapMeta) item.getItemMeta();
            if (meta != null) {
                try {
                    setMapIdMethod.invoke(meta, mapId);
                    item.setItemMeta(meta);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to set map ID via reflection", e);
                }
            }
        } else {
            item.setDurability((short) mapId);
        }
        return item;
    }

    @Override
    public boolean isFilledMap(ItemStack item) {
        return item != null && item.getType() == filledMapMaterial;
    }

    @Override
    public int spawnMapDisplay(Location location, int mapId) {
        if (!hasItemDisplay) {
            return -1;
        }
        
        try {
            World world = location.getWorld();
            if (world == null) return -1;
            
            // Use reflection to spawn ItemDisplay
            Entity display = spawnItemDisplayReflection(world, location, mapId);
            if (display != null) {
                int displayId = nextDisplayId++;
                displayEntities.put(displayId, display);
                return displayId;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to spawn map display", e);
        }
        
        return -1;
    }

    private Entity spawnItemDisplayReflection(World world, Location location, int mapId) {
        try {
            // Get the spawn method that accepts a Consumer
            Method spawnMethod = World.class.getMethod("spawn", Location.class, Class.class, org.bukkit.util.Consumer.class);
            
            // Create the item to display
            ItemStack mapItem = createMapItem(mapId);
            
            // We need to create a consumer that configures the ItemDisplay
            // This is tricky with reflection, so we'll use a simpler spawn and configure after
            Method simpleSpawn = World.class.getMethod("spawn", Location.class, Class.class);
            Entity entity = (Entity) simpleSpawn.invoke(world, location, itemDisplayClass);
            
            // Configure the display
            Method setItemStack = itemDisplayClass.getMethod("setItemStack", ItemStack.class);
            setItemStack.invoke(entity, mapItem);
            
            // Try to set billboard and transform
            try {
                Class<?> billboardClass = Class.forName("org.bukkit.entity.Display$Billboard");
                Method setBillboard = itemDisplayClass.getMethod("setBillboard", billboardClass);
                Object fixedBillboard = Enum.valueOf((Class<Enum>) billboardClass, "FIXED");
                setBillboard.invoke(entity, fixedBillboard);
            } catch (Exception ignored) {
            }
            
            try {
                Class<?> transformClass = Class.forName("org.bukkit.entity.ItemDisplay$ItemDisplayTransform");
                Method setTransform = itemDisplayClass.getMethod("setItemDisplayTransform", transformClass);
                Object fixedTransform = Enum.valueOf((Class<Enum>) transformClass, "FIXED");
                setTransform.invoke(entity, fixedTransform);
            } catch (Exception ignored) {
            }
            
            return entity;
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not spawn ItemDisplay via reflection", e);
            return null;
        }
    }

    @Override
    public void removeDisplay(int entityId) {
        Entity display = displayEntities.remove(entityId);
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    @Override
    public boolean supportsBlockDisplays() {
        return hasItemDisplay;
    }

    @Override
    public void showParticleOutline(Player player, Location location) {
        World world = location.getWorld();
        if (world == null) return;
        
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        
        for (double i = 0; i <= 1; i += 0.1) {
            spawnParticleSafe(player, x + i, y + 1, z);
            spawnParticleSafe(player, x + i, y, z);
            spawnParticleSafe(player, x, y + i, z);
            spawnParticleSafe(player, x + 1, y + i, z);
        }
    }

    private void spawnParticleSafe(Player player, double x, double y, double z) {
        try {
            if (dustOptions != null) {
                player.spawnParticle(dustParticle, x, y, z, 1, dustOptions);
            } else {
                player.spawnParticle(flameParticle, x, y, z, 1, 0, 0, 0, 0);
            }
        } catch (Exception e) {
            // Fallback to basic particle
            try {
                player.spawnParticle(flameParticle, x, y, z, 1, 0, 0, 0, 0);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void registerPacketInterceptor(MapPacketListener listener) {
        // Not available without ProtocolLib
        logger.info("Packet interception requires ProtocolLib");
    }

    @Override
    public void unregisterPacketInterceptor() {
        // No-op
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
}
