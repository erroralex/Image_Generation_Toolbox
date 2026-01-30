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
import java.util.Optional;
import java.util.stream.Collectors;

/**
 Navigation component displaying a file system tree and Virtual Collections.
 This sidebar component allows users to browse physical drives, access pinned folders,
 view starred images, and manage virtual collections via drag-and-drop operations.
 */
public class FolderNav extends VBox {

    /**
     Interface for handling navigation and collection events.
     */
    public interface FolderNavListener {
        void onFolderSelected(File folder);

        void onCollectionSelected(String collectionName);

        void onSearch(String query);

        void onShowStarred();

        void onPinFolder(File folder);

        void onUnpinFolder(File folder);

        void onFilesMoved();

        void onCreateCollection(String name);

        void onDeleteCollection(String name);

        void onAddFilesToCollection(String collectionName, List<File> files);
    }

    // --- Static Constants ---
    private static final File STARRED_SECTION = new File("::STARRED::");
    private static final File PINNED_SECTION = new File("::PINNED::");
    private static final File COLLECTIONS_SECTION = new File("::COLLECTIONS::");
    private static final File DRIVES_SECTION = new File("::DRIVES::");

    // --- UI Fields ---
    private final TreeView<File> treeView;
    private final TextField searchField;
    private final FolderNavListener listener;

    // --- Data State ---
    private List<File> pinnedFolders = new ArrayList<>();
    private List<String> collections = new ArrayList<>();

    // --- Constructor ---

    public FolderNav(FolderNavListener listener) {
        this.listener = listener;

        this.setMinWidth(220);
        this.setPrefWidth(260);
        this.getStyleClass().add("folder-nav");
        this.setSpacing(5);
        this.setPadding(new Insets(10, 0, 0, 0));

        // --- Search Box Section ---
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

        Button searchBtn = new Button();
        searchBtn.setGraphic(new FontIcon(FontAwesome.SEARCH));
        searchBtn.getStyleClass().add("icon-button");
        searchBtn.setOnAction(e -> performSearch());

        searchBox.getChildren().addAll(searchField, searchBtn);

        Label label = new Label("Library");
        label.getStyleClass().add("section-header");

        // --- Tree View Section ---
        treeView = new TreeView<>();
        treeView.setShowRoot(false);
        treeView.getStyleClass().add("transparent-tree");
        VBox.setVgrow(treeView, Priority.ALWAYS);
        treeView.setCellFactory(tv -> new FolderTreeCell());

        treeView.getSelectionModel().selectedItemProperty().addListener((obs, old, newVal) -> {
            if (newVal != null && newVal.getValue() != null) {
                File f = newVal.getValue();
                if (f.equals(STARRED_SECTION)) {
                    listener.onShowStarred();
                } else if (f.getPath().startsWith("::COL::")) {
                    String colName = f.getName();
                    listener.onCollectionSelected(colName);
                } else if (!f.equals(PINNED_SECTION) && !f.equals(DRIVES_SECTION) && !f.equals(COLLECTIONS_SECTION) && f.isDirectory()) {
                    listener.onFolderSelected(f);
                }
            }
        });

        this.getChildren().addAll(searchBox, new Separator(), label, treeView);
        refreshTree();
    }

    // --- Public API ---

    public void setPinnedFolders(List<File> pinnedFolders) {
        this.pinnedFolders = (pinnedFolders != null) ? pinnedFolders : new ArrayList<>();
        refreshTree();
    }

    public void setCollections(List<String> collections) {
        this.collections = (collections != null) ? collections : new ArrayList<>();
        refreshTree();
    }

    // --- Private Logic ---

    private void performSearch() {
        String query = searchField.getText().trim();
        if (!query.isEmpty() && listener != null) {
            treeView.getSelectionModel().clearSelection();
            listener.onSearch(query);
        }
    }

