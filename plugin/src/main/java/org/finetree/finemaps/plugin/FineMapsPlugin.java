package org.finetree.finemaps.plugin;

import org.finetree.finemaps.api.FineMapsAPI;
import org.finetree.finemaps.api.FineMapsAPIProvider;
import org.finetree.finemaps.core.config.FineMapsConfig;
import org.finetree.finemaps.core.database.DatabaseProvider;
import org.finetree.finemaps.core.database.MySQLDatabaseProvider;
import org.finetree.finemaps.core.database.SQLiteDatabaseProvider;
import org.finetree.finemaps.core.manager.MapManager;
import org.finetree.finemaps.core.manager.MultiBlockMapHandler;
import org.finetree.finemaps.api.nms.NMSAdapter;
import org.finetree.finemaps.core.nms.NMSAdapterFactory;
import org.finetree.finemaps.core.util.FineMapsScheduler;
import org.finetree.finemaps.plugin.command.DebugCommand;
import org.finetree.finemaps.plugin.command.FineMapsCommand;
import org.finetree.finemaps.plugin.recovery.PendingMapRecovery;
import org.finetree.finemaps.plugin.url.AnimationRegistry;
import org.finetree.finemaps.plugin.listener.ChunkListener;
import org.finetree.finemaps.plugin.listener.ItemFrameListener;
import org.finetree.finemaps.plugin.listener.MapInteractListener;
import org.finetree.finemaps.plugin.listener.PlayerListener;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import redempt.redlib.config.ConfigManager;

import java.io.File;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

import net.milkbowl.vault.economy.Economy;

/**
 * Main plugin class for FineMaps.
 * Provides database-backed map storage with unlimited map IDs.
 */
public class FineMapsPlugin extends JavaPlugin {

    private static FineMapsPlugin instance;

    /**
     * FineMaps requires Minecraft 1.21+ (Java 21 bytecode).
     */
    private static final int MIN_SUPPORTED_MAJOR = 21;
    
    private FineMapsConfig config;
    private DatabaseProvider database;
    private NMSAdapter nmsAdapter;
    private MapManager mapManager;
    private MultiBlockMapHandler multiBlockHandler;
    private ConfigManager configManager;
    private Economy economy;
    private AnimationRegistry animationRegistry;
    private PendingMapRecovery pendingMapRecovery;
    private final Set<UUID> debugPlayers = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        instance = this;
        
