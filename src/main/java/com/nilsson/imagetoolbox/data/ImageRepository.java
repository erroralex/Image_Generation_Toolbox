package com.nilsson.imagetoolbox.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 <h2>ImageRepository</h2>
 <p>
 A Data Access Object (DAO) responsible for managing image entities, their metadata,
 tags, and search capabilities within the application.
 </p>
 <p>This repository interacts with the {@link DatabaseService} to perform the following core functions:</p>
 <ul>
 <li><b>Core Identity Management:</b> Tracks files via unique paths and hashes, ensuring
 data integrity and mapping file system objects to database IDs.</li>
 <li><b>Advanced Search:</b> Dynamically generates SQL queries to support complex filtering
 and SQLite FTS5 (Full-Text Search) for rapid metadata retrieval.</li>
 <li><b>Attribute Management:</b> Handles persistence for user-driven attributes such as
 ratings and "starred" status.</li>
 <li><b>Metadata & Tags:</b> Manages key-value metadata pairs and tags, supporting both
 batch operations and individual updates.</li>
 <li><b>Pinned Folders:</b> Provides persistence for a user's frequently accessed directories.</li>
 </ul>
 <p><b>FTS Integration:</b> To ensure metadata changes are immediately searchable, this class
 invokes {@code updateFtsIndex} following metadata or tag modifications, keeping the SQLite
 FTS5 virtual tables in sync with the primary relational storage.</p>
 */
public class ImageRepository {

    private static final Logger logger = LoggerFactory.getLogger(ImageRepository.class);
    private final DatabaseService db;
    private static final int BATCH_SIZE = 500;

    @Inject
    public ImageRepository(DatabaseService db) {
        this.db = db;
    }

    // --- Core Identity ---

