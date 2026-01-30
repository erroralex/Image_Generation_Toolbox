package com.nilsson.imagetoolbox.ui.controllers;

import com.nilsson.imagetoolbox.ui.views.ScrubView;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.stage.FileChooser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Controller for the Metadata Scrubber module.
 * Handles loading images and exporting them without metadata.
 */
public class ScrubController implements ScrubView.ViewListener {

    private final ScrubView view;
    private File currentFile;
    private Image currentImage;

    public ScrubController(ScrubView view) {
        this.view = view;
    }

    @Override
    public void onFileDropped(File file) {
        loadFile(file);
    }

    @Override
    public void onBrowseRequested() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select Image to Scrub");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.webp"));

        // Use the view's scene window for the dialog
        File f = fc.showOpenDialog(view.getScene().getWindow());
        if (f != null) loadFile(f);
    }

    @Override
    public void onExportRequested() {
        if (currentFile == null || currentImage == null) return;

        FileChooser fc = new FileChooser();
        fc.setTitle("Save Clean Image");
        fc.setInitialFileName("clean_" + currentFile.getName());

        if (currentFile.getParentFile() != null) {
            fc.setInitialDirectory(currentFile.getParentFile());
        }

        File dest = fc.showSaveDialog(view.getScene().getWindow());
        if (dest != null) {
            try {
                // Converting FX Image -> BufferedImage -> Save removes metadata by default
                BufferedImage bImg = SwingFXUtils.fromFXImage(currentImage, null);
                ImageIO.write(bImg, "png", dest);

                view.showFeedback("Saved clean copy to " + dest.getName(), true);
            } catch (IOException e) {
                view.showFeedback("Export failed: " + e.getMessage(), false);
                e.printStackTrace();
            }
        }
    }

    private void loadFile(File file) {
        if (file == null) return;
        this.currentFile = file;
        try {
            // Load image
            this.currentImage = new Image(file.toURI().toString());

            // Update View
            view.displayImage(currentImage, file.getName());
        } catch (Exception e) {
            view.showFeedback("Error loading image", false);
        }
    }
}