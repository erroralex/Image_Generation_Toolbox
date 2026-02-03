package com.nilsson.imagetoolbox.data;

import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 <h2>UserDataManager</h2>
 <p>
 This class serves as the primary data access layer for the Image Toolbox application.
 </p>

 <h3>Refactoring Notes:</h3>
 <ul>
 <li><b>Bug Fix:</b> <code>resolvePath</code> now correctly identifies absolute paths (for Pinned Folders) using string analysis instead of disk I/O.</li>
 <li><b>Performance:</b> Maintains O(1) resolution speed for search loops.</li>
 </ul>
 */
public class UserDataManager {

    private static final Logger logger = LoggerFactory.getLogger(UserDataManager.class);

    private final DatabaseService db;
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();
    private final File libraryRoot;

    @Inject
    public UserDataManager(DatabaseService db) {
        this.db = db;
        this.libraryRoot = new File(System.getProperty("user.dir")).getAbsoluteFile();
        logger.info("UserDataManager initialized. Library root: {}", libraryRoot);
    }

    public void shutdown() {
        logger.info("Shutting down UserDataManager...");
        db.shutdown();
    }

    // ------------------------------------------------------------------------
    // Path Normalization Logic (FIXED)
    // ------------------------------------------------------------------------

    private File resolvePath(String dbPath) {
        if (dbPath == null) return null;

        // 1. Check if the path stored in DB is already absolute (e.g. Pinned Folders outside library)
        File potentiallyAbsolute = new File(dbPath);
        if (potentiallyAbsolute.isAbsolute()) {
            return potentiallyAbsolute;
        }

        // 2. Otherwise, treat as relative to library root
        String systemPath = dbPath.replace("/", File.separator);
        return new File(libraryRoot, systemPath);
    }

    private String relativizePath(File file) {
        if (file == null) return null;
        try {
            java.nio.file.Path rootPath = libraryRoot.toPath();
            java.nio.file.Path filePath = file.getAbsoluteFile().toPath();
            if (filePath.startsWith(rootPath)) {
                String rel = rootPath.relativize(filePath).toString();
                return rel.replace("\\", "/");
            }
        } catch (Exception e) {
            logger.warn("Failed to relativize path for file: {}", file, e);
        }
        return file.getAbsolutePath().replace("\\", "/");
    }

    // ------------------------------------------------------------------------
    // Structured Search & FTS5
    // ------------------------------------------------------------------------

