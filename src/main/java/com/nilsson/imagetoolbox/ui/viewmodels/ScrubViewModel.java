package com.nilsson.imagetoolbox.ui.viewmodels;

import de.saxsys.mvvmfx.ViewModel;
import javafx.beans.property.*;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * ViewModel for the "Scrub" utility, responsible for stripping metadata from images.
 * This class handles the loading of source images, managing the preview state,
 * and exporting a "clean" version of the image by converting it through
 * BufferedImage, which naturally discards generation parameters and EXIF data.
 */
public class ScrubViewModel implements ViewModel {

    // --- View State ---
    private final ObjectProperty<Image> previewImage = new SimpleObjectProperty<>();
    private final ObjectProperty<File> currentFile = new SimpleObjectProperty<>();
    private final StringProperty statusMessage = new SimpleStringProperty("Ready to scrub");
    private final BooleanProperty isExportDisabled = new SimpleBooleanProperty(true);
    private final BooleanProperty successFlag = new SimpleBooleanProperty(true);

    // --- Actions ---

    /**
     * Loads an image file into the view model for scrubbing.
     * * @param file The image file to load.
     */
    public void loadFile(File file) {
        if (file == null) return;
        try {
            Image img = new Image(file.toURI().toString());
            if (img.isError()) throw new Exception(img.getException());

            currentFile.set(file);
            previewImage.set(img);
            isExportDisabled.set(false);

            updateStatus("Loaded: " + file.getName(), true);
        } catch (Exception e) {
            updateStatus("Error loading image", false);
        }
    }

    /**
     * Exports the clean image to the specified destination.
     */
    public void exportCleanImage(File destination) {
        if (currentFile.get() == null || previewImage.get() == null || destination == null) return;

        try {
            BufferedImage bImg = SwingFXUtils.fromFXImage(previewImage.get(), null);
            String ext = getExtension(destination);
            if (ext.isEmpty()) ext = "png";

            ImageIO.write(bImg, ext, destination);
            updateStatus("Saved clean copy to " + destination.getName(), true);
        } catch (IOException e) {
            updateStatus("Export failed: " + e.getMessage(), false);
            e.printStackTrace();
        }
    }

    // --- Internal Helpers ---

    private void updateStatus(String msg, boolean success) {
        statusMessage.set(msg);
        successFlag.set(success);
    }

    private String getExtension(File f) {
        String name = f.getName();
        int lastIndexOf = name.lastIndexOf(".");
        if (lastIndexOf == -1) return "";
        return name.substring(lastIndexOf + 1);
    }

    // --- Property Accessors ---

    public ObjectProperty<Image> previewImageProperty() { return previewImage; }
    public StringProperty statusMessageProperty() { return statusMessage; }
    public BooleanProperty isExportDisabledProperty() { return isExportDisabled; }
    public BooleanProperty successFlagProperty() { return successFlag; }
    public ObjectProperty<File> currentFileProperty() { return currentFile; }
}