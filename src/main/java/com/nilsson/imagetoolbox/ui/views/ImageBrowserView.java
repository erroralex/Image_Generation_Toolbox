package com.nilsson.imagetoolbox.ui.views;

import com.nilsson.imagetoolbox.ui.components.BrowserToolbar;
import com.nilsson.imagetoolbox.ui.components.FolderNav;
import com.nilsson.imagetoolbox.ui.components.ImageLoader;
import com.nilsson.imagetoolbox.ui.viewmodels.*;
import de.saxsys.mvvmfx.FluentViewLoader;
import de.saxsys.mvvmfx.InjectViewModel;
import de.saxsys.mvvmfx.JavaView;
import de.saxsys.mvvmfx.ViewTuple;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import javax.inject.Inject;
import java.io.File;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 <h2>ImageBrowserView</h2>
 <p>
 The primary view component of the application, responsible for orchestrating the image
 browsing experience. It functions as a composite view that integrates navigation,
 toolbars, and various viewing modes (Browser, Gallery, and List).
 </p>
 <h3>Key Responsibilities:</h3>
 <ul>
 <li><b>Layout Management:</b> Coordinates a {@link BorderPane} based structure with
 a collapsible {@link MetadataSidebar} and a "pill" style {@link BrowserToolbar}.</li>
 <li><b>Mode Orchestration:</b> Switches dynamically between single-image focus,
 thumbnail galleries, and file list views.</li>
 <li><b>Interaction Handling:</b> Manages complex user input including keyboard shortcuts,
 multi-file selection (Shift/Ctrl), and drag-and-drop folder loading.</li>
 <li><b>Asynchronous Loading:</b> Utilizes a global {@link ExecutorService} to load
 high-resolution images without blocking the UI thread.</li>
 </ul>
 */
public class ImageBrowserView extends StackPane implements JavaView<ImageBrowserViewModel>, Initializable {

    /**
     Defines the available display modes for the central viewing area.
     */
    public enum ViewMode {BROWSER, GALLERY, LIST}

    // --- Core Dependencies ---

    @InjectViewModel
    private ImageBrowserViewModel viewModel;

    @Inject
    private SearchViewModel searchViewModel;

    @Inject
    private CollectionViewModel collectionViewModel;

    private final ExecutorService workerPool;
    private Future<?> currentLoadingTask;

    // --- View State ---

    private List<File> currentFiles = new ArrayList<>();
    private final List<File> selectedFiles = new ArrayList<>();
    private File lastAnchorFile = null;
    private int currentIndex = -1;
    private ViewMode currentViewMode = ViewMode.BROWSER;
    private boolean isDrawerOpen = false;
    private boolean isDocked = false;

    // --- UI UI Components ---

    private final BorderPane baseLayer;
    private FolderNav folderNav;
    private BrowserToolbar toolbar;
    private final FilmstripView filmstripView;
    private GalleryView galleryView;
    private final SingleImageView singleImageView;
    private MetadataSidebar inspectorDrawer;
    private StackPane centerContainer;
    private ListView<File> fileListView;
    private Region drawerBackdrop;
    private StackPane dropOverlay;

    // --- Constructor & Initialization ---

    @Inject
    public ImageBrowserView(ExecutorService globalExecutor) {
        this.workerPool = globalExecutor;

        this.getStyleClass().add("image-browser-view");
        this.setAlignment(Pos.TOP_LEFT);
        this.setFocusTraversable(true);
        this.setOnMousePressed(e -> this.requestFocus());

        baseLayer = new BorderPane();
        baseLayer.setMinSize(0, 0);

        this.filmstripView = new FilmstripView(workerPool);
        this.singleImageView = new SingleImageView(
                this::toggleDrawer,
                () -> navigate(-1),
                () -> navigate(1)
        );

        setupComponents();
        setupInputHandlers();
    }

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        this.galleryView = new GalleryView(viewModel, workerPool, this::onGalleryFileSelected);

        // Load BrowserToolbar via MVVMFX with manual ViewModel injection
        BrowserToolbarViewModel toolbarVM = new BrowserToolbarViewModel(searchViewModel);
        ViewTuple<BrowserToolbar, BrowserToolbarViewModel> toolbarTuple = FluentViewLoader.javaView(BrowserToolbar.class)
                .viewModel(toolbarVM)
                .load();
        
        // Explicit cast to resolve type mismatch
        toolbar = (BrowserToolbar) toolbarTuple.getView();

