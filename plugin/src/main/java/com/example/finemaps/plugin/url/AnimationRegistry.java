package com.example.finemaps.plugin.url;

import com.example.finemaps.core.manager.MapManager;
import com.example.finemaps.core.util.FineMapsScheduler;
import com.example.finemaps.api.nms.NMSAdapter;
import org.bukkit.Bukkit;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime animation playback for maps created from animated images.
 *
 * Note: this is in-memory playback; frame pixels are expected to be already computed.
 * Frame caching to disk is handled by the URL import command workflow.
 */
public final class AnimationRegistry {

    private final Plugin plugin;
    private final MapManager mapManager;
    private final NMSAdapter nmsAdapter;
    private final String cacheFolderName;
    private final int frameCacheFrames;

    private final File persistFile;
    private final AtomicBoolean loadedPersisted = new AtomicBoolean(false);

    private final Map<String, Animation> animationsByName = new ConcurrentHashMap<>();
    /**
     * Viewer index (rebuilt periodically on the main thread):
     * dbMapId -> (player UUID -> vanilla map IDs to update for that player).
     *
     * We use vanilla map IDs because item frames / items may carry a different Bukkit map id
     * than our runtime MapViewManager mapping (e.g. after restarts).
     */
    private volatile Map<Long, Map<UUID, Set<Integer>>> viewersByDbMapId = new HashMap<>();
    private Object viewerScanTask = null;

    public AnimationRegistry(Plugin plugin, MapManager mapManager, String cacheFolderName, int frameCacheFrames) {
        this.plugin = plugin;
        this.mapManager = mapManager;
        this.nmsAdapter = mapManager.getNmsAdapter();
        this.cacheFolderName = (cacheFolderName != null && !cacheFolderName.isBlank()) ? cacheFolderName : "url-cache";
        this.frameCacheFrames = Math.max(0, frameCacheFrames);
        this.persistFile = new File(plugin.getDataFolder(), "animations.yml");
    }

    public void stopAll() {
        for (Animation a : animationsByName.values()) {
            a.stop();
        }
        animationsByName.clear();
        stopViewerScan();
    }

    public void registerAndStartSingle(String name, long mapId, int fps, List<byte[]> frames) {
        Animation existing = animationsByName.remove(name);
        if (existing != null) existing.stop();
        Animation a = Animation.single(plugin, mapManager, () -> viewersByDbMapId, mapId, fps, frames);
        animationsByName.put(name, a);
        ensureViewerScan();
        a.start();
    }

    public void registerAndStartMulti(String name, List<Long> mapIdsInTileOrder, int width, int height, int fps, List<byte[][]> frames) {
        Animation existing = animationsByName.remove(name);
        if (existing != null) existing.stop();
        Animation a = Animation.multi(plugin, mapManager, () -> viewersByDbMapId, mapIdsInTileOrder, width, height, fps, frames);
        animationsByName.put(name, a);
        ensureViewerScan();
        a.start();
    }

