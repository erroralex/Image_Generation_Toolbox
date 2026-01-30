package com.nilsson.imagetoolbox.ui.viewmodels;

import com.nilsson.imagetoolbox.data.UserDataManager;
import com.nilsson.imagetoolbox.service.MetadataService;
import de.saxsys.mvvmfx.ViewModel;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;

import javax.inject.Inject;
import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ViewModel for the image browsing interface.
 * Handles the logic for folder navigation, image metadata extraction,
 * searching, and tagging. It bridges the UI with persistent storage and
 * manages background tasks for indexing and metadata parsing.
 */
public class ImageBrowserViewModel implements ViewModel {

    // --- Services ---
    private final UserDataManager dataManager;
    private final MetadataService metaService;
    private final ExecutorService executor;

    // --- View State ---
    private final ObservableList<File> currentFiles = FXCollections.observableArrayList();
    private final ObjectProperty<File> selectedImage = new SimpleObjectProperty<>();

    // --- Sidebar State ---
    private final ObjectProperty<Map<String, String>> activeMetadata = new SimpleObjectProperty<>(new HashMap<>());
    private final ObjectProperty<Set<String>> activeTags = new SimpleObjectProperty<>(new HashSet<>());
    private final BooleanProperty activeStarred = new SimpleBooleanProperty(false);

    @Inject
    public ImageBrowserViewModel(UserDataManager dataManager,
                                 MetadataService metaService,
                                 ExecutorService executor) {
        this.dataManager = dataManager;
        this.metaService = metaService;
        this.executor = executor;
    }

    // --- Actions: Navigation & Selection ---

    public void loadFolder(File folder) {
        if (folder == null || !folder.isDirectory()) return;
        dataManager.setLastFolder(folder);

        Task<List<File>> task = new Task<>() {
            @Override protected List<File> call() {
                File[] files = folder.listFiles((dir, name) -> {
                    String low = name.toLowerCase();
                    return low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".webp");
                });
                if (files == null) return new ArrayList<>();
                return Arrays.stream(files)
                        .sorted((f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()))
                        .collect(Collectors.toList());
            }
        };

        task.setOnSucceeded(e -> {
            currentFiles.setAll(task.getValue());
            startIndexing(task.getValue());
        });

        executor.submit(task);
    }

    public void selectImage(File file) {
        selectedImage.set(file);
        if (file == null) {
            activeMetadata.set(new HashMap<>());
            activeTags.set(new HashSet<>());
            activeStarred.set(false);
            return;
        }

        activeStarred.set(dataManager.isStarred(file));
        activeTags.set(dataManager.getTags(file));

        if (dataManager.hasCachedMetadata(file)) {
            activeMetadata.set(dataManager.getCachedMetadata(file));
        } else {
            activeMetadata.set(new HashMap<>());

            Task<Map<String, String>> metaTask = new Task<>() {
                @Override protected Map<String, String> call() {
                    return metaService.getExtractedData(file);
                }
            };
            metaTask.setOnSucceeded(e -> {
                Map<String, String> meta = metaTask.getValue();
                dataManager.cacheMetadata(file, meta);
                if (Objects.equals(selectedImage.get(), file)) {
                    activeMetadata.set(meta);
                }
            });
            executor.submit(metaTask);
        }
    }

    // --- Actions: User Data Interactions ---

    public void toggleStar() {
        File f = selectedImage.get();
        if (f != null) {
            dataManager.toggleStar(f);
            activeStarred.set(dataManager.isStarred(f));
        }
    }

    public void addTag(String tag) {
        File f = selectedImage.get();
        if (f != null && tag != null && !tag.isBlank()) {
            dataManager.addTag(f, tag);
            activeTags.set(dataManager.getTags(f));
        }
    }

    public void removeTag(String tag) {
        File f = selectedImage.get();
        if (f != null && tag != null) {
            dataManager.removeTag(f, tag);
            activeTags.set(dataManager.getTags(f));
        }
    }

    public void search(String query) {
        Task<List<File>> task = dataManager.findFilesAsync(query, 1000, 0);
        task.setOnSucceeded(e -> currentFiles.setAll(task.getValue()));
        new Thread(task).start();
    }

    public void loadStarred() {
        currentFiles.setAll(dataManager.getStarredFilesList());
    }

    // --- Actions: Pinned Folders & Settings ---

    public void pinFolder(File folder) { dataManager.addPinnedFolder(folder); }
    public void unpinFolder(File folder) { dataManager.removePinnedFolder(folder); }
    public List<File> getPinnedFolders() { return dataManager.getPinnedFolders(); }
    public File getLastFolder() { return dataManager.getLastFolder(); }

    public String getRawMetadata(File file) {
        return metaService.getRawMetadata(file);
    }

    // --- Internal Helpers & Processing ---

    private void startIndexing(List<File> files) {
        if (files == null || files.isEmpty()) return;

        executor.submit(() -> {
            for (File f : files) {
                if (!dataManager.hasCachedMetadata(f)) {
                    Map<String, String> meta = metaService.getExtractedData(f);
                    if (!meta.isEmpty()) {
                        dataManager.cacheMetadata(f, meta);
                        performAutoTagging(f, meta);
                    }
                }
            }
        });
    }

    private void performAutoTagging(File file, Map<String, String> meta) {
        if (meta.containsKey("Model")) {
            String model = meta.get("Model");
            if (model != null && !model.isBlank()) {
                dataManager.addTag(file, "Model: " + model.trim());
            }
        }

        if (meta.containsKey("Loras")) {
            String loras = meta.get("Loras");
            if (loras != null && !loras.isBlank()) {
                Pattern p = Pattern.compile("<lora:([^:>]+)");
                Matcher m = p.matcher(loras);
                while (m.find()) {
                    String loraName = m.group(1).trim();
                    dataManager.addTag(file, "Lora: " + loraName);
                }
            }
        }
    }

    // --- Property Getters ---
    public ObservableList<File> currentFilesProperty() { return currentFiles; }
    public ObjectProperty<Map<String, String>> activeMetadataProperty() { return activeMetadata; }
    public ObjectProperty<Set<String>> activeTagsProperty() { return activeTags; }
    public BooleanProperty activeStarredProperty() { return activeStarred; }
}