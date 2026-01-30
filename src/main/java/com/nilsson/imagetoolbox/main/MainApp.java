package com.nilsson.imagetoolbox.main;

import com.nilsson.imagetoolbox.data.UserDataManager;
import com.nilsson.imagetoolbox.service.MetadataService;
import com.nilsson.imagetoolbox.ui.components.ResizeHelper;
import com.nilsson.imagetoolbox.ui.RootLayout;
import com.nilsson.imagetoolbox.ui.factory.ViewFactory;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainApp extends Application {

    private ExecutorService applicationExecutor;
    private UserDataManager userDataManager;

    @Override
    public void start(Stage primaryStage) {
        try {
            // 1. Initialize Shared Infrastructure
            this.applicationExecutor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            });

            this.userDataManager = new UserDataManager();
            MetadataService metadataService = new MetadataService();

            // 2. Initialize Factory
            ViewFactory viewFactory = new ViewFactory(
                    userDataManager,
                    metadataService,
                    applicationExecutor
            );

            // 3. Create UI
            primaryStage.initStyle(StageStyle.TRANSPARENT);

            RootLayout root = viewFactory.createRootLayout(primaryStage);

            // 4. Scene Setup
            // We set a reasonable "Restore" size here, so if the user un-maximizes later,
            // these dimensions are used as a fallback if backupBounds is missing.
            Scene scene = new Scene(root, 1280, 850);
            scene.setFill(Color.TRANSPARENT);

            var cssUrl = getClass().getResource("/dark-theme.css");
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl.toExternalForm());
            }

            primaryStage.setTitle("Image Generation Toolbox");
            primaryStage.setScene(scene);

            // 5. Enable Resizing Logic
            ResizeHelper.addResizeListener(primaryStage);

            // 6. Show First (Important for OS handle registration)
            primaryStage.show();

            // 7. Pseudo-Maximize (Manual Bounds)
            // We do NOT use stage.setMaximized(true) because it conflicts with transparent stages
            // and CustomTitleBar logic. Instead, we manually size it to the screen.
            Rectangle2D visualBounds = Screen.getPrimary().getVisualBounds();
            primaryStage.setX(visualBounds.getMinX());
            primaryStage.setY(visualBounds.getMinY());
            primaryStage.setWidth(visualBounds.getWidth());
            primaryStage.setHeight(visualBounds.getHeight());

            // Note: CustomTitleBar will see this as "Maximized" because checks bounds.

        } catch (Throwable t) {
            t.printStackTrace();
            Platform.exit();
        }
    }

    @Override
    public void stop() {
        if (applicationExecutor != null && !applicationExecutor.isShutdown()) {
            applicationExecutor.shutdownNow();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}