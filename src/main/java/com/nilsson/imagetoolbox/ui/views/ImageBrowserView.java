package com.nilsson.imagetoolbox.ui.views;

import com.nilsson.imagetoolbox.ui.components.FolderNav;
import com.nilsson.imagetoolbox.ui.components.ThumbnailCache;
import com.nilsson.imagetoolbox.ui.viewmodels.ImageBrowserViewModel;
import de.saxsys.mvvmfx.InjectViewModel;
import de.saxsys.mvvmfx.JavaView;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import javafx.util.Duration;
import org.controlsfx.control.PopOver;
import org.kordamp.ikonli.javafx.FontIcon;

import javax.imageio.ImageIO;
import java.io.File;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 Main View class for the Image Browser.
 Handles the orchestration of the image gallery, single image viewer,
 metadata inspector drawer, and navigation components using an MVVM pattern.
 */
public class ImageBrowserView extends StackPane implements JavaView<ImageBrowserViewModel>, Initializable {

    public enum ViewMode {BROWSER, GALLERY, LIST}

    // ------------------------------------------------------------------------
    // View Model & Concurrency
    // ------------------------------------------------------------------------

    @InjectViewModel
    private ImageBrowserViewModel viewModel;

    private final ExecutorService thumbnailPool = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("ThumbnailLoader");
        return t;
    });

    private Future<?> currentLoadingTask;

    // ------------------------------------------------------------------------
    // State & Selection
    // ------------------------------------------------------------------------

    private List<File> currentFiles = new ArrayList<>();
    private final List<File> selectedFiles = new ArrayList<>();
    private File lastAnchorFile = null;
    private int currentIndex = -1;
    private ViewMode currentViewMode = ViewMode.BROWSER;
    private boolean isDrawerOpen = false;
    private boolean isDocked = false;

    // ------------------------------------------------------------------------
    // UI Components - Layout Containers
    // ------------------------------------------------------------------------

    private final BorderPane baseLayer;
    private final FolderNav folderNav;
    private final FilmstripView filmstripView;
    private StackPane centerContainer;
    private StackPane singleViewContainer;
    private ScrollPane masonryScrollPane;
    private HBox masonryColumns;
    private ListView<File> fileListView;

    // ------------------------------------------------------------------------
    // UI Components - Overlays & Interaction
    // ------------------------------------------------------------------------

    private ImageView mainImageView;
    private HBox commandPalette;
    private VBox inspectorDrawer;
    private Region drawerBackdrop;
    private StackPane dropOverlay;

    // ------------------------------------------------------------------------
    // UI Components - Inspector Fields
    // ------------------------------------------------------------------------

    private TextField inspectorFilename;
    private HBox starRatingBox;
    private Button dockToggleBtn;
    private TextArea promptArea;
    private TextArea negativePromptArea;
    private TextField softwareField, modelField, seedField, samplerField, schedulerField, cfgField, stepsField, resField;
    private FlowPane lorasFlow;
    private ComboBox<String> collectionCombo;
    private TextField searchField;

    // ------------------------------------------------------------------------
    // Transformation Logic
    // ------------------------------------------------------------------------

    private final Translate zoomTranslate = new Translate();
    private final Scale zoomScale = new Scale();

    // ------------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------------

    public ImageBrowserView() {
        this.getStyleClass().add("image-browser-view");
        this.setAlignment(Pos.TOP_LEFT);
        this.setFocusTraversable(true);
        this.setOnMousePressed(e -> this.requestFocus());

        baseLayer = new BorderPane();
        baseLayer.setMinSize(0, 0);

        this.folderNav = new FolderNav(new FolderNav.FolderNavListener() {
            @Override
            public void onFolderSelected(File folder) {
                viewModel.loadFolder(folder);
            }

            @Override
            public void onCollectionSelected(String name) {
                viewModel.loadCollection(name);
            }

            @Override
            public void onCreateCollection(String name) {
                viewModel.createNewCollection(name);
            }

            @Override
            public void onDeleteCollection(String name) {
                viewModel.deleteCollection(name);
            }

            @Override
            public void onAddFilesToCollection(String name, List<File> files) {
                viewModel.addFilesToCollection(name, files);
            }

            @Override
            public void onSearch(String query) {
                if (searchField != null) searchField.setText(query);
                viewModel.search(query);
            }

            @Override
            public void onShowStarred() {
                viewModel.loadStarred();
            }

            @Override
            public void onPinFolder(File folder) {
                viewModel.pinFolder(folder);
                refreshNav();
            }

            @Override
            public void onUnpinFolder(File folder) {
                viewModel.unpinFolder(folder);
                refreshNav();
            }

            @Override
            public void onFilesMoved() {
            }
        });

        this.filmstripView = new FilmstripView(thumbnailPool);
        this.filmstripView.setOnSelectionChanged(index -> {
            this.currentIndex = index;
            loadImage(currentIndex);
        });

        setupCenterLayout();
        baseLayer.setLeft(folderNav);
        baseLayer.setCenter(centerContainer);
        baseLayer.setBottom(filmstripView);

        setupInputHandlers();
    }

    // ------------------------------------------------------------------------
    // Initialization & Lifecycle
    // ------------------------------------------------------------------------

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        setupCommandPalette();
        setupInspectorDrawer();
        setupDragOverlay();

        this.getChildren().addAll(baseLayer, drawerBackdrop, inspectorDrawer, commandPalette, dropOverlay);

        StackPane.setAlignment(commandPalette, Pos.TOP_CENTER);
        StackPane.setMargin(commandPalette, new Insets(15, 0, 0, 100));
        StackPane.setAlignment(inspectorDrawer, Pos.CENTER_RIGHT);

        refreshNav();

        searchField.textProperty().bindBidirectional(viewModel.searchQueryProperty());

        viewModel.currentFilesProperty().addListener((ListChangeListener<File>) c -> {
            this.currentFiles = new ArrayList<>(viewModel.currentFilesProperty());
            refreshCurrentView();
            if (currentViewMode == ViewMode.GALLERY) populateMasonry(this.currentFiles);
            Platform.runLater(this::requestFocus);
        });

        viewModel.activeMetadataProperty().addListener((obs, old, meta) -> updateInspector(meta));
        viewModel.activeRatingProperty().addListener((obs, old, rating) -> updateRatingUI(rating.intValue()));

        folderNav.setCollections(viewModel.getCollectionList());
        viewModel.getCollectionList().addListener((ListChangeListener<String>) c -> {
            folderNav.setCollections(viewModel.getCollectionList());
            if (collectionCombo != null) collectionCombo.setItems(viewModel.getCollectionList());
        });

        setViewMode(ViewMode.BROWSER);

        Platform.runLater(() -> {
            File lastFolder = viewModel.getLastFolder();
            if (lastFolder != null && lastFolder.exists()) viewModel.loadFolder(lastFolder);
            this.requestFocus();
        });

        mainImageView.imageProperty().addListener((obs, old, img) -> {
            if (img != null && (resField.getText().equals("-") || resField.getText().isEmpty())) {
                resField.setText((int) img.getWidth() + "x" + (int) img.getHeight());
            }
        });
    }

    // ------------------------------------------------------------------------
    // Inspector Drawer UI Setup
    // ------------------------------------------------------------------------

    private void setupInspectorDrawer() {
        inspectorDrawer = new VBox(0);
        inspectorDrawer.getStyleClass().add("inspector-drawer");
        inspectorDrawer.setPrefWidth(380);
        inspectorDrawer.setMinWidth(380);
        inspectorDrawer.setMaxWidth(380);
        inspectorDrawer.setTranslateX(380);

        drawerBackdrop = new Region();
        drawerBackdrop.setStyle("-fx-background-color: rgba(0,0,0,0.4);");
        drawerBackdrop.setVisible(false);
        drawerBackdrop.setOnMouseClicked(e -> closeDrawer());

        VBox headerContainer = new VBox(10);
        headerContainer.getStyleClass().add("inspector-header");
        headerContainer.setAlignment(Pos.CENTER_LEFT);
        headerContainer.setPadding(new Insets(15));

        inspectorFilename = new TextField("No Selection");
        inspectorFilename.setEditable(false);
        inspectorFilename.getStyleClass().add("inspector-filename-field");
        inspectorFilename.setMaxWidth(Double.MAX_VALUE);

        HBox buttonRow = new HBox(15);
        buttonRow.setAlignment(Pos.CENTER_LEFT);
        Button openFileBtn = createLargeIconButton("fa-folder-open:16:white", "Open Location", e -> openFileLocation(getCurrentFile()));
        Button rawDataBtn = createLargeIconButton("fa-code:16:white", "Raw Metadata", e -> showRawMetadataPopup(viewModel.getRawMetadata(getCurrentFile())));
        dockToggleBtn = createLargeIconButton("fa-columns:16:white", "Snap to Side", e -> toggleDock());
        Button closeBtn = createLargeIconButton("fa-close:16:white", "Close Panel", e -> closeDrawer());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        buttonRow.getChildren().addAll(openFileBtn, rawDataBtn, dockToggleBtn, spacer, closeBtn);

        headerContainer.getChildren().addAll(inspectorFilename, buttonRow);

        ScrollPane scrollContent = new ScrollPane();
        scrollContent.setFitToWidth(true);
        scrollContent.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        scrollContent.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        VBox content = new VBox(20);
        content.setPadding(new Insets(15));
        content.setMaxWidth(350);

        starRatingBox = new HBox(5);
        starRatingBox.setAlignment(Pos.CENTER);
        for (int i = 1; i <= 5; i++) {
            Button star = new Button();
            star.getStyleClass().add("star-button");
            star.setGraphic(new FontIcon("fa-star-o:20:#808080"));
            final int r = i;
            star.setOnAction(e -> viewModel.setRating(r));
            starRatingBox.getChildren().add(star);
        }

        Label metaHeader = new Label("METADATA");
        metaHeader.getStyleClass().add("section-header-large");
        metaHeader.setAlignment(Pos.CENTER);
        metaHeader.setMaxWidth(Double.MAX_VALUE);

        VBox posPromptBox = createPromptSection("PROMPT", true);
        promptArea = (TextArea) posPromptBox.getChildren().get(1);

        VBox negPromptBox = createPromptSection("NEGATIVE PROMPT", false);
        negativePromptArea = (TextArea) negPromptBox.getChildren().get(1);

        GridPane techGrid = new GridPane();
        techGrid.setHgap(15);
        techGrid.setVgap(15);

        softwareField = addTechItem(techGrid, "Software", 0, 0, 2);
        modelField = addTechItem(techGrid, "Model", 0, 1, 2);
        seedField = addTechItem(techGrid, "Seed", 0, 2, 1);
        resField = addTechItem(techGrid, "Resolution", 1, 2, 1);
        samplerField = addTechItem(techGrid, "Sampler", 0, 3, 1);
        schedulerField = addTechItem(techGrid, "Scheduler", 1, 3, 1);
        cfgField = addTechItem(techGrid, "CFG", 0, 4, 1);
        stepsField = addTechItem(techGrid, "Steps", 1, 4, 1);

        VBox loraBox = new VBox(8);
        Label loraTitle = new Label("RESOURCES / LoRAs");
        loraTitle.getStyleClass().add("section-label");
        lorasFlow = new FlowPane(6, 6);
        lorasFlow.setMaxWidth(340);
        loraBox.getChildren().addAll(loraTitle, lorasFlow);

        HBox collectionBox = new HBox(10);
        collectionBox.setAlignment(Pos.CENTER_LEFT);
        collectionCombo = new ComboBox<>();
        collectionCombo.setPromptText("Add to Collection...");
        collectionCombo.setMaxWidth(Double.MAX_VALUE);
        collectionCombo.getStyleClass().add("collection-combo");
        HBox.setHgrow(collectionCombo, Priority.ALWAYS);
        Button newColBtn = createIconButton("fa-plus:14:white", "New Collection", e -> {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("New Collection");
            dialog.setHeaderText("Enter collection name:");
            dialog.initOwner(this.getScene().getWindow());
            dialog.showAndWait().ifPresent(name -> viewModel.createNewCollection(name));
        });
        Button addColBtn = createIconButton("fa-check:14:white", "Add to Collection", e -> {
            String col = collectionCombo.getValue();
            if (col != null) viewModel.addSelectedToCollection(col);
        });
        collectionBox.getChildren().addAll(collectionCombo, newColBtn, addColBtn);

        content.getChildren().addAll(
                starRatingBox,
                new Separator(),
                metaHeader,
                posPromptBox,
                negPromptBox,
                new Separator(),
                techGrid,
                new Separator(),
                loraBox,
                new Region(),
                collectionBox
        );
        scrollContent.setContent(content);

        inspectorDrawer.getChildren().addAll(headerContainer, scrollContent);
        VBox.setVgrow(scrollContent, Priority.ALWAYS);
    }

    // ------------------------------------------------------------------------
    // UI Helpers & Builders
    // ------------------------------------------------------------------------

    private TextField addTechItem(GridPane grid, String title, int col, int row, int colSpan) {
        VBox box = new VBox(2);
        Label t = new Label(title);
        t.getStyleClass().add("tech-grid-label");
        TextField v = new TextField("-");
        v.setEditable(false);
        v.getStyleClass().add("tech-grid-value-field");
        v.setMaxWidth(colSpan > 1 ? 330 : 150);
        box.getChildren().addAll(t, v);
        grid.add(box, col, row, colSpan, 1);
        return v;
    }

    private void addLoraChip(String text) {
        TextField tag = new TextField(text);
        tag.setEditable(false);
        tag.getStyleClass().add("lora-chip-field");
        int approxWidth = 20 + (text.length() * 7);
        tag.setPrefWidth(Math.min(approxWidth, 330));
        tag.setMinWidth(Region.USE_PREF_SIZE);
        tag.setMaxWidth(330);
        lorasFlow.getChildren().add(tag);
    }

    private Button createLargeIconButton(String iconLiteral, String tooltipText, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button btn = new Button();
        btn.setGraphic(new FontIcon(iconLiteral));
        btn.getStyleClass().add("icon-button-large");
        if (tooltipText != null) {
            Tooltip tt = new Tooltip(tooltipText);
            tt.setShowDelay(Duration.millis(50));
            btn.setTooltip(tt);
        }
        btn.setOnAction(action);
        return btn;
    }

    private Button createIconButton(String iconLiteral, String tooltip, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button btn = new Button();
        btn.setGraphic(new FontIcon(iconLiteral));
        btn.getStyleClass().add("icon-button");
        if (tooltip != null) btn.setTooltip(new Tooltip(tooltip));
        btn.setOnAction(action);
        return btn;
    }

    private VBox createPromptSection(String title, boolean isPositive) {
        VBox box = new VBox(5);
        HBox titleRow = new HBox(10);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label lbl = new Label(title);
        lbl.getStyleClass().add("section-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button copyBtn = new Button();
        copyBtn.setGraphic(new FontIcon("fa-copy:12:#aaaaaa"));
        copyBtn.getStyleClass().add("icon-button-small");
        copyBtn.setTooltip(new Tooltip("Copy Text"));
        TextArea area = new TextArea();
        area.getStyleClass().add("prompt-block");
        area.setWrapText(true);
        area.setEditable(false);
        area.setPrefHeight(isPositive ? 100 : 60);
        area.setMaxWidth(330);
        copyBtn.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(area.getText());
            Clipboard.getSystemClipboard().setContent(cc);
        });
        titleRow.getChildren().addAll(lbl, spacer, copyBtn);
        box.getChildren().addAll(titleRow, area);
        return box;
    }

    // ------------------------------------------------------------------------
    // Command Palette & Filters
    // ------------------------------------------------------------------------

    private void setupCommandPalette() {
        commandPalette = new HBox(10);
        commandPalette.getStyleClass().add("command-palette");
        commandPalette.setPrefHeight(45);
        commandPalette.setMaxHeight(45);
        commandPalette.setMaxWidth(500);
        commandPalette.setAlignment(Pos.CENTER_LEFT);
        Label searchIcon = new Label();
        searchIcon.setGraphic(new FontIcon("fa-search:14:white"));
        searchField = new TextField();
        searchField.setPromptText("Search...");
        searchField.getStyleClass().add("palette-search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        Label modelChip = createFilterChip("Model", viewModel.availableModelsProperty(), viewModel.selectedModelProperty());
        Label loraChip = createFilterChip("LoRA", FXCollections.observableArrayList("All"), new javafx.beans.property.SimpleObjectProperty<>("All"));
        Label samplerChip = createFilterChip("Sampler", viewModel.availableSamplersProperty(), viewModel.selectedSamplerProperty());
        commandPalette.getChildren().addAll(searchIcon, searchField, modelChip, loraChip, samplerChip);
    }

    private Label createFilterChip(String label, ObservableList<String> options, javafx.beans.property.ObjectProperty<String> property) {
        Label chip = new Label(label);
        chip.getStyleClass().add("filter-chip");
        chip.setOnMouseClicked(e -> {
            ListView<String> list = new ListView<>(options);
            list.setPrefSize(200, 250);
            list.getStyleClass().add("popover-list");
            PopOver pop = new PopOver(list);
            pop.setArrowLocation(PopOver.ArrowLocation.TOP_CENTER);
            if (getScene() != null) pop.getRoot().getStylesheets().setAll(getScene().getStylesheets());
            pop.show(chip);
            list.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> {
                if (val != null) {
                    property.set(val);
                    pop.hide();
                    viewModel.performSearch();
                }
            });
        });
        property.addListener((obs, old, val) -> {
            if (val == null || val.equals("All")) {
                chip.setText(label);
                chip.getStyleClass().remove("active");
            } else {
                chip.setText(label + ": " + val);
                if (!chip.getStyleClass().contains("active")) chip.getStyleClass().add("active");
            }
        });
        return chip;
    }

    // ------------------------------------------------------------------------
    // Drag & Drop
    // ------------------------------------------------------------------------

    private void setupDragOverlay() {
        dropOverlay = new StackPane();
        dropOverlay.getStyleClass().add("drag-overlay");
        dropOverlay.setVisible(false);
        dropOverlay.setMouseTransparent(true);
        Label dropText = new Label("Drop folder to load library");
        dropText.setStyle("-fx-font-size: 24px; -fx-text-fill: white; -fx-font-weight: bold; -fx-effect: dropshadow(gaussian, black, 10, 0.5, 0, 0);");
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
            Dragboard db = e.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                File f = db.getFiles().get(0);
                if (f.isDirectory()) viewModel.loadFolder(f);
                else viewModel.loadFolder(f.getParentFile());
                success = true;
            }
            dropOverlay.setVisible(false);
            e.setDropCompleted(success);
            e.consume();
        });
    }

    // ------------------------------------------------------------------------
    // Drawer Logic
    // ------------------------------------------------------------------------

    private void toggleDock() {
        isDocked = !isDocked;
        FontIcon icon = (FontIcon) dockToggleBtn.getGraphic();
        icon.setIconLiteral(isDocked ? "fa-columns:16:#0078d7" : "fa-columns:16:white");
        if (isDocked) {
            if (this.getChildren().contains(inspectorDrawer)) this.getChildren().remove(inspectorDrawer);
            inspectorDrawer.setTranslateX(0);
            baseLayer.setRight(inspectorDrawer);
            drawerBackdrop.setVisible(false);
        } else {
            baseLayer.setRight(null);
            if (!this.getChildren().contains(inspectorDrawer)) {
                int idx = this.getChildren().indexOf(drawerBackdrop);
                this.getChildren().add(idx + 1, inspectorDrawer);
            }
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

    // ------------------------------------------------------------------------
    // Navigation & View Mode Management
    // ------------------------------------------------------------------------

    private void refreshCurrentView() {
        currentIndex = 0;
        selectedFiles.clear();
        lastAnchorFile = null;
        filmstripView.setFiles(currentFiles);
        if (currentViewMode == ViewMode.LIST) fileListView.getItems().setAll(currentFiles);
        if (!currentFiles.isEmpty()) {
            handleSelectionClick(currentFiles.get(0), false, false);
            if (currentViewMode == ViewMode.BROWSER) loadImage(0);
        } else {
            mainImageView.setImage(null);
            viewModel.updateSelection(Collections.emptyList());
            updateInspector(new HashMap<>());
        }
    }

    public void setViewMode(ViewMode mode) {
        this.currentViewMode = mode;
        centerContainer.getChildren().clear();
        if (mode == ViewMode.GALLERY) {
            populateMasonry(currentFiles);
            centerContainer.getChildren().add(masonryScrollPane);
            baseLayer.setBottom(null);
        } else if (mode == ViewMode.LIST) {
            fileListView.getItems().setAll(currentFiles);
            centerContainer.getChildren().add(fileListView);
            baseLayer.setBottom(null);
        } else {
            centerContainer.getChildren().add(singleViewContainer);
            baseLayer.setBottom(filmstripView);
            if (currentIndex >= 0) loadImage(currentIndex);
        }
    }

    private void navigate(int dir) {
        if (currentFiles.isEmpty()) return;
        int newIndex = (currentIndex + dir) % currentFiles.size();
        if (newIndex < 0) newIndex += currentFiles.size();
        loadImage(newIndex);
    }

    // ------------------------------------------------------------------------
    // Selection & Data Updates
    // ------------------------------------------------------------------------

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

    private void updateInspector(Map<String, String> meta) {
        if (getCurrentFile() == null) {
            inspectorFilename.setText("No Selection");
            promptArea.setText("");
            negativePromptArea.setText("");
            lorasFlow.getChildren().clear();
            return;
        }
        inspectorFilename.setText(getCurrentFile().getName());
        promptArea.setText(meta.getOrDefault("Prompt", ""));
        String neg = meta.get("Negative");
        if (neg == null) neg = meta.get("Negative Prompt");
        negativePromptArea.setText(neg != null ? neg : "");
        seedField.setText(meta.getOrDefault("Seed", "-"));
        samplerField.setText(meta.getOrDefault("Sampler", "-"));
        schedulerField.setText(meta.getOrDefault("Scheduler", "-"));
        cfgField.setText(meta.getOrDefault("CFG", "-"));
        stepsField.setText(meta.getOrDefault("Steps", "-"));
        modelField.setText(meta.getOrDefault("Model", "-"));
        if (meta.containsKey("Width") && meta.containsKey("Height")) {
            resField.setText(meta.get("Width") + "x" + meta.get("Height"));
        } else {
            resField.setText(meta.getOrDefault("Resolution", "-"));
        }
        String soft = meta.get("Software");
        if (soft == null) soft = meta.get("Generator");
        if (soft == null) soft = meta.getOrDefault("Tool", "Unknown");
        softwareField.setText(soft);
        lorasFlow.getChildren().clear();
        String loraRaw = meta.get("Loras");
        if (loraRaw == null) loraRaw = meta.get("LoRAs");
        if (loraRaw == null) loraRaw = meta.get("Resources");
        if (loraRaw != null && !loraRaw.isEmpty()) {
            for (String lora : loraRaw.split(",")) {
                addLoraChip(lora.trim());
            }
        } else {
            Matcher m = Pattern.compile("<lora:([^:]+):").matcher(promptArea.getText());
            while (m.find()) addLoraChip(m.group(1));
        }
    }

    private void updateRatingUI(int rating) {
        for (int i = 0; i < starRatingBox.getChildren().size(); i++) {
            Button b = (Button) starRatingBox.getChildren().get(i);
            FontIcon icon = (FontIcon) b.getGraphic();
            if (i < rating) icon.setIconLiteral("fa-star:20:#FFD700");
            else icon.setIconLiteral("fa-star-o:20:#808080");
        }
    }

    private void refreshNav() {
        folderNav.setPinnedFolders(viewModel.getPinnedFolders());
    }

    // ------------------------------------------------------------------------
    // Image Loading & Processing
    // ------------------------------------------------------------------------

    private void loadImage(int index) {
        if (currentLoadingTask != null && !currentLoadingTask.isDone()) currentLoadingTask.cancel(true);
        if (index < 0 || index >= currentFiles.size()) {
            Platform.runLater(() -> mainImageView.setImage(null));
            return;
        }
        File file = currentFiles.get(index);
        this.currentIndex = index;
        if (currentViewMode == ViewMode.BROWSER && selectedFiles.size() <= 1) handleSelectionClick(file, false, false);
        resetZoom();
        mainImageView.setImage(null);
        currentLoadingTask = thumbnailPool.submit(() -> {
            if (Thread.currentThread().isInterrupted()) return;
            Image img = loadRobustImage(file, 0);
            Platform.runLater(() -> {
                if (currentIndex == index) mainImageView.setImage(img);
            });
        });
        filmstripView.setSelectedIndex(index);
    }

    private Image loadRobustImage(File file, double width) {
        try {
            Image img = new Image(file.toURI().toString(), width, 0, true, true, false);
            if (img.isError()) return SwingFXUtils.toFXImage(ImageIO.read(file), null);
            return img;
        } catch (Exception ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------------
    // Center Layout & Interaction Logic
    // ------------------------------------------------------------------------

    private void setupCenterLayout() {
        centerContainer = new StackPane();
        centerContainer.getStyleClass().add("center-stack");
        centerContainer.setMinSize(0, 0);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(centerContainer.widthProperty());
        clip.heightProperty().bind(centerContainer.heightProperty());
        centerContainer.setClip(clip);
        mainImageView = new ImageView();
        mainImageView.setPreserveRatio(true);
        mainImageView.setSmooth(true);
        mainImageView.fitWidthProperty().bind(centerContainer.widthProperty().subtract(40));
        mainImageView.fitHeightProperty().bind(centerContainer.heightProperty().subtract(40));
        mainImageView.getTransforms().addAll(zoomScale, zoomTranslate);
        setupZoomAndPan(mainImageView, centerContainer, zoomTranslate, zoomScale, this::toggleDrawer);
        singleViewContainer = new StackPane(mainImageView);
        singleViewContainer.setAlignment(Pos.CENTER);
        singleViewContainer.setMinSize(0, 0);
        setupMasonryGrid();
        fileListView = new ListView<>();
        fileListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(File item, boolean empty) {
                super.updateItem(item, empty);
                setText((empty || item == null) ? null : item.getName());
            }
        });
        fileListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && fileListView.getSelectionModel().getSelectedItem() != null) {
                handleSelectionClick(fileListView.getSelectionModel().getSelectedItem(), false, false);
                setViewMode(ViewMode.BROWSER);
            }
        });
    }

    private void setupMasonryGrid() {
        masonryColumns = new HBox(10);
        masonryColumns.setAlignment(Pos.TOP_CENTER);
        masonryColumns.setPadding(new Insets(10));
        for (int i = 0; i < 5; i++) {
            VBox col = new VBox(10);
            HBox.setHgrow(col, Priority.ALWAYS);
            col.setAlignment(Pos.TOP_CENTER);
            masonryColumns.getChildren().add(col);
        }
        masonryScrollPane = new ScrollPane(masonryColumns);
        masonryScrollPane.setFitToWidth(true);
        masonryScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        masonryScrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        masonryScrollPane.setMinSize(0, 0);
    }

    private void populateMasonry(List<File> files) {
        for (Node n : masonryColumns.getChildren()) ((VBox) n).getChildren().clear();
        int colIndex = 0;
        int colCount = masonryColumns.getChildren().size();
        for (File file : files) {
            ImageGridCell cell = new ImageGridCell(file);
            ((VBox) masonryColumns.getChildren().get(colIndex)).getChildren().add(cell);
            colIndex = (colIndex + 1) % colCount;
        }
    }

    // ------------------------------------------------------------------------
    // Zoom & Pan & Inputs
    // ------------------------------------------------------------------------

    private void setupZoomAndPan(ImageView view, Pane container, Translate translate, Scale scale, Runnable clickAction) {
        container.setOnScroll(e -> {
            if (e.isControlDown() || e.getDeltaY() != 0) {
                e.consume();
                double zoomFactor = (e.getDeltaY() > 0) ? 1.1 : 0.9;
                double newScale = Math.max(0.1, Math.min(20.0, scale.getX() * zoomFactor));
                scale.setX(newScale);
                scale.setY(newScale);
            }
        });
        class DragState {
            double startX, startY, transX, transY;
            boolean isDragging;
        }
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
            if (!state.isDragging && e.getButton() == MouseButton.PRIMARY && clickAction != null) clickAction.run();
        });
        container.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) resetZoom();
        });
    }

    private void resetZoom() {
        zoomScale.setX(1);
        zoomScale.setY(1);
        zoomTranslate.setX(0);
        zoomTranslate.setY(0);
    }

    private void setupInputHandlers() {
        this.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getTarget() instanceof TextInputControl) return;
            switch (e.getCode()) {
                case LEFT:
                case A:
                    navigate(-1);
                    e.consume();
                    break;
                case RIGHT:
                case D:
                    navigate(1);
                    e.consume();
                    break;
                case F:
                    toggleDock();
                    e.consume();
                    break;
                case S:
                    viewModel.toggleStar();
                    e.consume();
                    break;
            }
        });
    }

    // ------------------------------------------------------------------------
    // External Operations
    // ------------------------------------------------------------------------

    private void openFileLocation(File file) {
        if (file != null) new Thread(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(file.getParentFile());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void showRawMetadataPopup(String rawText) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Raw Metadata");
        alert.setHeaderText(null);
        TextArea area = new TextArea(rawText);
        area.setEditable(false);
        area.setWrapText(true);
        area.setMaxWidth(Double.MAX_VALUE);
        area.setMaxHeight(Double.MAX_VALUE);
        alert.getDialogPane().setContent(area);
        alert.showAndWait();
    }

    private File getCurrentFile() {
        return (currentIndex >= 0 && currentIndex < currentFiles.size()) ? currentFiles.get(currentIndex) : null;
    }

    // ------------------------------------------------------------------------
    // Inner Classes
    // ------------------------------------------------------------------------

    private class ImageGridCell extends StackPane {
        public ImageGridCell(File file) {
            this.getStyleClass().add("grid-cell");
            ImageView iv = new ImageView();
            iv.setPreserveRatio(true);
            iv.setFitWidth(150);
            Image cached = ThumbnailCache.get(file.getAbsolutePath());
            if (cached != null) iv.setImage(cached);
            else {
                thumbnailPool.submit(() -> {
                    Image img = loadRobustImage(file, 200);
                    if (img != null) {
                        ThumbnailCache.put(file.getAbsolutePath(), img);
                        Platform.runLater(() -> iv.setImage(img));
                    }
                });
            }
            this.getChildren().add(iv);
            this.setPadding(new Insets(5));
            this.setStyle("-fx-background-color: #2b2b2b; -fx-background-radius: 8; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 5, 0, 0, 2);");
            this.setOnMouseClicked(e -> {
                handleSelectionClick(file, e.isShiftDown(), e.isShortcutDown());
                if (e.getClickCount() == 2) setViewMode(ViewMode.BROWSER);
                if (selectedFiles.contains(file))
                    this.setStyle("-fx-background-color: #0078d7; -fx-background-radius: 8;");
                else this.setStyle("-fx-background-color: #2b2b2b; -fx-background-radius: 8;");
            });
            if (selectedFiles.contains(file)) this.setStyle("-fx-background-color: #0078d7; -fx-background-radius: 8;");
        }
    }
}