    /**
     * Starts playback using frames stored on disk (the URL import workflow writes these).
     * This avoids loading all frame data into memory at once.
     */
    public boolean registerAndStartSingleFromCache(String name, long mapId, int fps, String url, int width, int height, boolean raster) {
        try {
            List<byte[]> frames = loadSingleFramesFromCache(url, width, height, raster, fps);
            if (frames == null || frames.isEmpty()) return false;
            registerAndStartSingle(name, mapId, fps, frames);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean registerAndStartMultiFromCache(String name, List<Long> mapIdsInTileOrder, int width, int height, int fps, String url, boolean raster) {
        try {
            List<byte[][]> frames = loadMultiFramesFromCache(url, width, height, raster, fps);
            if (frames == null || frames.isEmpty()) return false;
            registerAndStartMulti(name, mapIdsInTileOrder, width, height, fps, frames);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Persists an animation definition so it can be restored after restart/reload.
     *
     * This does NOT write any pixel data itself; the caller must ensure processed color frames
     * exist on disk in the expected cache structure (see FineMapsCommand url import).
     */
    public void persistSingleDefinition(String name, String url, int width, int height, boolean raster, int fps, long mapId) {
        if (name == null || name.isBlank() || url == null || url.isBlank()) return;
        if (mapId <= 0) return;
        YamlConfiguration cfg = loadPersistConfig();
        String base = "animations." + name + ".";
        cfg.set(base + "type", "single");
        cfg.set(base + "url", url);
        cfg.set(base + "width", width);
        cfg.set(base + "height", height);
        cfg.set(base + "raster", raster);
        cfg.set(base + "fps", fps);
        cfg.set(base + "mapId", mapId);

        // If there isn't already a playhead, initialize it "now".
        if (cfg.get(base + "paused") == null && cfg.get(base + "startEpochMs") == null) {
            cfg.set(base + "paused", false);
            cfg.set(base + "startEpochMs", System.currentTimeMillis());
            cfg.set(base + "offsetMs", 0L);
            cfg.set(base + "pausedPositionMs", 0L);
        }
        savePersistConfig(cfg);
    }

    public void persistMultiDefinition(String name, String url, int width, int height, boolean raster, int fps, long groupId) {
        if (name == null || name.isBlank() || url == null || url.isBlank()) return;
        if (groupId <= 0) return;
        YamlConfiguration cfg = loadPersistConfig();
        String base = "animations." + name + ".";
        cfg.set(base + "type", "multi");
        cfg.set(base + "url", url);
        cfg.set(base + "width", width);
        cfg.set(base + "height", height);
        cfg.set(base + "raster", raster);
        cfg.set(base + "fps", fps);
        cfg.set(base + "groupId", groupId);

        if (cfg.get(base + "paused") == null && cfg.get(base + "startEpochMs") == null) {
            cfg.set(base + "paused", false);
            cfg.set(base + "startEpochMs", System.currentTimeMillis());
            cfg.set(base + "offsetMs", 0L);
            cfg.set(base + "pausedPositionMs", 0L);
        }
        savePersistConfig(cfg);
    }

    /**
     * Loads persisted animations from disk and starts them. Safe to call multiple times.
     */
    public void loadAndStartPersisted() {
        if (!loadedPersisted.compareAndSet(false, true)) {
            return;
        }
        YamlConfiguration cfg = loadPersistConfig();
        if (cfg == null) return;
        if (!cfg.isConfigurationSection("animations")) return;

        for (String name : cfg.getConfigurationSection("animations").getKeys(false)) {
            if (name == null || name.isBlank()) continue;
            String base = "animations." + name + ".";
            String type = cfg.getString(base + "type", "");
            String url = cfg.getString(base + "url", null);
            int width = cfg.getInt(base + "width", 1);
            int height = cfg.getInt(base + "height", 1);
            boolean raster = cfg.getBoolean(base + "raster", true);
            int fps = cfg.getInt(base + "fps", 10);

            boolean paused = cfg.getBoolean(base + "paused", false);
            long startEpochMs = cfg.getLong(base + "startEpochMs", System.currentTimeMillis());
            long offsetMs = cfg.getLong(base + "offsetMs", 0L);
            long pausedPositionMs = cfg.getLong(base + "pausedPositionMs", 0L);

            if (url == null || url.isBlank()) continue;

            try {
                if ("single".equalsIgnoreCase(type)) {
                    long mapId = cfg.getLong(base + "mapId", -1L);
                    if (mapId <= 0) continue;
                    List<byte[]> frames = loadSingleFramesFromCache(url, width, height, raster, fps);
                    if (frames == null || frames.isEmpty()) continue;
                    Animation a = Animation.single(plugin, mapManager, () -> viewersByDbMapId, mapId, fps, frames);
                    a.restorePlayhead(paused, startEpochMs, offsetMs, pausedPositionMs);
                    Animation existing = animationsByName.put(name, a);
                    if (existing != null) existing.stop();
                    ensureViewerScan();
                    a.start();
                    continue;
                }
                if ("multi".equalsIgnoreCase(type)) {
                    long groupId = cfg.getLong(base + "groupId", -1L);
                    if (groupId <= 0) continue;
                    // Resolve map ids in tile order from DB.
                    com.example.finemaps.api.map.MultiBlockMap mm = mapManager.getMultiBlockMap(groupId).join().orElse(null);
                    if (mm == null) continue;
                    List<Long> mapIds = tileOrderMapIds(mm, width, height);
                    if (mapIds == null || mapIds.isEmpty()) continue;
                    List<byte[][]> frames = loadMultiFramesFromCache(url, width, height, raster, fps);
                    if (frames == null || frames.isEmpty()) continue;
                    Animation a = Animation.multi(plugin, mapManager, () -> viewersByDbMapId, mapIds, width, height, fps, frames);
                    a.restorePlayhead(paused, startEpochMs, offsetMs, pausedPositionMs);
                    Animation existing = animationsByName.put(name, a);
                    if (existing != null) existing.stop();
                    ensureViewerScan();
                    a.start();
                }
            } catch (Throwable ignored) {
                // Best-effort; a bad entry should not prevent the plugin from enabling.
            }
        }
    }

    /**
     * Saves current playhead state for all running animations to disk.
     */
    public void savePersistedState() {
        YamlConfiguration cfg = loadPersistConfig();
        if (cfg == null) return;
        if (!cfg.isConfigurationSection("animations")) {
            cfg.createSection("animations");
        }
        for (Map.Entry<String, Animation> e : animationsByName.entrySet()) {
            String name = e.getKey();
            Animation a = e.getValue();
            if (name == null || name.isBlank() || a == null) continue;
            String base = "animations." + name + ".";
            Animation.PlayheadSnapshot snap = a.snapshotPlayhead();
            cfg.set(base + "paused", snap.paused);
            cfg.set(base + "startEpochMs", snap.startEpochMs);
            cfg.set(base + "offsetMs", snap.offsetMs);
            cfg.set(base + "pausedPositionMs", snap.pausedPositionMs);
        }
        savePersistConfig(cfg);
    }

    /**
     * Animation controls (used by /fm anim ...).
     */
    public boolean restartByDbMapId(long dbMapId) {
        Animation a = findByDbMapId(dbMapId);
        if (a == null) return false;
        a.restartNow();
        return true;
    }

    public Boolean togglePauseByDbMapId(long dbMapId) {
        Animation a = findByDbMapId(dbMapId);
        if (a == null) return null;
        return a.togglePause();
    }

    public boolean skipByDbMapId(long dbMapId, long deltaMs) {
        if (deltaMs == 0) return true;
        Animation a = findByDbMapId(dbMapId);
        if (a == null) return false;
        a.skipMs(deltaMs);
        return true;
    }

    private Animation findByDbMapId(long dbMapId) {
        if (dbMapId <= 0) return null;
        for (Animation a : animationsByName.values()) {
            if (a != null && a.containsDbMapId(dbMapId)) return a;
        }
        return null;
    }

    private void ensureViewerScan() {
        if (viewerScanTask != null) return;
        // Rebuild the viewer index periodically; this lets us push map packets for maps in item frames,
        // which otherwise update very infrequently compared to held maps.
        viewerScanTask = FineMapsScheduler.runSyncRepeating(plugin, this::rebuildViewerIndex, 20L, 20L);
    }

    private void stopViewerScan() {
        if (viewerScanTask != null) {
            FineMapsScheduler.cancel(viewerScanTask);
            viewerScanTask = null;
        }
        viewersByDbMapId = new HashMap<>();
    }

    private void rebuildViewerIndex() {
        // Only keep viewer entries for currently-running animations.
        if (animationsByName.isEmpty()) {
            // Avoid doing work when there are no animations.
            stopViewerScan();
            return;
        }

        Set<Long> animatedDbMapIds = new HashSet<>();
        for (Animation a : animationsByName.values()) {
            a.collectDbMapIds(animatedDbMapIds);
        }
        if (animatedDbMapIds.isEmpty()) {
            viewersByDbMapId = new HashMap<>();
            return;
        }

        Map<Long, Map<UUID, Set<Integer>>> prev = viewersByDbMapId;
        Map<Long, Map<UUID, Set<Integer>>> next = new HashMap<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null || !player.isOnline()) continue;
            UUID uuid = player.getUniqueId();

            // 1) In-hand/off-hand maps (usually already smooth, but keep in sync).
            addViewerFromItem(next, animatedDbMapIds, uuid, player.getInventory().getItemInMainHand());
            addViewerFromItem(next, animatedDbMapIds, uuid, player.getInventory().getItemInOffHand());

            // 2) Nearby item frames (this is the key for smooth in-world playback).
            // Keep radius modest; we rebuild once per second and only for animated maps.
            for (org.bukkit.entity.Entity e : player.getNearbyEntities(48, 48, 48)) {
                if (!(e instanceof ItemFrame)) continue;
                ItemFrame frame = (ItemFrame) e;
                ItemStack item = frame.getItem();
                addViewerFromItem(next, animatedDbMapIds, uuid, item);
            }
        }

        // Swap in the index first (so Animation pushes see the latest viewers).
        viewersByDbMapId = next;

        // Immediately push the current frame to newly-added viewers.
        // This fixes the "invisible until you pick up / re-place" behavior after restarts,
        // and ensures players see the animation as soon as they come into range.
        if (prev != null && !prev.isEmpty()) {
            pushCurrentFramesToNewViewers(prev, next);
        } else {
            // On the first scan after startup, treat everyone as "new".
            pushCurrentFramesToNewViewers(new HashMap<>(), next);
        }
    }

    private void pushCurrentFramesToNewViewers(Map<Long, Map<UUID, Set<Integer>>> prev,
                                               Map<Long, Map<UUID, Set<Integer>>> next) {
        if (next == null || next.isEmpty()) return;
        for (Map.Entry<Long, Map<UUID, Set<Integer>>> e : next.entrySet()) {
            long dbMapId = e.getKey() != null ? e.getKey() : -1L;
            if (dbMapId <= 0) continue;
            Map<UUID, Set<Integer>> nextByPlayer = e.getValue();
            if (nextByPlayer == null || nextByPlayer.isEmpty()) continue;

            Map<UUID, Set<Integer>> prevByPlayer = (prev != null) ? prev.get(dbMapId) : null;
            for (Map.Entry<UUID, Set<Integer>> pe : nextByPlayer.entrySet()) {
                UUID uuid = pe.getKey();
                if (uuid == null) continue;
                Set<Integer> nextIds = pe.getValue();
                if (nextIds == null || nextIds.isEmpty()) continue;

                Set<Integer> prevIds = (prevByPlayer != null) ? prevByPlayer.get(uuid) : null;
                for (Integer vanillaMapId : nextIds) {
                    if (vanillaMapId == null || vanillaMapId < 0) continue;
                    if (prevIds != null && prevIds.contains(vanillaMapId)) continue; // not new

                    Player p = Bukkit.getPlayer(uuid);
                    if (p == null || !p.isOnline()) continue;
                    Animation a = findByDbMapId(dbMapId);
                    if (a == null) continue;
                    a.forceSendCurrentFrameTo(p, vanillaMapId, dbMapId);
                }
            }
        }
    }

    private void addViewerFromItem(Map<Long, Map<UUID, Set<Integer>>> out,
                                   Set<Long> animatedDbMapIds,
                                   UUID playerUuid,
                                   ItemStack item) {
        if (item == null) return;
        long dbMapId = mapManager.getMapIdFromItem(item);
        if (dbMapId <= 0) return;
        if (!animatedDbMapIds.contains(dbMapId)) return;

        int vanillaMapId = nmsAdapter.getMapId(item);
        if (vanillaMapId < 0) return;

        // Critical after restart: re-bind the existing Bukkit map id in this item to our renderer mapping.
        // This makes Bukkit adapter updates (player.sendMap) work again without re-placing frames.
        try {
            mapManager.bindMapViewToItem(item);
        } catch (Throwable ignored) {
        }

        Map<UUID, Set<Integer>> byPlayer = out.computeIfAbsent(dbMapId, k -> new HashMap<>());
        Set<Integer> mapIds = byPlayer.computeIfAbsent(playerUuid, k -> new HashSet<>());
        mapIds.add(vanillaMapId);
    }

    private static final class Animation implements Runnable {
        private final Plugin plugin;
        private final MapManager mapManager;
        private final NMSAdapter nmsAdapter;
        private final java.util.function.Supplier<Map<Long, Map<UUID, Set<Integer>>>> viewersSupplier;
        private final int fps;
        private final double frameDurationMs;

        private final boolean isMulti;
        private final long singleMapId;
        private final List<byte[]> singleFrames;

        private final List<Long> multiMapIds;
        private final int multiWidth;
        private final int multiHeight;
        private final List<byte[][]> multiFrames;

        private Object taskHandle = null;
        private volatile boolean paused = false;
        private volatile long startEpochMs = System.currentTimeMillis();
        private volatile long offsetMs = 0L;
        private volatile long pausedPositionMs = 0L;

        private volatile int lastIdx = -1;

        private Animation(Plugin plugin,
                          MapManager mapManager,
                          java.util.function.Supplier<Map<Long, Map<UUID, Set<Integer>>>> viewersSupplier,
                          int fps,
                          boolean isMulti,
                          long singleMapId,
                          List<byte[]> singleFrames,
                          List<Long> multiMapIds,
                          int multiWidth,
                          int multiHeight,
                          List<byte[][]> multiFrames) {
            this.plugin = plugin;
            this.mapManager = mapManager;
            this.nmsAdapter = mapManager.getNmsAdapter();
            this.viewersSupplier = viewersSupplier != null ? viewersSupplier : java.util.Collections::emptyMap;
            this.fps = Math.max(1, Math.min(20, fps));
            this.frameDurationMs = 1000.0 / this.fps;
            this.isMulti = isMulti;
            this.singleMapId = singleMapId;
            this.singleFrames = singleFrames;
            this.multiMapIds = multiMapIds;
            this.multiWidth = multiWidth;
            this.multiHeight = multiHeight;
            this.multiFrames = multiFrames;
        }

        static Animation single(Plugin plugin,
                                MapManager mapManager,
                                java.util.function.Supplier<Map<Long, Map<UUID, Set<Integer>>>> viewersSupplier,
                                long mapId,
                                int fps,
                                List<byte[]> frames) {
            return new Animation(plugin, mapManager, viewersSupplier, fps, false, mapId, frames, null, 0, 0, null);
        }

        static Animation multi(Plugin plugin,
                               MapManager mapManager,
                               java.util.function.Supplier<Map<Long, Map<UUID, Set<Integer>>>> viewersSupplier,
                               List<Long> mapIdsInTileOrder,
                               int width,
                               int height,
                               int fps,
                               List<byte[][]> frames) {
            return new Animation(plugin, mapManager, viewersSupplier, fps, true, -1L, null, mapIdsInTileOrder, width, height, frames);
        }

        void start() {
            if (taskHandle != null) return;
            // Run every tick; we compute frame from wall-clock time to survive chunk unload/reload and plugin reload.
            taskHandle = FineMapsScheduler.runSyncRepeating(plugin, this, 1L, 1L);
        }

        void stop() {
            if (taskHandle != null) {
                FineMapsScheduler.cancel(taskHandle);
                taskHandle = null;
            }
        }

        @Override
        public void run() {
            int idx = currentFrameIndex();
            if (idx < 0) return;
            if (idx == lastIdx) return;
            lastIdx = idx;

            if (!isMulti) {
                if (singleFrames == null || singleFrames.isEmpty()) return;
                byte[] frame = singleFrames.get(idx % singleFrames.size());
                if (frame == null || frame.length != com.example.finemaps.api.map.MapData.TOTAL_PIXELS) return;
                // Always update in-memory pixels (keeps compatibility with the MapRenderer path).
                mapManager.updateMapPixelsRuntime(singleMapId, frame);
                // If ProtocolLib is present, push packets to viewers (smooth in-world playback).
                pushToViewers(singleMapId, frame);
                return;
            }

            if (multiFrames == null || multiFrames.isEmpty()) return;
            if (multiMapIds == null || multiMapIds.isEmpty()) return;
            byte[][] tiles = multiFrames.get(idx % multiFrames.size());
            int expectedTiles = multiWidth * multiHeight;
            if (tiles == null || tiles.length != expectedTiles) return;
            if (multiMapIds.size() != expectedTiles) return;

            for (int i = 0; i < expectedTiles; i++) {
                long mapId = multiMapIds.get(i);
                byte[] pixels = tiles[i];
                if (pixels == null || pixels.length != com.example.finemaps.api.map.MapData.TOTAL_PIXELS) continue;
                mapManager.updateMapPixelsRuntime(mapId, pixels);
                pushToViewers(mapId, pixels);
            }
        }

        void collectDbMapIds(Set<Long> out) {
            if (out == null) return;
            if (!isMulti) {
                if (singleMapId > 0) out.add(singleMapId);
                return;
            }
            if (multiMapIds != null) out.addAll(multiMapIds);
        }

        boolean containsDbMapId(long dbMapId) {
            if (dbMapId <= 0) return false;
            if (!isMulti) {
                return singleMapId == dbMapId;
            }
            return multiMapIds != null && multiMapIds.contains(dbMapId);
        }

        void restorePlayhead(boolean paused, long startEpochMs, long offsetMs, long pausedPositionMs) {
            this.paused = paused;
            this.startEpochMs = startEpochMs > 0 ? startEpochMs : System.currentTimeMillis();
            this.offsetMs = offsetMs;
            this.pausedPositionMs = Math.max(0L, pausedPositionMs);
            this.lastIdx = -1; // force immediate push
        }

        void restartNow() {
            this.paused = false;
            this.startEpochMs = System.currentTimeMillis();
            this.offsetMs = 0L;
            this.pausedPositionMs = 0L;
            this.lastIdx = -1;
        }

        boolean togglePause() {
            if (!paused) {
                // Pause: capture current position (mod duration).
                this.pausedPositionMs = currentPositionMsModDuration();
                this.paused = true;
                this.lastIdx = -1; // force a push on resume
                return true;
            }
            // Resume: convert paused position into (start, offset) anchored "now".
            this.paused = false;
            this.startEpochMs = System.currentTimeMillis();
            this.offsetMs = this.pausedPositionMs;
            this.lastIdx = -1;
            return false;
        }

        void skipMs(long deltaMs) {
            if (deltaMs == 0) return;
            if (paused) {
                long dur = durationMs();
                if (dur <= 0) return;
                long next = this.pausedPositionMs + deltaMs;
                // Normalize into [0, dur)
                this.pausedPositionMs = floorMod(next, dur);
                this.lastIdx = -1;
                return;
            }
            this.offsetMs += deltaMs;
            this.lastIdx = -1;
        }

        PlayheadSnapshot snapshotPlayhead() {
            if (paused) {
                return new PlayheadSnapshot(true, startEpochMs, offsetMs, currentPositionMsModDuration());
            }
            // For running animations we store raw start/offset; this preserves continuity across reloads.
            return new PlayheadSnapshot(false, startEpochMs, offsetMs, 0L);
        }

        private int currentFrameIndex() {
            int frameCount = frameCount();
            if (frameCount <= 0) return -1;
            long dur = durationMs();
            if (dur <= 0) return -1;

            long posMs = paused ? floorMod(pausedPositionMs, dur) : currentPositionMsModDuration();
            // Convert position into frame index.
            long frameNum = (long) Math.floor(posMs / frameDurationMs);
            int idx = (int) floorMod(frameNum, frameCount);
            return idx;
        }

        private long currentPositionMsModDuration() {
            long dur = durationMs();
            if (dur <= 0) return 0L;
            long now = System.currentTimeMillis();
            long elapsed = (now - startEpochMs) + offsetMs;
            return floorMod(elapsed, dur);
        }

        private int frameCount() {
            if (!isMulti) {
                return (singleFrames != null) ? singleFrames.size() : 0;
            }
            return (multiFrames != null) ? multiFrames.size() : 0;
        }

        private long durationMs() {
            int count = frameCount();
            if (count <= 0) return 0L;
            // Use double duration and round to nearest millisecond to avoid truncating to 0 at high fps.
            double total = frameDurationMs * count;
            long ms = (long) Math.round(total);
            return Math.max(1L, ms);
        }

        private static long floorMod(long a, long b) {
            if (b <= 0) return 0L;
            long r = a % b;
            return r < 0 ? (r + b) : r;
        }

        private void pushToViewers(long dbMapId, byte[] pixels) {
            if (pixels == null || pixels.length != com.example.finemaps.api.map.MapData.TOTAL_PIXELS) return;
            // Only ProtocolLibAdapter actually sends; Bukkit adapter is a no-op.
            Map<Long, Map<UUID, Set<Integer>>> viewers = viewersSupplier.get();
            if (viewers == null || viewers.isEmpty()) return;
            Map<UUID, Set<Integer>> byPlayer = viewers.get(dbMapId);
            if (byPlayer == null || byPlayer.isEmpty()) return;

            for (Map.Entry<UUID, Set<Integer>> e : byPlayer.entrySet()) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p == null || !p.isOnline()) continue;
                Set<Integer> vanillaIds = e.getValue();
                if (vanillaIds == null || vanillaIds.isEmpty()) continue;
                for (Integer vanillaMapId : vanillaIds) {
                    if (vanillaMapId == null || vanillaMapId < 0) continue;
                    nmsAdapter.sendMapUpdate(p, vanillaMapId, pixels);
                }
            }
        }

        /**
         * Sends the current frame immediately to a specific player/map id pair.
         * Used when a viewer first comes into range so they don't have to interact to "wake up" the map.
         */
        private void forceSendCurrentFrameTo(Player player, int vanillaMapId, long dbMapId) {
            if (player == null || !player.isOnline()) return;
            if (vanillaMapId < 0 || dbMapId <= 0) return;

            int idx = currentFrameIndex();
            if (idx < 0) return;

            byte[] pixels = null;
            if (!isMulti) {
                if (singleMapId != dbMapId) return;
                if (singleFrames == null || singleFrames.isEmpty()) return;
                pixels = singleFrames.get(idx % singleFrames.size());
            } else {
                if (multiFrames == null || multiFrames.isEmpty()) return;
                if (multiMapIds == null || multiMapIds.isEmpty()) return;
                int tileIdx = multiMapIds.indexOf(dbMapId);
                if (tileIdx < 0) return;
                byte[][] tiles = multiFrames.get(idx % multiFrames.size());
                if (tiles == null || tileIdx >= tiles.length) return;
                pixels = tiles[tileIdx];
            }

            if (pixels == null || pixels.length != com.example.finemaps.api.map.MapData.TOTAL_PIXELS) return;

            // Ensure our runtime pixel cache/renderer is in sync, then push to the client.
            mapManager.updateMapPixelsRuntime(dbMapId, pixels);
            nmsAdapter.sendMapUpdate(player, vanillaMapId, pixels);
        }

        private static final class PlayheadSnapshot {
            final boolean paused;
            final long startEpochMs;
            final long offsetMs;
            final long pausedPositionMs;

            private PlayheadSnapshot(boolean paused, long startEpochMs, long offsetMs, long pausedPositionMs) {
                this.paused = paused;
                this.startEpochMs = startEpochMs;
                this.offsetMs = offsetMs;
                this.pausedPositionMs = pausedPositionMs;
            }
        }
    }

