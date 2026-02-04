package com.nilsson.imagetoolbox.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
/**
 Data Access Object (DAO) responsible for managing image entities, their metadata,
 tags, and search capabilities within the application.
 * <p>This repository interacts with the {@link DatabaseService} to perform:
 <ul>
 <li>Core Identity Management: Tracking files via unique paths and hashes.</li>
 <li>Advanced Search: Dynamic SQL generation for filtered results and FTS (Full-Text Search) integration.</li>
 <li>Attribute Management: Handling ratings and starred status.</li>
 <li>Metadata & Tags: Batch processing and individual updates of EXIF/IPTC or custom data.</li>
 <li>Pinned Folders: Persistence for frequently accessed directories.</li>
 </ul>
 * <p>The class uses SQLite FTS5 through {@code updateFtsIndex} to ensure that metadata
 changes are immediately searchable via full-text queries.</p>
 */
public class ImageRepository {

    private static final Logger logger = LoggerFactory.getLogger(ImageRepository.class);
    private final DatabaseService db;

    @Inject
    public ImageRepository(DatabaseService db) {
        this.db = db;
    }

    // --- Core Identity ---

    public int getOrCreateId(String path, String hash) throws SQLException {
        try (Connection conn = db.connect()) {
            try (PreparedStatement pstmt = conn.prepareStatement("SELECT id FROM images WHERE file_path = ?")) {
                pstmt.setString(1, path);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) return rs.getInt("id");
            }

            String sql = "INSERT INTO images(file_path, file_hash, last_scanned) VALUES(?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setString(1, path);
                pstmt.setString(2, hash);
                pstmt.setLong(3, System.currentTimeMillis());
                pstmt.executeUpdate();
                ResultSet rs = pstmt.getGeneratedKeys();
                if (rs.next()) return rs.getInt(1);
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
                params.add(query.replace("'", "''"));
            }

            if (filters != null) {
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
        } catch (SQLException ignored) {
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
        } catch (SQLException ignored) {
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

        String placeholders = files.stream().map(f -> "?").collect(Collectors.joining(","));
        String sql = "SELECT i.file_path, m.key, m.value FROM images i " +
                "JOIN image_metadata m ON i.id = m.image_id " +
                "WHERE i.file_path IN (" + placeholders + ")";

        try (Connection conn = db.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            int idx = 1;
            for (File f : files) {
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
        } catch (SQLException ignored) {
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
        } catch (SQLException ignored) {
        }
    }

    public void removePinnedFolder(String path) {
        try (Connection conn = db.connect(); PreparedStatement pstmt = conn.prepareStatement("DELETE FROM pinned_folders WHERE path = ?")) {
            pstmt.setString(1, path);
            pstmt.executeUpdate();
        } catch (SQLException ignored) {
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

    public List<String> getDistinctValues(String key) {
        return db.getDistinctAttribute(key);
    }
}