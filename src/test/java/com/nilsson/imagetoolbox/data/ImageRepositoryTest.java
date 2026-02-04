package com.nilsson.imagetoolbox.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 Integration tests for {@link ImageRepository} using an ephemeral SQLite instance.
 * <p>This test suite validates the persistence layer by executing actual SQL commands
 against a temporary database file. It ensures that:
 <ul>
 <li>Image identity is maintained across retrieval calls.</li>
 <li>Metadata is correctly associated with image records via foreign keys.</li>
 <li>The Full-Text Search (FTS) indexing triggers correctly upon metadata saves.</li>
 <li>HikariCP connection pooling functions within the test lifecycle.</li>
 </ul>
 </p>
 * <p>The suite utilizes JUnit 5's {@link TempDir} to isolate database files for
 each test run, preventing cross-contamination between test cases.</p>
 */
class ImageRepositoryTest {

    private DatabaseService dbService;
    private ImageRepository repository;

    @TempDir
    Path tempDir;

    // --- Lifecycle Management ---

    @BeforeEach
    void setUp() {
        File dbFile = tempDir.resolve("test-library.db").toFile();
        String connectionString = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        dbService = new DatabaseService(connectionString);
        repository = new ImageRepository(dbService);
    }

    @AfterEach
    void tearDown() {
        if (dbService != null) {
            dbService.shutdown();
        }
    }

    // --- Test Cases ---

    @Test
    void testGetOrCreateId_createsAndRetrieves() throws SQLException {
        String path = "/tmp/image1.png";
        String hash = "abc123hash";

        // 1. Create
        int id1 = repository.getOrCreateId(path, hash);
        assertTrue(id1 > 0);

        // 2. Retrieve existing
        int id2 = repository.getOrCreateId(path, hash);
        assertEquals(id1, id2, "ID should remain consistent for same path");
    }

    @Test
    void testMetadataSavingAndSearch() throws SQLException {
        int id = repository.getOrCreateId("/tmp/robot.png", "hash1");

        Map<String, String> meta = new HashMap<>();
        meta.put("Model", "Stable Diffusion XL");
        meta.put("Prompt", "A futuristic robot");

        repository.saveMetadata(id, meta);

        // Test Filter Map search
        Map<String, String> filters = new HashMap<>();
        filters.put("Model", "Stable Diffusion XL");

        List<String> results = repository.findPaths("", filters, 10);
        assertEquals(1, results.size());
        assertEquals("/tmp/robot.png", results.get(0));
    }
}