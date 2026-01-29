package com.nilsson.imagetoolbox.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Manages all persistent user data including starred images, tags, settings, custom lists,
 * and pinned folders. Data is persisted to a JSON file using Jackson.
 * <p>
 * This version uses a DEBOUNCED save mechanism to prevent UI freezes during high-frequency
 * updates (like tagging or auto-indexing).
 */
public class UserDataManager {

    private static final File DATA_FILE = new File("data/userdata.json");
    private static final UserDataManager INSTANCE = new UserDataManager();
    private final ObjectMapper mapper;
    private PersistentData data;

    // Persistence Debouncing
    private final ScheduledExecutorService saveScheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pendingSave;

    private UserDataManager() {
        mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        load();

        // Ensure save on exit to capture any pending writes
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (pendingSave != null && !pendingSave.isDone()) {
                saveImmediate();
            }
        }));
    }

    public static UserDataManager getInstance() {
        return INSTANCE;
    }

    // ==================================================================================
    // PINNED FOLDERS
    // ==================================================================================

    public synchronized List<File> getPinnedFolders() {
        return data.pinnedFolders.stream()
                .map(File::new)
                .filter(File::exists)
                .collect(Collectors.toList());
    }

    public synchronized void addPinnedFolder(File folder) {
        if (folder != null && folder.isDirectory()) {
            String path = folder.getAbsolutePath();
            if (!data.pinnedFolders.contains(path)) {
                data.pinnedFolders.add(path);
                scheduleSave();
            }
        }
    }

    public synchronized void removePinnedFolder(File folder) {
        if (folder != null) {
            data.pinnedFolders.remove(folder.getAbsolutePath());
            scheduleSave();
        }
    }

    public synchronized boolean isPinned(File folder) {
        return folder != null && data.pinnedFolders.contains(folder.getAbsolutePath());
    }

    // ==================================================================================
    // STARS / FAVORITES
    // ==================================================================================

    public synchronized void addStar(File file) {
        if (file != null && data.stars.add(file.getAbsolutePath())) {
            scheduleSave();
        }
    }

    public synchronized void removeStar(File file) {
        if (file != null && data.stars.remove(file.getAbsolutePath())) {
            scheduleSave();
        }
    }

    public synchronized boolean isStarred(File file) {
        return file != null && data.stars.contains(file.getAbsolutePath());
    }

    public synchronized void toggleStar(File file) {
        if (isStarred(file)) removeStar(file);
        else addStar(file);
    }

    public synchronized List<File> getStarredFilesList() {
        return data.stars.stream()
                .map(File::new)
                .filter(File::exists)
                .collect(Collectors.toList());
    }

    // ==================================================================================
    // TAGS
    // ==================================================================================

    public synchronized void addTag(File file, String tag) {
        if (file == null || tag == null || tag.isBlank()) return;
        data.fileTags.computeIfAbsent(file.getAbsolutePath(), k -> new HashSet<>()).add(tag.trim());
        scheduleSave();
    }

    public synchronized void removeTag(File file, String tag) {
        if (file == null || tag == null) return;
        Set<String> tags = data.fileTags.get(file.getAbsolutePath());
        if (tags != null && tags.remove(tag)) {
            if (tags.isEmpty()) data.fileTags.remove(file.getAbsolutePath());
            scheduleSave();
        }
    }

    public synchronized Set<String> getTags(File file) {
        return file == null ? Collections.emptySet() :
                new HashSet<>(data.fileTags.getOrDefault(file.getAbsolutePath(), Collections.emptySet()));
    }

    // ==================================================================================
    // SEARCH ENGINE (Expanded Scope)
    // ==================================================================================

    public synchronized List<File> findFiles(String query) {
        if (query == null || query.isBlank()) return new ArrayList<>();
        String lowerQuery = query.toLowerCase();

        Set<String> matchedPaths = new HashSet<>();

        // 1. Search Tags
        data.fileTags.forEach((path, tags) -> {
            if (tags.stream().anyMatch(t -> t.toLowerCase().contains(lowerQuery))) {
                matchedPaths.add(path);
            }
        });

        // 2. Search Cached Metadata (Prompt, Model, etc.)
        data.metadataCache.forEach((path, meta) -> {
            boolean metaMatch = meta.values().stream()
                    .anyMatch(val -> val != null && val.toLowerCase().contains(lowerQuery));
            if (metaMatch) matchedPaths.add(path);
        });

        return matchedPaths.stream()
                .map(File::new)
                .filter(File::exists)
                .collect(Collectors.toList());
    }

    /**
     * Compatibility method for existing code.
     */
    public List<File> findFilesByTag(String query) {
        return findFiles(query);
    }

    // ==================================================================================
    // CUSTOM LISTS
    // ==================================================================================

    public synchronized Set<String> getCustomListNames() {
        return new HashSet<>(data.customLists.keySet());
    }

    public synchronized void createList(String name) {
        if (!data.customLists.containsKey(name)) {
            data.customLists.put(name, new ArrayList<>());
            scheduleSave();
        }
    }

    public synchronized void deleteList(String name) {
        if (data.customLists.remove(name) != null) {
            scheduleSave();
        }
    }

    public synchronized void addFileToList(String listName, File file) {
        List<String> list = data.customLists.get(listName);
        if (list != null && !list.contains(file.getAbsolutePath())) {
            list.add(file.getAbsolutePath());
            scheduleSave();
        }
    }

    public synchronized List<File> getFilesFromList(String listName) {
        List<String> paths = data.customLists.get(listName);
        if (paths == null) return new ArrayList<>();
        return paths.stream()
                .map(File::new)
                .filter(File::exists)
                .collect(Collectors.toList());
    }

    // ==================================================================================
    // APP SETTINGS
    // ==================================================================================

    /**
     * Retrieves a persistent setting string.
     * @param key The setting key
     * @param defaultValue Value to return if key is missing
     * @return The setting value or defaultValue
     */
    public synchronized String getSetting(String key, String defaultValue) {
        return data.settings.getOrDefault(key, defaultValue);
    }

    public synchronized void setSetting(String key, String value) {
        data.settings.put(key, value);
        scheduleSave();
    }

    public synchronized File getLastFolder() {
        String path = data.settings.get("last_folder");
        if (path != null) {
            File f = new File(path);
            if (f.exists() && f.isDirectory()) return f;
        }
        return null;
    }

    public synchronized void setLastFolder(File folder) {
        if (folder != null && folder.isDirectory()) {
            data.settings.put("last_folder", folder.getAbsolutePath());
            scheduleSave();
        }
    }

    // ==================================================================================
    // METADATA CACHE
    // ==================================================================================

    public synchronized boolean hasCachedMetadata(File file) {
        return file != null && data.metadataCache.containsKey(file.getAbsolutePath());
    }

    public synchronized void cacheMetadata(File file, Map<String, String> meta) {
        if (file != null && meta != null && !meta.isEmpty()) {
            if (!data.metadataCache.containsKey(file.getAbsolutePath())) {
                data.metadataCache.put(file.getAbsolutePath(), meta);
                scheduleSave();
            }
        }
    }

    public synchronized Map<String, String> getCachedMetadata(File file) {
        return file == null ? null : data.metadataCache.get(file.getAbsolutePath());
    }

    // ==================================================================================
    // PERSISTENCE LOGIC
    // ==================================================================================

    private void load() {
        if (DATA_FILE.exists()) {
            try {
                data = mapper.readValue(DATA_FILE, PersistentData.class);
            } catch (IOException e) {
                System.err.println("Failed to load user data: " + e.getMessage());
                data = new PersistentData();
            }
        } else {
            data = new PersistentData();
        }
        ensureDataIntegrity();
    }

    private synchronized void scheduleSave() {
        if (pendingSave != null && !pendingSave.isDone()) {
            pendingSave.cancel(false);
        }
        // Wait 3 seconds before writing to disk
        pendingSave = saveScheduler.schedule(this::saveImmediate, 3, TimeUnit.SECONDS);
    }

    private synchronized void saveImmediate() {
        try {
            if (!DATA_FILE.getParentFile().exists()) {
                DATA_FILE.getParentFile().mkdirs();
            }
            mapper.writeValue(DATA_FILE, data);
            System.out.println("User data saved to disk.");
        } catch (IOException e) {
            System.err.println("Failed to save user data: " + e.getMessage());
        }
    }

    private void ensureDataIntegrity() {
        if (data.stars == null) data.stars = new HashSet<>();
        if (data.settings == null) data.settings = new HashMap<>();
        if (data.customLists == null) data.customLists = new HashMap<>();
        if (data.fileTags == null) data.fileTags = new ConcurrentHashMap<>();
        if (data.metadataCache == null) data.metadataCache = new HashMap<>();
        if (data.pinnedFolders == null) data.pinnedFolders = new ArrayList<>();
    }

    private static class PersistentData {
        public Set<String> stars = new HashSet<>();
        public Map<String, String> settings = new HashMap<>();
        public Map<String, List<String>> customLists = new HashMap<>();
        public Map<String, Set<String>> fileTags = new ConcurrentHashMap<>();
        public Map<String, Map<String, String>> metadataCache = new HashMap<>();
        public List<String> pinnedFolders = new ArrayList<>();
    }
}