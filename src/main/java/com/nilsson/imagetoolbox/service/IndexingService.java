package com.nilsson.imagetoolbox.service;

import com.nilsson.imagetoolbox.data.ImageRepository;
import com.nilsson.imagetoolbox.data.UserDataManager;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 <h2>IndexingService</h2>
 <p>
 Service responsible for the background indexing and real-time monitoring of image folders.
 This service bridges the gap between the local file system and the application's data layer.
 </p>

 <h3>Core Responsibilities:</h3>
 <ul>
 <li><b>Batch Indexing:</b> Processes large sets of images in chunks to minimize DB overhead and prevent UI stutter.</li>
 <li><b>Data Synchronization:</b> Fetches known metadata from the database, extracts missing data via {@link MetadataService},
 and updates the {@link UserDataManager} cache.</li>
 <li><b>Real-time Monitoring:</b> Utilizes the Java {@link WatchService} to listen for OS-level file creation
 and deletion events, ensuring the library stays in sync with external changes.</li>
 </ul>

 <h3>Concurrency Model:</h3>
 <p>
 Heavy indexing runs on a low-priority background thread to keep the JavaFX Application Thread responsive.
 The Watcher runs on its own dedicated daemon thread to provide non-blocking file system updates.
 </p>
 */
public class IndexingService {

    private static final Logger logger = LoggerFactory.getLogger(IndexingService.class);
    private static final int BATCH_SIZE = 20;

    // --- Dependencies ---
    private final ImageRepository imageRepo;
    private final MetadataService metaService;
    private final UserDataManager dataManager;
    private final ExecutorService executor;

    // --- Task & Watcher State ---
    private Task<Void> currentTask;
    private WatchService watchService;
    private Thread watchThread;

    @Inject
    public IndexingService(ImageRepository imageRepo,
                           MetadataService metaService,
                           UserDataManager dataManager,
                           ExecutorService executor) {
        this.imageRepo = imageRepo;
        this.metaService = metaService;
        this.dataManager = dataManager;
        this.executor = executor;
    }

    // --- Lifecycle Management ---

