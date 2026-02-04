package com.nilsson.imagetoolbox.ui.viewmodels;

import com.nilsson.imagetoolbox.data.ImageRepository;
import com.nilsson.imagetoolbox.data.UserDataManager;
import com.nilsson.imagetoolbox.service.IndexingService;
import com.nilsson.imagetoolbox.service.MetadataService;
import de.saxsys.mvvmfx.ViewModel;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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
 <p>
 The primary ViewModel for the image browsing interface, responsible for managing the state
 of the UI and coordinating between data repositories and background services.
 </p>
 * <h3>Key Responsibilities:</h3>
 <ul>
 <li><b>State Management:</b> Maintains {@link ObservableList} and {@link Property} objects
 for UI data binding, including selected images, filtered results, and metadata.</li>
 <li><b>Asynchronous Processing:</b> Delegates heavy indexing tasks to {@link IndexingService}
 and manages local background tasks via an {@link ExecutorService}.</li>
 <li><b>Filtering & Search:</b> Provides SQL-backed search capabilities and manages
 distinct filter values for Models, Samplers, and Loras.</li>
 <li><b>Collection Management:</b> Interfaces with {@link UserDataManager} to handle
 user-defined collections, pinned folders, and file ratings.</li>
 </ul>
 */
public class ImageBrowserViewModel implements ViewModel {

    private static final Logger logger = LoggerFactory.getLogger(ImageBrowserViewModel.class);
    private static final int SEARCH_LIMIT = 5000;

    // --- Dependencies ---
    private final UserDataManager dataManager;
    private final MetadataService metaService;
    private final ImageRepository imageRepo;
    private final IndexingService indexingService;
    private final ExecutorService executor;

    // --- View State ---
    private final ObservableList<File> selectedImages = FXCollections.observableArrayList();
    private final ObjectProperty<File> selectedImage = new SimpleObjectProperty<>();
    private final ObservableList<File> allFolderFiles = FXCollections.observableArrayList();
    private final ObservableList<File> filteredFiles = FXCollections.observableArrayList();

    // --- Caches ---
    private final Map<File, Map<String, String>> currentFolderMetadata = new ConcurrentHashMap<>();
    private final Map<File, Integer> ratingCache = new ConcurrentHashMap<>();

    // --- Properties ---
    private final ObjectProperty<Map<String, String>> activeMetadata = new SimpleObjectProperty<>(new HashMap<>());
    private final ObjectProperty<Set<String>> activeTags = new SimpleObjectProperty<>(new HashSet<>());
    private final IntegerProperty activeRating = new SimpleIntegerProperty(0);

    // --- Filter Properties ---
    private final StringProperty searchQuery = new SimpleStringProperty("");
    private final ObservableList<String> availableModels = FXCollections.observableArrayList();
    private final ObservableList<String> availableSamplers = FXCollections.observableArrayList();
    private final ObservableList<String> loras = FXCollections.observableArrayList();
    private final ObjectProperty<String> selectedModel = new SimpleObjectProperty<>(null);
    private final ObjectProperty<String> selectedSampler = new SimpleObjectProperty<>(null);
    private final ObjectProperty<String> selectedLora = new SimpleObjectProperty<>(null);
    private final ObservableList<String> collectionList = FXCollections.observableArrayList();

    private Task<List<File>> currentSearchTask = null;

    // --- Initialization ---

    @Inject
    public ImageBrowserViewModel(UserDataManager dataManager,
                                 MetadataService metaService,
                                 ImageRepository imageRepo,
                                 IndexingService indexingService,
                                 ExecutorService executor) {
        this.dataManager = dataManager;
        this.metaService = metaService;
        this.imageRepo = imageRepo;
        this.indexingService = indexingService;
        this.executor = executor;

        setupListeners();
        refreshCollections();
        loadFilters();
    }

    private void setupListeners() {
        searchQuery.addListener((obs, old, val) -> triggerSearch());
        selectedModel.addListener((obs, old, val) -> triggerSearch());
        selectedSampler.addListener((obs, old, val) -> triggerSearch());
        selectedLora.addListener((obs, old, val) -> triggerSearch());
    }

    // --- Folder Loading & Indexing ---

