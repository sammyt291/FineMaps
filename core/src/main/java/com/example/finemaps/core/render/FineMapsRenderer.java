package com.example.finemaps.core.render;

import com.example.finemaps.api.map.MapData;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Custom MapRenderer that renders our stored pixel data to Minecraft maps.
 * This is the proper way to display custom map images in Minecraft.
 */
public class FineMapsRenderer extends MapRenderer {

    private final long mapId;
    private final Supplier<byte[]> pixelSupplier;
    
    // Track which players have already received this map to avoid re-rendering
    private final Set<UUID> renderedPlayers = ConcurrentHashMap.newKeySet();
    
    // Cached pixel data
    private byte[] cachedPixels = null;
    private boolean needsUpdate = true;

    /**
     * Creates a new map renderer.
     *
     * @param mapId The database map ID (for tracking)
     * @param pixelSupplier Supplier that provides the pixel data
     */
    public FineMapsRenderer(long mapId, Supplier<byte[]> pixelSupplier) {
        // contextual=false means render for all players, not per-player
        super(false);
        this.mapId = mapId;
        this.pixelSupplier = pixelSupplier;
    }

    /**
     * Creates a renderer with pre-loaded pixels.
     *
     * @param mapId The database map ID
     * @param pixels The pixel data
     */
    public FineMapsRenderer(long mapId, byte[] pixels) {
        super(false);
        this.mapId = mapId;
        this.pixelSupplier = () -> pixels;
        this.cachedPixels = pixels;
        this.needsUpdate = false;
    }

    @Override
    public void render(MapView mapView, MapCanvas canvas, Player player) {
        // Only render once per player (maps are cached client-side)
        if (player != null && renderedPlayers.contains(player.getUniqueId())) {
            return;
        }
        
        // Get pixel data
        byte[] pixels = cachedPixels;
        if (pixels == null || needsUpdate) {
            pixels = pixelSupplier.get();
            if (pixels == null || pixels.length != MapData.TOTAL_PIXELS) {
                return; // Invalid data
            }
            cachedPixels = pixels;
            needsUpdate = false;
        }
        
        // Render pixels to canvas
        for (int y = 0; y < MapData.MAP_HEIGHT; y++) {
            for (int x = 0; x < MapData.MAP_WIDTH; x++) {
                int index = y * MapData.MAP_WIDTH + x;
                canvas.setPixel(x, y, pixels[index]);
            }
        }
        
        // Mark player as rendered
        if (player != null) {
            renderedPlayers.add(player.getUniqueId());
        }
    }

    /**
     * Marks this renderer as needing an update.
     * Next render call will fetch fresh pixel data.
     */
    public void markDirty() {
        needsUpdate = true;
        renderedPlayers.clear(); // Force re-render for all players
    }

    /**
     * Updates the pixel data directly.
     *
     * @param pixels The new pixel data
     */
    public void updatePixels(byte[] pixels) {
        if (pixels != null && pixels.length == MapData.TOTAL_PIXELS) {
            this.cachedPixels = pixels;
            this.needsUpdate = false;
            renderedPlayers.clear(); // Force re-render for all players
        }
    }

    /**
     * Removes a player from the rendered set (e.g., when they rejoin).
     *
     * @param playerUUID The player's UUID
     */
    public void invalidatePlayer(UUID playerUUID) {
        renderedPlayers.remove(playerUUID);
    }

    /**
     * Gets the database map ID.
     *
     * @return The map ID
     */
    public long getMapId() {
        return mapId;
    }
}