        // Check dependencies
        if (!checkDependencies()) {
            getLogger().severe("Missing required dependencies! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Fail fast on unsupported (too old) server versions to avoid NoClassDefFoundError crashes.
        if (!checkSupportedServerVersion()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // Load configuration
        loadConfiguration();
        
        // Initialize database
        if (!initializeDatabase()) {
            getLogger().severe("Failed to initialize database! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // Initialize NMS adapter
        try {
            nmsAdapter = NMSAdapterFactory.createAdapter(getLogger());
            getLogger().info("NMS adapter initialized for version: " + nmsAdapter.getVersion());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize NMS adapter", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // Initialize managers
        mapManager = new MapManager(this, config, database, nmsAdapter);
        multiBlockHandler = new MultiBlockMapHandler(this, mapManager);
        String cacheFolder = (config != null && config.getImages() != null) ? config.getImages().getUrlCacheFolder() : "url-cache";
        int animCacheFrames = 32;
        try {
            if (config != null && config.getImages() != null) {
                animCacheFrames = config.getImages().getAnimationFrameCacheFrames();
            }
        } catch (Throwable ignored) {
        }
        animationRegistry = new AnimationRegistry(this, mapManager, cacheFolder, animCacheFrames);

        // Initialize pending map recovery system
        pendingMapRecovery = new PendingMapRecovery(this, mapManager);

        // Hook Vault (optional, only used if enabled in config)
        setupEconomy();
        
        // Register API
        FineMapsAPIProvider.register(mapManager);
        
        // Register listeners
        registerListeners();
        
        // Register commands
        registerCommands();
        
        // Start cleanup task
        startCleanupTask();

        // Restore persisted animations (if any)
        try {
            if (animationRegistry != null) {
                animationRegistry.loadAndStartPersisted();
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Failed to restore persisted animations", t);
        }
        
        getLogger().info("FineMaps enabled successfully!");
        getLogger().info("Database type: " + (config.getDatabase().isMySQL() ? "MySQL" : "SQLite"));
        getLogger().info("Max virtual IDs: " + config.getMaps().getMaxVirtualIds());
    }

    private boolean checkSupportedServerVersion() {
        String version = Bukkit.getBukkitVersion();
        int major = NMSAdapterFactory.getMajorVersion();
        int minor = NMSAdapterFactory.getMinorVersion();

        // If parsing fails, let the plugin try to run; adapter creation will still guard.
        if (major <= 0) return true;

        if (major < MIN_SUPPORTED_MAJOR) {
            getLogger().severe("FineMaps requires Minecraft 1." + MIN_SUPPORTED_MAJOR + "+.");
            getLogger().severe("Detected server version: " + version + " (1." + major + "." + minor + ").");
            getLogger().severe("Please update your server or use an older FineMaps version built for legacy servers.");
            return false;
        }
        return true;
    }

    @Override
    public void onDisable() {
        // Unregister API
        FineMapsAPIProvider.unregister();

        // Persist animation state, then stop animations before tearing down map systems
        if (animationRegistry != null) {
            try {
                animationRegistry.savePersistedState();
            } catch (Throwable ignored) {
            }
            animationRegistry.stopAll();
        }

        // Shutdown managers
        if (mapManager != null) {
            mapManager.shutdown();
        }
        
        // Save multi-block data
        if (multiBlockHandler != null) {
            multiBlockHandler.save();
        }

        // Save pending map recovery data
        if (pendingMapRecovery != null) {
            pendingMapRecovery.save();
        }
        
        // Close database
        if (database != null) {
            database.shutdown();
        }

        debugPlayers.clear();
        
        instance = null;
        getLogger().info("FineMaps disabled.");
    }

    private boolean checkDependencies() {
        // Check for ProtocolLib (optional but recommended)
        if (getServer().getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().warning("ProtocolLib not found - running in basic mode without virtual ID system");
            getLogger().warning("Install ProtocolLib for unlimited map support");
        } else {
            getLogger().info("ProtocolLib found - virtual ID system enabled");
        }
        
        return true;
    }

    private void loadConfiguration() {
        // Save default config
        saveDefaultConfig();
        
        // Try to use RedLib config manager
        try {
            config = new FineMapsConfig();
            configManager = ConfigManager.create(this)
                .target(config)
                .saveDefaults()
                .load();
            getLogger().info("Configuration loaded with RedLib");
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to load config with RedLib, using defaults", e);
            config = new FineMapsConfig();
        }
    }

    private void setupEconomy() {
        economy = null;
        try {
            if (config == null || config.getEconomy() == null || !config.getEconomy().isEnabled()) {
                return;
            }

            if (getServer().getPluginManager().getPlugin("Vault") == null) {
                getLogger().warning("Economy is enabled in config, but Vault is not installed. Disabling economy features.");
                return;
            }

            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp == null) {
                getLogger().warning("Economy is enabled in config, but no Vault economy provider was found. Disabling economy features.");
                return;
            }
            economy = rsp.getProvider();
            if (economy != null) {
                getLogger().info("Vault economy hooked: " + economy.getName());
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Failed to hook Vault economy", t);
            economy = null;
        }
    }

    private boolean initializeDatabase() {
        try {
            if (config.getDatabase().isMySQL()) {
                FineMapsConfig.MySQLConfig mysql = config.getDatabase().getMysql();
                database = new MySQLDatabaseProvider(
                    getLogger(),
                    mysql.getHost(),
                    mysql.getPort(),
                    mysql.getDatabase(),
                    mysql.getUsername(),
                    mysql.getPassword(),
                    mysql.isUseSSL()
                );
            } else {
                File dbFile = new File(getDataFolder(), config.getDatabase().getSqliteFile());
                database = new SQLiteDatabaseProvider(getLogger(), dbFile);
            }
            
            // Initialize database tables
            database.initialize().join();
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Database initialization failed", e);
            return false;
        }
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new MapInteractListener(this), this);
        getServer().getPluginManager().registerEvents(new ItemFrameListener(this), this);
        getServer().getPluginManager().registerEvents(new ChunkListener(this), this);
    }

    private void registerCommands() {
        DebugCommand debugCommand = new DebugCommand(this);
        FineMapsCommand command = new FineMapsCommand(this, debugCommand);
        getCommand("finemaps").setExecutor(command);
        getCommand("finemaps").setTabCompleter(command);
    }

    private void startCleanupTask() {
        int interval = config.getMaps().getCleanupInterval();
        if (interval <= 0) {
            return;
        }

        Runnable cleanup = () -> {
            if (mapManager != null) {
                mapManager.cleanup();
            }
        };

        // Folia does not support Bukkit's legacy repeating schedulers.
        // On Folia, use the async scheduler (wall-clock based) to preserve "async" cleanup semantics.
        try {
            if (FineMapsScheduler.isFolia()) {
                long ms = Math.max(1L, (long) interval) * 50L;
                Bukkit.getAsyncScheduler().runAtFixedRate(this, ignored -> cleanup.run(), ms, ms, TimeUnit.MILLISECONDS);
                return;
            }
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, cleanup, interval, interval);
        } catch (UnsupportedOperationException uoe) {
            // Defensive fallback: if Folia detection ever fails, don't crash enable.
            try {
                long ms = Math.max(1L, (long) interval) * 50L;
                Bukkit.getAsyncScheduler().runAtFixedRate(this, ignored -> cleanup.run(), ms, ms, TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "Failed to schedule cleanup task", t);
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Failed to schedule cleanup task", t);
        }
    }

    /**
     * Gets the plugin instance.
     *
     * @return The plugin instance
     */
    public static FineMapsPlugin getInstance() {
        return instance;
    }

    /**
     * Gets the map manager.
     *
     * @return The map manager
     */
    public MapManager getMapManager() {
        return mapManager;
    }

    public AnimationRegistry getAnimationRegistry() {
        return animationRegistry;
    }

    /**
     * Gets the pending map recovery manager.
     *
     * @return The pending map recovery manager
     */
    public PendingMapRecovery getPendingMapRecovery() {
        return pendingMapRecovery;
    }

    /**
     * Gets the multi-block handler.
     *
     * @return The multi-block handler
     */
    public MultiBlockMapHandler getMultiBlockHandler() {
        return multiBlockHandler;
    }

    /**
     * Gets the configuration.
     *
     * @return The config
     */
    public FineMapsConfig getFineMapsConfig() {
        return config;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public void reloadFineMapsConfiguration() {
        try {
            if (configManager != null) {
                configManager.reload();
            } else {
                loadConfiguration();
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to reload config via RedLib, using defaults", e);
            loadConfiguration();
        }
        // Re-evaluate economy hook in case config changed.
        setupEconomy();
    }

    public Economy getEconomy() {
        return economy;
    }

    public boolean isEconomyEnabled() {
        return economy != null && config != null && config.getEconomy() != null && config.getEconomy().isEnabled();
    }

    /**
     * Gets the database provider.
     *
     * @return The database
     */
    public DatabaseProvider getDatabase() {
        return database;
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
     * Toggles per-player debug mode (used by stick right-click inspection).
     */
    public boolean toggleDebug(Player player) {
        if (player == null) return false;
        UUID id = player.getUniqueId();
        if (debugPlayers.contains(id)) {
            debugPlayers.remove(id);
            return false;
        }
        debugPlayers.add(id);
        return true;
    }

    public void setDebug(Player player, boolean enabled) {
        if (player == null) return;
        if (enabled) {
            debugPlayers.add(player.getUniqueId());
        } else {
            debugPlayers.remove(player.getUniqueId());
        }
    }

    public boolean isDebug(Player player) {
        return player != null && debugPlayers.contains(player.getUniqueId());
    }
}
