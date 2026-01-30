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
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * ViewModel for the Image Browser interface.
 * Handles image loading, folder watching, collection management, metadata extraction,
 * and filtering logic within an MVVM architecture.
 */
public class ImageBrowserViewModel implements ViewModel {

    // --- Services ---
    private final UserDataManager dataManager;
    private final MetadataService metaService;
    private final ExecutorService executor;

    // --- View State ---
    private final ObservableList<File> currentFiles = FXCollections.observableArrayList();
    private final ObservableList<File> selectedImages = FXCollections.observableArrayList();
    private final ObjectProperty<File> selectedImage = new SimpleObjectProperty<>();

    // --- Filter State ---
    private final StringProperty searchQuery = new SimpleStringProperty("");
    private final ObservableList<String> availableModels = FXCollections.observableArrayList("All");
    private final ObservableList<String> availableSamplers = FXCollections.observableArrayList("All");
    private final ObjectProperty<String> selectedModel = new SimpleObjectProperty<>("All");
    private final ObjectProperty<String> selectedSampler = new SimpleObjectProperty<>("All");

    // --- Collection State ---
    private final ObservableList<String> collectionList = FXCollections.observableArrayList();
    private final StringProperty currentSourceLabel = new SimpleStringProperty("Folder");

    // --- Sidebar State ---
    private final ObjectProperty<Map<String, String>> activeMetadata = new SimpleObjectProperty<>(new HashMap<>());
    private final ObjectProperty<Set<String>> activeTags = new SimpleObjectProperty<>(new HashSet<>());
    private final BooleanProperty activeStarred = new SimpleBooleanProperty(false);

    // --- Watcher State ---
    private Thread watcherThread;
    private volatile boolean isWatching = false;

    // --- Constructor & Initialization ---

    @Inject
    public ImageBrowserViewModel(UserDataManager dataManager,
                                 MetadataService metaService,
                                 ExecutorService executor) {
        this.dataManager = dataManager;
        this.metaService = metaService;
        this.executor = executor;
        loadFilterOptions();
        refreshCollections();
    }

    private void loadFilterOptions() {
        executor.submit(() -> {
            List<String> models = dataManager.getDistinctMetadataValues("Model");
            List<String> samplers = dataManager.getDistinctMetadataValues("Sampler");
            Platform.runLater(() -> {
                availableModels.addAll(models);
                availableSamplers.addAll(samplers);
            });
        });
    }

    // --- Collection Operations ---

    public void refreshCollections() {
        executor.submit(() -> {
            List<String> cols = dataManager.getCollections();
            Platform.runLater(() -> collectionList.setAll(cols));
        });
    }

    public void createNewCollection(String name) {
        executor.submit(() -> {
            dataManager.createCollection(name);
            refreshCollections();
        });
    }

    public void deleteCollection(String name) {
        executor.submit(() -> {
            dataManager.deleteCollection(name);
            refreshCollections();
            Platform.runLater(() -> {
                if (name.equals(currentSourceLabel.get())) {
                    currentFiles.clear();
                    currentSourceLabel.set("Library");
                }
            });
        });
    }

    public void addFilesToCollection(String collectionName, List<File> files) {
        if (files == null || files.isEmpty()) return;
        executor.submit(() -> {
            for (File f : files) {
                dataManager.addImageToCollection(collectionName, f);
            }
        });
    }

    public void addSelectedToCollection(String collectionName) {
        addFilesToCollection(collectionName, new ArrayList<>(selectedImages));
    }

    public void removeSelectedFromCollection(String collectionName) {
        if (selectedImages.isEmpty()) return;
        List<File> toRemove = new ArrayList<>(selectedImages);
        executor.submit(() -> {
            for (File f : toRemove) {
                dataManager.removeImageFromCollection(collectionName, f);
            }
            if (collectionName.equals(currentSourceLabel.get())) {
                loadCollection(collectionName);
            }
        });
    }

    // --- Loading Logic ---

    public void loadCollection(String collectionName) {
        stopWatching();
        currentSourceLabel.set(collectionName);
        dataManager.setLastFolder(null);

        Task<List<File>> task = dataManager.getImagesInCollection(collectionName);
        task.setOnSucceeded(e -> {
            currentFiles.setAll(task.getValue());
            updateSelection(new ArrayList<>());
        });
        executor.submit(task);
    }

    public void loadFolder(File folder) {
        if (folder == null || !folder.isDirectory()) return;

        stopWatching();
        currentSourceLabel.set(folder.getName());
        dataManager.setLastFolder(folder);

        Task<List<File>> task = new Task<>() {
            @Override protected List<File> call() {
                File[] files = folder.listFiles((dir, name) -> isImageFile(name));
                if (files == null) return new ArrayList<>();
                return Arrays.stream(files)
                        .sorted((f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()))
                        .collect(Collectors.toList());
            }
        };

        task.setOnSucceeded(e -> {
            currentFiles.setAll(task.getValue());
            startIndexing(task.getValue());
            startWatching(folder);
        });

        executor.submit(task);
    }

    // --- Selection & Sidebar Logic ---

    public void selectImage(File file) {
        updateSelection(file == null ? Collections.emptyList() : Collections.singletonList(file));
    }

