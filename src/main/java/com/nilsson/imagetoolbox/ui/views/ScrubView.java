package com.nilsson.imagetoolbox.ui.views;

import com.nilsson.imagetoolbox.ui.viewmodels.ScrubViewModel;
import de.saxsys.mvvmfx.InjectViewModel;
import de.saxsys.mvvmfx.JavaView;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.fontawesome.FontAwesome;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.net.URL;
import java.util.ResourceBundle;

/**
 * View class for the Metadata Scrubber utility.
 * This class provides a graphical interface for loading images via drag-and-drop or file browsing
 * and triggers metadata stripping via the ScrubViewModel. It handles all visual states,
 * including image previews and dynamic status messages based on operation success.
 */
public class ScrubView extends ScrollPane implements JavaView<ScrubViewModel>, Initializable {

    // --- Services & State ---
    @InjectViewModel
    private ScrubViewModel viewModel;

    // --- UI Components ---
    private final ImageView previewImageView = new ImageView();
    private final Label statusLabel = new Label("Ready to scrub");
    private final Button btnExport;

    // --- Constructor & UI Building ---
    public ScrubView() {
        this.setFitToWidth(true);
        this.setStyle("-fx-background-color: transparent; -fx-padding: 0;");

        VBox container = new VBox(25);
        container.setPadding(new Insets(40));
        container.setAlignment(Pos.TOP_CENTER);
        container.getStyleClass().add("content-view");

        Label title = new Label("Metadata Scrubber");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: -text-primary;");
        Label subtitle = new Label("Remove hidden metadata (EXIF, Prompts, Workflow) for privacy.");
        subtitle.setStyle("-fx-text-fill: -text-secondary; -fx-font-size: 14px;");

        VBox dropZone = createDropZone();

        StackPane previewBox = new StackPane(previewImageView);
        previewBox.setStyle("-fx-background-color: -app-bg-card; -fx-background-radius: 8; -fx-border-color: rgba(255,255,255,0.1); -fx-border-width: 1; -fx-border-radius: 8;");
        previewBox.setMaxSize(400, 400);
        previewImageView.setFitWidth(380);
        previewImageView.setFitHeight(380);
        previewImageView.setPreserveRatio(true);

        btnExport = new Button("Export Clean Copy");
        btnExport.setGraphic(new FontIcon(FontAwesome.SHIELD));
        btnExport.getStyleClass().add("button");
        btnExport.setOnAction(e -> handleExport());

        statusLabel.setStyle("-fx-text-fill: -text-muted; -fx-font-style: italic;");

        container.getChildren().addAll(title, subtitle, dropZone, previewBox, statusLabel, btnExport);
        this.setContent(container);
    }

    // --- Lifecycle ---
    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        previewImageView.imageProperty().bind(viewModel.previewImageProperty());
        statusLabel.textProperty().bind(viewModel.statusMessageProperty());
        btnExport.disableProperty().bind(viewModel.isExportDisabledProperty());

        viewModel.successFlagProperty().addListener((obs, old, success) -> {
            String color = success ? "#4ade80" : "-app-red-warning";
            statusLabel.setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold;");
        });
    }

    // --- Event Handlers ---
    private void handleBrowse() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select Image");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.webp"));
        File f = fc.showOpenDialog(getScene().getWindow());
        if (f != null) viewModel.loadFile(f);
    }

    /**
     * Opens a save dialog defaulting to the application's local 'data/' folder
     * to ensure a zero-footprint operation on the host machine.
     */
    private void handleExport() {
        File current = viewModel.currentFileProperty().get();
        if (current == null) return;

        FileChooser fc = new FileChooser();
        fc.setTitle("Save Clean Image");
        fc.setInitialFileName("clean_" + current.getName());

        // Ensure we default to the local data/ directory if it exists
        File localDataDir = new File("data");
        if (localDataDir.exists()) {
            fc.setInitialDirectory(localDataDir);
        } else if (current.getParentFile() != null) {
            fc.setInitialDirectory(current.getParentFile());
        }

        File dest = fc.showSaveDialog(getScene().getWindow());
        if (dest != null) viewModel.exportCleanImage(dest);
    }

    // --- Internal UI Factories ---
    private VBox createDropZone() {
        VBox dz = new VBox(15);
        dz.getStyleClass().add("drop-zone");
        dz.setPadding(new Insets(30));
        dz.setAlignment(Pos.CENTER);
        dz.setMaxWidth(500);
        dz.setPrefHeight(250);

        String defaultStyle = "-fx-background-color: -app-grad-primary, -app-bg-deepest; -fx-background-insets: 0, 2; -fx-background-radius: 16; -fx-cursor: hand;";
        String activeStyle = "-fx-background-color: -app-grad-hover, rgba(69, 162, 158, 0.1); -fx-background-insets: 0, 2; -fx-background-radius: 16; -fx-effect: dropshadow(three-pass-box, -app-cyan, 20, 0, 0, 0);";
        dz.setStyle(defaultStyle);

        FontIcon icon = new FontIcon(FontAwesome.FOLDER_OPEN);
        icon.setIconSize(80);
        icon.setStyle("-fx-fill: -app-cyan;");

        dz.getChildren().addAll(icon, new Label("Drop Image Here"), new Label("or click to browse"));

        dz.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
                dz.setStyle(activeStyle);
            }
            e.consume();
        });
        dz.setOnDragExited(e -> dz.setStyle(defaultStyle));
        dz.setOnDragDropped(e -> {
            if (e.getDragboard().hasFiles()) {
                viewModel.loadFile(e.getDragboard().getFiles().get(0));
                e.setDropCompleted(true);
            }
            e.consume();
        });
        dz.setOnMouseClicked(e -> handleBrowse());

        return dz;
    }
}