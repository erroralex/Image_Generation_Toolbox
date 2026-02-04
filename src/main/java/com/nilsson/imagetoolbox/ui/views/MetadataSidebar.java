package com.nilsson.imagetoolbox.ui.views;

import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 <h2>MetadataSidebar</h2>
 <p>
 A dedicated inspector panel that displays detailed AI generation metadata for a selected image.
 This component provides a deep dive into prompts, sampler settings, and model information.
 </p>
 <h3>Core Features:</h3>
 <ul>
 <li><b>Drawer Transitions:</b> Supports both docked (side-panel) and floating (drawer) modes.</li>
 <li><b>Prompt Inspection:</b> Dedicated sections for positive and negative prompts with integrated copy-to-clipboard functionality.</li>
 <li><b>Technical Grid:</b> Organizes complex generation parameters like Sampler, Scheduler, CFG, and Steps into a scannable grid.</li>
 <li><b>Resource Tracking:</b> Dynamically generates "chips" for LoRAs detected in the metadata or prompt text.</li>
 <li><b>Star Rating:</b> Provides an interactive 5-star rating system synchronized with the underlying database.</li>
 </ul>

 */
public class MetadataSidebar extends VBox {

    // ------------------------------------------------------------------------
    // Action Handler Interface
    // ------------------------------------------------------------------------

    /**
     Listener interface to handle user interactions within the sidebar,
     delegating logic to the controller or ViewModel.
     */
    public interface SidebarActionHandler {
        void onToggleDock();

        void onClose();

        void onSetRating(int rating);

        void onCreateCollection(String name);

        void onAddToCollection(String collectionName);
    }

    // ------------------------------------------------------------------------
    // Fields & UI Controls
    // ------------------------------------------------------------------------

    private final SidebarActionHandler actionHandler;
    private final TextField inspectorFilename;
    private final HBox starRatingBox;
    // Removed dockToggleBtn as requested
    private final TextArea promptArea;
    private final TextArea negativePromptArea;
    private final TextField softwareField, modelField, seedField, samplerField, schedulerField, cfgField, stepsField, resField;
    private final FlowPane lorasFlow;
    private final ComboBox<String> collectionCombo;
    private File currentFile;
    private String currentRawMetadata; // Store raw data for the popup

    // ------------------------------------------------------------------------
    // Constructor & UI Initialization
    // ------------------------------------------------------------------------

