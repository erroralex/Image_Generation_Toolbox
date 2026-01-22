package com.nilsson.imagetoolbox;

import com.nilsson.imagetoolbox.ui.ToolboxLayout;
import com.nilsson.imagetoolbox.ui.ResizeHelper;
import javafx.application.Application;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class ImageToolboxApp extends Application {

    @Override
    public void start(Stage primaryStage) {
        // 1. Setup Window style (Transparent is required for custom rounded corners)
        primaryStage.initStyle(StageStyle.TRANSPARENT);

        // 2. Main Layout
        ToolboxLayout root = new ToolboxLayout(primaryStage);

        Scene scene = new Scene(root, 1000, 700); // Default non-maximized size
        scene.setFill(Color.TRANSPARENT);

        var cssUrl = getClass().getResource("/dark-theme.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        primaryStage.setTitle("Image Generation Toolbox");
        primaryStage.setScene(scene);

        // 3. Add Resize Listener
        ResizeHelper.addResizeListener(primaryStage);

        // 4. Show FIRST, then resize
        // JavaFX needs the stage to be visible to calculate screen positions correctly
        primaryStage.show();

        // FIX: STRICTLY set size to Visual Bounds (Work Area)
        // DO NOT call primaryStage.setMaximized(true)
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        primaryStage.setX(bounds.getMinX());
        primaryStage.setY(bounds.getMinY());
        primaryStage.setWidth(bounds.getWidth());
        primaryStage.setHeight(bounds.getHeight());
    }

    public static void main(String[] args) {
        launch(args);
    }
}