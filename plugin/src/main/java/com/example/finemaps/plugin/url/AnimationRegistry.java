package com.example.finemaps.plugin.url;

import com.example.finemaps.core.manager.MapManager;
import com.example.finemaps.api.nms.NMSAdapter;
import org.bukkit.Bukkit;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Map<String, Animation> animationsByName = new ConcurrentHashMap<>();
    /**
     * Viewer index (rebuilt periodically on the main thread):
     * dbMapId -> (player UUID -> vanilla map IDs to update for that player).
     *
     * We use vanilla map IDs because item frames / items may carry a different Bukkit map id
     * than our runtime MapViewManager mapping (e.g. after restarts).
     */
    private volatile Map<Long, Map<UUID, Set<Integer>>> viewersByDbMapId = new HashMap<>();
    private int viewerScanTaskId = -1;

    public AnimationRegistry(Plugin plugin, MapManager mapManager) {
        this.plugin = plugin;
        this.mapManager = mapManager;
        this.nmsAdapter = mapManager.getNmsAdapter();
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

    private void ensureViewerScan() {
        if (viewerScanTaskId != -1) return;
        // Rebuild the viewer index periodically; this lets us push map packets for maps in item frames,
        // which otherwise update very infrequently compared to held maps.
        viewerScanTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::rebuildViewerIndex, 20L, 20L);
    }

    private void stopViewerScan() {
        if (viewerScanTaskId != -1) {
            Bukkit.getScheduler().cancelTask(viewerScanTaskId);
            viewerScanTaskId = -1;
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

        viewersByDbMapId = next;
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

        Map<UUID, Set<Integer>> byPlayer = out.computeIfAbsent(dbMapId, k -> new HashMap<>());
        Set<Integer> mapIds = byPlayer.computeIfAbsent(playerUuid, k -> new HashSet<>());
        mapIds.add(vanillaMapId);
    }

    private static final class Animation implements Runnable {
        private final Plugin plugin;
        private final MapManager mapManager;
        private final NMSAdapter nmsAdapter;
        private final java.util.function.Supplier<Map<Long, Map<UUID, Set<Integer>>>> viewersSupplier;
        private final double ticksPerFrame;

        private final boolean isMulti;
        private final long singleMapId;
        private final List<byte[]> singleFrames;

        private final List<Long> multiMapIds;
        private final int multiWidth;
        private final int multiHeight;
        private final List<byte[][]> multiFrames;

        private int taskId = -1;
        private int idx = 0;
        private double tickAccumulator = 0.0;

        private Animation(Plugin plugin,
                          MapManager mapManager,
                          java.util.function.Supplier<Map<Long, Map<UUID, Set<Integer>>>> viewersSupplier,
                          double ticksPerFrame,
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
            this.ticksPerFrame = Math.max(1.0, ticksPerFrame);
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
            double tpf = ticksPerFrameForFps(fps);
            return new Animation(plugin, mapManager, viewersSupplier, tpf, false, mapId, frames, null, 0, 0, null);
        }

        static Animation multi(Plugin plugin,
                               MapManager mapManager,
                               java.util.function.Supplier<Map<Long, Map<UUID, Set<Integer>>>> viewersSupplier,
                               List<Long> mapIdsInTileOrder,
                               int width,
                               int height,
                               int fps,
                               List<byte[][]> frames) {
            double tpf = ticksPerFrameForFps(fps);
            return new Animation(plugin, mapManager, viewersSupplier, tpf, true, -1L, null, mapIdsInTileOrder, width, height, frames);
        }

        void start() {
            if (taskId != -1) return;
            // Run every tick and advance frames based on a stable accumulator.
            taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this, 1L, 1L);
        }

        void stop() {
            if (taskId != -1) {
                Bukkit.getScheduler().cancelTask(taskId);
                taskId = -1;
            }
        }

        @Override
        public void run() {
            // Maintain stable timing even when fps doesn't divide 20 cleanly.
            tickAccumulator += 1.0;
            if (tickAccumulator + 1e-9 < ticksPerFrame) {
                return;
            }
            // If we fell behind, advance multiple frames but cap the catch-up per tick to avoid spirals.
            int advance = 0;
            while (tickAccumulator + 1e-9 >= ticksPerFrame && advance < 5) {
                tickAccumulator -= ticksPerFrame;
                advance++;
            }
            if (advance <= 0) return;

            if (!isMulti) {
                if (singleFrames == null || singleFrames.isEmpty()) return;
                for (int a = 0; a < advance; a++) {
                    byte[] frame = singleFrames.get(idx % singleFrames.size());
                    idx++;
                    // Always update in-memory pixels (keeps compatibility with the MapRenderer path).
                    mapManager.updateMapPixelsRuntime(singleMapId, frame);
                    // If ProtocolLib is present, push packets to viewers (smooth in-world playback).
                    pushToViewers(singleMapId, frame);
                }
                return;
            }

            if (multiFrames == null || multiFrames.isEmpty()) return;
            if (multiMapIds == null || multiMapIds.isEmpty()) return;

            for (int a = 0; a < advance; a++) {
                byte[][] tiles = multiFrames.get(idx % multiFrames.size());
                idx++;
                int expectedTiles = multiWidth * multiHeight;
                if (tiles == null || tiles.length != expectedTiles) return;
                if (multiMapIds.size() != expectedTiles) return;

                for (int i = 0; i < expectedTiles; i++) {
                    long mapId = multiMapIds.get(i);
                    byte[] pixels = tiles[i];
                    mapManager.updateMapPixelsRuntime(mapId, pixels);
                    pushToViewers(mapId, pixels);
                }
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

        private static double ticksPerFrameForFps(int fps) {
            int safe = Math.max(1, Math.min(20, fps));
            // 20 server ticks per second.
            return 20.0 / safe;
        }
    }
}

