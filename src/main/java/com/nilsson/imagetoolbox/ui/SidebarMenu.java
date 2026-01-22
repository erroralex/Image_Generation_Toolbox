package com.nilsson.imagetoolbox.ui;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.fontawesome.FontAwesome;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class SidebarMenu extends VBox {

    private boolean isExpanded = false;
    private final double COLLAPSED_WIDTH = 60;
    private final double EXPANDED_WIDTH = 200;
    private final Consumer<String> onViewChange;
    private final VBox navItems;
    private final Button toggleBtn;
    private final Map<String, Button> viewButtons = new HashMap<>();

    public SidebarMenu(Consumer<String> onViewChange) {
        this.onViewChange = onViewChange;

        this.getStyleClass().add("sidebar-menu");
        this.setPrefWidth(COLLAPSED_WIDTH);
        this.setMinWidth(COLLAPSED_WIDTH);
        this.setMaxWidth(COLLAPSED_WIDTH);

        // 1. Top Toggle Area
        HBox topBar = new HBox();
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new javafx.geometry.Insets(10));

        Label menuLabel = new Label("MENU");
        menuLabel.getStyleClass().add("sidebar-header-label");
        menuLabel.setOpacity(0);
        menuLabel.setManaged(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        toggleBtn = new Button();
        toggleBtn.setGraphic(new FontIcon(FontAwesome.BARS));
        toggleBtn.getStyleClass().add("icon-button");
        toggleBtn.setOnAction(e -> toggle());
        toggleBtn.setTooltip(new Tooltip("Expand/Collapse Menu"));

        topBar.getChildren().addAll(menuLabel, spacer, toggleBtn);

        // 2. Nav Items
        navItems = new VBox(5);
        navItems.setPadding(new javafx.geometry.Insets(20, 0, 0, 0));
        navItems.getChildren().addAll(
                createNavItem("Library", FontAwesome.FOLDER_OPEN, "VIEW_TREE"),
                createNavItem("Speed Sorter", FontAwesome.BOLT, "VIEW_SORTER"),
                createNavItem("Metadata Scrub", FontAwesome.ERASER, "VIEW_SCRUB"),
                createNavItem("Favorites", FontAwesome.STAR, "VIEW_FAVORITES")
        );

        this.getChildren().addAll(topBar, navItems);

        this.widthProperty().addListener((obs, oldVal, newVal) -> {
            boolean expanded = newVal.doubleValue() > 100;
            menuLabel.setOpacity(expanded ? 1 : 0);
            menuLabel.setManaged(expanded);
            navItems.getChildren().forEach(node -> {
                if (node instanceof Button) {
                    HBox graphic = (HBox) ((Button) node).getGraphic();
                    Label lbl = (Label) graphic.getChildren().get(1);
                    lbl.setOpacity(expanded ? 1 : 0);
                    lbl.setManaged(expanded);
                }
            });
        });
    }

    private Button createNavItem(String text, FontAwesome icon, String viewId) {
        Button btn = new Button();
        FontIcon fontIcon = new FontIcon(icon);
        fontIcon.setIconSize(18);

        StackPane iconContainer = new StackPane(fontIcon);
        iconContainer.setPrefWidth(30);
        iconContainer.setAlignment(Pos.CENTER);

        Label lbl = new Label(text);
        lbl.setPadding(new javafx.geometry.Insets(0, 0, 0, 10));
        lbl.setOpacity(0);
        lbl.setManaged(false);

        HBox content = new HBox(iconContainer, lbl);
        content.setAlignment(Pos.CENTER_LEFT);

        btn.setGraphic(content);
        btn.getStyleClass().add("sidebar-btn");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setTooltip(new Tooltip(text));

        btn.setOnAction(e -> {
            setActive(viewId);
            onViewChange.accept(viewId);
        });

        viewButtons.put(viewId, btn);
        return btn;
    }

    public void setActive(String viewId) {
        viewButtons.values().forEach(b -> b.getStyleClass().remove("sidebar-btn-active"));
        if (viewButtons.containsKey(viewId)) {
            viewButtons.get(viewId).getStyleClass().add("sidebar-btn-active");
        }
    }

    public void toggle() {
        isExpanded = !isExpanded;
        double targetWidth = isExpanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;

        Timeline timeline = new Timeline(
                new KeyFrame(Duration.millis(200),
                        new KeyValue(this.prefWidthProperty(), targetWidth),
                        new KeyValue(this.minWidthProperty(), targetWidth),
                        new KeyValue(this.maxWidthProperty(), targetWidth)
                )
        );
        timeline.play();
    }
}