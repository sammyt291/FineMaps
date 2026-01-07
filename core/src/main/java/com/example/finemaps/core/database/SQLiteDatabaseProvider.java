package com.example.finemaps.core.database;

import com.zaxxer.hikari.HikariConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * SQLite implementation of the database provider.
 */
public class SQLiteDatabaseProvider extends AbstractDatabaseProvider {

    private final File databaseFile;

    public SQLiteDatabaseProvider(Logger logger, File databaseFile) {
        super(logger);
        this.databaseFile = databaseFile;
    }

    @Override
    protected HikariConfig createHikariConfig() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        
        // SQLite specific settings
        config.setMaximumPoolSize(1); // SQLite doesn't support multiple connections well
        config.setConnectionTestQuery("SELECT 1");
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("cache_size", "10000");
        config.addDataSourceProperty("foreign_keys", "ON");
        
        return config;
    }

    @Override
    protected String getCreateMapsTableSQL() {
        return "CREATE TABLE IF NOT EXISTS finemaps_maps (" +
            "  id INTEGER PRIMARY KEY," +
            "  plugin_id TEXT NOT NULL," +
            "  creator_uuid TEXT," +
            "  group_id INTEGER DEFAULT 0," +
            "  grid_x INTEGER DEFAULT 0," +
            "  grid_y INTEGER DEFAULT 0," +
            "  created_at INTEGER NOT NULL," +
            "  last_accessed INTEGER NOT NULL," +
            "  metadata TEXT" +
            ");" +
            "CREATE INDEX IF NOT EXISTS idx_maps_plugin_id ON finemaps_maps(plugin_id);" +
            "CREATE INDEX IF NOT EXISTS idx_maps_creator_uuid ON finemaps_maps(creator_uuid);" +
            "CREATE INDEX IF NOT EXISTS idx_maps_group_id ON finemaps_maps(group_id);";
    }

    @Override
    protected String getCreateMapDataTableSQL() {
        return "CREATE TABLE IF NOT EXISTS finemaps_data (" +
            "  map_id INTEGER PRIMARY KEY," +
            "  pixels BLOB NOT NULL," +
            "  palette BLOB," +
            "  compressed INTEGER DEFAULT 1," +
            "  FOREIGN KEY (map_id) REFERENCES finemaps_maps(id) ON DELETE CASCADE" +
            ")";
    }

    @Override
    protected String getCreateGroupsTableSQL() {
        return "CREATE TABLE IF NOT EXISTS finemaps_groups (" +
            "  id INTEGER PRIMARY KEY," +
            "  plugin_id TEXT NOT NULL," +
            "  creator_uuid TEXT," +
            "  width INTEGER NOT NULL," +
            "  height INTEGER NOT NULL," +
            "  created_at INTEGER NOT NULL," +
            "  metadata TEXT" +
            ");" +
            "CREATE INDEX IF NOT EXISTS idx_groups_plugin_id ON finemaps_groups(plugin_id);";
    }

    @Override
    protected String getCreateIdTableSQL() {
        return "CREATE TABLE IF NOT EXISTS finemaps_ids (" +
            "  id_type TEXT PRIMARY KEY," +
            "  current_value INTEGER NOT NULL" +
            ")";
    }

    @Override
    protected String getInsertIgnoreKeyword() {
        return "OR IGNORE";
    }

    @Override
    protected void initializeIdCounters(Connection conn) throws SQLException {
        String sql = "INSERT OR IGNORE INTO finemaps_ids (id_type, current_value) VALUES (?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, "map");
            stmt.setLong(2, 0);
            stmt.executeUpdate();

            stmt.setString(1, "group");
            stmt.setLong(2, 0);
            stmt.executeUpdate();
        }
    }
}
