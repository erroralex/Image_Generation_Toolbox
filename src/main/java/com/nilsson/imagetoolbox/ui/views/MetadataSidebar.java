package com.nilsson.imagetoolbox.ui.views;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilsson.imagetoolbox.ui.viewmodels.MetadataSidebarViewModel;
import de.saxsys.mvvmfx.InjectViewModel;
import de.saxsys.mvvmfx.JavaView;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 <h2>MetadataSidebar</h2>
 <p>
 A specialized inspector panel providing a comprehensive view of AI generation metadata.
 This component acts as a bridge between the raw image data and the user interface,
 translating technical parameters into a readable and interactive format.
 </p>
 <p>Functional Structure:
 <ul>
 <li><b>Header:</b> Displays filename and provides quick actions like opening file location or viewing raw data.</li>
 <li><b>Engagement:</b> Features a synchronization-ready 5-star rating system.</li>
 <li><b>Prompt Analysis:</b> Separates positive and negative prompts into scrollable, copy-friendly blocks.</li>
 <li><b>Technical Parameters:</b> A structured grid for Samplers, Schedulers, Seeds, and CFG scales.</li>
 <li><b>Resource Management:</b> Dynamic chip-based display for LoRAs and other external resources.</li>
 <li><b>Tagging:</b> Interface for viewing, adding, and removing generic tags.</li>
 <li><b>Organization:</b> Contextual collection management for quick sorting of assets.</li>
 </ul>
 </p>
 */
public class MetadataSidebar extends VBox implements JavaView<MetadataSidebarViewModel>, Initializable {

    private static final Logger logger = LoggerFactory.getLogger(MetadataSidebar.class);

    @InjectViewModel
    private MetadataSidebarViewModel viewModel;

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

    private final ObjectProperty<SidebarActionHandler> actionHandler = new SimpleObjectProperty<>();
    private TextField inspectorFilename;
    private HBox starRatingBox;
    private TextArea promptArea;
    private TextArea negativePromptArea;
    private TextField softwareField, modelField, seedField, samplerField, schedulerField, cfgField, stepsField, resField;
    private FlowPane lorasFlow;
    private FlowPane tagsFlow;
    private TextField tagInputField;
    private ComboBox<String> collectionCombo;
    private File currentFile;
    private String currentRawMetadata;

    // ------------------------------------------------------------------------
    // Constructor & UI Initialization
    // ------------------------------------------------------------------------

