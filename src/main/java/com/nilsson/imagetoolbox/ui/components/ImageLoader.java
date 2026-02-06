package com.nilsson.imagetoolbox.ui.components;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.Iterator;

/**
 A specialized utility class for high-performance image loading in JavaFX, tailored for
 modern digital assets and AI-generated content.
 <p>The loader implements a dual-strategy approach to ensure maximum compatibility
 and performance:
 <ul>
 <li><b>Native Strategy:</b> Leverages built-in JavaFX platform loading for standard
 formats to take advantage of hardware acceleration.</li>
 <li><b>Fallback Strategy:</b> Utilizes AWT ImageIO with TwelveMonkeys plugins to handle
 niche formats (specifically WebP) and perform memory-efficient subsampling for thumbnails.</li>
 </ul>
 <p>Key technical features include:
 <ul>
 <li>Manual registration of WebP Service Provider Interfaces (SPI).</li>
 <li>Memory-cached input streams to prevent file locking and interrupt exceptions.</li>
 <li>Aggressive subsampling during the read phase to reduce heap pressure when loading large images.</li>
 <li>Bilinear interpolation for high-quality final scaling.</li>
 </ul>
 */
public class ImageLoader {

    private static final Logger logger = LoggerFactory.getLogger(ImageLoader.class);

    static {
        ImageIO.scanForPlugins();
        ImageIO.setUseCache(false);

        try {
            IIORegistry.getDefaultInstance().registerServiceProvider(new WebPImageReaderSpi());
        } catch (Throwable t) {
            logger.error("ImageLoader: Failed to manually register WebP SPI", t);
        }
    }

    // --- Primary Loading Logic ---

    /**
     Loads an image from the specified file with requested dimensions.
     */
    public static Image load(File file, double requestedWidth, double requestedHeight) {
        if (file == null || !file.exists()) return null;

        String lowerName = file.getName().toLowerCase();
        boolean isWebP = lowerName.endsWith(".webp");

        if (!isWebP || (requestedWidth <= 0 && requestedHeight <= 0)) {
            try {
                String url = file.toURI().toString();
                Image img = new Image(url, requestedWidth, requestedHeight, true, true, false);

                if (img.isError() || img.getException() != null) {
                    // Fallthrough to fallback
                    if (img.getException() != null) {
                        logger.debug("JavaFX load failed for {}, trying fallback. Error: {}", file.getName(), img.getException().getMessage());
                    }
                } else {
                    return img;
                }
            } catch (Exception e) {
                logger.debug("JavaFX load exception for {}, trying fallback. Error: {}", file.getName(), e.getMessage());
            }
        }

        return loadFallback(file, requestedWidth, requestedHeight);
    }

    // --- Fallback and Subsampling Strategy ---

    private static Image loadFallback(File file, double reqWidth, double reqHeight) {
        try {
            byte[] rawBytes = java.nio.file.Files.readAllBytes(file.toPath());

            try (ByteArrayInputStream bis = new ByteArrayInputStream(rawBytes);
                 ImageInputStream iis = new MemoryCacheImageInputStream(bis)) {

                Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);

                if (!readers.hasNext() && file.getName().toLowerCase().endsWith(".webp")) {
                    iis.seek(0);
                    readers = ImageIO.getImageReadersByMIMEType("image/webp");
                }

                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(iis, true, true);

                        int sourceWidth = reader.getWidth(0);
                        int sourceHeight = reader.getHeight(0);

                        int targetW = (int) reqWidth;
                        int targetH = (int) reqHeight;

                        if (targetW <= 0 && targetH <= 0) {
                            targetW = sourceWidth;
                            targetH = sourceHeight;
                        } else if (targetW <= 0) {
                            targetW = (int) ((double) sourceWidth * targetH / sourceHeight);
                        } else if (targetH <= 0) {
                            targetH = (int) ((double) sourceHeight * targetW / sourceWidth);
                        }

                        ImageReadParam param = reader.getDefaultReadParam();

                        if (targetW < sourceWidth && targetH < sourceHeight) {
                            int subsampleW = sourceWidth / targetW;
                            int subsampleH = sourceHeight / targetH;
                            int subsampling = Math.min(subsampleW, subsampleH);

                            if (subsampling > 1) {
                                param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                            }
                        }

                        BufferedImage bImg = reader.read(0, param);

                        if (bImg != null) {
                            if (reqWidth > 0 || reqHeight > 0) {
                                bImg = resize(bImg, targetW, targetH);
                            }
                            return SwingFXUtils.toFXImage(bImg, null);
                        }
                    } finally {
                        reader.dispose();
                    }
                } else {
                    logger.warn("ImageLoader: No compatible reader found for {}", file.getName());
                }
            }
        } catch (Exception e) {
            logger.error("Failed to load fallback: {}", file.getName(), e);
        } catch (Throwable t) {
            logger.error("Critical error loading fallback: {}", file.getName(), t);
        }
        return null;
    }

    // --- Image Processing Helpers ---

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