package com.nilsson.imagetoolbox.ui.views;

import com.nilsson.imagetoolbox.data.UserDataManager;
import com.nilsson.imagetoolbox.service.MetadataService;
import com.nilsson.imagetoolbox.ui.FolderNav;
import com.nilsson.imagetoolbox.ui.ThumbnailCache;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.controlsfx.control.GridCell;
import org.controlsfx.control.GridView;

import javax.imageio.ImageIO;
import java.awt.Desktop;
import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ImageBrowserView extends BorderPane {

    public enum ViewMode { BROWSER, GALLERY, LIST }

    private final MetadataService metadataService = new MetadataService();
    private final UserDataManager dataManager = UserDataManager.getInstance();

    private final ExecutorService imageLoaderPool = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("ImageLoader");
        return t;
    });

    private final ExecutorService indexingPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("MetadataIndexer");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private List<File> currentFiles = new ArrayList<>();
    private int currentIndex = -1;
    private ViewMode currentViewMode = ViewMode.BROWSER;

    private final FolderNav folderNav;
    private final MetadataSidebar metadataSidebar;
    private final FilmstripView filmstripView;

    private StackPane centerContainer;
    private ImageView mainImageView;
    private StackPane singleViewContainer;
    private GridView<File> gridView;
    private ListView<File> fileListView;
    private StackPane overlayContainer;
    private TextArea rawMetaTextArea;

    // Zoom State for Main View
    private final Translate zoomTranslate = new Translate();
    private final Scale zoomScale = new Scale();

    public ImageBrowserView() {
        this.getStyleClass().add("image-browser-view");
        this.metadataSidebar = new MetadataSidebar(metadataService, this::showRawMetadata);
        this.filmstripView = new FilmstripView(imageLoaderPool);

        this.filmstripView.setOnSelectionChanged(index -> {
            this.currentIndex = index;
            loadImage(currentIndex);
        });

        this.folderNav = new FolderNav(
                this::loadFolder,
                this::loadCustomFileList,
                this::refreshCurrentViewIfNeeded
        );
        this.setLeft(folderNav);
        setupCenter();

        // Keys
        this.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getTarget() instanceof TextInputControl) return;
            switch (e.getCode()) {
                case LEFT: case A: navigate(-1); e.consume(); break;
                case RIGHT: case D: navigate(1); e.consume(); break;
                case DELETE: deleteCurrentImage(); e.consume(); break;
                case F: showFullScreen(getCurrentFile()); e.consume(); break;
                case ESCAPE: if (overlayContainer.isVisible()) overlayContainer.setVisible(false); e.consume(); break;
            }
        });
        setViewMode(ViewMode.BROWSER);
    }

    public FolderNav getFolderNav() { return folderNav; }

    public void restoreLastFolder() {
        File last = dataManager.getLastFolder();
        if(last != null && last.exists()) loadFolder(last);
    }

    private void refreshCurrentViewIfNeeded() {
        if (!currentFiles.isEmpty()) refreshAll();
    }

    public void loadCustomFileList(List<File> files) {
        this.currentFiles = files;
        refreshAll();
    }

    private void setupCenter() {
        centerContainer = new StackPane();
        centerContainer.getStyleClass().add("center-stack");

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(centerContainer.widthProperty());
        clip.heightProperty().bind(centerContainer.heightProperty());
        centerContainer.setClip(clip);

        mainImageView = new ImageView();
        mainImageView.setPreserveRatio(true);
        mainImageView.setSmooth(true);
        mainImageView.setPickOnBounds(true); // Ensure mouse events work on entire bounds
        mainImageView.fitWidthProperty().bind(centerContainer.widthProperty().subtract(40));
        mainImageView.fitHeightProperty().bind(centerContainer.heightProperty().subtract(40));

        // Transform Order: Scale first, then Translate.
        mainImageView.getTransforms().addAll(zoomScale, zoomTranslate);

        setupZoomAndPan(mainImageView, centerContainer, zoomTranslate, zoomScale, () -> showFullScreen(getCurrentFile()));

        singleViewContainer = new StackPane(mainImageView);
        singleViewContainer.setAlignment(Pos.CENTER);

        // --- Fullscreen Hint ---
        Label fullscreenHint = new Label("Click image for Fullscreen");
        fullscreenHint.setStyle("-fx-text-fill: rgba(255,255,255,0.3); -fx-font-size: 11px; -fx-padding: 5;");
        fullscreenHint.visibleProperty().bind(mainImageView.imageProperty().isNotNull());
        StackPane.setAlignment(fullscreenHint, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(fullscreenHint, new Insets(10));
        singleViewContainer.getChildren().add(fullscreenHint);

        // Grid View
        gridView = new GridView<>();
        gridView.setCellWidth(160);
        gridView.setCellHeight(160);
        gridView.setCellFactory(gv -> new ImageGridCell());

        fileListView = new ListView<>();
        fileListView.setCellFactory(lv -> new ListCell<File>() {
            @Override protected void updateItem(File item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) setText(null);
                else setText(item.getName());
            }
        });

        setupOverlay();
        this.setCenter(centerContainer);

        setupContextMenu(singleViewContainer, () -> getCurrentFile());
    }

    /**
     * Robust logic for Zoom (Anchor-Point based) and Pan.
     */
    private void setupZoomAndPan(ImageView view, Pane container, Translate translate, Scale scale, Runnable clickAction) {
        // Zoom Logic
        container.setOnScroll(e -> {
            if (e.isControlDown() || e.getDeltaY() != 0) {
                e.consume();

                double delta = e.getDeltaY();
                double zoomFactor = (delta > 0) ? 1.1 : 0.9;

                double oldScale = scale.getX();
                double newScale = oldScale * zoomFactor;

                // Clamp Zoom
                if (newScale < 0.1) newScale = 0.1;
                if (newScale > 20.0) newScale = 20.0;

                // 1. Capture the mouse position in Scene coordinates
                Point2D mouseScene = new Point2D(e.getSceneX(), e.getSceneY());

                // 2. Identify the specific pixel on the image under the mouse (before scaling)
                try {
                    Point2D pivotInImage = view.sceneToLocal(mouseScene);

                    // 3. Apply new Scale
                    scale.setX(newScale);
                    scale.setY(newScale);

                    // 4. Calculate where that pixel has moved to in the Scene (after scaling)
                    Point2D newPivotScene = view.localToScene(pivotInImage);

                    // 5. Calculate the drift (difference between mouse ptr and new pixel loc)
                    double dx = mouseScene.getX() - newPivotScene.getX();
                    double dy = mouseScene.getY() - newPivotScene.getY();

                    // 6. Adjust Translate to correct the drift
                    translate.setX(translate.getX() + dx);
                    translate.setY(translate.getY() + dy);

                } catch (Exception ex) {
                    // Fallback if sceneToLocal fails (e.g. node not in scene)
                    scale.setX(newScale);
                    scale.setY(newScale);
                }
            }
        });

        // Pan Logic
        class DragState {
            double startX, startY;
            double translateStartX, translateStartY;
            boolean isDragging = false;
        }
        final DragState state = new DragState();

        container.setOnMousePressed(e -> {
            // Allow panning with Middle mouse OR Left mouse (if not double click)
            if (e.getButton() == MouseButton.MIDDLE || e.getButton() == MouseButton.PRIMARY) {
                state.startX = e.getSceneX();
                state.startY = e.getSceneY();
                state.translateStartX = translate.getX();
                state.translateStartY = translate.getY();
                state.isDragging = false;
            }
        });

        container.setOnMouseDragged(e -> {
            if (e.getButton() == MouseButton.MIDDLE || (e.getButton() == MouseButton.PRIMARY && scale.getX() > 1.0)) {
                double deltaX = e.getSceneX() - state.startX;
                double deltaY = e.getSceneY() - state.startY;

                // Threshold to differentiate Click vs Drag (3 pixels)
                if (Math.abs(deltaX) > 3 || Math.abs(deltaY) > 3) {
                    state.isDragging = true;
                    container.setCursor(javafx.scene.Cursor.MOVE);
                    translate.setX(state.translateStartX + deltaX);
                    translate.setY(state.translateStartY + deltaY);
                }
            }
        });

        container.setOnMouseReleased(e -> {
            container.setCursor(javafx.scene.Cursor.DEFAULT);
            // Handle Click (Only if NOT a drag)
            if (!state.isDragging && e.getButton() == MouseButton.PRIMARY) {
                if (clickAction != null) {
                    clickAction.run();
                }
            }
        });

        // Reset Zoom
        container.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                scale.setX(1);
                scale.setY(1);
                translate.setX(0);
                translate.setY(0);
            }
        });
    }

    private void resetZoom() {
        zoomScale.setX(1);
        zoomScale.setY(1);
        zoomTranslate.setX(0);
        zoomTranslate.setY(0);
    }

    private void setupOverlay() {
        overlayContainer = new StackPane();
        overlayContainer.setVisible(false);
        overlayContainer.setStyle("-fx-background-color: rgba(0,0,0,0.85);");

        rawMetaTextArea = new TextArea();
        rawMetaTextArea.setEditable(false);
        rawMetaTextArea.getStyleClass().add("raw-meta-area");
        rawMetaTextArea.setMaxSize(800, 600);

        Button closeBtn = new Button("Close");
        closeBtn.setOnAction(e -> overlayContainer.setVisible(false));

        VBox box = new VBox(10, rawMetaTextArea, closeBtn);
        box.setAlignment(Pos.CENTER);
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        overlayContainer.getChildren().add(box);
    }

    public void setViewMode(ViewMode mode) {
        this.currentViewMode = mode;
        centerContainer.getChildren().clear();
        Node contentNode = (mode == ViewMode.GALLERY) ? gridView : (mode == ViewMode.LIST ? fileListView : singleViewContainer);

        if (mode == ViewMode.GALLERY) gridView.setItems(FXCollections.observableArrayList(currentFiles));
        if (mode == ViewMode.LIST) fileListView.getItems().setAll(currentFiles);

        centerContainer.getChildren().addAll(contentNode, overlayContainer);

        if (mode == ViewMode.BROWSER) {
            this.setBottom(filmstripView);
            this.setRight(metadataSidebar);
            if (currentIndex >= 0) loadImage(currentIndex);
        } else {
            this.setBottom(null);
            this.setRight(null);
        }
    }

    private File getCurrentFile() {
        if (currentIndex >= 0 && currentIndex < currentFiles.size()) return currentFiles.get(currentIndex);
        return null;
    }

    private void loadImage(int index) {
        if (index < 0 || index >= currentFiles.size()) return;
        File file = currentFiles.get(index);
        this.currentIndex = index;

        resetZoom(); // Reset zoom when changing images

        imageLoaderPool.submit(() -> {
            Image img = loadRobustImage(file, 0);
            Platform.runLater(() -> mainImageView.setImage(img));
        });

        metadataSidebar.setFile(file);
        filmstripView.setSelectedIndex(index);
        Platform.runLater(() -> centerContainer.requestFocus());
    }

    public void loadFolder(File folder) {
        if (folder == null) return;
        dataManager.setLastFolder(folder);

        Task<List<File>> task = new Task<>() {
            @Override protected List<File> call() {
                File[] files = folder.listFiles((dir, name) -> {
                    String low = name.toLowerCase();
                    return low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".webp");
                });
                if (files == null) return new ArrayList<>();
                return Arrays.stream(files)
                        .sorted((f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()))
                        .collect(Collectors.toList());
            }
        };

        task.setOnSucceeded(e -> {
            currentFiles = task.getValue();
            refreshAll();

            indexingPool.submit(() -> {
                for (File f : currentFiles) {
                    if (!dataManager.hasCachedMetadata(f)) {
                        Map<String, String> meta = metadataService.getExtractedData(f);
                        if (!meta.isEmpty()) {
                            dataManager.cacheMetadata(f, meta);
                            performAutoTagging(f, meta);
                        }
                    }
                }
            });
        });

        new Thread(task).start();
    }

    private void performAutoTagging(File file, Map<String, String> meta) {
        if (meta.containsKey("Model")) {
            String model = meta.get("Model");
            if (model != null && !model.isEmpty()) dataManager.addTag(file, "Model: " + model);
        }
        if (meta.containsKey("Loras")) {
            String loras = meta.get("Loras");
            if (loras != null && !loras.isEmpty()) {
                Pattern p = Pattern.compile("<lora:([^:>]+)");
                Matcher m = p.matcher(loras);
                while(m.find()) dataManager.addTag(file, "Lora: " + m.group(1));
            }
        }
    }

    private void refreshAll() {
        currentIndex = 0;
        filmstripView.setFiles(currentFiles);
        gridView.setItems(FXCollections.observableArrayList(currentFiles));
        fileListView.getItems().setAll(currentFiles);
        if (!currentFiles.isEmpty() && currentViewMode == ViewMode.BROWSER) loadImage(0);
        else mainImageView.setImage(null);
    }

    private void navigate(int dir) {
        if (currentFiles.isEmpty()) return;
        int newIndex = currentIndex + dir;
        if (newIndex >= 0 && newIndex < currentFiles.size()) {
            loadImage(newIndex);
        }
    }

    private void deleteCurrentImage() {
        File f = getCurrentFile();
        if (f == null) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Delete " + f.getName() + "?", ButtonType.YES, ButtonType.NO);
        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                if (f.delete()) {
                    currentFiles.remove(currentIndex);
                    refreshAll();
                }
            }
        });
    }

    private void showRawMetadata(File file) {
        if (file == null) return;
        rawMetaTextArea.setText(metadataService.getRawMetadata(file));
        overlayContainer.setVisible(true);
    }

    // ==================================================================================
    // FULLSCREEN VIEWER (With Zoom Support)
    // ==================================================================================

    private void showFullScreen(File file) {
        if (file == null) return;
        Stage fsStage = new Stage();
        fsStage.initStyle(StageStyle.TRANSPARENT);
        fsStage.initModality(Modality.APPLICATION_MODAL);

        ImageView view = new ImageView();
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.setPickOnBounds(true);

        Image img = loadRobustImage(file, 0);
        view.setImage(img);

        // Fullscreen specific transforms
        Translate fsTranslate = new Translate();
        Scale fsScale = new Scale();
        view.getTransforms().addAll(fsScale, fsTranslate);

        StackPane root = new StackPane(view);
        root.setStyle("-fx-background-color: black;");
        root.setAlignment(Pos.CENTER);

        // Pass null for clickAction so we don't trigger anything on single click in FS
        setupZoomAndPan(view, root, fsTranslate, fsScale, null);

        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        view.fitWidthProperty().bind(fsStage.widthProperty());
        view.fitHeightProperty().bind(fsStage.heightProperty());

        Scene scene = new Scene(root);
        scene.setFill(Color.BLACK);

        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) fsStage.close();
        });

        fsStage.setScene(scene);
        fsStage.setFullScreen(true);
        fsStage.setFullScreenExitHint("Press ESC to exit Fullscreen");
        fsStage.show();
    }

    private Image loadRobustImage(File file, double width) {
        try {
            Image img = new Image(file.toURI().toString(), width, 0, true, true, false);
            if (img.isError()) return SwingFXUtils.toFXImage(ImageIO.read(file), null);
            return img;
        } catch (Exception ex) { return null; }
    }

    // ==================================================================================
    // CONTEXT MENU
    // ==================================================================================

    private void setupContextMenu(Node node, java.util.function.Supplier<File> fileSupplier) {
        ContextMenu cm = new ContextMenu();

        MenuItem open = new MenuItem("Open in Default App");
        open.setOnAction(e -> {
            File f = fileSupplier.get();
            if (f != null) try { Desktop.getDesktop().open(f); } catch (Exception ex) { ex.printStackTrace(); }
        });

        MenuItem edit = new MenuItem("Open in External Editor");
        edit.setOnAction(e -> {
            File f = fileSupplier.get();
            if (f != null && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.EDIT)) {
                try { Desktop.getDesktop().edit(f); } catch (Exception ex) { ex.printStackTrace(); }
            } else {
                new Alert(Alert.AlertType.WARNING, "Edit action not supported on this platform.").show();
            }
        });

        MenuItem rename = new MenuItem("Rename");
        rename.setOnAction(e -> handleRename(fileSupplier.get()));

        MenuItem fullScreen = new MenuItem("View Fullscreen (F)");
        fullScreen.setOnAction(e -> showFullScreen(fileSupplier.get()));

        cm.getItems().addAll(open, edit, rename, new SeparatorMenuItem(), fullScreen);

        node.setOnContextMenuRequested(e -> {
            if (fileSupplier.get() != null) cm.show(node, e.getScreenX(), e.getScreenY());
        });
    }

    private void handleRename(File file) {
        if (file == null) return;
        TextInputDialog dialog = new TextInputDialog(file.getName());
        dialog.setTitle("Rename File");
        dialog.setHeaderText("Enter new name:");
        dialog.showAndWait().ifPresent(name -> {
            File target = new File(file.getParentFile(), name);
            if (file.renameTo(target)) {
                loadFolder(file.getParentFile());
            } else {
                new Alert(Alert.AlertType.ERROR, "Could not rename file.").show();
            }
        });
    }

    // ==================================================================================
    // GRID CELL
    // ==================================================================================

    private class ImageGridCell extends GridCell<File> {
        private final StackPane container;
        private final ImageView imageView;

        public ImageGridCell() {
            imageView = new ImageView();
            imageView.setFitWidth(150);
            imageView.setFitHeight(150);
            imageView.setPreserveRatio(true);

            container = new StackPane(imageView);
            container.setPrefSize(160, 160);
            container.getStyleClass().add("grid-cell");
            container.setStyle("-fx-border-color: #444; -fx-border-width: 1;");
            setGraphic(container);

            setupContextMenu(container, this::getItem);

            container.setOnMouseClicked(e -> {
                if (getItem() == null) return;
                currentIndex = getIndex();
                if (e.getClickCount() == 2) setViewMode(ViewMode.BROWSER);
            });
        }

        @Override
        protected void updateItem(File item, boolean empty) {
            super.updateItem(item, empty);

            if (empty || item == null) {
                imageView.setImage(null);
                container.setVisible(false);
            } else {
                container.setVisible(true);

                Image cached = ThumbnailCache.get(item.getAbsolutePath());
                if (cached != null) {
                    imageView.setImage(cached);
                } else {
                    imageView.setImage(null);
                    imageLoaderPool.submit(() -> {
                        Image img = loadRobustImage(item, 200);
                        if (img != null) ThumbnailCache.put(item.getAbsolutePath(), img);

                        Platform.runLater(() -> {
                            if (Objects.equals(item, getItem())) {
                                imageView.setImage(img);
                            }
                        });
                    });
                }
            }
        }
    }
}