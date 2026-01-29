package com.nilsson.imagetoolbox.ui;

import com.nilsson.imagetoolbox.data.UserDataManager;
import javafx.application.Platform;
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
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Navigation component displaying a hierarchical file system tree with a specialized
 * "Pinned" section for quick access. Supports lazy loading of directories,
 * drag-and-drop file moving, and context menu actions for pinning folders.
 */
public class FolderNav extends VBox {

    private final TreeView<File> treeView;
    private final TextField searchField;
    private final Consumer<File> onFolderSelected;
    private final Consumer<List<File>> onCustomListSelected;
    private final Runnable onFilesMoved;

    // Virtual File Placeholders
    private static final File STARRED_SECTION = new File("::STARRED::");
    private static final File PINNED_SECTION = new File("::PINNED::");
    private static final File DRIVES_SECTION = new File("::DRIVES::");

    public FolderNav(Consumer<File> onFolderSelected, Consumer<List<File>> onCustomListSelected, Runnable onFilesMoved) {
        this.onFolderSelected = onFolderSelected;
        this.onCustomListSelected = onCustomListSelected;
        this.onFilesMoved = onFilesMoved;

        this.setMinWidth(220);
        this.setPrefWidth(260);
        this.getStyleClass().add("folder-nav");
        this.setSpacing(5);
        this.setPadding(new Insets(10, 0, 0, 0));

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

        searchField.textProperty().addListener((obs, old, val) -> { if(val.length() > 2) performSearch(); });

        Button searchBtn = new Button();
        searchBtn.setGraphic(new FontIcon(FontAwesome.SEARCH));
        searchBtn.getStyleClass().add("icon-button");
        searchBtn.setOnAction(e -> performSearch());

        searchBox.getChildren().addAll(searchField, searchBtn);

        Label label = new Label("Library");
        label.getStyleClass().add("section-header");

        treeView = new TreeView<>();
        treeView.setShowRoot(false);
        treeView.getStyleClass().add("transparent-tree");
        VBox.setVgrow(treeView, Priority.ALWAYS);

        treeView.setCellFactory(tv -> new FolderTreeCell());

        // --- Selection Logic ---
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, old, newVal) -> {
            if (newVal != null && newVal.getValue() != null) {
                File f = newVal.getValue();

                // 1. Handle Starred Click
                if (f.equals(STARRED_SECTION)) {
                    searchField.clear();
                    if (onCustomListSelected != null) {
                        List<File> starredFiles = UserDataManager.getInstance().getStarredFilesList();
                        onCustomListSelected.accept(starredFiles);
                    }
                    return;
                }

                // 2. Handle Standard Directory Click (Ignore Headers)
                if (!f.equals(PINNED_SECTION) && !f.equals(DRIVES_SECTION) && f.isDirectory()) {
                    searchField.clear();
                    onFolderSelected.accept(f);
                }
            }
        });

        this.getChildren().addAll(searchBox, new Separator(), label, treeView);
        refreshTree();
    }

    private void performSearch() {
        String query = searchField.getText().trim();
        if (query.isEmpty()) return;

        treeView.getSelectionModel().clearSelection();
        List<File> results = UserDataManager.getInstance().findFilesByTag(query);
        if (onCustomListSelected != null) {
            onCustomListSelected.accept(results);
        }
    }

    public void selectPath(File path) {
        if (path == null || !path.exists()) return;

        for (TreeItem<File> section : treeView.getRoot().getChildren()) {
            if (section.getValue().equals(PINNED_SECTION)) {
                for (TreeItem<File> pin : section.getChildren()) {
                    if (pin.getValue().equals(path)) {
                        selectItem(pin);
                        return;
                    }
                }
            }
        }
        for (TreeItem<File> section : treeView.getRoot().getChildren()) {
            if (section.getValue().equals(DRIVES_SECTION)) {
                findAndSelectRecursive(section, path);
                break;
            }
        }
    }

    private boolean findAndSelectRecursive(TreeItem<File> parent, File target) {
        parent.setExpanded(true);
        for (TreeItem<File> child : parent.getChildren()) {
            File childFile = child.getValue();
            if (childFile.equals(target)) {
                selectItem(child);
                return true;
            }
            if (isParentOf(childFile, target)) {
                if (findAndSelectRecursive(child, target)) return true;
            }
        }
        return false;
    }

    private boolean isParentOf(File potentialParent, File target) {
        String pPath = potentialParent.getAbsolutePath();
        String tPath = target.getAbsolutePath();
        if (!pPath.endsWith(File.separator)) pPath += File.separator;
        return tPath.startsWith(pPath);
    }

    private void selectItem(TreeItem<File> item) {
        treeView.getSelectionModel().select(item);
        int row = treeView.getRow(item);
        if (row >= 0) treeView.scrollTo(row);
    }

    public void refreshTree() {
        TreeItem<File> invisibleRoot = new TreeItem<>(new File("root"));

        // 1. Starred Section (Top)
        TreeItem<File> starredNode = new TreeItem<>(STARRED_SECTION);
        invisibleRoot.getChildren().add(starredNode);

        // 2. Pinned Section
        TreeItem<File> pinnedRoot = new TreeItem<>(PINNED_SECTION);
        pinnedRoot.setExpanded(true);
        List<File> pins = UserDataManager.getInstance().getPinnedFolders();
        for (File pin : pins) {
            pinnedRoot.getChildren().add(new TreeItem<>(pin));
        }
        if (!pins.isEmpty()) {
            invisibleRoot.getChildren().add(pinnedRoot);
        }

        // 3. Drives Section
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

            @Override
            public javafx.collections.ObservableList<TreeItem<File>> getChildren() {
                if (isFirstTimeChildren) {
                    isFirstTimeChildren = false;
                    super.getChildren().setAll(buildChildren(this));
                }
                return super.getChildren();
            }

            @Override
            public boolean isLeaf() {
                if (isFirstTimeLeaf) {
                    isFirstTimeLeaf = false;
                    isLeaf = file.isFile();
                }
                return isLeaf;
            }

            private java.util.List<TreeItem<File>> buildChildren(TreeItem<File> item) {
                File f = item.getValue();
                if (f != null && f.isDirectory()) {
                    File[] files = f.listFiles(File::isDirectory);
                    if (files != null) {
                        return Arrays.stream(files)
                                .map(FolderNav.this::createNode)
                                .collect(java.util.stream.Collectors.toList());
                    }
                }
                return java.util.Collections.emptyList();
            }
        };
    }

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
                    if (success && onFilesMoved != null) onFilesMoved.run();
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
            } else {
                boolean isStarredHeader = item.equals(STARRED_SECTION);
                boolean isPinnedHeader = item.equals(PINNED_SECTION);
                boolean isDrivesHeader = item.equals(DRIVES_SECTION);
                TreeItem<File> parent = getTreeItem().getParent();
                boolean isInsidePinnedSection = parent != null && PINNED_SECTION.equals(parent.getValue());

                if (isStarredHeader) {
                    setText("Starred");
                    // Gold star for distinction
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
                    String name = item.getName();
                    if (name.isEmpty()) name = item.toString();
                    setText(name);

                    FontIcon icon = isInsidePinnedSection
                            ? new FontIcon(FontAwesome.THUMB_TACK)
                            : new FontIcon(FontAwesome.FOLDER_O);

                    if (isInsidePinnedSection) icon.setStyle("-fx-icon-color: #777;");
                    setGraphic(icon);
                }

                // Context Menus
                if (!isStarredHeader && !isPinnedHeader && !isDrivesHeader) {
                    ContextMenu cm = new ContextMenu();
                    MenuItem open = new MenuItem("Open in Explorer");
                    open.setOnAction(e -> { try { java.awt.Desktop.getDesktop().open(item); } catch (Exception ex) {} });
                    cm.getItems().add(open);

                    if (isInsidePinnedSection) {
                        MenuItem unpin = new MenuItem("Unpin Folder");
                        unpin.setOnAction(e -> {
                            UserDataManager.getInstance().removePinnedFolder(item);
                            refreshTree();
                        });
                        cm.getItems().add(unpin);
                    } else {
                        if (!UserDataManager.getInstance().isPinned(item)) {
                            MenuItem pin = new MenuItem("Pin Folder");
                            pin.setOnAction(e -> {
                                UserDataManager.getInstance().addPinnedFolder(item);
                                refreshTree();
                            });
                            cm.getItems().add(pin);
                        }
                    }
                    setContextMenu(cm);
                } else {
                    setContextMenu(null);
                }
            }
        }
    }
}