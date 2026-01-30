package com.nilsson.imagetoolbox.ui.views;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.fontawesome.FontAwesome;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;

/**
 * View for the Metadata Scrubber.
 * Responsible for Drag & Drop UI and displaying the preview.
 */
public class ScrubView extends ScrollPane {

    public interface ViewListener {
        void onFileDropped(File file);
        void onBrowseRequested();
        void onExportRequested();
    }

    private ViewListener listener;

    private final ImageView previewImageView = new ImageView();
    private final Label statusLabel = new Label("Ready to scrub");
    private final Button btnExport;

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

        // Drop Zone
        VBox dropZone = createDropZone();

        // Preview Area
        StackPane previewBox = new StackPane(previewImageView);
        previewBox.setStyle("-fx-background-color: -app-bg-card; -fx-background-radius: 8; -fx-border-color: rgba(255,255,255,0.1); -fx-border-width: 1; -fx-border-radius: 8;");
        previewBox.setMaxSize(400, 400);
        previewImageView.setFitWidth(380);
        previewImageView.setFitHeight(380);
        previewImageView.setPreserveRatio(true);

        btnExport = new Button("Export Clean Copy");
        btnExport.setGraphic(new FontIcon(FontAwesome.SHIELD));
        btnExport.getStyleClass().add("button");
        btnExport.setDisable(true);
        btnExport.setOnAction(e -> {
            if (listener != null) listener.onExportRequested();
        });

        statusLabel.setStyle("-fx-text-fill: -text-muted; -fx-font-style: italic;");

        container.getChildren().addAll(title, subtitle, dropZone, previewBox, statusLabel, btnExport);
        this.setContent(container);
    }

    public void setListener(ViewListener listener) {
        this.listener = listener;
    }

    public void displayImage(Image img, String fileName) {
        previewImageView.setImage(img);
        btnExport.setDisable(img == null);

        statusLabel.setText("Loaded: " + fileName);
        statusLabel.setStyle("-fx-text-fill: -app-cyan; -fx-font-weight: bold;");
    }

    public void showFeedback(String msg, boolean isSuccess) {
        statusLabel.setText(msg);
        String color = isSuccess ? "#4ade80" : "-app-red-warning"; // green or red
        statusLabel.setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold;");
    }

    private VBox createDropZone() {
        VBox dz = new VBox(15);
        dz.getStyleClass().add("drop-zone");
        dz.setPadding(new Insets(30));
        dz.setAlignment(Pos.CENTER);
        dz.setMaxWidth(500);
        dz.setPrefHeight(250);
        dz.setMinHeight(200);

        String defaultStyle = "-fx-background-color: -app-grad-primary, -app-bg-deepest; -fx-background-insets: 0, 2; -fx-background-radius: 16; -fx-cursor: hand;";
        String activeStyle = "-fx-background-color: -app-grad-hover, rgba(69, 162, 158, 0.1); -fx-background-insets: 0, 2; -fx-background-radius: 16; -fx-effect: dropshadow(three-pass-box, -app-cyan, 20, 0, 0, 0);";

        dz.setStyle(defaultStyle);

        FontIcon icon = new FontIcon(FontAwesome.FOLDER_OPEN);
        icon.setIconSize(80);
        icon.setStyle("-fx-fill: -app-cyan; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.5), 5, 0, 0, 0);");

        Label mainText = new Label("Drop Image Here");
        mainText.setStyle("-fx-text-fill: -text-primary; -fx-font-size: 18px; -fx-font-weight: bold;");
        Label subText = new Label("or click to browse files");
        subText.setStyle("-fx-text-fill: -text-secondary; -fx-font-size: 12px;");

        dz.getChildren().addAll(icon, mainText, subText);

        // --- EVENTS (Delegated to Controller) ---

        dz.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
                dz.setStyle(activeStyle);
            }
            e.consume();
        });

        dz.setOnDragExited(e -> dz.setStyle(defaultStyle));

        dz.setOnDragDropped(e -> {
            if (e.getDragboard().hasFiles() && listener != null) {
                listener.onFileDropped(e.getDragboard().getFiles().get(0));
                e.setDropCompleted(true);
            }
            e.consume();
        });

        dz.setOnMouseClicked(e -> {
            if (listener != null) listener.onBrowseRequested();
        });

        return dz;
    }
}