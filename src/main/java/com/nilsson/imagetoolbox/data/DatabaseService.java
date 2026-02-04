package com.nilsson.imagetoolbox.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 Core service responsible for database connectivity, connection pooling,
 and schema migration management for the Image Toolbox application.
 * <p>This service utilizes HikariCP for high-performance connection pooling
 and handles the lifecycle of the SQLite database, including:
 <ul>
 <li>Automatic creation of data directories and database files.</li>
 <li>Version-based schema migrations using {@code PRAGMA user_version}.</li>
 <li>Global database configuration (WAL mode, Foreign Keys, Synchronous settings).</li>
 <li>Providing thread-safe connections to repository layers.</li>
 </ul>
 * <p>The schema covers image records, metadata, tags, FTS (Full-Text Search),
 collections, and application settings.</p>
 */
public class DatabaseService {

    private static final String DEFAULT_DB_URL = "jdbc:sqlite:data/library.db";
    private static final int CURRENT_DB_VERSION = 1;
    private final HikariDataSource dataSource;

    // --- Constructors ---

    public DatabaseService(String jdbcUrl) {
        if (DEFAULT_DB_URL.equals(jdbcUrl)) {
            File dataDir = new File("data");
            if (!dataDir.exists()) dataDir.mkdirs();
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setPoolName("ImageToolboxPool");
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("foreign_keys", "ON");
        config.addDataSourceProperty("synchronous", "NORMAL");

        this.dataSource = new HikariDataSource(config);
        performMigrations();
    }

    public DatabaseService() {
        File dataDir = new File("data");
        if (!dataDir.exists()) dataDir.mkdirs();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(DEFAULT_DB_URL);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setPoolName("ImageToolboxPool");
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("foreign_keys", "ON");
        config.addDataSourceProperty("synchronous", "NORMAL");

        this.dataSource = new HikariDataSource(config);
        performMigrations();
    }

    // --- Lifecycle and Connection Management ---

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public Connection connect() throws SQLException {
        return dataSource.getConnection();
    }

    // --- Schema and Migration Logic ---

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

    private void applyInitialSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
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

            stmt.execute("CREATE VIRTUAL TABLE IF NOT EXISTS metadata_fts USING fts5(image_id UNINDEXED, global_text)");

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

            stmt.execute("CREATE TABLE IF NOT EXISTS pinned_folders (path TEXT UNIQUE NOT NULL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)");

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

    // --- Data Access Helpers ---

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