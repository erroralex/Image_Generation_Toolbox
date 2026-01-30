package com.nilsson.imagetoolbox.ui.controllers;

import com.nilsson.imagetoolbox.data.UserDataManager;
import com.nilsson.imagetoolbox.ui.views.SpeedSorterView;
import javafx.application.Platform;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Controller for the Speed Sorter module.
 * <p>
 * This class handles the business logic for:
 * <ul>
 * <li>Managing the list of images to be sorted.</li>
 * <li>Executing file operations (Move, Delete/Recycle).</li>
 * <li>Managing the Undo History stack.</li>
 * <li>Persisting target folder selections via {@link UserDataManager}.</li>
 * </ul>
 */
public class SpeedSorterController implements SpeedSorterView.ViewListener {

    private final UserDataManager dataManager;
    private final SpeedSorterView view;

    // State
    private File currentInputDir;
    private List<File> images = new ArrayList<>();
    private int currentIndex = 0;

    // Configuration
    private final File[] targetFolders = new File[5];

    // History for Undo
    private final Stack<SortAction> history = new Stack<>();

    public SpeedSorterController(UserDataManager dataManager, SpeedSorterView view) {
        this.dataManager = dataManager;
        this.view = view;
    }

    /**
     * Loads persisted settings (input dir, target folders) and initializes the view.
     * Should be called immediately after construction.
     */
    public void initialize() {
        // 1. Load Input Directory
        String savedInput = dataManager.getSetting("speed_input_dir", null);
        if (savedInput != null) {
            File f = new File(savedInput);
            if (f.exists() && f.isDirectory()) {
                currentInputDir = f;
                view.updateInputPath(f);
                loadImages();
            }
        }

        // 2. Load Target Folders
        for (int i = 0; i < 5; i++) {
            String savedTarget = dataManager.getSetting("speed_target_" + i, null);
            if (savedTarget != null) {
                File f = new File(savedTarget);
                if (f.exists() && f.isDirectory()) {
                    targetFolders[i] = f;
                    view.updateTargetButton(i, f);
                }
            }
        }
    }

    // ==================================================================================
    // VIEW LISTENER IMPLEMENTATION
    // ==================================================================================

    @Override
    public void onSelectInputFolder(File folder) {
        if (folder != null && folder.exists() && folder.isDirectory()) {
            this.currentInputDir = folder;
            dataManager.setSetting("speed_input_dir", folder.getAbsolutePath());
            view.updateInputPath(folder);
            loadImages();
        }
    }

    @Override
    public void onSetTargetFolder(int index, File folder) {
        if (folder != null && folder.exists() && folder.isDirectory()) {
            targetFolders[index] = folder;
            dataManager.setSetting("speed_target_" + index, folder.getAbsolutePath());
            view.updateTargetButton(index, folder);
        }
    }

    @Override
    public void onMoveRequested(int targetIndex) {
        if (images.isEmpty() || currentIndex >= images.size()) return;

        if (targetFolders[targetIndex] == null) {
            view.showFeedback("Target " + (targetIndex + 1) + " not set!", "orange");
            return;
        }

        File source = images.get(currentIndex);
        File destFolder = targetFolders[targetIndex];

        // Calculate destination path (handle duplicates)
        File dest = new File(destFolder, source.getName());
        if (dest.exists()) {
            String name = source.getName();
            int dot = name.lastIndexOf('.');
            String nameNoExt = (dot > 0) ? name.substring(0, dot) : name;
            String ext = (dot > 0) ? name.substring(dot) : "";
            dest = new File(destFolder, nameNoExt + "_" + System.currentTimeMillis() + ext);
        }

        try {
            Files.move(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);

            history.push(new SortAction(source, dest, false));

            view.showFeedback("Moved to " + destFolder.getName(), "#48bb78");

            // Advance
            images.remove(currentIndex);
            refreshCurrentView();

        } catch (IOException e) {
            e.printStackTrace();
            view.showFeedback("Error moving file", "red");
        }
    }

    @Override
    public void onDeleteRequested() {
        if (images.isEmpty() || currentIndex >= images.size()) return;

        File source = images.get(currentIndex);

        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            try {
                if (Desktop.getDesktop().moveToTrash(source)) {
                    view.showFeedback("Moved to Recycle Bin", "#e53e3e");

                    history.push(new SortAction(source, null, true));

                    images.remove(currentIndex);
                    refreshCurrentView();
                } else {
                    view.showFeedback("Recycle Bin not supported", "orange");
                }
            } catch (Exception e) {
                e.printStackTrace();
                view.showFeedback("Error deleting file", "red");
            }
        } else {
            view.showFeedback("Action not supported on this OS", "orange");
        }
    }

    @Override
    public void onUndoRequested() {
        if (history.isEmpty()) {
            view.showFeedback("Nothing to undo", "orange");
            return;
        }

        SortAction lastAction = history.pop();

        if (lastAction.wasDelete) {
            // Complex to undo recycle bin, for now we just warn
            view.showFeedback("Cannot undo Recycle Bin delete", "orange");
            return;
        }

        if (lastAction.destination != null && lastAction.destination.exists()) {
            try {
                // Move back
                Files.move(lastAction.destination.toPath(), lastAction.source.toPath(), StandardCopyOption.REPLACE_EXISTING);

                // Restore to list
                images.add(currentIndex, lastAction.source);

                view.showFeedback("Undid last move", "#4299e1");
                refreshCurrentView();

            } catch (IOException e) {
                e.printStackTrace();
                view.showFeedback("Failed to undo move", "red");
            }
        } else {
            view.showFeedback("File not found to undo", "red");
        }
    }

    @Override
    public void onSkipRequested() {
        if (images.isEmpty()) return;
        currentIndex++;
        refreshCurrentView();
    }

    @Override
    public void onPreviousRequested() {
        if (currentIndex > 0) {
            currentIndex--;
            refreshCurrentView();
        }
    }

    // ==================================================================================
    // INTERNAL LOGIC
    // ==================================================================================

    private void loadImages() {
        if (currentInputDir == null) return;

        File[] files = currentInputDir.listFiles((d, name) -> {
            String low = name.toLowerCase();
            return low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".webp");
        });

        images.clear();
        if (files != null) {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            images = new ArrayList<>(Arrays.asList(files));
        }

        currentIndex = 0;
        refreshCurrentView();
    }

    private void refreshCurrentView() {
        // Boundary checks
        if (images.isEmpty()) {
            view.showEmptyState();
            return;
        }
        if (currentIndex >= images.size()) {
            view.showFinishedState(images.size());
            return;
        }

        // Normal display
        File file = images.get(currentIndex);
        view.displayImage(file, currentIndex + 1, images.size());
    }

    // ==================================================================================
    // HELPER CLASSES
    // ==================================================================================

    /**
     * Records a file operation for Undo purposes.
     */
    private static class SortAction {
        final File source;
        final File destination;
        final boolean wasDelete;

        public SortAction(File source, File destination, boolean wasDelete) {
            this.source = source;
            this.destination = destination;
            this.wasDelete = wasDelete;
        }
    }
}