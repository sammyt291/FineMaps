package com.example.finemaps.api.map;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Represents a multi-block map composed of multiple single maps arranged in a grid.
 */
public class MultiBlockMap {

    private final long groupId;
    private final String pluginId;
    private final UUID creatorUUID;
    private final int width;
    private final int height;
    private final List<StoredMap> maps;
    private final long createdAt;
    private String metadata;

    public MultiBlockMap(long groupId, String pluginId, UUID creatorUUID, 
                         int width, int height, List<StoredMap> maps, 
                         long createdAt, String metadata) {
        this.groupId = groupId;
        this.pluginId = pluginId;
        this.creatorUUID = creatorUUID;
        this.width = width;
        this.height = height;
        this.maps = maps;
        this.createdAt = createdAt;
        this.metadata = metadata;
    }

    /**
     * Gets the unique group ID for this multi-block map.
     *
     * @return The group ID
     */
    public long getGroupId() {
        return groupId;
    }

    /**
     * Gets the plugin ID that created this map.
     *
     * @return The plugin identifier
     */
    public String getPluginId() {
        return pluginId;
    }

    /**
     * Gets the UUID of the player who created this map.
     *
     * @return The creator's UUID, or null if created by system
     */
    public UUID getCreatorUUID() {
        return creatorUUID;
    }

    /**
     * Gets the width of this multi-block map in blocks.
     *
     * @return Width in map blocks
     */
    public int getWidth() {
        return width;
    }

    /**
     * Gets the height of this multi-block map in blocks.
     *
     * @return Height in map blocks
     */
    public int getHeight() {
        return height;
    }

    /**
     * Gets the total number of map blocks.
     *
     * @return Total count
     */
    public int getTotalMaps() {
        return width * height;
    }

    /**
     * Gets all maps in this multi-block map.
     *
     * @return Unmodifiable list of stored maps
     */
    public List<StoredMap> getMaps() {
        return Collections.unmodifiableList(maps);
    }

    /**
     * Gets a specific map by its grid position.
     *
     * @param x X position in grid (0-based)
     * @param y Y position in grid (0-based)
     * @return The map at that position, or null if not found
     */
    public StoredMap getMapAt(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return null;
        }
        for (StoredMap map : maps) {
            if (map.getGridX() == x && map.getGridY() == y) {
                return map;
            }
        }
        return null;
    }

    /**
     * Gets all map IDs in this multi-block map.
     *
     * @return Array of map IDs
     */
    public long[] getMapIds() {
        return maps.stream().mapToLong(StoredMap::getId).toArray();
    }

    /**
     * Gets the timestamp when this map was created.
     *
     * @return Unix timestamp in milliseconds
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * Gets optional metadata stored with this map.
     *
     * @return The metadata string, or null if none
     */
    public String getMetadata() {
        return metadata;
    }

    /**
     * Sets optional metadata for this map.
     *
     * @param metadata The metadata string
     */
    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    /**
     * Gets the total pixel width of the combined image.
     *
     * @return Total width in pixels
     */
    public int getTotalPixelWidth() {
        return width * MapData.MAP_WIDTH;
    }

    /**
     * Gets the total pixel height of the combined image.
     *
     * @return Total height in pixels
     */
    public int getTotalPixelHeight() {
        return height * MapData.MAP_HEIGHT;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MultiBlockMap that = (MultiBlockMap) o;
        return groupId == that.groupId;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(groupId);
    }

    @Override
    public String toString() {
        return "MultiBlockMap{" +
                "groupId=" + groupId +
                ", pluginId='" + pluginId + '\'' +
                ", width=" + width +
                ", height=" + height +
                ", maps=" + maps.size() +
                '}';
    }
}
