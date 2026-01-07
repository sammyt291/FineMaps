package com.example.mapdb.core.database;

import com.example.mapdb.api.map.MapData;
import com.example.mapdb.api.map.MultiBlockMap;
import com.example.mapdb.api.map.StoredMap;
import com.example.mapdb.core.compression.RLECompression;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Abstract base implementation for database providers.
 * Provides common functionality for SQLite and MySQL.
 */
public abstract class AbstractDatabaseProvider implements DatabaseProvider {

    protected final Logger logger;
    protected final ExecutorService executor;
    protected HikariDataSource dataSource;

    // SQL statements - may be overridden by implementations
    protected static final String CREATE_MAPS_TABLE = 
        "CREATE TABLE IF NOT EXISTS mapdb_maps (" +
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
        ")";

    protected static final String CREATE_MAP_DATA_TABLE =
        "CREATE TABLE IF NOT EXISTS mapdb_data (" +
        "  map_id BIGINT PRIMARY KEY," +
        "  pixels BLOB NOT NULL," +
        "  palette BLOB," +
        "  compressed BOOLEAN DEFAULT TRUE," +
        "  FOREIGN KEY (map_id) REFERENCES mapdb_maps(id) ON DELETE CASCADE" +
        ")";

    protected static final String CREATE_GROUPS_TABLE =
        "CREATE TABLE IF NOT EXISTS mapdb_groups (" +
        "  id BIGINT PRIMARY KEY," +
        "  plugin_id VARCHAR(64) NOT NULL," +
        "  creator_uuid VARCHAR(36)," +
        "  width INT NOT NULL," +
        "  height INT NOT NULL," +
        "  created_at BIGINT NOT NULL," +
        "  metadata TEXT," +
        "  INDEX idx_plugin_id (plugin_id)" +
        ")";

    protected static final String CREATE_ID_TABLE =
        "CREATE TABLE IF NOT EXISTS mapdb_ids (" +
        "  id_type VARCHAR(32) PRIMARY KEY," +
        "  current_value BIGINT NOT NULL" +
        ")";

    public AbstractDatabaseProvider(Logger logger) {
        this.logger = logger;
        this.executor = Executors.newFixedThreadPool(4);
    }

    protected abstract HikariConfig createHikariConfig();
    protected abstract String getCreateMapsTableSQL();
    protected abstract String getCreateMapDataTableSQL();
    protected abstract String getCreateGroupsTableSQL();
    protected abstract String getCreateIdTableSQL();

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                HikariConfig config = createHikariConfig();
                dataSource = new HikariDataSource(config);

                try (Connection conn = dataSource.getConnection()) {
                    // Create tables
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(getCreateMapsTableSQL());
                        stmt.execute(getCreateMapDataTableSQL());
                        stmt.execute(getCreateGroupsTableSQL());
                        stmt.execute(getCreateIdTableSQL());
                    }

