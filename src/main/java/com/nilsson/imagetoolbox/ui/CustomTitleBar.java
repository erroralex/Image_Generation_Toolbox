package com.nilsson.imagetoolbox.ui;

import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.kordamp.ikonli.fontawesome.FontAwesome;
import org.kordamp.ikonli.javafx.FontIcon;

public class CustomTitleBar extends HBox {
    private double xOffset = 0;
    private double yOffset = 0;

    // We store the "normal" size here to restore it later
    private Rectangle2D backupBounds = null;

    private static final double SNAP_THRESHOLD = 20.0;

    public CustomTitleBar(Stage primaryStage, Runnable onExitCleanup) {
        this.getStyleClass().add("custom-title-bar");
        this.setAlignment(Pos.CENTER_LEFT);
        this.setPrefHeight(40);

        Label titleLabel = new Label("Image Generation Toolbox by ALX");
        titleLabel.getStyleClass().add("title-label");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // --- Window Controls ---
        Button minimizeBtn = new Button();
        minimizeBtn.setGraphic(new FontIcon(FontAwesome.MINUS));
        minimizeBtn.getStyleClass().add("window-button");
        minimizeBtn.setOnAction(e -> primaryStage.setIconified(true));

        Button maximizeBtn = new Button();
        maximizeBtn.setGraphic(new FontIcon(FontAwesome.WINDOW_MAXIMIZE));
        maximizeBtn.getStyleClass().add("window-button");
        maximizeBtn.setOnAction(e -> toggleMaximize(primaryStage, maximizeBtn));

        Button closeBtn = new Button();
        closeBtn.setGraphic(new FontIcon(FontAwesome.TIMES));
        closeBtn.getStyleClass().addAll("window-button", "window-close");
        closeBtn.setOnAction(e -> {
            if (onExitCleanup != null) onExitCleanup.run();
            primaryStage.close();
        });

        this.getChildren().addAll(titleLabel, spacer, minimizeBtn, maximizeBtn, closeBtn);

        // --- Dragging Logic ---
        this.setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                xOffset = event.getSceneX();
                yOffset = event.getSceneY();
            }
        });

        this.setOnMouseDragged(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                // 1. Handle "Tear-off": If dragging while maximized, restore first
                if (isMaximized(primaryStage)) {
                    double ratioX = event.getSceneX() / primaryStage.getWidth();
                    // Restore to previous size immediately
                    toggleMaximize(primaryStage, maximizeBtn);
                    // Adjust offset so the window "jumps" to the mouse cursor correctly
                    xOffset = primaryStage.getWidth() * ratioX;
                }

                double newX = event.getScreenX() - xOffset;
                double newY = event.getScreenY() - yOffset;

                primaryStage.setX(newX);
                primaryStage.setY(newY);
            }
        });

        // 2. Handle "Snap" on Release
        this.setOnMouseReleased(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                handleSnap(primaryStage, maximizeBtn, event.getScreenX(), event.getScreenY());
            }
        });

        // 3. Double Click to Maximize
        this.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && event.getButton() == MouseButton.PRIMARY) {
                toggleMaximize(primaryStage, maximizeBtn);
            }
        });
    }

    /**
     * Custom Maximize Logic for Transparent Window (Respects Taskbar)
     */
    private void toggleMaximize(Stage stage, Button btn) {
        Screen screen = getScreenForStage(stage);
        Rectangle2D bounds = screen.getVisualBounds();

        if (isMaximized(stage)) {
            // RESTORE
            if (backupBounds != null) {
                stage.setX(backupBounds.getMinX());
                stage.setY(backupBounds.getMinY());
                stage.setWidth(backupBounds.getWidth());
                stage.setHeight(backupBounds.getHeight());
            } else {
                // Fallback if no backup: center on screen with default size
                stage.setWidth(1000);
                stage.setHeight(700);
                stage.centerOnScreen();
            }
            btn.setGraphic(new FontIcon(FontAwesome.WINDOW_MAXIMIZE));
        } else {
            // MAXIMIZE
            // Save current position/size before maximizing
            backupBounds = new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());

            stage.setX(bounds.getMinX());
            stage.setY(bounds.getMinY());
            stage.setWidth(bounds.getWidth());
            stage.setHeight(bounds.getHeight());
            btn.setGraphic(new FontIcon(FontAwesome.WINDOW_RESTORE));
        }
    }

    /**
     * Checks if the window covers the full visual area (Effectively Maximized)
     */
    private boolean isMaximized(Stage stage) {
        Screen screen = getScreenForStage(stage);
        Rectangle2D bounds = screen.getVisualBounds();

        // Allow 1px error margin
        return Math.abs(stage.getX() - bounds.getMinX()) < 2 &&
                Math.abs(stage.getY() - bounds.getMinY()) < 2 &&
                Math.abs(stage.getWidth() - bounds.getWidth()) < 2 &&
                Math.abs(stage.getHeight() - bounds.getHeight()) < 2;
    }

    private void handleSnap(Stage stage, Button maxBtn, double cursorX, double cursorY) {
        Screen screen = getScreenForCursor(cursorX, cursorY);
        Rectangle2D bounds = screen.getVisualBounds();

        if (cursorY <= bounds.getMinY() + SNAP_THRESHOLD) {
            // Snap to Top -> Maximize
            if (!isMaximized(stage)) {
                toggleMaximize(stage, maxBtn);
            }
        }
    }

    private Screen getScreenForCursor(double x, double y) {
        var screens = Screen.getScreensForRectangle(x, y, 1, 1);
        return screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
    }

    private Screen getScreenForStage(Stage stage) {
        var screens = Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        return screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
    }
}