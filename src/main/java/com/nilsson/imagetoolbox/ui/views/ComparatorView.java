package com.nilsson.imagetoolbox.ui.views;

import com.nilsson.imagetoolbox.ui.components.ImageLoader;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.fontawesome.FontAwesome;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;

/**
 <h2>ComparatorView</h2>
 <p>
 A high-level UI component designed for side-by-side visual analysis of two images.
 It provides an interactive "curtain" slider interface that allows users to reveal
 differences between two loaded images (A and B) in real-time.
 </p>

 <h3>Core Features:</h3>
 <ul>
 <li><b>Interactive Slider:</b> Uses a clipping geometry system ({@link Rectangle}) and a
 dynamically positioned {@link Line} to perform a split-screen comparison.</li>
 <li><b>Dual-Input Slots:</b> Provides two distinct drop zones supporting both
 Drag-and-Drop and standard file system dialogs via {@link FileChooser}.</li>
 <li><b>Smart Visibility:</b> Automatically transitions from a configuration/drop
 layer to the comparison slider once both image slots are populated.</li>
 <li><b>Smart Drop Logic:</b> Intelligently determines which image slot to update based
 on cursor position when dragging files onto the active comparison view.</li>
 </ul>
 *

 @author Nilsson */
public class ComparatorView extends VBox {

    // ------------------------------------------------------------------------
    // UI Components & Layers
    // ------------------------------------------------------------------------

    private final ImageView imageViewA;
    private final ImageView imageViewB;
    private final StackPane paneA;
    private final StackPane paneB;
    private final StackPane imageContainer;
    private final Line dividerLine;
    private final Rectangle clipA;
    private final Rectangle clipB;

    // ------------------------------------------------------------------------
    // Constructor & Layout Assembly
    // ------------------------------------------------------------------------

