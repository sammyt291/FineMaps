package com.example.mapdb.plugin;

import com.example.mapdb.api.MapDBAPI;
import com.example.mapdb.api.MapDBAPIProvider;
import com.example.mapdb.core.config.MapDBConfig;
import com.example.mapdb.core.database.DatabaseProvider;
import com.example.mapdb.core.database.MySQLDatabaseProvider;
import com.example.mapdb.core.database.SQLiteDatabaseProvider;
import com.example.mapdb.core.manager.MapManager;
import com.example.mapdb.core.manager.MultiBlockMapHandler;
import com.example.mapdb.core.nms.NMSAdapter;
import com.example.mapdb.core.nms.NMSAdapterFactory;
import com.example.mapdb.plugin.command.MapDBCommand;
import com.example.mapdb.plugin.listener.ChunkListener;
import com.example.mapdb.plugin.listener.ItemFrameListener;
import com.example.mapdb.plugin.listener.MapInteractListener;
import com.example.mapdb.plugin.listener.PlayerListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import redempt.redlib.config.ConfigManager;

import java.io.File;
import java.util.logging.Level;

/**
 * Main plugin class for MapDB.
 * Provides database-backed map storage with unlimited map IDs.
 */
public class MapDBPlugin extends JavaPlugin {

    private static MapDBPlugin instance;
    
    private MapDBConfig config;
    private DatabaseProvider database;
    private NMSAdapter nmsAdapter;
    private MapManager mapManager;
    private MultiBlockMapHandler multiBlockHandler;
    private ConfigManager configManager;

    @Override
    public void onEnable() {
        instance = this;
        
        // Check dependencies
        if (!checkDependencies()) {
            getLogger().severe("Missing required dependencies! Disabling plugin.");
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
        
        // Register API
        MapDBAPIProvider.register(mapManager);
        
        // Register listeners
        registerListeners();
        
        // Register commands
        registerCommands();
        
        // Start cleanup task
        startCleanupTask();
        
        getLogger().info("MapDB enabled successfully!");
        getLogger().info("Database type: " + (config.getDatabase().isMySQL() ? "MySQL" : "SQLite"));
        getLogger().info("Max virtual IDs: " + config.getMaps().getMaxVirtualIds());
    }

    @Override
    public void onDisable() {
        // Unregister API
        MapDBAPIProvider.unregister();
        
        // Shutdown managers
        if (mapManager != null) {
            mapManager.shutdown();
        }
        
        // Save multi-block data
        if (multiBlockHandler != null) {
            multiBlockHandler.save();
        }
        
        // Close database
        if (database != null) {
            database.shutdown();
        }
        
        instance = null;
        getLogger().info("MapDB disabled.");
    }

    private boolean checkDependencies() {
        // Check for ProtocolLib
        if (getServer().getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().severe("ProtocolLib is required but not found!");
            return false;
        }
        
        // Check for RedLib (optional but recommended)
        if (getServer().getPluginManager().getPlugin("RedLib") == null) {
            getLogger().warning("RedLib not found - using built-in config management");
        }
        
        return true;
    }

    private void loadConfiguration() {
        // Save default config
        saveDefaultConfig();
        
        // Try to use RedLib config manager
        try {
            config = new MapDBConfig();
            configManager = ConfigManager.create(this)
                .target(config)
                .saveDefaults()
                .load();
            getLogger().info("Configuration loaded with RedLib");
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to load config with RedLib, using defaults", e);
            config = new MapDBConfig();
        }
    }

    private boolean initializeDatabase() {
        try {
            if (config.getDatabase().isMySQL()) {
                MapDBConfig.MySQLConfig mysql = config.getDatabase().getMysql();
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
        MapDBCommand command = new MapDBCommand(this);
        getCommand("mapdb").setExecutor(command);
        getCommand("mapdb").setTabCompleter(command);
    }

    private void startCleanupTask() {
        int interval = config.getMaps().getCleanupInterval();
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (mapManager != null) {
                mapManager.cleanup();
            }
        }, interval, interval);
    }

    /**
     * Gets the plugin instance.
     *
     * @return The plugin instance
     */
    public static MapDBPlugin getInstance() {
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
    public MapDBConfig getMapDBConfig() {
        return config;
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
}
