package com.nilsson.imagetoolbox.ui.views;

import com.nilsson.imagetoolbox.service.MetadataService;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import org.kordamp.ikonli.fontawesome.FontAwesome;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class GalleryView extends VBox {

    private final FlowPane gridPane;
    private final TextField searchField;
    private File currentDirectory;
    private List<File> allFiles = new ArrayList<>();

    // Background thread pool for loading images
    private final ExecutorService imageLoaderPool = Executors.newFixedThreadPool(4);
    private final MetadataService metadataService;

    public GalleryView(MetadataService metadataService) {
        this.metadataService = metadataService;
        this.setSpacing(10);
        this.setPadding(new Insets(10));
        this.getStyleClass().add("content-view");

        // --- Toolbar ---
        HBox toolbar = new HBox(10);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Button btnLoadFolder = new Button("Open Folder");
        btnLoadFolder.setGraphic(new FontIcon(FontAwesome.FOLDER_OPEN));
        // Add action to open directory chooser...

        searchField = new TextField();
        searchField.setPromptText("Filter by filename or metadata...");
        searchField.setPrefWidth(300);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> filterGallery(newVal));

        toolbar.getChildren().addAll(btnLoadFolder, searchField);

        // --- Grid Area ---
        gridPane = new FlowPane();
        gridPane.setHgap(10);
        gridPane.setVgap(10);
        gridPane.setPadding(new Insets(10));
        gridPane.setAlignment(Pos.TOP_LEFT);

        ScrollPane scrollPane = new ScrollPane(gridPane);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        this.getChildren().addAll(toolbar, scrollPane);

        // Handle Drag Enter (Import)
        this.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });

        this.setOnDragDropped(e -> {
            if(e.getDragboard().hasFiles()) {
                // Logic to copy files into current folder or open them
            }
            e.setDropCompleted(true);
            e.consume();
        });
    }

    public void loadFolder(File directory) {
        this.currentDirectory = directory;
        gridPane.getChildren().clear();

        // 1. List files in background
        Task<List<File>> listTask = new Task<>() {
            @Override
            protected List<File> call() {
                if (directory == null || !directory.isDirectory()) return new ArrayList<>();
                File[] files = directory.listFiles((dir, name) -> {
                    String low = name.toLowerCase();
                    return low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".webp");
                });
                return files == null ? new ArrayList<>() : List.of(files);
            }
        };

        listTask.setOnSucceeded(e -> {
            allFiles = listTask.getValue();
            populateGrid(allFiles);
        });

        new Thread(listTask).start();
    }

    private void populateGrid(List<File> files) {
        gridPane.getChildren().clear();
        for (File file : files) {
            gridPane.getChildren().add(createThumbnailCell(file));
        }
    }

    private StackPane createThumbnailCell(File file) {
        StackPane cell = new StackPane();
        cell.setPrefSize(150, 150);
        cell.getStyleClass().add("grid-cell"); // CSS style for border/hover

        ImageView thumbView = new ImageView();
        thumbView.setFitWidth(140);
        thumbView.setFitHeight(140);
        thumbView.setPreserveRatio(true);

        // Loading spinner placeholder
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(30, 30);
        cell.getChildren().add(spinner);

        // Async Load
        Task<Image> loadTask = new Task<>() {
            @Override
            protected Image call() throws Exception {
                // Load low-res first for speed, or full res scaled
                return new Image(file.toURI().toString(), 200, 200, true, true, true);
            }
        };

        loadTask.setOnSucceeded(e -> {
            thumbView.setImage(loadTask.getValue());
            cell.getChildren().setAll(thumbView); // Remove spinner, add image
        });

        loadTask.setOnFailed(e -> cell.getChildren().clear()); // Handle error

        imageLoaderPool.submit(loadTask);

        // Drag OUT (to Photoshop/Discord)
        cell.setOnDragDetected(e -> {
            Dragboard db = cell.startDragAndDrop(TransferMode.COPY);
            ClipboardContent content = new ClipboardContent();
            content.putFiles(List.of(file));
            db.setContent(content);
            e.consume();
        });

        // Click to view
        cell.setOnMouseClicked(e -> {
            if(e.getClickCount() == 2) {
                // Open Single View logic here
            }
        });

        return cell;
    }

    private void filterGallery(String query) {
        if (query == null || query.isEmpty()) {
            populateGrid(allFiles);
            return;
        }

        String lowerQuery = query.toLowerCase();

        // Quick filter by filename
        List<File> filtered = allFiles.stream()
                .filter(f -> f.getName().toLowerCase().contains(lowerQuery))
                .collect(Collectors.toList());

        // Note: For Metadata filtering, we would need to check if the MetadataService
        // has cached data for these files. Doing a fresh parse here would slow UI.

        populateGrid(filtered);
    }
}