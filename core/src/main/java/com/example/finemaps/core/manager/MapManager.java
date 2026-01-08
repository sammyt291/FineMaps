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
import com.example.finemaps.core.render.MapViewManager;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
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
    private final MapViewManager mapViewManager;
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
        this.mapViewManager = new MapViewManager(plugin);
        this.imageProcessor = new ImageProcessor(
            config.getImages().getConnectionTimeout(),
            config.getImages().getReadTimeout(),
            config.getPermissions().getMaxImportSize()
        );
        this.mapIdKey = new NamespacedKey(plugin, "finemaps_id");
        this.groupIdKey = new NamespacedKey(plugin, "finemaps_group");
    }

    /**
     * Gets or creates a proper Bukkit MapView for the given map ID.
     * This ensures the map will render correctly in item frames and player hands.
     *
     * @param dbMapId The database map ID
     * @return The Bukkit map ID
     */
    private int getOrCreateBukkitMapId(long dbMapId) {
        // Check if we already have a Bukkit map for this
        int existingId = mapViewManager.getBukkitMapId(dbMapId);
        if (existingId != -1) {
            return existingId;
        }
        
        // Check cache first for pixel data
        MapData cached = mapDataCache.get(dbMapId);
        if (cached != null) {
            return mapViewManager.getOrCreateBukkitMapId(dbMapId, cached.getPixelsUnsafe());
        }
        
        // Create with lazy loading - will load pixels when first rendered
        return mapViewManager.getOrCreateBukkitMapId(dbMapId, () -> {
            // Try cache again (might have been populated by another call)
            MapData cachedData = mapDataCache.get(dbMapId);
            if (cachedData != null) {
                return cachedData.getPixelsUnsafe();
            }
            
            // Load from database synchronously (called during render)
            try {
                Optional<MapData> optData = database.getMapData(dbMapId).join();
                if (optData.isPresent()) {
                    mapDataCache.put(dbMapId, optData.get());
                    return optData.get().getPixelsUnsafe();
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to load map data for " + dbMapId, e);
            }
            
            // Return blank map on failure
            return new byte[MapData.TOTAL_PIXELS];
        });
    }

    /**
     * Ensures a map's pixel data is loaded and the MapView is initialized.
     *
     * @param dbMapId The database map ID
     * @param pixels The pixel data to use
     */
    private void ensureMapInitialized(long dbMapId, byte[] pixels) {
        mapDataCache.put(dbMapId, new MapData(dbMapId, pixels));
        mapViewManager.getOrCreateBukkitMapId(dbMapId, pixels);
    }

    @Override
    public CompletableFuture<StoredMap> createMap(String pluginId, byte[] pixels) {
        return database.createMap(pluginId, null, pixels, null, 0, 0, 0, null);
    }

    /**
     * Creates a map directly from raw 128x128 pixel bytes with an optional art name.
     * Intended for import workflows (e.g. importing vanilla maps).
     *
     * @param pluginId The plugin ID namespace
     * @param pixels The pixel data (128x128 = 16384 bytes)
     * @param artName Optional art name to store as metadata
     * @return CompletableFuture containing the created map
     */
    public CompletableFuture<StoredMap> createMapWithName(String pluginId, byte[] pixels, String artName) {
        return database.createMap(pluginId, null, pixels, null, 0, 0, 0, artName);
    }

    @Override
    public CompletableFuture<StoredMap> createMapFromImage(String pluginId, BufferedImage image, boolean dither) {
        byte[] pixels = imageProcessor.processSingleMap(image, dither);
        return createMap(pluginId, pixels);
    }

    /**
     * Creates a map from an image with an art name.
     *
     * @param pluginId The plugin ID
     * @param image The image
     * @param dither Whether to use dithering
     * @param artName The art name to store as metadata
     * @return CompletableFuture containing the created map
     */
    public CompletableFuture<StoredMap> createMapFromImageWithName(String pluginId, BufferedImage image, 
                                                                     boolean dither, String artName) {
        byte[] pixels = imageProcessor.processSingleMap(image, dither);
        return database.createMap(pluginId, null, pixels, null, 0, 0, 0, artName);
    }

    @Override
    public CompletableFuture<MultiBlockMap> createMultiBlockMap(String pluginId, BufferedImage image,
                                                                  int widthBlocks, int heightBlocks, boolean dither) {
        return createMultiBlockMapWithName(pluginId, image, widthBlocks, heightBlocks, dither, null);
    }

    /**
     * Creates a multi-block map with an art name.
     *
     * @param pluginId The plugin ID
     * @param image The image
     * @param widthBlocks Width in blocks
     * @param heightBlocks Height in blocks
     * @param dither Whether to use dithering
     * @param artName The art name to store as metadata
     * @return CompletableFuture containing the created multi-block map
     */
    public CompletableFuture<MultiBlockMap> createMultiBlockMapWithName(String pluginId, BufferedImage image,
                                                                          int widthBlocks, int heightBlocks, 
                                                                          boolean dither, String artName) {
        return CompletableFuture.supplyAsync(() -> {
            byte[][] pixelArrays = imageProcessor.processImage(image, widthBlocks, heightBlocks, dither);
            return pixelArrays;
        }).thenCompose(pixelArrays -> {
            // Create group first with art name as metadata
            return database.createMultiBlockGroup(pluginId, null, widthBlocks, heightBlocks, artName)
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
                                                    System.currentTimeMillis(), artName);
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
        // Clear from cache and release MapView
        mapDataCache.remove(mapId);
        mapViewManager.releaseMap(mapId);
        
        return database.deleteMap(mapId);
    }

    @Override
    public CompletableFuture<Boolean> deleteMultiBlockMap(long groupId) {
        // Clear all maps in group from cache
        return database.getMapsByGroup(groupId).thenCompose(maps -> {
            for (StoredMap map : maps) {
                mapDataCache.remove(map.getId());
                mapViewManager.releaseMap(map.getId());
            }
            return database.deleteMultiBlockGroup(groupId);
        });
    }

    @Override
    public CompletableFuture<Boolean> updateMapPixels(long mapId, byte[] pixels) {
        // Update cache
        mapDataCache.put(mapId, new MapData(mapId, pixels));
        
        // Update the MapView renderer
        mapViewManager.updateMapPixels(mapId, pixels);
        
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
        // Get or create a proper Bukkit map for this database ID
        int bukkitMapId = getOrCreateBukkitMapId(mapId);
        
        // Create the map item using NMS adapter
        ItemStack item = nmsAdapter.createMapItem(bukkitMapId);
        
        // Store our database map ID in NBT so we can identify it later
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
        giveMapToPlayerWithName(player, mapId, null);
    }

    /**
     * Gives a map item to a player with an art name displayed.
     *
     * @param player The player
     * @param mapId The map ID
     * @param artName The art name to display (or null to use default)
     */
    public void giveMapToPlayerWithName(Player player, long mapId, String artName) {
        // Ensure map data is loaded first
        getMapData(mapId).thenAccept(optData -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Create the map item (this will also initialize the MapView)
                ItemStack item = createMapItem(mapId);
                
                // Add art name to display if provided
                if (artName != null) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.setDisplayName(org.bukkit.ChatColor.GOLD + "Map: " + artName);
                        List<String> lore = new ArrayList<>();
                        lore.add(org.bukkit.ChatColor.GRAY + "Size: 1x1");
                        meta.setLore(lore);
                        hideVanillaMapTooltip(meta);
                        item.setItemMeta(meta);
                    }
                }
                
                player.getInventory().addItem(item);
                
                // Force update the player's inventory to refresh the item display
                player.updateInventory();
                
                // Track that this player has this map
                playerLoadedMaps.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>()).add(mapId);
                
                // Invalidate the map for this player to force re-render
                mapViewManager.getRenderer(mapId).ifPresent(r -> r.invalidatePlayer(player.getUniqueId()));
            });
        });
    }

    @Override
    public void giveMultiBlockMapToPlayer(Player player, long groupId) {
        giveMultiBlockMapToPlayerWithName(player, groupId, null);
    }

    /**
     * Gives a multi-block map item to a player with an art name displayed.
     *
     * @param player The player
     * @param groupId The group ID
     * @param artName The art name to display (or null to use default)
     */
    public void giveMultiBlockMapToPlayerWithName(Player player, long groupId, String artName) {
        // Pre-load all map data first, then give the item
        getMultiBlockMap(groupId).thenAccept(optMap -> {
            if (!optMap.isPresent()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage(org.bukkit.ChatColor.RED + "Map group not found!");
                });
                return;
            }
            
            MultiBlockMap multiMap = optMap.get();
            
            // Use art name from metadata if not provided
            String displayName = artName != null ? artName : multiMap.getMetadata();
            
            // Load all individual maps' pixel data
            List<CompletableFuture<Void>> loadFutures = new ArrayList<>();
            for (StoredMap map : multiMap.getMaps()) {
                CompletableFuture<Void> future = getMapData(map.getId()).thenAccept(data -> {
                    // This loads the data into cache
                });
                loadFutures.add(future);
            }
            
            // Wait for all to load, then give item on main thread
            final String finalDisplayName = displayName;
            CompletableFuture.allOf(loadFutures.toArray(new CompletableFuture[0])).thenRun(() -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // Create the multi-block map item with name
                    ItemStack item = createMultiBlockMapItemWithName(groupId, finalDisplayName);
                    if (item != null) {
                        player.getInventory().addItem(item);
                        player.updateInventory();
                        
                        // Track maps for this player
                        for (StoredMap map : multiMap.getMaps()) {
                            playerLoadedMaps.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>()).add(map.getId());
                            
                            // Invalidate renderer for this player to force re-render
                            mapViewManager.getRenderer(map.getId()).ifPresent(r -> r.invalidatePlayer(player.getUniqueId()));
                        }
                    }
                });
            });
        });
    }
    
    /**
     * Creates a single item representing an entire multi-block map.
     * Also ensures all component maps are initialized with their MapViews.
     *
     * @param groupId The group ID
     * @return The item, or null if not found
     */
    public ItemStack createMultiBlockMapItem(long groupId) {
        return createMultiBlockMapItemWithName(groupId, null);
    }

    /**
     * Creates a single item representing an entire multi-block map with an art name.
     *
     * @param groupId The group ID
     * @param artName The art name to display (or null to use metadata)
     * @return The item, or null if not found
     */
    public ItemStack createMultiBlockMapItemWithName(long groupId, String artName) {
        Optional<MultiBlockMap> optMap = getMultiBlockMap(groupId).join();
        if (!optMap.isPresent()) {
            return null;
        }
        
        MultiBlockMap multiMap = optMap.get();
        
        // Use art name from parameter or fall back to metadata
        String displayName = artName != null ? artName : multiMap.getMetadata();
        
        // Initialize MapViews for ALL maps in the group (so they render when placed)
        for (StoredMap map : multiMap.getMaps()) {
            // Load pixel data and create MapView
            getMapData(map.getId()).thenAccept(optData -> {
                optData.ifPresent(data -> {
                    // This ensures the MapView is created
                    getOrCreateBukkitMapId(map.getId());
                });
            });
        }
        
        // Use the first map (top-left, 0,0) as the display item
        StoredMap firstMap = multiMap.getMapAt(0, 0);
        if (firstMap == null && !multiMap.getMaps().isEmpty()) {
            firstMap = multiMap.getMaps().get(0);
        }
        if (firstMap == null) {
            return null;
        }
        
        ItemStack item = createMapItem(firstMap.getId());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            
            // Store group ID and dimensions
            pdc.set(groupIdKey, PersistentDataType.LONG, groupId);
            pdc.set(new NamespacedKey(plugin, "finemaps_width"), PersistentDataType.INTEGER, multiMap.getWidth());
            pdc.set(new NamespacedKey(plugin, "finemaps_height"), PersistentDataType.INTEGER, multiMap.getHeight());
            
            // Set display name showing only the art name (size stays in lore)
            if (displayName != null) {
                meta.setDisplayName(org.bukkit.ChatColor.GOLD + "Map: " + displayName);
            } else {
                meta.setDisplayName(org.bukkit.ChatColor.GOLD + "Map");
            }
            
            // Add lore with info
            List<String> lore = new ArrayList<>();
            lore.add(org.bukkit.ChatColor.GRAY + "Size: " + multiMap.getWidth() + "x" + multiMap.getHeight() + " blocks");
            lore.add("");
            lore.add(org.bukkit.ChatColor.YELLOW + "Look at a wall to see preview");
            lore.add(org.bukkit.ChatColor.YELLOW + "Right-click to place");
            meta.setLore(lore);
            hideVanillaMapTooltip(meta);
            
            item.setItemMeta(meta);
        }
        
        return item;
    }

    /**
     * Hides the vanilla map tooltip ("default lore") where supported.
     * Uses reflection / dynamic enum lookup to keep compatibility across server versions.
     */
    void hideVanillaMapTooltip(ItemMeta meta) {
        if (meta == null) {
            return;
        }

        // Always available: hide generic attribute lines
        try {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        } catch (Throwable ignored) {
            // Best-effort on older forks/APIs
        }

        // 1.20.5+ (and some forks): hides extra item tooltip lines (used by maps among others)
        try {
            meta.addItemFlags(ItemFlag.valueOf("HIDE_ADDITIONAL_TOOLTIP"));
        } catch (IllegalArgumentException ignored) {
            // Not available on this API version
        } catch (Throwable ignored) {
            // Best-effort: don't fail item creation
        }

        // Paper/modern APIs: completely hide tooltip
        try {
            java.lang.reflect.Method setHideTooltip = meta.getClass().getMethod("setHideTooltip", boolean.class);
            setHideTooltip.invoke(meta, true);
        } catch (ReflectiveOperationException ignored) {
            // Not available
        } catch (Throwable ignored) {
            // Best-effort
        }
    }
    
    /**
     * Gets the width of a multi-block map from an item.
     *
     * @param item The item
     * @return The width, or 1 if not a multi-block map
     */
    public int getMultiBlockWidth(ItemStack item) {
        if (item == null) return 1;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 1;
        
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Integer width = pdc.get(new NamespacedKey(plugin, "finemaps_width"), PersistentDataType.INTEGER);
        return width != null ? width : 1;
    }
    
    /**
     * Gets the height of a multi-block map from an item.
     *
     * @param item The item
     * @return The height, or 1 if not a multi-block map
     */
    public int getMultiBlockHeight(ItemStack item) {
        if (item == null) return 1;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 1;
        
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Integer height = pdc.get(new NamespacedKey(plugin, "finemaps_height"), PersistentDataType.INTEGER);
        return height != null ? height : 1;
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
        // Track loaded maps for this player
        playerLoadedMaps.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>()).add(mapId);
        
        // Update last accessed
        database.updateLastAccessed(mapId);
        
        // Load map data to ensure it's in cache and the MapView is initialized
        getMapData(mapId).thenAccept(optData -> {
            if (optData.isPresent()) {
                MapData data = optData.get();
                
                // Ensure the Bukkit MapView is created with the pixel data
                Bukkit.getScheduler().runTask(plugin, () -> {
                    mapViewManager.getOrCreateBukkitMapId(mapId, data.getPixelsUnsafe());
                    
                    // Invalidate for this player to trigger re-render
                    mapViewManager.getRenderer(mapId).ifPresent(r -> r.invalidatePlayer(player.getUniqueId()));
                    
                    // Fire event
                    getMap(mapId).thenAccept(optMap -> {
                        if (optMap.isPresent()) {
                            int bukkitMapId = mapViewManager.getBukkitMapId(mapId);
                            MapLoadEvent event = new MapLoadEvent(optMap.get(), player, bukkitMapId);
                            Bukkit.getPluginManager().callEvent(event);
                        }
                    });
                });
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
     * Called when a player quits to clean up their map state.
     *
     * @param playerUUID The player's UUID
     */
    public void onPlayerQuit(UUID playerUUID) {
        // Clear player from loaded maps tracking
        Set<Long> loadedMaps = playerLoadedMaps.remove(playerUUID);
        
        // Invalidate renderers for this player (so they re-render on rejoin)
        if (loadedMaps != null) {
            for (long mapId : loadedMaps) {
                mapViewManager.getRenderer(mapId).ifPresent(r -> r.invalidatePlayer(playerUUID));
            }
        }
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
                // Note: We don't release MapViews here as they might still be in use
                // MapViews are persistent in Minecraft, releasing them could cause issues
            }
        });
    }

    /**
     * Shuts down the manager.
     */
    public void shutdown() {
        nmsAdapter.unregisterPacketInterceptor();
        imageProcessor.shutdown();
        mapDataCache.clear();
        playerLoadedMaps.clear();
        mapViewManager.clear();
    }
    
    /**
     * Gets the MapViewManager for direct access if needed.
     *
     * @return The MapViewManager
     */
    public MapViewManager getMapViewManager() {
        return mapViewManager;
    }

    /**
     * Gets a multi-block map group by its art name.
     *
     * @param pluginId The plugin ID
     * @param name The art name
     * @return CompletableFuture containing the group ID if found
     */
    public CompletableFuture<Optional<Long>> getGroupByName(String pluginId, String name) {
        return database.getGroupByName(pluginId, name);
    }

    /**
     * Gets a single map by its art name.
     *
     * @param pluginId The plugin ID
     * @param name The art name
     * @return CompletableFuture containing the map ID if found
     */
    public CompletableFuture<Optional<Long>> getMapByName(String pluginId, String name) {
        return database.getMapByName(pluginId, name);
    }

    /**
     * Checks if an art name is already in use.
     *
     * @param pluginId The plugin ID
     * @param name The art name to check
     * @return CompletableFuture containing true if name exists
     */
    public CompletableFuture<Boolean> isNameTaken(String pluginId, String name) {
        return database.isNameTaken(pluginId, name);
    }

    /**
     * Gets the database provider.
     *
     * @return The database provider
     */
    public DatabaseProvider getDatabase() {
        return database;
    }
}
