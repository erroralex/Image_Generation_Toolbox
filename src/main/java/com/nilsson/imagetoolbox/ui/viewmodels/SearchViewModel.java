package com.nilsson.imagetoolbox.ui.viewmodels;

import com.nilsson.imagetoolbox.data.ImageRepository;
import de.saxsys.mvvmfx.ViewModel;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.*;
import java.util.concurrent.ExecutorService;

/**
 * ViewModel responsible for managing search state and filters.
 * Handles search queries, filter selections (Model, Sampler, Lora, Rating), and loading available filter options.
 */
public class SearchViewModel implements ViewModel {

    private static final Logger logger = LoggerFactory.getLogger(SearchViewModel.class);

    private final ImageRepository imageRepo;
    private final ExecutorService executor;

    // --- Filter Properties ---
    private final StringProperty searchQuery = new SimpleStringProperty("");
    private final ObservableList<String> availableModels = FXCollections.observableArrayList();
    private final ObservableList<String> availableSamplers = FXCollections.observableArrayList();
    private final ObservableList<String> loras = FXCollections.observableArrayList();
    private final ObjectProperty<String> selectedModel = new SimpleObjectProperty<>(null);
    private final ObjectProperty<String> selectedSampler = new SimpleObjectProperty<>(null);
    private final ObjectProperty<String> selectedLora = new SimpleObjectProperty<>(null);
    private final ObjectProperty<String> selectedTag = new SimpleObjectProperty<>(null);
    private final ObservableList<String> stars = FXCollections.observableArrayList("Any Star Count", "1", "2", "3", "4", "5");
    private final ObjectProperty<String> selectedStar = new SimpleObjectProperty<>();

    @Inject
    public SearchViewModel(ImageRepository imageRepo, ExecutorService executor) {
        this.imageRepo = imageRepo;
        this.executor = executor;
        loadFilters();
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

    private String cleanLoraName(String raw) {
        if (raw.toLowerCase().startsWith("<lora:")) raw = raw.substring(6);
        if (raw.endsWith(">")) raw = raw.substring(0, raw.length() - 1);
        int lastColon = raw.lastIndexOf(':');
        if (lastColon > 0 && raw.substring(lastColon + 1).matches("[\\d.]+")) {
            raw = raw.substring(0, lastColon);
        }
        return raw.trim();
    }

    public boolean isAll(String val) {
        return val == null || "All".equals(val);
    }

    // --- Accessors ---

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

    public ObservableList<String> getLoras() {
        return loras;
    }

    public ObjectProperty<String> selectedLoraProperty() {
        return selectedLora;
    }

    public ObjectProperty<String> selectedTagProperty() {
        return selectedTag;
    }

    public ObservableList<String> getStars() {
        return stars;
    }

    public ObjectProperty<String> selectedStarProperty() {
        return selectedStar;
    }
}
