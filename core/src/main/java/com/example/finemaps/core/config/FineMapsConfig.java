package com.example.finemaps.core.config;

import redempt.redlib.config.annotations.Comment;
import redempt.redlib.config.annotations.ConfigMappable;
import redempt.redlib.config.annotations.ConfigName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main configuration class for FineMaps.
 * Uses RedLib's config annotation system.
 */
@ConfigMappable
public class FineMapsConfig {

    @Comment("Database configuration")
    @ConfigName("database")
    private DatabaseConfig database = new DatabaseConfig();

    @Comment("Permission settings")
    @ConfigName("permissions")
    private PermissionsConfig permissions = new PermissionsConfig();

    @Comment("Map settings")
    @ConfigName("maps")
    private MapSettings maps = new MapSettings();

    @Comment("Image processing settings")
    @ConfigName("images")
    private ImageConfig images = new ImageConfig();

    @Comment("Economy settings (Vault)")
    @ConfigName("economy")
    private EconomyConfig economy = new EconomyConfig();

    @Comment("GUI settings")
    @ConfigName("gui")
    private GuiConfig gui = new GuiConfig();

    public DatabaseConfig getDatabase() {
        return database;
    }

    public PermissionsConfig getPermissions() {
        return permissions;
    }

    public MapSettings getMaps() {
        return maps;
    }

    public ImageConfig getImages() {
        return images;
    }

    public EconomyConfig getEconomy() {
        return economy;
    }

    public GuiConfig getGui() {
        return gui;
    }

    @ConfigMappable
    public static class DatabaseConfig {
        @Comment("Database type: 'sqlite' or 'mysql'")
        @ConfigName("type")
        private String type = "sqlite";

        @Comment("SQLite file name (relative to plugin folder)")
        @ConfigName("sqlite-file")
        private String sqliteFile = "maps.db";

        @Comment("MySQL connection settings")
        @ConfigName("mysql")
        private MySQLConfig mysql = new MySQLConfig();

        public String getType() {
            return type;
        }

        public String getSqliteFile() {
            return sqliteFile;
        }

        public MySQLConfig getMysql() {
            return mysql;
        }

        public boolean isMySQL() {
            return "mysql".equalsIgnoreCase(type);
        }

        public boolean isSQLite() {
            return "sqlite".equalsIgnoreCase(type);
        }
    }

    @ConfigMappable
    public static class MySQLConfig {
        @ConfigName("host")
        private String host = "localhost";

        @ConfigName("port")
        private int port = 3306;

        @ConfigName("database")
        private String database = "finemaps";

        @ConfigName("username")
        private String username = "root";

        @ConfigName("password")
        private String password = "";

        @ConfigName("use-ssl")
        private boolean useSSL = false;

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public String getDatabase() {
            return database;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        public boolean isUseSSL() {
            return useSSL;
        }
    }

    @ConfigMappable
    public static class PermissionsConfig {
        @Comment("Default map creation limit for players (-1 for unlimited)")
        @ConfigName("default-limit")
        private int defaultLimit = 100;

        @Comment("Map limits per permission group")
        @ConfigName("group-limits")
        private Map<String, Integer> groupLimits = new HashMap<>();

        @Comment("Whether to allow URL image imports")
        @ConfigName("allow-url-import")
        private boolean allowUrlImport = true;

        @Comment("Maximum image size for URL imports (in pixels)")
        @ConfigName("max-import-size")
        private int maxImportSize = 4096;

        @Comment("Allowed URL domains for image import (empty for all)")
        @ConfigName("allowed-domains")
        private List<String> allowedDomains = new ArrayList<>();

        public int getDefaultLimit() {
            return defaultLimit;
        }

        public Map<String, Integer> getGroupLimits() {
            return groupLimits;
        }

        public boolean isAllowUrlImport() {
            return allowUrlImport;
        }

        public int getMaxImportSize() {
            return maxImportSize;
        }

        public List<String> getAllowedDomains() {
            return allowedDomains;
        }

        public int getLimitForPermission(String permission) {
            return groupLimits.getOrDefault(permission, defaultLimit);
        }
    }

    @ConfigMappable
    public static class MapSettings {
        @Comment("Maximum virtual map IDs to use (0-32000)")
        @ConfigName("max-virtual-ids")
        private int maxVirtualIds = 30000;

        @Comment("How often to clean up unused virtual IDs (in ticks)")
        @ConfigName("cleanup-interval")
        private int cleanupInterval = 6000; // 5 minutes

        @Comment("Time before a map is considered stale for cleanup (in milliseconds)")
        @ConfigName("stale-time")
        private long staleTime = 86400000; // 24 hours

        @Comment("Whether to show particle outlines for map previews as fallback")
        @ConfigName("use-particles-legacy")
        private boolean useParticlesLegacy = true;

        @Comment("Whether to use block displays for map previews")
        @ConfigName("use-block-displays")
        private boolean useBlockDisplays = true;

        public int getMaxVirtualIds() {
            return maxVirtualIds;
        }

        public int getCleanupInterval() {
            return cleanupInterval;
        }

        public long getStaleTime() {
            return staleTime;
        }

        public boolean isUseParticlesLegacy() {
            return useParticlesLegacy;
        }

