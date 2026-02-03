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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 <h2>ImageBrowserViewModel</h2>
 */
public class ImageBrowserViewModel implements ViewModel {

    private static final Logger logger = LoggerFactory.getLogger(ImageBrowserViewModel.class);

    // ------------------------------------------------------------------------
    // Dependencies
    // ------------------------------------------------------------------------
    private final UserDataManager dataManager;
    private final MetadataService metaService;
    private final DatabaseService databaseService;
    private final ExecutorService executor;

    // ------------------------------------------------------------------------
    // View State
    // ------------------------------------------------------------------------
    private final ObservableList<File> selectedImages = FXCollections.observableArrayList();
    private final ObjectProperty<File> selectedImage = new SimpleObjectProperty<>();

    // Filtering & Indexing
    private final ObservableList<File> allFolderFiles = FXCollections.observableArrayList();
    private final FilteredList<File> filteredFiles = new FilteredList<>(allFolderFiles, p -> true);

    // CACHES
    private final Map<File, Map<String, String>> currentFolderMetadata = new ConcurrentHashMap<>();
    private final Map<File, Integer> ratingCache = new ConcurrentHashMap<>();

    // Sidebar Properties
    private final ObjectProperty<Map<String, String>> activeMetadata = new SimpleObjectProperty<>(new HashMap<>());
    private final ObjectProperty<Set<String>> activeTags = new SimpleObjectProperty<>(new HashSet<>());
    private final IntegerProperty activeRating = new SimpleIntegerProperty(0);

    // Filter UI Properties
    private final StringProperty searchQuery = new SimpleStringProperty("");

    // Lists for dropdowns
    private final ObservableList<String> availableModels = FXCollections.observableArrayList();
    private final ObservableList<String> availableSamplers = FXCollections.observableArrayList();
    private final ObservableList<String> loras = FXCollections.observableArrayList();

    // Default to NULL so checkboxes start "unchecked"
    private final ObjectProperty<String> selectedModel = new SimpleObjectProperty<>(null);
    private final ObjectProperty<String> selectedSampler = new SimpleObjectProperty<>(null);
    private final ObjectProperty<String> selectedLora = new SimpleObjectProperty<>(null);

    private final ObservableList<String> collectionList = FXCollections.observableArrayList();

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

    public int getRatingForFile(File file) {
        if (file == null) return 0;
        if (ratingCache.containsKey(file)) {
            return ratingCache.get(file);
        }
        int r = dataManager.getRating(file);
        ratingCache.put(file, r);
        return r;
    }

    public String getMetadataValue(File file, String key) {
        if (file == null || key == null) return "";
        Map<String, String> meta = currentFolderMetadata.get(file);
        return meta != null ? meta.getOrDefault(key, "") : "";
    }

    public void addFilesToCollection(String name, List<File> files) {
        executor.submit(() -> files.forEach(f -> dataManager.addImageToCollection(name, f)));
    }

    public int getSelectionCount() {
        return selectedImages.size();
    }

    public void loadFolder(File folder) {
        if (folder == null || !folder.isDirectory()) return;
        dataManager.setLastFolder(folder);
        currentFolderMetadata.clear();
        ratingCache.clear();
        searchQuery.set("");

        logger.info("Loading folder: {}", folder.getAbsolutePath());

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

                    ratingCache.put(file, dataManager.getRating(file));

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

    private void loadFilters() {
        executor.submit(() -> {
            try {
                List<String> rawModels = databaseService.getDistinctAttribute("Model");
                List<String> rawSamplers = databaseService.getDistinctAttribute("Sampler");
                List<String> rawLoras = databaseService.getDistinctAttribute("Loras");

                List<String> cleanModels = normalizeList(rawModels, false);
                List<String> cleanSamplers = normalizeList(rawSamplers, false);
                List<String> cleanLoras = normalizeList(rawLoras, true);

                Platform.runLater(() -> {
                    // Just set the clean lists. No need to add "All" manually if we use NULL for clear.
                    // But users might want a way to click "All" to clear selection if UI doesn't allow deselection.
                    // Standard JavaFX ComboBox doesn't deselect easily.
                    // So we add "All" as an option that resets the filter.

                    availableModels.setAll(cleanModels);
                    availableModels.add(0, "All");

                    availableSamplers.setAll(cleanSamplers);
                    availableSamplers.add(0, "All");

                    loras.setAll(cleanLoras);
                    loras.add(0, "All");
                });
            } catch (Exception e) {
                logger.error("Failed to load filters", e);
            }
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
        if (input == null) return new ArrayList<>();

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
        // Treat NULL or "All" as valid match for everything
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

    public void setRating(int rating) {
        activeRating.set(rating);
        List<File> target = new ArrayList<>(selectedImages);
        executor.submit(() -> {
            target.forEach(f -> {
                dataManager.setRating(f, rating);
                ratingCache.put(f, rating);
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

    public void search(String query) {
        searchQuery.set(query);
    }

    public void loadStarred() {
        allFolderFiles.setAll(dataManager.getStarredFilesList());
    }

    public File getLastFolder() {
        return dataManager.getLastFolder();
    }

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

    public void pinFolder(File f) {
        dataManager.addPinnedFolder(f);
    }

    public void removePinnedFolder(File f) {
        dataManager.removePinnedFolder(f);
    }

    public List<File> getPinnedFolders() {
        return dataManager.getPinnedFolders();
    }

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