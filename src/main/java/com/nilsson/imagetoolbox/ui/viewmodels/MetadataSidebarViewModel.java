package com.nilsson.imagetoolbox.ui.viewmodels;

import com.nilsson.imagetoolbox.data.UserDataManager;
import de.saxsys.mvvmfx.ViewModel;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ObservableList;

import javax.inject.Inject;
import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 ViewModel implementation for the Metadata Sidebar component within the Image Toolbox.

 <p>This class facilitates the display and management of image-specific information, acting
 as a specialized bridge between the {@link ImageBrowserViewModel} and the sidebar UI. It
 utilizes JavaFX properties to provide reactive updates when the selection or metadata
 of an image changes.</p>

 <p><b>Key Responsibilities:</b></p>
 <ul>
 <li>Synchronizing state with the global {@code ImageBrowserViewModel} via property bindings.</li>
 <li>Exposing image metadata, tags, and rating information for UI binding.</li>
 <li>Providing command interfaces for collection management and user ratings.</li>
 </ul>

 <p><b>Lifecycle & Threading:</b> Expected to be managed by a Dependency Injection container.
 Property updates typically occur on the JavaFX Application Thread due to internal bindings.</p>

 @author Senior Java Engineer
 @version 1.0
 @see de.saxsys.mvvmfx.ViewModel
 @see ImageBrowserViewModel */
public class MetadataSidebarViewModel implements ViewModel {

    // ------------------------------------------------------------------------
    // Observable State (Properties)
    // ------------------------------------------------------------------------

    private final ImageBrowserViewModel mainViewModel;
    private final CollectionViewModel collectionViewModel;
    private final UserDataManager userDataManager;

    private final ObjectProperty<File> currentFile = new SimpleObjectProperty<>();
    private final ObjectProperty<Map<String, String>> activeMetadata = new SimpleObjectProperty<>();
    private final ObjectProperty<Set<String>> activeTags = new SimpleObjectProperty<>();
    private final IntegerProperty activeRating = new SimpleIntegerProperty(0);

    // ------------------------------------------------------------------------
    // Initialization & Dependency Injection
    // ------------------------------------------------------------------------

    /**
     Constructs a new MetadataSidebarViewModel and initializes unidirectional bindings.
     The properties of this class are bound to the properties of the mainViewModel
     to ensure the sidebar always reflects the global application state.

     @param mainViewModel The primary view model managing the global application state.
     @param collectionViewModel The view model managing collections.
     @param userDataManager The manager for user data operations.
     */
    @Inject
    public MetadataSidebarViewModel(ImageBrowserViewModel mainViewModel, CollectionViewModel collectionViewModel, UserDataManager userDataManager) {
        this.mainViewModel = mainViewModel;
        this.collectionViewModel = collectionViewModel;
        this.userDataManager = userDataManager;

        this.activeMetadata.bind(mainViewModel.activeMetadataProperty());
        this.activeTags.bind(mainViewModel.activeTagsProperty());
        this.activeRating.bind(mainViewModel.activeRatingProperty());
        this.currentFile.bind(mainViewModel.getSelectedImage());
    }

    // ------------------------------------------------------------------------
    // Property Accessors
    // ------------------------------------------------------------------------

    /**
     @return An observable property containing the currently selected image file.
     */
    public ObjectProperty<File> currentFileProperty() {
        return currentFile;
    }

    /**
     @return An observable property containing a map of metadata keys and values for the current image.
     */
    public ObjectProperty<Map<String, String>> activeMetadataProperty() {
        return activeMetadata;
    }

    /**
     @return An observable property containing a set of tags for the current image.
     */
    public ObjectProperty<Set<String>> activeTagsProperty() {
        return activeTags;
    }

    /**
     @return An observable property representing the integer rating (e.g., 0-5) of the current image.
     */
    public IntegerProperty activeRatingProperty() {
        return activeRating;
    }

    // ------------------------------------------------------------------------
    // Data Accessors
    // ------------------------------------------------------------------------

    /**
     Retrieves the global list of user-defined collections from the main state.

     @return An observable list of collection names.
     */
    public ObservableList<String> getCollections() {
        return collectionViewModel.getCollectionList();
    }

    // ------------------------------------------------------------------------
    // Actions & User Commands
    // ------------------------------------------------------------------------

    /**
     Updates the rating for the currently selected image by delegating to the main view model.

     @param rating The new rating value to persist.
     */
    public void setRating(int rating) {
        mainViewModel.setRating(rating);
    }

    /**
     Triggers the creation of a new image collection.

     @param name The unique name of the collection to create.
     */
    public void createCollection(String name) {
        collectionViewModel.createNewCollection(name);
    }

    /**
     Adds the currently selected image to a specific collection.

     @param collectionName The name of the target collection.
     */
    public void addToCollection(String collectionName) {
        mainViewModel.addSelectedToCollection(collectionName);
    }

    /**
     Adds a new tag to the currently selected image.

     @param tag The tag to add.
     */
    public void addTag(String tag) {
        mainViewModel.addTag(tag);
    }

    /**
     Removes a tag from the currently selected image.

     @param tag The tag to remove.
     */
    public void removeTag(String tag) {
        mainViewModel.removeTag(tag);
    }
}