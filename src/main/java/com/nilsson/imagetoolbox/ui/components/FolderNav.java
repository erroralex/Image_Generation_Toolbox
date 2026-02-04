package com.nilsson.imagetoolbox.ui.components;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.fontawesome.FontAwesome;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 A sidebar navigation component that provides a unified interface for physical and virtual file access.
 * <p>This component manages a hierarchical {@link TreeView} divided into four logical sections:
 <ul>
 <li><b>Starred:</b> Quick access to user-flagged favorite items.</li>
 <li><b>Collections:</b> Virtual groupings of files that exist independently of the filesystem.</li>
 <li><b>Pinned:</b> User-defined shortcuts to frequently accessed local directories.</li>
 <li><b>This PC:</b> A lazy-loading representation of the local physical drives.</li>
 </ul>
 * <p>Key Features:
 <ul>
 <li><b>Lazy Loading:</b> Physical directories are scanned only upon expansion to ensure UI responsiveness.</li>
 <li><b>Auto-Expansion:</b> Supports programmatic selection and path traversal to specific folders.</li>
 <li><b>Drag-and-Drop:</b> Facilitates moving files between physical folders and adding them to virtual collections.</li>
 <li><b>Contextual Actions:</b> Built-in menus for pinning/unpinning and collection management.</li>
 </ul>

 @version 1.0 */
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
    private final FolderNavListener listener;

    // --- Data State ---
    private List<File> pinnedFolders = new ArrayList<>();
    private List<String> collections = new ArrayList<>();

    private final Set<String> autoExpandPaths = Collections.synchronizedSet(new HashSet<>());
    private File targetSelection = null;

    // --- Constructor ---

    public FolderNav(FolderNavListener listener) {
        this.listener = listener;

        this.setMinWidth(220);
        this.setPrefWidth(260);
        this.getStyleClass().add("folder-nav");
        this.setSpacing(5);
        this.setPadding(new Insets(0, 0, 0, 0));

        Label label = new Label("Library");
        label.getStyleClass().add("section-header");
        label.setPadding(new Insets(5, 10, 5, 10));

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

        this.getChildren().addAll(label, treeView);
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

    public void selectFolder(File folder) {
        if (folder == null || !folder.exists()) return;

        targetSelection = folder;
        autoExpandPaths.clear();

        File current = folder;
        while (current != null) {
            autoExpandPaths.add(normalizePath(current));
            current = current.getParentFile();
        }

        if (treeView.getRoot() != null) {
            for (TreeItem<File> section : treeView.getRoot().getChildren()) {
                if (DRIVES_SECTION.equals(section.getValue())) {
                    if (!section.isExpanded()) section.setExpanded(true);

                    for (TreeItem<File> drive : section.getChildren()) {
                        String drivePath = normalizePath(drive.getValue());
                        if (autoExpandPaths.contains(drivePath)) {
                            drive.setExpanded(true);
                        }
                    }
                    break;
                }
            }
        }
    }

    // --- Private Logic ---

    private String normalizePath(File file) {
        return file.getAbsolutePath().replace("\\", "/").toLowerCase();
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
                                        return Arrays.stream(files)
                                                .sorted()
                                                .map(FolderNav.this::createNode)
                                                .collect(Collectors.toList());
                                }
                                return new ArrayList<>();
                            }
                        };
                        task.setOnSucceeded(e -> {
                            List<TreeItem<File>> loadedChildren = task.getValue();
                            super.getChildren().setAll(loadedChildren);

                            for (TreeItem<File> child : loadedChildren) {
                                String childPath = normalizePath(child.getValue());

                                if (autoExpandPaths.contains(childPath)) {
                                    child.setExpanded(true);

                                    if (targetSelection != null && normalizePath(targetSelection).equals(childPath)) {
                                        treeView.getSelectionModel().select(child);
                                        treeView.scrollTo(treeView.getRow(child));
                                        targetSelection = null;
                                    }
                                }
                            }
                        });
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
                setStyle("-fx-padding: 15 0 0 0; -fx-background-color: transparent;");

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
                setStyle("-fx-padding: 15 0 0 0; -fx-background-color: transparent;");
                return;
            }

            if (item.equals(DRIVES_SECTION)) {
                setText("THIS PC");
                setGraphic(createIcon(FontAwesome.DESKTOP, null));
                getStyleClass().add("nav-tree-header");
                setStyle("-fx-padding: 15 0 0 0; -fx-background-color: transparent;");
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