    public void loadFolder(File folder) {
        if (folder == null || !folder.isDirectory()) return;

        indexingService.cancel();
        cancelActiveTasks();

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
            List<File> files = task.getValue();
            allFolderFiles.setAll(files);
            filteredFiles.setAll(files);

            indexingService.startIndexing(files, this::onBatchIndexed);
        });
        executor.submit(task);
    }

    private void onBatchIndexed(IndexingService.BatchResult result) {
        currentFolderMetadata.putAll(result.metadata);
        ratingCache.putAll(result.ratings);
    }

    // --- Search & Filtering Logic ---

    private void triggerSearch() {
        if (currentSearchTask != null && !currentSearchTask.isDone()) {
            currentSearchTask.cancel(true);
        }

        String query = searchQuery.get();
        String model = selectedModel.get();
        String sampler = selectedSampler.get();
        String lora = selectedLora.get();

        if ((query == null || query.isBlank()) && isAll(model) && isAll(sampler) && isAll(lora)) {
            filteredFiles.setAll(allFolderFiles);
            return;
        }

        Map<String, String> filters = new HashMap<>();
        if (!isAll(model)) filters.put("Model", model);
        if (!isAll(sampler)) filters.put("Sampler", sampler);
        if (!isAll(lora)) filters.put("Loras", lora);

        currentSearchTask = new Task<>() {
            @Override
            protected List<File> call() {
                List<String> paths = imageRepo.findPaths(query, filters, SEARCH_LIMIT);
                return paths.stream()
                        .map(File::new)
                        .filter(File::exists)
                        .collect(Collectors.toList());
            }
        };

        currentSearchTask.setOnSucceeded(e -> {
            filteredFiles.setAll(currentSearchTask.getValue());
            if (selectedImage.get() != null && !filteredFiles.contains(selectedImage.get())) {
                updateSelection(Collections.emptyList());
            }
        });
        executor.submit(currentSearchTask);
    }

    private void loadFilters() {
        executor.submit(() -> {
            try {
                List<String> rawModels = imageRepo.getDistinctValues("Model");
                List<String> rawSamplers = imageRepo.getDistinctValues("Sampler");
                List<String> rawLoras = imageRepo.getDistinctValues("Loras");

                Platform.runLater(() -> {
                    updateFilterList(availableModels, rawModels, false);
                    updateFilterList(availableSamplers, rawSamplers, false);
                    updateFilterList(loras, rawLoras, true);
                });
            } catch (Exception e) {
                logger.error("Failed to load filters", e);
            }
        });
    }

    private void updateFilterList(ObservableList<String> list, List<String> raw, boolean isLora) {
        Set<String> unique = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (raw != null) {
            for (String item : raw) {
                if (item == null || item.isBlank()) continue;
                if (isLora) {
                    for (String p : item.split(",")) {
                        String clean = cleanLoraName(p.trim());
                        if (!clean.isEmpty()) unique.add(clean);
                    }
                } else {
                    unique.add(item.trim());
                }
            }
        }
        list.setAll(new ArrayList<>(unique));
        list.add(0, "All");
    }

    // --- Selection & Sidebar Management ---

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

    // --- File & Rating Operations ---

    public void setRating(int rating) {
        activeRating.set(rating);
        List<File> target = new ArrayList<>(selectedImages);
        executor.submit(() -> target.forEach(f -> {
            dataManager.setRating(f, rating);
            ratingCache.put(f, rating);
        }));
    }

    public void toggleStar() {
        setRating(activeRating.get() > 0 ? 0 : 5);
    }

    public void deleteSelectedFiles() {
        if (selectedImages.isEmpty()) return;
        List<File> targets = new ArrayList<>(selectedImages);
        executor.submit(() -> {
            List<File> deleted = targets.stream().filter(dataManager::moveFileToTrash).collect(Collectors.toList());
            if (!deleted.isEmpty()) Platform.runLater(() -> {
                allFolderFiles.removeAll(deleted);
                filteredFiles.removeAll(deleted);
                updateSelection(Collections.emptyList());
            });
        });
    }

    // --- Collection & Folder Pinning ---

    public void refreshCollections() {
        executor.submit(() -> {
            List<String> c = dataManager.getCollections();
            Platform.runLater(() -> collectionList.setAll(c));
        });
    }

    public void loadCollection(String name) {
        indexingService.cancel();
        List<File> c = dataManager.getFilesFromCollection(name);
        allFolderFiles.setAll(c);
        filteredFiles.setAll(c);
    }

    public void createNewCollection(String n) {
        dataManager.createCollection(n);
        refreshCollections();
    }

    public void deleteCollection(String n) {
        dataManager.deleteCollection(n);
        refreshCollections();
    }

    public void addSelectedToCollection(String n) {
        List<File> t = new ArrayList<>(selectedImages);
        executor.submit(() -> t.forEach(f -> dataManager.addImageToCollection(n, f)));
    }

    public void addFilesToCollection(String name, List<File> files) {
        executor.submit(() -> files.forEach(f -> dataManager.addImageToCollection(name, f)));
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

    // --- Helpers & Utility ---

    public int getRatingForFile(File file) {
        if (file == null) return 0;
        return ratingCache.getOrDefault(file, 0);
    }

    public String getMetadataValue(File file, String key) {
        if (file == null || key == null) return "";
        Map<String, String> meta = currentFolderMetadata.get(file);
        return meta != null ? meta.getOrDefault(key, "") : "";
    }

    private boolean isAll(String val) {
        return val == null || "All".equals(val);
    }

    private String cleanLoraName(String raw) {
        if (raw.toLowerCase().startsWith("<lora:")) raw = raw.substring(6);
        if (raw.endsWith(">")) raw = raw.substring(0, raw.length() - 1);
        int lastColon = raw.lastIndexOf(':');
        if (lastColon > 0 && raw.substring(lastColon + 1).matches("[\\d.]+")) {
            raw = raw.substring(0, lastColon);
        }
        return raw.trim();
    }

    private void cancelActiveTasks() {
        if (currentSearchTask != null && !currentSearchTask.isDone()) currentSearchTask.cancel(true);
    }

    public void search(String query) {
        searchQuery.set(query);
    }

    public void loadStarred() {
        indexingService.cancel();
        List<File> starred = dataManager.getStarredFilesList();
        allFolderFiles.setAll(starred);
        filteredFiles.setAll(starred);
    }

    public File getLastFolder() {
        return dataManager.getLastFolder();
    }

    // --- JavaFX Properties & Accessors ---

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

    public int getSelectionCount() {
        return selectedImages.size();
    }
}