    public MetadataSidebar(SidebarActionHandler actionHandler) {
        this.actionHandler = actionHandler;

        this.getStyleClass().add("inspector-drawer");
        this.setPrefWidth(380);
        this.setMinWidth(380);
        this.setMaxWidth(380);

        // --- Header Section ---
        VBox headerContainer = new VBox(10);
        headerContainer.getStyleClass().add("inspector-header");
        headerContainer.setAlignment(Pos.CENTER_LEFT);
        headerContainer.setPadding(new Insets(15));

        inspectorFilename = new TextField("No Selection");
        inspectorFilename.setEditable(false);
        inspectorFilename.getStyleClass().add("inspector-filename-field");
        inspectorFilename.setMaxWidth(Double.MAX_VALUE);

        HBox buttonRow = new HBox(15);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        Button openFileBtn = createLargeIconButton("fa-folder-open:16:white", "Open Location", e -> openFileLocation(currentFile));

        // Updated Action: Show Raw Metadata Window
        Button rawDataBtn = createLargeIconButton("fa-code:16:white", "Raw Metadata", e -> showRawMetadata());

        Button closeBtn = createLargeIconButton("fa-close:16:white", "Close Panel", e -> actionHandler.onClose());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Removed dockToggleBtn from the layout
        buttonRow.getChildren().addAll(openFileBtn, rawDataBtn, spacer, closeBtn);
        headerContainer.getChildren().addAll(inspectorFilename, buttonRow);

        // --- Scrollable Content Section ---
        ScrollPane scrollContent = new ScrollPane();
        scrollContent.setFitToWidth(true);
        scrollContent.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        scrollContent.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        VBox content = new VBox(20);
        content.setPadding(new Insets(15));
        content.setMaxWidth(350);

        // Interaction: Rating
        starRatingBox = new HBox(5);
        starRatingBox.setAlignment(Pos.CENTER);
        for (int i = 1; i <= 5; i++) {
            Button star = new Button();
            star.getStyleClass().add("star-button");
            star.setGraphic(new FontIcon("fa-star-o:20:#808080"));
            final int r = i;
            star.setOnAction(e -> actionHandler.onSetRating(r));
            starRatingBox.getChildren().add(star);
        }

        Label metaHeader = new Label("METADATA");
        metaHeader.getStyleClass().add("section-header-large");
        metaHeader.setAlignment(Pos.CENTER);
        metaHeader.setMaxWidth(Double.MAX_VALUE);

        // Prompt Areas
        VBox posPromptBox = createPromptSection("PROMPT", true);
        promptArea = (TextArea) posPromptBox.getChildren().get(1);

        VBox negPromptBox = createPromptSection("NEGATIVE PROMPT", false);
        negativePromptArea = (TextArea) negPromptBox.getChildren().get(1);

        // Technical Parameter Grid
        GridPane techGrid = new GridPane();
        techGrid.setHgap(15);
        techGrid.setVgap(15);

        softwareField = addTechItem(techGrid, "Software", 0, 0, 2);
        modelField = addTechItem(techGrid, "Model", 0, 1, 2);
        seedField = addTechItem(techGrid, "Seed", 0, 2, 1);
        resField = addTechItem(techGrid, "Resolution", 1, 2, 1);
        samplerField = addTechItem(techGrid, "Sampler", 0, 3, 1);
        schedulerField = addTechItem(techGrid, "Scheduler", 1, 3, 1);
        cfgField = addTechItem(techGrid, "CFG", 0, 4, 1);
        stepsField = addTechItem(techGrid, "Steps", 1, 4, 1);

        // Resource Flow
        VBox loraBox = new VBox(8);
        Label loraTitle = new Label("RESOURCES / LoRAs");
        loraTitle.getStyleClass().add("section-label");
        lorasFlow = new FlowPane(6, 6);
        lorasFlow.setMaxWidth(340);
        loraBox.getChildren().addAll(loraTitle, lorasFlow);

        // Collection Management
        HBox collectionBox = new HBox(10);
        collectionBox.setAlignment(Pos.CENTER_LEFT);
        collectionCombo = new ComboBox<>();
        collectionCombo.setPromptText("Add to Collection...");
        collectionCombo.setMaxWidth(Double.MAX_VALUE);
        collectionCombo.getStyleClass().add("collection-combo");
        HBox.setHgrow(collectionCombo, Priority.ALWAYS);

        Button newColBtn = createIconButton("fa-plus:14:white", "New Collection", e -> {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("New Collection");
            dialog.setHeaderText("Enter collection name:");
            dialog.showAndWait().ifPresent(actionHandler::onCreateCollection);
        });

        Button addColBtn = createIconButton("fa-check:14:white", "Add to Collection", e -> {
            String col = collectionCombo.getValue();
            if (col != null) actionHandler.onAddToCollection(col);
        });
        collectionBox.getChildren().addAll(collectionCombo, newColBtn, addColBtn);

        content.getChildren().addAll(starRatingBox, new Separator(), metaHeader, posPromptBox, negPromptBox,
                new Separator(), techGrid, new Separator(), loraBox, new Region(), collectionBox);
        scrollContent.setContent(content);

        this.getChildren().addAll(headerContainer, scrollContent);
        VBox.setVgrow(scrollContent, Priority.ALWAYS);
    }

    // ------------------------------------------------------------------------
    // Public API & Data Binding
    // ------------------------------------------------------------------------

    public void setCollections(ObservableList<String> collections) {
        collectionCombo.setItems(collections);
    }

