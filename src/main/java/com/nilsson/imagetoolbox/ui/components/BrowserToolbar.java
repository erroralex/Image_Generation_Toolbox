package com.nilsson.imagetoolbox.ui.components;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ListChangeListener;
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
 <li><b>Filters:</b> Converted to MenuButton + Chip pattern for cleaner UI.</li>
 <li><b>View Toggle:</b> Converted to ToggleButtons for state indication.</li>
 </ul>
 */
public class BrowserToolbar extends HBox {

    private final Slider sizeSlider;
    private final TextField searchField;
    private final ToggleButton gridBtn;
    private final ToggleButton singleBtn;
    private final ToggleGroup viewGroup;

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
        // 2. FILTERS SECTION (MenuButton + Chip)
        // ====================================================================
        HBox modelFilter = createFilterControl("Model", models, selectedModel);
        HBox samplerFilter = createFilterControl("Sampler", samplers, selectedSampler);
        HBox loraFilter = createFilterControl("LoRA", loras, selectedLora);

        // ====================================================================
        // 3. CONTROLS SECTION
        // ====================================================================
        HBox viewControls = new HBox(8);
        viewControls.setAlignment(Pos.CENTER_RIGHT);

        sizeSlider = new Slider(100, 400, 160);
        sizeSlider.setPrefWidth(100);
        sizeSlider.setVisible(false);

        viewGroup = new ToggleGroup();

        gridBtn = createToggleButton("fa-th-large", "Gallery");
        gridBtn.setToggleGroup(viewGroup);
        gridBtn.setSelected(true); // Default
        gridBtn.setOnAction(e -> {
            if (onGridAction != null) onGridAction.run();
        });

        singleBtn = createToggleButton("fa-square-o", "Single");
        singleBtn.setToggleGroup(viewGroup);
        singleBtn.setOnAction(e -> {
            if (onSingleAction != null) onSingleAction.run();
        });

        // Ensure one is always selected (optional UX choice, prevents no-selection state)
        viewGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) {
                oldVal.setSelected(true);
            }
        });

        viewControls.getChildren().addAll(sizeSlider, new Separator(javafx.geometry.Orientation.VERTICAL), gridBtn, singleBtn);

        // ====================================================================
        // ASSEMBLE
        // ====================================================================
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        this.getChildren().addAll(searchContainer, modelFilter, samplerFilter, loraFilter, spacer, viewControls);
    }

    /**
     Updates the toggle buttons to reflect the current view state externally.

     @param isGrid true for Gallery/Grid view, false for Single view.
     */
    public void setActiveView(boolean isGrid) {
        if (isGrid) {
            if (!gridBtn.isSelected()) gridBtn.setSelected(true);
        } else {
            if (!singleBtn.isSelected()) singleBtn.setSelected(true);
        }
    }

    /**
     Creates a ToggleButton for view switching.
     */
    private ToggleButton createToggleButton(String iconCode, String tooltip) {
        ToggleButton btn = new ToggleButton();
        btn.getStyleClass().add("toolbar-button");
        FontIcon icon = new FontIcon(iconCode);
        icon.setIconSize(15);
        btn.setGraphic(icon);
        btn.setTooltip(new Tooltip(tooltip));
        return btn;
    }

    /**
     Creates a composite filter control: A static Label dropdown + a dynamic removable chip.
     */
    private HBox createFilterControl(String label, ObservableList<String> items, ObjectProperty<String> boundProperty) {
        HBox container = new HBox(6);
        container.setAlignment(Pos.CENTER_LEFT);

        // 1. The MenuButton (Always visible "Label")
        MenuButton menuBtn = new MenuButton(label);
        menuBtn.getStyleClass().add("toolbar-menu-button");

        // Helper to rebuild menu items
        Runnable populateMenu = () -> {
            menuBtn.getItems().clear();
            for (String item : items) {
                MenuItem mi = new MenuItem(item);
                mi.setOnAction(e -> boundProperty.set(item));
                menuBtn.getItems().add(mi);
            }
        };

        // Initial population and listener for list updates
        populateMenu.run();
        items.addListener((ListChangeListener<String>) c -> populateMenu.run());

        // 2. The Chip (Dynamic)
        Label chip = new Label();
        chip.getStyleClass().add("filter-chip");
        chip.setContentDisplay(ContentDisplay.RIGHT);
        chip.setGraphicTextGap(6);

        // X Icon to clear
        FontIcon xIcon = new FontIcon("fa-times");
        xIcon.setIconSize(10);
        xIcon.setStyle("-fx-fill: rgba(255,255,255,0.7);"); // Slightly dimmed X
        chip.setGraphic(xIcon);

        // Hover effect for the X (handled in CSS usually, but simple logic here)
        chip.setOnMouseEntered(e -> xIcon.setStyle("-fx-fill: white;"));
        chip.setOnMouseExited(e -> xIcon.setStyle("-fx-fill: rgba(255,255,255,0.7);"));

        // Click chip to remove filter
        chip.setOnMouseClicked(e -> boundProperty.set(null));

        // Listener to Show/Hide Chip
        Runnable updateChip = () -> {
            String val = boundProperty.get();
            if (val != null && !val.isEmpty()) {
                chip.setText(val);
                if (!container.getChildren().contains(chip)) {
                    container.getChildren().add(chip);
                }
            } else {
                container.getChildren().remove(chip);
            }
        };

        boundProperty.addListener((o, old, newVal) -> updateChip.run());
        updateChip.run(); // Initial check

        container.getChildren().add(0, menuBtn);
        return container;
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