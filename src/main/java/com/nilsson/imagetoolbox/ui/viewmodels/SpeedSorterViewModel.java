package com.nilsson.imagetoolbox.ui.viewmodels;

import com.nilsson.imagetoolbox.data.UserDataManager;
import de.saxsys.mvvmfx.ViewModel;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import javax.inject.Inject;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Stack;

/**
 * ViewModel for the Speed Sorter utility.
 * Handles rapid image triage by allowing users to move files to predefined target folders
 * or the trash using hotkeys or UI triggers. Manages navigation state, file history
 * for undo operations, and visual feedback properties.
 */
public class SpeedSorterViewModel implements ViewModel {

    private final UserDataManager dataManager;

    // --- State: Data ---
    private final ObjectProperty<File> currentInputDir = new SimpleObjectProperty<>();
    private final ObservableList<File> images = FXCollections.observableArrayList();
    private final IntegerProperty currentIndex = new SimpleIntegerProperty(0);
    private final ObjectProperty<File> currentDisplayedFile = new SimpleObjectProperty<>();

    // --- State: UI & Feedback ---
    private final StringProperty feedbackMessage = new SimpleStringProperty("");
    private final StringProperty feedbackColor = new SimpleStringProperty("");
    private final BooleanProperty triggerFeedback = new SimpleBooleanProperty(false);

    // --- State: Configuration ---
    private final File[] targetFolders = new File[5];
    private final ObservableList<String> targetFolderNames = FXCollections.observableArrayList("", "", "", "", "");

    // --- State: History ---
    private final Stack<SortAction> history = new Stack<>();

    @Inject
    public SpeedSorterViewModel(UserDataManager dataManager) {
        this.dataManager = dataManager;
    }

    // --- Initialization ---

    public void initialize() {
        String savedInput = dataManager.getSetting("speed_input_dir", null);
        if (savedInput != null) {
            File f = new File(savedInput);
            if (f.exists() && f.isDirectory()) setInputFolder(f);
        }

        for (int i = 0; i < 5; i++) {
            String savedTarget = dataManager.getSetting("speed_target_" + i, null);
            if (savedTarget != null) {
                File f = new File(savedTarget);
                if (f.exists() && f.isDirectory()) {
                    targetFolders[i] = f;
                    targetFolderNames.set(i, f.getName());
                }
            }
        }
    }

    // --- Actions: Configuration ---

    public void setInputFolder(File folder) {
        if (folder != null && folder.exists() && folder.isDirectory()) {
            currentInputDir.set(folder);
            dataManager.setSetting("speed_input_dir", folder.getAbsolutePath());
            loadImages();
        }
    }

    public void setTargetFolder(int index, File folder) {
        if (index >= 0 && index < 5 && folder != null && folder.isDirectory()) {
            targetFolders[index] = folder;
            targetFolderNames.set(index, folder.getName());
            dataManager.setSetting("speed_target_" + index, folder.getAbsolutePath());
        }
    }

    // --- Actions: Sorting Operations ---

    public void moveFile(int targetIndex) {
        if (images.isEmpty() || currentIndex.get() >= images.size()) return;

        if (targetFolders[targetIndex] == null) {
            showFeedback("Target " + (targetIndex + 1) + " not set!", "orange");
            return;
        }

        File source = images.get(currentIndex.get());
        File destFolder = targetFolders[targetIndex];
        File dest = generateUniqueDest(destFolder, source.getName());

        try {
            Files.move(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            history.push(new SortAction(source, dest, false));
            showFeedback("Moved to " + destFolder.getName(), "#48bb78");

            images.remove(currentIndex.get());
            refreshDisplay();
        } catch (IOException e) {
            e.printStackTrace();
            showFeedback("Error moving file", "red");
        }
    }

    public void deleteFile() {
        if (images.isEmpty() || currentIndex.get() >= images.size()) return;
        File source = images.get(currentIndex.get());

        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            try {
                if (Desktop.getDesktop().moveToTrash(source)) {
                    history.push(new SortAction(source, null, true));
                    showFeedback("Moved to Recycle Bin", "#e53e3e");
                    images.remove(currentIndex.get());
                    refreshDisplay();
                } else {
                    showFeedback("Recycle Bin not supported", "orange");
                }
            } catch (Exception e) {
                showFeedback("Error deleting file", "red");
            }
        } else {
            showFeedback("Action not supported on OS", "orange");
        }
    }

    public void undo() {
        if (history.isEmpty()) {
            showFeedback("Nothing to undo", "orange");
            return;
        }

        SortAction last = history.pop();
        if (last.wasDelete) {
            showFeedback("Cannot undo Recycle Bin", "orange");
            return;
        }

        if (last.destination != null && last.destination.exists()) {
            try {
                Files.move(last.destination.toPath(), last.source.toPath(), StandardCopyOption.REPLACE_EXISTING);
                images.add(currentIndex.get(), last.source);
                showFeedback("Undid last move", "#4299e1");
                refreshDisplay();
            } catch (IOException e) {
                showFeedback("Failed to undo", "red");
            }
        }
    }

    // --- Actions: Navigation ---

    public void skip() {
        if (images.isEmpty()) return;
        if (currentIndex.get() < images.size() - 1) {
            currentIndex.set(currentIndex.get() + 1);
            refreshDisplay();
        }
    }

    public void previous() {
        if (currentIndex.get() > 0) {
            currentIndex.set(currentIndex.get() - 1);
            refreshDisplay();
        }
    }

    // --- Internal Helpers ---

    private void loadImages() {
        File dir = currentInputDir.get();
        if (dir == null) return;

        File[] files = dir.listFiles((d, name) -> {
            String low = name.toLowerCase();
            return low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".webp");
        });

        images.clear();
        if (files != null) {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            images.addAll(Arrays.asList(files));
        }
        currentIndex.set(0);
        refreshDisplay();
    }

    private void refreshDisplay() {
        if (images.isEmpty() || currentIndex.get() >= images.size()) {
            currentDisplayedFile.set(null);
        } else {
            currentDisplayedFile.set(images.get(currentIndex.get()));
        }
    }

    private File generateUniqueDest(File folder, String name) {
        File dest = new File(folder, name);
        if (!dest.exists()) return dest;

        int dot = name.lastIndexOf('.');
        String base = (dot > 0) ? name.substring(0, dot) : name;
        String ext = (dot > 0) ? name.substring(dot) : "";
        return new File(folder, base + "_" + System.currentTimeMillis() + ext);
    }

    private void showFeedback(String msg, String color) {
        feedbackMessage.set(msg);
        feedbackColor.set(color);
        triggerFeedback.set(!triggerFeedback.get());
    }

    // --- Accessors ---

    public ObjectProperty<File> currentInputDirProperty() { return currentInputDir; }
    public ObservableList<File> getImages() { return images; }
    public IntegerProperty currentIndexProperty() { return currentIndex; }
    public ObjectProperty<File> currentDisplayedFileProperty() { return currentDisplayedFile; }
    public ObservableList<String> getTargetFolderNames() { return targetFolderNames; }
    public StringProperty feedbackMessageProperty() { return feedbackMessage; }
    public StringProperty feedbackColorProperty() { return feedbackColor; }
    public BooleanProperty triggerFeedbackProperty() { return triggerFeedback; }

    private record SortAction(File source, File destination, boolean wasDelete) {}
}