    private YamlConfiguration loadPersistConfig() {
        try {
            if (!plugin.getDataFolder().exists()) {
                //noinspection ResultOfMethodCallIgnored
                plugin.getDataFolder().mkdirs();
            }
            if (!persistFile.exists()) {
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(persistFile);
        } catch (Throwable t) {
            return new YamlConfiguration();
        }
    }

    private void savePersistConfig(YamlConfiguration cfg) {
        if (cfg == null) return;
        try {
            if (!plugin.getDataFolder().exists()) {
                //noinspection ResultOfMethodCallIgnored
                plugin.getDataFolder().mkdirs();
            }
            cfg.save(persistFile);
        } catch (IOException ignored) {
        }
    }

    private List<byte[]> loadSingleFramesFromCache(String url, int width, int height, boolean raster, int fps) throws IOException {
        if (width != 1 || height != 1) return java.util.Collections.emptyList();
        Path dir = cacheVariantColorsDir(url, width, height, raster, fps);
        if (dir == null || !Files.isDirectory(dir)) return java.util.Collections.emptyList();
        List<Path> files = listFrameFiles(dir);
        if (files.isEmpty()) return java.util.Collections.emptyList();
        return new DiskSingleFrameList(files, frameCacheFrames);
    }

    private List<byte[][]> loadMultiFramesFromCache(String url, int width, int height, boolean raster, int fps) throws IOException {
        if (width <= 0 || height <= 0) return java.util.Collections.emptyList();
        int tiles = width * height;
        int tileSize = com.example.finemaps.api.map.MapData.TOTAL_PIXELS;
        Path dir = cacheVariantColorsDir(url, width, height, raster, fps);
        if (dir == null || !Files.isDirectory(dir)) return java.util.Collections.emptyList();
        List<Path> files = listFrameFiles(dir);
        if (files.isEmpty()) return java.util.Collections.emptyList();
        return new DiskMultiFrameList(files, tiles, tileSize, frameCacheFrames);
    }

    private List<Path> listFrameFiles(Path dir) throws IOException {
        java.util.List<Path> files = new java.util.ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "frame_*.bin")) {
            for (Path p : ds) {
                if (p != null && Files.isRegularFile(p)) files.add(p);
            }
        }
        files.sort((a, b) -> {
            int ia = parseFrameIndex(a != null ? a.getFileName().toString() : null);
            int ib = parseFrameIndex(b != null ? b.getFileName().toString() : null);
            if (ia != Integer.MIN_VALUE && ib != Integer.MIN_VALUE) {
                return Integer.compare(ia, ib);
            }
            return String.valueOf(a != null ? a.getFileName() : "").compareTo(String.valueOf(b != null ? b.getFileName() : ""));
        });
        return files;
    }

    private int parseFrameIndex(String filename) {
        if (filename == null) return Integer.MIN_VALUE;
        // frame_0000.bin / frame_10000.bin
        int us = filename.indexOf('_');
        int dot = filename.lastIndexOf('.');
        if (us < 0) return Integer.MIN_VALUE;
        if (dot < 0) dot = filename.length();
        if (dot <= us + 1) return Integer.MIN_VALUE;
        String mid = filename.substring(us + 1, dot);
        try {
            return Integer.parseInt(mid);
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static final class DiskSingleFrameList extends java.util.AbstractList<byte[]> implements java.util.RandomAccess {
        private final List<Path> files;
        private final int cacheCap;
        private final java.util.LinkedHashMap<Integer, byte[]> cache;

        private DiskSingleFrameList(List<Path> files, int cacheCap) {
            this.files = files != null ? files : java.util.Collections.emptyList();
            this.cacheCap = Math.max(0, cacheCap);
            this.cache = new java.util.LinkedHashMap<Integer, byte[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Integer, byte[]> eldest) {
                    return DiskSingleFrameList.this.cacheCap > 0 && size() > DiskSingleFrameList.this.cacheCap;
                }
            };
        }

        @Override
        public int size() {
            return files.size();
        }

        @Override
        public byte[] get(int index) {
            if (index < 0 || index >= files.size()) throw new IndexOutOfBoundsException();
            synchronized (cache) {
                byte[] cached = cache.get(index);
                if (cached != null) return cached;
            }
            try {
                byte[] bytes = Files.readAllBytes(files.get(index));
                if (bytes.length != com.example.finemaps.api.map.MapData.TOTAL_PIXELS) {
                    return null;
                }
                if (cacheCap > 0) {
                    synchronized (cache) {
                        cache.put(index, bytes);
                    }
                }
                return bytes;
            } catch (IOException e) {
                return null;
            }
        }
    }

    private static final class DiskMultiFrameList extends java.util.AbstractList<byte[][]> implements java.util.RandomAccess {
        private final List<Path> files;
        private final int tiles;
        private final int tileSize;
        private final int expected;
        private final int cacheCap;
        private final java.util.LinkedHashMap<Integer, byte[][]> cache;

        private DiskMultiFrameList(List<Path> files, int tiles, int tileSize, int cacheCap) {
            this.files = files != null ? files : java.util.Collections.emptyList();
            this.tiles = Math.max(1, tiles);
            this.tileSize = Math.max(1, tileSize);
            this.expected = this.tiles * this.tileSize;
            this.cacheCap = Math.max(0, cacheCap);
            this.cache = new java.util.LinkedHashMap<Integer, byte[][]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Integer, byte[][]> eldest) {
                    return DiskMultiFrameList.this.cacheCap > 0 && size() > DiskMultiFrameList.this.cacheCap;
                }
            };
        }

        @Override
        public int size() {
            return files.size();
        }

        @Override
        public byte[][] get(int index) {
            if (index < 0 || index >= files.size()) throw new IndexOutOfBoundsException();
            synchronized (cache) {
                byte[][] cached = cache.get(index);
                if (cached != null) return cached;
            }
            try {
                byte[] bytes = Files.readAllBytes(files.get(index));
                if (bytes.length != expected) {
                    return null;
                }
                byte[][] tilesArr = new byte[tiles][tileSize];
                int off = 0;
                for (int i = 0; i < tiles; i++) {
                    System.arraycopy(bytes, off, tilesArr[i], 0, tileSize);
                    off += tileSize;
                }
                if (cacheCap > 0) {
                    synchronized (cache) {
                        cache.put(index, tilesArr);
                    }
                }
                return tilesArr;
            } catch (IOException e) {
                return null;
            }
        }
    }

    private Path cacheVariantColorsDir(String url, int width, int height, boolean raster, int fps) {
        if (url == null) return null;
        Path baseDir = UrlCache.cacheDirForUrl(plugin.getDataFolder(), cacheFolderName, url);
        String variant = String.format(java.util.Locale.ROOT, "variant_%dx%d_r%s_fps%d", width, height, raster ? "1" : "0", fps);
        return baseDir.resolve(variant).resolve("colors");
    }

    private List<Long> tileOrderMapIds(com.example.finemaps.api.map.MultiBlockMap multiMap, int width, int height) {
        if (multiMap == null) return java.util.Collections.emptyList();
        long[] ids = new long[Math.max(1, width * height)];
        java.util.Arrays.fill(ids, -1L);
        for (com.example.finemaps.api.map.StoredMap m : multiMap.getMaps()) {
            int x = m.getGridX();
            int y = m.getGridY();
            if (x < 0 || y < 0 || x >= width || y >= height) continue;
            ids[y * width + x] = m.getId();
        }
        List<Long> out = new java.util.ArrayList<>();
        for (long id : ids) out.add(id);
        return out;
    }
}

