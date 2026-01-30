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

public class FilmstripView extends ScrollPane {

    private final HBox filmStripBox;
    private final ExecutorService imageLoaderPool;
    private Consumer<Integer> onSelectionChanged;

    // Layout Constants
    private static final double FRAME_SIZE = 80;
    private static final double IMAGE_SIZE = 72; // Smaller than frame to reveal highlight border
    private static final double GAP_SIZE = 15;
    private static final double TOTAL_ITEM_WIDTH = FRAME_SIZE + GAP_SIZE;

    // Track state
    private int selectedIndex = 0;
    private final List<StackPane> frameNodes = new ArrayList<>();

    public FilmstripView(ExecutorService imageLoaderPool) {
        this.imageLoaderPool = imageLoaderPool;

        this.filmStripBox = new HBox(GAP_SIZE);
        this.filmStripBox.setAlignment(Pos.CENTER_LEFT);

        // --- CAROUSEL LOGIC ---
        // Dynamically calculate padding so the first/last items can be centered.
        // Padding = (ViewportWidth / 2) - (ItemWidth / 2)
        this.filmStripBox.paddingProperty().bind(Bindings.createObjectBinding(() -> {
            double viewportW = this.getViewportBounds().getWidth();
            if (viewportW <= 0) return new Insets(10, 0, 10, 0); // Fallback

            double halfWidth = viewportW / 2.0;
            double halfItem = FRAME_SIZE / 2.0;
            double padding = Math.max(0, halfWidth - halfItem);

            return new Insets(10, padding, 10, padding);
        }, this.viewportBoundsProperty()));

        this.setContent(filmStripBox);

        // ScrollPane Settings
        this.setFitToHeight(true);
        this.setFitToWidth(false); // Essential for horizontal growth
        this.setVbarPolicy(ScrollBarPolicy.NEVER);
        this.setHbarPolicy(ScrollBarPolicy.NEVER); // Hide bar for clean look
        this.setPannable(true);

        this.setMinHeight(140);
        this.setPrefHeight(140);

        // Re-center when window resizes
        this.viewportBoundsProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.getWidth() > 0) {
                Platform.runLater(this::scrollToCurrentIndex);
            }
        });
    }

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
            thumb.setFitHeight(IMAGE_SIZE); // 72px
            thumb.setFitWidth(IMAGE_SIZE);  // 72px
            thumb.setPreserveRatio(true);

            // Frame is 80px. Image is 72px.
            // This leaves 4px of space on all sides for the CSS border/background to show.
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

        // Initial selection
        setSelectedIndex(0);
    }

    public void setSelectedIndex(int index) {
        if (frameNodes.isEmpty()) return;

        // Clamp index
        if (index < 0) index = 0;
        if (index >= frameNodes.size()) index = frameNodes.size() - 1;

        this.selectedIndex = index;

        // 1. Update Styles
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

        // 2. Scroll to Center (Async to allow layout pass)
        Platform.runLater(this::scrollToCurrentIndex);
    }

    private void scrollToCurrentIndex() {
        if (frameNodes.isEmpty()) return;

        double contentWidth = filmStripBox.getWidth();
        double viewportWidth = this.getViewportBounds().getWidth();
        double maxScroll = contentWidth - viewportWidth;

        if (maxScroll <= 0) {
            this.setHvalue(0); // If content fits, start at 0 (padding handles centering)
            return;
        }

        // Calculate exact position
        double leftPadding = ((Insets) filmStripBox.getPadding()).getLeft();

        // Start position of the item in the box
        double itemStart = leftPadding + (selectedIndex * TOTAL_ITEM_WIDTH);

        // Center position of the item
        double itemCenter = itemStart + (FRAME_SIZE / 2.0);

        // Center of the viewport
        double viewportCenter = viewportWidth / 2.0;

        // Calculate offset required
        double targetOffset = itemCenter - viewportCenter;

        // Convert to 0.0 - 1.0 range
        double hValue = targetOffset / maxScroll;

        this.setHvalue(Math.max(0, Math.min(1, hValue)));
    }

    private Image loadRobustImage(File file, double width) {
        try {
            Image img = new Image(file.toURI().toString(), width, 0, true, true, false);
            if(img.isError()) {
                return SwingFXUtils.toFXImage(ImageIO.read(file), null);
            }
            return img;
        } catch (Exception e) { return null; }
    }
}