    public void updateData(File file, Map<String, String> meta) {
        this.currentFile = file;

        // Capture raw metadata for the popup
        this.currentRawMetadata = (meta != null) ? meta.get("Raw") : null;

        if (file == null) {
            inspectorFilename.setText("No Selection");
            promptArea.setText("");
            negativePromptArea.setText("");
            lorasFlow.getChildren().clear();
            return;
        }

        inspectorFilename.setText(file.getName());
        promptArea.setText(meta.getOrDefault("Prompt", ""));

        String neg = meta.get("Negative");
        if (neg == null) neg = meta.get("Negative Prompt");
        negativePromptArea.setText(neg != null ? neg : "");

        seedField.setText(meta.getOrDefault("Seed", "-"));
        samplerField.setText(meta.getOrDefault("Sampler", "-"));
        schedulerField.setText(meta.getOrDefault("Scheduler", "-"));
        cfgField.setText(meta.getOrDefault("CFG", "-"));
        stepsField.setText(meta.getOrDefault("Steps", "-"));
        modelField.setText(meta.getOrDefault("Model", "-"));

        if (meta.containsKey("Width") && meta.containsKey("Height")) {
            resField.setText(meta.get("Width") + "x" + meta.get("Height"));
        } else {
            resField.setText(meta.getOrDefault("Resolution", "-"));
        }

        String soft = meta.get("Software");
        if (soft == null) soft = meta.get("Generator");
        if (soft == null) soft = meta.getOrDefault("Tool", "Unknown");
        softwareField.setText(soft);

        lorasFlow.getChildren().clear();
        String loraRaw = meta.get("Loras");
        if (loraRaw == null) loraRaw = meta.get("LoRAs");
        if (loraRaw == null) loraRaw = meta.get("Resources");

        if (loraRaw != null && !loraRaw.isEmpty()) {
            for (String lora : loraRaw.split(",")) addLoraChip(lora.trim());
        } else {
            Matcher m = Pattern.compile("<lora:([^:]+):").matcher(promptArea.getText());
            while (m.find()) addLoraChip(m.group(1));
        }
    }

    public void setRating(int rating) {
        for (int i = 0; i < starRatingBox.getChildren().size(); i++) {
            Button b = (Button) starRatingBox.getChildren().get(i);
            FontIcon icon = (FontIcon) b.getGraphic();
            if (i < rating) icon.setIconLiteral("fa-star:20:#FFD700");
            else icon.setIconLiteral("fa-star-o:20:#808080");
        }
    }

    public void updateResolution(double w, double h) {
        if ("-".equals(resField.getText()) || resField.getText().isEmpty()) {
            resField.setText((int) w + "x" + (int) h);
        }
    }

    public void setDocked(boolean isDocked) {
        // No-Op: Button was removed, keeping method stub for compatibility with ImageBrowserView
    }

    // ------------------------------------------------------------------------
    // Raw Metadata Popup
    // ------------------------------------------------------------------------

    private void showRawMetadata() {
        if (currentRawMetadata == null || currentRawMetadata.isEmpty()) return;

        Stage stage = new Stage();
        stage.initStyle(StageStyle.UNDECORATED);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initOwner(this.getScene().getWindow());

        // Header
        Label header = new Label("Raw Metadata");
        header.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button closeBtn = new Button();
        closeBtn.setGraphic(new FontIcon("fa-times:14:white"));
        closeBtn.setStyle("-fx-background-color: transparent; -fx-cursor: hand;");
        closeBtn.setOnAction(e -> stage.close());
        HBox titleBar = new HBox(header, spacer, closeBtn);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(0, 0, 10, 0));

        // Content
        TextArea textArea = new TextArea();
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.getStyleClass().add("prompt-block"); // Reuse adjusted style
        textArea.setStyle("-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 12px;");
        VBox.setVgrow(textArea, Priority.ALWAYS);

