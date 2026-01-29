package com.nilsson.imagetoolbox.ui.views;

import com.nilsson.imagetoolbox.data.UserDataManager;
import com.nilsson.imagetoolbox.service.MetadataService;
import com.nilsson.imagetoolbox.ui.FolderNav;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import org.controlsfx.control.GridCell;
import org.controlsfx.control.GridView;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class ImageBrowserView extends BorderPane {

    public enum ViewMode { BROWSER, GALLERY, LIST }

    private final MetadataService metadataService = new MetadataService();
    private final UserDataManager dataManager = UserDataManager.getInstance();

    // Shared Thread Pool
    private final ExecutorService imageLoaderPool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    private List<File> currentFiles = new ArrayList<>();
    private int currentIndex = -1;
    private ViewMode currentViewMode = ViewMode.BROWSER;

    // Sub-Components
    private final FolderNav folderNav;
    private final MetadataSidebar metadataSidebar;
    private final FilmstripView filmstripView;

    // Central UI
    private StackPane centerContainer;
    private ImageView mainImageView;
    private StackPane singleViewContainer;
    private GridView<File> gridView;
    private ListView<File> fileListView;

    // Overlay
    private StackPane overlayContainer;
    private TextArea rawMetaTextArea;
    private ToggleGroup viewToggleGroup;

    public ImageBrowserView() {
        this.getStyleClass().add("image-browser-view");

        // 1. Initialize Sub-Components
        this.metadataSidebar = new MetadataSidebar(metadataService, this::showRawMetadata);
        this.filmstripView = new FilmstripView(imageLoaderPool);

        // Link Filmstrip selection to Main View
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

        // 2. Setup Central Area
        setupCenter();

        // 3. Global Keys
        this.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getTarget() instanceof TextInputControl) return;
            switch (e.getCode()) {
                case LEFT: case A: navigate(-1); e.consume(); break;
                case RIGHT: case D: navigate(1); e.consume(); break;
                case DELETE: deleteCurrentImage(); e.consume(); break;
                case ESCAPE: if (overlayContainer.isVisible()) overlayContainer.setVisible(false); e.consume(); break;
            }
        });

        setViewMode(ViewMode.BROWSER);
    }

    public FolderNav getFolderNav() { return folderNav; }

    public void restoreLastFolder() {
        File last = dataManager.getLastFolder();
        if(last != null && last.exists()) {
            System.out.println("Restoring last folder: " + last.getAbsolutePath());
            loadFolder(last);
        }
    }

    private void refreshCurrentViewIfNeeded() {
        Platform.runLater(() -> {
            List<File> toRemove = currentFiles.stream()
                    .filter(f -> !f.exists())
                    .collect(Collectors.toList());
            if (!toRemove.isEmpty()) {
                currentFiles.removeAll(toRemove);
                refreshAll();
            }
        });
    }

    public void loadCustomFileList(List<File> files) {
        this.currentFiles = new ArrayList<>(files);
        refreshAll();
    }

    private void setupCenter() {
        centerContainer = new StackPane();
        centerContainer.getStyleClass().add("center-stack");
        centerContainer.setFocusTraversable(true);
        centerContainer.setOnMouseClicked(e -> centerContainer.requestFocus());

        centerContainer.setMinSize(0, 0);
        centerContainer.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(centerContainer.widthProperty());
        clip.heightProperty().bind(centerContainer.heightProperty());
        centerContainer.setClip(clip);

        // A. Single View
        mainImageView = new ImageView();
        mainImageView.setPreserveRatio(true);
        mainImageView.setSmooth(true);
        mainImageView.fitWidthProperty().bind(centerContainer.widthProperty().subtract(40));
        mainImageView.fitHeightProperty().bind(centerContainer.heightProperty().subtract(40));

        singleViewContainer = new StackPane(mainImageView);
        singleViewContainer.setAlignment(Pos.CENTER);

        // B. Grid View (Virtualized)
        gridView = new GridView<>();
        gridView.setCellWidth(160);
        gridView.setCellHeight(160);
        gridView.setHorizontalCellSpacing(10);
        gridView.setVerticalCellSpacing(10);
        gridView.setStyle("-fx-background-color: transparent;");
        gridView.setCellFactory(gv -> new ImageGridCell());

        // C. List View
        fileListView = new ListView<>();
        fileListView.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(File item, boolean empty) {
                super.updateItem(item, empty);
                setText((empty || item == null) ? null : item.getName());
            }
        });
        fileListView.getSelectionModel().selectedIndexProperty().addListener((o, old, v) -> {
            if(v != null && v.intValue() >= 0) {
                currentIndex = v.intValue();
                // If in list mode, we might want to show preview somewhere, but currently minimal
            }
        });

        setupOverlay();
        this.setCenter(centerContainer);
    }

    private void setupOverlay() {
        overlayContainer = new StackPane();
        overlayContainer.setStyle("-fx-background-color: rgba(0,0,0,0.85);");
        overlayContainer.setVisible(false);
        overlayContainer.setPadding(new Insets(40));

        rawMetaTextArea = new TextArea();
        rawMetaTextArea.setEditable(false);
        rawMetaTextArea.getStyleClass().add("meta-value-text-area");

        Button closeBtn = new Button("Close");
        closeBtn.setOnAction(e -> overlayContainer.setVisible(false));

        VBox content = new VBox(10, rawMetaTextArea, closeBtn);
        content.setAlignment(Pos.CENTER_RIGHT);
        content.setMaxSize(800, 600);
        overlayContainer.getChildren().add(content);
    }

    public void setViewMode(ViewMode mode) {
        this.currentViewMode = mode;
        centerContainer.getChildren().clear();

        Node contentNode;
        switch (mode) {
            case GALLERY:
                contentNode = gridView;
                // CRITICAL: Ensure items are set when switching to Gallery
                gridView.setItems(FXCollections.observableArrayList(currentFiles));
                break;
            case LIST:
                contentNode = fileListView;
                fileListView.getItems().setAll(currentFiles);
                break;
            case BROWSER: default:
                contentNode = singleViewContainer;
                break;
        }
        centerContainer.getChildren().add(contentNode);
        centerContainer.getChildren().add(overlayContainer);
        // centerContainer.getChildren().add(createViewSwitcher()); // Add this if you have the helper method

        // Toggle Sidebars
        if (mode == ViewMode.BROWSER) {
            this.setBottom(filmstripView);
            this.setRight(metadataSidebar);
            if (currentIndex >= 0) loadImage(currentIndex);
        } else {
            this.setBottom(null);
            this.setRight(null);
        }
    }

    private void loadImage(int index) {
        if (index < 0 || index >= currentFiles.size()) return;
        File file = currentFiles.get(index);

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
            System.out.println("Loaded " + currentFiles.size() + " images from " + folder.getName());
            refreshAll();
        });
        task.setOnFailed(e -> {
            System.err.println("Failed to load folder: " + folder.getAbsolutePath());
            task.getException().printStackTrace();
        });
        new Thread(task).start();
    }

    private void refreshAll() {
        currentIndex = 0;
        filmstripView.setFiles(currentFiles);

        // Always update grid items just in case
        gridView.setItems(FXCollections.observableArrayList(currentFiles));
        fileListView.getItems().setAll(currentFiles);

        if (!currentFiles.isEmpty() && currentViewMode == ViewMode.BROWSER) {
            loadImage(0);
        } else {
            mainImageView.setImage(null);
        }
        Platform.runLater(() -> centerContainer.requestFocus());
    }

    private void navigate(int dir) {
        if (currentFiles.isEmpty()) return;
        currentIndex = (currentIndex + dir + currentFiles.size()) % currentFiles.size();
        if (currentViewMode == ViewMode.BROWSER) loadImage(currentIndex);
        if (currentViewMode == ViewMode.LIST) {
            fileListView.getSelectionModel().select(currentIndex);
            fileListView.scrollTo(currentIndex);
        }
    }

    private void deleteCurrentImage() {
        if(currentIndex >=0 && currentIndex < currentFiles.size()){
            File f = currentFiles.get(currentIndex);
            try {
                if(java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH))
                    java.awt.Desktop.getDesktop().moveToTrash(f);
                else f.delete();

                currentFiles.remove(currentIndex);
                refreshAll();
            } catch(Exception e) { e.printStackTrace(); }
        }
    }

    private void showRawMetadata(File file) {
        Task<String> task = new Task<>() { @Override protected String call() { return metadataService.getRawMetadata(file); }};
        task.setOnSucceeded(e -> {
            rawMetaTextArea.setText(task.getValue());
            overlayContainer.setVisible(true);
        });
        new Thread(task).start();
    }

    // --- ROBUST LOADER WITH FALLBACK ---
    private Image loadRobustImage(File file, double width) {
        try {
            // backgroundLoading = false (Last param) ensures we catch errors immediately
            Image img = new Image(file.toURI().toString(), width, 0, true, true, false);

            if (img.isError()) {
                System.out.println("JavaFX Load Failed (WebP/Format?): " + file.getName() + ". Trying ImageIO...");
                return SwingFXUtils.toFXImage(ImageIO.read(file), null);
            }
            return img;
        } catch (Exception ex) {
            System.err.println("Image Load Error for " + file.getName() + ": " + ex.getMessage());
            return null;
        }
    }

    // --- INNER CLASS: VIRTUALIZED CELL ---
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

            // Add a temporary border/color so we can see the cell even if image is null
            container.setStyle("-fx-border-color: #444; -fx-border-width: 1;");

            setGraphic(container);

            container.setOnMouseClicked(e -> {
                if (getItem() == null) return;
                currentIndex = getIndex();
                if (e.getClickCount() == 2) {
                    setViewMode(ViewMode.BROWSER);
                }
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
                imageView.setImage(null);

                imageLoaderPool.submit(() -> {
                    Image img = loadRobustImage(item, 200);
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