package com.nilsson.imagetoolbox.ui.components;

import javafx.scene.image.Image;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A simple Least Recently Used (LRU) cache for storing image thumbnails.
 * <p>
 * This cache prevents memory exhaustion by limiting the number of loaded images
 * kept in memory. It uses a synchronized LinkedHashMap to ensure thread safety
 * during concurrent access from UI and background threads.
 */
public class ThumbnailCache {

    // ==================================================================================
    // CONSTANTS & STATE
    // ==================================================================================

    private static final int MAX_ENTRIES = 200;

    private static final Map<String, Image> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, Image>(MAX_ENTRIES + 1, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
                    return size() > MAX_ENTRIES;
                }
            }
    );

    // ==================================================================================
    // PUBLIC API
    // ==================================================================================

    /**
     * Retrieves an image from the cache by its path.
     *
     * @param path The absolute path of the image.
     * @return The cached Image, or null if not present.
     */
    public static Image get(String path) {
        return cache.get(path);
    }

    /**
     * Retrieves an image from the cache, or loads it as a thumbnail if not present.
     * <p>
     * This ensures the image is loaded with reduced dimensions (width 300px) to save memory.
     *
     * @param file The file to load.
     * @return The cached or newly loaded thumbnail Image.
     */
    public static Image get(File file) {
        if (file == null) return null;
        String path = file.getAbsolutePath();

        if (cache.containsKey(path)) {
            return cache.get(path);
        }

        Image img = new Image(file.toURI().toString(), 300, 0, true, true);
        put(path, img);
        return img;
    }

    /**
     * Manually adds an image to the cache.
     *
     * @param path The key for the image (usually absolute path).
     * @param img  The image object to store.
     */
    public static void put(String path, Image img) {
        if (path != null && img != null) {
            cache.put(path, img);
        }
    }

    /**
     * Clears all entries from the cache.
     */
    public static void clear() {
        cache.clear();
    }
}