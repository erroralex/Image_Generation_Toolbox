package com.nilsson.imagetoolbox.main;

import com.google.inject.Inject;
import com.google.inject.Module;
import com.nilsson.imagetoolbox.service.IndexingService;
import com.nilsson.imagetoolbox.ui.RootLayout;
import com.nilsson.imagetoolbox.ui.factory.ViewFactory;
import de.saxsys.mvvmfx.guice.MvvmfxGuiceApplication;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import javax.imageio.ImageIO;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 <h2>MainApp</h2>
 <p>
 The central entry point for the <b>Image Generation Toolbox</b> application.
 This class extends {@link MvvmfxGuiceApplication} to integrate JavaFX with
 the MVVM pattern and Guice dependency injection.
 </p>

 <h3>Core Responsibilities:</h3>
 <ul>
 <li><b>Dependency Injection:</b> Initializes the Guice container by loading the {@link AppModule}.</li>
 <li><b>Stage Configuration:</b> Configures the primary stage with a {@code TRANSPARENT} style
 to support custom window decorations and modern UI aesthetics.</li>
 <li><b>Layout Initialization:</b> Coordinates with the {@link ViewFactory} to bootstrap
 the {@link RootLayout} and inject the primary stage context.</li>
 <li><b>Window Management:</b> Implements pseudo-maximize logic by calculating primary screen
 visual bounds and attaches a {@code ResizeHelper} to manage borderless window resizing.</li>
 <li><b>Runtime Preparation:</b> Scans for {@code ImageIO} plugins to ensure broad image
 format support across different operating systems.</li>
 </ul>
 */
public class MainApp extends MvvmfxGuiceApplication {

    // ------------------------------------------------------------------------
    // Dependency Injection
    // ------------------------------------------------------------------------

    @Inject
    private ViewFactory viewFactory;

    @Inject
    private IndexingService indexingService;

    // ------------------------------------------------------------------------
    // Static Initializer
    // ------------------------------------------------------------------------

    static {
        ImageIO.scanForPlugins();
    }

    // ------------------------------------------------------------------------
    // Application Lifecycle
    // ------------------------------------------------------------------------

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

        CompletableFuture.runAsync(() -> {
            try {
                // FIX: Use the injected field
                if (indexingService != null) {
                    indexingService.reconcileLibrary();
                } else {
                    System.err.println("IndexingService was not injected!");
                }
            } catch (Exception e) {
                System.err.println("Startup reconciliation failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }


    // ------------------------------------------------------------------------
    // Entry Point
    // ------------------------------------------------------------------------

    public static void main(String[] args) {
        launch(args);
    }
}