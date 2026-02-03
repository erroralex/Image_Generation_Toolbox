package com.nilsson.imagetoolbox.ui.components;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;

/**
 <h2>BrowserToolbar</h2>
 <p>
 A custom JavaFX {@link ToolBar} designed for the Image Toolbox library browser.
 This component provides user interface controls for searching, filtering, and
 toggling between different view modes (Grid and Single view).
 </p>
 * <h3>Key UI Components:</h3>
 <ul>
 <li><b>Search Field:</b> A {@link TextField} bidirectionally bound to a search query property.</li>
 <li><b>Filtering Dropdowns:</b> {@link ComboBox} elements for filtering the library by Model, Sampler, and LoRA.</li>
 <li><b>Size Slider:</b> A {@link Slider} that controls the visual scale of image cards in grid mode.</li>
 <li><b>View Controls:</b> Buttons to switch between gallery and detailed single-image perspectives.</li>
 </ul>
 * <h3>Interactivity:</h3>
 <p>
 The toolbar utilizes functional interfaces ({@link Runnable}) to delegate actions back to
 the parent controller or ViewModel, ensuring a clean separation of concerns. Properties
 for search and filters are bound to allow the UI to reflect the state of the underlying data model.
 </p>
 */
public class BrowserToolbar extends ToolBar {

    // ------------------------------------------------------------------------
    // UI Components
    // ------------------------------------------------------------------------

    private final Slider sizeSlider;
    private final TextField searchField;
    private final Button gridBtn;
    private final Button singleBtn;

    // ------------------------------------------------------------------------
    // Action Delegates
    // ------------------------------------------------------------------------

    private Runnable onGridAction;
    private Runnable onSingleAction;
    private Runnable onSearchEnter;

    // ------------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------------

    public BrowserToolbar(StringProperty searchQuery,
                          List<String> models, ObjectProperty<String> selectedModel,
                          List<String> samplers, ObjectProperty<String> selectedSampler,
                          List<String> loras, ObjectProperty<String> selectedLora) {

        this.getStyleClass().add("browser-toolbar");

        // Prevent full-screen expansion
        this.setMinHeight(Region.USE_PREF_SIZE);
        this.setMaxHeight(Region.USE_PREF_SIZE);

        // --- Search Section ---
        searchField = new TextField();
        searchField.setPromptText("Search prompt...");
        searchField.getStyleClass().add("search-field");
        searchField.setPrefWidth(300);
        searchField.textProperty().bindBidirectional(searchQuery);
        searchField.setOnAction(e -> {
            if (onSearchEnter != null) onSearchEnter.run();
        });

        // --- View Mode Section ---
        gridBtn = createButton("fa-th-large", "Gallery View");
        gridBtn.setOnAction(e -> {
            if (onGridAction != null) onGridAction.run();
        });

        singleBtn = createButton("fa-square-o", "Single View");
        singleBtn.setOnAction(e -> {
            if (onSingleAction != null) onSingleAction.run();
        });

        // --- Grid Card Sizing ---
        sizeSlider = new Slider(100, 400, 160);
        sizeSlider.setPrefWidth(120);
        sizeSlider.setVisible(false);

        // --- Filter Section ---
        ComboBox<String> modelBox = createCombo("Model", models, selectedModel);
        ComboBox<String> samplerBox = createCombo("Sampler", samplers, selectedSampler);
        ComboBox<String> loraBox = createCombo("LoRA", loras, selectedLora);

        // Layout Spacer
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        this.getItems().addAll(
                new Label("Search:"), searchField,
                new Separator(),
                new Label("Filter:"), modelBox, samplerBox, loraBox,
                spacer,
                sizeSlider,
                gridBtn, singleBtn
        );
    }

    // ------------------------------------------------------------------------
    // Helper Methods
    // ------------------------------------------------------------------------

    private Button createButton(String iconCode, String tooltip) {
        Button btn = new Button();
        btn.getStyleClass().add("toolbar-button");
        FontIcon icon = new FontIcon(iconCode);
        icon.setIconSize(18);
        btn.setGraphic(icon);
        btn.setTooltip(new Tooltip(tooltip));
        return btn;
    }

    private ComboBox<String> createCombo(String prompt, List<String> items, ObjectProperty<String> boundProperty) {
        ComboBox<String> box = new ComboBox<>();
        box.getItems().add("");
        box.getItems().addAll(items);
        box.setPromptText(prompt);

        if (boundProperty.get() != null) box.setValue(boundProperty.get());

        // Listener to update ViewModel
        box.valueProperty().addListener((o, old, newVal) -> boundProperty.set(newVal));

        // Listener to update View if ViewModel changes externally
        boundProperty.addListener((o, old, newVal) -> {
            if (newVal != null && !newVal.equals(box.getValue())) {
                box.setValue(newVal);
            }
        });

        return box;
    }

    // ------------------------------------------------------------------------
    // Public API / Getters & Setters
    // ------------------------------------------------------------------------

    public DoubleProperty cardSizeProperty() {
        return sizeSlider.valueProperty();
    }

    public void setSliderVisible(boolean visible) {
        sizeSlider.setVisible(visible);
    }

    public void setOnGridAction(Runnable action) {
        this.onGridAction = action;
    }

    public void setOnSingleAction(Runnable action) {
        this.onSingleAction = action;
    }

    public void setOnSearchEnter(Runnable action) {
        this.onSearchEnter = action;
    }
}