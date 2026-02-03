package com.nilsson.imagetoolbox.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DatabaseService {

    private static final String DB_URL = "jdbc:sqlite:data/library.db";
    // We reset version to 1 since we squashed the history
    private static final int CURRENT_DB_VERSION = 1;
    private final HikariDataSource dataSource;

    public DatabaseService() {
        File dataDir = new File("data");
        if (!dataDir.exists()) dataDir.mkdirs();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(DB_URL);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setPoolName("ImageToolboxPool");
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("foreign_keys", "ON");
        config.addDataSourceProperty("synchronous", "NORMAL");

        this.dataSource = new HikariDataSource(config);
        performMigrations();
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public Connection connect() throws SQLException {
        return dataSource.getConnection();
    }

    private void performMigrations() {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            int currentVersion = getDatabaseVersion(conn);

            if (currentVersion < CURRENT_DB_VERSION) {
                System.out.println("Initializing Database Schema (v1)...");
                try {
                    applyInitialSchema(conn);
                    setDatabaseVersion(conn, CURRENT_DB_VERSION);
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database migration failed.", e);
        }
    }

    // The "Mega Schema" containing everything from V1-V4
    private void applyInitialSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // 1. Images (includes 'rating' and 'file_hash' from start)
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS images (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            file_path TEXT UNIQUE NOT NULL,
                            file_hash TEXT,
                            is_starred BOOLEAN DEFAULT 0,
                            rating INTEGER DEFAULT 0,
                            last_scanned INTEGER
                        )
                    """);

            // 2. Metadata & Tags
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS image_metadata (
                            image_id INTEGER,
                            key TEXT,
                            value TEXT,
                            FOREIGN KEY(image_id) REFERENCES images(id) ON DELETE CASCADE
                        )
                    """);
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS image_tags (
                            image_id INTEGER,
                            tag TEXT,
                            FOREIGN KEY(image_id) REFERENCES images(id) ON DELETE CASCADE
                        )
                    """);

            // 3. FTS Search
            stmt.execute("CREATE VIRTUAL TABLE IF NOT EXISTS metadata_fts USING fts5(image_id UNINDEXED, global_text)");

            // 4. Collections
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS collections (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            name TEXT UNIQUE NOT NULL,
                            created_at INTEGER
                        )
                    """);
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS collection_images (
                            collection_id INTEGER,
                            image_id INTEGER,
                            added_at INTEGER,
                            PRIMARY KEY (collection_id, image_id),
                            FOREIGN KEY(collection_id) REFERENCES collections(id) ON DELETE CASCADE,
                            FOREIGN KEY(image_id) REFERENCES images(id) ON DELETE CASCADE
                        )
                    """);

            // 5. Utility Tables
            stmt.execute("CREATE TABLE IF NOT EXISTS pinned_folders (path TEXT UNIQUE NOT NULL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)");

            // 6. Indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_path ON images(file_path)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_hash ON images(file_hash)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tags_text ON image_tags(tag)");
        }
    }

    private int getDatabaseVersion(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version;")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void setDatabaseVersion(Connection conn, int version) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA user_version = " + version);
        }
    }

    // Helper used by Repository
    public List<String> getDistinctAttribute(String attributeKey) {
        List<String> results = new ArrayList<>();
        String sql = "SELECT DISTINCT value FROM image_metadata WHERE key = ? ORDER BY value ASC";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, attributeKey);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                String val = rs.getString("value");
                if (val != null && !val.isBlank()) results.add(val);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return results;
    }
}