        toolbar.setOnGridAction(() -> setViewMode(ViewMode.GALLERY));
        toolbar.setOnSingleAction(() -> setViewMode(ViewMode.BROWSER));
        toolbar.setOnSearchEnter(() -> {
            if (currentViewMode == ViewMode.GALLERY) galleryView.requestFocus();
            else this.requestFocus();
        });

        StackPane toolbarContainer = new StackPane(toolbar);
        toolbarContainer.setAlignment(Pos.CENTER);
        toolbarContainer.setPadding(new Insets(15, 0, 10, 0));
        toolbarContainer.setStyle("-fx-background-color: transparent;");
        toolbarContainer.setPickOnBounds(false);
        toolbarContainer.setMaxHeight(Region.USE_PREF_SIZE);

        baseLayer.setTop(toolbarContainer);

        setupInspector();
        setupDragOverlay();

        this.getChildren().addAll(baseLayer, drawerBackdrop, inspectorDrawer, dropOverlay);

        refreshNav();

        setupBindings();

        setViewMode(ViewMode.BROWSER);

        Platform.runLater(() -> {
            File lastFolder = viewModel.getLastFolder();
            if (lastFolder != null && lastFolder.exists()) {
                viewModel.loadFolder(lastFolder);
                folderNav.selectFolder(lastFolder);
            }
            this.requestFocus();
        });
    }

    // --- Configuration Logic ---

    private void setupComponents() {
        this.folderNav = new FolderNav(new FolderNav.FolderNavListener() {
            @Override
            public void onFolderSelected(File f) {
                viewModel.loadFolder(f);
            }

            @Override
            public void onCollectionSelected(String n) {
                viewModel.loadCollection(n);
            }

            @Override
            public void onCreateCollection(String n) {
                viewModel.createNewCollection(n);
            }

            @Override
            public void onDeleteCollection(String n) {
                viewModel.deleteCollection(n);
            }

            @Override
            public void onAddFilesToCollection(String n, List<File> f) {
                viewModel.addFilesToCollection(n, f);
            }

            @Override
            public void onSearch(String q) {
                viewModel.search(q);
            }

            @Override
            public void onShowStarred() {
                viewModel.loadStarred();
            }

            @Override
            public void onPinFolder(File f) {
                viewModel.pinFolder(f);
                refreshNav();
            }

            @Override
            public void onUnpinFolder(File f) {
                viewModel.removePinnedFolder(f);
                refreshNav();
            }

            @Override
            public void onFilesMoved() {
            }
        });

        this.filmstripView.setOnSelectionChanged(index -> {
            this.currentIndex = index;
            loadImage(currentIndex);
        });

        centerContainer = new StackPane();
        centerContainer.getStyleClass().add("center-stack");

        fileListView = new ListView<>();
        fileListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(File item, boolean empty) {
                super.updateItem(item, empty);
                setText((empty || item == null) ? null : item.getName());
            }
        });

        baseLayer.setLeft(folderNav);
        baseLayer.setCenter(centerContainer);
    }

    private void setupInspector() {
        // Load MetadataSidebar via MVVMFX with manual ViewModel injection
        MetadataSidebarViewModel sidebarVM = new MetadataSidebarViewModel(viewModel, collectionViewModel);
        ViewTuple<MetadataSidebar, MetadataSidebarViewModel> sidebarTuple = FluentViewLoader.javaView(MetadataSidebar.class)
                .viewModel(sidebarVM)
                .load();
        
        // Explicit cast to resolve type mismatch
        inspectorDrawer = (MetadataSidebar) sidebarTuple.getView();

        inspectorDrawer.setActionHandler(new MetadataSidebar.SidebarActionHandler() {
            @Override
            public void onToggleDock() {
                toggleDock();
            }

            @Override
            public void onClose() {
                closeDrawer();
            }

            @Override
            public void onSetRating(int rating) {
                viewModel.setRating(rating);
            }

            @Override
            public void onCreateCollection(String name) {
                viewModel.createNewCollection(name);
            }

            @Override
            public void onAddToCollection(String collectionName) {
                viewModel.addSelectedToCollection(collectionName);
            }
        });
        inspectorDrawer.setTranslateX(380);
        StackPane.setAlignment(inspectorDrawer, Pos.CENTER_RIGHT);

        drawerBackdrop = new Region();
        drawerBackdrop.setStyle("-fx-background-color: rgba(0,0,0,0.4);");
        drawerBackdrop.setVisible(false);
        drawerBackdrop.setOnMouseClicked(e -> closeDrawer());
    }

    private void setupBindings() {
        viewModel.getFilteredFiles().addListener((ListChangeListener<File>) c -> {
            File previousFile = (currentIndex >= 0 && currentIndex < currentFiles.size()) ? currentFiles.get(currentIndex) : null;
            this.currentFiles = new ArrayList<>(viewModel.getFilteredFiles());
            filmstripView.setFiles(currentFiles);

            if (currentViewMode == ViewMode.LIST) {
                fileListView.getItems().setAll(currentFiles);
            } else if (currentViewMode == ViewMode.BROWSER) {
                if (currentFiles.isEmpty()) {
                    singleImageView.setImage(null);
                    viewModel.updateSelection(Collections.emptyList());
                } else {
                    if (previousFile != null && currentFiles.contains(previousFile)) {
                        currentIndex = currentFiles.indexOf(previousFile);
                    } else {
                        loadImage(0);
                    }
                }
            }
        });

        // Note: MetadataSidebarViewModel now handles its own bindings to MainViewModel properties
        // We just need to handle the resolution update which is UI specific
        singleImageView.imageProperty().addListener((obs, old, img) -> {
            if (img != null) inspectorDrawer.updateResolution(img.getWidth(), img.getHeight());
        });

        folderNav.setCollections(viewModel.getCollectionList());
        viewModel.getCollectionList().addListener((ListChangeListener<String>) c -> {
            folderNav.setCollections(viewModel.getCollectionList());
        });

        galleryView.tileSizeProperty().bind(toolbar.cardSizeProperty());
    }

    // --- State Management ---

    public void setViewMode(ViewMode mode) {
        this.currentViewMode = mode;
        centerContainer.getChildren().clear();

        if (toolbar != null) {
            toolbar.setSliderVisible(mode == ViewMode.GALLERY);
            toolbar.setActiveView(mode == ViewMode.GALLERY);
        }

        if (mode == ViewMode.GALLERY) {
            centerContainer.getChildren().add(galleryView);
            baseLayer.setBottom(null);
            Platform.runLater(galleryView::requestFocus);
        } else if (mode == ViewMode.LIST) {
            fileListView.getItems().setAll(currentFiles);
            centerContainer.getChildren().add(fileListView);
            baseLayer.setBottom(null);
        } else {
            centerContainer.getChildren().add(singleImageView);
            baseLayer.setBottom(filmstripView);
            filmstripView.setFiles(currentFiles);
            if (currentIndex >= 0) {
                loadImage(currentIndex);
                filmstripView.setSelectedIndex(currentIndex);
            }
        }
    }

    private void loadImage(int index) {
        if (currentLoadingTask != null && !currentLoadingTask.isDone()) currentLoadingTask.cancel(true);
        if (index < 0 || index >= currentFiles.size()) {
            Platform.runLater(() -> singleImageView.setImage(null));
            return;
        }
        File file = currentFiles.get(index);
        this.currentIndex = index;

        if (currentViewMode == ViewMode.BROWSER && selectedFiles.size() <= 1) {
            handleSelectionClick(file, false, false);
        }

        singleImageView.setImage(null);

        currentLoadingTask = workerPool.submit(() -> {
            if (Thread.currentThread().isInterrupted()) return;
            Image img = ImageLoader.load(file, 0, 0);
            Platform.runLater(() -> {
                if (currentIndex == index) singleImageView.setImage(img);
            });
        });

        filmstripView.setSelectedIndex(index);
    }

    // --- Navigation & Selection ---

    private void navigate(int dir) {
        if (currentFiles.isEmpty()) return;
        int newIndex = (currentIndex + dir) % currentFiles.size();
        if (newIndex < 0) newIndex += currentFiles.size();
        loadImage(newIndex);
        currentIndex = newIndex;
        if (currentViewMode == ViewMode.GALLERY) {
            handleSelectionClick(currentFiles.get(newIndex), false, false);
        }
    }

    private void onGalleryFileSelected(File file, Boolean doubleClick) {
        handleSelectionClick(file, false, false);
        if (doubleClick) {
            setViewMode(ViewMode.BROWSER);
            if (currentIndex == -1 || !currentFiles.get(currentIndex).equals(file)) {
                currentIndex = currentFiles.indexOf(file);
            }
            if (currentIndex >= 0) {
                loadImage(currentIndex);
                openDrawer();
            }
        }
    }

    private void handleSelectionClick(File file, boolean isShift, boolean isCtrl) {
        if (file == null) return;
        if (isShift && lastAnchorFile != null && currentFiles.contains(lastAnchorFile)) {
            int start = currentFiles.indexOf(lastAnchorFile);
            int end = currentFiles.indexOf(file);
            selectedFiles.clear();
            selectedFiles.addAll(currentFiles.subList(Math.min(start, end), Math.max(start, end) + 1));
        } else if (isCtrl) {
            if (selectedFiles.contains(file)) selectedFiles.remove(file);
            else {
                selectedFiles.add(file);
                lastAnchorFile = file;
            }
        } else {
            selectedFiles.clear();
            selectedFiles.add(file);
            lastAnchorFile = file;
        }
        viewModel.updateSelection(new ArrayList<>(selectedFiles));
        currentIndex = currentFiles.indexOf(file);
    }

    // --- View Transitions ---

    private void toggleDock() {
        isDocked = !isDocked;
        inspectorDrawer.setDocked(isDocked);
        if (isDocked) {
            this.getChildren().remove(inspectorDrawer);
            inspectorDrawer.setTranslateX(0);
            baseLayer.setRight(inspectorDrawer);
            drawerBackdrop.setVisible(false);
        } else {
            baseLayer.setRight(null);
            int idx = this.getChildren().indexOf(drawerBackdrop);
            this.getChildren().add(idx + 1, inspectorDrawer);
            inspectorDrawer.setTranslateX(0);
        }
    }

    private void toggleDrawer() {
        if (isDrawerOpen) closeDrawer();
        else openDrawer();
    }

    private void openDrawer() {
        if (isDrawerOpen) return;
        isDrawerOpen = true;
        drawerBackdrop.setVisible(!isDocked);
        if (!isDocked) {
            TranslateTransition tt = new TranslateTransition(Duration.millis(250), inspectorDrawer);
            tt.setToX(0);
            tt.play();
        }
    }

    private void closeDrawer() {
        if (!isDrawerOpen) return;
        isDrawerOpen = false;
        drawerBackdrop.setVisible(false);
        if (!isDocked) {
            TranslateTransition tt = new TranslateTransition(Duration.millis(250), inspectorDrawer);
            tt.setToX(380);
            tt.play();
        }
    }

    // --- Interaction Handlers ---

    private void setupInputHandlers() {
        this.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getTarget() instanceof TextInputControl || e.getTarget() instanceof Slider) return;

            if (currentViewMode == ViewMode.GALLERY) {
                switch (e.getCode()) {
                    case LEFT, RIGHT, UP, DOWN, A, D, W, S -> {
                        return;
                    }
                }
            }

            switch (e.getCode()) {
                case LEFT, A -> navigate(-1);
                case RIGHT, D -> navigate(1);
                case F -> toggleDock();
                case S -> viewModel.toggleStar();
                case G -> setViewMode(ViewMode.GALLERY);
                case B, ESCAPE -> setViewMode(ViewMode.BROWSER);
                case DELETE -> handleDelete();
            }
        });
    }

    private void handleDelete() {
        if (viewModel.getSelectionCount() > 0) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.initOwner(this.getScene().getWindow());
            alert.setTitle("Delete Files");
            alert.setHeaderText("Delete " + viewModel.getSelectionCount() + " files?");
            alert.showAndWait().ifPresent(r -> {
                if (r == ButtonType.OK) viewModel.deleteSelectedFiles();
            });
        }
    }

    private void setupDragOverlay() {
        dropOverlay = new StackPane();
        dropOverlay.getStyleClass().add("drag-overlay");
        dropOverlay.setVisible(false);
        dropOverlay.setMouseTransparent(true);
        Label dropText = new Label("Drop folder to load");
        dropText.setStyle("-fx-font-size: 24px; -fx-text-fill: white;");
        dropOverlay.getChildren().add(dropText);

        this.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
                dropOverlay.setVisible(true);
            }
            e.consume();
        });
        this.setOnDragExited(e -> {
            dropOverlay.setVisible(false);
            e.consume();
        });
        this.setOnDragDropped(e -> {
            boolean s = false;
            if (e.getDragboard().hasFiles()) {
                File f = e.getDragboard().getFiles().get(0);
                File dir = f.isDirectory() ? f : f.getParentFile();
                viewModel.loadFolder(dir);
                folderNav.selectFolder(dir);
                s = true;
            }
            dropOverlay.setVisible(false);
            e.setDropCompleted(s);
            e.consume();
        });
    }

    // --- Utilities ---

    private void refreshNav() {
        folderNav.setPinnedFolders(viewModel.getPinnedFolders());
    }
}
