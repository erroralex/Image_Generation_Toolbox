package com.nilsson.imagetoolbox.ui.components;

import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.kordamp.ikonli.fontawesome.FontAwesome;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 A custom window title bar designed for undecorated JavaFX stages.
 <p>
 Features include:
 <ul>
 <li>Drag-to-move functionality with support for tearing off maximized windows.</li>
 <li>Double-click to toggle maximize/restore.</li>
 <li>Windows Aero-style snapping (Top to maximize, sides for split-screen, corners for quadrants).</li>
 <li>Standard window controls (Minimize, Maximize/Restore, Close).</li>
 </ul>
 */
public class CustomTitleBar extends HBox {

    private static final double SNAP_THRESHOLD = 20.0;

    private double xOffset = 0;
    private double yOffset = 0;
    private Rectangle2D backupBounds = null;

    public CustomTitleBar(Stage primaryStage, Runnable onExitCleanup) {
        this.getStyleClass().add("custom-title-bar");
        this.setAlignment(Pos.CENTER_LEFT);
        this.setPrefHeight(40);

        Label titleLabel = new Label("Image Generation Toolbox by ALX");
        titleLabel.getStyleClass().add("title-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

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
        
        // Inline styling for hover effect to avoid affecting global CSS
        closeBtn.setOnMouseEntered(e -> closeBtn.setStyle("-fx-background-color: #e81123; -fx-text-fill: white;"));
        closeBtn.setOnMouseExited(e -> closeBtn.setStyle(""));

        closeBtn.setOnAction(e -> {
            if (onExitCleanup != null) onExitCleanup.run();
            primaryStage.close();
        });

        HBox windowControls = new HBox(8, minimizeBtn, maximizeBtn, closeBtn);
        windowControls.setAlignment(Pos.CENTER_RIGHT);
        windowControls.setMinWidth(Region.USE_PREF_SIZE);

        this.getChildren().addAll(titleLabel, spacer, windowControls);

        this.setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                xOffset = event.getSceneX();
                yOffset = event.getSceneY();
            }
        });

        this.setOnMouseDragged(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                if (isMaximized(primaryStage)) {
                    double ratioX = event.getSceneX() / primaryStage.getWidth();
                    toggleMaximize(primaryStage, maximizeBtn);
                    // Adjust offset so window doesn't jump
                    xOffset = primaryStage.getWidth() * ratioX;
                }

                double newX = event.getScreenX() - xOffset;
                double newY = event.getScreenY() - yOffset;

                primaryStage.setX(newX);
                primaryStage.setY(newY);
            }
        });

        this.setOnMouseReleased(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                handleSnap(primaryStage, maximizeBtn, event.getScreenX(), event.getScreenY());
            }
        });

        this.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && event.getButton() == MouseButton.PRIMARY) {
                toggleMaximize(primaryStage, maximizeBtn);
            }
        });
    }

    private void toggleMaximize(Stage stage, Button btn) {
        Screen screen = getScreenForStage(stage);
        Rectangle2D bounds = screen.getVisualBounds();

        if (isMaximized(stage)) {
            // CRITICAL FIX: Explicitly disable native maximize state before restoring
            stage.setMaximized(false);

            if (backupBounds != null) {
                stage.setX(backupBounds.getMinX());
                stage.setY(backupBounds.getMinY());
                stage.setWidth(backupBounds.getWidth());
                stage.setHeight(backupBounds.getHeight());
            } else {
                // Fallback if started maximized
                stage.setWidth(1280);
                stage.setHeight(850);
                stage.centerOnScreen();
            }
            btn.setGraphic(new FontIcon(FontAwesome.WINDOW_MAXIMIZE));
        } else {
            backupBounds = new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());

            // CRITICAL FIX: Disable native maximize, perform manual bounds maximize
            stage.setMaximized(false);

            stage.setX(bounds.getMinX());
            stage.setY(bounds.getMinY());
            stage.setWidth(bounds.getWidth());
            stage.setHeight(bounds.getHeight());
            btn.setGraphic(new FontIcon(FontAwesome.WINDOW_RESTORE));
        }
    }

    private boolean isMaximized(Stage stage) {
        // Check both native flag AND bounds match
        if (stage.isMaximized()) return true;

        Screen screen = getScreenForStage(stage);
        Rectangle2D bounds = screen.getVisualBounds();
        return Math.abs(stage.getX() - bounds.getMinX()) < 2 &&
                Math.abs(stage.getY() - bounds.getMinY()) < 2 &&
                Math.abs(stage.getWidth() - bounds.getWidth()) < 2 &&
                Math.abs(stage.getHeight() - bounds.getHeight()) < 2;
    }

    private void handleSnap(Stage stage, Button maxBtn, double x, double y) {
        Screen screen = getScreenForCursor(x, y);
        Rectangle2D bounds = screen.getVisualBounds();

        boolean left = x <= bounds.getMinX() + SNAP_THRESHOLD;
        boolean right = x >= bounds.getMaxX() - SNAP_THRESHOLD;
        boolean top = y <= bounds.getMinY() + SNAP_THRESHOLD;
        boolean bottom = y >= bounds.getMaxY() - SNAP_THRESHOLD;

        // 1. Quadrants (Corners)
        if (top && left) {
            snapToRect(stage, bounds.getMinX(), bounds.getMinY(), bounds.getWidth() / 2, bounds.getHeight() / 2);
        } else if (top && right) {
            snapToRect(stage, bounds.getMinX() + bounds.getWidth() / 2, bounds.getMinY(), bounds.getWidth() / 2, bounds.getHeight() / 2);
        } else if (bottom && left) {
            snapToRect(stage, bounds.getMinX(), bounds.getMinY() + bounds.getHeight() / 2, bounds.getWidth() / 2, bounds.getHeight() / 2);
        } else if (bottom && right) {
            snapToRect(stage, bounds.getMinX() + bounds.getWidth() / 2, bounds.getMinY() + bounds.getHeight() / 2, bounds.getWidth() / 2, bounds.getHeight() / 2);
        }
        // 2. Full Screen (Top Edge)
        else if (top) {
            if (!isMaximized(stage)) toggleMaximize(stage, maxBtn);
        }
        // 3. Half Screen (Side Edges) - NEW LOGIC
        else if (left) {
            snapToRect(stage, bounds.getMinX(), bounds.getMinY(), bounds.getWidth() / 2, bounds.getHeight());
        } else if (right) {
            snapToRect(stage, bounds.getMinX() + bounds.getWidth() / 2, bounds.getMinY(), bounds.getWidth() / 2, bounds.getHeight());
        }
    }

    private void snapToRect(Stage stage, double x, double y, double w, double h) {
        if (backupBounds == null) {
            backupBounds = new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        }
        stage.setMaximized(false); // Ensure un-maximized before snapping
        stage.setX(x);
        stage.setY(y);
        stage.setWidth(w);
        stage.setHeight(h);
    }

    private Screen getScreenForCursor(double x, double y) {
        for (Screen screen : Screen.getScreens()) {
            if (screen.getBounds().contains(x, y)) return screen;
        }
        return Screen.getPrimary();
    }

    private Screen getScreenForStage(Stage stage) {
        for (Screen screen : Screen.getScreens()) {
            if (screen.getBounds().intersects(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight())) {
                return screen;
            }
        }
        return Screen.getPrimary();
    }
}