package com.nilsson.imagetoolbox.ui.views;

import com.nilsson.imagetoolbox.ui.components.ImageLoader;
import com.nilsson.imagetoolbox.ui.components.ThumbnailCache;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 A UI component that provides a horizontal scrolling filmstrip view for image files.
 It utilizes an ExecutorService for asynchronous thumbnail loading and includes
 robust image decoding to handle various file formats and potential loading errors.
 */
public class FilmstripView extends VBox {

    // ------------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------------

    private final ListView<File> listView;
    private final ExecutorService thumbnailPool;

    // ------------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------------

    public FilmstripView(ExecutorService thumbnailPool) {
        this.thumbnailPool = thumbnailPool;
        this.getStyleClass().add("filmstrip-view");
        this.setPrefHeight(140);
        this.setMinHeight(140);
        this.setMaxHeight(140);

        listView = new ListView<>();
        listView.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        listView.getStyleClass().add("filmstrip-list");
        VBox.setVgrow(listView, Priority.ALWAYS);

        listView.setCellFactory(param -> new FilmstripCell());

        this.getChildren().add(listView);
    }

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------

    public void setFiles(List<File> files) {
        listView.setItems(FXCollections.observableArrayList(files));
    }

    public void setSelectedIndex(int index) {
        if (index >= 0 && index < listView.getItems().size()) {
            listView.getSelectionModel().select(index);
            listView.scrollTo(index);
        }
    }

    public void setOnSelectionChanged(java.util.function.Consumer<Integer> listener) {
        listView.getSelectionModel().selectedIndexProperty().addListener((obs, old, val) -> {
            if (val != null && val.intValue() >= 0) {
                listener.accept(val.intValue());
            }
        });
    }

    // ------------------------------------------------------------------------
    // Image Loading Logic
    // ------------------------------------------------------------------------

    private Image loadRobustImage(File file, double targetHeight) {
        try {
            Image img = new Image(file.toURI().toString(), 0, targetHeight, true, true, false);
            if (img.isError()) {
                return SwingFXUtils.toFXImage(ImageIO.read(file), null);
            }
            return img;
        } catch (Exception ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------------
    // Inner Classes
    // ------------------------------------------------------------------------

    private class FilmstripCell extends ListCell<File> {
        private final ImageView imageView = new ImageView();
        private final VBox container = new VBox(imageView);
        private java.util.concurrent.Future<?> loadingTask;

        public FilmstripCell() {
            container.setAlignment(Pos.CENTER);
            container.setPadding(new Insets(5));

            selectedProperty().addListener((obs, old, isSelected) -> {
                if (isSelected) {
                    container.getStyleClass().add("filmstrip-selected");
                } else {
                    container.getStyleClass().remove("filmstrip-selected");
                }
            });

            imageView.setFitHeight(100);
            imageView.setPreserveRatio(true);
            setGraphic(container);
        }

        @Override
        protected void updateItem(File item, boolean empty) {
            super.updateItem(item, empty);

            if (loadingTask != null && !loadingTask.isDone()) {
                loadingTask.cancel(true);
            }

            if (empty || item == null) {
                setGraphic(null);
                imageView.setImage(null);
                setTooltip(null);
            } else {
                setGraphic(container);
                setTooltip(new Tooltip(item.getName()));

                Image cached = ThumbnailCache.get(item.getAbsolutePath());
                if (cached != null) {
                    imageView.setImage(cached);
                } else {
                    imageView.setImage(null);

                    loadingTask = thumbnailPool.submit(() -> {
                        if (Thread.currentThread().isInterrupted()) return;

                        Image img = ImageLoader.load(item, 0, 0);

                        if (img != null) {
                            com.nilsson.imagetoolbox.ui.components.ThumbnailCache.put(item.getAbsolutePath(), img);
                        }

                        Platform.runLater(() -> {
                            if (getItem() == item) {
                                imageView.setImage(img);
                            }
                        });
                    });
                }
            }
        }
    }
}