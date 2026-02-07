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
 <h2>RootLayout</h2>
 <p>
 The primary container and orchestrator for the application's user interface.
 This class extends {@link StackPane} and serves as the main shell, managing the
 lifecycle and navigation of sub-views within the MVVM pattern.
 </p>
 * <h3>Key Responsibilities:</h3>
 <ul>
 <li><b>Layout Management:</b> Coordinates a {@link BorderPane} containing a
 custom title bar, a sidebar navigation menu, and a central content area.</li>
 <li><b>View Navigation:</b> Uses a view cache and {@link MainViewModel} to
 swap between the Library, Speed Sorter, and Scrubber modules.</li>
 <li><b>Window Customization:</b> Implements custom window styling including
 rounded corners and transparent backgrounds via clipping.</li>
 </ul>
 * <p>This view implements {@link JavaView} for MVVM integration and
 {@link Initializable} for FXML/JavaFX lifecycle management.</p>
 * @author Nilsson

 @version 1.0 */
public class RootLayout extends StackPane implements JavaView<MainViewModel>, Initializable {

    // ------------------------------------------------------------------------
    // ViewModels and State
    // ------------------------------------------------------------------------

    @InjectViewModel
    private MainViewModel viewModel;

    private ImageBrowserViewModel browserVM;

    private ImageBrowserView browserView;

    /**
     Cache used to store preloaded views to avoid repeated instantiation
     and improve navigation performance.
     */
    private final Map<String, Parent> viewCache = new HashMap<>();

    // ------------------------------------------------------------------------
    // UI Components
    // ------------------------------------------------------------------------

    private CustomTitleBar titleBar;

    private final BorderPane contentPane = new BorderPane();

    // ------------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------------

    /**
     Initializes the RootLayout structure, applies CSS styling, and
     configures the clipping mask for rounded corners.
     */
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

    // ------------------------------------------------------------------------
    // Lifecycle and Initialization
    // ------------------------------------------------------------------------

    /**
     Called automatically by the JavaFX framework. Preloads views and
     establishes data bindings for navigation.
     */
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
     Attaches the primary stage to the custom title bar for window control management.
     Must be called by the ViewFactory after instantiation.
     * @param stage The primary application stage.
     */
    public void setStage(Stage stage) {
        this.titleBar = new CustomTitleBar(stage, () -> {
            Platform.exit();
            System.exit(0);
        });
        contentPane.setTop(titleBar);
    }

    // ------------------------------------------------------------------------
    // View Management Logic
    // ------------------------------------------------------------------------

    /**
     Preloads and caches the main application modules using FluentViewLoader.
     */
    private void loadViews() {
        ViewTuple<ImageBrowserView, ImageBrowserViewModel> browserTuple = FluentViewLoader.javaView(ImageBrowserView.class).load();

        this.browserView = (ImageBrowserView) browserTuple.getView();
        viewCache.put(MainViewModel.VIEW_LIBRARY, this.browserView);
        this.browserVM = browserTuple.getViewModel();

        ViewTuple<SpeedSorterView, SpeedSorterViewModel> sorterTuple = FluentViewLoader.javaView(SpeedSorterView.class).load();
        viewCache.put(MainViewModel.VIEW_SORTER, sorterTuple.getView());

        ViewTuple<ScrubView, ScrubViewModel> scrubTuple = FluentViewLoader.javaView(ScrubView.class).load();
        viewCache.put(MainViewModel.VIEW_SCRUB, scrubTuple.getView());
    }

    /**
     Updates the UI to display the requested view and adjusts specific
     view modes for the Image Browser if applicable.
     * @param viewId The unique identifier of the view to display.
     */
    private void switchView(String viewId) {
        if (MainViewModel.VIEW_COMPARATOR.equals(viewId)) {
            contentPane.setCenter(browserView);
            browserView.setViewMode(ImageBrowserView.ViewMode.COMPARATOR);
        } else if (MainViewModel.VIEW_FAVORITES.equals(viewId)) {
            contentPane.setCenter(browserView);
            browserView.setViewMode(ImageBrowserView.ViewMode.GALLERY);
            browserVM.loadStarred();
        } else if (MainViewModel.VIEW_LIBRARY.equals(viewId)) {
            contentPane.setCenter(browserView);
            browserView.setViewMode(ImageBrowserView.ViewMode.BROWSER);
        } else {
            Parent view = viewCache.get(viewId);
            if (view != null) {
                contentPane.setCenter(view);
            }
        }
    }
}