package com.nilsson.imagetoolbox.ui;

import com.nilsson.imagetoolbox.ui.components.CustomTitleBar;
import com.nilsson.imagetoolbox.ui.components.SidebarMenu;
import com.nilsson.imagetoolbox.ui.viewmodels.ImageBrowserViewModel;
import com.nilsson.imagetoolbox.ui.viewmodels.MainViewModel;
import com.nilsson.imagetoolbox.ui.viewmodels.ScrubViewModel;
import com.nilsson.imagetoolbox.ui.viewmodels.SpeedSorterViewModel;
import com.nilsson.imagetoolbox.ui.views.ImageBrowserView;
import com.nilsson.imagetoolbox.ui.views.ScrubView;
import com.nilsson.imagetoolbox.ui.views.SpeedSorterView;
import de.saxsys.mvvmfx.FluentViewLoader;
import de.saxsys.mvvmfx.InjectViewModel;
import de.saxsys.mvvmfx.JavaView;
import de.saxsys.mvvmfx.ViewTuple;
import javafx.application.Platform;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * The main container for the application UI.
 * This class coordinates the top-level layout, including the custom title bar,
 * sidebar navigation, and the dynamic content area where different views are preloaded
 * and swapped via the MainViewModel.
 */
public class RootLayout extends StackPane implements JavaView<MainViewModel>, Initializable {

    // --- State and ViewModels ---
    @InjectViewModel
    private MainViewModel viewModel;

    private ImageBrowserViewModel browserVM;
    private final Map<String, Parent> viewCache = new HashMap<>();

    // --- UI Components ---
    private CustomTitleBar titleBar;
    private final BorderPane contentPane = new BorderPane();

    // --- Constructor & Styling ---
    public RootLayout() {
        this.getStyleClass().add("root-layout");
        this.setStyle("-fx-background-color: transparent;");

        contentPane.setStyle("-fx-background-color: #1e1e1e; -fx-background-radius: 10; -fx-border-radius: 10; -fx-border-color: #333; -fx-border-width: 1;");

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(this.widthProperty());
        clip.heightProperty().bind(this.heightProperty());
        clip.setArcWidth(20);
        clip.setArcHeight(20);
        this.setClip(clip);

        this.getChildren().add(contentPane);
    }

    // --- Initialization & Lifecycle ---
    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        loadViews();

        SidebarMenu sidebar = new SidebarMenu(viewId -> viewModel.navigate(viewId));
        contentPane.setLeft(sidebar);

        viewModel.activeViewProperty().addListener((obs, oldVal, newVal) -> {
            switchView(newVal);
            sidebar.setActive(newVal);
        });

        switchView(MainViewModel.VIEW_LIBRARY);
        sidebar.setActive(MainViewModel.VIEW_LIBRARY);
    }

    /**
     * Must be called by ViewFactory after instantiation to setup the Window Controls
     */
    public void setStage(Stage stage) {
        this.titleBar = new CustomTitleBar(stage, () -> {
            Platform.exit();
            System.exit(0);
        });
        contentPane.setTop(titleBar);
    }

    // --- View Management ---
    private void loadViews() {
        // Library
        ViewTuple<ImageBrowserView, ImageBrowserViewModel> browserTuple = FluentViewLoader.javaView(ImageBrowserView.class).load();
        viewCache.put(MainViewModel.VIEW_LIBRARY, browserTuple.getView());
        this.browserVM = browserTuple.getViewModel();

        // Speed Sorter
        ViewTuple<SpeedSorterView, SpeedSorterViewModel> sorterTuple = FluentViewLoader.javaView(SpeedSorterView.class).load();
        viewCache.put(MainViewModel.VIEW_SORTER, sorterTuple.getView());

        // Scrubber
        ViewTuple<ScrubView, ScrubViewModel> scrubTuple = FluentViewLoader.javaView(ScrubView.class).load();
        viewCache.put(MainViewModel.VIEW_SCRUB, scrubTuple.getView());
    }

    private void switchView(String viewId) {
        if (MainViewModel.VIEW_FAVORITES.equals(viewId)) {
            Parent libraryView = viewCache.get(MainViewModel.VIEW_LIBRARY);
            contentPane.setCenter(libraryView);
            browserVM.loadStarred();
        } else {
            Parent view = viewCache.get(viewId);
            if (view != null) {
                contentPane.setCenter(view);
            }
        }
    }
}
