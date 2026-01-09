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
import org.bukkit.map.MapView;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final Map<Integer, Entity> displayEntities = new ConcurrentHashMap<>();
    private final Map<Integer, Entity> previewDisplayEntities = new ConcurrentHashMap<>();
    private final Set<Integer> pendingDisplayIds = ConcurrentHashMap.newKeySet();
    private final Set<Integer> pendingPreviewDisplayIds = ConcurrentHashMap.newKeySet();
    private final AtomicInteger nextDisplayId = new AtomicInteger(Integer.MAX_VALUE - 1000000);
    private final AtomicInteger nextPreviewDisplayId = new AtomicInteger(Integer.MAX_VALUE - 2000000);
    private volatile boolean warnedFoliaPreviewFailure = false;
    
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
        // In "basic mode" (no ProtocolLib), we can still force the client to receive updated
        // map pixels by sending the map view to the player. This is critical for smooth
        // in-world item frame animations (held maps update more often naturally).
        if (player == null || !player.isOnline() || mapId < 0) {
            return;
        }

        try {
            @SuppressWarnings("deprecation")
            MapView view = Bukkit.getMap(mapId);
            if (view == null) {
                return;
            }
            try {
                // Bukkit API (available across many versions; deprecated on some).
                player.sendMap(view);
                return;
            } catch (Throwable ignored) {
                // Fall back to reflection on CraftPlayer (older forks / shaded APIs).
            }

            // Reflection fallback: CraftPlayer#sendMap(MapView)
            try {
                Method m = player.getClass().getMethod("sendMap", MapView.class);
                m.invoke(player, view);
            } catch (Throwable ignored) {
                // Best-effort; if we can't send, animation will fall back to render-triggered updates.
            }
        } catch (Throwable t) {
            // Keep this silent; this runs frequently during animations.
            logger.log(Level.FINEST, "Failed to send map update via Bukkit adapter", t);
        }
    }

    @Override
    public void sendPartialMapUpdate(Player player, int mapId, int startX, int startY,
                                      int width, int height, byte[] pixels) {
        // Not truly supported without ProtocolLib; best-effort full update.
        sendMapUpdate(player, mapId, pixels);
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
            
            // On Folia, this must run on the region thread that owns the target location.
            if (isFolia) {
                int displayId = nextDisplayId.getAndIncrement();
                pendingDisplayIds.add(displayId);

                Plugin plugin = getOwningPlugin();
                if (plugin == null) {
                    pendingDisplayIds.remove(displayId);
                    return -1;
                }

                int cx = location.getBlockX() >> 4;
                int cz = location.getBlockZ() >> 4;
                try {
                    Bukkit.getRegionScheduler().run(plugin, world, cx, cz, ignored -> {
                        try {
                            Entity display = spawnItemDisplayReflection(world, location, mapId);
                            if (display == null) {
                                pendingDisplayIds.remove(displayId);
                                return;
                            }
                            if (!pendingDisplayIds.remove(displayId)) {
                                // Cancelled before spawn completed
                                try {
                                    display.remove();
                                } catch (Throwable ignored2) {
                                }
                                return;
                            }
                            displayEntities.put(displayId, display);
                        } catch (Throwable ignored2) {
                            pendingDisplayIds.remove(displayId);
                        }
                    });
                    return displayId;
                } catch (Throwable t) {
                    pendingDisplayIds.remove(displayId);
                    if (!warnedFoliaPreviewFailure) {
                        warnedFoliaPreviewFailure = true;
                        logger.log(Level.WARNING, "Folia rejected ItemDisplay spawn scheduling; map previews may be missing", t);
                    }
                    return -1;
                }
            }

            // Non-Folia: spawn directly
            Entity display = spawnItemDisplayReflection(world, location, mapId);
            if (display != null) {
                int displayId = nextDisplayId.getAndIncrement();
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
        pendingDisplayIds.remove(entityId);
        Entity display = displayEntities.remove(entityId);
        if (display == null) return;

        if (isFolia) {
            Plugin plugin = getOwningPlugin();
            if (plugin != null) {
                try {
                    display.getScheduler().run(plugin, ignored -> {
                        try {
                            if (display.isValid()) display.remove();
                        } catch (Throwable ignored2) {
                        }
                    }, () -> {
                    });
                    return;
                } catch (Throwable ignored) {
                    // fall through
                }
            }
        }

        try {
            if (display.isValid()) display.remove();
        } catch (Throwable ignored) {
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
            
            // On Folia, this must run on the region thread that owns the target location.
            if (isFolia) {
                int displayId = nextPreviewDisplayId.getAndIncrement();
                pendingPreviewDisplayIds.add(displayId);

                Plugin plugin = getOwningPlugin();
                if (plugin == null) {
                    pendingPreviewDisplayIds.remove(displayId);
                    return -1;
                }

                int cx = location.getBlockX() >> 4;
                int cz = location.getBlockZ() >> 4;
                try {
                    Bukkit.getRegionScheduler().run(plugin, world, cx, cz, ignored -> {
                        try {
                            Entity display = spawnBlockDisplayReflection(world, location, valid, scaleX, scaleY, scaleZ);
                            if (display == null) {
                                pendingPreviewDisplayIds.remove(displayId);
                                return;
                            }
                            if (!pendingPreviewDisplayIds.remove(displayId)) {
                                // Cancelled before spawn completed
                                try {
                                    display.remove();
                                } catch (Throwable ignored2) {
                                }
                                return;
                            }
                            previewDisplayEntities.put(displayId, display);
                        } catch (Throwable ignored2) {
                            pendingPreviewDisplayIds.remove(displayId);
                        }
                    });
                    return displayId;
                } catch (Throwable t) {
                    pendingPreviewDisplayIds.remove(displayId);
                    if (!warnedFoliaPreviewFailure) {
                        warnedFoliaPreviewFailure = true;
                        logger.log(Level.WARNING, "Folia rejected BlockDisplay spawn scheduling; placement preview may be missing", t);
                    }
                    return -1;
                }
            }

            // Non-Folia: spawn directly
            Entity display = spawnBlockDisplayReflection(world, location, valid, scaleX, scaleY, scaleZ);
            if (display != null) {
                int displayId = nextPreviewDisplayId.getAndIncrement();
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
                
                // Important: BlockDisplay models are defined in [0..1] space.
                // If we only scale, the block expands from the local origin (corner) and the display appears offset.
                // Translate by -0.5 * scale on each axis to keep the scaled shape centered on the entity location.
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
        pendingPreviewDisplayIds.remove(entityId);
        Entity display = previewDisplayEntities.remove(entityId);
        if (display == null) return;

        if (isFolia) {
            Plugin plugin = getOwningPlugin();
            if (plugin != null) {
                try {
                    display.getScheduler().run(plugin, ignored -> {
                        try {
                            if (display.isValid()) display.remove();
                        } catch (Throwable ignored2) {
                        }
                    }, () -> {
                    });
                    return;
                } catch (Throwable ignored) {
                    // fall through
                }
            }
        }

        try {
            if (display.isValid()) display.remove();
        } catch (Throwable ignored) {
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

    private Plugin getOwningPlugin() {
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("FineMaps");
            return (p != null && p.isEnabled()) ? p : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
