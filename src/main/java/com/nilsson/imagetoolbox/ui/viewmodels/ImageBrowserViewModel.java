package com.nilsson.imagetoolbox.ui.viewmodels;

import com.nilsson.imagetoolbox.data.ImageRepository;
import com.nilsson.imagetoolbox.data.UserDataManager;
import com.nilsson.imagetoolbox.service.IndexingService;
import com.nilsson.imagetoolbox.service.MetadataService;
import com.nilsson.imagetoolbox.ui.components.NotificationService;
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
 The primary ViewModel for the image browsing interface, acting as a high-level coordinator
 between specialized ViewModels and the underlying data layer.
 </p>

 <h3>Key Responsibilities:</h3>
 <ul>
 <li><b>Coordination:</b> Orchestrates interactions between {@link SearchViewModel},
 {@link CollectionViewModel}, and various data services to ensure a unified state.</li>
 <li><b>State Management:</b> Maintains the master list of filtered files, the active
 selection set, and reactive properties for metadata and tags.</li>
 <li><b>Asynchronous I/O:</b> Offloads heavy operations (indexing, metadata extraction,
 file system searches) to an {@link ExecutorService} to keep the UI thread responsive.</li>
 <li><b>Pagination & Caching:</b> Implements optimized page-based loading for large
 directories and maintains transient caches for ratings and metadata.</li>
 </ul>
 * @author Nilsson

 @version 1.0 */
public class ImageBrowserViewModel implements ViewModel {

    private static final Logger logger = LoggerFactory.getLogger(ImageBrowserViewModel.class);
    private static final int PAGE_SIZE = 100;

    // ------------------------------------------------------------------------
    // Dependencies
    // ------------------------------------------------------------------------

    private final UserDataManager dataManager;
    private final MetadataService metaService;
    private final ImageRepository imageRepo;
    private final IndexingService indexingService;
    private final ExecutorService executor;
    private final NotificationService notificationService;

    // ------------------------------------------------------------------------
    // Child ViewModels
    // ------------------------------------------------------------------------

    private final SearchViewModel searchViewModel;
    private final CollectionViewModel collectionViewModel;

    // ------------------------------------------------------------------------
    // View State
    // ------------------------------------------------------------------------

    private final ObservableList<File> selectedImages = FXCollections.observableArrayList();
    private final ObjectProperty<File> selectedImage = new SimpleObjectProperty<>();
    private final ObservableList<File> filteredFiles = FXCollections.observableArrayList();
    private final List<File> allFilesCache = new ArrayList<>();

    // ------------------------------------------------------------------------
    // Transient Caches
    // ------------------------------------------------------------------------

    private final Map<File, Map<String, String>> currentFolderMetadata = new ConcurrentHashMap<>();
    private final Map<File, Integer> ratingCache = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------------
    // Reactive Properties
    // ------------------------------------------------------------------------

    private final ObjectProperty<Map<String, String>> activeMetadata = new SimpleObjectProperty<>(new HashMap<>());
    private final ObjectProperty<Set<String>> activeTags = new SimpleObjectProperty<>(new HashSet<>());
    private final IntegerProperty activeRating = new SimpleIntegerProperty(0);

    // ------------------------------------------------------------------------
    // Pagination & Task Management
    // ------------------------------------------------------------------------

    private Task<List<File>> activeTask = null;
    private int currentPage = 0;
    private boolean isLoadingPage = false;

    // ------------------------------------------------------------------------
    // Constructor & Initialization
    // ------------------------------------------------------------------------

    /**
     Injects dependencies and initializes the internal listener setup for
     cross-ViewModel coordination.
     */
    @Inject
    public ImageBrowserViewModel(UserDataManager dataManager,
                                 MetadataService metaService,
                                 ImageRepository imageRepo,
                                 IndexingService indexingService,
                                 ExecutorService executor,
                                 SearchViewModel searchViewModel,
                                 CollectionViewModel collectionViewModel,
                                 NotificationService notificationService) {
        this.dataManager = dataManager;
        this.metaService = metaService;
        this.imageRepo = imageRepo;
        this.indexingService = indexingService;
        this.executor = executor;
        this.searchViewModel = searchViewModel;
        this.collectionViewModel = collectionViewModel;
        this.notificationService = notificationService;

        setupListeners();
    }

    /**
     Binds child ViewModel properties to trigger local search and filtering logic.
     */
    private void setupListeners() {
        searchViewModel.searchQueryProperty().addListener((obs, old, val) -> triggerSearch());
        searchViewModel.selectedModelProperty().addListener((obs, old, val) -> triggerSearch());
        searchViewModel.selectedSamplerProperty().addListener((obs, old, val) -> triggerSearch());
        searchViewModel.selectedLoraProperty().addListener((obs, old, val) -> triggerSearch());
        searchViewModel.selectedStarProperty().addListener((obs, old, val) -> triggerSearch());
    }

