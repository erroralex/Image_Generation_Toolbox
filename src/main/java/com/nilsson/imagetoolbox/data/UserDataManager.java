package com.nilsson.imagetoolbox.data;

import javafx.concurrent.Task;
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
 It manages persistent user data, including file locations, metadata caching, tagging,
 and virtual collections.
 </p>
 * <h3>Key Features:</h3>
 <ul>
 <li><b>Path Normalization:</b> Handles conversion between absolute system paths and
 database-friendly relative paths to ensure library portability.</li>
 <li><b>Concurrency Control:</b> Implements a {@link ReadWriteLock} strategy to ensure
 thread-safe access to the underlying SQLite database during heavy scan or search operations.</li>
 <li><b>Advanced Search:</b> Leverages SQLite's FTS5 (Full-Text Search) for rapid
 global querying across tags and metadata.</li>
 <li><b>File Integrity:</b> Utilizes SHA-256 hashing to track files even if they are
 renamed or moved within the library structure.</li>
 <li><b>Integration:</b> Supports system-level operations like moving files to the
 OS trash while keeping the database synchronized.</li>
 </ul>
 */
public class UserDataManager {

    // ------------------------------------------------------------------------
    // Fields & State
    // ------------------------------------------------------------------------

    private final DatabaseService db;
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();
    private final File libraryRoot;

    // ------------------------------------------------------------------------
    // Constructor & Lifecycle
    // ------------------------------------------------------------------------

    @javax.inject.Inject
    public UserDataManager(DatabaseService db) {
        this.db = db;
        this.libraryRoot = new File(System.getProperty("user.dir")).getAbsoluteFile();
    }

    public void shutdown() {
        db.shutdown();
    }

    // ------------------------------------------------------------------------
    // Path Normalization Logic
    // ------------------------------------------------------------------------

    private File resolvePath(String dbPath) {
        if (dbPath == null) return null;
        String systemPath = dbPath.replace("/", File.separator);
        File f = new File(libraryRoot, systemPath);
        if (!f.exists()) {
            File abs = new File(dbPath);
            if (abs.exists()) return abs;
        }
        return f;
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
        } catch (Exception ignored) {
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
            e.printStackTrace();
        } finally {
            readLock.unlock();
        }
        return values;
    }

    public Task<List<File>> findFilesWithFilters(String query, Map<String, String> filters, int limit) {
        return new Task<>() {
            @Override
            protected List<File> call() throws Exception {
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
                            if (f != null && f.exists()) {
                                results.add(f);
                            }
                        }
                    }
                    return results;
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
                e.printStackTrace();
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
                e.printStackTrace();
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
                    if (f != null && f.exists()) result.add(f);
                }
            } catch (SQLException e) {
                e.printStackTrace();
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
                e.printStackTrace();
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
                e.printStackTrace();
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
            e.printStackTrace();
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
            e.printStackTrace();
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
                e.printStackTrace();
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
            e.printStackTrace();
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
                e.printStackTrace();
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
                e.printStackTrace();
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
            e.printStackTrace();
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
                e.printStackTrace();
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

    // ------------------------------------------------------------------------
    // Virtual Collections Management
    // ------------------------------------------------------------------------

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
                e.printStackTrace();
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
        } catch (SQLException e) {
            e.printStackTrace();
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
        } catch (SQLException e) {
            e.printStackTrace();
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
            e.printStackTrace();
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
            e.printStackTrace();
        } finally {
            readLock.unlock();
        }
        return results;
    }

    // ------------------------------------------------------------------------
    // Settings & Persistence
    // ------------------------------------------------------------------------

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
            e.printStackTrace();
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