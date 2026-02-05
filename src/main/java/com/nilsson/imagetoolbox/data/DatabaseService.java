package com.nilsson.imagetoolbox.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 Core infrastructure service handling database persistence, connection pooling, and schema migration.

 <p><strong>Architecture & Portability:</strong>
 This service implements a <em>portable data model</em>. Instead of relying on OS-specific user directories
 (e.g., AppData or ~/.local), it stores the SQLite database in a {@code /data} directory relative to the
 application's working directory. This ensures the entire application state is self-contained and can be
 moved via a simple folder copy or zip extraction.

 <p><strong>Concurrency & Performance:</strong>
 It utilizes {@link HikariDataSource} for high-performance connection pooling.
 SQLite is configured in <strong>WAL (Write-Ahead Logging)</strong> mode to allow simultaneous readers
 and writers, significantly improving UI responsiveness during background scans.

 <p><strong>Schema Management:</strong>
 The service includes a self-healing migration system. On startup, it checks the
 {@code PRAGMA user_version} and applies transactional schema updates if the database is outdated.
 */
public class DatabaseService {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseService.class);

    private static final String DATA_DIR_NAME = "data";
    private static final String DB_FILE_NAME = "library.db";
    private static final int CURRENT_DB_VERSION = 1;

    private final HikariDataSource dataSource;

    /**
     Initializes the database service using the default portable path.
     <p>
     This constructor automatically resolves the {@code ./data} directory relative to the
     application executable and ensures it exists.
     */
    public DatabaseService() {
        this(resolvePortableDbUrl());
    }

    /**
     Initializes the database service with a specific JDBC URL.
     <p>
     Useful for integration testing or non-standard configurations.

     @param jdbcUrl The full JDBC URL (e.g., "jdbc:sqlite:test.db").
     */
    public DatabaseService(String jdbcUrl) {
        logger.info("Initializing DatabaseService with URL: {}", jdbcUrl);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setPoolName("ImageToolboxPool");

        // Optimizations for desktop application usage
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("foreign_keys", "ON");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("busy_timeout", "5000");

        this.dataSource = new HikariDataSource(config);
        performMigrations();
    }

    /**
     Resolves the absolute path for the database file to ensure portability.

     @return The formatted JDBC URL string.

     @throws RuntimeException If the data directory cannot be created.
     */
    private static String resolvePortableDbUrl() {
        try {
            Path appDir = Paths.get(System.getProperty("user.dir"));
            Path dataDir = appDir.resolve(DATA_DIR_NAME);

            if (!Files.exists(dataDir)) {
                logger.info("Portable data directory missing. Creating: {}", dataDir.toAbsolutePath());
                Files.createDirectories(dataDir);
            }

            Path dbPath = dataDir.resolve(DB_FILE_NAME);
            return "jdbc:sqlite:" + dbPath.toAbsolutePath();

        } catch (IOException e) {
            logger.error("Failed to initialize portable data directory.", e);
            throw new RuntimeException("Fatal Error: Could not create portable data directory.", e);
        }
    }

    /**
     Closes the connection pool and releases resources.
     Should be called during application shutdown.
     */
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            logger.info("Shutting down connection pool...");
            dataSource.close();
        }
    }

    /**
     Obtains a connection from the pool.

     @return A valid {@link Connection} object.

     @throws SQLException If a database access error occurs.
     */
    public Connection connect() throws SQLException {
        return dataSource.getConnection();
    }

    private void performMigrations() {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);

            int currentVersion = getDatabaseVersion(conn);
            logger.debug("Detected Database Version: {}", currentVersion);

            if (currentVersion < CURRENT_DB_VERSION) {
                logger.info("Migrating database from v{} to v{}...", currentVersion, CURRENT_DB_VERSION);
                try {
                    if (currentVersion == 0) {
                        applyInitialSchema(conn);
                    }
                    // Future migrations: else if (currentVersion == 1) { applyV2(conn); }

                    setDatabaseVersion(conn, CURRENT_DB_VERSION);
                    conn.commit();
                    logger.info("Database migration completed successfully.");
                } catch (SQLException e) {
                    conn.rollback();
                    logger.error("Migration failed. Transaction rolled back.", e);
                    throw e;
                }
            }
        } catch (SQLException e) {
            logger.error("Critical error during database migration.", e);
            throw new RuntimeException("Database migration failed during startup.", e);
        }
    }

    private void applyInitialSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Core Entities
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
                        CREATE TABLE IF NOT EXISTS collections (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            name TEXT UNIQUE NOT NULL,
                            created_at INTEGER
                        )
                    """);

            // Relations
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

            // Virtual Tables & Configuration
            stmt.execute("CREATE VIRTUAL TABLE IF NOT EXISTS metadata_fts USING fts5(image_id UNINDEXED, global_text)");
            stmt.execute("CREATE TABLE IF NOT EXISTS pinned_folders (path TEXT UNIQUE NOT NULL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)");

            // Indices
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
}