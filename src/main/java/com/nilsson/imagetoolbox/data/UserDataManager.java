package com.nilsson.imagetoolbox.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages all persistent user data including starred images, tags, settings, custom lists,
 * and pinned folders. Data is persisted to a JSON file using Jackson.
 * <p>
 * This class is thread-safe; all data modification methods are synchronized to prevent
 * race conditions during concurrent UI and background task access.
 */
public class UserDataManager {

    private static final File DATA_FILE = new File("data/userdata.json");
    private static final UserDataManager INSTANCE = new UserDataManager();
    private final ObjectMapper mapper;
    private PersistentData data;

    private UserDataManager() {
        mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        load();
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
                save();
            }
        }
    }

    public synchronized void removePinnedFolder(File folder) {
        if (folder != null) {
            data.pinnedFolders.remove(folder.getAbsolutePath());
            save();
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
            save();
        }
    }

    public synchronized void removeStar(File file) {
        if (file != null && data.stars.remove(file.getAbsolutePath())) {
            save();
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
        save();
    }

    public synchronized void removeTag(File file, String tag) {
        if (file == null || tag == null) return;
        Set<String> tags = data.fileTags.get(file.getAbsolutePath());
        if (tags != null && tags.remove(tag)) {
            if (tags.isEmpty()) data.fileTags.remove(file.getAbsolutePath());
            save();
        }
    }

    public synchronized Set<String> getTags(File file) {
        return file == null ? Collections.emptySet() :
                new HashSet<>(data.fileTags.getOrDefault(file.getAbsolutePath(), Collections.emptySet()));
    }

    public synchronized List<File> findFilesByTag(String query) {
        if (query == null || query.isBlank()) return new ArrayList<>();
        String lowerQuery = query.toLowerCase();

        return data.fileTags.entrySet().stream()
                .filter(e -> e.getValue().stream().anyMatch(t -> t.toLowerCase().contains(lowerQuery)))
                .map(e -> new File(e.getKey()))
                .filter(File::exists)
                .collect(Collectors.toList());
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
            save();
        }
    }

    public synchronized void deleteList(String name) {
        if (data.customLists.remove(name) != null) {
            save();
        }
    }

    public synchronized void addFileToList(String listName, File file) {
        List<String> list = data.customLists.get(listName);
        if (list != null && !list.contains(file.getAbsolutePath())) {
            list.add(file.getAbsolutePath());
            save();
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

    public synchronized String getSetting(String key, String defaultValue) {
        return data.settings.getOrDefault(key, defaultValue);
    }

    public synchronized void setSetting(String key, String value) {
        data.settings.put(key, value);
        save();
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
            save();
        }
    }

    // ==================================================================================
    // METADATA CACHE
    // ==================================================================================

    public synchronized void cacheMetadata(File file, Map<String, String> meta) {
        if (file != null && meta != null && meta.containsKey("Prompt")) {
            data.metadataCache.put(file.getAbsolutePath(), meta);
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

    private synchronized void save() {
        try {
            if (!DATA_FILE.getParentFile().exists()) {
                DATA_FILE.getParentFile().mkdirs();
            }
            mapper.writeValue(DATA_FILE, data);
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

    /**
     * Inner POJO for JSON mapping.
     * All fields stores paths as Strings to avoid serialization issues with File objects.
     */
    private static class PersistentData {
        public Set<String> stars = new HashSet<>();
        public Map<String, String> settings = new HashMap<>();
        public Map<String, List<String>> customLists = new HashMap<>();
        public Map<String, Set<String>> fileTags = new ConcurrentHashMap<>();
        public Map<String, Map<String, String>> metadataCache = new HashMap<>();
        public List<String> pinnedFolders = new ArrayList<>();
    }
}