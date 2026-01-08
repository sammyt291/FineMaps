package com.example.finemaps.plugin.url;

import com.example.finemaps.core.manager.MapManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
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

    private final Map<String, Animation> animationsByName = new ConcurrentHashMap<>();

    public AnimationRegistry(Plugin plugin, MapManager mapManager) {
        this.plugin = plugin;
        this.mapManager = mapManager;
    }

    public void stopAll() {
        for (Animation a : animationsByName.values()) {
            a.stop();
        }
        animationsByName.clear();
    }

    public void registerAndStartSingle(String name, long mapId, int fps, List<byte[]> frames) {
        Animation existing = animationsByName.remove(name);
        if (existing != null) existing.stop();
        Animation a = Animation.single(plugin, mapManager, mapId, fps, frames);
        animationsByName.put(name, a);
        a.start();
    }

    public void registerAndStartMulti(String name, List<Long> mapIdsInTileOrder, int width, int height, int fps, List<byte[][]> frames) {
        Animation existing = animationsByName.remove(name);
        if (existing != null) existing.stop();
        Animation a = Animation.multi(plugin, mapManager, mapIdsInTileOrder, width, height, fps, frames);
        animationsByName.put(name, a);
        a.start();
    }

    private static final class Animation implements Runnable {
        private final Plugin plugin;
        private final MapManager mapManager;
        private final int periodTicks;

        private final boolean isMulti;
        private final long singleMapId;
        private final List<byte[]> singleFrames;

        private final List<Long> multiMapIds;
        private final int multiWidth;
        private final int multiHeight;
        private final List<byte[][]> multiFrames;

        private int taskId = -1;
        private int idx = 0;

        private Animation(Plugin plugin,
                          MapManager mapManager,
                          int periodTicks,
                          boolean isMulti,
                          long singleMapId,
                          List<byte[]> singleFrames,
                          List<Long> multiMapIds,
                          int multiWidth,
                          int multiHeight,
                          List<byte[][]> multiFrames) {
            this.plugin = plugin;
            this.mapManager = mapManager;
            this.periodTicks = periodTicks;
            this.isMulti = isMulti;
            this.singleMapId = singleMapId;
            this.singleFrames = singleFrames;
            this.multiMapIds = multiMapIds;
            this.multiWidth = multiWidth;
            this.multiHeight = multiHeight;
            this.multiFrames = multiFrames;
        }

        static Animation single(Plugin plugin, MapManager mapManager, long mapId, int fps, List<byte[]> frames) {
            int p = periodForFps(fps);
            return new Animation(plugin, mapManager, p, false, mapId, frames, null, 0, 0, null);
        }

        static Animation multi(Plugin plugin, MapManager mapManager, List<Long> mapIdsInTileOrder, int width, int height, int fps, List<byte[][]> frames) {
            int p = periodForFps(fps);
            return new Animation(plugin, mapManager, p, true, -1L, null, mapIdsInTileOrder, width, height, frames);
        }

        void start() {
            if (taskId != -1) return;
            taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this, 1L, Math.max(1, periodTicks));
        }

        void stop() {
            if (taskId != -1) {
                Bukkit.getScheduler().cancelTask(taskId);
                taskId = -1;
            }
        }

        @Override
        public void run() {
            if (!isMulti) {
                if (singleFrames == null || singleFrames.isEmpty()) return;
                byte[] frame = singleFrames.get(idx % singleFrames.size());
                idx++;
                mapManager.updateMapPixelsRuntime(singleMapId, frame);
                return;
            }

            if (multiFrames == null || multiFrames.isEmpty()) return;
            if (multiMapIds == null || multiMapIds.isEmpty()) return;

            byte[][] tiles = multiFrames.get(idx % multiFrames.size());
            idx++;
            int expectedTiles = multiWidth * multiHeight;
            if (tiles == null || tiles.length != expectedTiles) return;
            if (multiMapIds.size() != expectedTiles) return;

            for (int i = 0; i < expectedTiles; i++) {
                long mapId = multiMapIds.get(i);
                byte[] pixels = tiles[i];
                mapManager.updateMapPixelsRuntime(mapId, pixels);
            }
        }

        private static int periodForFps(int fps) {
            int safe = Math.max(1, Math.min(1000, fps));
            // 20 server ticks per second. periodTicks=1 => 20fps.
            return Math.max(1, Math.round(20f / safe));
        }
    }
}

