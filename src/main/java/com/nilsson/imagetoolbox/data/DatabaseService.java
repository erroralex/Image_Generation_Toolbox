package com.nilsson.imagetoolbox.data;

import java.io.File;
import java.sql.*;
import java.util.*;

/**
 Handles all interactions with the SQLite database.
 <p>
 This service manages the connection lifecycle and schema initialization.
 It uses JDBC directly for maximum performance and minimal memory overhead.
 */
public class DatabaseService {

    private static final String DB_URL = "jdbc:sqlite:data/library.db";

    public DatabaseService() {
        File dataDir = new File("data");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        initializeDatabase();
    }

    /**
     Establishes a connection to the database.
     Use try-with-resources blocks when calling this to ensure connections are closed.
     */
    public Connection connect() throws SQLException {
        Connection conn = DriverManager.getConnection(DB_URL);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA foreign_keys=ON;");
        }
        return conn;
    }

    /**
     Creates tables if they do not exist.
     */
    private void initializeDatabase() {
        String createImagesTable = """
                    CREATE TABLE IF NOT EXISTS images (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        file_path TEXT UNIQUE NOT NULL,
                        is_starred BOOLEAN DEFAULT 0,
                        last_scanned INTEGER
                    );
                """;

        String createTagsTable = """
                    CREATE TABLE IF NOT EXISTS tags (
                        image_id INTEGER,
                        tag_text TEXT NOT NULL,
                        FOREIGN KEY(image_id) REFERENCES images(id) ON DELETE CASCADE,
                        UNIQUE(image_id, tag_text)
                    );
                """;

        String createMetadataTable = """
                    CREATE TABLE IF NOT EXISTS metadata (
                        image_id INTEGER,
                        key_text TEXT,
                        value_text TEXT,
                        FOREIGN KEY(image_id) REFERENCES images(id) ON DELETE CASCADE
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

        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute(createImagesTable);
            stmt.execute(createTagsTable);
            stmt.execute(createMetadataTable);
            stmt.execute(createPinnedFoldersTable);
            stmt.execute(createSettingsTable);

            // Create indexes for speed
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_path ON images(file_path);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tags_text ON tags(tag_text);");

        } catch (SQLException e) {
            System.err.println("CRITICAL: Failed to initialize database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ==================================================================================
    // CRUD HELPERS
    // ==================================================================================

    /**
     Helper to get the ID of an image, inserting it if it doesn't exist.
     */
    public int getOrCreateImageId(String absolutePath) throws SQLException {
        String selectSql = "SELECT id FROM images WHERE file_path = ?";
        String insertSql = "INSERT INTO images(file_path, last_scanned) VALUES(?, ?)";

        try (Connection conn = connect()) {
            // 1. Try to find
            try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
                pstmt.setString(1, absolutePath);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) return rs.getInt("id");
            }

            // 2. Insert if new
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