    // ------------------------------------------------------------------------
    // Folder Loading & Indexing
    // ------------------------------------------------------------------------

    /**
     Cancels existing tasks and initiates a fresh load of a directory.

     @param folder The directory to display and index.
     */
    public void loadFolder(File folder) {
        if (folder == null || !folder.isDirectory()) return;

        indexingService.cancel();
        cancelActiveTasks();

        dataManager.setLastFolder(folder);
        currentFolderMetadata.clear();
        ratingCache.clear();
        searchViewModel.searchQueryProperty().set("");
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
            if (activeTask != task) return;

            List<File> files = task.getValue();
            allFilesCache.addAll(files);
            loadNextPage();

            indexingService.startIndexing(files, this::onBatchIndexed);
        });

        task.setOnFailed(e -> {
            logger.error("Failed to load folder", task.getException());
            notificationService.showError("Load Failed", "Could not load folder: " + folder.getName());
        });

        activeTask = task;
        executor.submit(task);
    }

    /**
     Appends the next segment of files to the filtered list for lazy-loading/infinite scroll.
     */
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

    /**
     Handles metadata results returned from the indexing service batches.
     */
    private void onBatchIndexed(IndexingService.BatchResult result) {
        currentFolderMetadata.putAll(result.metadata());
        ratingCache.putAll(result.ratings());
    }

    // ------------------------------------------------------------------------
    // Search & Filtering Logic
    // ------------------------------------------------------------------------

    /**
     Gathers current filter criteria and queries the repository asynchronously.
     */
    private void triggerSearch() {
        cancelActiveTasks();

        String query = searchViewModel.searchQueryProperty().get();
        String model = searchViewModel.selectedModelProperty().get();
        String sampler = searchViewModel.selectedSamplerProperty().get();
        String lora = searchViewModel.selectedLoraProperty().get();
        String star = searchViewModel.selectedStarProperty().get();

        currentPage = 0;
        filteredFiles.clear();
        allFilesCache.clear();

        if ((query == null || query.isBlank()) && searchViewModel.isAll(model) && searchViewModel.isAll(sampler) && searchViewModel.isAll(lora) && (star == null || star.isEmpty())) {
            File last = dataManager.getLastFolder();
            if (last != null) loadFolder(last);
            return;
        }

        Map<String, String> filters = new HashMap<>();
        if (!searchViewModel.isAll(model)) filters.put("Model", model);
        if (!searchViewModel.isAll(sampler)) filters.put("Sampler", sampler);
        if (!searchViewModel.isAll(lora)) filters.put("Loras", lora);

        if (star != null && !star.isEmpty()) {
            filters.put("Rating", star);
        }

        Task<List<File>> task = new Task<>() {
            @Override
            protected List<File> call() {
                List<String> paths = imageRepo.findPaths(query, filters, 100000);
                return paths.stream()
                        .map(File::new)
                        .filter(File::exists)
                        .map(File::getAbsoluteFile)
                        .distinct()
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

        task.setOnFailed(e -> {
            logger.error("Search failed", task.getException());
            notificationService.showError("Search Failed", "An error occurred while searching.");
        });

        activeTask = task;
        executor.submit(task);
    }

    // ------------------------------------------------------------------------
    // Selection & Sidebar Management
    // ------------------------------------------------------------------------

    /**
     Updates the local list of selected images and refreshes the metadata for the lead item.

     @param selection The list of files currently selected in the UI.
     */
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

    /**
     Refreshes ratings, tags, and metadata for a specific file.
     */
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

    /**
     Forces a refresh of the tag set for the currently selected image.
     */
    public void reloadTags() {
        File file = selectedImage.get();
        if (file != null) {
            Task<Set<String>> task = new Task<>() {
                @Override
                protected Set<String> call() {
                    return dataManager.getTags(file);
                }
            };
            task.setOnSucceeded(e -> {
                if (Objects.equals(selectedImage.get(), file)) {
                    activeTags.set(task.getValue());
                }
            });
            executor.submit(task);
        }
    }

    /**
     Persists a new tag to the data manager for the active image.
     */
    public void addTag(String tag) {
        File file = selectedImage.get();
        if (file != null && tag != null && !tag.isBlank()) {
            executor.submit(() -> {
                dataManager.addTag(file, tag);
                Platform.runLater(this::reloadTags);
            });
        }
    }

    /**
     Removes a tag from the active image via the data manager.
     */
    public void removeTag(String tag) {
        File file = selectedImage.get();
        if (file != null && tag != null) {
            executor.submit(() -> {
                dataManager.removeTag(file, tag);
                Platform.runLater(this::reloadTags);
            });
        }
    }

    // ------------------------------------------------------------------------
    // File & Rating Operations
    // ------------------------------------------------------------------------

    /**
     Sets a star rating for all currently selected images.

     @param rating The rating value (0-5).
     */
    public void setRating(int rating) {
        activeRating.set(rating);
        List<File> target = new ArrayList<>(selectedImages);
        executor.submit(() -> target.forEach(f -> {
            dataManager.setRating(f, rating);
            ratingCache.put(f, rating);
        }));
    }

    /**
     Toggles between no rating and a 5-star rating for the current selection.
     */
    public void toggleStar() {
        setRating(activeRating.get() > 0 ? 0 : 5);
    }

    /**
     Triggers a move-to-trash operation for selected files.
     */
    public void deleteSelectedFiles() {
        if (selectedImages.isEmpty()) return;
        List<File> targets = new ArrayList<>(selectedImages);
        executor.submit(() -> {
            List<File> deleted = targets.stream().filter(dataManager::moveFileToTrash).collect(Collectors.toList());
            if (!deleted.isEmpty()) {
                Platform.runLater(() -> {
                    allFilesCache.removeAll(deleted);
                    filteredFiles.removeAll(deleted);
                    updateSelection(Collections.emptyList());
                    notificationService.showInfo("Files Deleted", deleted.size() + " files moved to trash.");
                });
            } else {
                notificationService.showWarning("Delete Failed", "Could not move files to trash.");
            }
        });
    }

    // ------------------------------------------------------------------------
    // Collection & Folder Pinning
    // ------------------------------------------------------------------------

    public void refreshCollections() {
        collectionViewModel.refreshCollections();
    }

    /**
     Loads files belonging to a specific collection into the main view.
     */
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
        collectionViewModel.createNewCollection(n);
        notificationService.showInfo("Collection Created", "Created collection: " + n);
    }

    public void deleteCollection(String n) {
        collectionViewModel.deleteCollection(n);
        notificationService.showInfo("Collection Deleted", "Deleted collection: " + n);
    }

    public void addSelectedToCollection(String n) {
        collectionViewModel.addFilesToCollection(n, selectedImages);
        notificationService.showInfo("Added to Collection", "Added " + selectedImages.size() + " files to " + n);
    }

    public void addFilesToCollection(String name, List<File> files) {
        collectionViewModel.addFilesToCollection(name, files);
        notificationService.showInfo("Added to Collection", "Added " + files.size() + " files to " + name);
    }

    public void pinFolder(File f) {
        dataManager.addPinnedFolder(f);
        notificationService.showInfo("Folder Pinned", f.getName() + " pinned to sidebar.");
    }

    public void removePinnedFolder(File f) {
        dataManager.removePinnedFolder(f);
        notificationService.showInfo("Folder Unpinned", f.getName() + " removed from sidebar.");
    }

    public List<File> getPinnedFolders() {
        return dataManager.getPinnedFolders();
    }

    // ------------------------------------------------------------------------
    // Helper Logic & Utilities
    // ------------------------------------------------------------------------

    public int getRatingForFile(File file) {
        if (file == null) return 0;
        return ratingCache.getOrDefault(file, 0);
    }

    public String getMetadataValue(File file, String key) {
        if (file == null || key == null) return "";
        Map<String, String> meta = currentFolderMetadata.get(file);
        return meta != null ? meta.getOrDefault(key, "") : "";
    }

    private void cancelActiveTasks() {
        if (activeTask != null && !activeTask.isDone()) {
            activeTask.cancel(true);
        }
    }

    /**
     Externally updates the search query.
     */
    public void search(String query) {
        searchViewModel.searchQueryProperty().set(query);
    }

    /**
     Loads the global starred files list into the viewer.
     */
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

    // ------------------------------------------------------------------------
    // JavaFX Properties & Accessors
    // ------------------------------------------------------------------------

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
        return searchViewModel.searchQueryProperty();
    }

    public ObservableList<String> getModels() {
        return searchViewModel.getModels();
    }

    public ObservableList<String> getSamplers() {
        return searchViewModel.getSamplers();
    }

    public ObjectProperty<String> selectedModelProperty() {
        return searchViewModel.selectedModelProperty();
    }

    public ObjectProperty<String> selectedSamplerProperty() {
        return searchViewModel.selectedSamplerProperty();
    }

    public ObservableList<String> getCollectionList() {
        return collectionViewModel.getCollectionList();
    }

    public ObservableList<String> getLoras() {
        return searchViewModel.getLoras();
    }

    public ObjectProperty<String> selectedLoraProperty() {
        return searchViewModel.selectedLoraProperty();
    }

    public int getSelectionCount() {
        return selectedImages.size();
    }

    public ObservableList<String> getStars() {
        return searchViewModel.getStars();
    }

    public ObjectProperty<String> selectedStarProperty() {
        return searchViewModel.selectedStarProperty();
    }
}