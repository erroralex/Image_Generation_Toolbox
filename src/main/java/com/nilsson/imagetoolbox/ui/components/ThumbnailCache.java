package com.nilsson.imagetoolbox.ui.components;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 A robust 2-tier Least Recently Used (LRU) cache system designed for JavaFX image thumbnails.
 * <p>This utility optimizes application performance by minimizing expensive I/O and
 image decoding operations. It operates using two distinct layers:</p>
 * <ul>
 <li><b>Tier 1 (Memory Cache):</b> Utilizes {@link SoftReference} to store {@link Image}
 objects in the JVM heap. This layer provides near-instantaneous access but allows
 the Garbage Collector to reclaim memory during low-resource scenarios.</li>
 <li><b>Tier 2 (Disk Cache):</b> Persists resized thumbnails as JPEGs in a local
 {@code .cache} directory. This ensures that once a thumbnail is generated, it
 survives application restarts and reduces CPU overhead for subsequent sessions.</li>
 </ul>
 * <p><b>Implementation Details:</b></p>
 <ul>
 <li>The memory cache is synchronized and capped at 250 entries using an LRU eviction policy.</li>
 <li>Cache keys are generated using SHA-256 hashes of absolute file paths to ensure
 filesystem compatibility and prevent collisions with special characters.</li>
 <li>Disk writes utilize {@link ImageIO} and convert images to {@code TYPE_INT_RGB} for
 optimal JPEG compression and performance.</li>
 </ul>
 */
public class ThumbnailCache {

    // ------------------------------------------------------------------------
    // Static Configuration & State
    // ------------------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(ThumbnailCache.class);
    private static final int MAX_MEM_ENTRIES = 250;
    private static final File CACHE_DIR = new File(System.getProperty("user.dir"), ".cache/thumbnails");

    private static final Map<String, SoftReference<Image>> memCache = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_MEM_ENTRIES + 1, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SoftReference<Image>> eldest) {
                    return size() > MAX_MEM_ENTRIES;
                }
            }
    );

    static {
        if (!CACHE_DIR.exists()) {
            if (CACHE_DIR.mkdirs()) {
                logger.info("Created thumbnail cache directory: {}", CACHE_DIR.getAbsolutePath());
            }
        }
    }

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------

    /**
     Retrieves an image from the cache by checking tiers in order: Memory -> Disk.
     If not found in either tier, it returns null to signify a source load is required.

     @param file The source image file to retrieve a thumbnail for.

     @return The cached {@link Image}, or {@code null} if the cache is empty for this file.
     */
    public static Image get(File file) {
        if (file == null) return null;
        String path = file.getAbsolutePath();

        Image cached = getFromMemory(path);
        if (cached != null) {
            return cached;
        }

        File diskCacheFile = getCacheFile(path);
        if (diskCacheFile.exists()) {
            try {
                Image diskImg = new Image(diskCacheFile.toURI().toString());
                if (!diskImg.isError()) {
                    putInMemory(path, diskImg);
                    return diskImg;
                }
            } catch (Exception e) {
                logger.warn("Failed to load from disk cache: {}", diskCacheFile.getName());
            }
        }

        return null;
    }

    /**
     Manually adds a loaded image to both the Memory and Disk cache tiers.

     @param sourceFile The original source file used as the cache key.
     @param img        The JavaFX Image thumbnail to be stored.
     */
    public static void put(File sourceFile, Image img) {
        if (sourceFile == null || img == null) return;
        String path = sourceFile.getAbsolutePath();

        putInMemory(path, img);

        File diskCacheFile = getCacheFile(path);
        if (!diskCacheFile.exists()) {
            saveToDisk(img, diskCacheFile);
        }
    }

    /**
     Clears all entries from the volatile memory cache.
     Note: This does not affect the persistent disk cache.
     */
    public static void clearMemory() {
        memCache.clear();
    }

    // ------------------------------------------------------------------------
    // Legacy / Convenience Wrappers
    // ------------------------------------------------------------------------

    /**
     String-based wrapper for adding an image to the cache.

     @param path The absolute path to the file.
     @param img  The thumbnail image.
     */
    public static void put(String path, Image img) {
        put(new File(path), img);
    }

    /**
     String-based wrapper for retrieving an image from the cache.

     @param path The absolute path to the file.

     @return The cached image or null.
     */
    public static Image get(String path) {
        return get(new File(path));
    }

    // ------------------------------------------------------------------------
    // Internal Helper Methods
    // ------------------------------------------------------------------------

    private static Image getFromMemory(String path) {
        SoftReference<Image> ref = memCache.get(path);
        if (ref != null) {
            Image img = ref.get();
            if (img != null) {
                return img;
            } else {
                memCache.remove(path);
            }
        }
        return null;
    }

    private static void putInMemory(String path, Image img) {
        memCache.put(path, new SoftReference<>(img));
    }

    private static File getCacheFile(String path) {
        String hash = computeHash(path);
        return new File(CACHE_DIR, hash + ".jpg");
    }

    private static void saveToDisk(Image img, File target) {
        try {
            BufferedImage bImg = SwingFXUtils.fromFXImage(img, null);
            if (bImg != null) {
                BufferedImage rgbImg = new BufferedImage(bImg.getWidth(), bImg.getHeight(), BufferedImage.TYPE_INT_RGB);
                rgbImg.createGraphics().drawImage(bImg, 0, 0, java.awt.Color.BLACK, null);

                ImageIO.write(rgbImg, "jpg", target);
            }
        } catch (IOException e) {
            logger.error("Failed to write thumbnail to disk: {}", target.getName(), e);
        }
    }

    private static String computeHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}