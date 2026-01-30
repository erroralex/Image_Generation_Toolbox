package com.nilsson.imagetoolbox.ui.components;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.fontawesome.FontAwesome;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Navigation component displaying a file system tree.
 * <p>
 * This view handles the display of pinned folders, drives, and the file hierarchy.
 * Directory expansion is handled asynchronously to prevent UI freezing during I/O operations.
 * It does not fetch data from the database but delegates user actions to the {@link FolderNavListener}.
 */
public class FolderNav extends VBox {

    public interface FolderNavListener {
        void onFolderSelected(File folder);
        void onSearch(String query);
        void onShowStarred();
        void onPinFolder(File folder);
        void onUnpinFolder(File folder);
        void onFilesMoved();
    }

    // ==================================================================================
    // FIELDS & CONSTANTS
    // ==================================================================================

    private static final File STARRED_SECTION = new File("::STARRED::");
    private static final File PINNED_SECTION = new File("::PINNED::");
    private static final File DRIVES_SECTION = new File("::DRIVES::");

    private final TreeView<File> treeView;
    private final TextField searchField;
    private final FolderNavListener listener;

    private List<File> pinnedFolders = new ArrayList<>();

    // ==================================================================================
    // CONSTRUCTOR & INITIALIZATION
    // ==================================================================================

    public FolderNav(FolderNavListener listener) {
        this.listener = listener;

        this.setMinWidth(220);
        this.setPrefWidth(260);
        this.getStyleClass().add("folder-nav");
        this.setSpacing(5);
        this.setPadding(new Insets(10, 0, 0, 0));

        // Search Box
        HBox searchBox = new HBox(5);
        searchBox.setAlignment(Pos.CENTER_LEFT);
        searchBox.setPadding(new Insets(0, 10, 5, 10));

        searchField = new TextField();
        searchField.setPromptText("Search tags...");
        searchField.getStyleClass().add("search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) performSearch();
        });

        searchField.textProperty().addListener((obs, old, val) -> {
            if (val.length() > 2) performSearch();
        });

        Button searchBtn = new Button();
        searchBtn.setGraphic(new FontIcon(FontAwesome.SEARCH));
        searchBtn.getStyleClass().add("icon-button");
        searchBtn.setOnAction(e -> performSearch());

        searchBox.getChildren().addAll(searchField, searchBtn);

        Label label = new Label("Library");
        label.getStyleClass().add("section-header");

        // Tree View
        treeView = new TreeView<>();
        treeView.setShowRoot(false);
        treeView.getStyleClass().add("transparent-tree");
        VBox.setVgrow(treeView, Priority.ALWAYS);
        treeView.setCellFactory(tv -> new FolderTreeCell());

        treeView.getSelectionModel().selectedItemProperty().addListener((obs, old, newVal) -> {
            if (newVal != null && newVal.getValue() != null) {
                File f = newVal.getValue();
                if (f.equals(STARRED_SECTION)) {
                    searchField.clear();
                    if (listener != null) listener.onShowStarred();
                } else if (!f.equals(PINNED_SECTION) && !f.equals(DRIVES_SECTION) && f.isDirectory()) {
                    searchField.clear();
                    if (listener != null) listener.onFolderSelected(f);
                }
            }
        });

