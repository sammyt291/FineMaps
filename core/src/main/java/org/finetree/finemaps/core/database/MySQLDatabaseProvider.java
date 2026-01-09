package org.finetree.finemaps.core.database;

import com.zaxxer.hikari.HikariConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * MySQL implementation of the database provider.
 */
public class MySQLDatabaseProvider extends AbstractDatabaseProvider {

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final boolean useSSL;

    public MySQLDatabaseProvider(Logger logger, String host, int port, String database,
                                  String username, String password, boolean useSSL) {
        super(logger);
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.useSSL = useSSL;
    }

    @Override
    protected HikariConfig createHikariConfig() {
        HikariConfig config = new HikariConfig();
        
        String jdbcUrl = String.format("jdbc:mysql://%s:%d/%s?useSSL=%s&autoReconnect=true&useUnicode=true&characterEncoding=UTF-8",
            host, port, database, useSSL);
        
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        
        // MySQL connection pool settings
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setIdleTimeout(300000); // 5 minutes
        config.setMaxLifetime(600000); // 10 minutes
        config.setConnectionTimeout(30000); // 30 seconds
        config.setConnectionTestQuery("SELECT 1");
        
        // MySQL performance settings
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        
        return config;
    }

    @Override
    protected String getCreateMapsTableSQL() {
        return "CREATE TABLE IF NOT EXISTS finemaps_maps (" +
            "  id BIGINT PRIMARY KEY," +
            "  plugin_id VARCHAR(64) NOT NULL," +
            "  creator_uuid VARCHAR(36)," +
            "  group_id BIGINT DEFAULT 0," +
            "  grid_x INT DEFAULT 0," +
            "  grid_y INT DEFAULT 0," +
            "  created_at BIGINT NOT NULL," +
            "  last_accessed BIGINT NOT NULL," +
            "  metadata TEXT," +
            "  INDEX idx_plugin_id (plugin_id)," +
            "  INDEX idx_creator_uuid (creator_uuid)," +
            "  INDEX idx_group_id (group_id)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    }

    @Override
    protected String getCreateMapDataTableSQL() {
        return "CREATE TABLE IF NOT EXISTS finemaps_data (" +
            "  map_id BIGINT PRIMARY KEY," +
            "  pixels MEDIUMBLOB NOT NULL," +
            "  palette BLOB," +
            "  compressed BOOLEAN DEFAULT TRUE," +
            "  FOREIGN KEY (map_id) REFERENCES finemaps_maps(id) ON DELETE CASCADE" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    }

    @Override
    protected String getCreateGroupsTableSQL() {
        return "CREATE TABLE IF NOT EXISTS finemaps_groups (" +
            "  id BIGINT PRIMARY KEY," +
            "  plugin_id VARCHAR(64) NOT NULL," +
            "  creator_uuid VARCHAR(36)," +
            "  width INT NOT NULL," +
            "  height INT NOT NULL," +
            "  created_at BIGINT NOT NULL," +
            "  metadata TEXT," +
            "  INDEX idx_plugin_id (plugin_id)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    }

    @Override
    protected String getCreateIdTableSQL() {
        return "CREATE TABLE IF NOT EXISTS finemaps_ids (" +
            "  id_type VARCHAR(32) PRIMARY KEY," +
            "  current_value BIGINT NOT NULL" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    }

    @Override
    protected void initializeIdCounters(Connection conn) throws SQLException {
        String sql = "INSERT IGNORE INTO finemaps_ids (id_type, current_value) VALUES (?, ?)";
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
