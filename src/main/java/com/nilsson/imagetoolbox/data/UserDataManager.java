package com.nilsson.imagetoolbox.data;

import javafx.concurrent.Task;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 Manages persistent user data, including pinned folders, image starring,
 tagging, settings, and metadata caching.
 * This class provides thread-safe access to the underlying DatabaseService
 using a ReadWriteLock and supports asynchronous operations for heavy tasks
 like paginated file searches.
 */
public class UserDataManager {

    private final DatabaseService db;
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();

    @javax.inject.Inject
    public UserDataManager(DatabaseService db) {
        this.db = db;
    }

    public void shutdown() {
        db.shutdown();
    }

    // --- Pinned Folders ---

    public List<File> getPinnedFolders() {
        readLock.lock();
        try {
            List<File> result = new ArrayList<>();
            String sql = "SELECT path FROM pinned_folders ORDER BY path ASC";
            try (Connection conn = db.connect();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    File f = new File(rs.getString("path"));
                    if (f.exists()) result.add(f);
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
                pstmt.setString(1, folder.getAbsolutePath());
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
                pstmt.setString(1, folder.getAbsolutePath());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } finally {
            writeLock.unlock();
        }
    }

    public boolean isPinned(File folder) {
        if (folder == null) return false;
        readLock.lock();
        try {
            String sql = "SELECT 1 FROM pinned_folders WHERE path = ?";
            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, folder.getAbsolutePath());
                ResultSet rs = pstmt.executeQuery();
                return rs.next();
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return false;
        } finally {
            readLock.unlock();
        }
    }

    // --- Async Search & Pagination ---

