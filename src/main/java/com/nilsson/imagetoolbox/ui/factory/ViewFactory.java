package com.nilsson.imagetoolbox.ui.factory;

import com.nilsson.imagetoolbox.data.UserDataManager;
import com.nilsson.imagetoolbox.service.MetadataService;
import com.nilsson.imagetoolbox.ui.RootLayout;
import com.nilsson.imagetoolbox.ui.controllers.ImageBrowserController;
import com.nilsson.imagetoolbox.ui.controllers.ScrubController;
import com.nilsson.imagetoolbox.ui.controllers.SpeedSorterController;
import com.nilsson.imagetoolbox.ui.views.ImageBrowserView;
import com.nilsson.imagetoolbox.ui.views.ScrubView;
import com.nilsson.imagetoolbox.ui.views.SpeedSorterView;
import javafx.stage.Stage;

import java.util.concurrent.ExecutorService;

/**
 * Factory responsible for creating Views and wiring them to their Controllers.
 * <p>
 * This ensures that specific dependency implementations (like ThreadPools or Services)
 * are injected cleanly without the Views needing to know about them.
 */
public class ViewFactory {

    private final UserDataManager userDataManager;
    private final MetadataService metadataService;
    private final ExecutorService sharedExecutor;

    public ViewFactory(UserDataManager userDataManager,
                       MetadataService metadataService,
                       ExecutorService sharedExecutor) {
        this.userDataManager = userDataManager;
        this.metadataService = metadataService;
        this.sharedExecutor = sharedExecutor;
    }

    public RootLayout createRootLayout(Stage stage) {
        // --- 1. Image Browser ---
        ImageBrowserView browserView = new ImageBrowserView();
        ImageBrowserController browserController = new ImageBrowserController(
                userDataManager,
                metadataService,
                browserView,
                sharedExecutor
        );
        browserView.setListener(browserController);
        browserController.initializeSidebar(); // Initial UI state

        // --- 2. Speed Sorter ---
        SpeedSorterView speedSorterView = new SpeedSorterView();
        SpeedSorterController speedSorterController = new SpeedSorterController(
                userDataManager,
                speedSorterView
        );
        speedSorterView.setListener(speedSorterController);
        speedSorterController.initialize();

        // --- 3. Scrub View ---
        ScrubView scrubView = new ScrubView();
        ScrubController scrubController = new ScrubController(scrubView);
        scrubView.setListener(scrubController);

        // --- 4. Assemble Root ---
        return new RootLayout(
                stage,
                browserView,
                userDataManager,
                browserController,
                speedSorterController,
                speedSorterView,
                scrubView
        );
    }
}