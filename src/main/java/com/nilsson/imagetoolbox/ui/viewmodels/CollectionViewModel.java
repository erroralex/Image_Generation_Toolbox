package com.nilsson.imagetoolbox.ui.viewmodels;

import com.nilsson.imagetoolbox.data.UserDataManager;
import de.saxsys.mvvmfx.ViewModel;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * ViewModel responsible for managing image collections.
 * Handles creating, deleting, and listing collections, as well as adding images to them.
 */
public class CollectionViewModel implements ViewModel {

    private static final Logger logger = LoggerFactory.getLogger(CollectionViewModel.class);

    private final UserDataManager dataManager;
    private final ExecutorService executor;
    private final ObservableList<String> collectionList = FXCollections.observableArrayList();

    @Inject
    public CollectionViewModel(UserDataManager dataManager, ExecutorService executor) {
        this.dataManager = dataManager;
        this.executor = executor;
        refreshCollections();
    }

    public void refreshCollections() {
        executor.submit(() -> {
            List<String> c = dataManager.getCollections();
            Platform.runLater(() -> collectionList.setAll(c));
        });
    }

    public void createNewCollection(String name) {
        dataManager.createCollection(name);
        refreshCollections();
    }

    public void deleteCollection(String name) {
        dataManager.deleteCollection(name);
        refreshCollections();
    }

    public void addFilesToCollection(String name, List<File> files) {
        if (files == null || files.isEmpty()) return;
        List<File> filesToAdd = new ArrayList<>(files);
        executor.submit(() -> filesToAdd.forEach(f -> dataManager.addImageToCollection(name, f)));
    }

    public ObservableList<String> getCollectionList() {
        return collectionList;
    }
}
