package com.nilsson.imagetoolbox.ui.viewmodels;

import de.saxsys.mvvmfx.ViewModel;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * The top-level ViewModel for the application's root layout.
 * This class manages the global navigation state, allowing the application
 * to switch between different functional views like the Library, Sorter,
 * and Favorites while maintaining the current view state as an observable property.
 */
public class MainViewModel implements ViewModel {

    // --- Navigation Constants ---
    public static final String VIEW_LIBRARY = "VIEW_TREE";
    public static final String VIEW_SORTER = "VIEW_SORTER";
    public static final String VIEW_SCRUB = "VIEW_SCRUB";
    public static final String VIEW_COMPARATOR = "VIEW_COMPARATOR";
    public static final String VIEW_FAVORITES = "VIEW_FAVORITES";

    // --- View State ---
    private final StringProperty activeView = new SimpleStringProperty(VIEW_LIBRARY);

    // --- Actions ---
    /**
     * Updates the active view state to the specified view identifier.
     *
     * @param viewId The ID of the view to navigate to.
     */
    public void navigate(String viewId) {
        activeView.set(viewId);
    }

    // --- Properties ---
    public StringProperty activeViewProperty() {
        return activeView;
    }
}