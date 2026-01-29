package com.nilsson.imagetoolbox.ui.views;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

public class FilmstripView extends ScrollPane {

    private final HBox filmStripBox;
    private final ExecutorService imageLoaderPool;
    private Consumer<Integer> onSelectionChanged;

    public FilmstripView(ExecutorService imageLoaderPool) {
        this.imageLoaderPool = imageLoaderPool;

        this.filmStripBox = new HBox(15);
        this.filmStripBox.setAlignment(Pos.CENTER_LEFT);
        this.filmStripBox.setPadding(new Insets(10));

        this.filmStripBox.setMinWidth(0);
        this.filmStripBox.setPrefWidth(Region.USE_COMPUTED_SIZE);
        this.filmStripBox.setMaxWidth(Double.MAX_VALUE);

        StackPane stripWrapper = new StackPane(filmStripBox);
        stripWrapper.setAlignment(Pos.CENTER_LEFT);
        stripWrapper.minWidthProperty().bind(this.widthProperty());

        this.setContent(stripWrapper);
        this.setFitToHeight(true);
        this.setFitToWidth(true);
        this.setVbarPolicy(ScrollBarPolicy.NEVER);
        this.setHbarPolicy(ScrollBarPolicy.AS_NEEDED);
        this.setPannable(true);
        this.setMinHeight(140);
        this.setPrefHeight(140);

        HBox.setHgrow(this, Priority.ALWAYS);
        VBox.setVgrow(this, Priority.NEVER);
    }

    public void setOnSelectionChanged(Consumer<Integer> callback) {
        this.onSelectionChanged = callback;
    }

    public void setFiles(List<File> files) {
        filmStripBox.getChildren().clear();
        if (files == null) return;

        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            int idx = i;

            ImageView thumb = new ImageView();
            thumb.setFitHeight(80);
            thumb.setFitWidth(80);
            thumb.setPreserveRatio(true);

            StackPane frame = new StackPane(thumb);
            frame.getStyleClass().add("film-frame");
            frame.getProperties().put("index", idx);
            // Default min size for frame so it appears even while loading
            frame.setMinSize(80, 80);

            frame.setOnMouseClicked(e -> {
                if (onSelectionChanged != null) onSelectionChanged.accept(idx);
                setSelectedIndex(idx);
            });

            imageLoaderPool.submit(() -> {
                Image img = loadRobustImage(f, 120);
                Platform.runLater(() -> thumb.setImage(img));
            });

            filmStripBox.getChildren().add(frame);
        }
    }

    public void setSelectedIndex(int index) {
        if (filmStripBox.getChildren().isEmpty()) return;

        for (Node node : filmStripBox.getChildren()) {
            int fileIndex = (int) node.getProperties().get("index");
            if (fileIndex == index) {
                if (!node.getStyleClass().contains("film-frame-selected")) {
                    node.getStyleClass().add("film-frame-selected");
                }
                centerNode(node);
            } else {
                node.getStyleClass().remove("film-frame-selected");
            }
        }
    }

    private void centerNode(Node node) {
        Bounds nodeBounds = node.getBoundsInParent();
        double nodeCenterX = (nodeBounds.getMinX() + nodeBounds.getMaxX()) / 2;
        double contentWidth = filmStripBox.getBoundsInLocal().getWidth();
        double viewportWidth = this.getViewportBounds().getWidth();

        if (contentWidth <= viewportWidth) {
            this.setHvalue(0.5);
            return;
        }

        double targetX = nodeCenterX - (viewportWidth / 2);
        double maxScroll = contentWidth - viewportWidth;
        double hValue = targetX / maxScroll;
        this.setHvalue(Math.max(0, Math.min(1, hValue)));
    }

    // Duplicate robust loader (or move to a static Utility class)
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