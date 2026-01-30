package com.nilsson.imagetoolbox.ui.views;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.kordamp.ikonli.fontawesome.FontAwesome;
import org.kordamp.ikonli.javafx.FontIcon;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;

/**
 * The View component for the Speed Sorter module.
 * <p>
 * This class is responsible for the visual presentation of the sorting interface.
 * It does not contain business logic (file moving, history, persistence).
 * Instead, it captures user inputs (Keyboard shortcuts, Buttons) and forwards them
 * to the {@link ViewListener}.
 * <p>
 * Data rendering is performed only when requested by the Controller via public methods
 * (e.g., {@link #displayImage(File, int, int)}).
 */
public class SpeedSorterView extends VBox {

    /**
     * Listener interface to delegate actions to the Controller.
     */
    public interface ViewListener {
        void onSelectInputFolder(File folder);
        void onSetTargetFolder(int index, File folder);
        void onMoveRequested(int targetIndex);
        void onDeleteRequested();
        void onUndoRequested();
        void onSkipRequested();
        void onPreviousRequested();
    }

    private ViewListener listener;

    // UI Components
    private final Button[] targetButtons = new Button[5];
    private final ImageView mainImageView = new ImageView();
    private final Label progressLabel = new Label("0 / 0");
    private final Label currentPathLabel = new Label("");
    private final Label infoLabel = new Label("Select Input Folder to Start");
    private final Label fullscreenHint = new Label("Click image for Fullscreen");
    private final Label messageLabel = new Label("");

    private File currentDisplayedFile;

    public SpeedSorterView() {
        this.setPadding(new Insets(20));
        this.setSpacing(15);
        this.getStyleClass().add("speed-sorter-view");
        this.setAlignment(Pos.TOP_CENTER);

        setupTopBar();
        setupImageArea();
        setupControlBar();

        // Input Handling
        this.setOnKeyPressed(this::handleKeyPress);
        this.setFocusTraversable(true);
        this.setOnMouseClicked(e -> this.requestFocus());
    }

    public void setListener(ViewListener listener) {
        this.listener = listener;
    }

    // ==================================================================================
    // PUBLIC API (Called by Controller)
    // ==================================================================================

    public void updateInputPath(File folder) {
        if (folder != null) {
            currentPathLabel.setText(folder.getAbsolutePath());
        }
    }

    public void updateTargetButton(int index, File folder) {
        if (index >= 0 && index < targetButtons.length && folder != null) {
            targetButtons[index].setText(folder.getName());
            targetButtons[index].setTooltip(new Tooltip(folder.getAbsolutePath()));
        }
    }

    public void displayImage(File file, int current, int total) {
        this.currentDisplayedFile = file;
        infoLabel.setText("");
        fullscreenHint.setVisible(true);
        loadImageIntoView(file, mainImageView);
        progressLabel.setText(current + " / " + total);
    }

    public void showEmptyState() {
        infoLabel.setText("No images found in folder");
        mainImageView.setImage(null);
        progressLabel.setText("0 / 0");
        fullscreenHint.setVisible(false);
        currentDisplayedFile = null;
    }

    public void showFinishedState(int total) {
        infoLabel.setText("All images processed!");
        mainImageView.setImage(null);
        progressLabel.setText(total + " / " + total);
        fullscreenHint.setVisible(false);
        currentDisplayedFile = null;
    }

