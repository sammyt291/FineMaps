package com.example.finemaps.core.render;

import com.example.finemaps.api.map.MapData;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.map.MapView;
import org.bukkit.map.MapRenderer;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages Bukkit MapViews for our custom maps.
 * Creates real Minecraft maps and attaches our custom renderers.
 */
public class MapViewManager {

    private final Plugin plugin;
    private final Logger logger;
    
    // Map from database map ID -> Bukkit map ID
    private final Map<Long, Integer> dbToBukkitId = new ConcurrentHashMap<>();
    
    // Map from Bukkit map ID -> database map ID
    private final Map<Integer, Long> bukkitToDbId = new ConcurrentHashMap<>();
    
    // Map from database map ID -> our custom renderer
    private final Map<Long, FineMapsRenderer> renderers = new ConcurrentHashMap<>();
    
    // Map from database map ID -> MapView
    private final Map<Long, MapView> mapViews = new ConcurrentHashMap<>();

    public MapViewManager(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Gets or creates a Bukkit map ID for a database map ID.
     * This creates a real Minecraft map that can be displayed in item frames.
     *
     * @param dbMapId The database map ID
     * @param pixelSupplier Supplier for pixel data (called lazily)
     * @return The Bukkit map ID
     */
    public int getOrCreateBukkitMapId(long dbMapId, Supplier<byte[]> pixelSupplier) {
        // Check if we already have a mapping
        Integer existing = dbToBukkitId.get(dbMapId);
        if (existing != null) {
            return existing;
        }
        
        // Create a new map
        return createBukkitMap(dbMapId, pixelSupplier);
    }

    /**
     * Gets or creates a Bukkit map with pre-loaded pixel data.
     *
     * @param dbMapId The database map ID
     * @param pixels The pixel data
     * @return The Bukkit map ID
     */
    public int getOrCreateBukkitMapId(long dbMapId, byte[] pixels) {
        // Check if we already have a mapping
        Integer existing = dbToBukkitId.get(dbMapId);
        if (existing != null) {
            // Update the renderer with new pixels
            FineMapsRenderer renderer = renderers.get(dbMapId);
            if (renderer != null) {
                renderer.updatePixels(pixels);
            }
            return existing;
        }
        
        // Create a new map with the pixels
        return createBukkitMapWithPixels(dbMapId, pixels);
    }

    /**
     * Creates a new Bukkit map for a database map ID.
     */
    @SuppressWarnings("deprecation")
    private int createBukkitMap(long dbMapId, Supplier<byte[]> pixelSupplier) {
        World world = getDefaultWorld();
        if (world == null) {
            logger.severe("No worlds loaded! Cannot create map.");
            return -1;
        }
        
        try {
            // Create a new map
            MapView mapView = Bukkit.createMap(world);
            int bukkitId = mapView.getId();
            
            // Remove default renderers
            for (MapRenderer renderer : mapView.getRenderers()) {
                mapView.removeRenderer(renderer);
            }
            
            // Add our custom renderer
            FineMapsRenderer customRenderer = new FineMapsRenderer(dbMapId, pixelSupplier);
            mapView.addRenderer(customRenderer);
            
            // Configure the map view
            mapView.setScale(MapView.Scale.NORMAL);
            mapView.setTrackingPosition(false);
            mapView.setUnlimitedTracking(false);
            
            // Try to lock the map if available (1.14+)
            try {
                mapView.setLocked(true);
            } catch (NoSuchMethodError ignored) {
                // Method doesn't exist in older versions
            }
            
            // Store mappings
            dbToBukkitId.put(dbMapId, bukkitId);
            bukkitToDbId.put(bukkitId, dbMapId);
            renderers.put(dbMapId, customRenderer);
            mapViews.put(dbMapId, mapView);
            
            logger.fine("Created Bukkit map " + bukkitId + " for database map " + dbMapId);
            
            return bukkitId;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to create Bukkit map for DB ID " + dbMapId, e);
            return -1;
        }
    }

    /**
     * Creates a new Bukkit map with pre-loaded pixel data.
     */
    @SuppressWarnings("deprecation")
    private int createBukkitMapWithPixels(long dbMapId, byte[] pixels) {
        World world = getDefaultWorld();
        if (world == null) {
            logger.severe("No worlds loaded! Cannot create map.");
            return -1;
        }
        
        try {
            // Create a new map
            MapView mapView = Bukkit.createMap(world);
            int bukkitId = mapView.getId();
            
            // Remove default renderers
            for (MapRenderer renderer : mapView.getRenderers()) {
                mapView.removeRenderer(renderer);
            }
            
            // Add our custom renderer with the pixel data
            FineMapsRenderer customRenderer = new FineMapsRenderer(dbMapId, pixels);
            mapView.addRenderer(customRenderer);
            
            // Configure the map view
            mapView.setScale(MapView.Scale.NORMAL);
            mapView.setTrackingPosition(false);
            mapView.setUnlimitedTracking(false);
            
            // Try to lock the map if available (1.14+)
            try {
                mapView.setLocked(true);
            } catch (NoSuchMethodError ignored) {
                // Method doesn't exist in older versions
            }
            
            // Store mappings
            dbToBukkitId.put(dbMapId, bukkitId);
            bukkitToDbId.put(bukkitId, dbMapId);
            renderers.put(dbMapId, customRenderer);
            mapViews.put(dbMapId, mapView);
            
            logger.fine("Created Bukkit map " + bukkitId + " for database map " + dbMapId);
            
            return bukkitId;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to create Bukkit map for DB ID " + dbMapId, e);
            return -1;
        }
    }

    /**
     * Gets the Bukkit map ID for a database map ID.
     *
     * @param dbMapId The database map ID
     * @return The Bukkit map ID, or -1 if not found
     */
    public int getBukkitMapId(long dbMapId) {
        Integer id = dbToBukkitId.get(dbMapId);
        return id != null ? id : -1;
    }

    /**
     * Gets the database map ID for a Bukkit map ID.
     *
     * @param bukkitMapId The Bukkit map ID
     * @return The database map ID, or -1 if not managed by us
     */
    public long getDbMapId(int bukkitMapId) {
        Long id = bukkitToDbId.get(bukkitMapId);
        return id != null ? id : -1;
    }

    /**
     * Checks if a Bukkit map ID is managed by us.
     *
     * @param bukkitMapId The Bukkit map ID
     * @return true if this is one of our maps
     */
    public boolean isOurMap(int bukkitMapId) {
        return bukkitToDbId.containsKey(bukkitMapId);
    }

    /**
     * Updates the pixel data for a map.
     *
     * @param dbMapId The database map ID
     * @param pixels The new pixel data
     */
    public void updateMapPixels(long dbMapId, byte[] pixels) {
        FineMapsRenderer renderer = renderers.get(dbMapId);
        if (renderer != null) {
            renderer.updatePixels(pixels);
        }
    }

    /**
     * Marks a map as needing re-render.
     *
     * @param dbMapId The database map ID
     */
    public void invalidateMap(long dbMapId) {
        FineMapsRenderer renderer = renderers.get(dbMapId);
        if (renderer != null) {
            renderer.markDirty();
        }
    }

    /**
     * Gets the MapView for a database map ID.
     *
     * @param dbMapId The database map ID
     * @return The MapView, or empty if not found
     */
    public Optional<MapView> getMapView(long dbMapId) {
        return Optional.ofNullable(mapViews.get(dbMapId));
    }

    /**
     * Gets the renderer for a database map ID.
     *
     * @param dbMapId The database map ID
     * @return The renderer, or empty if not found
     */
    public Optional<FineMapsRenderer> getRenderer(long dbMapId) {
        return Optional.ofNullable(renderers.get(dbMapId));
    }

    /**
     * Releases a map.
     *
     * @param dbMapId The database map ID
     */
    public void releaseMap(long dbMapId) {
        Integer bukkitId = dbToBukkitId.remove(dbMapId);
        if (bukkitId != null) {
            bukkitToDbId.remove(bukkitId);
        }
        renderers.remove(dbMapId);
        mapViews.remove(dbMapId);
    }

    /**
     * Clears all managed maps.
     */
    public void clear() {
        dbToBukkitId.clear();
        bukkitToDbId.clear();
        renderers.clear();
        mapViews.clear();
    }

    /**
     * Gets the first available world for map creation.
     */
    private World getDefaultWorld() {
        // Try to get the main overworld first
        World world = Bukkit.getWorld("world");
        if (world != null) {
            return world;
        }
        
        // Fall back to any available world
        if (!Bukkit.getWorlds().isEmpty()) {
            return Bukkit.getWorlds().get(0);
        }
        
        return null;
    }

    /**
     * Gets statistics about managed maps.
     *
     * @return Number of managed maps
     */
    public int getManagedMapCount() {
        return dbToBukkitId.size();
    }
}