    public int getIdByPath(String path) {
        String sql = "SELECT id FROM images WHERE file_path = ?";
        try (Connection conn = db.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, path);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return rs.getInt("id");
            }
        } catch (SQLException e) {
            logger.error("Failed to fetch ID for path: {}", path, e);
        }
        return -1;
    }

    public List<String> findPathsByHash(String hash) {
        List<String> paths = new ArrayList<>();
        String sql = "SELECT file_path FROM images WHERE file_hash = ?";
        try (Connection conn = db.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, hash);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) paths.add(rs.getString("file_path"));
            }
        } catch (SQLException e) {
            logger.error("Failed to fetch paths by hash", e);
        }
        return paths;
    }

    public void updatePath(String oldPath, String newPath) {
        String sql = "UPDATE images SET file_path = ? WHERE file_path = ?";
        try (Connection conn = db.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newPath);
            pstmt.setString(2, oldPath);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to update path from {} to {}", oldPath, newPath, e);
        }
    }

    public int getOrCreateId(String path, String hash) throws SQLException {
        try (Connection conn = db.connect()) {
            String insertSql = "INSERT OR IGNORE INTO images(file_path, file_hash, last_scanned) VALUES(?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                pstmt.setString(1, path);
                pstmt.setString(2, hash);
                pstmt.setLong(3, System.currentTimeMillis());
                pstmt.executeUpdate();
            }

            String selectSql = "SELECT id FROM images WHERE file_path = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
                pstmt.setString(1, path);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("id");
                    }
                }
            }
        }
        throw new SQLException("Failed to get ID for " + path);
    }

    public void deleteByPath(String path) {
        String sql = "DELETE FROM images WHERE file_path = ?";
        try (Connection conn = db.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, path);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to delete record: {}", path, e);
        }
    }

    /**
     Streams all file paths from the database to the provided consumer.
     Uses a forward-only ResultSet to avoid loading all paths into memory.
     This is used for low-priority background reconciliation.
     */
    public void forEachFilePath(Consumer<String> action) {
        String sql = "SELECT file_path FROM images";
        try (Connection conn = db.connect();
             Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String path = rs.getString("file_path");
                if (path != null) {
                    action.accept(path);
                }
            }
        } catch (SQLException e) {
            logger.error("Error streaming file paths", e);
        }
    }

    /**
     Retrieves all file paths currently stored in the database.

     @deprecated Use forEachFilePath for large datasets to avoid OOM.
     */
    public List<String> getAllFilePaths() {
        List<String> paths = new ArrayList<>();
        forEachFilePath(paths::add);
        return paths;
    }

    // --- Search ---

    public List<String> findPaths(String query, Map<String, String> filters, int limit) {
        List<String> results = new ArrayList<>();
        try (Connection conn = db.connect()) {
            StringBuilder sql = new StringBuilder("SELECT DISTINCT i.file_path FROM images i ");
            List<Object> params = new ArrayList<>();

            boolean hasTextQuery = (query != null && !query.isBlank());
            if (hasTextQuery) sql.append("JOIN metadata_fts fts ON fts.image_id = i.id ");

            sql.append("WHERE 1=1 ");

            if (hasTextQuery) {
                sql.append("AND fts.global_text MATCH ? ");
                params.add(query);
            }

            if (filters != null) {
                for (Map.Entry<String, String> entry : filters.entrySet()) {
                    if (entry.getValue() == null || "All".equals(entry.getValue())) continue;

                    if ("Rating".equals(entry.getKey())) {
                        sql.append("AND i.rating = ? ");
                        params.add(entry.getValue());
                    } else {
                        sql.append("AND EXISTS (SELECT 1 FROM image_metadata m WHERE m.image_id = i.id AND m.key = ? AND m.value = ?) ");
                        params.add(entry.getKey());
                        params.add(entry.getValue());
                    }
                }
            }
            sql.append("LIMIT ?");
            params.add(limit);

            try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
                for (int k = 0; k < params.size(); k++) pstmt.setObject(k + 1, params.get(k));
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) results.add(rs.getString("file_path"));
            }
        } catch (SQLException e) {
            logger.error("Search failed", e);
        }
        return results;
    }

    // --- Attributes ---

    public int getRating(String path) {
        String sql = "SELECT rating FROM images WHERE file_path = ?";
        try (Connection conn = db.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, path);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) return rs.getInt("rating");
        } catch (SQLException e) {
            logger.error("Failed to get rating for path: {}", path, e);
        }
        return 0;
    }

    public void setRating(int id, int rating) {
        String sql = "UPDATE images SET rating = ? WHERE id = ?";
        try (Connection conn = db.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, rating);
            pstmt.setInt(2, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to set rating", e);
        }
    }

    public List<String> getStarredPaths() {
        List<String> results = new ArrayList<>();
        try (Connection conn = db.connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT file_path FROM images WHERE is_starred = 1")) {
            while (rs.next()) results.add(rs.getString("file_path"));
        } catch (SQLException e) {
            logger.error("Failed to fetch starred", e);
        }
        return results;
    }

    // --- Metadata & Tags ---

    public boolean hasMetadata(String path) {
        String sql = "SELECT 1 FROM image_metadata m JOIN images i ON i.id = m.image_id WHERE i.file_path = ? LIMIT 1";
        try (Connection conn = db.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, path);
            return pstmt.executeQuery().next();
        } catch (SQLException e) {
            logger.error("Failed to check metadata existence for path: {}", path, e);
            return false;
        }
    }

    public Map<String, String> getMetadata(String path) {
        Map<String, String> meta = new HashMap<>();
        String sql = """
                    SELECT key, value FROM image_metadata m
                    JOIN images i ON i.id = m.image_id
                    WHERE i.file_path = ?
                """;
        try (Connection conn = db.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, path);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) meta.put(rs.getString("key"), rs.getString("value"));
        } catch (SQLException e) {
            logger.error("Failed to fetch metadata: {}", path, e);
        }
        return meta;
    }

    public Map<String, Map<String, String>> batchGetMetadata(List<File> files) {
        Map<String, Map<String, String>> result = new HashMap<>();
        if (files == null || files.isEmpty()) return result;

        // Chunking to avoid SQLite variable limit (SQLITE_MAX_VARIABLE_NUMBER)
        for (int i = 0; i < files.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, files.size());
            List<File> batch = files.subList(i, end);

            String placeholders = batch.stream().map(f -> "?").collect(Collectors.joining(","));
            String sql = "SELECT i.file_path, m.key, m.value FROM images i " +
                    "JOIN image_metadata m ON i.id = m.image_id " +
                    "WHERE i.file_path IN (" + placeholders + ")";

            try (Connection conn = db.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                int idx = 1;
                for (File f : batch) {
                    pstmt.setString(idx++, f.getAbsolutePath());
                }

                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        String path = rs.getString("file_path");
                        String key = rs.getString("key");
                        String value = rs.getString("value");

                        result.computeIfAbsent(path, k -> new HashMap<>()).put(key, value);
                    }
                }
            } catch (SQLException e) {
                logger.error("Batch metadata fetch failed", e);
            }
        }
        return result;
    }

    public void saveMetadata(int imageId, Map<String, String> meta) {
        String sql = "INSERT OR REPLACE INTO image_metadata(image_id, key, value) VALUES(?, ?, ?)";
        try (Connection conn = db.connect()) {
            conn.setAutoCommit(false);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
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
            updateFtsIndex(imageId);
        } catch (SQLException e) {
            logger.error("Failed to save metadata", e);
        }
    }

    public void addTag(int imageId, String tag) {
        try (Connection conn = db.connect();
             PreparedStatement pstmt = conn.prepareStatement("INSERT OR IGNORE INTO image_tags(image_id, tag) VALUES(?, ?)")) {
            pstmt.setInt(1, imageId);
            pstmt.setString(2, tag);
            pstmt.executeUpdate();
            updateFtsIndex(imageId);
        } catch (SQLException e) {
            logger.error("Failed to add tag", e);
        }
    }

    public Set<String> getTags(String path) {
        Set<String> tags = new HashSet<>();
        String sql = "SELECT tag FROM image_tags t JOIN images i ON i.id = t.image_id WHERE i.file_path = ?";
        try (Connection conn = db.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, path);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) tags.add(rs.getString("tag"));
        } catch (SQLException e) {
            logger.error("Failed to get tags for path: {}", path, e);
        }
        return tags;
    }

    // --- Pinned Folders ---

    public List<File> getPinnedFolders(java.util.function.Function<String, File> resolver) {
        List<File> result = new ArrayList<>();
        try (Connection conn = db.connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT path FROM pinned_folders ORDER BY path ASC")) {
            while (rs.next()) {
                File f = resolver.apply(rs.getString("path"));
                if (f != null && f.exists()) result.add(f);
            }
        } catch (SQLException e) {
            logger.error("Error fetching pinned folders", e);
        }
        return result;
    }

    public void addPinnedFolder(String path) {
        try (Connection conn = db.connect(); PreparedStatement pstmt = conn.prepareStatement("INSERT OR IGNORE INTO pinned_folders(path) VALUES(?)")) {
            pstmt.setString(1, path);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to add pinned folder: {}", path, e);
        }
    }

    public void removePinnedFolder(String path) {
        try (Connection conn = db.connect(); PreparedStatement pstmt = conn.prepareStatement("DELETE FROM pinned_folders WHERE path = ?")) {
            pstmt.setString(1, path);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to remove pinned folder: {}", path, e);
        }
    }

    // --- Internal Helpers ---

    private void updateFtsIndex(int imageId) {
        String sql = """
                    INSERT OR REPLACE INTO metadata_fts(image_id, global_text)
                    SELECT ?, group_concat(val, ' ') FROM (
                        SELECT key || ':' || value as val FROM image_metadata WHERE image_id = ?
                        UNION ALL
                        SELECT 'tag:' || tag as val FROM image_tags WHERE image_id = ?
                    )
                """;
        try (Connection conn = db.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, imageId);
            pstmt.setInt(2, imageId);
            pstmt.setInt(3, imageId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("FTS Update failed", e);
        }
    }

    /**
     Retrieves a list of distinct values for a given metadata key.
     <p>
     Used primarily for populating filter dropdowns in the UI (e.g., Models, Samplers).
     </p>

     @param key The metadata key to search for (e.g., "Model").

     @return A list of unique string values, sorted alphabetically.
     */
    public List<String> getDistinctValues(String key) {
        List<String> results = new ArrayList<>();
        String sql = "SELECT DISTINCT value FROM image_metadata WHERE key = ? ORDER BY value ASC";
        try (Connection conn = db.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                String val = rs.getString("value");
                if (val != null && !val.isBlank()) {
                    results.add(val);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to fetch distinct values for key: {}", key, e);
        }
        return results;
    }
}