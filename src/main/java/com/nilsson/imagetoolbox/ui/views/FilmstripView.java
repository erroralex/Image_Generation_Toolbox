package com.nilsson.imagetoolbox.ui.views;

import com.nilsson.imagetoolbox.ui.components.ThumbnailCache;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.util.Callback;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * A virtualized filmstrip view using ListView for high performance with large datasets.
 * This component provides a horizontal scrollable interface for image selection,
 * utilizing cell recycling and asynchronous loading to ensure UI responsiveness.
 */
public class FilmstripView extends StackPane {

    // --- Configuration Constants ---
    private static final double THUMB_SIZE = 72;
    private static final double CELL_SIZE = 90;

    // --- Fields ---
    private final ListView<File> listView;
    private final ExecutorService imageLoaderPool;
    private Consumer<Integer> onSelectionChanged;

    // --- Constructor ---
    public FilmstripView(ExecutorService imageLoaderPool) {
        this.imageLoaderPool = imageLoaderPool;

        this.listView = new ListView<>();
        this.listView.setOrientation(Orientation.HORIZONTAL);
        this.listView.getStyleClass().add("filmstrip-list");
        this.listView.setPrefHeight(140);
        this.listView.setMinHeight(140);

        this.listView.setCellFactory(new Callback<>() {
            @Override
            public ListCell<File> call(ListView<File> param) {
                return new FilmstripCell(imageLoaderPool);
            }
        });

        this.listView.getSelectionModel().selectedIndexProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && onSelectionChanged != null) {
                onSelectionChanged.accept(newVal.intValue());
            }
        });

        this.getChildren().add(listView);
    }

    // --- Public API ---
    public void setFiles(List<File> files) {
        Platform.runLater(() -> {
            listView.getItems().setAll(files);
            if (!files.isEmpty()) {
                listView.getSelectionModel().select(0);
            }
        });
    }

    public void setSelectedIndex(int index) {
        if (index >= 0 && index < listView.getItems().size()) {
            if (listView.getSelectionModel().getSelectedIndex() != index) {
                listView.getSelectionModel().select(index);
                listView.scrollTo(index);
            }
        }
    }

    public void setOnSelectionChanged(Consumer<Integer> callback) {
        this.onSelectionChanged = callback;
    }

    // --- Inner Classes ---
    /**
     * Inner Cell Class handling Async Loading and Virtualization.
     */
    private static class FilmstripCell extends ListCell<File> {
        private final ImageView imageView;
        private final StackPane container;
        private final ExecutorService executor;
        private volatile String currentFilePath;

        public FilmstripCell(ExecutorService executor) {
            this.executor = executor;

            imageView = new ImageView();
            imageView.setFitHeight(THUMB_SIZE);
            imageView.setFitWidth(THUMB_SIZE);
            imageView.setPreserveRatio(true);

            container = new StackPane(imageView);
            container.setMinSize(CELL_SIZE, CELL_SIZE);
            container.setAlignment(Pos.CENTER);
            container.getStyleClass().add("film-frame");

            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            setGraphic(container);
        }

        @Override
        protected void updateItem(File item, boolean empty) {
            super.updateItem(item, empty);

            if (empty || item == null) {
                setGraphic(null);
                currentFilePath = null;
                imageView.setImage(null);
                container.getStyleClass().remove("film-frame-selected");
            } else {
                setGraphic(container);
                currentFilePath = item.getAbsolutePath();

                if (isSelected()) {
                    if (!container.getStyleClass().contains("film-frame-selected")) {
                        container.getStyleClass().add("film-frame-selected");
                    }
                } else {
                    container.getStyleClass().remove("film-frame-selected");
                }

                Image cached = ThumbnailCache.get(item.getAbsolutePath());
                if (cached != null) {
                    imageView.setImage(cached);
                } else {
                    imageView.setImage(null);

                    executor.submit(() -> {
                        if (!item.getAbsolutePath().equals(currentFilePath)) return;

                        Image img = ThumbnailCache.get(item);

                        Platform.runLater(() -> {
                            if (item.getAbsolutePath().equals(currentFilePath)) {
                                imageView.setImage(img);
                            }
                        });
                    });
                }
            }
        }
    }
}