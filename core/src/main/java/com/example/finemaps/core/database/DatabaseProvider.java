package com.example.finemaps.core.database;

import com.example.finemaps.api.map.MapData;
import com.example.finemaps.api.map.MultiBlockMap;
import com.example.finemaps.api.map.StoredMap;
import com.example.finemaps.api.map.ArtSummary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Abstract database provider interface for map storage.
 * Implementations must handle SQLite or MySQL connections.
 */
public interface DatabaseProvider {

    /**
     * Initializes the database connection and creates tables.
     *
     * @return CompletableFuture that completes when initialization is done
     */
    CompletableFuture<Void> initialize();

    /**
     * Closes the database connection.
     */
    void shutdown();

    /**
     * Checks if the database is connected.
     *
     * @return true if connected
     */
    boolean isConnected();

    // Map CRUD Operations

    /**
     * Saves a new map to the database.
     *
     * @param pluginId The plugin creating the map
     * @param creatorUUID The UUID of the creator (null for system)
     * @param pixels The pixel data
     * @param palette Optional palette data
     * @param groupId The group ID (0 if not part of a group)
     * @param gridX Grid X position for multi-block maps
     * @param gridY Grid Y position for multi-block maps
     * @param metadata Optional metadata
     * @return CompletableFuture containing the created StoredMap
     */
    CompletableFuture<StoredMap> createMap(String pluginId, UUID creatorUUID, byte[] pixels, 
                                            byte[] palette, long groupId, int gridX, int gridY, 
                                            String metadata);

    /**
     * Gets a map by its ID.
     *
     * @param mapId The map ID
     * @return CompletableFuture containing the StoredMap if found
     */
    CompletableFuture<Optional<StoredMap>> getMap(long mapId);

    /**
     * Gets the pixel data for a map.
     *
     * @param mapId The map ID
     * @return CompletableFuture containing the MapData if found
     */
    CompletableFuture<Optional<MapData>> getMapData(long mapId);

    /**
     * Updates the pixel data of a map.
     *
     * @param mapId The map ID
     * @param pixels The new pixel data
     * @return CompletableFuture that completes with true if successful
     */
    CompletableFuture<Boolean> updateMapPixels(long mapId, byte[] pixels);

    /**
     * Updates a map's metadata.
     *
     * @param mapId The map ID
     * @param metadata The new metadata
     * @return CompletableFuture that completes with true if successful
     */
    CompletableFuture<Boolean> updateMapMetadata(long mapId, String metadata);

    /**
     * Deletes a map.
     *
     * @param mapId The map ID
     * @return CompletableFuture that completes with true if successful
     */
    CompletableFuture<Boolean> deleteMap(long mapId);

    /**
     * Gets all maps belonging to a plugin.
     *
     * @param pluginId The plugin ID
     * @return CompletableFuture containing list of maps
     */
    CompletableFuture<List<StoredMap>> getMapsByPlugin(String pluginId);

    /**
     * Gets all map IDs belonging to a plugin, ordered by ID ascending.
     * Intended for debug/load-test routines that need to iterate maps efficiently
     * without loading full map rows.
     *
     * @param pluginId The plugin ID
     * @return CompletableFuture containing list of map IDs
     */
    CompletableFuture<List<Long>> getMapIdsByPlugin(String pluginId);

    /**
     * Gets all maps created by a player.
     *
     * @param creatorUUID The creator's UUID
     * @return CompletableFuture containing list of maps
     */
    CompletableFuture<List<StoredMap>> getMapsByCreator(UUID creatorUUID);

    /**
     * Gets the count of maps for a plugin.
     *
     * @param pluginId The plugin ID
     * @return CompletableFuture containing the count
     */
    CompletableFuture<Integer> getMapCount(String pluginId);

    /**
     * Gets the count of maps for a player.
     *
     * @param creatorUUID The creator's UUID
     * @return CompletableFuture containing the count
     */
    CompletableFuture<Integer> getMapCountByCreator(UUID creatorUUID);

    // Multi-block map operations

    /**
     * Creates a new multi-block map group.
     *
     * @param pluginId The plugin creating the map
     * @param creatorUUID The creator's UUID
     * @param width Width in blocks
     * @param height Height in blocks
     * @param metadata Optional metadata
     * @return CompletableFuture containing the group ID
     */
    CompletableFuture<Long> createMultiBlockGroup(String pluginId, UUID creatorUUID, 
                                                   int width, int height, String metadata);

    /**
     * Gets a multi-block map by its group ID.
     *
     * @param groupId The group ID
     * @return CompletableFuture containing the MultiBlockMap if found
     */
    CompletableFuture<Optional<MultiBlockMap>> getMultiBlockMap(long groupId);

    /**
     * Gets all maps in a multi-block group.
     *
     * @param groupId The group ID
     * @return CompletableFuture containing list of maps
     */
    CompletableFuture<List<StoredMap>> getMapsByGroup(long groupId);

    /**
     * Deletes an entire multi-block map group.
     *
     * @param groupId The group ID
     * @return CompletableFuture that completes with true if successful
     */
    CompletableFuture<Boolean> deleteMultiBlockGroup(long groupId);

    // Access tracking

    /**
     * Updates the last accessed timestamp for a map.
     *
     * @param mapId The map ID
     */
    void updateLastAccessed(long mapId);

    /**
     * Gets maps that haven't been accessed since a given time.
     *
     * @param beforeTimestamp Unix timestamp
     * @return CompletableFuture containing list of map IDs
     */
    CompletableFuture<List<Long>> getStaleMapIds(long beforeTimestamp);

    /**
     * Gets a multi-block map group by its name (stored in metadata).
     *
     * @param pluginId The plugin ID
     * @param name The art name
     * @return CompletableFuture containing the group ID if found
     */
    CompletableFuture<Optional<Long>> getGroupByName(String pluginId, String name);

    /**
     * Gets a single map by its name (stored in metadata).
     *
     * @param pluginId The plugin ID
     * @param name The art name
     * @return CompletableFuture containing the map ID if found
     */
    CompletableFuture<Optional<Long>> getMapByName(String pluginId, String name);

    /**
     * Checks if an art name is already in use.
     *
     * @param pluginId The plugin ID
     * @param name The art name to check
     * @return CompletableFuture containing true if name exists
     */
    CompletableFuture<Boolean> isNameTaken(String pluginId, String name);

    /**
     * Lists named art entries (single maps + multi-block groups) for a plugin.
     *
     * - Single maps are returned where group_id = 0 and metadata is the art name.
     * - Multi-block groups are returned where metadata is the art name.
     *
     * @param pluginId The plugin ID namespace
     * @return CompletableFuture containing list of art entries
     */
    CompletableFuture<List<ArtSummary>> getArtSummariesByPlugin(String pluginId);
}