                    // Initialize ID counters if not present
                    initializeIdCounters(conn);
                }

                logger.info("Database initialized successfully");
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to initialize database", e);
                throw new RuntimeException("Database initialization failed", e);
            }
        }, executor);
    }

    protected void initializeIdCounters(Connection conn) throws SQLException {
        String sql = "INSERT IGNORE INTO mapdb_ids (id_type, current_value) VALUES (?, ?)";
        // SQLite uses different syntax, so implementations may override this
        try (PreparedStatement stmt = conn.prepareStatement(
                sql.replace("IGNORE", getInsertIgnoreKeyword()))) {
            stmt.setString(1, "map");
            stmt.setLong(2, 0);
            stmt.executeUpdate();

            stmt.setString(1, "group");
            stmt.setLong(2, 0);
            stmt.executeUpdate();
        }
    }

    protected String getInsertIgnoreKeyword() {
        return "IGNORE"; // MySQL default, SQLite overrides
    }

    protected long getNextId(Connection conn, String idType) throws SQLException {
        // Update and get in one operation to avoid race conditions
        String updateSql = "UPDATE mapdb_ids SET current_value = current_value + 1 WHERE id_type = ?";
        String selectSql = "SELECT current_value FROM mapdb_ids WHERE id_type = ?";

        try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
            updateStmt.setString(1, idType);
            updateStmt.executeUpdate();
        }

        try (PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
            selectStmt.setString(1, idType);
            try (ResultSet rs = selectStmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }

        throw new SQLException("Failed to generate ID for type: " + idType);
    }

    @Override
    public void shutdown() {
        executor.shutdown();
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    @Override
    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }

    @Override
    public CompletableFuture<StoredMap> createMap(String pluginId, UUID creatorUUID, byte[] pixels,
                                                   byte[] palette, long groupId, int gridX, int gridY,
                                                   String metadata) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    long mapId = getNextId(conn, "map");
                    long now = System.currentTimeMillis();

                    // Insert map record
                    String insertMapSql = "INSERT INTO mapdb_maps " +
                        "(id, plugin_id, creator_uuid, group_id, grid_x, grid_y, created_at, last_accessed, metadata) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
                    
                    try (PreparedStatement stmt = conn.prepareStatement(insertMapSql)) {
                        stmt.setLong(1, mapId);
                        stmt.setString(2, pluginId);
                        stmt.setString(3, creatorUUID != null ? creatorUUID.toString() : null);
                        stmt.setLong(4, groupId);
                        stmt.setInt(5, gridX);
                        stmt.setInt(6, gridY);
                        stmt.setLong(7, now);
                        stmt.setLong(8, now);
                        stmt.setString(9, metadata);
                        stmt.executeUpdate();
                    }

                    // Compress and insert pixel data
                    byte[] compressedPixels = RLECompression.compress(pixels);
                    byte[] compressedPalette = palette != null ? RLECompression.compress(palette) : null;

                    String insertDataSql = "INSERT INTO mapdb_data (map_id, pixels, palette, compressed) VALUES (?, ?, ?, ?)";
                    try (PreparedStatement stmt = conn.prepareStatement(insertDataSql)) {
                        stmt.setLong(1, mapId);
                        stmt.setBytes(2, compressedPixels);
                        stmt.setBytes(3, compressedPalette);
                        stmt.setBoolean(4, true);
                        stmt.executeUpdate();
                    }

                    conn.commit();

                    return new StoredMap(mapId, pluginId, creatorUUID, groupId, 
                                        gridX, gridY, now, now, metadata);
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to create map", e);
                throw new RuntimeException("Failed to create map", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<StoredMap>> getMap(long mapId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM mapdb_maps WHERE id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, mapId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapFromResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to get map: " + mapId, e);
            }
            return Optional.empty();
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<MapData>> getMapData(long mapId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM mapdb_data WHERE map_id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, mapId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        byte[] pixels = rs.getBytes("pixels");
                        byte[] palette = rs.getBytes("palette");
                        boolean compressed = rs.getBoolean("compressed");

                        if (compressed) {
                            pixels = RLECompression.decompress(pixels, MapData.TOTAL_PIXELS);
                            if (palette != null) {
                                palette = RLECompression.decompress(palette, palette.length * 4);
                            }
                        }

                        return Optional.of(new MapData(mapId, pixels, palette));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to get map data: " + mapId, e);
            }
            return Optional.empty();
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> updateMapPixels(long mapId, byte[] pixels) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "UPDATE mapdb_data SET pixels = ?, compressed = ? WHERE map_id = ?";
            byte[] compressed = RLECompression.compress(pixels);
            
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setBytes(1, compressed);
                stmt.setBoolean(2, true);
                stmt.setLong(3, mapId);
                return stmt.executeUpdate() > 0;
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to update map pixels: " + mapId, e);
                return false;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> updateMapMetadata(long mapId, String metadata) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "UPDATE mapdb_maps SET metadata = ? WHERE id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, metadata);
                stmt.setLong(2, mapId);
                return stmt.executeUpdate() > 0;
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to update map metadata: " + mapId, e);
                return false;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> deleteMap(long mapId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    // Delete data first (if no cascade)
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "DELETE FROM mapdb_data WHERE map_id = ?")) {
                        stmt.setLong(1, mapId);
                        stmt.executeUpdate();
                    }

                    // Delete map record
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "DELETE FROM mapdb_maps WHERE id = ?")) {
                        stmt.setLong(1, mapId);
                        int result = stmt.executeUpdate();
                        conn.commit();
                        return result > 0;
                    }
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to delete map: " + mapId, e);
                return false;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<StoredMap>> getMapsByPlugin(String pluginId) {
        return CompletableFuture.supplyAsync(() -> {
            List<StoredMap> maps = new ArrayList<>();
            String sql = "SELECT * FROM mapdb_maps WHERE plugin_id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, pluginId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        maps.add(mapFromResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to get maps by plugin: " + pluginId, e);
            }
            return maps;
        }, executor);
    }

    @Override
    public CompletableFuture<List<StoredMap>> getMapsByCreator(UUID creatorUUID) {
        return CompletableFuture.supplyAsync(() -> {
            List<StoredMap> maps = new ArrayList<>();
            String sql = "SELECT * FROM mapdb_maps WHERE creator_uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, creatorUUID.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        maps.add(mapFromResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to get maps by creator: " + creatorUUID, e);
            }
            return maps;
        }, executor);
    }

    @Override
    public CompletableFuture<Integer> getMapCount(String pluginId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) FROM mapdb_maps WHERE plugin_id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, pluginId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to get map count: " + pluginId, e);
            }
            return 0;
        }, executor);
    }

    @Override
    public CompletableFuture<Integer> getMapCountByCreator(UUID creatorUUID) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) FROM mapdb_maps WHERE creator_uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, creatorUUID.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to get map count: " + creatorUUID, e);
            }
            return 0;
        }, executor);
    }

    @Override
    public CompletableFuture<Long> createMultiBlockGroup(String pluginId, UUID creatorUUID,
                                                          int width, int height, String metadata) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                long groupId = getNextId(conn, "group");
                long now = System.currentTimeMillis();

                String sql = "INSERT INTO mapdb_groups " +
                    "(id, plugin_id, creator_uuid, width, height, created_at, metadata) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)";

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setLong(1, groupId);
                    stmt.setString(2, pluginId);
                    stmt.setString(3, creatorUUID != null ? creatorUUID.toString() : null);
                    stmt.setInt(4, width);
                    stmt.setInt(5, height);
                    stmt.setLong(6, now);
                    stmt.setString(7, metadata);
                    stmt.executeUpdate();
                }

                return groupId;
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to create multi-block group", e);
                throw new RuntimeException("Failed to create group", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<MultiBlockMap>> getMultiBlockMap(long groupId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                // Get group info
                String groupSql = "SELECT * FROM mapdb_groups WHERE id = ?";
                String pluginId = null;
                UUID creatorUUID = null;
                int width = 0, height = 0;
                long createdAt = 0;
                String metadata = null;

                try (PreparedStatement stmt = conn.prepareStatement(groupSql)) {
                    stmt.setLong(1, groupId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            pluginId = rs.getString("plugin_id");
                            String uuidStr = rs.getString("creator_uuid");
                            creatorUUID = uuidStr != null ? UUID.fromString(uuidStr) : null;
                            width = rs.getInt("width");
                            height = rs.getInt("height");
                            createdAt = rs.getLong("created_at");
                            metadata = rs.getString("metadata");
                        } else {
                            return Optional.empty();
                        }
                    }
                }

                // Get all maps in group
                List<StoredMap> maps = new ArrayList<>();
                String mapsSql = "SELECT * FROM mapdb_maps WHERE group_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(mapsSql)) {
                    stmt.setLong(1, groupId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            maps.add(mapFromResultSet(rs));
                        }
                    }
                }

                return Optional.of(new MultiBlockMap(groupId, pluginId, creatorUUID,
                                                     width, height, maps, createdAt, metadata));
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to get multi-block map: " + groupId, e);
                return Optional.empty();
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<StoredMap>> getMapsByGroup(long groupId) {
        return CompletableFuture.supplyAsync(() -> {
            List<StoredMap> maps = new ArrayList<>();
            String sql = "SELECT * FROM mapdb_maps WHERE group_id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, groupId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        maps.add(mapFromResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to get maps by group: " + groupId, e);
            }
            return maps;
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> deleteMultiBlockGroup(long groupId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    // Get all map IDs in group
                    List<Long> mapIds = new ArrayList<>();
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "SELECT id FROM mapdb_maps WHERE group_id = ?")) {
                        stmt.setLong(1, groupId);
                        try (ResultSet rs = stmt.executeQuery()) {
                            while (rs.next()) {
                                mapIds.add(rs.getLong(1));
                            }
                        }
                    }

                    // Delete map data
                    for (long mapId : mapIds) {
                        try (PreparedStatement stmt = conn.prepareStatement(
                                "DELETE FROM mapdb_data WHERE map_id = ?")) {
                            stmt.setLong(1, mapId);
                            stmt.executeUpdate();
                        }
                    }

                    // Delete maps
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "DELETE FROM mapdb_maps WHERE group_id = ?")) {
                        stmt.setLong(1, groupId);
                        stmt.executeUpdate();
                    }

                    // Delete group
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "DELETE FROM mapdb_groups WHERE id = ?")) {
                        stmt.setLong(1, groupId);
                        int result = stmt.executeUpdate();
                        conn.commit();
                        return result > 0;
                    }
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to delete group: " + groupId, e);
                return false;
            }
        }, executor);
    }

    @Override
    public void updateLastAccessed(long mapId) {
        executor.submit(() -> {
            String sql = "UPDATE mapdb_maps SET last_accessed = ? WHERE id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, System.currentTimeMillis());
                stmt.setLong(2, mapId);
                stmt.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to update last accessed: " + mapId, e);
            }
        });
    }

    @Override
    public CompletableFuture<List<Long>> getStaleMapIds(long beforeTimestamp) {
        return CompletableFuture.supplyAsync(() -> {
            List<Long> ids = new ArrayList<>();
            String sql = "SELECT id FROM mapdb_maps WHERE last_accessed < ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, beforeTimestamp);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to get stale map IDs", e);
            }
            return ids;
        }, executor);
    }

    protected StoredMap mapFromResultSet(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String pluginId = rs.getString("plugin_id");
        String uuidStr = rs.getString("creator_uuid");
        UUID creatorUUID = uuidStr != null ? UUID.fromString(uuidStr) : null;
        long groupId = rs.getLong("group_id");
        int gridX = rs.getInt("grid_x");
        int gridY = rs.getInt("grid_y");
        long createdAt = rs.getLong("created_at");
        long lastAccessed = rs.getLong("last_accessed");
        String metadata = rs.getString("metadata");

        return new StoredMap(id, pluginId, creatorUUID, groupId, gridX, gridY, 
                            createdAt, lastAccessed, metadata);
    }
}
