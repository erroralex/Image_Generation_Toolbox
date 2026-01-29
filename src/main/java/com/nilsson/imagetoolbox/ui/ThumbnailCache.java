package com.nilsson.imagetoolbox.ui;

import javafx.scene.image.Image;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ThumbnailCache {
    private static final int MAX_ENTRIES = 200;

    private static final Map<String, Image> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, Image>(MAX_ENTRIES + 1, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
                    return size() > MAX_ENTRIES;
                }
            }
    );

    public static Image get(String path) {
        return cache.get(path);
    }

    public static void put(String path, Image img) {
        if (path != null && img != null) {
            cache.put(path, img);
        }
    }

    public static void clear() {
        cache.clear();
    }
}