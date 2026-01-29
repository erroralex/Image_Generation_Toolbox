package com.nilsson.imagetoolbox.ui;

import com.nilsson.imagetoolbox.data.UserDataManager;
import com.nilsson.imagetoolbox.ui.views.ImageBrowserView;
import com.nilsson.imagetoolbox.ui.views.ScrubView;
import com.nilsson.imagetoolbox.ui.views.SpeedSorterView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.File;

public class RootLayout extends BorderPane {

    private final CustomTitleBar titleBar;
    private final SidebarMenu sidebar;
    private final StackPane contentArea;

    private ImageBrowserView imageBrowserView;
    private SpeedSorterView speedSorterView;
    private ScrubView scrubView;

    public RootLayout(Stage stage) {
        this.sidebar = new SidebarMenu(this::switchView);
        this.setLeft(sidebar);

        this.titleBar = new CustomTitleBar(stage, () -> {
            if (sidebar != null) sidebar.toggle();
        });
        this.setTop(titleBar);

        this.contentArea = new StackPane();
        this.contentArea.setStyle("-fx-background-color: -app-bg-deepest;");

        this.imageBrowserView = new ImageBrowserView();
        this.imageBrowserView.setMinSize(0, 0);
        this.imageBrowserView.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        this.setCenter(contentArea);

        File lastFolder = UserDataManager.getInstance().getLastFolder();
        if (lastFolder != null && lastFolder.exists()) {
            imageBrowserView.loadFolder(lastFolder);
            imageBrowserView.getFolderNav().selectPath(lastFolder);
        }

        switchView("VIEW_TREE");
    }

    private void switchView(String viewId) {
        sidebar.setActive(viewId);
        contentArea.getChildren().clear();

        switch (viewId) {
            case "VIEW_TREE":
                // Logic Fix: Restore the real folder if we were in favorites
                imageBrowserView.restoreLastFolder();
                contentArea.getChildren().add(imageBrowserView);
                break;
            case "VIEW_SORTER":
                if (speedSorterView == null) speedSorterView = new SpeedSorterView();
                contentArea.getChildren().add(speedSorterView);
                break;
            case "VIEW_SCRUB":
                if (scrubView == null) scrubView = new ScrubView();
                contentArea.getChildren().add(scrubView);
                break;
            case "VIEW_FAVORITES":
                imageBrowserView.loadCustomFileList(UserDataManager.getInstance().getStarredFilesList());
                imageBrowserView.setViewMode(ImageBrowserView.ViewMode.GALLERY);
                contentArea.getChildren().add(imageBrowserView);
                break;
        }
    }
}