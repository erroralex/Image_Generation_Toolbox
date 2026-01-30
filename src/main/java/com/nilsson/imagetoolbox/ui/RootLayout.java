package com.nilsson.imagetoolbox.ui;

import com.nilsson.imagetoolbox.data.UserDataManager;
import com.nilsson.imagetoolbox.ui.components.CustomTitleBar;
import com.nilsson.imagetoolbox.ui.components.SidebarMenu;
import com.nilsson.imagetoolbox.ui.controllers.ImageBrowserController;
import com.nilsson.imagetoolbox.ui.controllers.SpeedSorterController;
import com.nilsson.imagetoolbox.ui.views.ImageBrowserView;
import com.nilsson.imagetoolbox.ui.views.ScrubView;
import com.nilsson.imagetoolbox.ui.views.SpeedSorterView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.File;

/**
 * Root layout container for the application UI.
 *
 * <p>This class serves as the top-level layout manager, coordinating
 * navigation and view switching between the major application views.
 * All views and controllers are provided via dependency injection.</p>
 */
public class RootLayout extends BorderPane {

    /* ============================================================
       Core UI Components
       ============================================================ */

    private final CustomTitleBar titleBar;
    private final SidebarMenu sidebar;
    private final StackPane contentArea;

    /* ============================================================
       Application State
       ============================================================ */

    private final UserDataManager dataManager;

    /* ============================================================
       Views
       ============================================================ */

    private final ImageBrowserView imageBrowserView;
    private final SpeedSorterView speedSorterView;
    private final ScrubView scrubView;

    /* ============================================================
       Controllers
       ============================================================ */

    private final ImageBrowserController browserController;
    private final SpeedSorterController speedSorterController;

    /* ============================================================
       Construction
       ============================================================ */

    public RootLayout(Stage stage,
                      ImageBrowserView imageBrowserView,
                      UserDataManager dataManager,
                      ImageBrowserController browserController,
                      SpeedSorterController speedSorterController,
                      SpeedSorterView speedSorterView,
                      ScrubView scrubView) {

        this.imageBrowserView = imageBrowserView;
        this.dataManager = dataManager;
        this.browserController = browserController;
        this.speedSorterController = speedSorterController;
        this.speedSorterView = speedSorterView;
        this.scrubView = scrubView;

        this.sidebar = new SidebarMenu(this::switchView);
        this.setLeft(sidebar);

        this.titleBar = new CustomTitleBar(stage, () -> {
            if (sidebar != null) sidebar.toggle();
        });
        this.setTop(titleBar);

        this.contentArea = new StackPane();
        this.contentArea.setStyle("-fx-background-color: -app-bg-deepest;");
        this.setCenter(contentArea);

        this.imageBrowserView.setMinSize(0, 0);
        this.imageBrowserView.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        File lastFolder = dataManager.getLastFolder();
        if (lastFolder != null && lastFolder.exists()) {
            browserController.onFolderSelected(lastFolder);
            imageBrowserView.getFolderNav().selectPath(lastFolder);
        }

        switchView("VIEW_TREE");
    }

    /* ============================================================
       View Navigation
       ============================================================ */

    private void switchView(String viewId) {
        sidebar.setActive(viewId);
        contentArea.getChildren().clear();

        switch (viewId) {
            case "VIEW_TREE":
                File last = dataManager.getLastFolder();
                if (last != null && last.exists()) {
                    browserController.onFolderSelected(last);
                }
                contentArea.getChildren().add(imageBrowserView);
                break;

            case "VIEW_SORTER":
                contentArea.getChildren().add(speedSorterView);
                break;

            case "VIEW_SCRUB":
                contentArea.getChildren().add(scrubView);
                break;

            case "VIEW_FAVORITES":
                imageBrowserView.displayFiles(dataManager.getStarredFilesList());
                imageBrowserView.setViewMode(ImageBrowserView.ViewMode.GALLERY);
                contentArea.getChildren().add(imageBrowserView);
                break;
        }
    }
}
