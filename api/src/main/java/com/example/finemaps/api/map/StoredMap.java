package com.example.finemaps.api.map;

import java.util.UUID;

/**
 * Represents a map stored in the database.
 */
public class StoredMap {

    private final long id;
    private final String pluginId;
    private final UUID creatorUUID;
    private final long groupId;
    private final int gridX;
    private final int gridY;
    private final long createdAt;
    private long lastAccessed;
    private String metadata;

    public StoredMap(long id, String pluginId, UUID creatorUUID, long groupId, 
                     int gridX, int gridY, long createdAt, long lastAccessed, String metadata) {
        this.id = id;
        this.pluginId = pluginId;
        this.creatorUUID = creatorUUID;
        this.groupId = groupId;
        this.gridX = gridX;
        this.gridY = gridY;
        this.createdAt = createdAt;
        this.lastAccessed = lastAccessed;
        this.metadata = metadata;
    }

    /**
     * Gets the unique 64-bit ID of this map.
     *
     * @return The map ID
     */
    public long getId() {
        return id;
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
     * Gets the group ID if this map is part of a multi-block map.
     *
     * @return The group ID, or 0 if not part of a group
     */
    public long getGroupId() {
        return groupId;
    }

    /**
     * Gets the X position in a multi-block map grid.
     *
     * @return The grid X position (0-based)
     */
    public int getGridX() {
        return gridX;
    }

    /**
     * Gets the Y position in a multi-block map grid.
     *
     * @return The grid Y position (0-based)
     */
    public int getGridY() {
        return gridY;
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
     * Gets the timestamp when this map was last accessed.
     *
     * @return Unix timestamp in milliseconds
     */
    public long getLastAccessed() {
        return lastAccessed;
    }

    /**
     * Sets the last accessed timestamp.
     *
     * @param lastAccessed Unix timestamp in milliseconds
     */
    public void setLastAccessed(long lastAccessed) {
        this.lastAccessed = lastAccessed;
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
     * Checks if this map is part of a multi-block map.
     *
     * @return true if part of a group
     */
    public boolean isMultiBlock() {
        return groupId > 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StoredMap storedMap = (StoredMap) o;
        return id == storedMap.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public String toString() {
        return "StoredMap{" +
                "id=" + id +
                ", pluginId='" + pluginId + '\'' +
                ", groupId=" + groupId +
                ", gridX=" + gridX +
                ", gridY=" + gridY +
                '}';
    }
}
