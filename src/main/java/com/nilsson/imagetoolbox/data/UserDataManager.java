package com.nilsson.imagetoolbox.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class UserDataManager {

    private static final String DATA_FILE = "data/userdata.json";
    private static UserDataManager INSTANCE;

    private final ObjectMapper mapper;
    private PersistentData data;

    public static synchronized UserDataManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new UserDataManager();
        }
        return INSTANCE;
    }

    private UserDataManager() {
        mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        load();
    }

    // --- STARS (Thread Safe) ---

    public synchronized void addStar(File file) { addStar(file.getAbsolutePath()); }
    public synchronized void addStar(String path) {
        if (data.stars.add(path)) save();
    }

    public synchronized void removeStar(File file) { removeStar(file.getAbsolutePath()); }
    public synchronized void removeStar(String path) {
        if (data.stars.remove(path)) save();
    }

    public synchronized boolean isStarred(File file) { return isStarred(file.getAbsolutePath()); }
    public synchronized boolean isStarred(String path) {
        return data.stars.contains(path);
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

    // --- CUSTOM LISTS (Thread Safe) ---

    public synchronized Set<String> getCustomListNames() { return new HashSet<>(data.customLists.keySet()); }

    public synchronized void createList(String name) {
        if (!data.customLists.containsKey(name)) {
            data.customLists.put(name, new ArrayList<>());
            save();
        }
    }

    public synchronized void deleteList(String name) {
        if (data.customLists.remove(name) != null) save();
    }

    public synchronized void addFileToList(String listName, File file) {
        List<String> list = data.customLists.computeIfAbsent(listName, k -> new ArrayList<>());
        if (!list.contains(file.getAbsolutePath())) {
            list.add(file.getAbsolutePath());
            save();
        }
    }

    public synchronized List<File> getFilesFromList(String listName) {
        List<String> paths = data.customLists.get(listName);
        if (paths == null) return new ArrayList<>();
        return paths.stream().map(File::new).filter(File::exists).collect(Collectors.toList());
    }

    // --- TAGS (Thread Safe) ---

    public synchronized void addTag(File file, String tag) {
        data.fileTags.computeIfAbsent(file.getAbsolutePath(), k -> new HashSet<>()).add(tag);
        save();
    }

    public synchronized void removeTag(File file, String tag) {
        Set<String> tags = data.fileTags.get(file.getAbsolutePath());
        if (tags != null && tags.remove(tag)) {
            save();
        }
    }

    public synchronized Set<String> getTags(File file) {
        return data.fileTags.getOrDefault(file.getAbsolutePath(), Collections.emptySet());
    }

    public synchronized List<File> findFilesByTag(String query) {
        if (query == null || query.isBlank()) return new ArrayList<>();
        String lowerQuery = query.toLowerCase();
        List<File> results = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : data.fileTags.entrySet()) {
            boolean match = entry.getValue().stream().anyMatch(t -> t.toLowerCase().contains(lowerQuery));
            if (match) {
                File f = new File(entry.getKey());
                if (f.exists()) results.add(f);
            }
        }
        return results;
    }

    // --- SETTINGS & PERSISTENCE ---

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
        if (folder != null) {
            data.settings.put("last_folder", folder.getAbsolutePath());
            save();
        }
    }

    public synchronized boolean isGridMode() {
        return Boolean.parseBoolean(data.settings.getOrDefault("view_mode_grid", "false"));
    }

    public synchronized void setGridMode(boolean grid) {
        data.settings.put("view_mode_grid", String.valueOf(grid));
        save();
    }

    // --- METADATA CACHE (For Search) ---
    // Simple in-memory cache that persists to disk
    public synchronized void cacheMetadata(File file, Map<String, String> meta) {
        // Only store if prompt exists to save space
        if (meta.containsKey("Prompt")) {
            data.metadataCache.put(file.getAbsolutePath(), meta);
            // Don't save on every single cache update to avoid IO thrashing,
            // but for safety we save occasionally or rely on other saves.
        }
    }

    public synchronized Map<String, String> getCachedMetadata(File file) {
        return data.metadataCache.get(file.getAbsolutePath());
    }

    private void load() {
        File file = new File(DATA_FILE);
        if (file.exists()) {
            try {
                data = mapper.readValue(file, PersistentData.class);
            } catch (IOException e) {
                data = new PersistentData();
            }
        } else {
            data = new PersistentData();
        }
        // Init nulls
        if (data.stars == null) data.stars = new HashSet<>();
        if (data.settings == null) data.settings = new HashMap<>();
        if (data.customLists == null) data.customLists = new HashMap<>();
        if (data.fileTags == null) data.fileTags = new HashMap<>();
        if (data.metadataCache == null) data.metadataCache = new HashMap<>();
    }

    private synchronized void save() {
        try {
            File file = new File(DATA_FILE);
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            mapper.writeValue(file, data);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class PersistentData {
        public Set<String> stars = new HashSet<>();
        public Map<String, String> settings = new HashMap<>();
        public Map<String, List<String>> customLists = new HashMap<>();
        public Map<String, Set<String>> fileTags = new HashMap<>();
        public Map<String, Map<String, String>> metadataCache = new HashMap<>();
    }
}