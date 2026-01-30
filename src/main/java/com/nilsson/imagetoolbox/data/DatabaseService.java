package com.nilsson.imagetoolbox.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.*;

/**
 Service class responsible for managing the SQLite database lifecycle and connectivity.
 It utilizes the HikariCP connection pool to optimize performance for the Image Toolbox
 application and handles automatic schema migrations using SQLite's user_version.
 */
public class DatabaseService {

    // --- Configuration ---
    private static final String DB_URL = "jdbc:sqlite:data/library.db";
    private static final int CURRENT_DB_VERSION = 3;
    private final HikariDataSource dataSource;

    // --- Lifecycle ---
    public DatabaseService() {
        File dataDir = new File("data");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }

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

    // --- Migration Engine ---
    private void performMigrations() {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            int currentVersion = getDatabaseVersion(conn);

            if (currentVersion < CURRENT_DB_VERSION) {
                System.out.println("Migrating database from version " + currentVersion + " to " + CURRENT_DB_VERSION);
                try {
                    if (currentVersion < 1) applySchemaV1(conn);
                    if (currentVersion < 2) applySchemaV2(conn);
                    if (currentVersion < 3) applySchemaV3(conn);

                    setDatabaseVersion(conn, CURRENT_DB_VERSION);
                    conn.commit();
                    System.out.println("Database migration completed successfully.");
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            }
        } catch (SQLException e) {
            System.err.println("CRITICAL: Failed to migrate database: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Database migration failed.", e);
        }
    }

    private int getDatabaseVersion(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version;")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    private void setDatabaseVersion(Connection conn, int version) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA user_version = " + version);
        }
    }

    // --- Schema Definitions ---
    private void applySchemaV1(Connection conn) throws SQLException {
        String createImagesTable = """
                    CREATE TABLE IF NOT EXISTS images (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        file_path TEXT UNIQUE NOT NULL,
                        is_starred BOOLEAN DEFAULT 0,
                        last_scanned INTEGER
                    );
                """;

        String createTagsTable = """
                    CREATE TABLE IF NOT EXISTS image_tags (
                        image_id INTEGER,
                        tag TEXT,
                        FOREIGN KEY(image_id) REFERENCES images(id)
                    );
                """;

        String createMetadataTable = """
                    CREATE TABLE IF NOT EXISTS image_metadata (
                        image_id INTEGER,
                        key TEXT,
                        value TEXT,
                        FOREIGN KEY(image_id) REFERENCES images(id)
                    );
                """;

        String createPinnedFoldersTable = """
                    CREATE TABLE IF NOT EXISTS pinned_folders (
                        path TEXT UNIQUE NOT NULL
                    );
                """;

        String createSettingsTable = """
                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY, 
                    value TEXT
                    );
                """;

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createImagesTable);
            stmt.execute(createTagsTable);
            stmt.execute(createMetadataTable);
            stmt.execute(createPinnedFoldersTable);
            stmt.execute(createSettingsTable);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_path ON images(file_path);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tags_text ON image_tags(tag);");
        }
    }

    private void applySchemaV2(Connection conn) throws SQLException {
        System.out.println("Applying Schema V2: FTS5 and File Hashing...");
        try (Statement stmt = conn.createStatement()) {

            boolean hasHashCol = false;
            try (ResultSet rs = conn.getMetaData().getColumns(null, null, "images", "file_hash")) {
                if (rs.next()) hasHashCol = true;
            }
            if (!hasHashCol) {
                stmt.execute("ALTER TABLE images ADD COLUMN file_hash TEXT;");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_hash ON images(file_hash);");
            }

            stmt.execute("CREATE VIRTUAL TABLE IF NOT EXISTS metadata_fts USING fts5(image_id UNINDEXED, global_text);");

            boolean hasMetadataTable = false;
            try (ResultSet rs = conn.getMetaData().getTables(null, null, "image_metadata", null)) {
                if (rs.next()) hasMetadataTable = true;
            }

            if (hasMetadataTable) {
                System.out.println("Re-indexing existing metadata for FTS...");
                String populateFts = """
                            INSERT INTO metadata_fts(image_id, global_text)
                            SELECT image_id, group_concat(key || ': ' || value, ' ') 
                            FROM image_metadata 
                            GROUP BY image_id;
                        """;
                stmt.execute(populateFts);
            } else {
                System.out.println("Warning: image_metadata table missing during V2 migration. Skipping data copy.");
            }
        }
    }

    private void applySchemaV3(Connection conn) throws SQLException {
        System.out.println("Applying Schema V3: Virtual Collections...");
        try (Statement stmt = conn.createStatement()) {
            String createCollections = """
                        CREATE TABLE IF NOT EXISTS collections (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            name TEXT UNIQUE NOT NULL,
                            created_at INTEGER
                        );
                    """;

            String createCollectionImages = """
                        CREATE TABLE IF NOT EXISTS collection_images (
                            collection_id INTEGER,
                            image_id INTEGER,
                            added_at INTEGER,
                            PRIMARY KEY (collection_id, image_id),
                            FOREIGN KEY(collection_id) REFERENCES collections(id) ON DELETE CASCADE,
                            FOREIGN KEY(image_id) REFERENCES images(id) ON DELETE CASCADE
                        );
                    """;

            stmt.execute(createCollections);
            stmt.execute(createCollectionImages);
        }
    }

    // --- Connection Management ---
    public Connection connect() throws SQLException {
        return dataSource.getConnection();
    }

    // --- Data Access Operations ---
    public int getOrCreateImageId(String absolutePath) throws SQLException {
        String selectSql = "SELECT id FROM images WHERE file_path = ?";
        String insertSql = "INSERT INTO images(file_path, last_scanned) VALUES(?, ?)";

        try (Connection conn = connect()) {
            try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
                pstmt.setString(1, absolutePath);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) return rs.getInt("id");
            }

            try (PreparedStatement pstmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setString(1, absolutePath);
                pstmt.setLong(2, System.currentTimeMillis());
                pstmt.executeUpdate();
                ResultSet rs = pstmt.getGeneratedKeys();
                if (rs.next()) return rs.getInt(1);
            }
        }
        throw new SQLException("Could not retrieve or create image ID for: " + absolutePath);
    }
}