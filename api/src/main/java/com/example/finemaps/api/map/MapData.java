package com.example.finemaps.api.map;

import java.util.Arrays;

/**
 * Represents the pixel data of a map.
 * Maps are always 128x128 pixels with color indices 0-127.
 */
public class MapData {

    public static final int MAP_WIDTH = 128;
    public static final int MAP_HEIGHT = 128;
    public static final int TOTAL_PIXELS = MAP_WIDTH * MAP_HEIGHT;

    private final long mapId;
    private final byte[] pixels;
    private final byte[] palette;

    /**
     * Creates new map data with the given pixels.
     *
     * @param mapId The map ID this data belongs to
     * @param pixels 128x128 array of color indices (16384 bytes)
     */
    public MapData(long mapId, byte[] pixels) {
        this(mapId, pixels, null);
    }

    /**
     * Creates new map data with the given pixels and optional palette.
     *
     * @param mapId The map ID
     * @param pixels The pixel data
     * @param palette Optional custom palette (null for default Minecraft palette)
     */
    public MapData(long mapId, byte[] pixels, byte[] palette) {
        if (pixels == null || pixels.length != TOTAL_PIXELS) {
            throw new IllegalArgumentException("Pixels must be exactly " + TOTAL_PIXELS + " bytes");
        }
        this.mapId = mapId;
        this.pixels = Arrays.copyOf(pixels, pixels.length);
        this.palette = palette != null ? Arrays.copyOf(palette, palette.length) : null;
    }

    /**
     * Gets the map ID this data belongs to.
     *
     * @return The map ID
     */
    public long getMapId() {
        return mapId;
    }

    /**
     * Gets a copy of the pixel data.
     *
     * @return 128x128 array of color indices
     */
    public byte[] getPixels() {
        return Arrays.copyOf(pixels, pixels.length);
    }

    /**
     * Gets the pixel at a specific coordinate.
     *
     * @param x X coordinate (0-127)
     * @param y Y coordinate (0-127)
     * @return The color index at that position
     */
    public byte getPixel(int x, int y) {
        if (x < 0 || x >= MAP_WIDTH || y < 0 || y >= MAP_HEIGHT) {
            throw new IndexOutOfBoundsException("Coordinates out of bounds: " + x + ", " + y);
        }
        return pixels[y * MAP_WIDTH + x];
    }

    /**
     * Gets the raw pixel array directly (no copy).
     * Use with caution - modifications will affect this object.
     *
     * @return The internal pixel array
     */
    public byte[] getPixelsUnsafe() {
        return pixels;
    }

    /**
     * Gets the custom palette if one was set.
     *
     * @return The palette data, or null if using default Minecraft palette
     */
    public byte[] getPalette() {
        return palette != null ? Arrays.copyOf(palette, palette.length) : null;
    }

    /**
     * Checks if this map uses a custom palette.
     *
     * @return true if using custom palette
     */
    public boolean hasCustomPalette() {
        return palette != null;
    }

    /**
     * Creates an empty map data (all transparent).
     *
     * @param mapId The map ID
     * @return Empty map data
     */
    public static MapData createEmpty(long mapId) {
        return new MapData(mapId, new byte[TOTAL_PIXELS]);
    }

    /**
     * Creates map data filled with a single color.
     *
     * @param mapId The map ID
     * @param color The color index to fill with
     * @return Filled map data
     */
    public static MapData createFilled(long mapId, byte color) {
        byte[] pixels = new byte[TOTAL_PIXELS];
        Arrays.fill(pixels, color);
        return new MapData(mapId, pixels);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MapData mapData = (MapData) o;
        return mapId == mapData.mapId && Arrays.equals(pixels, mapData.pixels);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(mapId);
        result = 31 * result + Arrays.hashCode(pixels);
        return result;
    }
}
