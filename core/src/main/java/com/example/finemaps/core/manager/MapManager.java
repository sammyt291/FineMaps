package com.example.finemaps.core.manager;

import com.example.finemaps.api.FineMapsAPI;
import com.example.finemaps.api.event.MapCreateEvent;
import com.example.finemaps.api.event.MapDeleteEvent;
import com.example.finemaps.api.event.MapLoadEvent;
import com.example.finemaps.api.map.MapData;
import com.example.finemaps.api.map.MultiBlockMap;
import com.example.finemaps.api.map.StoredMap;
import com.example.finemaps.core.config.FineMapsConfig;
import com.example.finemaps.core.database.DatabaseProvider;
import com.example.finemaps.core.image.ImageProcessor;
import com.example.finemaps.api.nms.NMSAdapter;
import com.example.finemaps.core.virtual.VirtualIdManager;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main manager for map operations.
 * Coordinates database, virtual IDs, and NMS operations.
 */
public class MapManager implements FineMapsAPI {

    private final Plugin plugin;
    private final Logger logger;
    private final FineMapsConfig config;
    private final DatabaseProvider database;
    private final NMSAdapter nmsAdapter;
    private final VirtualIdManager virtualIdManager;
    private final ImageProcessor imageProcessor;

    // Cache of loaded maps
    private final Map<Long, MapData> mapDataCache = new ConcurrentHashMap<>();
    
    // Track which maps are loaded for each player
    private final Map<UUID, Set<Long>> playerLoadedMaps = new ConcurrentHashMap<>();
    
    // NBT key for storing our map ID
    private final NamespacedKey mapIdKey;
    private final NamespacedKey groupIdKey;
    
    // Track chunk loading to prevent loops
    private final Set<String> processingChunks = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public MapManager(Plugin plugin, FineMapsConfig config, DatabaseProvider database, 
                      NMSAdapter nmsAdapter) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = config;
        this.database = database;
        this.nmsAdapter = nmsAdapter;
        this.virtualIdManager = new VirtualIdManager(config.getMaps().getMaxVirtualIds());
        this.imageProcessor = new ImageProcessor(
            config.getImages().getConnectionTimeout(),
            config.getImages().getReadTimeout(),
            config.getPermissions().getMaxImportSize()
        );
        this.mapIdKey = new NamespacedKey(plugin, "finemaps_id");
        this.groupIdKey = new NamespacedKey(plugin, "finemaps_group");

