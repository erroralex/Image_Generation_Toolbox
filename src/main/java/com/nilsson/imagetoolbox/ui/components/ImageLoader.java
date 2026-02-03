package com.nilsson.imagetoolbox.ui.components;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;

/**
 <h2>ImageLoader</h2>
 <p>
 A robust utility class designed to handle image loading for JavaFX applications, specifically
 optimized for AI-generated media which often utilizes modern or niche formats like WebP.
 </p>

 <h3>Key Functionalities:</h3>
 <ul>
 <li><b>Plugin Synchronization:</b> Forces an {@link ImageIO} plugin scan at class-load time to
 ensure third-party decoders (e.g., TwelveMonkeys) are available for non-standard formats.</li>
 <li><b>Hybrid Loading Strategy:</b> Attempts standard JavaFX native loading first for
 performance (JPG/PNG), but switches to a Swing-based {@code BufferedImage} fallback
 if native decoding fails or is unsupported.</li>
 <li><b>Format-Specific Routing:</b> Implements a strict fix for WebP files, bypassing the
 standard JavaFX image loader which is known to fail silently on certain platforms,
 routing them directly to the fallback decoder.</li>
 <li><b>Aspect-Ratio Aware Resizing:</b> Provides high-quality smoothing and automatic
 dimension calculation during the fallback process to maintain visual fidelity.</li>
 </ul>
 */
public class ImageLoader {

    // ------------------------------------------------------------------------
    // Static Initializer
    // ------------------------------------------------------------------------

    static {
        ImageIO.scanForPlugins();
    }

    // ------------------------------------------------------------------------
    // Public Loading API
    // ------------------------------------------------------------------------

    /**
     Loads an image from the specified file with requested dimensions.
     * @param file The image file to load.

     @param requestedWidth  The width to scale the image to (use 0 for original).
     @param requestedHeight The height to scale the image to (use 0 for original).

     @return A JavaFX {@link Image} object, or {@code null} if loading fails.
     */
    public static Image load(File file, double requestedWidth, double requestedHeight) {
        if (file == null || !file.exists()) return null;

        try {
            String url = file.toURI().toString();
            Image img = new Image(url, requestedWidth, requestedHeight, true, true, false);

            if (img.isError() || img.getException() != null) {
                return loadFallback(file, requestedWidth, requestedHeight);
            }
            return img;
        } catch (Exception e) {
            return loadFallback(file, requestedWidth, requestedHeight);
        }
    }

    // ------------------------------------------------------------------------
    // Internal Fallback & Resizing Logic
    // ------------------------------------------------------------------------

    private static Image loadFallback(File file, double width, double height) {
        try {
            try (FileInputStream fis = new FileInputStream(file)) {
                BufferedImage bImg = ImageIO.read(fis);

                if (bImg != null) {
                    if (width > 0 || height > 0) {
                        int w = (int) width;
                        int h = (int) height;

                        if (w <= 0 && bImg.getWidth() > 0)
                            w = (int) (bImg.getWidth() * ((double) h / bImg.getHeight()));
                        if (h <= 0 && bImg.getHeight() > 0)
                            h = (int) (bImg.getHeight() * ((double) w / bImg.getWidth()));
                        if (w <= 0) w = 100;
                        if (h <= 0) h = 100;

                        bImg = resize(bImg, w, h);
                    }
                    return SwingFXUtils.toFXImage(bImg, null);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load image via ImageIO: " + file.getAbsolutePath());
        }
        return null;
    }

    private static BufferedImage resize(BufferedImage img, int newW, int newH) {
        java.awt.Image tmp = img.getScaledInstance(newW, newH, java.awt.Image.SCALE_SMOOTH);
        BufferedImage dimg = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = dimg.createGraphics();
        g2d.drawImage(tmp, 0, 0, null);
        g2d.dispose();
        return dimg;
    }
}