        this.getChildren().addAll(searchBox, new Separator(), label, treeView);
        refreshTree();
    }

    // ==================================================================================
    // PUBLIC API
    // ==================================================================================

    public void setPinnedFolders(List<File> pinnedFolders) {
        this.pinnedFolders = (pinnedFolders != null) ? pinnedFolders : new ArrayList<>();
        refreshTree();
    }

    public void selectPath(File path) {
        if (path == null || !path.exists()) return;

        if (treeView.getRoot() != null) {
            // Check pinned folders first
            for (TreeItem<File> section : treeView.getRoot().getChildren()) {
                if (PINNED_SECTION.equals(section.getValue())) {
                    for (TreeItem<File> pin : section.getChildren()) {
                        if (path.equals(pin.getValue())) {
                            selectItem(pin);
                            return;
                        }
                    }
                }
            }
            // Check drives
            for (TreeItem<File> section : treeView.getRoot().getChildren()) {
                if (DRIVES_SECTION.equals(section.getValue())) {
                    findAndSelectRecursive(section, path);
                    break;
                }
            }
        }
    }

    // ==================================================================================
    // PRIVATE METHODS
    // ==================================================================================

    private void performSearch() {
        String query = searchField.getText().trim();
        if (query.isEmpty() || listener == null) return;
        treeView.getSelectionModel().clearSelection();
        listener.onSearch(query);
    }

    private void refreshTree() {
        TreeItem<File> invisibleRoot = new TreeItem<>(new File("root"));

        // 1. Starred
        TreeItem<File> starredNode = new TreeItem<>(STARRED_SECTION);
        invisibleRoot.getChildren().add(starredNode);

        // 2. Pinned
        TreeItem<File> pinnedRoot = new TreeItem<>(PINNED_SECTION);
        pinnedRoot.setExpanded(true);
        for (File pin : pinnedFolders) {
            pinnedRoot.getChildren().add(new TreeItem<>(pin));
        }
        invisibleRoot.getChildren().add(pinnedRoot);

        // 3. Drives
        TreeItem<File> drivesRoot = new TreeItem<>(DRIVES_SECTION);
        drivesRoot.setExpanded(true);
        File[] roots = File.listRoots();
        if (roots != null) {
            for (File drive : roots) {
                if (drive.exists()) drivesRoot.getChildren().add(createNode(drive));
            }
        }
        invisibleRoot.getChildren().add(drivesRoot);

        treeView.setRoot(invisibleRoot);
    }

    private TreeItem<File> createNode(File file) {
        return new TreeItem<File>(file) {
            private boolean isLeaf;
            private boolean isFirstTimeChildren = true;
            private boolean isFirstTimeLeaf = true;

            {
                // Add dummy node to enable expansion if it is a directory
                if (file.isDirectory()) {
                    super.getChildren().add(new TreeItem<>());
                }

                // Async expansion logic
                expandedProperty().addListener((obs, wasCollapsed, isExpanded) -> {
                    if (isExpanded && isFirstTimeChildren) {
                        isFirstTimeChildren = false;

                        Task<List<TreeItem<File>>> task = new Task<>() {
                            @Override
                            protected List<TreeItem<File>> call() {
                                if (file != null && file.isDirectory()) {
                                    File[] files = file.listFiles(File::isDirectory);
                                    if (files != null) {
                                        return Arrays.stream(files)
                                                .map(FolderNav.this::createNode)
                                                .collect(Collectors.toList());
                                    }
                                }
                                return new ArrayList<>();
                            }
                        };

                        task.setOnSucceeded(e -> super.getChildren().setAll(task.getValue()));
                        task.setOnFailed(e -> super.getChildren().clear()); // Clear dummy on failure

                        new Thread(task).start();
                    }
                });
            }

            @Override
            public boolean isLeaf() {
                if (isFirstTimeLeaf) {
                    isFirstTimeLeaf = false;
                    isLeaf = file.isFile();
                }
                return isLeaf;
            }
        };
    }

    private boolean findAndSelectRecursive(TreeItem<File> parent, File target) {
        parent.setExpanded(true);
        for (TreeItem<File> child : parent.getChildren()) {
            if (child.getValue().equals(target)) {
                selectItem(child);
                return true;
            }
            if (isParentOf(child.getValue(), target)) {
                if (findAndSelectRecursive(child, target)) return true;
            }
        }
        return false;
    }

    private boolean isParentOf(File parent, File target) {
        return target.getAbsolutePath().startsWith(parent.getAbsolutePath() + File.separator);
    }

    private void selectItem(TreeItem<File> item) {
        treeView.getSelectionModel().select(item);
        int row = treeView.getRow(item);
        if (row >= 0) treeView.scrollTo(row);
    }

    // ==================================================================================
    // INNER CLASSES
    // ==================================================================================

    private class FolderTreeCell extends TreeCell<File> {
        public FolderTreeCell() {
            setOnDragOver(event -> {
                if (getItem() != null && getItem().isDirectory() && event.getDragboard().hasFiles()) {
                    event.acceptTransferModes(TransferMode.MOVE);
                }
                event.consume();
            });

            setOnDragDropped(event -> {
                Dragboard db = event.getDragboard();
                if (db.hasFiles() && getItem() != null) {
                    boolean success = true;
                    for (File source : db.getFiles()) {
                        File target = new File(getItem(), source.getName());
                        if (source.renameTo(target)) {
                            System.out.println("Moved " + source.getName());
                        } else {
                            success = false;
                        }
                    }
                    event.setDropCompleted(success);
                    if (success && listener != null) listener.onFilesMoved();
                    event.consume();
                }
            });
        }

        @Override
        protected void updateItem(File item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().remove("nav-tree-header");
            setStyle("");

            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }

            boolean isStarredHeader = item.equals(STARRED_SECTION);
            boolean isPinnedHeader = item.equals(PINNED_SECTION);
            boolean isDrivesHeader = item.equals(DRIVES_SECTION);
            TreeItem<File> parent = getTreeItem().getParent();
            boolean isInsidePinnedSection = parent != null && PINNED_SECTION.equals(parent.getValue());

            if (isStarredHeader) {
                setText("Starred");
                FontIcon starIcon = new FontIcon(FontAwesome.STAR);
                starIcon.setStyle("-fx-icon-color: #FFD700;");
                setGraphic(starIcon);
                getStyleClass().add("nav-tree-header");
            } else if (isPinnedHeader) {
                setText("PINNED");
                setGraphic(new FontIcon(FontAwesome.THUMB_TACK));
                getStyleClass().add("nav-tree-header");
            } else if (isDrivesHeader) {
                setText("THIS PC");
                setGraphic(new FontIcon(FontAwesome.DESKTOP));
                getStyleClass().add("nav-tree-header");
            } else {
                setText(item.getName().isEmpty() ? item.toString() : item.getName());
                FontIcon icon = isInsidePinnedSection
                        ? new FontIcon(FontAwesome.THUMB_TACK)
                        : new FontIcon(FontAwesome.FOLDER_O);
                if (isInsidePinnedSection) icon.setStyle("-fx-icon-color: #777;");
                setGraphic(icon);

                ContextMenu cm = new ContextMenu();
                MenuItem open = new MenuItem("Open in Explorer");
                open.setOnAction(e -> {
                    try { java.awt.Desktop.getDesktop().open(item); } catch (Exception ex) {}
                });
                cm.getItems().add(open);

                if (listener != null) {
                    if (isInsidePinnedSection) {
                        MenuItem unpin = new MenuItem("Unpin Folder");
                        unpin.setOnAction(e -> listener.onUnpinFolder(item));
                        cm.getItems().add(unpin);
                    } else {
                        if (!pinnedFolders.contains(item)) {
                            MenuItem pin = new MenuItem("Pin Folder");
                            pin.setOnAction(e -> listener.onPinFolder(item));
                            cm.getItems().add(pin);
                        }
                    }
                }
                setContextMenu(cm);
            }
        }
    }
}