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
    private final Map<Integer, Entity> previewDisplayEntities = new HashMap<>();
    private int nextDisplayId = Integer.MAX_VALUE - 1000000;
    private int nextPreviewDisplayId = Integer.MAX_VALUE - 2000000;
    
    // Block display class for preview
    private Class<?> blockDisplayClass;
    
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
            // Also try to detect BlockDisplay
            try {
                blockDisplayClass = Class.forName("org.bukkit.entity.BlockDisplay");
            } catch (ClassNotFoundException e) {
                blockDisplayClass = null;
            }
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
        // When using MapViewManager, the MapRenderer handles rendering
        // This method is now a no-op since we use Bukkit's MapRenderer API
        // The renderer will automatically send map data to clients
    }

    @Override
    public void sendPartialMapUpdate(Player player, int mapId, int startX, int startY,
                                      int width, int height, byte[] pixels) {
        // Not available without ProtocolLib
    }

    @Override
    @SuppressWarnings("deprecation")
    public ItemStack createMapItem(int mapId) {
        ItemStack item = new ItemStack(filledMapMaterial);
        
        if (hasMapMeta) {
            // 1.13+ - use MapMeta
            try {
                MapMeta meta = (MapMeta) item.getItemMeta();
                if (meta != null) {
                    // Try direct method first (most versions)
                    try {
                        meta.setMapId(mapId);
                    } catch (NoSuchMethodError e) {
                        // Fall back to reflection
                        setMapIdMethod.invoke(meta, mapId);
                    }
                    item.setItemMeta(meta);
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to set map ID, trying alternative method", e);
                // Try creating with map view
                try {
                    org.bukkit.map.MapView view = org.bukkit.Bukkit.getMap(mapId);
                    if (view != null) {
                        MapMeta meta = (MapMeta) item.getItemMeta();
                        if (meta != null) {
                            // Some versions use setMapView instead
                            try {
                                java.lang.reflect.Method setMapView = MapMeta.class.getMethod("setMapView", org.bukkit.map.MapView.class);
                                setMapView.invoke(meta, view);
                                item.setItemMeta(meta);
                            } catch (NoSuchMethodException ignored) {
                            }
                        }
                    }
                } catch (Exception ignored) {
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
    @SuppressWarnings("unchecked")
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

    @SuppressWarnings("unchecked")
    private Entity spawnItemDisplayReflection(World world, Location location, int mapId) {
        try {
            // Create the item to display
            ItemStack mapItem = createMapItem(mapId);
            
            // Try simpler spawn method
            Method simpleSpawn = World.class.getMethod("spawn", Location.class, Class.class);
            Entity entity = (Entity) simpleSpawn.invoke(world, location, itemDisplayClass);
            
            // Configure the display - set the item
            try {
                Method setItemStack = itemDisplayClass.getMethod("setItemStack", ItemStack.class);
                setItemStack.invoke(entity, mapItem);
            } catch (NoSuchMethodException e) {
                logger.fine("setItemStack not found on ItemDisplay");
            }
            
            // Try to set billboard to FIXED
            try {
                Class<?> displayClass = Class.forName("org.bukkit.entity.Display");
                Class<?> billboardClass = Class.forName("org.bukkit.entity.Display$Billboard");
                Method setBillboard = displayClass.getMethod("setBillboard", billboardClass);
                Object fixedBillboard = Enum.valueOf((Class<Enum>) billboardClass, "FIXED");
                setBillboard.invoke(entity, fixedBillboard);
            } catch (Exception ignored) {
            }
            
            // Try to set transform to FIXED (makes it appear like in item frame)
            try {
                Class<?> transformClass = Class.forName("org.bukkit.entity.ItemDisplay$ItemDisplayTransform");
                Method setTransform = itemDisplayClass.getMethod("setItemDisplayTransform", transformClass);
                Object fixedTransform = Enum.valueOf((Class<Enum>) transformClass, "FIXED");
                setTransform.invoke(entity, fixedTransform);
            } catch (Exception ignored) {
            }
            
            // Set view range
            try {
                Class<?> displayClass = Class.forName("org.bukkit.entity.Display");
                Method setViewRange = displayClass.getMethod("setViewRange", float.class);
                setViewRange.invoke(entity, 64.0f);
            } catch (Exception ignored) {
            }
            
            return entity;
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not spawn ItemDisplay via reflection: " + e.getMessage());
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
        return hasItemDisplay && blockDisplayClass != null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public int spawnPreviewBlockDisplay(Location location, boolean valid, float scaleX, float scaleY, float scaleZ) {
        if (blockDisplayClass == null) {
            return -1;
        }
        
        try {
            World world = location.getWorld();
            if (world == null) return -1;
            
            // Spawn BlockDisplay
            Entity display = spawnBlockDisplayReflection(world, location, valid, scaleX, scaleY, scaleZ);
            if (display != null) {
                int displayId = nextPreviewDisplayId++;
                previewDisplayEntities.put(displayId, display);
                return displayId;
            }
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to spawn preview block display", e);
        }
        
        return -1;
    }

    @SuppressWarnings("unchecked")
    private Entity spawnBlockDisplayReflection(World world, Location location, boolean valid, float scaleX, float scaleY, float scaleZ) {
        try {
            // Spawn the BlockDisplay entity
            Method simpleSpawn = World.class.getMethod("spawn", Location.class, Class.class);
            Entity entity = (Entity) simpleSpawn.invoke(world, location, blockDisplayClass);
            
            // Get the block material for the color (lime/green concrete for valid, red concrete for invalid)
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
            
            // Set the block data
            try {
                org.bukkit.block.data.BlockData blockData = blockMaterial.createBlockData();
                Method setBlock = blockDisplayClass.getMethod("setBlock", org.bukkit.block.data.BlockData.class);
                setBlock.invoke(entity, blockData);
            } catch (Exception e) {
                logger.fine("Could not set block data on BlockDisplay: " + e.getMessage());
            }
            
            // Try to set billboard to FIXED
            try {
                Class<?> displayClass = Class.forName("org.bukkit.entity.Display");
                Class<?> billboardClass = Class.forName("org.bukkit.entity.Display$Billboard");
                Method setBillboard = displayClass.getMethod("setBillboard", billboardClass);
                Object fixedBillboard = Enum.valueOf((Class<Enum>) billboardClass, "FIXED");
                setBillboard.invoke(entity, fixedBillboard);
            } catch (Exception ignored) {
            }
            
            // Set transformation to make it smaller (outline-like appearance)
            try {
                Class<?> displayClass = Class.forName("org.bukkit.entity.Display");
                Class<?> transformationClass = Class.forName("org.bukkit.util.Transformation");
                Class<?> vector3fClass = Class.forName("org.joml.Vector3f");
                Class<?> quaternionfClass = Class.forName("org.joml.Quaternionf");
                
                // Create a small scale transformation (0.02 blocks = thin outline)
                Object scale = vector3fClass.getConstructor(float.class, float.class, float.class)
                    .newInstance(scaleX, scaleY, scaleZ);
                Object translation = vector3fClass.getConstructor(float.class, float.class, float.class)
                    .newInstance(0.0f, 0.0f, 0.0f);
                Object leftRotation = quaternionfClass.getConstructor().newInstance();
                Object rightRotation = quaternionfClass.getConstructor().newInstance();
                
                Object transformation = transformationClass.getConstructor(
                    vector3fClass, quaternionfClass, vector3fClass, quaternionfClass
                ).newInstance(translation, leftRotation, scale, rightRotation);
                
                Method setTransformation = displayClass.getMethod("setTransformation", transformationClass);
                setTransformation.invoke(entity, transformation);
            } catch (Exception e) {
                logger.fine("Could not set transformation on BlockDisplay: " + e.getMessage());
            }
            
            // Set view range to be visible nearby
            try {
                Class<?> displayClass = Class.forName("org.bukkit.entity.Display");
                Method setViewRange = displayClass.getMethod("setViewRange", float.class);
                setViewRange.invoke(entity, 32.0f);
            } catch (Exception ignored) {
            }
            
            // Make it glow for better visibility
            try {
                Method setGlowing = Entity.class.getMethod("setGlowing", boolean.class);
                setGlowing.invoke(entity, true);
            } catch (Exception ignored) {
            }
            
            return entity;
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not spawn BlockDisplay via reflection: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void removePreviewDisplay(int entityId) {
        Entity display = previewDisplayEntities.remove(entityId);
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    @Override
    public void showParticleOutline(Player player, Location location) {
        World world = location.getWorld();
        if (world == null) return;
        
        double x = location.getBlockX();
        double y = location.getBlockY();
        double z = location.getBlockZ();
        
        // Draw outline of a block
        double step = 0.2;
        for (double i = 0; i <= 1; i += step) {
            // Top edge
            spawnParticleSafe(player, x + i, y + 1, z);
            spawnParticleSafe(player, x + i, y + 1, z + 1);
            // Bottom edge
            spawnParticleSafe(player, x + i, y, z);
            spawnParticleSafe(player, x + i, y, z + 1);
            // Vertical edges
            spawnParticleSafe(player, x, y + i, z);
            spawnParticleSafe(player, x + 1, y + i, z);
            spawnParticleSafe(player, x, y + i, z + 1);
            spawnParticleSafe(player, x + 1, y + i, z + 1);
            // Side edges
            spawnParticleSafe(player, x, y, z + i);
            spawnParticleSafe(player, x + 1, y, z + i);
            spawnParticleSafe(player, x, y + 1, z + i);
            spawnParticleSafe(player, x + 1, y + 1, z + i);
        }
    }

    private void spawnParticleSafe(Player player, double x, double y, double z) {
        try {
            if (dustOptions != null && dustParticle != null) {
                player.spawnParticle(dustParticle, x, y, z, 1, 0, 0, 0, 0, dustOptions);
            } else if (flameParticle != null) {
                player.spawnParticle(flameParticle, x, y, z, 1, 0, 0, 0, 0);
            }
        } catch (Exception e) {
            // Fallback to basic particle
            try {
                if (flameParticle != null) {
                    player.spawnParticle(flameParticle, x, y, z, 1, 0, 0, 0, 0);
                }
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
