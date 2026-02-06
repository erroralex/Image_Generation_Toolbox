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
 <h3>Key Responsibilities:</h3>
 <ul>
 <li><b>State Management:</b> Maintains observable properties for data binding with the View.</li>
 <li><b>Asynchronous I/O:</b> Delegates file system and database operations to background threads.</li>
 <li><b>Search Coordination:</b> Aggregates user search queries and metadata filters.</li>
 <li><b>Caching:</b> Manages transient metadata caches to improve UI responsiveness.</li>
 </ul>
 */
public class ImageBrowserViewModel implements ViewModel {

    private static final Logger logger = LoggerFactory.getLogger(ImageBrowserViewModel.class);
    private static final int PAGE_SIZE = 100;

    // --- Dependencies ---
    private final UserDataManager dataManager;
    private final MetadataService metaService;
    private final ImageRepository imageRepo;
    private final IndexingService indexingService;
    private final ExecutorService executor;

    // --- View State ---
    private final ObservableList<File> selectedImages = FXCollections.observableArrayList();
    private final ObjectProperty<File> selectedImage = new SimpleObjectProperty<>();
    private final ObservableList<File> filteredFiles = FXCollections.observableArrayList();
    private final List<File> allFilesCache = new ArrayList<>(); // Cache for current folder/search

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
    private final ObservableList<String> stars = FXCollections.observableArrayList("1", "2", "3", "4", "5");
    private final ObjectProperty<String> selectedStar = new SimpleObjectProperty<>();

    // Unified task to prevent race conditions between folder loading and searching
    private Task<List<File>> activeTask = null;
    private int currentPage = 0;
    private boolean isLoadingPage = false;

    // --- Constructor & Initialization ---

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
        selectedStar.addListener((obs, old, val) -> triggerSearch());
    }

    // --- Folder Loading & Indexing ---

    public void loadFolder(File folder) {
        if (folder == null || !folder.isDirectory()) return;

        indexingService.cancel();
        cancelActiveTasks(); // CRITICAL: Cancel any running search or previous load to prevent duplicates

        dataManager.setLastFolder(folder);
        currentFolderMetadata.clear();
        ratingCache.clear();
        searchQuery.set("");
        allFilesCache.clear();
        filteredFiles.clear();
        currentPage = 0;

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
            if (activeTask != task) return; // Ignore if a new task started

            List<File> files = task.getValue();
            allFilesCache.addAll(files);
            loadNextPage(); // Load initial page

            indexingService.startIndexing(files, this::onBatchIndexed);
        });

        activeTask = task;
        executor.submit(task);
    }

    public void loadNextPage() {
        if (isLoadingPage || allFilesCache.isEmpty()) return;

        int start = currentPage * PAGE_SIZE;
        if (start >= allFilesCache.size()) return;

        isLoadingPage = true;
        int end = Math.min(start + PAGE_SIZE, allFilesCache.size());
        List<File> page = new ArrayList<>(allFilesCache.subList(start, end));

        Platform.runLater(() -> {
            filteredFiles.addAll(page);
            currentPage++;
            isLoadingPage = false;
        });
    }

    private void onBatchIndexed(IndexingService.BatchResult result) {
        currentFolderMetadata.putAll(result.metadata);
        ratingCache.putAll(result.ratings);
    }

    // --- Search & Filtering Logic ---

    private void triggerSearch() {
        cancelActiveTasks(); // Cancel any running folder load or previous search

        String query = searchQuery.get();
        String model = selectedModel.get();
        String sampler = selectedSampler.get();
        String lora = selectedLora.get();
        String star = selectedStar.get();

        // Reset pagination state
        currentPage = 0;
        filteredFiles.clear();
        allFilesCache.clear();

        if ((query == null || query.isBlank()) && isAll(model) && isAll(sampler) && isAll(lora) && (star == null || star.isEmpty())) {
            // If no filters, reload current folder from disk
            File last = dataManager.getLastFolder();
            if (last != null) loadFolder(last);
            return;
        }

        Map<String, String> filters = new HashMap<>();
        if (!isAll(model)) filters.put("Model", model);
        if (!isAll(sampler)) filters.put("Sampler", sampler);
        if (!isAll(lora)) filters.put("Loras", lora);
        if (star != null && !star.isEmpty()) filters.put("Rating", star);

        Task<List<File>> task = new Task<>() {
            @Override
            protected List<File> call() {
                List<String> paths = imageRepo.findPaths(query, filters, 100000);
                return paths.stream()
                        .map(File::new)
                        .filter(File::exists)
                        .map(File::getAbsoluteFile) // Ensure consistent path representation
                        .distinct()                 // Remove duplicates (e.g. case variants or mixed separators)
                        .collect(Collectors.toList());
            }
        };

        task.setOnSucceeded(e -> {
            if (activeTask != task) return;

            List<File> results = task.getValue();
            if (results != null) {
                allFilesCache.addAll(results);
                loadNextPage();
            }

            if (selectedImage.get() != null && !allFilesCache.contains(selectedImage.get())) {
                updateSelection(Collections.emptyList());
            }
        });

        activeTask = task;
        executor.submit(task);
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
                if (Objects.equals(selectedImage.get(), file)) {
                    activeMetadata.set(meta);
                }
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
                allFilesCache.removeAll(deleted);
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
        cancelActiveTasks();

        allFilesCache.clear();
        filteredFiles.clear();
        currentPage = 0;

        List<File> c = dataManager.getFilesFromCollection(name);
        allFilesCache.addAll(c);
        loadNextPage();
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

    // --- Helper Logic & Utility ---

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
        if (activeTask != null && !activeTask.isDone()) {
            activeTask.cancel(true);
        }
    }

    public void search(String query) {
        searchQuery.set(query);
    }

    public void loadStarred() {
        indexingService.cancel();
        cancelActiveTasks();

        allFilesCache.clear();
        filteredFiles.clear();
        currentPage = 0;

        List<File> starred = dataManager.getStarredFilesList();
        allFilesCache.addAll(starred);
        loadNextPage();
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

    public ObservableList<String> getStars() {
        return stars;
    }

    public ObjectProperty<String> selectedStarProperty() {
        return selectedStar;
    }
}