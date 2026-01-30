package com.nilsson.imagetoolbox.ui.views;

import com.nilsson.imagetoolbox.ui.components.FolderNav;
import com.nilsson.imagetoolbox.ui.components.ThumbnailCache;
import com.nilsson.imagetoolbox.ui.viewmodels.ImageBrowserViewModel;
import de.saxsys.mvvmfx.InjectViewModel;
import de.saxsys.mvvmfx.JavaView;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
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
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.controlsfx.control.GridCell;
import org.controlsfx.control.GridView;

import javax.imageio.ImageIO;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The main browser view for the application, providing a multifaceted interface for
 * exploring image directories.
 * * Supports three view modes: Browser (Single image focus), Gallery (Grid), and List.
 * Features advanced image interactions including asynchronous thumbnail loading,
 * coordinate-aware zooming/panning, and full-screen presentation.
 */
public class ImageBrowserView extends BorderPane implements JavaView<ImageBrowserViewModel>, Initializable {

    // --- State & Configuration ---
    public enum ViewMode { BROWSER, GALLERY, LIST }

    @InjectViewModel
    private ImageBrowserViewModel viewModel;

    private final ExecutorService thumbnailPool = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("ThumbnailLoader");
        return t;
    });

    private Future<?> currentLoadingTask;
    private List<File> currentFiles = new ArrayList<>();
    private int currentIndex = -1;
    private ViewMode currentViewMode = ViewMode.BROWSER;

    // --- Components ---
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

    // --- Transformation Properties ---
    private final Translate zoomTranslate = new Translate();
    private final Scale zoomScale = new Scale();

    // --- Initialization ---

    public ImageBrowserView() {
        this.getStyleClass().add("image-browser-view");

        this.metadataSidebar = new MetadataSidebar(new MetadataSidebar.SidebarListener() {
            @Override public void onToggleStar(File file) { viewModel.toggleStar(); }
            @Override public void onAddTag(File file, String tag) { viewModel.addTag(tag); }
            @Override public void onRemoveTag(File file, String tag) { viewModel.removeTag(tag); }
            @Override public void onOpenRaw(File file) {
                String raw = viewModel.getRawMetadata(file);
                showRawMetadataPopup(raw);
            }
        });

        this.filmstripView = new FilmstripView(thumbnailPool);
        this.filmstripView.setOnSelectionChanged(index -> {
            this.currentIndex = index;
            loadImage(currentIndex);
        });

        this.folderNav = new FolderNav(new FolderNav.FolderNavListener() {
            @Override public void onFolderSelected(File folder) { viewModel.loadFolder(folder); }
            @Override public void onSearch(String query) { viewModel.search(query); }
            @Override public void onShowStarred() { viewModel.loadStarred(); }
            @Override public void onPinFolder(File folder) {
                viewModel.pinFolder(folder);
                refreshSidebar();
            }
            @Override public void onUnpinFolder(File folder) {
                viewModel.unpinFolder(folder);
                refreshSidebar();
            }
            @Override public void onFilesMoved() { }
        });

        this.setLeft(folderNav);
        setupCenterLayout();
        setupInputHandlers();
        setViewMode(ViewMode.BROWSER);
    }

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        refreshSidebar();

        viewModel.currentFilesProperty().addListener((ListChangeListener<File>) c -> {
            this.currentFiles = new ArrayList<>(viewModel.currentFilesProperty());
            refreshCurrentView();
        });

        viewModel.activeMetadataProperty().addListener((obs, old, meta) -> updateSidebar());
        viewModel.activeTagsProperty().addListener((obs, old, tags) -> updateSidebar());
        viewModel.activeStarredProperty().addListener((obs, old, isStarred) -> updateSidebar());

        Platform.runLater(() -> {
            File lastFolder = viewModel.getLastFolder();
            if (lastFolder != null && lastFolder.exists()) {
                viewModel.loadFolder(lastFolder);
            }
        });
    }

    // --- UI Layout Construction ---

    private void setupCenterLayout() {
        centerContainer = new StackPane();
        centerContainer.getStyleClass().add("center-stack");

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(centerContainer.widthProperty());
        clip.heightProperty().bind(centerContainer.heightProperty());
        centerContainer.setClip(clip);

        mainImageView = new ImageView();
        mainImageView.setPreserveRatio(true);
        mainImageView.setSmooth(true);
        mainImageView.setPickOnBounds(true);
        mainImageView.fitWidthProperty().bind(centerContainer.widthProperty().subtract(40));
        mainImageView.fitHeightProperty().bind(centerContainer.heightProperty().subtract(40));
        mainImageView.getTransforms().addAll(zoomScale, zoomTranslate);

        setupZoomAndPan(mainImageView, centerContainer, zoomTranslate, zoomScale, () -> showFullScreen(getCurrentFile()));

        singleViewContainer = new StackPane(mainImageView);
        singleViewContainer.setAlignment(Pos.CENTER);

        Label hint = new Label("Click image for Fullscreen");
        hint.setStyle("-fx-text-fill: rgba(255,255,255,0.3); -fx-font-size: 11px; -fx-padding: 5;");
        hint.visibleProperty().bind(mainImageView.imageProperty().isNotNull());
        StackPane.setAlignment(hint, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(hint, new Insets(10));
        singleViewContainer.getChildren().add(hint);

        gridView = new GridView<>();
        gridView.setCellWidth(160);
        gridView.setCellHeight(160);
        gridView.setCellFactory(gv -> new ImageGridCell());

        fileListView = new ListView<>();
        fileListView.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(File item, boolean empty) {
                super.updateItem(item, empty);
                setText((empty || item == null) ? null : item.getName());
            }
        });

        setupOverlay();
        this.setCenter(centerContainer);
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

    // --- View Management ---

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
            mainImageView.setImage(null);
            if (currentLoadingTask != null) currentLoadingTask.cancel(true);
            this.setBottom(null);
            this.setRight(null);
        }
    }

    private void refreshCurrentView() {
        currentIndex = 0;
        filmstripView.setFiles(currentFiles);
        if (gridView != null) gridView.setItems(FXCollections.observableArrayList(currentFiles));
        if (fileListView != null) fileListView.getItems().setAll(currentFiles);
        if (!currentFiles.isEmpty() && currentViewMode == ViewMode.BROWSER) {
            loadImage(0);
        } else {
            mainImageView.setImage(null);
        }
    }

    // --- Image Loading & Operations ---

    private void loadImage(int index) {
        if (currentLoadingTask != null && !currentLoadingTask.isDone()) {
            currentLoadingTask.cancel(true);
        }

        if (index < 0 || index >= currentFiles.size()) {
            Platform.runLater(() -> mainImageView.setImage(null));
            return;
        }

        File file = currentFiles.get(index);
        this.currentIndex = index;

        viewModel.selectImage(file);
        resetZoom();
        mainImageView.setImage(null);

        currentLoadingTask = thumbnailPool.submit(() -> {
            if (Thread.currentThread().isInterrupted()) return;
            Image img = loadRobustImage(file, 0);
            if (Thread.currentThread().isInterrupted()) return;
            Platform.runLater(() -> {
                if (currentIndex == index) {
                    mainImageView.setImage(img);
                }
            });
        });

        filmstripView.setSelectedIndex(index);
        Platform.runLater(() -> centerContainer.requestFocus());
    }

    private void deleteCurrentImage() {
        File f = getCurrentFile();
        if (f == null) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Delete " + f.getName() + "?", ButtonType.YES, ButtonType.NO);
        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                if (f.delete()) {
                    currentFiles.remove(currentIndex);
                    refreshCurrentView();
                }
            }
        });
    }

    private void showFullScreen(File file) {
        if (file == null) return;
        Stage fsStage = new Stage();
        fsStage.initStyle(StageStyle.TRANSPARENT);
        fsStage.initModality(Modality.APPLICATION_MODAL);
        ImageView view = new ImageView();
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.setPickOnBounds(true);
        view.setImage(loadRobustImage(file, 0));
        Translate fsTranslate = new Translate();
        Scale fsScale = new Scale();
        view.getTransforms().addAll(fsScale, fsTranslate);
        StackPane root = new StackPane(view);
        root.setStyle("-fx-background-color: black;");
        root.setAlignment(Pos.CENTER);
        setupZoomAndPan(view, root, fsTranslate, fsScale, null);
        view.fitWidthProperty().bind(fsStage.widthProperty());
        view.fitHeightProperty().bind(fsStage.heightProperty());
        Scene scene = new Scene(root);
        scene.setFill(Color.BLACK);
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) fsStage.close(); });
        fsStage.setScene(scene);
        fsStage.setFullScreen(true);
        fsStage.setFullScreenExitHint("Press ESC to exit Fullscreen");
        fsStage.show();
    }

    // --- Input & Event Handling ---

    private void setupInputHandlers() {
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
    }

    private void setupZoomAndPan(ImageView view, Pane container, Translate translate, Scale scale, Runnable clickAction) {
        container.setOnScroll(e -> {
            if (e.isControlDown() || e.getDeltaY() != 0) {
                e.consume();
                double zoomFactor = (e.getDeltaY() > 0) ? 1.1 : 0.9;
                double newScale = Math.max(0.1, Math.min(20.0, scale.getX() * zoomFactor));
                try {
                    Point2D pivotInImage = view.sceneToLocal(e.getSceneX(), e.getSceneY());
                    scale.setX(newScale);
                    scale.setY(newScale);
                    Point2D newPivotScene = view.localToScene(pivotInImage);
                    translate.setX(translate.getX() + (e.getSceneX() - newPivotScene.getX()));
                    translate.setY(translate.getY() + (e.getSceneY() - newPivotScene.getY()));
                } catch (Exception ex) {
                    scale.setX(newScale);
                    scale.setY(newScale);
                }
            }
        });

        class DragState { double startX, startY, transX, transY; boolean isDragging; }
        final DragState state = new DragState();

        container.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.MIDDLE || e.getButton() == MouseButton.PRIMARY) {
                state.startX = e.getSceneX();
                state.startY = e.getSceneY();
                state.transX = translate.getX();
                state.transY = translate.getY();
                state.isDragging = false;
            }
        });

        container.setOnMouseDragged(e -> {
            if (e.getButton() == MouseButton.MIDDLE || (e.getButton() == MouseButton.PRIMARY && scale.getX() > 1.0)) {
                if (Math.abs(e.getSceneX() - state.startX) > 3 || Math.abs(e.getSceneY() - state.startY) > 3) {
                    state.isDragging = true;
                    container.setCursor(javafx.scene.Cursor.MOVE);
                    translate.setX(state.transX + (e.getSceneX() - state.startX));
                    translate.setY(state.transY + (e.getSceneY() - state.startY));
                }
            }
        });

        container.setOnMouseReleased(e -> {
            container.setCursor(javafx.scene.Cursor.DEFAULT);
            if (!state.isDragging && e.getButton() == MouseButton.PRIMARY && clickAction != null) {
                clickAction.run();
            }
        });

        container.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                resetZoom();
            }
        });
    }

    // --- Helpers & Internal Logic ---

    private void updateSidebar() {
        File f = getCurrentFile();
        if (f == null) return;
        metadataSidebar.updateData(
                f,
                viewModel.activeMetadataProperty().get(),
                viewModel.activeTagsProperty().get(),
                viewModel.activeStarredProperty().get()
        );
    }

    private void refreshSidebar() {
        folderNav.setPinnedFolders(viewModel.getPinnedFolders());
    }

    private void navigate(int dir) {
        if (currentFiles.isEmpty()) return;
        int newIndex = (currentIndex + dir) % currentFiles.size();
        if (newIndex < 0) newIndex += currentFiles.size();
        loadImage(newIndex);
    }

    private File getCurrentFile() {
        if (currentIndex >= 0 && currentIndex < currentFiles.size()) return currentFiles.get(currentIndex);
        return null;
    }

    private void resetZoom() {
        zoomScale.setX(1);
        zoomScale.setY(1);
        zoomTranslate.setX(0);
        zoomTranslate.setY(0);
    }

    private Image loadRobustImage(File file, double width) {
        try {
            Image img = new Image(file.toURI().toString(), width, 0, true, true, false);
            if (img.isError()) return SwingFXUtils.toFXImage(ImageIO.read(file), null);
            return img;
        } catch (Exception ex) { return null; }
    }

    public void showRawMetadataPopup(String rawText) {
        rawMetaTextArea.setText(rawText);
        overlayContainer.setVisible(true);
    }

    public FolderNav getFolderNav() { return folderNav; }

    // --- Inner Classes ---

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
                    thumbnailPool.submit(() -> {
                        Image img = loadRobustImage(item, 200);
                        if (img != null) ThumbnailCache.put(item.getAbsolutePath(), img);
                        Platform.runLater(() -> {
                            if (Objects.equals(item, getItem())) imageView.setImage(img);
                        });
                    });
                }
            }
        }
    }
}