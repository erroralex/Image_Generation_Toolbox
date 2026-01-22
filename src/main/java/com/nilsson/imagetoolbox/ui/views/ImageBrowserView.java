package com.nilsson.imagetoolbox.ui.views;

import com.nilsson.imagetoolbox.data.UserDataManager;
import com.nilsson.imagetoolbox.service.MetadataService;
import com.nilsson.imagetoolbox.ui.FolderNav;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import org.kordamp.ikonli.fontawesome.FontAwesome;
import org.kordamp.ikonli.javafx.FontIcon;

import javax.imageio.ImageIO;
import java.awt.Desktop;
import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class ImageBrowserView extends BorderPane {

    public enum ViewMode { BROWSER, GALLERY, LIST }

    private final MetadataService metadataService = new MetadataService();
    private final UserDataManager dataManager = UserDataManager.getInstance();

    private Task<Map<String, String>> currentMetaTask;
    private boolean isViewingCustomList = false;

    private final ExecutorService imageLoaderPool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    private List<File> currentFiles = new ArrayList<>();
    private int currentIndex = -1;
    private ViewMode currentViewMode = ViewMode.BROWSER;

    // UI Elements
    private StackPane centerContainer;
    private ImageView mainImageView;
    private StackPane singleViewContainer;
    private ScrollPane gridScrollPane;
    private FlowPane gridPane;
    private ListView<File> fileListView;

    // Metadata & Filmstrip
    private ScrollPane metaPane;
    private VBox metaContent;
    private HBox filmStrip;
    private ScrollPane filmStripScrollPane;
    private FlowPane tagsFlowPane;
    private TextField tagInput;

    // Overlay Elements
    private StackPane overlayContainer;
    private TextArea rawMetaTextArea;

    private ToggleGroup viewToggleGroup;
    private FolderNav folderNav;

    public ImageBrowserView() {
        this.getStyleClass().add("image-browser-view");

        setupCenter();
        setupRight();
        setupBottom();

        folderNav = new FolderNav(
                this::loadFolder,
                this::loadCustomFileList,
                this::refreshCurrentViewIfNeeded
        );
        this.setLeft(folderNav);

        this.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getTarget() instanceof TextInputControl) return;
            switch (e.getCode()) {
                case LEFT: case A: navigate(-1); e.consume(); break;
                case RIGHT: case D: navigate(1); e.consume(); break;
                case DELETE: deleteCurrentImage(); e.consume(); break;
                case ESCAPE: if (overlayContainer.isVisible()) closeRawMetadata(); e.consume(); break;
            }
        });

        setViewMode(ViewMode.BROWSER);
    }

    public FolderNav getFolderNav() { return folderNav; }

    public void restoreLastFolder() {
        if (isViewingCustomList) {
            File last = dataManager.getLastFolder();
            if (last != null && last.exists()) {
                loadFolder(last);
            } else {
                currentFiles.clear();
                refreshAll();
            }
        }
        this.setVisible(true);
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

    private void setupCenter() {
        centerContainer = new StackPane();
        centerContainer.getStyleClass().add("center-stack");
        centerContainer.setMinSize(0,0);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(centerContainer.widthProperty());
        clip.heightProperty().bind(centerContainer.heightProperty());
        centerContainer.setClip(clip);

        mainImageView = new ImageView();
        mainImageView.setPreserveRatio(true);
        mainImageView.setSmooth(true);

        singleViewContainer = new StackPane(mainImageView);
        singleViewContainer.setMinSize(0, 0);

        mainImageView.fitWidthProperty().bind(singleViewContainer.widthProperty());
        mainImageView.fitHeightProperty().bind(singleViewContainer.heightProperty());

        // --- Overlay Setup ---
        overlayContainer = new StackPane();
        overlayContainer.setStyle("-fx-background-color: rgba(0,0,0,0.85);");
        overlayContainer.setVisible(false);
        overlayContainer.setPadding(new Insets(40));

        rawMetaTextArea = new TextArea();
        rawMetaTextArea.setEditable(false);
        rawMetaTextArea.setWrapText(true);
        rawMetaTextArea.getStyleClass().add("meta-value-text-area");
        rawMetaTextArea.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 13px;");

        Button closeOverlayBtn = new Button("Close");
        closeOverlayBtn.getStyleClass().add("button");
        closeOverlayBtn.setOnAction(e -> closeRawMetadata());

        VBox overlayContent = new VBox(10, rawMetaTextArea, closeOverlayBtn);
        overlayContent.setAlignment(Pos.CENTER_RIGHT);
        overlayContent.setMaxWidth(800);
        overlayContent.setMaxHeight(600);

        overlayContainer.getChildren().add(overlayContent);
        // -------------------------

        gridPane = new FlowPane(10, 10);
        gridPane.setPadding(new Insets(10));
        gridPane.setAlignment(Pos.TOP_LEFT);
        gridScrollPane = new ScrollPane(gridPane);
        gridScrollPane.setFitToWidth(true);
        gridScrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");

        fileListView = new ListView<>();
        fileListView.setStyle("-fx-background-color: transparent;");
        fileListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(File item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.getName());
                    setGraphic(new FontIcon(FontAwesome.FILE_IMAGE_O));
                }
            }
        });
        fileListView.getSelectionModel().selectedIndexProperty().addListener((obs, old, newVal) -> {
            if (newVal != null && newVal.intValue() >= 0) {
                currentIndex = newVal.intValue();
            }
        });

        this.setCenter(centerContainer);
    }

    private void setupRight() {
        metaContent = new VBox(15);
        metaContent.setPadding(new Insets(20));

        metaPane = new ScrollPane(metaContent);
        metaPane.setFitToWidth(true);
        metaPane.setMinWidth(300);
        metaPane.setPrefWidth(340);
        metaPane.getStyleClass().add("meta-pane");
    }

    private void setupBottom() {
        filmStrip = new HBox(15);
        filmStrip.setAlignment(Pos.CENTER);
        filmStrip.setPadding(new Insets(10));

        StackPane stripWrapper = new StackPane(filmStrip);
        stripWrapper.setAlignment(Pos.CENTER);
        stripWrapper.minWidthProperty().bind(filmStripScrollPane == null ?
                new javafx.beans.property.SimpleDoubleProperty(0) :
                filmStripScrollPane.widthProperty());

        filmStripScrollPane = new ScrollPane(stripWrapper);
        stripWrapper.minWidthProperty().bind(filmStripScrollPane.viewportBoundsProperty().map(Bounds::getWidth));

        filmStripScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        filmStripScrollPane.setFitToHeight(true);
        filmStripScrollPane.setFitToWidth(false);
        filmStripScrollPane.setMinHeight(130);
        filmStripScrollPane.setPannable(true);
    }

    private HBox createViewSwitcher() {
        HBox box = new HBox(10);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(8));
        box.setStyle("-fx-background-color: rgba(0,0,0,0.6); -fx-background-radius: 20;");
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        viewToggleGroup = new ToggleGroup();
        ToggleButton btnBrowser = createIconToggle(FontAwesome.SQUARE_O, ViewMode.BROWSER);
        ToggleButton btnGallery = createIconToggle(FontAwesome.TH, ViewMode.GALLERY);
        ToggleButton btnList = createIconToggle(FontAwesome.LIST, ViewMode.LIST);

        if (currentViewMode == ViewMode.GALLERY) btnGallery.setSelected(true);
        else if (currentViewMode == ViewMode.LIST) btnList.setSelected(true);
        else btnBrowser.setSelected(true);

        box.getChildren().addAll(btnBrowser, btnGallery, btnList);
        return box;
    }

    private ToggleButton createIconToggle(FontAwesome icon, ViewMode mode) {
        ToggleButton btn = new ToggleButton();
        btn.setGraphic(new FontIcon(icon));
        btn.getStyleClass().add("view-switcher-btn");
        btn.setToggleGroup(viewToggleGroup);
        btn.setOnAction(e -> setViewMode(mode));
        return btn;
    }

    public void setViewMode(ViewMode mode) {
        this.currentViewMode = mode;
        centerContainer.getChildren().clear();
        Node contentNode;
        switch (mode) {
            case GALLERY: contentNode = gridScrollPane; populateGrid(); break;
            case LIST:    contentNode = fileListView; fileListView.getItems().setAll(currentFiles); fileListView.getSelectionModel().select(currentIndex); break;
            case BROWSER: default: contentNode = singleViewContainer; break;
        }
        centerContainer.getChildren().add(contentNode);
        centerContainer.getChildren().add(overlayContainer); // Add overlay on top

        HBox toolbar = createViewSwitcher();
        centerContainer.getChildren().add(toolbar);
        StackPane.setAlignment(toolbar, Pos.TOP_CENTER);
        StackPane.setMargin(toolbar, new Insets(15));

        if (mode == ViewMode.BROWSER) {
            this.setBottom(filmStripScrollPane);
            this.setRight(metaPane);
            if (currentIndex >= 0) loadImage(currentIndex);
            Platform.runLater(this::updateFilmStripSelection);
        } else {
            this.setBottom(null);
            this.setRight(null);
        }
    }

    // --- Overlay Logic ---
    private void showRawMetadata(File file) {
        Task<String> task = new Task<>() {
            @Override protected String call() {
                return metadataService.getRawMetadata(file);
            }
        };
        task.setOnSucceeded(e -> {
            String raw = task.getValue();
            rawMetaTextArea.setText(raw != null ? raw : "No readable metadata found.");
            overlayContainer.setVisible(true);
            rawMetaTextArea.requestFocus();
        });
        new Thread(task).start();
    }

    private void closeRawMetadata() {
        overlayContainer.setVisible(false);
    }

    public void navigate(int direction) {
        if (currentFiles.isEmpty()) return;
        currentIndex = (currentIndex + direction + currentFiles.size()) % currentFiles.size();
        if (currentViewMode == ViewMode.BROWSER) {
            loadImage(currentIndex);
            updateFilmStripSelection();
        } else if (currentViewMode == ViewMode.LIST) {
            fileListView.getSelectionModel().select(currentIndex);
            fileListView.scrollTo(currentIndex);
        }
    }

    private void updateFilmStripSelection() {
        if (filmStrip.getChildren().isEmpty()) return;
        for (Node node : filmStrip.getChildren()) {
            int fileIndex = (int) node.getProperties().get("index");
            if (fileIndex == currentIndex) {
                if (!node.getStyleClass().contains("film-frame-selected")) node.getStyleClass().add("film-frame-selected");
                centerFilmStripNode(node);
            } else {
                node.getStyleClass().remove("film-frame-selected");
            }
        }
    }

    private void centerFilmStripNode(Node node) {
        Bounds nodeBounds = node.getBoundsInParent();
        double nodeCenterX = (nodeBounds.getMinX() + nodeBounds.getMaxX()) / 2;
        double contentWidth = filmStrip.getBoundsInLocal().getWidth();
        double viewportWidth = filmStripScrollPane.getViewportBounds().getWidth();
        if (contentWidth <= viewportWidth) { filmStripScrollPane.setHvalue(0.5); return; }
        double targetX = nodeCenterX - (viewportWidth / 2);
        double maxScroll = contentWidth - viewportWidth;
        double hValue = targetX / maxScroll;
        filmStripScrollPane.setHvalue(Math.max(0, Math.min(1, hValue)));
    }

    public void loadFolder(File folder) {
        if (folder == null || !folder.isDirectory()) return;
        isViewingCustomList = false;
        dataManager.setLastFolder(folder);

        Task<List<File>> task = new Task<>() {
            @Override protected List<File> call() {
                File[] files = folder.listFiles((dir, name) -> {
                    String low = name.toLowerCase();
                    return low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".webp");
                });
                return files == null ? new ArrayList<>() : Arrays.stream(files)
                        .sorted((f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()))
                        .collect(Collectors.toList());
            }
        };
        task.setOnSucceeded(e -> {
            currentFiles = task.getValue();
            refreshAll();
        });
        new Thread(task).start();
    }

    public void loadCustomFileList(List<File> files) {
        this.currentFiles = new ArrayList<>(files);
        isViewingCustomList = true;
        refreshAll();
    }

    private void refreshAll() {
        currentIndex = 0;
        if (currentViewMode == ViewMode.LIST) fileListView.getItems().setAll(currentFiles);
        populateFilmStrip();
        if (currentViewMode == ViewMode.GALLERY) populateGrid();
        if (!currentFiles.isEmpty()) {
            if (currentViewMode == ViewMode.BROWSER) loadImage(0);
            Platform.runLater(this::updateFilmStripSelection);
        } else {
            mainImageView.setImage(null);
            metaContent.getChildren().clear();
        }
    }

    private void enableDragSource(Node node, File file) {
        node.setOnDragDetected(e -> {
            Dragboard db = node.startDragAndDrop(TransferMode.COPY_OR_MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putFiles(Collections.singletonList(file));
            db.setContent(content);
            e.consume();
        });
    }

    private void populateGrid() {
        gridPane.getChildren().clear();
        for (int i = 0; i < currentFiles.size(); i++) {
            File f = currentFiles.get(i);
            int finalIndex = i;

            StackPane cell = new StackPane();
            cell.setPrefSize(160, 160);
            cell.getStyleClass().add("grid-cell");

            ImageView thumb = new ImageView();
            thumb.setFitWidth(150);
            thumb.setFitHeight(150);
            thumb.setPreserveRatio(true);

            cell.getChildren().add(thumb);
            enableDragSource(cell, f);

            imageLoaderPool.submit(() -> {
                Image img = loadRobustImage(f, 200);
                Platform.runLater(() -> thumb.setImage(img));
            });

            cell.setOnMouseClicked(e -> {
                currentIndex = finalIndex;
                if (e.getClickCount() == 2) setViewMode(ViewMode.BROWSER);
            });
            gridPane.getChildren().add(cell);
        }
    }

    private void populateFilmStrip() {
        filmStrip.getChildren().clear();
        for (int i = 0; i < currentFiles.size(); i++) {
            File f = currentFiles.get(i);
            int idx = i;
            ImageView thumb = new ImageView();
            thumb.setFitHeight(80);
            thumb.setFitWidth(80);
            thumb.setPreserveRatio(true);
            StackPane frame = new StackPane(thumb);
            frame.getStyleClass().add("film-frame");
            frame.getProperties().put("index", idx);
            enableDragSource(frame, f);

            frame.setOnMouseClicked(e -> {
                currentIndex = idx;
                loadImage(currentIndex);
                updateFilmStripSelection();
            });
            imageLoaderPool.submit(() -> {
                Image img = loadRobustImage(f, 120);
                Platform.runLater(() -> thumb.setImage(img));
            });
            filmStrip.getChildren().add(frame);
        }
    }

    private void loadImage(int index) {
        if (index < 0 || index >= currentFiles.size()) return;
        File file = currentFiles.get(index);
        imageLoaderPool.submit(() -> {
            Image fullImage = loadRobustImage(file, 0);
            Platform.runLater(() -> mainImageView.setImage(fullImage));
        });
        renderMetadataPanel(file);
    }

    private Image loadRobustImage(File file, double width) {
        Image img = new Image(file.toURI().toString(), width, 0, true, true, false);
        if (img.isError() || img.getException() != null) {
            try {
                return SwingFXUtils.toFXImage(ImageIO.read(file), null);
            } catch (Exception ex) {
                return null;
            }
        }
        return img;
    }

    private void renderMetadataPanel(File file) {
        if (currentMetaTask != null && !currentMetaTask.isDone()) currentMetaTask.cancel();
        metaContent.getChildren().clear();

        // 1. Toolbar
        HBox toolbar = new HBox(15);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(0,0,10,0));

        Button btnStar = new Button();
        btnStar.getStyleClass().add("icon-button");
        boolean isStarred = dataManager.isStarred(file);
        FontIcon starIcon = new FontIcon(isStarred ? FontAwesome.STAR : FontAwesome.STAR_O);
        starIcon.setIconSize(18);
        if (isStarred) starIcon.setStyle("-fx-icon-color: gold;");
        btnStar.setGraphic(starIcon);
        btnStar.setOnAction(e -> { dataManager.toggleStar(file); renderMetadataPanel(file); });

        Button btnRaw = new Button();
        btnRaw.setTooltip(new Tooltip("View Raw Metadata"));
        btnRaw.getStyleClass().add("icon-button");
        btnRaw.setGraphic(new FontIcon(FontAwesome.FILE_CODE_O));
        btnRaw.setOnAction(e -> showRawMetadata(file));

        Button btnFolder = new Button();
        btnFolder.setTooltip(new Tooltip("Open File Location"));
        btnFolder.getStyleClass().add("icon-button");
        btnFolder.setGraphic(new FontIcon(FontAwesome.FOLDER_OPEN_O));
        btnFolder.setOnAction(e -> { try { Desktop.getDesktop().open(file.getParentFile()); } catch(Exception ex){} });

        toolbar.getChildren().addAll(btnStar, btnRaw, btnFolder);
        metaContent.getChildren().add(toolbar);

        // 2. Title & Tags
        Label title = new Label(file.getName());
        title.getStyleClass().add("meta-value");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 0 0 10 0;");

        tagsFlowPane = new FlowPane(8, 8);
        Set<String> currentTags = dataManager.getTags(file);
        if (currentTags != null) currentTags.forEach(this::addTagChip);

        tagInput = new TextField();
        tagInput.setPromptText("+ Add Tag");
        tagInput.getStyleClass().add("search-field");

        tagInput.setOnAction(e -> {
            String txt = tagInput.getText().trim();
            if (!txt.isEmpty()) { dataManager.addTag(file, txt); addTagChip(txt); tagInput.clear(); }
        });

        metaContent.getChildren().addAll(title, tagsFlowPane, tagInput, new Separator());

        // 3. Metadata
        currentMetaTask = new Task<>() {
            @Override protected Map<String, String> call() {
                if (isCancelled()) return null;
                return metadataService.getExtractedData(file);
            }
        };

        currentMetaTask.setOnSucceeded(e -> {
            Map<String, String> data = currentMetaTask.getValue();
            if (data == null) return;

            addMetaBlock("Prompt", data.get("Prompt"), true);
            addMetaBlock("Negative", data.get("Negative"), true);

            Separator sep = new Separator();
            sep.setPadding(new Insets(10, 0, 10, 0));
            metaContent.getChildren().add(sep);

            GridPane grid = new GridPane();
            grid.setHgap(10); grid.setVgap(10);

            ColumnConstraints col1 = new ColumnConstraints(); col1.setPercentWidth(50);
            ColumnConstraints col2 = new ColumnConstraints(); col2.setPercentWidth(50);
            grid.getColumnConstraints().addAll(col1, col2);

            String sampler = data.getOrDefault("Sampler", "-");
            String scheduler = data.getOrDefault("Scheduler", "-");

            addGridItem(grid, "Model", data.get("Model"), 0, 0, 2);
            addGridItem(grid, "Sampler", sampler, 0, 1, 1);
            addGridItem(grid, "Scheduler", scheduler, 1, 1, 1);
            addGridItem(grid, "Steps", data.get("Steps"), 0, 2, 1);
            addGridItem(grid, "CFG", data.get("CFG"), 1, 2, 1);
            addGridItem(grid, "Seed", data.get("Seed"), 0, 3, 1);

            String dim = "-";
            if (data.containsKey("Width") && data.containsKey("Height")) {
                dim = data.get("Width") + "x" + data.get("Height");
            }
            addGridItem(grid, "Resolution", dim, 1, 3, 1);

            if (data.containsKey("Loras")) {
                addGridItem(grid, "Loras", data.get("Loras"), 0, 4, 2);
            }

            metaContent.getChildren().add(grid);
        });
        new Thread(currentMetaTask).start();
    }

    private void addMetaBlock(String label, String value, boolean showCopyBtn) {
        if (value == null || value.isEmpty()) return;

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);

        Label lbl = new Label(label);
        lbl.getStyleClass().add("meta-label");
        header.getChildren().add(lbl);

        if (showCopyBtn) {
            Button copyBtn = new Button();
            copyBtn.setGraphic(new FontIcon(FontAwesome.COPY));
            copyBtn.getStyleClass().add("icon-button-small");
            copyBtn.setTooltip(new Tooltip("Copy " + label));
            copyBtn.setOnAction(e -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(value);
                Clipboard.getSystemClipboard().setContent(content);
            });
            header.getChildren().add(copyBtn);
        }

        TextArea textArea = new TextArea(value);
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.getStyleClass().add("meta-value-text-area");
        int rows = Math.min(6, Math.max(1, value.length() / 45));
        textArea.setPrefRowCount(rows);

        VBox box = new VBox(2, header, textArea);
        box.getStyleClass().add("meta-value-box");
        metaContent.getChildren().add(box);
    }

    private void addGridItem(GridPane grid, String label, String value, int col, int row, int colSpan) {
        if (value == null) value = "-";
        VBox box = new VBox(2);
        Label l = new Label(label); l.getStyleClass().add("meta-label");

        TextArea t = new TextArea(value);
        t.setEditable(false);
        t.setWrapText(true);
        t.getStyleClass().add("meta-value-text-area");

        int len = value.length();
        if (colSpan > 1 && len > 40) t.setPrefRowCount(2);
        else t.setPrefRowCount(1);

        t.setMaxWidth(Double.MAX_VALUE);

        box.getChildren().addAll(l, t);
        grid.add(box, col, row, colSpan, 1);
    }

    private void addTagChip(String tag) {
        String displayTag = tag.trim();
        Label chip = new Label(displayTag);
        chip.getStyleClass().add("tag-chip");
        int hue = Math.abs(displayTag.toLowerCase().hashCode()) % 360;
        chip.setStyle("-fx-background-color: hsb(" + hue + ", 60%, 50%);");
        chip.setOnMouseClicked(e -> {
            dataManager.removeTag(currentFiles.get(currentIndex), displayTag);
            tagsFlowPane.getChildren().remove(chip);
        });
        tagsFlowPane.getChildren().add(chip);
    }

    private void deleteCurrentImage() {
        if (currentIndex < 0 || currentFiles.isEmpty()) return;
        try {
            File toDelete = currentFiles.get(currentIndex);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
                Desktop.getDesktop().moveToTrash(toDelete);
            } else {
                toDelete.delete();
            }
            currentFiles.remove(currentIndex);
            if (currentFiles.isEmpty()) {
                mainImageView.setImage(null);
            } else {
                navigate(0);
                populateFilmStrip();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}