    /**
     Cancels any currently running indexing task and stops the directory watcher.
     */
    public void cancel() {
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true);
        }
        stopWatching();
    }

    // --- Reconciliation Logic ---

    /**
     Synchronizes the DB with the File System.
     Refactored for "Lazy Reconciliation":
     1. Immediately scans the last active folder for responsiveness.
     2. Schedules a low-priority background task to check for "ghost" records globally.
     */
    public void reconcileLibrary() {
        File lastFolder = dataManager.getLastFolder();
        if (lastFolder != null && lastFolder.exists() && lastFolder.isDirectory()) {
            logger.info("[Reconcile] Fast-scanning last folder: {}", lastFolder.getName());
            indexFolder(lastFolder);
        }

        Task<Void> ghostCleanupTask = new Task<>() {
            @Override
            protected Void call() {
                Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
                logger.info("[Reconcile] Starting background ghost record cleanup...");
                long start = System.currentTimeMillis();
                AtomicInteger removedCount = new AtomicInteger(0);

                imageRepo.forEachFilePath(path -> {
                    if (isCancelled()) return;

                    File file = new File(path);
                    if (!file.exists()) {
                        imageRepo.deleteByPath(path);
                        removedCount.incrementAndGet();
                    }
                });

                if (removedCount.get() > 0) {
                    logger.info("[Reconcile] Background cleanup finished. Removed {} ghost records in {}ms",
                            removedCount.get(), (System.currentTimeMillis() - start));
                } else {
                    logger.debug("[Reconcile] Background cleanup finished. Library is consistent.");
                }
                return null;
            }
        };

        executor.submit(ghostCleanupTask);
    }

    /**
     Helper to list files in a folder and start indexing them.
     */
    public void indexFolder(File folder) {
        if (folder == null || !folder.isDirectory()) return;

        File[] files = folder.listFiles(this::isImageFile);
        if (files != null && files.length > 0) {
            startIndexing(Arrays.asList(files), null);
        }
    }

    // --- Batch Indexing ---

    /**
     Starts indexing the provided list of files in batches.

     @param files         The list of files to index.
     @param onBatchResult A callback executed on the background thread that receives
     the processed metadata and ratings for a batch.
     */
    public void startIndexing(List<File> files, Consumer<BatchResult> onBatchResult) {
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true);
        }

        if (files == null || files.isEmpty()) return;

        currentTask = new Task<>() {
            @Override
            protected Void call() {
                Thread.currentThread().setPriority(Thread.MIN_PRIORITY);

                int total = files.size();
                for (int i = 0; i < total; i += BATCH_SIZE) {
                    if (isCancelled()) break;

                    int end = Math.min(i + BATCH_SIZE, total);
                    List<File> batch = files.subList(i, end);

                    BatchResult result = processBatch(batch);

                    if (onBatchResult != null) {
                        onBatchResult.accept(result);
                    }

                    Thread.yield();
                }
                return null;
            }
        };
        executor.submit(currentTask);
    }

    // --- File System Watcher Logic ---

    /**
     Starts monitoring the specified directory for file creations and deletions.

     @param directory The directory to watch.
     @param listener  Callback to handle file system events.
     */
    public void startWatching(File directory, Consumer<FileChangeEvent> listener) {
        stopWatching();

        if (directory == null || !directory.exists() || !directory.isDirectory()) return;

        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            Path path = directory.toPath();
            path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);

            watchThread = new Thread(() -> watchLoop(path, listener));
            watchThread.setDaemon(true);
            watchThread.setName("Folder-Watcher-" + directory.getName());
            watchThread.start();

            logger.info("Started watching directory: {}", directory);

        } catch (IOException e) {
            logger.error("Failed to start WatchService for {}", directory, e);
        }
    }

    public void stopWatching() {
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
            watchService = null;
        }
    }

    private void watchLoop(Path monitoredPath, Consumer<FileChangeEvent> listener) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException x) {
                    break;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                    Path fileName = (Path) event.context();
                    File file = monitoredPath.resolve(fileName).toFile();

                    if (!isImageFile(file)) continue;

                    if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                        handleFileCreation(file);
                        if (listener != null) listener.accept(new FileChangeEvent(file, ChangeType.CREATED));
                    } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        handleFileDeletion(file);
                        if (listener != null) listener.accept(new FileChangeEvent(file, ChangeType.DELETED));
                    }
                }

                boolean valid = key.reset();
                if (!valid) break;
            }
        } catch (Exception e) {
            logger.debug("WatchService loop ended: {}", e.getMessage());
        }
    }

    // --- Internal Event Handling ---

    private void handleFileCreation(File file) {
        try {
            Map<String, String> meta = metaService.getExtractedData(file);
            saveToDatabase(file, meta);
            dataManager.cacheMetadata(file, meta);
            logger.debug("Indexed new file: {}", file.getName());
        } catch (Exception e) {
            logger.error("Error processing new file: {}", file.getName(), e);
        }
    }

    private void handleFileDeletion(File file) {
        try {
            imageRepo.deleteByPath(file.getAbsolutePath());
            logger.debug("Removed deleted file: {}", file.getName());
        } catch (Exception e) {
            logger.error("Error processing deleted file: {}", file.getName(), e);
        }
    }

    private boolean isImageFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp");
    }

    // --- Core Processing Logic ---

    private BatchResult processBatch(List<File> batch) {
        Map<File, Map<String, String>> metadataMap = new HashMap<>();
        Map<File, Integer> ratingMap = new HashMap<>();

        Map<String, Map<String, String>> dbMeta = imageRepo.batchGetMetadata(batch);

        for (File file : batch) {
            if (currentTask.isCancelled()) break;

            int rating = dataManager.getRating(file);
            ratingMap.put(file, rating);

            Map<String, String> meta;
            if (dbMeta.containsKey(file.getAbsolutePath())) {
                meta = dbMeta.get(file.getAbsolutePath());
            } else if (dataManager.hasCachedMetadata(file)) {
                meta = dataManager.getCachedMetadata(file);
            } else {
                meta = metaService.getExtractedData(file);
                saveToDatabase(file, meta);
            }

            dataManager.cacheMetadata(file, meta);
            metadataMap.put(file, meta);
        }

        return new BatchResult(metadataMap, ratingMap);
    }

    private void saveToDatabase(File file, Map<String, String> meta) {
        try {
            int id = imageRepo.getOrCreateId(file.getAbsolutePath(), null);
            imageRepo.saveMetadata(id, meta);
        } catch (Exception e) {
            logger.error("Failed to save metadata for {}", file.getName(), e);
        }
    }

    // --- Data Transfer Objects ---

    /**
     Container for results produced by a background indexing batch.
     */
    public static class BatchResult {
        public final Map<File, Map<String, String>> metadata;
        public final Map<File, Integer> ratings;

        public BatchResult(Map<File, Map<String, String>> metadata, Map<File, Integer> ratings) {
            this.metadata = metadata;
            this.ratings = ratings;
        }
    }

    public enum ChangeType {
        CREATED, DELETED
    }

    /**
     Event payload representing a file system change detected by the Watcher.
     */
    public static class FileChangeEvent {
        public final File file;
        public final ChangeType type;

        public FileChangeEvent(File file, ChangeType type) {
            this.file = file;
            this.type = type;
        }
    }
}