    private void refreshTree() {
        TreeItem<File> invisibleRoot = new TreeItem<>(new File("root"));

        invisibleRoot.getChildren().add(new TreeItem<>(STARRED_SECTION));

        TreeItem<File> collectionRoot = new TreeItem<>(COLLECTIONS_SECTION);
        collectionRoot.setExpanded(true);
        for (String colName : collections) {
            File colFile = new File("::COL::", colName);
            collectionRoot.getChildren().add(new TreeItem<>(colFile));
        }
        invisibleRoot.getChildren().add(collectionRoot);

        TreeItem<File> pinnedRoot = new TreeItem<>(PINNED_SECTION);
        pinnedRoot.setExpanded(true);
        for (File pin : pinnedFolders) {
            pinnedRoot.getChildren().add(new TreeItem<>(pin));
        }
        invisibleRoot.getChildren().add(pinnedRoot);

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
                if (file.isDirectory()) super.getChildren().add(new TreeItem<>());
                expandedProperty().addListener((obs, wasCollapsed, isExpanded) -> {
                    if (isExpanded && isFirstTimeChildren) {
                        isFirstTimeChildren = false;
                        Task<List<TreeItem<File>>> task = new Task<>() {
                            @Override
                            protected List<TreeItem<File>> call() {
                                if (file.isDirectory()) {
                                    File[] files = file.listFiles(File::isDirectory);
                                    if (files != null)
                                        return Arrays.stream(files).map(FolderNav.this::createNode).collect(Collectors.toList());
                                }
                                return new ArrayList<>();
                            }
                        };
                        task.setOnSucceeded(e -> super.getChildren().setAll(task.getValue()));
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

    // --- Cell Factory & Interaction ---

    private class FolderTreeCell extends TreeCell<File> {
        public FolderTreeCell() {
            setOnDragOver(event -> {
                if (getItem() == null) return;
                boolean isCollection = getItem().getPath().startsWith("::COL::");
                boolean isDirectory = getItem().isDirectory() && !isCollection && !getItem().getPath().startsWith("::");
                if ((isCollection || isDirectory) && event.getDragboard().hasFiles()) {
                    event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
                }
                event.consume();
            });

            setOnDragDropped(event -> {
                Dragboard db = event.getDragboard();
                if (db.hasFiles() && getItem() != null) {
                    boolean success = false;
                    if (getItem().getPath().startsWith("::COL::")) {
                        String colName = getItem().getName();
                        listener.onAddFilesToCollection(colName, db.getFiles());
                        success = true;
                    } else if (getItem().isDirectory()) {
                        for (File source : db.getFiles()) {
                            File target = new File(getItem(), source.getName());
                            if (source.renameTo(target)) success = true;
                        }
                        if (success) listener.onFilesMoved();
                    }
                    event.setDropCompleted(success);
                    event.consume();
                }
            });
        }

        @Override
        protected void updateItem(File item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().remove("nav-tree-header");
            setStyle("");
            setContextMenu(null);

            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            if (item.equals(STARRED_SECTION)) {
                setText("Starred");
                setGraphic(createIcon(FontAwesome.STAR, "#FFD700"));
                getStyleClass().add("nav-tree-header");
                return;
            }

            if (item.equals(COLLECTIONS_SECTION)) {
                setText("COLLECTIONS");
                setGraphic(createIcon(FontAwesome.LIST_ALT, null));
                getStyleClass().add("nav-tree-header");
                ContextMenu cm = new ContextMenu();
                MenuItem createItem = new MenuItem("Create New Collection");
                createItem.setOnAction(e -> showCreateCollectionDialog());
                cm.getItems().add(createItem);
                setContextMenu(cm);
                return;
            }

            if (item.equals(PINNED_SECTION)) {
                setText("PINNED");
                setGraphic(createIcon(FontAwesome.THUMB_TACK, null));
                getStyleClass().add("nav-tree-header");
                return;
            }

            if (item.equals(DRIVES_SECTION)) {
                setText("THIS PC");
                setGraphic(createIcon(FontAwesome.DESKTOP, null));
                getStyleClass().add("nav-tree-header");
                return;
            }

            if (item.getPath().startsWith("::COL::")) {
                setText(item.getName());
                setGraphic(createIcon(FontAwesome.BOOKMARK, "#4da6ff"));
                ContextMenu cm = new ContextMenu();
                MenuItem delItem = new MenuItem("Delete Collection");
                delItem.setOnAction(e -> listener.onDeleteCollection(item.getName()));
                cm.getItems().add(delItem);
                setContextMenu(cm);
                return;
            }

            TreeItem<File> parent = getTreeItem().getParent();
            boolean isPinned = parent != null && PINNED_SECTION.equals(parent.getValue());
            setText(item.getName().isEmpty() ? item.toString() : item.getName());
            FontIcon icon = isPinned ? createIcon(FontAwesome.THUMB_TACK, "#777") : createIcon(FontAwesome.FOLDER_O, null);
            setGraphic(icon);

            ContextMenu cm = new ContextMenu();
            if (isPinned) {
                MenuItem unpin = new MenuItem("Unpin Folder");
                unpin.setOnAction(e -> listener.onUnpinFolder(item));
                cm.getItems().add(unpin);
            } else {
                MenuItem pin = new MenuItem("Pin Folder");
                pin.setOnAction(e -> listener.onPinFolder(item));
                cm.getItems().add(pin);
            }
            setContextMenu(cm);
        }
    }

    // --- Helper Methods ---

    private void showCreateCollectionDialog() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("New Collection");
        dialog.setHeaderText("Create a new Virtual Collection");
        dialog.setContentText("Name:");
        dialog.getDialogPane().getStyleClass().add("my-dialog");
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            if (!name.isBlank()) listener.onCreateCollection(name);
        });
    }

    private FontIcon createIcon(FontAwesome iconCode, String colorHex) {
        FontIcon icon = new FontIcon(iconCode);
        if (colorHex != null) icon.setStyle("-fx-icon-color: " + colorHex + ";");
        return icon;
    }
}