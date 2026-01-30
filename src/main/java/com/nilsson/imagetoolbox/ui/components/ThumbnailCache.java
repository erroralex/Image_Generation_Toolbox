package com.nilsson.imagetoolbox.ui.components;

import javafx.scene.image.Image;

import java.io.File;
import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A robust Least Recently Used (LRU) cache for storing image thumbnails using SoftReferences.
 * <p>
 * This implementation wraps images in {@link SoftReference}, allowing the Garbage Collector
 * to reclaim memory if the application approaches an OutOfMemoryError, prioritizing application
 * stability over cache retention. It maintains a hard count limit as a secondary eviction policy.
 */
public class ThumbnailCache {

    // ==================================================================================
    // CONSTANTS & STATE
    // ==================================================================================

    private static final int MAX_ENTRIES = 250;

    private static final Map<String, SoftReference<Image>> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_ENTRIES + 1, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SoftReference<Image>> eldest) {
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
     * @return The cached Image, or null if not present or collected by GC.
     */
    public static Image get(String path) {
        if (path == null) return null;

        SoftReference<Image> ref = cache.get(path);
        if (ref != null) {
            Image img = ref.get();
            if (img != null) {
                return img;
            } else {
                cache.remove(path);
            }
        }
        return null;
    }

    /**
     * Retrieves an image from the cache, or loads it as a thumbnail if not present
     * (or if it was previously collected by the GC).
     *
     * @param file The file to load.
     * @return The cached or newly loaded thumbnail Image.
     */
    public static Image get(File file) {
        if (file == null) return null;
        String path = file.getAbsolutePath();

        Image cached = get(path);
        if (cached != null) {
            return cached;
        }

        try {
            Image img = new Image(file.toURI().toString(), 300, 0, true, true, false);
            put(path, img);
            return img;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Manually adds an image to the cache.
     *
     * @param path The key for the image (usually absolute path).
     * @param img  The image object to store.
     */
    public static void put(String path, Image img) {
        if (path != null && img != null) {
            cache.put(path, new SoftReference<>(img));
        }
    }

    /**
     * Clears all entries from the cache.
     */
    public static void clear() {
        cache.clear();
    }
}