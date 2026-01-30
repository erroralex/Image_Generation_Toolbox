package com.nilsson.imagetoolbox.ui.factory;

import com.nilsson.imagetoolbox.ui.RootLayout;
import com.nilsson.imagetoolbox.ui.viewmodels.MainViewModel;
import de.saxsys.mvvmfx.FluentViewLoader;
import de.saxsys.mvvmfx.ViewTuple;
import javafx.stage.Stage;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Factory class responsible for instantiating and configuring UI components.
 * This class leverages the mvvmfx FluentViewLoader to manage the lifecycle
 * of Views and their corresponding ViewModels, ensuring proper dependency
 * injection and stage linkage.
 */
@Singleton
public class ViewFactory {

    // --- Constructor ---
    @Inject
    public ViewFactory() {
    }

    // --- Factory Methods ---
    /**
     * Creates and initializes the root layout of the application.
     *
     * @param stage The primary stage used for window management and custom title bar logic.
     * @return The initialized RootLayout instance.
     */
    public RootLayout createRootLayout(Stage stage) {
        ViewTuple<RootLayout, MainViewModel> tuple =
                FluentViewLoader.javaView(RootLayout.class).load();

        RootLayout rootLayout = tuple.getCodeBehind();

        rootLayout.setStage(stage);

        return rootLayout;
    }
}