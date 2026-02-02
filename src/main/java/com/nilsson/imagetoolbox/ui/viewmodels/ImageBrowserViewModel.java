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
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 ViewModel for the Image Browser component.
 Manages the state of the image gallery, including file loading, metadata extraction,
 selection tracking, and rating systems using an asynchronous execution model.
 */
public class ImageBrowserViewModel implements ViewModel {

    // ------------------------------------------------------------------------
    // Dependencies
    // ------------------------------------------------------------------------

    private final UserDataManager dataManager;
    private final MetadataService metaService;
    private final ExecutorService executor;

    // ------------------------------------------------------------------------
    // Properties - View State
    // ------------------------------------------------------------------------

    private final ObservableList<File> currentFiles = FXCollections.observableArrayList();
    private final ObservableList<File> selectedImages = FXCollections.observableArrayList();
    private final ObjectProperty<File> selectedImage = new SimpleObjectProperty<>();

    // ------------------------------------------------------------------------
    // Properties - Sidebar & Metadata
    // ------------------------------------------------------------------------

    private final ObjectProperty<Map<String, String>> activeMetadata = new SimpleObjectProperty<>(new HashMap<>());
    private final ObjectProperty<Set<String>> activeTags = new SimpleObjectProperty<>(new HashSet<>());
    private final IntegerProperty activeRating = new SimpleIntegerProperty(0);

    // ------------------------------------------------------------------------
    // Properties - Filters & Search
    // ------------------------------------------------------------------------

    private final StringProperty searchQuery = new SimpleStringProperty("");
    private final ObservableList<String> availableModels = FXCollections.observableArrayList("All");
    private final ObservableList<String> availableSamplers = FXCollections.observableArrayList("All");
    private final ObjectProperty<String> selectedModel = new SimpleObjectProperty<>("All");
    private final ObjectProperty<String> selectedSampler = new SimpleObjectProperty<>("All");

    // ------------------------------------------------------------------------
    // Properties - Collections
    // ------------------------------------------------------------------------

    private final ObservableList<String> collectionList = FXCollections.observableArrayList();

    // ------------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------------

    @Inject
    public ImageBrowserViewModel(UserDataManager dataManager, MetadataService metaService, ExecutorService executor) {
        this.dataManager = dataManager;
        this.metaService = metaService;
        this.executor = executor;
        loadFilterOptions();
        refreshCollections();
    }

    // ------------------------------------------------------------------------
    // Core Logic - Selection & Sidebar
    // ------------------------------------------------------------------------

    public void updateSelection(List<File> selection) {
        this.selectedImages.setAll(selection);

        if (selection.isEmpty()) {
            selectedImage.set(null);
            activeMetadata.set(new HashMap<>());
            activeTags.set(new HashSet<>());
            activeRating.set(0);
        } else {
            File lead = selection.get(selection.size() - 1);
            if (!Objects.equals(selectedImage.get(), lead)) {
                selectedImage.set(lead);
                updateSidebar(lead);
            }
        }
    }

    private void updateSidebar(File file) {
        activeRating.set(dataManager.getRating(file));
        activeTags.set(dataManager.getTags(file));

        if (dataManager.hasCachedMetadata(file)) {
            activeMetadata.set(dataManager.getCachedMetadata(file));
        } else {
            activeMetadata.set(new HashMap<>());
            Task<Map<String, String>> metaTask = new Task<>() {
                @Override
                protected Map<String, String> call() {
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

    // ------------------------------------------------------------------------
    // Core Logic - Rating System
    // ------------------------------------------------------------------------

    public void setRating(int rating) {
        activeRating.set(rating);
        if (selectedImages.isEmpty()) return;

        List<File> target = new ArrayList<>(selectedImages);
        executor.submit(() -> {
            for (File f : target) {
                dataManager.setRating(f, rating);
            }
        });
    }

    public void toggleStar() {
        setRating(activeRating.get() > 0 ? 0 : 5);
    }

    // ------------------------------------------------------------------------
    // File & Folder Management
    // ------------------------------------------------------------------------

    public void loadFolder(File folder) {
        if (folder == null || !folder.isDirectory()) return;
        dataManager.setLastFolder(folder);
        Task<List<File>> task = new Task<>() {
            @Override
            protected List<File> call() {
                File[] files = folder.listFiles((dir, name) -> name.toLowerCase().matches(".*\\.(png|jpg|jpeg|webp)"));
                if (files == null) return new ArrayList<>();
                return Arrays.stream(files)
                        .sorted((f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()))
                        .collect(Collectors.toList());
            }
        };
        task.setOnSucceeded(e -> currentFiles.setAll(task.getValue()));
        executor.submit(task);
    }

    public void loadStarred() {
        currentFiles.setAll(dataManager.getStarredFilesList());
    }

    public File getLastFolder() {
        return dataManager.getLastFolder();
    }

    // ------------------------------------------------------------------------
    // Collections & Pins
    // ------------------------------------------------------------------------

    public void refreshCollections() {
        executor.submit(() -> {
            List<String> c = dataManager.getCollections();
            Platform.runLater(() -> collectionList.setAll(c));
        });
    }

    public void loadCollection(String name) { /* ... */ }

    public void createNewCollection(String name) {
        dataManager.createCollection(name);
        refreshCollections();
    }

    public void deleteCollection(String name) {
        dataManager.deleteCollection(name);
        refreshCollections();
    }

    public void addFilesToCollection(String name, List<File> files) {
        executor.submit(() -> files.forEach(f -> dataManager.addImageToCollection(name, f)));
    }

    public void addSelectedToCollection(String name) {
        addFilesToCollection(name, new ArrayList<>(selectedImages));
    }

    public void pinFolder(File f) {
        dataManager.addPinnedFolder(f);
    }

    public void unpinFolder(File f) {
        dataManager.removePinnedFolder(f);
    }

    public List<File> getPinnedFolders() {
        return dataManager.getPinnedFolders();
    }

    // ------------------------------------------------------------------------
    // Search & Metadata API
    // ------------------------------------------------------------------------

    public void search(String q) {
        performSearch();
    }

    public void performSearch() { /* implementation same as before */ }

    public String getRawMetadata(File f) {
        return metaService.getRawMetadata(f);
    }

    private void loadFilterOptions() { /* ... */ }

    // ------------------------------------------------------------------------
    // Property Accessors
    // ------------------------------------------------------------------------

    public IntegerProperty activeRatingProperty() {
        return activeRating;
    }

    public ObservableList<File> currentFilesProperty() {
        return currentFiles;
    }

    public ObjectProperty<Map<String, String>> activeMetadataProperty() {
        return activeMetadata;
    }

    public ObjectProperty<Set<String>> activeTagsProperty() {
        return activeTags;
    }

    public StringProperty searchQueryProperty() {
        return searchQuery;
    }

    public ObservableList<String> availableModelsProperty() {
        return availableModels;
    }

    public ObservableList<String> availableSamplersProperty() {
        return availableSamplers;
    }

    public ObjectProperty<String> selectedModelProperty() {
        return selectedModel;
    }

    public ObjectProperty<String> selectedSamplerProperty() {
        return selectedSampler;
    }

    public ObservableList<String> getCollectionList() {
        return collectionList;
    }
}