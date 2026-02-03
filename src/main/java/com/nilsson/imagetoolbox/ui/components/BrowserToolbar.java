package com.nilsson.imagetoolbox.ui.components;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 <h2>BrowserToolbar</h2>
 <p>
 A floating "Pill" style toolbar containing search, filters, and view controls.
 </p>
 <h3>Refactoring Notes:</h3>
 <ul>
 <li><b>Design:</b> Now acts as a centered floating pill (max-width 920px).</li>
 <li><b>Search:</b> Added a "Clear" (X) button inside the search field.</li>
 </ul>
 */
public class BrowserToolbar extends HBox {

    private final Slider sizeSlider;
    private final TextField searchField;
    private final Button gridBtn;
    private final Button singleBtn;

    private Runnable onGridAction;
    private Runnable onSingleAction;
    private Runnable onSearchEnter;

    public BrowserToolbar(StringProperty searchQuery,
                          ObservableList<String> models, ObjectProperty<String> selectedModel,
                          ObservableList<String> samplers, ObjectProperty<String> selectedSampler,
                          ObservableList<String> loras, ObjectProperty<String> selectedLora) {

        this.getStyleClass().add("browser-toolbar");
        this.setAlignment(Pos.CENTER_LEFT);
        this.setSpacing(12);
        this.setPadding(new Insets(6, 16, 6, 16));

        // Critical: Constrain width to make it a "Pill" rather than a full bar
        this.setMaxWidth(920);
        this.setPickOnBounds(true); // Ensure clicks on the empty space of the pill are caught

        // ====================================================================
        // 1. SEARCH SECTION (With Clear Button)
        // ====================================================================
        StackPane searchContainer = new StackPane();
        searchContainer.getStyleClass().add("search-container");
        searchContainer.setAlignment(Pos.CENTER_LEFT);

        searchField = new TextField();
        searchField.setPromptText("Search...");
        searchField.getStyleClass().add("pill-search-field");
        searchField.setPrefWidth(220);

        // Clear Button (X)
        Button clearBtn = new Button();
        clearBtn.getStyleClass().add("search-clear-button");
        FontIcon closeIcon = new FontIcon("fa-times-circle");
        closeIcon.setIconSize(14);
        clearBtn.setGraphic(closeIcon);
        clearBtn.setCursor(javafx.scene.Cursor.HAND);
        clearBtn.setVisible(false); // Hidden by default
        StackPane.setAlignment(clearBtn, Pos.CENTER_RIGHT);
        StackPane.setMargin(clearBtn, new Insets(0, 5, 0, 0));

        // Logic
        searchField.textProperty().bindBidirectional(searchQuery);
        searchField.textProperty().addListener((obs, old, val) ->
                clearBtn.setVisible(val != null && !val.isEmpty())
        );

        clearBtn.setOnAction(e -> {
            searchField.setText("");
            searchField.requestFocus();
        });

        searchField.setOnAction(e -> {
            if (onSearchEnter != null) onSearchEnter.run();
        });

        searchContainer.getChildren().addAll(searchField, clearBtn);

        // ====================================================================
        // 2. FILTERS SECTION
        // ====================================================================
        ComboBox<String> modelBox = createCombo("Model", models, selectedModel);
        ComboBox<String> samplerBox = createCombo("Sampler", samplers, selectedSampler);
        ComboBox<String> loraBox = createCombo("LoRA", loras, selectedLora);

        // ====================================================================
        // 3. CONTROLS SECTION
        // ====================================================================
        HBox viewControls = new HBox(8);
        viewControls.setAlignment(Pos.CENTER_RIGHT);

        sizeSlider = new Slider(100, 400, 160);
        sizeSlider.setPrefWidth(100);
        sizeSlider.setVisible(false);

        gridBtn = createButton("fa-th-large", "Gallery");
        gridBtn.setOnAction(e -> {
            if (onGridAction != null) onGridAction.run();
        });

        singleBtn = createButton("fa-square-o", "Single");
        singleBtn.setOnAction(e -> {
            if (onSingleAction != null) onSingleAction.run();
        });

        viewControls.getChildren().addAll(sizeSlider, new Separator(javafx.geometry.Orientation.VERTICAL), gridBtn, singleBtn);

        // ====================================================================
        // ASSEMBLE
        // ====================================================================
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        this.getChildren().addAll(searchContainer, modelBox, samplerBox, loraBox, spacer, viewControls);
    }

    private Button createButton(String iconCode, String tooltip) {
        Button btn = new Button();
        btn.getStyleClass().add("toolbar-button");
        FontIcon icon = new FontIcon(iconCode);
        icon.setIconSize(15);
        btn.setGraphic(icon);
        btn.setTooltip(new Tooltip(tooltip));
        return btn;
    }

    private ComboBox<String> createCombo(String prompt, ObservableList<String> items, ObjectProperty<String> boundProperty) {
        ComboBox<String> box = new ComboBox<>();
        box.setItems(items);
        box.setPromptText(prompt);
        box.setPrefWidth(110); // Slightly compact

        if (boundProperty.get() != null) {
            box.setValue(boundProperty.get());
        }

        box.valueProperty().addListener((o, old, newVal) -> boundProperty.set(newVal));

        boundProperty.addListener((o, old, newVal) -> {
            if (newVal == null) {
                box.getSelectionModel().clearSelection();
            } else if (!newVal.equals(box.getValue())) {
                box.setValue(newVal);
            }
        });

        return box;
    }

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