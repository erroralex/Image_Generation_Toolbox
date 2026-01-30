package com.nilsson.imagetoolbox.ui.views;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javax.imageio.ImageIO;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * A custom horizontal ScrollPane that displays a carousel-style filmstrip of image thumbnails.
 * Features dynamic padding to center the active item, background image loading for performance,
 * and synchronized selection state with visual feedback through CSS.
 */
public class FilmstripView extends ScrollPane {

    // --- Configuration & Constants ---
    private static final double FRAME_SIZE = 80;
    private static final double IMAGE_SIZE = 72;
    private static final double GAP_SIZE = 15;
    private static final double TOTAL_ITEM_WIDTH = FRAME_SIZE + GAP_SIZE;

    private final HBox filmStripBox;
    private final ExecutorService imageLoaderPool;
    private Consumer<Integer> onSelectionChanged;

    // --- State Management ---
    private int selectedIndex = 0;
    private final List<StackPane> frameNodes = new ArrayList<>();

    // --- Lifecycle & Initialization ---
    public FilmstripView(ExecutorService imageLoaderPool) {
        this.imageLoaderPool = imageLoaderPool;
        this.filmStripBox = new HBox(GAP_SIZE);
        this.filmStripBox.setAlignment(Pos.CENTER_LEFT);

        // Bind padding to dynamically center the first and last items based on viewport width
        this.filmStripBox.paddingProperty().bind(Bindings.createObjectBinding(() -> {
            double viewportW = this.getViewportBounds().getWidth();
            if (viewportW <= 0) return new Insets(10, 0, 10, 0);

            double halfWidth = viewportW / 2.0;
            double halfItem = FRAME_SIZE / 2.0;
            double padding = Math.max(0, halfWidth - halfItem);

            return new Insets(10, padding, 10, padding);
        }, this.viewportBoundsProperty()));

        this.setContent(filmStripBox);
        setupScrollPane();

        this.viewportBoundsProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.getWidth() > 0) {
                Platform.runLater(this::scrollToCurrentIndex);
            }
        });
    }

    private void setupScrollPane() {
        this.setFitToHeight(true);
        this.setFitToWidth(false);
        this.setVbarPolicy(ScrollBarPolicy.NEVER);
        this.setHbarPolicy(ScrollBarPolicy.NEVER);
        this.setPannable(true);
        this.setMinHeight(140);
        this.setPrefHeight(140);
    }

    // --- Public API ---
    public void setOnSelectionChanged(Consumer<Integer> callback) {
        this.onSelectionChanged = callback;
    }

    public void setFiles(List<File> files) {
        filmStripBox.getChildren().clear();
        frameNodes.clear();

        if (files == null || files.isEmpty()) return;

        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            int idx = i;

            ImageView thumb = new ImageView();
            thumb.setFitHeight(IMAGE_SIZE);
            thumb.setFitWidth(IMAGE_SIZE);
            thumb.setPreserveRatio(true);

            StackPane frame = new StackPane(thumb);
            frame.getStyleClass().add("film-frame");
            frame.setMinSize(FRAME_SIZE, FRAME_SIZE);
            frame.setMaxSize(FRAME_SIZE, FRAME_SIZE);

            frame.setOnMouseClicked(e -> {
                if (onSelectionChanged != null) onSelectionChanged.accept(idx);
                setSelectedIndex(idx);
            });

            imageLoaderPool.submit(() -> {
                Image img = loadRobustImage(f, 120);
                Platform.runLater(() -> thumb.setImage(img));
            });

            filmStripBox.getChildren().add(frame);
            frameNodes.add(frame);
        }

        setSelectedIndex(0);
    }

    public void setSelectedIndex(int index) {
        if (frameNodes.isEmpty()) return;

        if (index < 0) index = 0;
        if (index >= frameNodes.size()) index = frameNodes.size() - 1;

        this.selectedIndex = index;

        for (int i = 0; i < frameNodes.size(); i++) {
            Node n = frameNodes.get(i);
            if (i == index) {
                if (!n.getStyleClass().contains("film-frame-selected")) {
                    n.getStyleClass().add("film-frame-selected");
                }
            } else {
                n.getStyleClass().remove("film-frame-selected");
            }
        }

        Platform.runLater(this::scrollToCurrentIndex);
    }

    // --- Layout & Scrolling Logic ---
    private void scrollToCurrentIndex() {
        if (frameNodes.isEmpty()) return;

        double contentWidth = filmStripBox.getWidth();
        double viewportWidth = this.getViewportBounds().getWidth();
        double maxScroll = contentWidth - viewportWidth;

        if (maxScroll <= 0) {
            this.setHvalue(0);
            return;
        }

        double leftPadding = ((Insets) filmStripBox.getPadding()).getLeft();
        double itemStart = leftPadding + (selectedIndex * TOTAL_ITEM_WIDTH);
        double itemCenter = itemStart + (FRAME_SIZE / 2.0);
        double viewportCenter = viewportWidth / 2.0;
        double targetOffset = itemCenter - viewportCenter;

        double hValue = targetOffset / maxScroll;
        this.setHvalue(Math.max(0, Math.min(1, hValue)));
    }

    // --- Utility Methods ---
    private Image loadRobustImage(File file, double width) {
        try {
            Image img = new Image(file.toURI().toString(), width, 0, true, true, false);
            if (img.isError()) {
                return SwingFXUtils.toFXImage(ImageIO.read(file), null);
            }
            return img;
        } catch (Exception e) {
            return null;
        }
    }
}