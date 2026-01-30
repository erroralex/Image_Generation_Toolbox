package com.nilsson.imagetoolbox.ui.views;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import org.kordamp.ikonli.fontawesome.FontAwesome;
import org.kordamp.ikonli.javafx.FontIcon;

import java.awt.Desktop;
import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 * Sidebar component for displaying image metadata, tags, and actions.
 * <p>
 * This class is a "Dumb View". It does not fetch data.
 * It receives data via {@link #updateData(File, Map, Set, boolean)} and
 * delegates actions to the {@link SidebarListener}.
 */
public class MetadataSidebar extends ScrollPane {

    public interface SidebarListener {
        void onToggleStar(File file);
        void onAddTag(File file, String tag);
        void onRemoveTag(File file, String tag);
        void onOpenRaw(File file);
    }

    private final VBox contentBox;
    private final SidebarListener listener;

    private FlowPane tagsFlowPane;
    private TextField tagInput;
    private File currentFile;

    public MetadataSidebar(SidebarListener listener) {
        this.listener = listener;

        this.contentBox = new VBox(15);
        this.contentBox.setPadding(new Insets(20));

        this.setContent(contentBox);
        this.setFitToWidth(true);
        this.setMinWidth(300);
        this.setPrefWidth(340);
        this.getStyleClass().add("meta-pane");
    }

    /**
     * Updates the sidebar with fresh data pushed from the Controller.
     */
    public void updateData(File file, Map<String, String> metadata, Set<String> tags, boolean isStarred) {
        this.currentFile = file;
        contentBox.getChildren().clear();

        if (file == null) return;

        // 1. Toolbar
        HBox toolbar = new HBox(15);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(0, 0, 10, 0));

        Button btnStar = new Button();
        btnStar.getStyleClass().add("icon-button");
        FontIcon starIcon = new FontIcon(isStarred ? FontAwesome.STAR : FontAwesome.STAR_O);
        starIcon.setIconSize(18);
        if (isStarred) starIcon.setStyle("-fx-icon-color: gold;");
        btnStar.setGraphic(starIcon);
        btnStar.setOnAction(e -> {
            if (listener != null) listener.onToggleStar(file);
        });

        Button btnRaw = new Button();
        btnRaw.setTooltip(new Tooltip("View Raw Metadata"));
        btnRaw.getStyleClass().add("icon-button");
        btnRaw.setGraphic(new FontIcon(FontAwesome.FILE_CODE_O));
        btnRaw.setOnAction(e -> {
            if (listener != null) listener.onOpenRaw(file);
        });

        Button btnFolder = new Button();
        btnFolder.setTooltip(new Tooltip("Open File Location"));
        btnFolder.getStyleClass().add("icon-button");
        btnFolder.setGraphic(new FontIcon(FontAwesome.FOLDER_OPEN_O));
        btnFolder.setOnAction(e -> {
            try { Desktop.getDesktop().open(file.getParentFile()); } catch (Exception ex) {}
        });

        toolbar.getChildren().addAll(btnStar, btnRaw, btnFolder);
        contentBox.getChildren().add(toolbar);

        // 2. Title & Tags
        Label title = new Label(file.getName());
        title.getStyleClass().add("meta-value");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 0 0 10 0;");

        tagsFlowPane = new FlowPane(8, 8);
        if (tags != null) tags.forEach(t -> addTagChip(file, t));

        tagInput = new TextField();
        tagInput.setPromptText("+ Add Tag");
        tagInput.getStyleClass().add("search-field");
        tagInput.setOnAction(e -> {
            String txt = tagInput.getText().trim();
            if (!txt.isEmpty() && listener != null) {
                listener.onAddTag(file, txt);
                tagInput.clear();
            }
        });

        contentBox.getChildren().addAll(title, tagsFlowPane, tagInput, new Separator());

        // 3. Render Metadata Fields
        if (metadata != null && !metadata.isEmpty()) {
            renderFields(metadata);
        } else {
            Label noMeta = new Label("No metadata found or indexing...");
            noMeta.setStyle("-fx-text-fill: #666; -fx-font-style: italic;");
            contentBox.getChildren().add(noMeta);
        }
    }

    private void renderFields(Map<String, String> data) {
        addMetaBlock("Prompt", data.get("Prompt"), true);
        addMetaBlock("Negative", data.get("Negative"), true);

        Separator sep = new Separator();
        sep.setPadding(new Insets(10, 0, 10, 0));
        contentBox.getChildren().add(sep);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        ColumnConstraints col1 = new ColumnConstraints(); col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints(); col2.setPercentWidth(50);
        grid.getColumnConstraints().addAll(col1, col2);

        String sampler = data.getOrDefault("Sampler", "-");
        String scheduler = data.getOrDefault("Scheduler", "-");
        String dim = (data.containsKey("Width") && data.containsKey("Height")) ? data.get("Width") + "x" + data.get("Height") : "-";

        addGridItem(grid, "Model", data.get("Model"), 0, 0, 2);
        addGridItem(grid, "Sampler", sampler, 0, 1, 1);
        addGridItem(grid, "Scheduler", scheduler, 1, 1, 1);
        addGridItem(grid, "Steps", data.get("Steps"), 0, 2, 1);
        addGridItem(grid, "CFG", data.get("CFG"), 1, 2, 1);
        addGridItem(grid, "Seed", data.get("Seed"), 0, 3, 1);
        addGridItem(grid, "Resolution", dim, 1, 3, 1);
        if (data.containsKey("Loras")) addGridItem(grid, "Loras", data.get("Loras"), 0, 4, 2);

        contentBox.getChildren().add(grid);
    }

    private void addTagChip(File file, String tag) {
        String displayTag = tag.trim();
        Label chip = new Label(displayTag);
        chip.getStyleClass().add("tag-chip");
        int hue = Math.abs(displayTag.toLowerCase().hashCode()) % 360;
        chip.setStyle("-fx-background-color: hsb(" + hue + ", 60%, 50%);");

        chip.setOnMouseClicked(e -> {
            if (listener != null) listener.onRemoveTag(file, displayTag);
        });

        tagsFlowPane.getChildren().add(chip);
    }

    private void addMetaBlock(String label, String value, boolean copy) {
        if (value == null || value.isEmpty()) return;
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Label lbl = new Label(label);
        lbl.getStyleClass().add("meta-label");
        header.getChildren().add(lbl);
        if (copy) {
            Button cp = new Button();
            cp.setGraphic(new FontIcon(FontAwesome.COPY));
            cp.getStyleClass().add("icon-button-small");
            cp.setOnAction(e -> {
                ClipboardContent cc = new ClipboardContent();
                cc.putString(value);
                Clipboard.getSystemClipboard().setContent(cc);
            });
            header.getChildren().add(cp);
        }
        TextArea t = new TextArea(value);
        t.setEditable(false);
        t.setWrapText(true);
        t.getStyleClass().add("meta-value-text-area");
        t.setPrefRowCount(Math.min(6, Math.max(1, value.length() / 45)));
        VBox b = new VBox(2, header, t);
        b.getStyleClass().add("meta-value-box");
        contentBox.getChildren().add(b);
    }

    private void addGridItem(GridPane g, String l, String v, int c, int r, int cs) {
        if (v == null) v = "-";
        VBox b = new VBox(2);
        Label lb = new Label(l);
        lb.getStyleClass().add("meta-label");
        TextArea t = new TextArea(v);
        t.setEditable(false);
        t.setWrapText(true);
        t.getStyleClass().add("meta-value-text-area");
        t.setPrefRowCount(1);
        t.setMaxWidth(Double.MAX_VALUE);
        b.getChildren().addAll(lb, t);
        g.add(b, c, r, cs, 1);
    }
}