        // Format JSON if possible
        try {
            String trimmed = currentRawMetadata.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                ObjectMapper mapper = new ObjectMapper();
                Object json = mapper.readValue(trimmed, Object.class);
                textArea.setText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json));
            } else {
                textArea.setText(currentRawMetadata);
            }
        } catch (Exception e) {
            textArea.setText(currentRawMetadata);
        }

        // Copy Button
        Button copyBtn = new Button("Copy to Clipboard");
        copyBtn.getStyleClass().add("button");
        copyBtn.setMaxWidth(Double.MAX_VALUE);
        copyBtn.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(textArea.getText());
            Clipboard.getSystemClipboard().setContent(cc);
        });

        VBox root = new VBox(10, titleBar, textArea, copyBtn);
        root.setPadding(new Insets(20));
        // Using app-gradient for the border color
        root.setStyle(
                "-fx-background-color: #12141a;" +
                        "-fx-border-color: linear-gradient(to right, #45a29e 0%, #c45dec 100%);" +
                        "-fx-border-width: 1;" +
                        "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.7), 20, 0, 0, 10);"
        );

        Scene scene = new Scene(root, 600, 500);
        if (this.getScene() != null) {
            scene.getStylesheets().addAll(this.getScene().getStylesheets());
        }

        stage.setScene(scene);
        stage.centerOnScreen();
        stage.show();
    }

    // ------------------------------------------------------------------------
    // Internal UI Helpers
    // ------------------------------------------------------------------------

    private TextField addTechItem(GridPane grid, String title, int col, int row, int colSpan) {
        VBox box = new VBox(2);
        Label t = new Label(title);
        t.getStyleClass().add("tech-grid-label");
        TextField v = new TextField("-");
        v.setEditable(false);
        v.getStyleClass().add("tech-grid-value-field");
        v.setMaxWidth(colSpan > 1 ? 330 : 150);
        box.getChildren().addAll(t, v);
        grid.add(box, col, row, colSpan, 1);
        return v;
    }

    private void addLoraChip(String text) {
        TextField tag = new TextField(text);
        tag.setEditable(false);
        tag.getStyleClass().add("lora-chip-field");
        int approxWidth = 20 + (text.length() * 7);
        tag.setPrefWidth(Math.min(approxWidth, 330));
        tag.setMinWidth(Region.USE_PREF_SIZE);
        tag.setMaxWidth(330);
        lorasFlow.getChildren().add(tag);
    }

    private Button createLargeIconButton(String icon, String tooltip, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button btn = new Button();
        btn.setGraphic(new FontIcon(icon));
        btn.getStyleClass().add("icon-button-large");
        if (tooltip != null) btn.setTooltip(new Tooltip(tooltip));
        btn.setOnAction(action);
        return btn;
    }

    private Button createIconButton(String icon, String tooltip, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button btn = new Button();
        btn.setGraphic(new FontIcon(icon));
        btn.getStyleClass().add("icon-button");
        if (tooltip != null) btn.setTooltip(new Tooltip(tooltip));
        btn.setOnAction(action);
        return btn;
    }

    private VBox createPromptSection(String title, boolean isPositive) {
        VBox box = new VBox(5);
        HBox titleRow = new HBox(10);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label lbl = new Label(title);
        lbl.getStyleClass().add("section-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button copyBtn = new Button();
        copyBtn.setGraphic(new FontIcon("fa-copy:12:#aaaaaa"));
        copyBtn.getStyleClass().add("icon-button-small");
        copyBtn.setTooltip(new Tooltip("Copy Text"));
        TextArea area = new TextArea();
        area.getStyleClass().add("prompt-block");
        area.setWrapText(true);
        area.setEditable(false);
        area.setPrefHeight(isPositive ? 100 : 60);
        area.setMaxWidth(330);
        copyBtn.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(area.getText());
            Clipboard.getSystemClipboard().setContent(cc);
        });
        titleRow.getChildren().addAll(lbl, spacer, copyBtn);
        box.getChildren().addAll(titleRow, area);
        return box;
    }

    private void openFileLocation(File file) {
        if (file != null) new Thread(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(file.getParentFile());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}