    public void showFeedback(String msg, String colorHex) {
        messageLabel.setText(msg);
        messageLabel.setStyle("-fx-text-fill: " + colorHex + "; -fx-font-size: 14px; -fx-font-weight: bold; -fx-background-color: rgba(0,0,0,0.8); -fx-padding: 5 10; -fx-background-radius: 5;");
        messageLabel.setVisible(true);

        new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException e) {}
            Platform.runLater(() -> messageLabel.setVisible(false));
        }).start();
    }

    // ==================================================================================
    // UI SETUP
    // ==================================================================================

    private void setupTopBar() {
        HBox topBar = new HBox(15);
        topBar.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("Speed Sorter");
        title.getStyleClass().add("content-title");

        Button btnSelectInput = new Button("Select Input Folder");
        btnSelectInput.setGraphic(new FontIcon(FontAwesome.FOLDER_OPEN));
        btnSelectInput.getStyleClass().add("button");
        btnSelectInput.setOnAction(e -> handleSelectInput());

        currentPathLabel.setStyle("-fx-text-fill: -text-muted; -fx-font-size: 12px; -fx-font-style: italic;");
        progressLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: -text-muted;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        topBar.getChildren().addAll(title, btnSelectInput, currentPathLabel, spacer, progressLabel);
        this.getChildren().add(topBar);
    }

    private void setupImageArea() {
        StackPane imageContainer = new StackPane();
        imageContainer.setBackground(new Background(new BackgroundFill(Color.web("#0b0e14"), new CornerRadii(8), Insets.EMPTY)));
        imageContainer.setBorder(new Border(new BorderStroke(Color.web("#2d3748"), BorderStrokeStyle.SOLID, new CornerRadii(8), BorderWidths.DEFAULT)));
        imageContainer.setMinSize(0, 0);
        VBox.setVgrow(imageContainer, Priority.ALWAYS);

        mainImageView.setPreserveRatio(true);
        mainImageView.setSmooth(true);
        mainImageView.fitWidthProperty().bind(Bindings.max(0, imageContainer.widthProperty().subtract(20)));
        mainImageView.fitHeightProperty().bind(Bindings.max(0, imageContainer.heightProperty().subtract(20)));

        infoLabel.setStyle("-fx-text-fill: -text-primary; -fx-font-size: 18px; -fx-font-weight: bold;");

        fullscreenHint.setStyle("-fx-text-fill: rgba(255,255,255,0.3); -fx-font-size: 11px; -fx-padding: 5;");
        fullscreenHint.setVisible(false);
        StackPane.setAlignment(fullscreenHint, Pos.BOTTOM_RIGHT);

        messageLabel.setVisible(false);
        StackPane.setAlignment(messageLabel, Pos.TOP_CENTER);
        StackPane.setMargin(messageLabel, new Insets(10));

        imageContainer.getChildren().addAll(infoLabel, mainImageView, fullscreenHint, messageLabel);

        Tooltip.install(imageContainer, new Tooltip("Click to view Fullscreen"));
        imageContainer.setCursor(javafx.scene.Cursor.HAND);
        imageContainer.setOnMouseClicked(e -> {
            if (currentDisplayedFile != null) showFullScreenImage(currentDisplayedFile);
        });

        this.getChildren().add(imageContainer);
    }

    private void setupControlBar() {
        HBox controls = new HBox(15);
        controls.setAlignment(Pos.CENTER);
        controls.setPadding(new Insets(10));

        for (int i = 0; i < 5; i++) {
            final int index = i;
            VBox box = new VBox(5);
            box.setAlignment(Pos.CENTER);

            Label keyLabel = new Label("Key [" + (i + 1) + "]");
            keyLabel.setStyle("-fx-text-fill: -app-cyan; -fx-font-weight: bold;");

            Button btn = new Button("Set Folder");
            btn.getStyleClass().add("button");
            btn.setPrefWidth(120);
            btn.setOnAction(e -> handleSelectTarget(index));

            targetButtons[i] = btn;
            box.getChildren().addAll(keyLabel, btn);
            controls.getChildren().add(box);
        }

        VBox extraControls = new VBox(5);
        extraControls.setAlignment(Pos.CENTER_LEFT);
        Label deleteLabel = new Label("DEL / X : Recycle Bin");
        deleteLabel.setStyle("-fx-text-fill: #e53e3e; -fx-font-size: 11px;");
        Label undoLabel = new Label("Ctrl+Z : Undo Move");
        undoLabel.setStyle("-fx-text-fill: -text-muted; -fx-font-size: 11px;");
        Label spaceLabel = new Label("SPACE : Skip");
        spaceLabel.setStyle("-fx-text-fill: -text-muted; -fx-font-size: 11px;");

        extraControls.getChildren().addAll(deleteLabel, undoLabel, spaceLabel);
        controls.getChildren().add(extraControls);

        this.getChildren().add(controls);
    }

    // ==================================================================================
    // INPUT HANDLING
    // ==================================================================================

    private void handleKeyPress(KeyEvent e) {
        if (listener == null) return;

        if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN).match(e)) {
            listener.onUndoRequested();
            return;
        }

        switch (e.getCode()) {
            case DIGIT1: listener.onMoveRequested(0); break;
            case DIGIT2: listener.onMoveRequested(1); break;
            case DIGIT3: listener.onMoveRequested(2); break;
            case DIGIT4: listener.onMoveRequested(3); break;
            case DIGIT5: listener.onMoveRequested(4); break;
            case DELETE: case X: listener.onDeleteRequested(); break;
            case SPACE: case RIGHT: listener.onSkipRequested(); break;
            case LEFT: listener.onPreviousRequested(); break;
        }
    }

    private void handleSelectInput() {
        if (listener == null) return;
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Select Input Folder");
        File dir = dc.showDialog(this.getScene().getWindow());
        if (dir != null) listener.onSelectInputFolder(dir);
    }

    private void handleSelectTarget(int index) {
        if (listener == null) return;
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Select Target Folder " + (index + 1));
        File dir = dc.showDialog(this.getScene().getWindow());
        if (dir != null) listener.onSetTargetFolder(index, dir);
    }

    // ==================================================================================
    // VISUAL HELPERS
    // ==================================================================================

    private void showFullScreenImage(File file) {
        Stage stage = new Stage();
        stage.initOwner(this.getScene().getWindow());
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initStyle(StageStyle.TRANSPARENT);

        ImageView fullView = new ImageView();
        fullView.setPreserveRatio(true);
        fullView.setSmooth(true);
        loadImageIntoView(file, fullView);
        fullView.fitWidthProperty().bind(stage.widthProperty());
        fullView.fitHeightProperty().bind(stage.heightProperty());

        StackPane root = new StackPane(fullView);
        root.setStyle("-fx-background-color: rgba(0, 0, 0, 0.9);");
        root.setOnMouseClicked(e -> stage.close());

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.close();
        });

        stage.setScene(scene);
        stage.setFullScreen(true);
        stage.setFullScreenExitHint("");
        stage.show();
    }

    private void loadImageIntoView(File f, ImageView view) {
        if (f == null) {
            view.setImage(null);
            return;
        }
        try {
            // High-quality sync load for Sorter (usually local files)
            Image img = new Image(f.toURI().toString(), 0, 0, true, true, true);
            if (img.isError()) {
                fallbackLoad(f, view);
            } else {
                view.setImage(img);
            }
        } catch (Exception e) {
            view.setImage(null);
        }
    }

    private void fallbackLoad(File f, ImageView view) {
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes)) {
                Image streamImg = new Image(bis, 0, 0, true, true);
                if (!streamImg.isError()) {
                    view.setImage(streamImg);
                    return;
                }
            }
        } catch (Exception ignored) {}
        try {
            BufferedImage bImg = ImageIO.read(f);
            if (bImg != null) {
                view.setImage(SwingFXUtils.toFXImage(bImg, null));
            }
        } catch (Exception ignored) {}
    }
}