        public boolean isUseBlockDisplays() {
            return useBlockDisplays;
        }
    }

    @ConfigMappable
    public static class ImageConfig {
        @Comment("Whether to use dithering by default")
        @ConfigName("default-dither")
        private boolean defaultDither = true;

        @Comment("Maximum width for multi-block maps (in blocks)")
        @ConfigName("max-width")
        private int maxWidth = 10;

        @Comment("Maximum height for multi-block maps (in blocks)")
        @ConfigName("max-height")
        private int maxHeight = 10;

        @Comment("Connection timeout for URL imports (in milliseconds)")
        @ConfigName("connection-timeout")
        private int connectionTimeout = 10000;

        @Comment("Read timeout for URL imports (in milliseconds)")
        @ConfigName("read-timeout")
        private int readTimeout = 30000;

        @Comment("If true, URL imports will be cached to disk in the plugin folder")
        @ConfigName("url-cache-enabled")
        private boolean urlCacheEnabled = true;

        @Comment("Folder (under the plugin data folder) to store URL cache files")
        @ConfigName("url-cache-folder")
        private String urlCacheFolder = "url-cache";

        @Comment("Maximum allowed download size for a URL import. Supports suffix K, M, or G (no space). If no suffix is provided, K is assumed.")
        @ConfigName("max-url-download-size")
        private String maxUrlDownloadSize = "8192K";

        @Comment("Default FPS for animated imports (GIF/APNG/WEBP/MP4/WEBM) when not specified in the command")
        @ConfigName("default-animated-fps")
        private int defaultAnimatedFps = 20;

        @Comment("Path to ffmpeg executable used to decode video URL imports (MP4/WEBM). Set to 'ffmpeg' to use PATH.")
        @ConfigName("ffmpeg-path")
        private String ffmpegPath = "ffmpeg";

        @Comment("Maximum number of frames to extract from a video import (MP4/WEBM)")
        @ConfigName("max-video-frames")
        private int maxVideoFrames = 300;

        @Comment("Maximum number of frames to decode/process from an animated image import (GIF/APNG/WEBP).\n" +
            "Large animated images can consume a lot of memory/CPU; this provides a hard safety cap.")
        @ConfigName("max-animated-frames")
        private int maxAnimatedFrames = 300;

        @Comment("How many animation frames to keep cached in memory during playback.\n" +
            "Frames are stored on disk; this is only a small LRU window to avoid constant disk reads.")
        @ConfigName("animation-frame-cache-frames")
        private int animationFrameCacheFrames = 32;

        @Comment("How many worker threads to use when rasterising animated imports (GIF/APNG/WEBP/MP4/WEBM).\n" +
            "1 = single-threaded. 0 = auto (uses available processors).")
        @ConfigName("processor-threads")
        private int processorThreads = 0;

        public boolean isDefaultDither() {
            return defaultDither;
        }

        public int getMaxWidth() {
            return maxWidth;
        }

        public int getMaxHeight() {
            return maxHeight;
        }

        public int getConnectionTimeout() {
            return connectionTimeout;
        }

        public int getReadTimeout() {
            return readTimeout;
        }

        public boolean isUrlCacheEnabled() {
            return urlCacheEnabled;
        }

        public String getUrlCacheFolder() {
            return urlCacheFolder;
        }

        public String getMaxUrlDownloadSize() {
            return maxUrlDownloadSize;
        }

        public int getDefaultAnimatedFps() {
            return defaultAnimatedFps;
        }

        public String getFfmpegPath() {
            return ffmpegPath;
        }

        public int getMaxVideoFrames() {
            return maxVideoFrames;
        }

        public int getMaxAnimatedFrames() {
            return maxAnimatedFrames;
        }

        public int getAnimationFrameCacheFrames() {
            return animationFrameCacheFrames;
        }

        public int getProcessorThreads() {
            return processorThreads;
        }
    }

    @ConfigMappable
    public static class EconomyConfig {
        @Comment("Enable Vault economy integration (requires Vault + an economy plugin)")
        @ConfigName("enabled")
        private boolean enabled = false;

        @Comment("Enable /fm buy")
        @ConfigName("enable-buy-command")
        private boolean enableBuyCommand = true;

        @Comment("Base cost per 1x1 map (multi-block cost multiplies by tiles if enabled)")
        @ConfigName("cost-per-map")
        private double costPerMap = 100.0;

        @Comment("If true, multi-block map cost = cost-per-map * (width * height)")
        @ConfigName("multiply-by-tiles")
        private boolean multiplyByTiles = true;

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isEnableBuyCommand() {
            return enableBuyCommand;
        }

        public double getCostPerMap() {
            return costPerMap;
        }

        public boolean isMultiplyByTiles() {
            return multiplyByTiles;
        }
    }

    @ConfigMappable
    public static class GuiConfig {
        @Comment("Enable the paginated art browser GUI")
        @ConfigName("enabled")
        private boolean enabled = true;

        @Comment("Show cost in the GUI tooltip when buying is enabled")
        @ConfigName("show-cost-in-tooltip")
        private boolean showCostInTooltip = true;

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isShowCostInTooltip() {
            return showCostInTooltip;
        }
    }
}