    public MetadataSidebar() {
        this.getStyleClass().add("inspector-drawer");
        this.setPrefWidth(380);
        this.setMinWidth(380);
        this.setMaxWidth(380);
    }

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        // Header
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
        Button rawDataBtn = createLargeIconButton("fa-code:16:white", "Raw Metadata", e -> showRawMetadata());
        Button closeBtn = createLargeIconButton("fa-arrow-right:16:white", "Close Panel", e -> {
            if (actionHandler.get() != null) actionHandler.get().onClose();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        buttonRow.getChildren().addAll(openFileBtn, rawDataBtn, spacer, closeBtn);
        headerContainer.getChildren().addAll(inspectorFilename, buttonRow);

        // Scrollable Content
        ScrollPane scrollContent = new ScrollPane();
        scrollContent.setFitToWidth(true);
        scrollContent.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        scrollContent.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        VBox content = new VBox(20);
        content.setPadding(new Insets(15));
        content.setMaxWidth(350);

        starRatingBox = new HBox(5);
        starRatingBox.setAlignment(Pos.CENTER);
        for (int i = 1; i <= 5; i++) {
            Button star = new Button();
            star.getStyleClass().add("star-button");
            FontIcon icon = new FontIcon("fa-star-o");
            icon.setIconSize(20);
            star.setGraphic(icon);
            final int r = i;
            star.setOnAction(e -> {
                viewModel.setRating(r);
                if (actionHandler.get() != null) actionHandler.get().onSetRating(r);
            });
            starRatingBox.getChildren().add(star);
        }

        Label metaHeader = new Label("METADATA");
        metaHeader.getStyleClass().add("section-header-large");
        metaHeader.setAlignment(Pos.CENTER);
        metaHeader.setMaxWidth(Double.MAX_VALUE);

        VBox posPromptBox = createPromptSection("PROMPT", true);
        promptArea = (TextArea) posPromptBox.getChildren().get(1);

        VBox negPromptBox = createPromptSection("NEGATIVE PROMPT", false);
        negativePromptArea = (TextArea) negPromptBox.getChildren().get(1);

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

        // LoRA Section
        VBox loraBox = new VBox(8);
        Label loraTitle = new Label("RESOURCES / LoRAs");
        loraTitle.getStyleClass().add("section-label");
        lorasFlow = new FlowPane(6, 6);
        lorasFlow.setMaxWidth(340);
        loraBox.getChildren().addAll(loraTitle, lorasFlow);

        // Tags Section
        VBox tagsBox = new VBox(8);
        Label tagsTitle = new Label("TAGS");
        tagsTitle.getStyleClass().add("section-label");
        tagsFlow = new FlowPane(6, 6);
        tagsFlow.setMaxWidth(340);

        HBox tagInputBox = new HBox(5);
        tagInputField = new TextField();
        tagInputField.setPromptText("Add tag...");
        tagInputField.getStyleClass().add("tag-input-field");
        HBox.setHgrow(tagInputField, Priority.ALWAYS);
        
        Button addTagBtn = createIconButton("fa-plus:12:white", "Add Tag", e -> addTag());
        tagInputField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) addTag();
        });

        tagInputBox.getChildren().addAll(tagInputField, addTagBtn);
        tagsBox.getChildren().addAll(tagsTitle, tagsFlow, tagInputBox);

        // Collection Section
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
            dialog.showAndWait().ifPresent(name -> {
                viewModel.createCollection(name);
                if (actionHandler.get() != null) actionHandler.get().onCreateCollection(name);
            });
        });

        Button addColBtn = createIconButton("fa-check:14:white", "Add to Collection", e -> {
            String col = collectionCombo.getValue();
            if (col != null) {
                viewModel.addToCollection(col);
                if (actionHandler.get() != null) actionHandler.get().onAddToCollection(col);
            }
        });
        collectionBox.getChildren().addAll(collectionCombo, newColBtn, addColBtn);

        content.getChildren().addAll(starRatingBox, new Separator(), metaHeader, posPromptBox, negPromptBox,
                new Separator(), techGrid, new Separator(), loraBox, new Separator(), tagsBox, new Region(), collectionBox);
        scrollContent.setContent(content);

        this.getChildren().addAll(headerContainer, scrollContent);
        VBox.setVgrow(scrollContent, Priority.ALWAYS);

        // Bindings
        viewModel.activeMetadataProperty().addListener((obs, old, meta) -> updateData(viewModel.currentFileProperty().get(), meta));
        viewModel.activeTagsProperty().addListener((obs, old, tags) -> updateTags(tags));
        viewModel.activeRatingProperty().addListener((obs, old, rating) -> setRating(rating.intValue()));
        viewModel.getCollections().addListener((ListChangeListener<String>) c -> setCollections(viewModel.getCollections()));
        setCollections(viewModel.getCollections());
    }

    // ------------------------------------------------------------------------
    // Public API & Data Binding
    // ------------------------------------------------------------------------

    public void setActionHandler(SidebarActionHandler handler) {
        this.actionHandler.set(handler);
    }

    public void setCollections(ObservableList<String> collections) {
        collectionCombo.setItems(collections);
    }

    public void updateData(File file, Map<String, String> meta) {
        this.currentFile = file;
        this.currentRawMetadata = (meta != null) ? meta.get("Raw") : null;

        if (file == null) {
            inspectorFilename.setText("No Selection");
            promptArea.setText("");
            negativePromptArea.setText("");
            lorasFlow.getChildren().clear();
            tagsFlow.getChildren().clear();
            return;
        }

        inspectorFilename.setText(file.getName());
        if (meta != null) {
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
    }

    public void updateTags(Set<String> tags) {
        tagsFlow.getChildren().clear();
        if (tags != null) {
            for (String tag : tags) {
                addTagChip(tag);
            }
        }
    }

    public void setRating(int rating) {
        for (int i = 0; i < starRatingBox.getChildren().size(); i++) {
            Button b = (Button) starRatingBox.getChildren().get(i);
            FontIcon icon = (FontIcon) b.getGraphic();

            boolean filled = i < rating;

            if (filled) {
                icon.setIconLiteral("fa-star");
                icon.setIconSize(20);
                icon.setStyle("-fx-fill: #FFD700 !important; -fx-icon-color: #FFD700 !important;");
            } else {
                icon.setIconLiteral("fa-star-o");
                icon.setIconSize(20);
                icon.setStyle(null);
            }
        }
    }

    public void updateResolution(double w, double h) {
        if ("-".equals(resField.getText()) || resField.getText().isEmpty()) {
            resField.setText((int) w + "x" + (int) h);
        }
    }

    public void setDocked(boolean isDocked) {
        // Stub for compatibility with layout orchestrators
    }

    // ------------------------------------------------------------------------
    // Raw Metadata Popup Window
    // ------------------------------------------------------------------------

    private void showRawMetadata() {
        if (currentRawMetadata == null || currentRawMetadata.isEmpty()) return;

        Stage stage = new Stage();
        stage.initStyle(StageStyle.UNDECORATED);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initOwner(this.getScene().getWindow());

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

        TextArea textArea = new TextArea();
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.getStyleClass().add("prompt-block");
        textArea.setStyle("-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 12px;");
        VBox.setVgrow(textArea, Priority.ALWAYS);

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
        root.setStyle("-fx-background-color: #12141a; -fx-border-color: linear-gradient(to right, #45a29e 0%, #c45dec 100%); -fx-border-width: 1; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.7), 20, 0, 0, 10);");

        Scene scene = new Scene(root, 600, 500);
        if (this.getScene() != null) {
            scene.getStylesheets().addAll(this.getScene().getStylesheets());
        }

        stage.setScene(scene);
        stage.centerOnScreen();
        stage.show();
    }

    // ------------------------------------------------------------------------
    // Internal UI Building Helpers
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

    private void addTagChip(String text) {
        HBox chip = new HBox(4);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().add("tag-chip");
        
        Label lbl = new Label(text);
        lbl.setStyle("-fx-text-fill: white;");
        
        Button removeBtn = new Button();
        removeBtn.setGraphic(new FontIcon("fa-times:10:white"));
        removeBtn.getStyleClass().add("icon-button-small");
        removeBtn.setOnAction(e -> {
            viewModel.removeTag(text);
            tagsFlow.getChildren().remove(chip); // Immediate UI update for removal
        });
        
        chip.getChildren().addAll(lbl, removeBtn);
        tagsFlow.getChildren().add(chip);
    }

    private void addTag() {
        String tag = tagInputField.getText().trim();
        if (!tag.isEmpty()) {
            viewModel.addTag(tag);
            addTagChip(tag); // Immediate UI update
            tagInputField.clear();
        }
    }

    private Button createLargeIconButton(String icon, String tooltipText, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button btn = new Button();
        btn.setGraphic(new FontIcon(icon));
        btn.getStyleClass().add("icon-button-large");
        if (tooltipText != null) {
            Tooltip tooltip = new Tooltip(tooltipText);
            tooltip.setAnchorLocation(PopupWindow.AnchorLocation.CONTENT_BOTTOM_LEFT);

            btn.setOnMouseEntered(e -> {
                tooltip.show(btn, e.getScreenX(), e.getScreenY() - 15);
            });
            btn.setOnMouseExited(e -> tooltip.hide());
        }
        btn.setOnAction(action);
        return btn;
    }

    private Button createIconButton(String icon, String tooltipText, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button btn = new Button();
        btn.setGraphic(new FontIcon(icon));
        btn.getStyleClass().add("icon-button");
        if (tooltipText != null) {
            Tooltip tooltip = new Tooltip(tooltipText);
            tooltip.setAnchorLocation(PopupWindow.AnchorLocation.CONTENT_BOTTOM_LEFT);
            btn.setOnMouseEntered(e -> tooltip.show(btn, e.getScreenX(), e.getScreenY() - 15));
            btn.setOnMouseExited(e -> tooltip.hide());
        }
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
        copyBtn.setGraphic(new FontIcon("fa-copy:14:#aaaaaa")); // Size 14
        copyBtn.getStyleClass().add("icon-button-small");

        // Manual tooltip "Above" logic
        Tooltip tooltip = new Tooltip("Copy Text");
        tooltip.setAnchorLocation(PopupWindow.AnchorLocation.CONTENT_BOTTOM_LEFT);
        copyBtn.setOnMouseEntered(e -> tooltip.show(copyBtn, e.getScreenX(), e.getScreenY() - 15));
        copyBtn.setOnMouseExited(e -> tooltip.hide());

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
                logger.error("Failed to open file location", e);
            }
        }).start();
    }
}