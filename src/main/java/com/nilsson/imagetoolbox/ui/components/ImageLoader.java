package com.nilsson.imagetoolbox.ui.components;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 <h2>ImageLoader</h2>
 <p>
 A robust utility class designed to handle image loading for JavaFX applications, specifically
 optimized for AI-generated media which often utilizes modern or niche formats like WebP.
 </p>
 */
public class ImageLoader {

    static {
        // Essential: Scan for TwelveMonkeys plugins (WebP, etc.)
        ImageIO.scanForPlugins();
        // Disable disk cache to prevent locking issues; relies on RAM (MemoryCacheImageInputStream)
        ImageIO.setUseCache(false);

        // Explicitly register WebP provider to ensure it's available in all contexts
        try {
            IIORegistry.getDefaultInstance().registerServiceProvider(new WebPImageReaderSpi());
        } catch (Throwable t) {
            System.err.println("ImageLoader: Failed to manually register WebP SPI: " + t.getMessage());
        }
    }

    /**
     * Loads an image from the specified file with requested dimensions.
     */
    public static Image load(File file, double requestedWidth, double requestedHeight) {
        if (file == null || !file.exists()) return null;

        String lowerName = file.getName().toLowerCase();
        boolean isWebP = lowerName.endsWith(".webp");

        // STRATEGY 1: Native JavaFX Load
        // We only try native if NOT WebP, or if it IS WebP but we want the original size.
        // (Native loader often crashes/fails when scaling WebP).
        if (!isWebP || (requestedWidth <= 0 && requestedHeight <= 0)) {
            try {
                String url = file.toURI().toString();
                // Load synchronously (backgroundLoading=false) to catch errors immediately
                Image img = new Image(url, requestedWidth, requestedHeight, true, true, false);

                if (img.isError() || img.getException() != null) {
                    // If native failed, fall through to fallback
                } else {
                    return img;
                }
            } catch (Exception ignored) {
                // Fallthrough to fallback
            }
        }

        // STRATEGY 2: Fallback (ImageIO with Subsampling)
        return loadFallback(file, requestedWidth, requestedHeight);
    }

    private static Image loadFallback(File file, double reqWidth, double reqHeight) {
        // NUCLEAR OPTION: Read all bytes into RAM first.
        // This is necessary to avoid ClosedByInterruptException (NIO) and ensure seeking works for all plugins.
        try {
            byte[] rawBytes = java.nio.file.Files.readAllBytes(file.toPath());

            try (ByteArrayInputStream bis = new ByteArrayInputStream(rawBytes);
                 ImageInputStream iis = new MemoryCacheImageInputStream(bis)) { // Force Memory Cache

                Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);

                // Force WebP Reader if auto-detect fails
                if (!readers.hasNext() && file.getName().toLowerCase().endsWith(".webp")) {
                    iis.seek(0);
                    readers = ImageIO.getImageReadersByMIMEType("image/webp");
                }

                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(iis, true, true); // ignoreMetadata = true

                        // OPTIMIZATION: Subsampling
                        // Calculate target dimensions BEFORE reading to avoid decoding full 4K images for thumbnails.
                        int sourceWidth = reader.getWidth(0);
                        int sourceHeight = reader.getHeight(0);

                        int targetW = (int) reqWidth;
                        int targetH = (int) reqHeight;

                        // Calculate missing dimensions if keeping aspect ratio
                        if (targetW <= 0 && targetH <= 0) {
                            targetW = sourceWidth;
                            targetH = sourceHeight;
                        } else if (targetW <= 0) {
                            targetW = (int) ((double) sourceWidth * targetH / sourceHeight);
                        } else if (targetH <= 0) {
                            targetH = (int) ((double) sourceHeight * targetW / sourceWidth);
                        }

                        ImageReadParam param = reader.getDefaultReadParam();

                        // If we are scaling down significantly, tell ImageIO to skip pixels
                        if (targetW < sourceWidth && targetH < sourceHeight) {
                            int subsampleW = sourceWidth / targetW;
                            int subsampleH = sourceHeight / targetH;
                            int subsampling = Math.min(subsampleW, subsampleH);

                            // Aggressive subsampling (e.g., read every 2nd or 4th pixel)
                            if (subsampling > 1) {
                                param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                            }
                        }

                        // Read with subsampling
                        BufferedImage bImg = reader.read(0, param);

                        if (bImg != null) {
                            // Final high-quality resize to exact requested dimensions
                            // (Subsampling gets us close, but not exact)
                            if (reqWidth > 0 || reqHeight > 0) {
                                bImg = resize(bImg, targetW, targetH);
                            }
                            return SwingFXUtils.toFXImage(bImg, null);
                        }
                    } finally {
                        reader.dispose();
                    }
                } else {
                    System.err.println("ImageLoader: No compatible reader found for " + file.getName());
                }
            }
        } catch (Throwable e) {
            System.err.println("Failed to load fallback: " + file.getName() + " - " + e.toString());
            e.printStackTrace();
        }
        return null;
    }

    private static BufferedImage resize(BufferedImage img, int newW, int newH) {
        BufferedImage dimg = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = dimg.createGraphics();

        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g2d.drawImage(img, 0, 0, newW, newH, null);
        g2d.dispose();
        return dimg;
    }
}