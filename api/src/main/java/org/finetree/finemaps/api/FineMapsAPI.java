package org.finetree.finemaps.api;

import org.finetree.finemaps.api.map.StoredMap;
import org.finetree.finemaps.api.map.MapData;
import org.finetree.finemaps.api.map.MultiBlockMap;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Main API for the FineMaps plugin.
 * Allows other plugins to store and retrieve custom maps from the database.
 */
public interface FineMapsAPI {

    /**
     * Get the singleton instance of the API.
     * @return The API instance, or null if the plugin is not loaded
     */
    static FineMapsAPI getInstance() {
        return FineMapsAPIProvider.getAPI();
    }

    /**
     * Creates a new map from raw pixel data and stores it in the database.
     *
     * @param pluginId Unique identifier for your plugin (used for namespacing)
     * @param pixels 128x128 array of color indices (0-127 for map colors)
     * @return CompletableFuture containing the created StoredMap
     */
    CompletableFuture<StoredMap> createMap(String pluginId, byte[] pixels);

    /**
     * Creates a new map from a BufferedImage.
     * The image will be resized to 128x128 and converted to map colors.
     *
     * @param pluginId Unique identifier for your plugin
     * @param image The image to convert
     * @param dither Whether to apply dithering for better color approximation
     * @return CompletableFuture containing the created StoredMap
     */
    CompletableFuture<StoredMap> createMapFromImage(String pluginId, BufferedImage image, boolean dither);

    /**
     * Creates a multi-block map from a large image.
     *
     * @param pluginId Unique identifier for your plugin
     * @param image The image to convert
     * @param widthBlocks Width in map blocks (each block is 128 pixels)
     * @param heightBlocks Height in map blocks
     * @param dither Whether to apply dithering
     * @return CompletableFuture containing the created MultiBlockMap
     */
    CompletableFuture<MultiBlockMap> createMultiBlockMap(String pluginId, BufferedImage image, 
                                                          int widthBlocks, int heightBlocks, boolean dither);

    /**
     * Retrieves a stored map by its unique ID.
     *
     * @param mapId The 64-bit map ID
     * @return CompletableFuture containing the StoredMap if found
     */
    CompletableFuture<Optional<StoredMap>> getMap(long mapId);

    /**
     * Retrieves a multi-block map by its group ID.
     *
     * @param groupId The group ID for the multi-block map
     * @return CompletableFuture containing the MultiBlockMap if found
     */
    CompletableFuture<Optional<MultiBlockMap>> getMultiBlockMap(long groupId);

    /**
     * Gets all maps belonging to a specific plugin.
     *
     * @param pluginId The plugin identifier
     * @return CompletableFuture containing a list of StoredMaps
     */
    CompletableFuture<List<StoredMap>> getMapsByPlugin(String pluginId);

    /**
     * Deletes a map from the database.
     *
     * @param mapId The map ID to delete
     * @return CompletableFuture that completes when deletion is done
     */
    CompletableFuture<Boolean> deleteMap(long mapId);

    /**
     * Deletes an entire multi-block map group.
     *
     * @param groupId The group ID to delete
     * @return CompletableFuture that completes when deletion is done
     */
    CompletableFuture<Boolean> deleteMultiBlockMap(long groupId);

    /**
     * Updates the pixel data of an existing map.
     *
     * @param mapId The map ID to update
     * @param pixels New 128x128 pixel data
     * @return CompletableFuture that completes when update is done
     */
    CompletableFuture<Boolean> updateMapPixels(long mapId, byte[] pixels);

    /**
     * Gets the current pixel data for a map.
     *
     * @param mapId The map ID
     * @return CompletableFuture containing the MapData if found
     */
    CompletableFuture<Optional<MapData>> getMapData(long mapId);

    /**
     * Creates an ItemStack representing a stored map.
     *
     * @param mapId The map ID
     * @return The map ItemStack, or null if map doesn't exist
     */
    ItemStack createMapItem(long mapId);

    /**
     * Creates ItemStacks for a multi-block map.
     *
     * @param groupId The multi-block map group ID
     * @return Array of map ItemStacks arranged by grid position
     */
    ItemStack[][] createMultiBlockMapItems(long groupId);

    /**
     * Gives a map item to a player.
     *
     * @param player The player to give the map to
     * @param mapId The map ID
     */
    void giveMapToPlayer(Player player, long mapId);

    /**
     * Gives all items of a multi-block map to a player.
     *
     * @param player The player
     * @param groupId The multi-block map group ID
     */
    void giveMultiBlockMapToPlayer(Player player, long groupId);

    /**
     * Gets the stored map ID from a map ItemStack.
     *
     * @param item The map item
     * @return The map ID, or -1 if not a stored map
     */
    long getMapIdFromItem(ItemStack item);

    /**
     * Checks if the given ItemStack is a stored map.
     *
     * @param item The item to check
     * @return true if this is a stored map item
     */
    boolean isStoredMap(ItemStack item);

    /**
     * Sends map data to a specific player for rendering.
     *
     * @param player The player to send to
     * @param mapId The map ID
     */
    void sendMapToPlayer(Player player, long mapId);

    /**
     * Gets the number of maps stored by a plugin.
     *
     * @param pluginId The plugin identifier
     * @return CompletableFuture containing the count
     */
    CompletableFuture<Integer> getMapCount(String pluginId);

    /**
     * Checks if a player has permission to create maps.
     *
     * @param player The player
     * @return true if allowed
     */
    boolean canCreateMaps(Player player);

    /**
     * Gets the map creation limit for a player.
     *
     * @param player The player
     * @return The limit, or -1 for unlimited
     */
    int getMapLimit(Player player);

    /**
     * Gets the number of maps a player has created.
     *
     * @param player The player
     * @return CompletableFuture containing the count
     */
    CompletableFuture<Integer> getPlayerMapCount(Player player);
}
