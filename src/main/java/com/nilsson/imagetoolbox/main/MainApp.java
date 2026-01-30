package com.nilsson.imagetoolbox.main;

import com.google.inject.Inject;
import com.google.inject.Module;
import com.nilsson.imagetoolbox.ui.RootLayout;
import com.nilsson.imagetoolbox.ui.factory.ViewFactory;
import de.saxsys.mvvmfx.guice.MvvmfxGuiceApplication;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.List;

/**
 * Entry point for the Image Generation Toolbox application.
 * This class handles the JavaFX lifecycle, Guice module initialization,
 * and the configuration of the primary transparent stage including
 * pseudo-maximize logic and resizing capabilities.
 */
public class MainApp extends MvvmfxGuiceApplication {

    @Inject
    private ViewFactory viewFactory;

    // --- Application Lifecycle ---

    @Override
    public void initGuiceModules(List<Module> modules) throws Exception {
        modules.add(new AppModule());
    }

    @Override
    public void startMvvmfx(Stage stage) throws Exception {
        stage.initStyle(StageStyle.TRANSPARENT);

        RootLayout root = viewFactory.createRootLayout(stage);

        Scene scene = new Scene(root, 1280, 850);
        scene.setFill(Color.TRANSPARENT);

        var cssUrl = getClass().getResource("/dark-theme.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        stage.setTitle("Image Generation Toolbox");
        stage.setScene(scene);

        com.nilsson.imagetoolbox.ui.components.ResizeHelper.addResizeListener(stage);

        stage.show();

        // --- Window State Management ---
        Rectangle2D visualBounds = Screen.getPrimary().getVisualBounds();
        stage.setX(visualBounds.getMinX());
        stage.setY(visualBounds.getMinY());
        stage.setWidth(visualBounds.getWidth());
        stage.setHeight(visualBounds.getHeight());
    }

    // --- Entry Point ---

    public static void main(String[] args) {
        launch(args);
    }
}