    /**
     Constructs the ComparatorView, establishing the layout hierarchy, styling,
     and bidirectional property bindings for the comparison engine.
     */
    public ComparatorView() {
        this.getStyleClass().add("comparator-view");
        this.setAlignment(Pos.TOP_CENTER);
        this.setSpacing(10);
        this.setPadding(new Insets(20));

        // --- Header ---
        VBox header = new VBox(5);
        header.setAlignment(Pos.CENTER);

        Label title = new Label("Comparator");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: -text-primary;");

        Label subtitle = new Label("Compare images by dropping them into the slots below.");
        subtitle.setStyle("-fx-text-fill: -text-secondary; -fx-font-size: 14px;");

        header.getChildren().addAll(title, subtitle);

        // --- Image Layer (The Slider View) ---
        imageContainer = new StackPane();
        imageContainer.setAlignment(Pos.CENTER);
        imageContainer.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        imageViewA = createImageView();
        imageViewB = createImageView();

        paneA = new StackPane(imageViewA);
        paneA.setAlignment(Pos.CENTER);

        paneB = new StackPane(imageViewB);
        paneB.setAlignment(Pos.CENTER);

        imageViewA.fitWidthProperty().bind(paneA.widthProperty());
        imageViewA.fitHeightProperty().bind(paneA.heightProperty());
        imageViewB.fitWidthProperty().bind(paneB.widthProperty());
        imageViewB.fitHeightProperty().bind(paneB.heightProperty());

        clipB = new Rectangle();
        clipB.widthProperty().bind(imageContainer.widthProperty().multiply(0.5));
        clipB.heightProperty().bind(imageContainer.heightProperty());
        clipB.xProperty().bind(imageContainer.widthProperty().multiply(0.5));
        paneB.setClip(clipB);

        clipA = new Rectangle();
        clipA.widthProperty().bind(imageContainer.widthProperty().multiply(0.5));
        clipA.heightProperty().bind(imageContainer.heightProperty());
        paneA.setClip(clipA);

        dividerLine = new Line();
        dividerLine.setStrokeWidth(2);
        dividerLine.setStyle("-fx-stroke: white; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.8), 5, 0, 0, 0);");
        dividerLine.setStartY(0);
        dividerLine.endYProperty().bind(imageContainer.heightProperty());
        dividerLine.setManaged(false);

        imageContainer.getChildren().addAll(paneA, paneB, dividerLine);

        imageContainer.setOnMouseMoved(e -> {
            Point2D p = imageContainer.sceneToLocal(e.getSceneX(), e.getSceneY());
            updateSliderPosition(p.getX());
        });
        imageContainer.setOnMouseDragged(e -> {
            Point2D p = imageContainer.sceneToLocal(e.getSceneX(), e.getSceneY());
            updateSliderPosition(p.getX());
        });

        imageContainer.widthProperty().addListener((obs, old, w) -> updateSliderPosition(w.doubleValue() / 2));

        // --- Drop Layer (The Input Slots) ---
        HBox dropLayer = new HBox(20);
        dropLayer.setAlignment(Pos.CENTER);
        dropLayer.setMaxSize(600, 300);

        Node slotA = createSlot("Image A (Left)", imageViewA);
        Node slotB = createSlot("Image B (Right)", imageViewB);

        dropLayer.getChildren().addAll(slotA, slotB);

        // --- Visibility Logic ---
        imageContainer.visibleProperty().bind(imageViewA.imageProperty().isNotNull().and(imageViewB.imageProperty().isNotNull()));
        dropLayer.visibleProperty().bind(imageContainer.visibleProperty().not());

        // --- Controls ---
        HBox controls = new HBox(10);
        controls.setAlignment(Pos.CENTER);
        controls.setPadding(new Insets(10));

        Button resetBtn = new Button("Reset");
        resetBtn.getStyleClass().add("button");
        resetBtn.setOnAction(e -> {
            imageViewA.setImage(null);
            imageViewB.setImage(null);
            updateSliderPosition(imageContainer.getWidth() / 2);
        });

        controls.getChildren().addAll(resetBtn);

        // --- Content Stack ---
        StackPane contentStack = new StackPane();
        contentStack.getChildren().addAll(imageContainer, dropLayer);
        VBox.setVgrow(contentStack, Priority.ALWAYS);

        this.getChildren().addAll(header, contentStack, controls);

        this.setOnDragEntered(e -> e.consume());
        this.setOnDragExited(e -> e.consume());
        this.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        this.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles()) {
                File file = db.getFiles().get(0);
                Image img = ImageLoader.load(file, 0, 0);
                if (imageContainer.isVisible()) {
                    Point2D p = imageContainer.sceneToLocal(e.getSceneX(), e.getSceneY());
                    if (p.getX() < imageContainer.getWidth() / 2) imageViewA.setImage(img);
                    else imageViewB.setImage(img);
                } else {
                    if (imageViewA.getImage() == null) imageViewA.setImage(img);
                    else imageViewB.setImage(img);
                }
                e.setDropCompleted(true);
            }
            e.consume();
        });
    }

    // ------------------------------------------------------------------------
    // Internal Logic & Utility Methods
    // ------------------------------------------------------------------------

    /**
     Recalculates the divider position and updates the rectangular clips
     for both image panes based on the provided X-coordinate.
     * @param x The local X-coordinate of the divider.
     */
    private void updateSliderPosition(double x) {
        double width = imageContainer.getWidth();
        if (width <= 0) return;

        x = Math.max(0, Math.min(x, width));

        dividerLine.setStartX(x);
        dividerLine.setEndX(x);

        clipA.widthProperty().unbind();
        clipA.setWidth(x);

        clipB.xProperty().unbind();
        clipB.widthProperty().unbind();
        clipB.setX(x);
        clipB.setWidth(width - x);
    }

    /**
     Factory method for creating aspect-ratio preserved {@link ImageView} instances.
     * @return A configured ImageView.
     */
    private ImageView createImageView() {
        ImageView iv = new ImageView();
        iv.setPreserveRatio(true);
        return iv;
    }

    /**
     Creates an interactive input slot that functions as both a preview thumbnail
     and a drag-and-drop/click-to-browse target.
     * @param labelText The title displayed in the drop zone.

     @param target The target ImageView that will store the selected image.

     @return A Node containing the interactive slot UI.
     */
    private Node createSlot(String labelText, ImageView target) {
        StackPane slotStack = new StackPane();
        slotStack.setPrefSize(250, 250);
        slotStack.setMaxSize(250, 250);

        // 1. The Input UI (Drop Zone)
        VBox inputUI = new VBox(10);
        inputUI.getStyleClass().add("drop-zone");
        inputUI.setAlignment(Pos.CENTER);

        String defaultStyle = "-fx-background-color: -app-grad-primary, -app-bg-deepest; -fx-background-insets: 0, 2; -fx-background-radius: 16; -fx-cursor: hand;";
        String activeStyle = "-fx-background-color: -app-grad-hover, rgba(69, 162, 158, 0.1); -fx-background-insets: 0, 2; -fx-background-radius: 16; -fx-effect: dropshadow(three-pass-box, -app-cyan, 20, 0, 0, 0);";
        inputUI.setStyle(defaultStyle);

        FontIcon icon = new FontIcon(FontAwesome.FOLDER_OPEN);
        icon.setIconSize(50);
        icon.setStyle("-fx-fill: -app-cyan;");

        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: -text-primary;");
        Label sub = new Label("Drop or Click");
        sub.setStyle("-fx-text-fill: -text-secondary; -fx-font-size: 11px;");

        inputUI.getChildren().addAll(icon, lbl, sub);

        // 2. The Preview UI (Thumbnail)
        ImageView preview = new ImageView();
        preview.setPreserveRatio(true);
        preview.setFitWidth(240);
        preview.setFitHeight(240);
        preview.imageProperty().bind(target.imageProperty());

        inputUI.visibleProperty().bind(target.imageProperty().isNull());
        preview.visibleProperty().bind(target.imageProperty().isNotNull());

        slotStack.getChildren().addAll(inputUI, preview);

        slotStack.setOnDragEntered(e -> e.consume());
        slotStack.setOnDragExited(e -> {
            inputUI.setStyle(defaultStyle);
            e.consume();
        });
        slotStack.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
                inputUI.setStyle(activeStyle);
            }
            e.consume();
        });
        slotStack.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles()) {
                File file = db.getFiles().get(0);
                Image img = ImageLoader.load(file, 0, 0);
                target.setImage(img);
                e.setDropCompleted(true);
            }
            e.consume();
        });
        slotStack.setOnMouseClicked(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select Image");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.webp"));
            File f = fc.showOpenDialog(getScene().getWindow());
            if (f != null) {
                Image img = ImageLoader.load(f, 0, 0);
                target.setImage(img);
            }
        });

        return slotStack;
    }
}