        // Register packet interceptor
        nmsAdapter.registerPacketInterceptor(this::handleMapPacket);
    }

    /**
     * Handles intercepted map packets.
     *
     * @param player The player
     * @param mapId The map ID
     * @return true to cancel the packet
     */
    private boolean handleMapPacket(Player player, int mapId) {
        // Check if this is a virtual ID we manage
        if (!virtualIdManager.isVirtualId(mapId)) {
            return false; // Allow vanilla maps through
        }
        
        // Get the actual database map ID
        long dbMapId = virtualIdManager.resolveVirtualId(mapId);
        if (dbMapId == -1) {
            return true; // Cancel packets for unresolved IDs
        }
        
        // Send our custom map data instead
        sendMapToPlayerInternal(player, dbMapId, mapId);
        return true; // Cancel original packet
    }

    private void sendMapToPlayerInternal(Player player, long dbMapId, int virtualId) {
        // Check cache first
        MapData cached = mapDataCache.get(dbMapId);
        if (cached != null) {
            nmsAdapter.sendMapUpdate(player, virtualId, cached.getPixelsUnsafe());
            return;
        }
        
        // Load from database
        database.getMapData(dbMapId).thenAccept(optData -> {
            optData.ifPresent(data -> {
                mapDataCache.put(dbMapId, data);
                
                // Run on main thread for packet sending
                Bukkit.getScheduler().runTask(plugin, () -> {
                    nmsAdapter.sendMapUpdate(player, virtualId, data.getPixelsUnsafe());
                });
            });
        });
    }

    @Override
    public CompletableFuture<StoredMap> createMap(String pluginId, byte[] pixels) {
        return database.createMap(pluginId, null, pixels, null, 0, 0, 0, null);
    }

    @Override
    public CompletableFuture<StoredMap> createMapFromImage(String pluginId, BufferedImage image, boolean dither) {
        byte[] pixels = imageProcessor.processSingleMap(image, dither);
        return createMap(pluginId, pixels);
    }

    @Override
    public CompletableFuture<MultiBlockMap> createMultiBlockMap(String pluginId, BufferedImage image,
                                                                  int widthBlocks, int heightBlocks, boolean dither) {
        return CompletableFuture.supplyAsync(() -> {
            byte[][] pixelArrays = imageProcessor.processImage(image, widthBlocks, heightBlocks, dither);
            return pixelArrays;
        }).thenCompose(pixelArrays -> {
            // Create group first
            return database.createMultiBlockGroup(pluginId, null, widthBlocks, heightBlocks, null)
                .thenCompose(groupId -> {
                    // Create all maps in the group
                    List<CompletableFuture<StoredMap>> mapFutures = new ArrayList<>();
                    
                    for (int y = 0; y < heightBlocks; y++) {
                        for (int x = 0; x < widthBlocks; x++) {
                            int index = y * widthBlocks + x;
                            byte[] pixels = pixelArrays[index];
                            
                            CompletableFuture<StoredMap> mapFuture = database.createMap(
                                pluginId, null, pixels, null, groupId, x, y, null
                            );
                            mapFutures.add(mapFuture);
                        }
                    }
                    
                    // Wait for all maps to be created
                    return CompletableFuture.allOf(mapFutures.toArray(new CompletableFuture[0]))
                        .thenApply(v -> {
                            List<StoredMap> maps = new ArrayList<>();
                            for (CompletableFuture<StoredMap> f : mapFutures) {
                                maps.add(f.join());
                            }
                            return new MultiBlockMap(groupId, pluginId, null, 
                                                    widthBlocks, heightBlocks, maps, 
                                                    System.currentTimeMillis(), null);
                        });
                });
        });
    }

    @Override
    public CompletableFuture<Optional<StoredMap>> getMap(long mapId) {
        return database.getMap(mapId);
    }

    @Override
    public CompletableFuture<Optional<MultiBlockMap>> getMultiBlockMap(long groupId) {
        return database.getMultiBlockMap(groupId);
    }

    @Override
    public CompletableFuture<List<StoredMap>> getMapsByPlugin(String pluginId) {
        return database.getMapsByPlugin(pluginId);
    }

    @Override
    public CompletableFuture<Boolean> deleteMap(long mapId) {
        // Clear from cache
        mapDataCache.remove(mapId);
        virtualIdManager.releaseGlobalVirtualId(mapId);
        
        return database.deleteMap(mapId);
    }

    @Override
    public CompletableFuture<Boolean> deleteMultiBlockMap(long groupId) {
        // Clear all maps in group from cache
        return database.getMapsByGroup(groupId).thenCompose(maps -> {
            for (StoredMap map : maps) {
                mapDataCache.remove(map.getId());
                virtualIdManager.releaseGlobalVirtualId(map.getId());
            }
            return database.deleteMultiBlockGroup(groupId);
        });
    }

    @Override
    public CompletableFuture<Boolean> updateMapPixels(long mapId, byte[] pixels) {
        // Update cache
        mapDataCache.put(mapId, new MapData(mapId, pixels));
        
        return database.updateMapPixels(mapId, pixels);
    }

    @Override
    public CompletableFuture<Optional<MapData>> getMapData(long mapId) {
        // Check cache first
        MapData cached = mapDataCache.get(mapId);
        if (cached != null) {
            return CompletableFuture.completedFuture(Optional.of(cached));
        }
        
        return database.getMapData(mapId).thenApply(opt -> {
            opt.ifPresent(data -> mapDataCache.put(mapId, data));
            return opt;
        });
    }

    @Override
    public ItemStack createMapItem(long mapId) {
        int virtualId = virtualIdManager.getOrCreateGlobalVirtualId(mapId);
        ItemStack item = nmsAdapter.createMapItem(virtualId);
        
        // Store our map ID in NBT
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(mapIdKey, PersistentDataType.LONG, mapId);
            item.setItemMeta(meta);
        }
        
        return item;
    }

    @Override
    public ItemStack[][] createMultiBlockMapItems(long groupId) {
        Optional<MultiBlockMap> optMap = getMultiBlockMap(groupId).join();
        if (!optMap.isPresent()) {
            return new ItemStack[0][0];
        }
        
        MultiBlockMap multiMap = optMap.get();
        ItemStack[][] items = new ItemStack[multiMap.getHeight()][multiMap.getWidth()];
        
        for (StoredMap map : multiMap.getMaps()) {
            ItemStack item = createMapItem(map.getId());
            
            // Add group ID to item
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                pdc.set(groupIdKey, PersistentDataType.LONG, groupId);
                item.setItemMeta(meta);
            }
            
            items[map.getGridY()][map.getGridX()] = item;
        }
        
        return items;
    }

    @Override
    public void giveMapToPlayer(Player player, long mapId) {
        ItemStack item = createMapItem(mapId);
        player.getInventory().addItem(item);
        
        // Load and send map data
        sendMapToPlayer(player, mapId);
    }

    @Override
    public void giveMultiBlockMapToPlayer(Player player, long groupId) {
        ItemStack[][] items = createMultiBlockMapItems(groupId);
        for (ItemStack[] row : items) {
            for (ItemStack item : row) {
                if (item != null) {
                    player.getInventory().addItem(item);
                }
            }
        }
    }

    @Override
    public long getMapIdFromItem(ItemStack item) {
        if (item == null || !nmsAdapter.isFilledMap(item)) {
            return -1;
        }
        
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return -1;
        }
        
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Long mapId = pdc.get(mapIdKey, PersistentDataType.LONG);
        
        return mapId != null ? mapId : -1;
    }

    @Override
    public boolean isStoredMap(ItemStack item) {
        return getMapIdFromItem(item) != -1;
    }

    @Override
    public void sendMapToPlayer(Player player, long mapId) {
        int virtualId = virtualIdManager.getOrCreatePlayerVirtualId(player.getUniqueId(), mapId);
        
        // Track loaded maps for this player
        playerLoadedMaps.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>()).add(mapId);
        
        // Update last accessed
        database.updateLastAccessed(mapId);
        
        // Fire event
        getMap(mapId).thenAccept(optMap -> {
            if (optMap.isPresent()) {
                MapLoadEvent event = new MapLoadEvent(optMap.get(), player, virtualId);
                Bukkit.getPluginManager().callEvent(event);
                
                if (!event.isCancelled()) {
                    sendMapToPlayerInternal(player, mapId, virtualId);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Integer> getMapCount(String pluginId) {
        return database.getMapCount(pluginId);
    }

    @Override
    public boolean canCreateMaps(Player player) {
        if (player.hasPermission("finemaps.unlimited")) {
            return true;
        }
        
        int limit = getMapLimit(player);
        if (limit < 0) {
            return true;
        }
        
        int current = getPlayerMapCount(player).join();
        return current < limit;
    }

    @Override
    public int getMapLimit(Player player) {
        if (player.hasPermission("finemaps.unlimited")) {
            return -1;
        }
        
        // Check group-specific limits
        for (Map.Entry<String, Integer> entry : config.getPermissions().getGroupLimits().entrySet()) {
            if (player.hasPermission("finemaps.limit." + entry.getKey())) {
                return entry.getValue();
            }
        }
        
        return config.getPermissions().getDefaultLimit();
    }

    @Override
    public CompletableFuture<Integer> getPlayerMapCount(Player player) {
        return database.getMapCountByCreator(player.getUniqueId());
    }

    /**
     * Called when a player quits to clean up their virtual IDs.
     *
     * @param playerUUID The player's UUID
     */
    public void onPlayerQuit(UUID playerUUID) {
        virtualIdManager.clearPlayerSpace(playerUUID);
        playerLoadedMaps.remove(playerUUID);
    }

    /**
     * Gets the group ID from a map item.
     *
     * @param item The item
     * @return The group ID, or -1 if not a group member
     */
    public long getGroupIdFromItem(ItemStack item) {
        if (item == null) {
            return -1;
        }
        
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return -1;
        }
        
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Long groupId = pdc.get(groupIdKey, PersistentDataType.LONG);
        
        return groupId != null ? groupId : -1;
    }

    /**
     * Marks a chunk as being processed to prevent loops.
     *
     * @param chunkKey The chunk key (world:x:z)
     * @return true if processing started, false if already processing
     */
    public boolean startChunkProcessing(String chunkKey) {
        return processingChunks.add(chunkKey);
    }

    /**
     * Marks chunk processing as complete.
     *
     * @param chunkKey The chunk key
     */
    public void endChunkProcessing(String chunkKey) {
        processingChunks.remove(chunkKey);
    }

    /**
     * Gets the NMS adapter.
     *
     * @return The NMS adapter
     */
    public NMSAdapter getNmsAdapter() {
        return nmsAdapter;
    }

    /**
     * Gets the image processor.
     *
     * @return The image processor
     */
    public ImageProcessor getImageProcessor() {
        return imageProcessor;
    }

    /**
     * Gets the configuration.
     *
     * @return The config
     */
    public FineMapsConfig getConfig() {
        return config;
    }

    /**
     * Performs cleanup operations.
     */
    public void cleanup() {
        // Clean up stale cache entries
        long staleTime = System.currentTimeMillis() - config.getMaps().getStaleTime();
        database.getStaleMapIds(staleTime).thenAccept(staleIds -> {
            for (Long id : staleIds) {
                mapDataCache.remove(id);
            }
        });
        
        // Clean up virtual IDs
        virtualIdManager.cleanup(mapDataCache.keySet());
    }

    /**
     * Shuts down the manager.
     */
    public void shutdown() {
        nmsAdapter.unregisterPacketInterceptor();
        imageProcessor.shutdown();
        mapDataCache.clear();
        playerLoadedMaps.clear();
    }
}
