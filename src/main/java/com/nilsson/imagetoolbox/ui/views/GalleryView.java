package com.nilsson.imagetoolbox.ui.views;

import com.nilsson.imagetoolbox.ui.components.ImageLoader;
import com.nilsson.imagetoolbox.ui.components.ThumbnailCache;
import com.nilsson.imagetoolbox.ui.viewmodels.ImageBrowserViewModel;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import org.controlsfx.control.GridCell;
import org.controlsfx.control.GridView;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

/**
 <h2>GalleryView</h2>
 <p>
 A custom UI component that provides a fluid, grid-based view of image files.
 It integrates with the {@link ImageBrowserViewModel} to display filtered content
 and handles high-performance thumbnail rendering via a thread pool and cache.
 </p>

 <h3>Key Functionalities:</h3>
 <ul>
 <li><b>Responsive Grid:</b> Built on {@link GridView} to allow dynamic resizing of thumbnails
 while maintaining performance via cell reuse.</li>
 <li><b>Keyboard Navigation:</b> Custom event filters manage focus and allow users to navigate
 the library using arrow keys and Enter for selection.</li>
 <li><b>Async Loading:</b> Thumbnails are loaded on background threads to prevent UI stutter,
 utilizing {@link ThumbnailCache} to avoid redundant I/O.</li>
 <li><b>Visual Feedback:</b> Each image card displays metadata labels, star ratings, and
 selection states using CSS PseudoClasses.</li>
 <li><b>Infinite Scroll:</b> Automatically triggers pagination when the user scrolls near the bottom.</li>
 <li><b>Drag-and-Drop Export:</b> Allows users to drag images from the gallery to external applications.</li>
 </ul>
 * @author Nilsson
 */
public class GalleryView extends StackPane {

    // ------------------------------------------------------------------------
    // Fields & State
    // ------------------------------------------------------------------------

    private final GridView<File> gridView;
    private final ExecutorService thumbnailPool;
    private final ImageBrowserViewModel viewModel;
    private final BiConsumer<File, Boolean> onFileSelected;

    private final DoubleProperty tileSize = new SimpleDoubleProperty(160);
    private ScrollBar verticalScrollBar;

    // ------------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------------

    /**
     Constructs a new GalleryView, initializing the grid layout and data observers.
     * @param viewModel     The primary view model for data binding.

     @param thumbnailPool  The executor service for asynchronous thumbnail processing.
     @param onFileSelected A callback handling file selection and double-click actions.
     */
    public GalleryView(ImageBrowserViewModel viewModel, ExecutorService thumbnailPool, BiConsumer<File, Boolean> onFileSelected) {
        this.viewModel = viewModel;
        this.thumbnailPool = thumbnailPool;
        this.onFileSelected = onFileSelected;

        this.getStyleClass().add("gallery-view");
        this.setFocusTraversable(true);

        gridView = new GridView<>();
        gridView.setStyle("-fx-background-color: transparent;");
        gridView.setCellFactory(gv -> new ImageGridCell());
        gridView.setFocusTraversable(false);

        gridView.cellWidthProperty().bind(tileSize);
        gridView.cellHeightProperty().bind(tileSize);
        gridView.setHorizontalCellSpacing(10);
        gridView.setVerticalCellSpacing(10);

        this.getChildren().add(gridView);

        viewModel.getFilteredFiles().addListener((ListChangeListener<File>) c -> {
            Platform.runLater(() -> {
                gridView.getItems().setAll(viewModel.getFilteredFiles());
            });
        });

        this.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyNavigation);
        this.setOnMousePressed(e -> this.requestFocus());

