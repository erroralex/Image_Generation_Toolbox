package com.nilsson.imagetoolbox.ui.components;

import com.nilsson.imagetoolbox.ui.viewmodels.BrowserToolbarViewModel;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.scene.Scene;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 Automated UI and Integration tests for the {@link BrowserToolbar} component.
 * <p>This class utilizes TestFX and Mockito to verify the two-way bindings between the
 UI View and the {@link BrowserToolbarViewModel}. It ensures that user interactions
 (typing, clicking, menu selections) correctly propagate to the underlying state
 and that UI elements are populated correctly from the ViewModel data.</p>
 * <p>Engineering Note: Tests utilize {@code robot.interact()} to ensure thread-safe
 execution on the JavaFX Application Thread and to maintain test stability across
 different operating systems.</p>

 @author Senior Java Engineer
 @version 1.0 */
@ExtendWith(MockitoExtension.class)
@ExtendWith(ApplicationExtension.class)
class BrowserToolbarTest {

    @Mock
    private BrowserToolbarViewModel mockViewModel;

    private BrowserToolbar toolbar;

    // --- Setup & Configuration ---

    /**
     Configures the mocked ViewModel with functional JavaFX properties.
     This ensures that UI bindings established during initialization have
     valid property targets to observe.
     */
    void setupMocks() {
        lenient().when(mockViewModel.searchQueryProperty()).thenReturn(new SimpleStringProperty(""));
        lenient().when(mockViewModel.cardSizeProperty()).thenReturn(new SimpleDoubleProperty(160));

        // Filter Lists
        lenient().when(mockViewModel.getModels()).thenReturn(FXCollections.observableArrayList("Model A", "Model B"));
        lenient().when(mockViewModel.getSamplers()).thenReturn(FXCollections.observableArrayList());
        lenient().when(mockViewModel.getLoras()).thenReturn(FXCollections.observableArrayList());
        lenient().when(mockViewModel.getStars()).thenReturn(FXCollections.observableArrayList());

        // Selections
        lenient().when(mockViewModel.selectedModelProperty()).thenReturn(new SimpleObjectProperty<>());
        lenient().when(mockViewModel.selectedSamplerProperty()).thenReturn(new SimpleObjectProperty<>());
        lenient().when(mockViewModel.selectedLoraProperty()).thenReturn(new SimpleObjectProperty<>());
        lenient().when(mockViewModel.selectedStarProperty()).thenReturn(new SimpleObjectProperty<>());

        // Actions
        lenient().when(mockViewModel.onGridActionProperty()).thenReturn(new SimpleObjectProperty<>(() -> {
        }));
        lenient().when(mockViewModel.onSingleActionProperty()).thenReturn(new SimpleObjectProperty<>(() -> {
        }));
        lenient().when(mockViewModel.onSearchEnterProperty()).thenReturn(new SimpleObjectProperty<>(() -> {
        }));
    }

    /**
     Initializes the JavaFX Stage and the component under test.
     * @param stage The primary stage provided by the ApplicationExtension.
     */
    @Start
    private void start(Stage stage) {
        setupMocks();

        toolbar = new BrowserToolbar();
        toolbar.setViewModel(mockViewModel);
        toolbar.initialize(null, null);

        stage.setScene(new Scene(toolbar, 800, 150));
        stage.setAlwaysOnTop(true);
        stage.show();
    }

    // --- Test Suites ---

    /**
     Verifies that entering text into the search field correctly updates
     the ViewModel's search query property.
     */
    @Test
    void testSearchBinding(FxRobot robot) {
        TextField searchField = robot.lookup(".pill-search-field").queryAs(TextField.class);

        robot.interact(() -> searchField.setText("cyberpunk"));

        assertEquals("cyberpunk", mockViewModel.searchQueryProperty().get());
    }

    /**
     Verifies that toggling the view mode button triggers the corresponding
     action method in the ViewModel.
     */
    @Test
    void testGridToggleAction(FxRobot robot) {
        ToggleButton singleBtn = robot.lookup(".toolbar-button").nth(1).queryAs(ToggleButton.class);

        robot.interact(singleBtn::fire);

        verify(mockViewModel, times(1)).triggerSingleAction();
    }

    /**
     Verifies that filter menus are populated with data from the ViewModel
     and that selecting an item updates the ViewModel state.
     */
    @Test
    void testFilterMenuPopulation(FxRobot robot) {
        MenuButton modelMenu = robot.lookup(node -> node instanceof MenuButton && "Model".equals(((MenuButton) node).getText()))
                .queryAs(MenuButton.class);

        assertNotNull(modelMenu, "Could not find 'Model' menu button");

        boolean hasModelA = modelMenu.getItems().stream()
                .anyMatch(item -> "Model A".equals(item.getText()));
        assertTrue(hasModelA, "Menu should contain 'Model A'");

        MenuItem modelAItem = modelMenu.getItems().stream()
                .filter(item -> "Model A".equals(item.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Model A item missing"));

        robot.interact(modelAItem::fire);

        assertEquals("Model A", mockViewModel.selectedModelProperty().get());
    }
}