    /**
     Searches for files matching the query in Tags or Metadata.
     Runs asynchronously on a background thread.

     @param query  The search string.
     @param limit  Max results to return.
     @param offset Result offset.

     @return A JavaFX Task containing the results.
     */
    public Task<List<File>> findFilesAsync(String query, int limit, int offset) {
        return new Task<>() {
            @Override
            protected List<File> call() throws Exception {
                if (query == null || query.isBlank()) return new ArrayList<>();

                readLock.lock();
                try {
                    List<File> results = new ArrayList<>();
                    String likeQuery = "%" + query.trim() + "%";

                    String sql = """
                                SELECT file_path FROM (
                                    SELECT i.file_path 
                                    FROM images i
                                    JOIN tags t ON t.image_id = i.id
                                    WHERE t.tag_text LIKE ?
                            
                                    UNION
                            
                                    SELECT i.file_path 
                                    FROM images i
                                    JOIN metadata m ON m.image_id = i.id
                                    WHERE m.value_text LIKE ?
                                )
                                LIMIT ? OFFSET ?
                            """;

                    try (Connection conn = db.connect();
                         PreparedStatement pstmt = conn.prepareStatement(sql)) {

                        pstmt.setString(1, likeQuery);
                        pstmt.setString(2, likeQuery);
                        pstmt.setInt(3, limit);
                        pstmt.setInt(4, offset);

                        ResultSet rs = pstmt.executeQuery();
                        while (rs.next()) {
                            File f = new File(rs.getString("file_path"));
                            if (f.exists()) {
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

    public List<File> findFiles(String query) {
        try {
            Task<List<File>> task = findFilesAsync(query, 1000, 0);
            task.run();
            return task.get();
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    // --- Stars / Favorites ---

    public void addStar(File file) {
        if (file == null) return;
        writeLock.lock();
        try {
            int imageId = db.getOrCreateImageId(file.getAbsolutePath());
            String sql = "UPDATE images SET is_starred = 1 WHERE id = ?";
            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, imageId);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            writeLock.unlock();
        }
    }

    public void removeStar(File file) {
        if (file == null) return;
        writeLock.lock();
        try {
            String sql = "UPDATE images SET is_starred = 0 WHERE file_path = ?";
            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, file.getAbsolutePath());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } finally {
            writeLock.unlock();
        }
    }

    public boolean isStarred(File file) {
        if (file == null) return false;
        readLock.lock();
        try {
            String sql = "SELECT is_starred FROM images WHERE file_path = ?";
            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, file.getAbsolutePath());
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) return rs.getBoolean("is_starred");
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return false;
        } finally {
            readLock.unlock();
        }
    }

    public void toggleStar(File file) {
        writeLock.lock();
        try {
            if (isStarred(file)) removeStar(file);
            else addStar(file);
        } finally {
            writeLock.unlock();
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
                    File f = new File(rs.getString("file_path"));
                    if (f.exists()) result.add(f);
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return result;
        } finally {
            readLock.unlock();
        }
    }

    // --- Tags ---

    public void addTag(File file, String tag) {
        if (file == null || tag == null || tag.isBlank()) return;
        writeLock.lock();
        try {
            int imageId = db.getOrCreateImageId(file.getAbsolutePath());
            String sql = "INSERT OR IGNORE INTO tags(image_id, tag_text) VALUES(?, ?)";
            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, imageId);
                pstmt.setString(2, tag.trim());
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            writeLock.unlock();
        }
    }

    public void removeTag(File file, String tag) {
        if (file == null || tag == null) return;
        writeLock.lock();
        try {
            String sql = """
                        DELETE FROM tags 
                        WHERE tag_text = ? 
                        AND image_id = (SELECT id FROM images WHERE file_path = ?)
                    """;
            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, tag);
                pstmt.setString(2, file.getAbsolutePath());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
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
                        SELECT tag_text FROM tags 
                        JOIN images ON tags.image_id = images.id 
                        WHERE images.file_path = ?
                    """;

            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, file.getAbsolutePath());
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    tags.add(rs.getString("tag_text"));
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return tags;
        } finally {
            readLock.unlock();
        }
    }

    public List<File> findFilesByTag(String query) {
        return findFiles(query);
    }

    // --- Settings ---

    public String getSetting(String key, String defaultValue) {
        readLock.lock();
        try {
            String sql = "SELECT value FROM settings WHERE key = ?";
            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, key);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) return rs.getString("value");
            } catch (SQLException e) {
            }
            return defaultValue;
        } finally {
            readLock.unlock();
        }
    }

    public void setSetting(String key, String value) {
        writeLock.lock();
        try {
            String sql = "INSERT OR REPLACE INTO settings(key, value) VALUES(?, ?)";
            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, key);
                pstmt.setString(2, value);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } finally {
            writeLock.unlock();
        }
    }

    public File getLastFolder() {
        String pathStr = getSetting("last_folder", null);
        if (pathStr != null) {
            Path path = Paths.get(pathStr);
            File f = path.isAbsolute() ? path.toFile() : Paths.get("").toAbsolutePath().resolve(path).toFile();
            if (f.exists() && f.isDirectory()) return f;
        }
        return null;
    }

    /**
     * Saves the last opened folder. If the folder is a sub-directory of the
     * application, it is saved as a relative path for portability.
     */
    public void setLastFolder(File folder) {
        if (folder != null && folder.isDirectory()) {
            Path appPath = Paths.get("").toAbsolutePath();
            Path folderPath = folder.toPath().toAbsolutePath();

            String pathSave;
            if (folderPath.startsWith(appPath)) {
                pathSave = appPath.relativize(folderPath).toString();
            } else {
                pathSave = folderPath.toString();
            }
            setSetting("last_folder", pathSave);
        }
    }

    // --- Metadata Cache ---

    public boolean hasCachedMetadata(File file) {
        if (file == null) return false;
        readLock.lock();
        try {
            String sql = """
                        SELECT 1 FROM metadata m
                        JOIN images i ON i.id = m.image_id
                        WHERE i.file_path = ? LIMIT 1
                    """;
            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, file.getAbsolutePath());
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
            int imageId = db.getOrCreateImageId(file.getAbsolutePath());
            String insertSql = "INSERT OR REPLACE INTO metadata(image_id, key_text, value_text) VALUES(?, ?, ?)";

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
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            writeLock.unlock();
        }
    }

    public Map<String, String> getCachedMetadata(File file) {
        readLock.lock();
        try {
            Map<String, String> meta = new HashMap<>();
            if (file == null) return meta;

            String sql = """
                        SELECT key_text, value_text FROM metadata m
                        JOIN images i ON i.id = m.image_id
                        WHERE i.file_path = ?
                    """;

            try (Connection conn = db.connect();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, file.getAbsolutePath());
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    meta.put(rs.getString("key_text"), rs.getString("value_text"));
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return meta;
        } finally {
            readLock.unlock();
        }
    }
}