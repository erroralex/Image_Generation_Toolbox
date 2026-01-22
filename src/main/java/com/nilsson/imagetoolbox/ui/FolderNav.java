package com.nilsson.imagetoolbox.ui;

import com.nilsson.imagetoolbox.data.UserDataManager;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTreeCell;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Consumer;

public class FolderNav extends VBox {

    private final Consumer<File> onFolderSelected;
    private final Consumer<List<File>> onCustomListSelected;
    private final Runnable onRefreshNeeded;
    private final TreeView<File> treeView;

    // Added callback for refresh
    public FolderNav(Consumer<File> onFolderSelected, Consumer<List<File>> onCustomListSelected, Runnable onRefreshNeeded) {
        this.onFolderSelected = onFolderSelected;
        this.onCustomListSelected = onCustomListSelected;
        this.onRefreshNeeded = onRefreshNeeded;

        // Increased width as requested
        this.setMinWidth(300);
        this.getStyleClass().add("side-navigation");
        this.setSpacing(0);
        this.setPadding(new Insets(10));

        // Header
        Label lbl = new Label("Library");
        lbl.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 16px; -fx-padding: 0 0 10 0;");
        this.getChildren().add(lbl);

        // Search Field
        TextField searchField = new TextField();
        searchField.setPromptText("Search tags...");
        searchField.getStyleClass().add("search-field");
        searchField.setOnAction(e -> {
            List<File> results = UserDataManager.getInstance().findFilesByTag(searchField.getText());
            if (onCustomListSelected != null) onCustomListSelected.accept(results);
        });

        VBox topBox = new VBox(10, searchField);
        topBox.setPadding(new Insets(0, 0, 10, 0));
        this.getChildren().add(topBox);

        // File Tree
        treeView = createFileSystemTree();
        treeView.getStyleClass().add("nav-tree-view");
        treeView.setShowRoot(false);
        VBox.setVgrow(treeView, Priority.ALWAYS);

        this.getChildren().add(treeView);
    }

    // Fallback constructor for backwards compatibility if needed
    public FolderNav(Consumer<File> onFolderSelected, Consumer<List<File>> onCustomListSelected) {
        this(onFolderSelected, onCustomListSelected, null);
    }

    public void selectPath(File folder) {
        if (folder == null || !folder.exists()) return;
        Platform.runLater(() -> {
            TreeItem<File> root = treeView.getRoot();
            if (root == null) return;
            TreeItem<File> item = findAndExpand(root, folder);
            if (item != null) {
                treeView.getSelectionModel().select(item);
                int row = treeView.getRow(item);
                if (row >= 0) treeView.scrollTo(row);
            }
        });
    }

    private TreeItem<File> findAndExpand(TreeItem<File> parent, File target) {
        for (TreeItem<File> child : parent.getChildren()) {
            if (child.getValue() == null) continue;
            if (child.getValue().equals(target)) return child;
            if (target.getAbsolutePath().startsWith(child.getValue().getAbsolutePath())) {
                child.setExpanded(true);
                return findAndExpand(child, target);
            }
        }
        return null;
    }

    private TreeView<File> createFileSystemTree() {
        TreeItem<File> dummyRoot = new TreeItem<>(new File("Computer"));
        File[] roots = File.listRoots();
        if (roots != null) {
            for (File root : roots) dummyRoot.getChildren().add(createNode(root));
        }
        TreeView<File> tv = new TreeView<>(dummyRoot);

        // IMPLEMENT DRAG AND DROP VIA CUSTOM CELL FACTORY
        tv.setCellFactory(tree -> {
            TreeCell<File> cell = new TextFieldTreeCell<>(new StringConverter<File>() {
                @Override public String toString(File file) {
                    if (file == null) return "";
                    String name = file.getName();
                    return (name == null || name.isEmpty()) ? file.toString() : name;
                }
                @Override public File fromString(String string) { return null; }
            });

            cell.setOnDragOver(event -> {
                if (event.getGestureSource() != cell && event.getDragboard().hasFiles()) {
                    File target = cell.getItem();
                    if (target != null && target.isDirectory()) {
                        event.acceptTransferModes(TransferMode.MOVE);
                    }
                }
                event.consume();
            });

            cell.setOnDragDropped(event -> {
                Dragboard db = event.getDragboard();
                boolean success = false;
                if (db.hasFiles()) {
                    File targetFolder = cell.getItem();
                    if (targetFolder != null && targetFolder.isDirectory()) {
                        for (File file : db.getFiles()) {
                            try {
                                File dest = new File(targetFolder, file.getName());
                                // Move the file
                                Files.move(file.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE);
                                success = true;
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        if (success && onRefreshNeeded != null) {
                            onRefreshNeeded.run();
                        }
                    }
                }
                event.setDropCompleted(success);
                event.consume();
            });

            return cell;
        });

        tv.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.getValue() != null && newVal.getValue().isDirectory()) {
                if (onFolderSelected != null) onFolderSelected.accept(newVal.getValue());
            }
        });
        return tv;
    }

    private TreeItem<File> createNode(final File f) {
        return new TreeItem<File>(f) {
            private boolean isLeaf;
            private boolean isFirstTimeLeaf = true;
            private boolean isFirstTimeChildren = true;

            @Override public boolean isLeaf() {
                if (isFirstTimeLeaf) {
                    isFirstTimeLeaf = false;
                    File[] fList = getValue().listFiles(File::isDirectory);
                    isLeaf = (fList == null || fList.length == 0);
                }
                return isLeaf;
            }

            @Override public ObservableList<TreeItem<File>> getChildren() {
                if (isFirstTimeChildren) {
                    isFirstTimeChildren = false;
                    super.getChildren().setAll(buildChildren(this));
                }
                return super.getChildren();
            }
        };
    }

    private ObservableList<TreeItem<File>> buildChildren(TreeItem<File> treeItem) {
        File f = treeItem.getValue();
        if (f != null && f.isDirectory()) {
            File[] files = f.listFiles(File::isDirectory);
            if (files != null) {
                ObservableList<TreeItem<File>> children = FXCollections.observableArrayList();
                for (File childFile : files) {
                    if (!childFile.isHidden()) children.add(createNode(childFile));
                }
                return children;
            }
        }
        return FXCollections.emptyObservableList();
    }
}