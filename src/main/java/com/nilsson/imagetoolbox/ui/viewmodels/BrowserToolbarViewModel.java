package com.nilsson.imagetoolbox.ui.viewmodels;

import de.saxsys.mvvmfx.ViewModel;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ObservableList;

import javax.inject.Inject;

/**
 ViewModel for the BrowserToolbar component within the Image Toolbox application.
 * <p>This class acts as a mediator between the toolbar UI and the main image browser logic.
 It manages the state for search queries, filtering criteria (Models, Samplers, LoRAs, and Ratings),
 and view-specific settings like card scaling and layout actions.</p>
 * <p>Key responsibilities include:</p>
 <ul>
 <li>Delegating search and filter state to the {@link SearchViewModel}.</li>
 <li>Providing UI-bound properties for grid resizing and layout switching.</li>
 <li>Exposing {@link Runnable} properties to handle UI events like search execution or view toggles.</li>
 </ul>
 * @see de.saxsys.mvvmfx.ViewModel

 @see SearchViewModel */
public class BrowserToolbarViewModel implements ViewModel {

    // ------------------------------------------------------------------------
    // Fields & State
    // ------------------------------------------------------------------------

    private final SearchViewModel searchViewModel;

    private final DoubleProperty cardSize = new SimpleDoubleProperty(160);
    private final ObjectProperty<Runnable> onGridAction = new SimpleObjectProperty<>();
    private final ObjectProperty<Runnable> onSingleAction = new SimpleObjectProperty<>();
    private final ObjectProperty<Runnable> onSearchEnter = new SimpleObjectProperty<>();

    // ------------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------------

    @Inject
    public BrowserToolbarViewModel(SearchViewModel searchViewModel) {
        this.searchViewModel = searchViewModel;
    }

    // ------------------------------------------------------------------------
    // Search & Filter Delegation (searchViewModel)
    // ------------------------------------------------------------------------

    public StringProperty searchQueryProperty() {
        return searchViewModel.searchQueryProperty();
    }

    public ObservableList<String> getModels() {
        return searchViewModel.getModels();
    }

    public ObjectProperty<String> selectedModelProperty() {
        return searchViewModel.selectedModelProperty();
    }

    public ObservableList<String> getSamplers() {
        return searchViewModel.getSamplers();
    }

    public ObjectProperty<String> selectedSamplerProperty() {
        return searchViewModel.selectedSamplerProperty();
    }

    public ObservableList<String> getLoras() {
        return searchViewModel.getLoras();
    }

    public ObjectProperty<String> selectedLoraProperty() {
        return searchViewModel.selectedLoraProperty();
    }

    public ObservableList<String> getStars() {
        return searchViewModel.getStars();
    }

    public ObjectProperty<String> selectedStarProperty() {
        return searchViewModel.selectedStarProperty();
    }

    // ------------------------------------------------------------------------
    // View Settings & Properties
    // ------------------------------------------------------------------------

    public DoubleProperty cardSizeProperty() {
        return cardSize;
    }

    public ObjectProperty<Runnable> onGridActionProperty() {
        return onGridAction;
    }

    public ObjectProperty<Runnable> onSingleActionProperty() {
        return onSingleAction;
    }

    public ObjectProperty<Runnable> onSearchEnterProperty() {
        return onSearchEnter;
    }

    // ------------------------------------------------------------------------
    // Action Triggers
    // ------------------------------------------------------------------------

    public void triggerGridAction() {
        if (onGridAction.get() != null) {
            onGridAction.get().run();
        }
    }

    public void triggerSingleAction() {
        if (onSingleAction.get() != null) {
            onSingleAction.get().run();
        }
    }

    public void triggerSearchEnter() {
        if (onSearchEnter.get() != null) {
            onSearchEnter.get().run();
        }
    }
}