        gridView.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin != null) {
                Platform.runLater(this::findAndAttachScrollBarListener);
            }
        });
    }

    // ------------------------------------------------------------------------
    // Scroll & Viewport Logic
    // ------------------------------------------------------------------------

    /**
     Locates the vertical scrollbar within the GridView's internal VirtualFlow
     and attaches listeners for infinite scrolling.
     */
    private void findAndAttachScrollBarListener() {
        if (verticalScrollBar != null) return;

        Set<Node> scrollBars = this.lookupAll(".scroll-bar");
        for (Node node : scrollBars) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL) {
                this.verticalScrollBar = bar;
                bar.valueProperty().addListener((obs, oldVal, newVal) -> {
                    if (newVal.doubleValue() >= bar.getMax() * 0.8) {
                        viewModel.loadNextPage();
                    }
                });
                break;
            }
        }
    }

    // ------------------------------------------------------------------------
    // Interaction Handlers
    // ------------------------------------------------------------------------

    /**
     Processes keyboard input for grid navigation and item selection.
     * @param e The KeyEvent triggered by the user.
     */
    private void handleKeyNavigation(KeyEvent e) {
        if (gridView.getItems().isEmpty()) return;

        File current = viewModel.getSelectedImage().get();
        int currentIndex = (current != null) ? viewModel.getFilteredFiles().indexOf(current) : 0;
        if (currentIndex == -1) currentIndex = 0;

        double gridWidth = gridView.getWidth();
        if (gridWidth <= 0) gridWidth = gridView.getPrefWidth();

        double cellW = gridView.getCellWidth() + gridView.getHorizontalCellSpacing();
        if (cellW <= 10) cellW = 160;

        int columns = Math.max(1, (int) (gridWidth / cellW));
        int newIndex = currentIndex;

        switch (e.getCode()) {
            case RIGHT, D -> newIndex++;
            case LEFT, A -> newIndex--;
            case DOWN, S -> newIndex += columns;
            case UP, W -> newIndex -= columns;
            case ENTER -> {
                onFileSelected.accept(gridView.getItems().get(currentIndex), true);
                e.consume();
                return;
            }
            default -> {
                return;
            }
        }

        if (newIndex < 0) newIndex = 0;
        if (newIndex >= gridView.getItems().size()) newIndex = gridView.getItems().size() - 1;

        if (newIndex != currentIndex) {
            File newFile = gridView.getItems().get(newIndex);
            onFileSelected.accept(newFile, false);

            if (newIndex >= gridView.getItems().size() - columns) {
                viewModel.loadNextPage();
            }
        }
        e.consume();
    }

    /**
     @return The property governing the width and height of grid cells.
     */
    public DoubleProperty tileSizeProperty() {
        return tileSize;
    }

    // ------------------------------------------------------------------------
    // ImageGridCell Implementation
    // ------------------------------------------------------------------------

    /**
     Inner class representing an individual item within the GridView.
     Handles thumbnail loading, selection state, and drag-and-drop export.
     */
    private class ImageGridCell extends GridCell<File> {
        private final StackPane container;
        private final ImageView imageView;
        private final VBox overlay;
        private final HBox starRow;
        private final Label modelLabel;
        private final PseudoClass SELECTED_PSEUDO_CLASS = PseudoClass.getPseudoClass("selected");
        private final ChangeListener<File> selectionListener;

        public ImageGridCell() {
            container = new StackPane();
            container.getStyleClass().add("image-card");
            container.setAlignment(Pos.CENTER);

            imageView = new ImageView();
            imageView.setPreserveRatio(true);

            selectionListener = (obs, oldVal, newVal) -> {
                File item = getItem();
                boolean isSelected = item != null && Objects.equals(item, newVal);
                container.pseudoClassStateChanged(SELECTED_PSEUDO_CLASS, isSelected);
            };

            container.setOnMouseClicked(e -> {
                GalleryView.this.requestFocus();
                File file = getItem();
                if (file != null) {
                    onFileSelected.accept(file, e.getClickCount() == 2);
                }
                e.consume();
            });

            container.setOnDragDetected(e -> {
                File file = getItem();
                if (file != null) {
                    Dragboard db = container.startDragAndDrop(TransferMode.COPY);
                    ClipboardContent content = new ClipboardContent();
                    content.putFiles(Collections.singletonList(file));
                    db.setContent(content);

                    Image dragImage = imageView.getImage();
                    if (dragImage != null) {
                        db.setDragView(dragImage);
                    }

                    e.consume();
                }
            });

            overlay = new VBox(0);
            overlay.getStyleClass().add("card-overlay");
            overlay.setAlignment(Pos.BOTTOM_LEFT);
            overlay.setMinHeight(45);
            overlay.setMaxHeight(45);
            overlay.setPadding(new Insets(4, 8, 4, 8));
            overlay.setMouseTransparent(true);
            StackPane.setAlignment(overlay, Pos.BOTTOM_CENTER);

            starRow = new HBox(2);
            modelLabel = new Label();
            modelLabel.getStyleClass().add("card-label");
            overlay.getChildren().addAll(starRow, modelLabel);
            container.getChildren().addAll(imageView, overlay);

            setGraphic(container);
        }

        @Override
        protected void updateItem(File file, boolean empty) {
            viewModel.getSelectedImage().removeListener(selectionListener);

            super.updateItem(file, empty);

            if (empty || file == null) {
                setGraphic(null);
                imageView.fitWidthProperty().unbind();
                imageView.fitHeightProperty().unbind();
            } else {
                viewModel.getSelectedImage().addListener(selectionListener);

                if (getGridView() != null) {
                    imageView.fitWidthProperty().bind(getGridView().cellWidthProperty().subtract(10));
                    imageView.fitHeightProperty().bind(getGridView().cellHeightProperty().subtract(10));
                    container.setPrefSize(getGridView().getCellWidth(), getGridView().getCellHeight());
                    container.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
                    container.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
                }
                setGraphic(container);

                Image cached = ThumbnailCache.get(file.getAbsolutePath());
                if (cached != null) {
                    imageView.setImage(cached);
                } else {
                    imageView.setImage(null);
                    thumbnailPool.submit(() -> {
                        Image img = ImageLoader.load(file, 0, 0);
                        if (img != null) {
                            ThumbnailCache.put(file.getAbsolutePath(), img);
                            Platform.runLater(() -> {
                                if (Objects.equals(getItem(), file)) imageView.setImage(img);
                            });
                        }
                    });
                }

                starRow.getChildren().clear();
                int rating = viewModel.getRatingForFile(file);
                if (rating > 0) {
                    for (int i = 1; i <= 5; i++) {
                        boolean filled = i <= rating;
                        FontIcon star = new FontIcon(filled ? "fa-star" : "fa-star-o");
                        if (filled) {
                            star.setStyle("-fx-fill: #FFD700 !important; -fx-icon-color: #FFD700 !important;");
                        } else {
                            star.setStyle(null);
                        }
                        starRow.getChildren().add(star);
                    }
                }

                String model = viewModel.getMetadataValue(file, "Model");
                modelLabel.setText((model == null || model.isBlank()) ? file.getName() : model);

                boolean isSelected = Objects.equals(file, viewModel.getSelectedImage().get());
                container.pseudoClassStateChanged(SELECTED_PSEUDO_CLASS, isSelected);
            }
        }
    }
}