    public void updateSelection(List<File> selection) {
        this.selectedImages.setAll(selection);

        if (selection.isEmpty()) {
            selectedImage.set(null);
            activeMetadata.set(new HashMap<>());
            activeTags.set(new HashSet<>());
            activeStarred.set(false);
        } else {
            File lead = selection.get(selection.size() - 1);
            if (!Objects.equals(selectedImage.get(), lead)) {
                selectedImage.set(lead);
                updateSidebar(lead);
            }
        }
    }

    private void updateSidebar(File file) {
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

    // --- Tagging & Starring ---

    public void toggleStar() {
        boolean newState = !activeStarred.get();
        activeStarred.set(newState);

        executor.submit(() -> {
            for (File f : selectedImages) {
                if (newState) dataManager.addStar(f);
                else dataManager.removeStar(f);
            }
        });
    }

    public void addTag(String tag) {
        if (tag == null || tag.isBlank() || selectedImages.isEmpty()) return;

        Set<String> currentTags = new HashSet<>(activeTags.get());
        currentTags.add(tag);
        activeTags.set(currentTags);

        executor.submit(() -> {
            for (File f : selectedImages) dataManager.addTag(f, tag);
        });
    }

    public void removeTag(String tag) {
        if (tag == null || selectedImages.isEmpty()) return;

        Set<String> currentTags = new HashSet<>(activeTags.get());
        currentTags.remove(tag);
        activeTags.set(currentTags);

        executor.submit(() -> {
            for (File f : selectedImages) dataManager.removeTag(f, tag);
        });
    }

    // --- Navigation & Search ---

    public void search(String query) {
        searchQuery.set(query);
        performSearch();
    }

    public void performSearch() {
        String query = searchQuery.get();
        Map<String, String> filters = new HashMap<>();

        if (!"All".equals(selectedModel.get())) filters.put("Model", selectedModel.get());
        if (!"All".equals(selectedSampler.get())) filters.put("Sampler", selectedSampler.get());

        Task<List<File>> task = dataManager.findFilesWithFilters(query, filters, 2000);
        task.setOnSucceeded(e -> {
            currentFiles.setAll(task.getValue());
            startIndexing(task.getValue());
        });
        executor.submit(task);
    }

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

    // --- File System Watcher ---

    private void startWatching(File folder) {
        isWatching = true;
        watcherThread = new Thread(() -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                Path path = folder.toPath();
                path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);

                while (isWatching) {
                    WatchKey key;
                    try {
                        key = watchService.take();
                    } catch (InterruptedException x) {
                        break;
                    }

                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        Path filename = (Path) event.context();
                        File file = path.resolve(filename).toFile();

                        if (!isImageFile(file.getName())) continue;

                        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                            handleFileCreated(file);
                        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                            handleFileDeleted(file);
                        }
                    }
                    if (!key.reset()) break;
                }
            } catch (IOException e) {
                System.err.println("Watcher failed: " + e.getMessage());
            }
        });
        watcherThread.setDaemon(true);
        watcherThread.setName("FolderWatcher-" + folder.getName());
        watcherThread.start();
    }

    private void stopWatching() {
        isWatching = false;
        if (watcherThread != null) {
            watcherThread.interrupt();
            watcherThread = null;
        }
    }

    private void handleFileCreated(File file) {
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        Platform.runLater(() -> {
            if (!currentFiles.contains(file)) {
                currentFiles.add(0, file);
                startIndexing(Collections.singletonList(file));
            }
        });
    }

    private void handleFileDeleted(File file) {
        Platform.runLater(() -> {
            currentFiles.remove(file);
            if (selectedImages.contains(file)) {
                selectedImages.remove(file);
            }
        });
    }

    // --- Utility & Metadata Helpers ---

    private boolean isImageFile(String name) {
        String low = name.toLowerCase();
        return low.endsWith(".png") || low.endsWith(".jpg") ||
                low.endsWith(".jpeg") || low.endsWith(".webp");
    }

    private void performAutoTagging(File file, Map<String, String> meta) {
        if (meta.containsKey("Model")) {
            String model = meta.get("Model");
            if (model != null && !model.isBlank()) {
                dataManager.addTag(file, "Model: " + model.trim());
            }
        }
    }

    public String getRawMetadata(File file) { return metaService.getRawMetadata(file); }

    // --- Properties & Getters ---

    public ObservableList<File> currentFilesProperty() { return currentFiles; }
    public ObjectProperty<Map<String, String>> activeMetadataProperty() { return activeMetadata; }
    public ObjectProperty<Set<String>> activeTagsProperty() { return activeTags; }
    public BooleanProperty activeStarredProperty() { return activeStarred; }
    public ObservableList<String> availableModelsProperty() { return availableModels; }
    public ObservableList<String> availableSamplersProperty() { return availableSamplers; }
    public ObjectProperty<String> selectedModelProperty() { return selectedModel; }
    public ObjectProperty<String> selectedSamplerProperty() { return selectedSampler; }
    public StringProperty searchQueryProperty() { return searchQuery; }
    public ObservableList<String> getCollectionList() { return collectionList; }
    public StringProperty currentSourceLabelProperty() { return currentSourceLabel; }

    public void loadStarred() { currentFiles.setAll(dataManager.getStarredFilesList()); }
    public void pinFolder(File folder) { dataManager.addPinnedFolder(folder); }
    public void unpinFolder(File folder) { dataManager.removePinnedFolder(folder); }
    public List<File> getPinnedFolders() { return dataManager.getPinnedFolders(); }
    public File getLastFolder() { return dataManager.getLastFolder(); }
}