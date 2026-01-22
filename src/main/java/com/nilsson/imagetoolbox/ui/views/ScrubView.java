package com.nilsson.imagetoolbox.ui.views;

import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.fontawesome.FontAwesome;
import org.kordamp.ikonli.javafx.FontIcon;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class ScrubView extends ScrollPane {

    private final ImageView previewImageView = new ImageView();
    private final Label statusLabel = new Label("Ready to scrub");
    private File currentFile;

    public ScrubView() {
        this.setFitToWidth(true);
        this.setStyle("-fx-background-color: transparent; -fx-padding: 0;");

        VBox container = new VBox(25);
        container.setPadding(new Insets(40));
        container.setAlignment(Pos.TOP_CENTER);
        container.getStyleClass().add("content-view");

        // Header
        Label title = new Label("Metadata Scrubber");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: -text-primary;");

        Label subtitle = new Label("Remove hidden metadata (EXIF, Prompts, Workflow) for privacy.");
        subtitle.setStyle("-fx-text-fill: -text-secondary; -fx-font-size: 14px;");

        // Clickable Drop Zone
        VBox dropZone = createDropZone();

        // Preview Area
        StackPane previewBox = new StackPane(previewImageView);
        previewBox.setStyle("-fx-background-color: -app-bg-card; -fx-background-radius: 8; -fx-border-color: -app-border; -fx-border-radius: 8;");
        previewBox.setMaxSize(400, 400);
        previewImageView.setFitWidth(380);
        previewImageView.setFitHeight(380);
        previewImageView.setPreserveRatio(true);

        Button btnExport = new Button("Export Clean Copy");
        btnExport.setGraphic(new FontIcon(FontAwesome.SHIELD));
        btnExport.getStyleClass().add("button");
        btnExport.setDisable(true);
        btnExport.setOnAction(e -> exportCleanImage());

        // Enable button only when file is loaded
        previewImageView.imageProperty().addListener((o, old, img) -> btnExport.setDisable(img == null));

        statusLabel.setStyle("-fx-text-fill: -text-muted; -fx-font-style: italic;");

        container.getChildren().addAll(title, subtitle, dropZone, previewBox, statusLabel, btnExport);
        this.setContent(container);
    }

    private VBox createDropZone() {
        VBox dz = new VBox(15);
        dz.getStyleClass().add("drop-zone");
        dz.setPadding(new Insets(30));
        dz.setAlignment(Pos.CENTER);

        // Size Constraints
        dz.setMaxWidth(500);
        dz.setPrefHeight(250);
        dz.setMinHeight(200);

        // --- GRADIENT BORDER STYLE ---
        // 1. Background = Gradient + Deep Background (Layered)
        // 2. Insets = 0 (Gradient), 2 (Deep Background) -> Creates 2px Border
        String defaultStyle =
                "-fx-background-color: -app-grad-primary, -app-bg-deepest;" +
                        "-fx-background-insets: 0, 2;" +
                        "-fx-background-radius: 16;" +
                        "-fx-cursor: hand;";

        // On Hover: Use lighter gradient & add glow
        String activeStyle =
                "-fx-background-color: -app-grad-hover, rgba(69, 162, 158, 0.1);" +
                        "-fx-background-insets: 0, 2;" +
                        "-fx-background-radius: 16;" +
                        "-fx-effect: dropshadow(three-pass-box, -app-cyan, 20, 0, 0, 0);";

        dz.setStyle(defaultStyle);

        // --- ICON ---
        // Using the exact icon from Sidebar: FOLDER_OPEN
        // Using solid accent color (-app-cyan) to ensure it renders (Gradients on Text nodes can glitch)
        FontIcon icon = new FontIcon(FontAwesome.FOLDER_OPEN);
        icon.setIconSize(80);
        icon.setStyle("-fx-fill: -app-cyan; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.5), 5, 0, 0, 0);");

        // Text
        Label mainText = new Label("Drop Image Here");
        mainText.setStyle("-fx-text-fill: -text-primary; -fx-font-size: 18px; -fx-font-weight: bold;");

        Label subText = new Label("or click to browse files");
        subText.setStyle("-fx-text-fill: -text-secondary; -fx-font-size: 12px;");

        dz.getChildren().addAll(icon, mainText, subText);

        // --- DRAG EVENTS ---
        dz.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
                dz.setStyle(activeStyle); // Activate Glow
            }
            e.consume();
        });

        dz.setOnDragExited(e -> dz.setStyle(defaultStyle)); // Restore

        dz.setOnDragDropped(e -> {
            if (e.getDragboard().hasFiles()) {
                loadFile(e.getDragboard().getFiles().get(0));
                e.setDropCompleted(true);
            }
            e.consume();
        });

        // --- CLICK EVENT ---
        dz.setOnMouseClicked(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select Image to Scrub");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.webp"));
            File f = fc.showOpenDialog(this.getScene().getWindow());
            if (f != null) loadFile(f);
        });

        return dz;
    }

    private void loadFile(File file) {
        this.currentFile = file;
        try {
            Image img = new Image(file.toURI().toString());
            previewImageView.setImage(img);
            statusLabel.setText("Loaded: " + file.getName());
            statusLabel.setStyle("-fx-text-fill: -app-cyan; -fx-font-weight: bold;");
        } catch (Exception e) {
            statusLabel.setText("Error loading image");
            statusLabel.setStyle("-fx-text-fill: -app-red-warning;");
        }
    }

    private void exportCleanImage() {
        if (currentFile == null) return;

        FileChooser fc = new FileChooser();
        fc.setTitle("Save Clean Image");
        fc.setInitialFileName("clean_" + currentFile.getName());

        if (currentFile.getParentFile() != null) {
            fc.setInitialDirectory(currentFile.getParentFile());
        }

        File dest = fc.showSaveDialog(this.getScene().getWindow());
        if (dest != null) {
            try {
                BufferedImage bImg = SwingFXUtils.fromFXImage(previewImageView.getImage(), null);
                ImageIO.write(bImg, "png", dest);
                statusLabel.setText("Saved clean copy to " + dest.getName());
                statusLabel.setStyle("-fx-text-fill: #4ade80; -fx-font-weight: bold;");
            } catch (IOException e) {
                statusLabel.setText("Export failed: " + e.getMessage());
                statusLabel.setStyle("-fx-text-fill: -app-red-warning;");
            }
        }
    }
}