    public List<String> getDistinctMetadataValues(String key) {
        List<String> values = new ArrayList<>();
        readLock.lock();
        try (Connection conn = db.connect();
             PreparedStatement pstmt = conn.prepareStatement(
                     "SELECT DISTINCT value FROM image_metadata WHERE key = ? ORDER BY value ASC")) {
            pstmt.setString(1, key);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                String v = rs.getString("value");
                if (v != null && !v.isBlank()) {
                    values.add(v);
                }
            }
        } catch (SQLException e) {
            logger.error("Error fetching distinct metadata values for key: {}", key, e);
        } finally {
            readLock.unlock();
        }
        return values;
    }

    public Task<List<File>> findFilesWithFilters(String query, Map<String, String> filters, int limit) {
        return new Task<>() {
            @Override
            protected List<File> call() throws Exception {
                long startTime = System.currentTimeMillis();
                readLock.lock();
                try (Connection conn = db.connect()) {
                    List<File> results = new ArrayList<>();
                    StringBuilder sql = new StringBuilder("SELECT DISTINCT i.file_path FROM images i ");
                    List<Object> params = new ArrayList<>();

                    boolean hasTextQuery = (query != null && !query.isBlank());
                    if (hasTextQuery) {
                        sql.append("JOIN metadata_fts fts ON fts.image_id = i.id ");
                    }

                    sql.append("WHERE 1=1 ");

                    if (hasTextQuery) {
                        sql.append("AND fts.global_text MATCH ? ");
                        params.add(query.replace("'", "''"));
                    }

                    if (filters != null && !filters.isEmpty()) {
                        for (Map.Entry<String, String> entry : filters.entrySet()) {
                            if (entry.getValue() == null || "All".equals(entry.getValue())) continue;

                            sql.append("AND EXISTS (SELECT 1 FROM image_metadata m WHERE m.image_id = i.id AND m.key = ? AND m.value = ?) ");
                            params.add(entry.getKey());
                            params.add(entry.getValue());
                        }
                    }

                    sql.append("LIMIT ?");
                    params.add(limit);

                    try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
                        for (int k = 0; k < params.size(); k++) {
                            pstmt.setObject(k + 1, params.get(k));
                        }

                        ResultSet rs = pstmt.executeQuery();
                        while (rs.next()) {
                            File f = resolvePath(rs.getString("file_path"));
                            if (f != null) {
                                results.add(f);
                            }
                        }
                    }

                    logger.debug("Search completed in {}ms. Found {} files.",
                            (System.currentTimeMillis() - startTime), results.size());
                    return results;
                } catch (SQLException e) {
                    logger.error("SQL Error during file search", e);
                    throw e;
                } finally {
                    readLock.unlock();
                }
            }
        };
    }

    // ------------------------------------------------------------------------
    // File Operations & Hashing
    // ------------------------------------------------------------------------

    public boolean moveFileToTrash(File file) {
        if (file == null || !file.exists()) return false;

        boolean success = false;
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            try {
                success = Desktop.getDesktop().moveToTrash(file);
            } catch (Exception e) {
                logger.error("Failed to move file to trash: {}", file, e);
                return false;
            }
        } else {
            return false;
        }

        if (success) {
            writeLock.lock();
            try {
                String relPath = relativizePath(file);
                String sql = "DELETE FROM images WHERE file_path = ?";
                try (Connection conn = db.connect();
                     PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, relPath);
                    pstmt.executeUpdate();
                }
            } catch (SQLException e) {
                logger.error("Failed to delete file record from DB: {}", file, e);
            } finally {
                writeLock.unlock();
            }
        }
        return success;
    }

    private String calculateHash(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] byteArray = new byte[8192];
            int bytesCount;
            while ((bytesCount = fis.read(byteArray)) != -1) {
                digest.update(byteArray, 0, bytesCount);
            }
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            logger.error("Error calculating hash for file: {}", file, e);
            return null;
        }
    }

    // ------------------------------------------------------------------------
    // Image Properties & Folders
    // ------------------------------------------------------------------------

    public List<File> getPinnedFolders() {
        readLock.lock();
        try {
            List<File> result = new ArrayList<>();
            String sql = "SELECT path FROM pinned_folders ORDER BY path ASC";
            try (Connection conn = db.connect();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    File f = resolvePath(rs.getString("path"));
                    // We check exists() here because pinned folders are few and specific
                    if (f != null && f.exists()) result.add(f);
                }
            } catch (SQLException e) {
                logger.error("Error fetching pinned folders", e);
            }
            return result;
        } finally {
            readLock.unlock();
        }
    }

    public void addPinnedFolder(File folder) {
        if (folder == null || !folder.isDirectory()) return;
        writeLock.lock();
        try {
            String sql = "INSERT OR IGNORE INTO pinned_folders(path) VALUES(?)";
            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, relativizePath(folder));
                pstmt.executeUpdate();
            } catch (SQLException e) {
                logger.error("Error adding pinned folder: {}", folder, e);
            }
        } finally {
            writeLock.unlock();
        }
    }

    public void removePinnedFolder(File folder) {
        if (folder == null) return;
        writeLock.lock();
        try {
            String sql = "DELETE FROM pinned_folders WHERE path = ?";
            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, relativizePath(folder));
                pstmt.executeUpdate();
            } catch (SQLException e) {
                logger.error("Error removing pinned folder: {}", folder, e);
            }
        } finally {
            writeLock.unlock();
        }
    }

    public int getRating(File file) {
        if (file == null) return 0;
        String sql = "SELECT rating FROM images WHERE file_path = ?";
        try (Connection conn = db.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, relativizePath(file));
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) return rs.getInt("rating");
        } catch (Exception e) {
            logger.error("Error fetching rating for file: {}", file, e);
        }
        return 0;
    }

    public void setRating(File file, int rating) {
        if (file == null) return;
        int r = Math.max(0, Math.min(5, rating));
        String sql = "UPDATE images SET rating = ? WHERE id = ?";
        try {
            int id = getOrCreateImageIdInternal(file);
            try (Connection conn = db.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, r);
                pstmt.setInt(2, id);
                pstmt.executeUpdate();
            }
        } catch (Exception e) {
            logger.error("Error setting rating for file: {}", file, e);
        }
    }

    public List<File> getStarredFilesList() {
        readLock.lock();
        try {
            List<File> result = new ArrayList<>();
            String sql = "SELECT file_path FROM images WHERE is_starred = 1";
            try (Connection conn = db.connect();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    File f = resolvePath(rs.getString("file_path"));
                    if (f != null && f.exists()) result.add(f);
                }
            } catch (SQLException e) {
                logger.error("Error fetching starred files", e);
            }
            return result;
        } finally {
            readLock.unlock();
        }
    }

    // ------------------------------------------------------------------------
    // Tagging System
    // ------------------------------------------------------------------------

    public void addTag(File file, String tag) {
        if (file == null || tag == null || tag.isBlank()) return;
        writeLock.lock();
        try {
            int imageId = getOrCreateImageIdInternal(file);
            String sql = "INSERT OR IGNORE INTO image_tags(image_id, tag) VALUES(?, ?)";
            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, imageId);
                pstmt.setString(2, tag.trim());
                pstmt.executeUpdate();
            }
            updateFtsIndex(imageId);
        } catch (SQLException e) {
            logger.error("Error adding tag {} to file {}", tag, file, e);
        } finally {
            writeLock.unlock();
        }
    }

    public Set<String> getTags(File file) {
        readLock.lock();
        try {
            Set<String> tags = new HashSet<>();
            if (file == null) return tags;
            String sql = """
                        SELECT tag FROM image_tags 
                        JOIN images ON image_tags.image_id = images.id 
                        WHERE images.file_path = ?
                    """;
            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, relativizePath(file));
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    tags.add(rs.getString("tag"));
                }
            } catch (SQLException e) {
                logger.error("Error fetching tags for file: {}", file, e);
            }
            return tags;
        } finally {
            readLock.unlock();
        }
    }

    // ------------------------------------------------------------------------
    // Metadata Management
    // ------------------------------------------------------------------------

    public boolean hasCachedMetadata(File file) {
        if (file == null) return false;
        readLock.lock();
        try {
            String sql = "SELECT 1 FROM image_metadata m JOIN images i ON i.id = m.image_id WHERE i.file_path = ? LIMIT 1";
            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, relativizePath(file));
                return pstmt.executeQuery().next();
            } catch (SQLException e) {
                logger.error("Error checking metadata cache for file: {}", file, e);
            }
            return false;
        } finally {
            readLock.unlock();
        }
    }

    public void cacheMetadata(File file, Map<String, String> meta) {
        if (file == null || meta == null || meta.isEmpty()) return;
        writeLock.lock();
        try {
            int imageId = getOrCreateImageIdInternal(file);
            String insertSql = "INSERT OR REPLACE INTO image_metadata(image_id, key, value) VALUES(?, ?, ?)";
            try (Connection conn = db.connect()) {
                conn.setAutoCommit(false);
                try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                    for (Map.Entry<String, String> entry : meta.entrySet()) {
                        pstmt.setInt(1, imageId);
                        pstmt.setString(2, entry.getKey());
                        pstmt.setString(3, entry.getValue());
                        pstmt.addBatch();
                    }
                    pstmt.executeBatch();
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            }
            updateFtsIndex(imageId);
        } catch (SQLException e) {
            logger.error("Error caching metadata for file: {}", file, e);
        } finally {
            writeLock.unlock();
        }
    }

    private void updateFtsIndex(int imageId) throws SQLException {
        String aggregateSql = """
                    SELECT key, value FROM image_metadata WHERE image_id = ?
                    UNION ALL
                    SELECT 'tag' as key, tag as value FROM image_tags WHERE image_id = ?
                """;
        StringBuilder globalText = new StringBuilder();
        try (Connection conn = db.connect();
             PreparedStatement pstmt = conn.prepareStatement(aggregateSql)) {
            pstmt.setInt(1, imageId);
            pstmt.setInt(2, imageId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                String k = rs.getString("key");
                String v = rs.getString("value");
                if (k != null) globalText.append(k).append(":").append(v).append(" ");
                else globalText.append(v).append(" ");
            }
        }
        if (globalText.length() > 0) {
            String updateFts = "INSERT OR REPLACE INTO metadata_fts(image_id, global_text) VALUES(?, ?)";
            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement(updateFts)) {
                pstmt.setInt(1, imageId);
                pstmt.setString(2, globalText.toString());
                pstmt.executeUpdate();
            }
        }
    }

    public Map<String, String> getCachedMetadata(File file) {
        readLock.lock();
        try {
            Map<String, String> meta = new HashMap<>();
            if (file == null) return meta;
            String sql = """
                        SELECT key, value FROM image_metadata m
                        JOIN images i ON i.id = m.image_id
                        WHERE i.file_path = ?
                    """;
            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, relativizePath(file));
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    meta.put(rs.getString("key"), rs.getString("value"));
                }
            } catch (SQLException e) {
                logger.error("Error retrieving cached metadata for file: {}", file, e);
            }
            return meta;
        } finally {
            readLock.unlock();
        }
    }

    private int getOrCreateImageIdInternal(File file) throws SQLException {
        String relPath = relativizePath(file);
        try (Connection conn = db.connect()) {
            try (PreparedStatement pstmt = conn.prepareStatement("SELECT id FROM images WHERE file_path = ?")) {
                pstmt.setString(1, relPath);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) return rs.getInt("id");
            }
            String hash = calculateHash(file);
            String insert = "INSERT INTO images(file_path, file_hash, last_scanned) VALUES(?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setString(1, relPath);
                pstmt.setString(2, hash);
                pstmt.setLong(3, System.currentTimeMillis());
                pstmt.executeUpdate();
                ResultSet rs = pstmt.getGeneratedKeys();
                if (rs.next()) return rs.getInt(1);
            }
        }
        throw new SQLException("Failed to get ID for " + relPath);
    }

    public List<String> getCollections() {
        readLock.lock();
        try {
            List<String> names = new ArrayList<>();
            try (Connection conn = db.connect();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name FROM collections ORDER BY name ASC")) {
                while (rs.next()) {
                    names.add(rs.getString("name"));
                }
            } catch (SQLException e) {
                logger.error("Error fetching collections", e);
            }
            return names;
        } finally {
            readLock.unlock();
        }
    }

    public void createCollection(String name) {
        if (name == null || name.isBlank()) return;
        writeLock.lock();
        try (Connection conn = db.connect();
             PreparedStatement pstmt = conn.prepareStatement("INSERT OR IGNORE INTO collections(name, created_at) VALUES(?, ?)")) {
            pstmt.setString(1, name.trim());
            pstmt.setLong(2, System.currentTimeMillis());
            pstmt.executeUpdate();
            logger.info("Created collection: {}", name);
        } catch (SQLException e) {
            logger.error("Error creating collection: {}", name, e);
        } finally {
            writeLock.unlock();
        }
    }

    public void deleteCollection(String name) {
        if (name == null) return;
        writeLock.lock();
        try (Connection conn = db.connect();
             PreparedStatement pstmt = conn.prepareStatement("DELETE FROM collections WHERE name = ?")) {
            pstmt.setString(1, name);
            pstmt.executeUpdate();
            logger.info("Deleted collection: {}", name);
        } catch (SQLException e) {
            logger.error("Error deleting collection: {}", name, e);
        } finally {
            writeLock.unlock();
        }
    }

    public void addImageToCollection(String collectionName, File file) {
        if (collectionName == null || file == null) return;
        writeLock.lock();
        try {
            int imageId = getOrCreateImageIdInternal(file);
            String sql = """
                        INSERT OR IGNORE INTO collection_images (collection_id, image_id, added_at)
                        SELECT c.id, ?, ? FROM collections c WHERE c.name = ?
                    """;
            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, imageId);
                pstmt.setLong(2, System.currentTimeMillis());
                pstmt.setString(3, collectionName);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("Error adding image {} to collection {}", file, collectionName, e);
        } finally {
            writeLock.unlock();
        }
    }

    public List<File> getFilesFromCollection(String collectionName) {
        readLock.lock();
        List<File> results = new ArrayList<>();
        try (Connection conn = db.connect()) {
            String sql = """
                        SELECT i.file_path 
                        FROM images i
                        JOIN collection_images ci ON i.id = ci.image_id
                        JOIN collections c ON ci.collection_id = c.id
                        WHERE c.name = ?
                        ORDER BY ci.added_at DESC
                    """;
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, collectionName);
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    File f = resolvePath(rs.getString("file_path"));
                    if (f != null && f.exists()) {
                        results.add(f);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error fetching files from collection: {}", collectionName, e);
        } finally {
            readLock.unlock();
        }
        return results;
    }

    public String getSetting(String key, String defaultValue) {
        readLock.lock();
        try {
            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement("SELECT value FROM settings WHERE key = ?")) {
                pstmt.setString(1, key);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) return rs.getString("value");
            } catch (SQLException ignored) {
            }
            return defaultValue;
        } finally {
            readLock.unlock();
        }
    }

    public void setSetting(String key, String value) {
        writeLock.lock();
        try (Connection conn = db.connect();
             PreparedStatement pstmt = conn.prepareStatement("INSERT OR REPLACE INTO settings(key, value) VALUES(?, ?)")) {
            pstmt.setString(1, key);
            pstmt.setString(2, value);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Error saving setting: {} = {}", key, value, e);
        } finally {
            writeLock.unlock();
        }
    }

    public File getLastFolder() {
        String pathStr = getSetting("last_folder", null);
        if (pathStr != null) {
            return resolvePath(pathStr);
        }
        return null;
    }

    public void setLastFolder(File folder) {
        if (folder != null) {
            setSetting("last_folder", relativizePath(folder));
        }
    }
}