package com.nilsson.imagetoolbox.ui.viewmodels;

import com.nilsson.imagetoolbox.data.DatabaseService;
import com.nilsson.imagetoolbox.data.UserDataManager;
import com.nilsson.imagetoolbox.service.MetadataService;
import de.saxsys.mvvmfx.ViewModel;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;

import javax.inject.Inject;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 <h2>ImageBrowserViewModel</h2>
 <p>
 The primary ViewModel for the application's browser interface. It bridges the data layer
 ({@link UserDataManager}) and the view, managing state for file navigation, selection,
 and advanced filtering based on AI generation metadata.
 </p>

 <h3>Key Features:</h3>
 <ul>
 <li><b>Asynchronous Processing:</b> Offloads heavy I/O operations, such as file scanning
 and metadata extraction, to a background {@link ExecutorService} to keep the UI responsive.</li>
 <li><b>Advanced Filtering:</b> Implements a {@link FilteredList} that dynamically
 responds to text queries and metadata chip selections (Model, Sampler, LoRA).</li>
 <li><b>Metadata Indexing:</b> Maintains a thread-safe {@link ConcurrentHashMap} of
 metadata for the current folder to enable instant searching and filtering.</li>
 <li><b>Collections & Pins:</b> Synchronizes user-defined virtual collections and
 pinned filesystem paths with the persistent data store.</li>
 </ul>

 <h3>UI Data Flow:</h3>
 <p>
 The ViewModel exposes {@link ObservableList} and {@link Property} objects that the View
 binds to directly. It uses {@link Platform#runLater} to ensure that data updates
 resulting from background tasks occur on the JavaFX Application Thread.
 </p>
 */
public class ImageBrowserViewModel implements ViewModel {

    // ------------------------------------------------------------------------
    // Dependencies
    // ------------------------------------------------------------------------
    private final UserDataManager dataManager;
    private final MetadataService metaService;
    private final DatabaseService databaseService;
    private final ExecutorService executor;

    // ------------------------------------------------------------------------
    // View State & Observable Data
    // ------------------------------------------------------------------------
    private final ObservableList<File> selectedImages = FXCollections.observableArrayList();
    private final ObjectProperty<File> selectedImage = new SimpleObjectProperty<>();

    // Filtering & Indexing
    private final ObservableList<File> allFolderFiles = FXCollections.observableArrayList();
    private final FilteredList<File> filteredFiles = new FilteredList<>(allFolderFiles, p -> true);

    // CACHES (Critical for performance)
    private final Map<File, Map<String, String>> currentFolderMetadata = new ConcurrentHashMap<>();
    private final Map<File, Integer> ratingCache = new ConcurrentHashMap<>();

    // Sidebar Properties
    private final ObjectProperty<Map<String, String>> activeMetadata = new SimpleObjectProperty<>(new HashMap<>());
    private final ObjectProperty<Set<String>> activeTags = new SimpleObjectProperty<>(new HashSet<>());
    private final IntegerProperty activeRating = new SimpleIntegerProperty(0);

    // Filter UI Properties
    private final StringProperty searchQuery = new SimpleStringProperty("");
    private final ObservableList<String> availableModels = FXCollections.observableArrayList("All");
    private final ObservableList<String> availableSamplers = FXCollections.observableArrayList("All");
    private final ObservableList<String> loras = FXCollections.observableArrayList("All");

    private final ObjectProperty<String> selectedModel = new SimpleObjectProperty<>("All");
    private final ObjectProperty<String> selectedSampler = new SimpleObjectProperty<>("All");
    private final ObjectProperty<String> selectedLora = new SimpleObjectProperty<>("All");

    private final ObservableList<String> collectionList = FXCollections.observableArrayList();

    // ------------------------------------------------------------------------
    // Constructor & Initialization
    // ------------------------------------------------------------------------
    @Inject
    public ImageBrowserViewModel(UserDataManager dataManager,
                                 MetadataService metaService,
                                 DatabaseService databaseService,
                                 ExecutorService executor) {
        this.dataManager = dataManager;
        this.metaService = metaService;
        this.databaseService = databaseService;
        this.executor = executor;

        // Establish Filter Listeners
        this.searchQuery.addListener((obs, old, val) -> updateFilter());
        this.selectedModel.addListener((obs, old, val) -> updateFilter());
        this.selectedSampler.addListener((obs, old, val) -> updateFilter());
        this.selectedLora.addListener((obs, old, val) -> updateFilter());

        refreshCollections();
        loadFilters();
    }

    // ------------------------------------------------------------------------
    // NEW METHODS (Fixing your Compilation Errors)
    // ------------------------------------------------------------------------

    /**
     Called by GalleryView to render stars efficiently.
     Uses a memory cache to avoid hitting the DB 60 times a second.
     */
    public int getRatingForFile(File file) {
        if (file == null) return 0;
        if (ratingCache.containsKey(file)) {
            return ratingCache.get(file);
        }
        // Fallback (slow path)
        int r = dataManager.getRating(file);
        ratingCache.put(file, r);
        return r;
    }

    /**
     Called by GalleryView to display "Model" or other data on the card.
     */
    public String getMetadataValue(File file, String key) {
        if (file == null || key == null) return "";
        Map<String, String> meta = currentFolderMetadata.get(file);
        return meta != null ? meta.getOrDefault(key, "") : "";
    }

    /**
     Required by ImageBrowserView for Drag-and-Drop operations.
     */
    public void addFilesToCollection(String name, List<File> files) {
        executor.submit(() -> files.forEach(f -> dataManager.addImageToCollection(name, f)));
    }

    /**
     Required by ImageBrowserView for the "Delete" confirmation dialog.
     */
    public int getSelectionCount() {
        return selectedImages.size();
    }

    // ------------------------------------------------------------------------
    // Loading & Indexing
    // ------------------------------------------------------------------------
    public void loadFolder(File folder) {
        if (folder == null || !folder.isDirectory()) return;
        dataManager.setLastFolder(folder);
        currentFolderMetadata.clear();
        ratingCache.clear(); // Clear cache when changing folders
        searchQuery.set("");

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
        task.setOnSucceeded(e -> {
            allFolderFiles.setAll(task.getValue());
            startIndexing(task.getValue());
        });
        executor.submit(task);
    }

    private void startIndexing(List<File> files) {
        Task<Void> indexTask = new Task<>() {
            @Override
            protected Void call() {
                for (File file : files) {
                    if (isCancelled()) break;

                    // 1. Pre-fetch Rating
                    ratingCache.put(file, dataManager.getRating(file));

                    // 2. Pre-fetch Metadata
                    if (dataManager.hasCachedMetadata(file)) {
                        currentFolderMetadata.put(file, dataManager.getCachedMetadata(file));
                    } else {
                        Map<String, String> meta = metaService.getExtractedData(file);
                        dataManager.cacheMetadata(file, meta);
                        currentFolderMetadata.put(file, meta);
                    }
                }
                Platform.runLater(() -> updateFilter());
                return null;
            }
        };
        executor.submit(indexTask);
    }

    // ------------------------------------------------------------------------
    // Filtering
    // ------------------------------------------------------------------------
    private void loadFilters() {
        executor.submit(() -> {
            List<String> rawModels = databaseService.getDistinctAttribute("Model");
            List<String> rawSamplers = databaseService.getDistinctAttribute("Sampler");
            List<String> rawLoras = databaseService.getDistinctAttribute("Loras");

            List<String> cleanModels = normalizeList(rawModels, false);
            List<String> cleanSamplers = normalizeList(rawSamplers, false);
            List<String> cleanLoras = normalizeList(rawLoras, true);

            Platform.runLater(() -> {
                availableModels.setAll(cleanModels);
                availableModels.add(0, "All");
                availableSamplers.setAll(cleanSamplers);
                availableSamplers.add(0, "All");
                loras.setAll(cleanLoras);
                loras.add(0, "All");
            });
        });
    }

    private void updateFilter() {
        String query = searchQuery.get();
        String modelFilter = selectedModel.get();
        String samplerFilter = selectedSampler.get();
        String loraFilter = selectedLora.get();

        filteredFiles.setPredicate(file -> {
            boolean matchName = true;
            if (query != null && !query.isBlank()) {
                String lowerQuery = query.toLowerCase();
                boolean nameHit = file.getName().toLowerCase().contains(lowerQuery);
                boolean metaHit = false;

                Map<String, String> meta = currentFolderMetadata.get(file);
                if (meta != null) {
                    if (containsIgnoreCase(meta.get("Prompt"), lowerQuery)) metaHit = true;
                    if (containsIgnoreCase(meta.get("Model"), lowerQuery)) metaHit = true;
                    if (containsIgnoreCase(meta.get("Seed"), lowerQuery)) metaHit = true;
                    if (containsIgnoreCase(meta.get("Loras"), lowerQuery)) metaHit = true;
                }
                matchName = nameHit || metaHit;
            }
            if (!matchName) return false;

            Map<String, String> meta = currentFolderMetadata.get(file);
            if (!isMatch(meta != null ? meta.get("Model") : null, modelFilter)) return false;
            if (!isMatch(meta != null ? meta.get("Sampler") : null, samplerFilter)) return false;
            if (!isMatch(meta != null ? meta.get("Loras") : null, loraFilter)) return false;

            return true;
        });

        if (selectedImage.get() != null && !filteredFiles.contains(selectedImage.get())) {
            updateSelection(Collections.emptyList());
        }
    }

    private List<String> normalizeList(List<String> input, boolean isLora) {
        Set<String> unique = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String item : input) {
            if (item == null || item.isBlank()) continue;
            if (isLora) {
                String[] parts = item.split(",");
                for (String p : parts) {
                    String clean = cleanLoraName(p.trim());
                    if (!clean.isEmpty()) unique.add(clean);
                }
            } else {
                unique.add(item.trim());
            }
        }
        return new ArrayList<>(unique);
    }

    private String cleanLoraName(String raw) {
        if (raw.toLowerCase().startsWith("<lora:")) raw = raw.substring(6);
        if (raw.endsWith(">")) raw = raw.substring(0, raw.length() - 1);
        int lastColon = raw.lastIndexOf(':');
        if (lastColon > 0) {
            String suffix = raw.substring(lastColon + 1);
            if (suffix.matches("[\\d.]+")) raw = raw.substring(0, lastColon);
        }
        return raw.trim();
    }

    private boolean isMatch(String fileValue, String filterValue) {
        if (filterValue == null || filterValue.equals("All")) return true;
        if (fileValue == null) return false;
        return fileValue.toLowerCase().contains(filterValue.toLowerCase());
    }

    private boolean containsIgnoreCase(String source, String target) {
        return source != null && source.toLowerCase().contains(target);
    }

    // ------------------------------------------------------------------------
    // Selection & Updates
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

        // Cache update
        ratingCache.put(file, activeRating.get());

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
                if (Objects.equals(selectedImage.get(), file)) activeMetadata.set(meta);
            });
            executor.submit(metaTask);
        }
    }

    // ------------------------------------------------------------------------
    // User Actions
    // ------------------------------------------------------------------------
    public void setRating(int rating) {
        activeRating.set(rating);
        List<File> target = new ArrayList<>(selectedImages);
        executor.submit(() -> {
            target.forEach(f -> {
                dataManager.setRating(f, rating);
                ratingCache.put(f, rating); // Update Cache
            });
        });
    }

    public void toggleStar() {
        setRating(activeRating.get() > 0 ? 0 : 5);
    }

    public void deleteSelectedFiles() {
        if (selectedImages.isEmpty()) return;
        List<File> targets = new ArrayList<>(selectedImages);
        executor.submit(() -> {
            List<File> deleted = new ArrayList<>();
            for (File f : targets) if (dataManager.moveFileToTrash(f)) deleted.add(f);
            if (!deleted.isEmpty()) Platform.runLater(() -> {
                allFolderFiles.removeAll(deleted);
                updateSelection(Collections.emptyList());
            });
        });
    }

    // ------------------------------------------------------------------------
    // Getters & Setters
    // ------------------------------------------------------------------------
    public void search(String query) {
        searchQuery.set(query);
    }

    public void loadStarred() {
        allFolderFiles.setAll(dataManager.getStarredFilesList());
    }

    public File getLastFolder() {
        return dataManager.getLastFolder();
    }

    // Collections
    public void refreshCollections() {
        executor.submit(() -> {
            List<String> c = dataManager.getCollections();
            Platform.runLater(() -> collectionList.setAll(c));
        });
    }

    public void loadCollection(String name) {
        allFolderFiles.setAll(dataManager.getFilesFromCollection(name));
    }

    public void createNewCollection(String name) {
        dataManager.createCollection(name);
        refreshCollections();
    }

    public void deleteCollection(String name) {
        dataManager.deleteCollection(name);
        refreshCollections();
    }

    public void addSelectedToCollection(String name) {
        List<File> targets = new ArrayList<>(selectedImages);
        executor.submit(() -> targets.forEach(f -> dataManager.addImageToCollection(name, f)));
    }

    // Pins
    public void pinFolder(File f) {
        dataManager.addPinnedFolder(f);
    }

    // FIXED: Renamed from unpinFolder to match View
    public void removePinnedFolder(File f) {
        dataManager.removePinnedFolder(f);
    }

    public List<File> getPinnedFolders() {
        return dataManager.getPinnedFolders();
    }

    // Properties
    public IntegerProperty activeRatingProperty() {
        return activeRating;
    }

    public ObservableList<File> getFilteredFiles() {
        return filteredFiles;
    }

    public ObjectProperty<File> getSelectedImage() {
        return selectedImage;
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

    public ObservableList<String> getModels() {
        return availableModels;
    }

    public ObservableList<String> getSamplers() {
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

    public ObservableList<String> getLoras() {
        return loras;
    }

    public ObjectProperty<String> selectedLoraProperty() {
        return selectedLora;
    }
}