package com.nilsson.imagetoolbox.ui.views;

import com.nilsson.imagetoolbox.ui.viewmodels.SpeedSorterViewModel;
import de.saxsys.mvvmfx.InjectViewModel;
import de.saxsys.mvvmfx.JavaView;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
import java.io.File;
import java.net.URL;
import java.util.ResourceBundle;

/**
 * View class for the Speed Sorter utility.
 * Provides a specialized interface for rapid image triage, allowing users to
 * move images to target folders using keyboard shortcuts (1-5) or hotkeys (Delete/X).
 * Features high-performance image rendering, an immersive fullscreen mode,
 * and persistent visual feedback for sort actions.
 */
public class SpeedSorterView extends VBox implements JavaView<SpeedSorterViewModel>, Initializable {

    @InjectViewModel
    private SpeedSorterViewModel viewModel;

    // --- UI Components ---
    private final Button[] targetButtons = new Button[5];
    private final ImageView mainImageView = new ImageView();
    private final Label progressLabel = new Label("0 / 0");
    private final Label currentPathLabel = new Label("");
    private final Label infoLabel = new Label("Select Input Folder to Start");
    private final Label fullscreenHint = new Label("Click image for Fullscreen");
    private final Label messageLabel = new Label("");

    // --- Constructor & UI Layout ---
    public SpeedSorterView() {
        this.setPadding(new Insets(20));
        this.setSpacing(15);
        this.getStyleClass().add("speed-sorter-view");
        this.setAlignment(Pos.TOP_CENTER);

        setupTopBar();
        setupImageArea();
        setupControlBar();

        this.setOnKeyPressed(this::handleKeyPress);
        this.setFocusTraversable(true);
        this.setOnMouseClicked(e -> this.requestFocus());
    }

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
        imageContainer.setOnMouseClicked(e -> {
            if (viewModel.currentDisplayedFileProperty().get() != null)
                showFullScreenImage(viewModel.currentDisplayedFileProperty().get());
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

            targetButtons[i] = new Button("Set Folder");
            targetButtons[i].getStyleClass().add("button");
            targetButtons[i].setPrefWidth(120);
            targetButtons[i].setOnAction(e -> handleSelectTarget(index));

            box.getChildren().addAll(keyLabel, targetButtons[i]);
            controls.getChildren().add(box);
        }

        VBox extra = new VBox(5);
        extra.getChildren().addAll(
                new Label("DEL / X : Recycle Bin"),
                new Label("Ctrl+Z : Undo"),
                new Label("SPACE : Skip")
        );
        extra.getChildren().forEach(n -> n.setStyle("-fx-text-fill: -text-muted; -fx-font-size: 11px;"));
        controls.getChildren().add(extra);

        this.getChildren().add(controls);
    }

    // --- Lifecycle & Initialization ---
    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        viewModel.initialize();

        viewModel.currentInputDirProperty().addListener((obs, old, val) ->
                currentPathLabel.setText(val != null ? val.getAbsolutePath() : "")
        );

        viewModel.currentDisplayedFileProperty().addListener((obs, old, file) -> {
            if (file != null) {
                displayImage(file);
            } else {
                if (viewModel.getImages().isEmpty()) showEmptyState();
                else showFinishedState();
            }
        });

        viewModel.currentIndexProperty().addListener((obs, old, val) -> updateProgress());

        for (int i = 0; i < 5; i++) {
            final int idx = i;
            targetButtons[i].textProperty().bind(Bindings.createStringBinding(() -> {
                String name = viewModel.getTargetFolderNames().get(idx);
                return name.isEmpty() ? "Set Folder" : name;
            }, viewModel.getTargetFolderNames()));
        }

        viewModel.triggerFeedbackProperty().addListener((obs, old, val) ->
                showFeedback(viewModel.feedbackMessageProperty().get(), viewModel.feedbackColorProperty().get())
        );
    }

    // --- Interaction Logic ---
    private void handleKeyPress(KeyEvent e) {
        if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN).match(e)) {
            viewModel.undo();
            return;
        }
        switch (e.getCode()) {
            case DIGIT1: viewModel.moveFile(0); break;
            case DIGIT2: viewModel.moveFile(1); break;
            case DIGIT3: viewModel.moveFile(2); break;
            case DIGIT4: viewModel.moveFile(3); break;
            case DIGIT5: viewModel.moveFile(4); break;
            case DELETE: case X: viewModel.deleteFile(); break;
            case SPACE: case RIGHT: viewModel.skip(); break;
            case LEFT: viewModel.previous(); break;
        }
    }

    private void handleSelectInput() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Select Input Folder");
        File dir = dc.showDialog(this.getScene().getWindow());
        if (dir != null) viewModel.setInputFolder(dir);
    }

    private void handleSelectTarget(int index) {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Select Target Folder " + (index + 1));
        File dir = dc.showDialog(this.getScene().getWindow());
        if (dir != null) viewModel.setTargetFolder(index, dir);
    }

    // --- UI Update & Feedback Helpers ---
    private void displayImage(File file) {
        infoLabel.setText("");
        fullscreenHint.setVisible(true);
        loadImageIntoView(file, mainImageView);
        updateProgress();
    }

    private void updateProgress() {
        int cur = viewModel.currentIndexProperty().get() + 1;
        int total = viewModel.getImages().size();
        progressLabel.setText(cur + " / " + total);
    }

    private void showEmptyState() {
        infoLabel.setText("No images found");
        mainImageView.setImage(null);
        fullscreenHint.setVisible(false);
    }

    private void showFinishedState() {
        infoLabel.setText("All images processed!");
        mainImageView.setImage(null);
        fullscreenHint.setVisible(false);
    }

    private void showFeedback(String msg, String colorHex) {
        messageLabel.setText(msg);
        messageLabel.setStyle("-fx-text-fill: " + colorHex + "; -fx-font-size: 14px; -fx-font-weight: bold; " +
                "-fx-background-color: rgba(0,0,0,0.8); -fx-padding: 5 10; -fx-background-radius: 5;");
        messageLabel.setVisible(true);
        new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException e) {}
            Platform.runLater(() -> messageLabel.setVisible(false));
        }).start();
    }

    // --- Image Rendering ---
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
        if (f == null) { view.setImage(null); return; }
        try {
            Image img = new Image(f.toURI().toString(), 0, 0, true, true, true);
            if (img.isError()) fallbackLoad(f, view);
            else view.setImage(img);
        } catch (Exception e) { view.setImage(null); }
    }

    private void fallbackLoad(File f, ImageView view) {
        try {
            BufferedImage bImg = ImageIO.read(f);
            if (bImg != null) view.setImage(SwingFXUtils.toFXImage(bImg, null));
        } catch (Exception ignored) {}
    }
}