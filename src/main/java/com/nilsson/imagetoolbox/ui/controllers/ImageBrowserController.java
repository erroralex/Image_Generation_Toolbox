package com.nilsson.imagetoolbox.ui.controllers;

import com.nilsson.imagetoolbox.data.UserDataManager;
import com.nilsson.imagetoolbox.service.MetadataService;
import com.nilsson.imagetoolbox.ui.views.ImageBrowserView;
import javafx.concurrent.Task;

import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ImageBrowserController implements ImageBrowserView.ViewListener {

    private final UserDataManager dataManager;
    private final MetadataService metaService;
    private final ImageBrowserView view;
    private final ExecutorService executor;

    /**
     * @param dataManager Shared persistence manager
     * @param metaService Service for parsing image metadata
     * @param view        The view instance this controller manages
     * @param executor    Shared application thread pool for background tasks
     */
    public ImageBrowserController(UserDataManager dataManager,
                                  MetadataService metaService,
                                  ImageBrowserView view,
                                  ExecutorService executor) {
        this.dataManager = dataManager;
        this.metaService = metaService;
        this.view = view;
        this.executor = executor;
    }

    public void initializeSidebar() {
        view.refreshSidebar(dataManager.getPinnedFolders());
    }

    // ==================================================================================
    // NAVIGATION & SELECTION
    // ==================================================================================

    @Override
    public void onFolderSelected(File folder) {
        if (folder == null || !folder.isDirectory()) return;

        dataManager.setLastFolder(folder);

        Task<List<File>> loadTask = new Task<>() {
            @Override
            protected List<File> call() {
                // Filter for images
                File[] files = folder.listFiles((dir, name) -> {
                    String low = name.toLowerCase();
                    return low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".webp");
                });
                if (files == null) return new ArrayList<>();
                // Sort by Newest First
                return Arrays.stream(files)
                        .sorted((f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()))
                        .collect(Collectors.toList());
            }
        };

        loadTask.setOnSucceeded(e -> {
            List<File> loadedFiles = loadTask.getValue();
            view.displayFiles(loadedFiles);
            // Trigger indexing in background using the same shared executor
            startIndexing(loadedFiles);
        });

        loadTask.setOnFailed(e -> view.displayFiles(new ArrayList<>()));

        // Execute on shared pool
        executor.submit(loadTask);
    }

    @Override
    public void onImageSelected(File file) {
        if (file == null) {
            view.updateSidebarMetadata(null, null, null, false);
            return;
        }

        // Fetch Data: Metadata (Async), Tags (Sync), Stars (Sync)
        boolean isStarred = dataManager.isStarred(file);
        Set<String> tags = dataManager.getTags(file);

        // 1. Check Cache for Metadata
        if (dataManager.hasCachedMetadata(file)) {
            // Instant load
            Map<String, String> meta = dataManager.getCachedMetadata(file);
            view.updateSidebarMetadata(file, meta, tags, isStarred);
        } else {
            // 2. Load Async if not cached
            // Show shell first (name, tags, star) while loading details
            view.updateSidebarMetadata(file, new HashMap<>(), tags, isStarred);

            Task<Map<String, String>> metaTask = new Task<>() {
                @Override protected Map<String, String> call() {
                    return metaService.getExtractedData(file);
                }
            };
            metaTask.setOnSucceeded(e -> {
                Map<String, String> meta = metaTask.getValue();
                dataManager.cacheMetadata(file, meta);
                // Push update to view
                view.updateSidebarMetadata(file, meta, tags, isStarred);
            });

            // Execute on shared pool
            executor.submit(metaTask);
        }
    }

    // ==================================================================================
    // ACTIONS (SIDEBAR / NAV)
    // ==================================================================================

    @Override
    public void onToggleStar(File file) {
        if (file == null) return;
        if (dataManager.isStarred(file)) dataManager.removeStar(file);
        else dataManager.addStar(file);

        refreshSingleFileState(file);
    }

    @Override
    public void onAddTag(File file, String tag) {
        if (file != null && tag != null && !tag.isEmpty()) {
            dataManager.addTag(file, tag);
            refreshSingleFileState(file);
        }
    }

    @Override
    public void onRemoveTag(File file, String tag) {
        if (file != null && tag != null) {
            dataManager.removeTag(file, tag);
            refreshSingleFileState(file);
        }
    }

    @Override
    public void onOpenRaw(File file) {
        if (file == null) return;
        // Raw metadata might be large, but usually instant enough to run on UI thread
        // if file IO is fast. However, safer to offload if reading large headers.
        // For simplicity and responsiveness, we run sync unless blocking issues arise.
        String raw = metaService.getRawMetadata(file);
        view.showRawMetadataPopup(raw);
    }

    @Override
    public void onSearch(String query) {
        // Search is currently synchronous in DataManager (in-memory map).
        // If DataManager moves to DB, this should wrap in a Task.
        List<File> results = dataManager.findFilesByTag(query);
        view.displayFiles(results);
    }

    @Override
    public void onStarredRequested() {
        view.displayFiles(dataManager.getStarredFilesList());
    }

    @Override
    public void onPinFolder(File folder) {
        dataManager.addPinnedFolder(folder);
        view.refreshSidebar(dataManager.getPinnedFolders());
    }

    @Override
    public void onUnpinFolder(File folder) {
        dataManager.removePinnedFolder(folder);
        view.refreshSidebar(dataManager.getPinnedFolders());
    }

    // ==================================================================================
    // HELPERS
    // ==================================================================================

    private void refreshSingleFileState(File file) {
        boolean isStarred = dataManager.isStarred(file);
        Set<String> tags = dataManager.getTags(file);
        Map<String, String> meta = dataManager.getCachedMetadata(file);
        view.updateSidebarMetadata(file, meta, tags, isStarred);
    }

    private void startIndexing(List<File> files) {
        if (files == null || files.isEmpty()) return;

        // Submit indexing task to shared pool
        executor.submit(() -> {
            for (File f : files) {
                // Skip if already cached to save CPU
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
            if (model != null && !model.isEmpty()) dataManager.addTag(file, "Model: " + model);
        }
        if (meta.containsKey("Loras")) {
            String loras = meta.get("Loras");
            if (loras != null && !loras.isEmpty()) {
                Pattern p = Pattern.compile("<lora:([^:>]+)");
                Matcher m = p.matcher(loras);
                while (m.find()) dataManager.addTag(file, "Lora: " + m.group(1));
            }
        }
    }
}