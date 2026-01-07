package com.example.mapdb.api.nms;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Interface for version-specific NMS operations.
 * Each supported Minecraft version implements this interface.
 */
public interface NMSAdapter {

    /**
     * Gets the NMS version string.
     *
     * @return Version string like "v1_21_R1"
     */
    String getVersion();

    /**
     * Sends a map update packet to a player.
     *
     * @param player The player to send to
     * @param mapId The map ID to update
     * @param pixels The pixel data (128x128 bytes)
     */
    void sendMapUpdate(Player player, int mapId, byte[] pixels);

    /**
     * Sends a partial map update packet.
     *
     * @param player The player
     * @param mapId The map ID
     * @param startX Start X coordinate
     * @param startY Start Y coordinate
     * @param width Width of update region
     * @param height Height of update region
     * @param pixels Pixel data for the region
     */
    void sendPartialMapUpdate(Player player, int mapId, int startX, int startY, 
                               int width, int height, byte[] pixels);

    /**
     * Creates a map ItemStack with the given map ID.
     *
     * @param mapId The map ID
     * @return The map item
     */
    ItemStack createMapItem(int mapId);

    /**
     * Gets the map ID from an ItemStack.
     *
     * @param item The item
     * @return The map ID, or -1 if not a map
     */
    int getMapId(ItemStack item);

    /**
     * Sets the map ID on an ItemStack.
     *
     * @param item The item to modify
     * @param mapId The new map ID
     * @return The modified item
     */
    ItemStack setMapId(ItemStack item, int mapId);

    /**
     * Checks if an item is a filled map.
     *
     * @param item The item to check
     * @return true if filled map
     */
    boolean isFilledMap(ItemStack item);

    /**
     * Spawns a block display entity showing a map (1.19.4+).
     *
     * @param location The location to spawn at
     * @param mapId The map ID to display
     * @return The entity ID, or -1 if not supported
     */
    int spawnMapDisplay(Location location, int mapId);

    /**
     * Removes a display entity.
     *
     * @param entityId The entity ID
     */
    void removeDisplay(int entityId);

    /**
     * Checks if block displays are supported.
     *
     * @return true if supported (1.19.4+)
     */
    boolean supportsBlockDisplays();

    /**
     * Shows particle outline for map preview (legacy method).
     *
     * @param player The player to show particles to
     * @param location The location of the map item
     */
    void showParticleOutline(Player player, Location location);

    /**
     * Intercepts and modifies outgoing map packets.
     * Used to redirect map rendering to our custom data.
     *
     * @param listener The packet listener
     */
    void registerPacketInterceptor(MapPacketListener listener);

    /**
     * Removes the packet interceptor.
     */
    void unregisterPacketInterceptor();

    /**
     * Checks if this adapter supports Folia's region threading.
     *
     * @return true if Folia-compatible
     */
    boolean supportsFolia();

    /**
     * Gets the major Minecraft version.
     *
     * @return Version number like 21 for 1.21
     */
    int getMajorVersion();

    /**
     * Gets the minor Minecraft version.
     *
     * @return Minor version number
     */
    int getMinorVersion();

    /**
     * Interface for handling intercepted map packets.
     */
    interface MapPacketListener {
        /**
         * Called when a map packet is about to be sent.
         *
         * @param player The player receiving the packet
         * @param mapId The map ID in the packet
         * @return true to cancel the packet, false to allow
         */
        boolean onMapPacket(Player player, int mapId);
    }
}
