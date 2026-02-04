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
 A specialized UI component providing a centered, "Pill-style" floating toolbar
 for browser navigation and asset filtering.
 * <p>The toolbar is organized into three primary functional zones:
 <ul>
 <li><b>Search:</b> A text input with a conditional clear button for real-time filtering.</li>
 <li><b>Metadata Filters:</b> Dynamic MenuButton controls for Model, Sampler, and LoRA categories,
 utilizing a "Chip" pattern to display and clear active selections.</li>
 <li><b>View Controls:</b> Toggle controls for switching between Gallery and Single view modes,
 along with a contextual card-size slider.</li>
 </ul>
 * <p>This component is designed to be overlayed or positioned at the top of an image browser,
 maintaining a maximum width to preserve its floating aesthetic.</p>
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

    // --- Constructor ---

    public BrowserToolbar(StringProperty searchQuery,
                          ObservableList<String> models, ObjectProperty<String> selectedModel,
                          ObservableList<String> samplers, ObjectProperty<String> selectedSampler,
                          ObservableList<String> loras, ObjectProperty<String> selectedLora) {

        this.getStyleClass().add("browser-toolbar");
        this.setAlignment(Pos.CENTER_LEFT);
        this.setSpacing(12);
        this.setPadding(new Insets(6, 16, 6, 16));
        this.setMaxWidth(920);
        this.setPickOnBounds(true);

        // 1. Search Section
        StackPane searchContainer = new StackPane();
        searchContainer.getStyleClass().add("search-container");
        searchContainer.setAlignment(Pos.CENTER_LEFT);

        searchField = new TextField();
        searchField.setPromptText("Search...");
        searchField.getStyleClass().add("pill-search-field");
        searchField.setPrefWidth(220);

        Button clearBtn = new Button();
        clearBtn.getStyleClass().add("search-clear-button");
        FontIcon closeIcon = new FontIcon("fa-times-circle");
        closeIcon.setIconSize(14);
        clearBtn.setGraphic(closeIcon);
        clearBtn.setCursor(javafx.scene.Cursor.HAND);
        clearBtn.setVisible(false);
        StackPane.setAlignment(clearBtn, Pos.CENTER_RIGHT);
        StackPane.setMargin(clearBtn, new Insets(0, 5, 0, 0));

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

        // 2. Filters Section
        HBox modelFilter = createFilterControl("Model", models, selectedModel);
        HBox samplerFilter = createFilterControl("Sampler", samplers, selectedSampler);
        HBox loraFilter = createFilterControl("LoRA", loras, selectedLora);

        // 3. Controls Section
        HBox viewControls = new HBox(8);
        viewControls.setAlignment(Pos.CENTER_RIGHT);

        sizeSlider = new Slider(100, 400, 160);
        sizeSlider.setPrefWidth(100);
        sizeSlider.setVisible(false);

        viewGroup = new ToggleGroup();

        gridBtn = createToggleButton("fa-th-large", "Gallery");
        gridBtn.setToggleGroup(viewGroup);
        gridBtn.setSelected(true);
        gridBtn.setOnAction(e -> {
            if (onGridAction != null) onGridAction.run();
        });

        singleBtn = createToggleButton("fa-square-o", "Single");
        singleBtn.setToggleGroup(viewGroup);
        singleBtn.setOnAction(e -> {
            if (onSingleAction != null) onSingleAction.run();
        });

        viewGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) {
                oldVal.setSelected(true);
            }
        });

        viewControls.getChildren().addAll(sizeSlider, new Separator(javafx.geometry.Orientation.VERTICAL), gridBtn, singleBtn);

        // Assembly
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        this.getChildren().addAll(searchContainer, modelFilter, samplerFilter, loraFilter, spacer, viewControls);
    }

    // --- Public API ---

    public void setActiveView(boolean isGrid) {
        if (isGrid) {
            if (!gridBtn.isSelected()) gridBtn.setSelected(true);
        } else {
            if (!singleBtn.isSelected()) singleBtn.setSelected(true);
        }
    }

    public DoubleProperty cardSizeProperty() {
        return sizeSlider.valueProperty();
    }

    public void setSliderVisible(boolean visible) {
        sizeSlider.setVisible(visible);
    }

    // --- View Actions ---

    public void setOnGridAction(Runnable action) {
        this.onGridAction = action;
    }

    public void setOnSingleAction(Runnable action) {
        this.onSingleAction = action;
    }

    public void setOnSearchEnter(Runnable action) {
        this.onSearchEnter = action;
    }

    // --- Private UI Helpers ---

    private ToggleButton createToggleButton(String iconCode, String tooltip) {
        ToggleButton btn = new ToggleButton();
        btn.getStyleClass().add("toolbar-button");
        FontIcon icon = new FontIcon(iconCode);
        icon.setIconSize(15);
        btn.setGraphic(icon);
        btn.setTooltip(new Tooltip(tooltip));
        return btn;
    }

    private HBox createFilterControl(String label, ObservableList<String> items, ObjectProperty<String> boundProperty) {
        HBox container = new HBox(6);
        container.setAlignment(Pos.CENTER_LEFT);

        MenuButton menuBtn = new MenuButton(label);
        menuBtn.getStyleClass().add("toolbar-menu-button");

        Runnable populateMenu = () -> {
            menuBtn.getItems().clear();
            for (String item : items) {
                MenuItem mi = new MenuItem(item);
                mi.setOnAction(e -> boundProperty.set(item));
                menuBtn.getItems().add(mi);
            }
        };

        populateMenu.run();
        items.addListener((ListChangeListener<String>) c -> populateMenu.run());

        Label chip = new Label();
        chip.getStyleClass().add("filter-chip");
        chip.setContentDisplay(ContentDisplay.RIGHT);
        chip.setGraphicTextGap(6);

        FontIcon xIcon = new FontIcon("fa-times");
        xIcon.setIconSize(10);
        xIcon.setStyle("-fx-fill: rgba(255,255,255,0.7);");
        chip.setGraphic(xIcon);

        chip.setOnMouseEntered(e -> xIcon.setStyle("-fx-fill: white;"));
        chip.setOnMouseExited(e -> xIcon.setStyle("-fx-fill: rgba(255,255,255,0.7);"));
        chip.setOnMouseClicked(e -> boundProperty.set(null));

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
        updateChip.run();

        container.getChildren().add(0, menuBtn);
        return container;
    }
}