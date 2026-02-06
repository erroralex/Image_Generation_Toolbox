package com.nilsson.imagetoolbox.ui.views;

import com.nilsson.imagetoolbox.ui.viewmodels.MetadataSidebarViewModel;
import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;

/**
 <h2>MetadataSidebarTest</h2>
 <p>
 Provides comprehensive UI unit testing for the {@link MetadataSidebar} component.
 This class ensures that metadata extraction results are correctly mapped and displayed
 within the sidebar's graphical controls.
 </p>
 <h3>Technical Design:</h3>
 <ul>
 <li><b>Toolkit Initialization:</b> Orchestrates the JavaFX runtime lifecycle to support UI node instantiation.</li>
 <li><b>Concurrency Control:</b> Manages synchronization between the JUnit test thread and the JavaFX
 Application Thread using {@code waitForFxEvents}.</li>
 <li><b>Reflection-Based Assertion:</b> Accesses private UI components to verify internal state without
 exposing internal nodes to the public API of the sidebar.</li>
 </ul>
 */
@ExtendWith(MockitoExtension.class)
class MetadataSidebarTest {

    @Mock
    private MetadataSidebar.SidebarActionHandler mockHandler;

    @Mock
    private MetadataSidebarViewModel mockViewModel;

    private MetadataSidebar sidebar;

    // --- Lifecycle Management ---

    /**
     Bootstraps the JavaFX Platform once for the test suite.
     */
    @BeforeAll
    static void initToolkit() {
        try {
            Platform.startup(() -> {
            });
        } catch (IllegalStateException e) {
            // Ignore if toolkit is already active
        }
    }

    /**
     Instantiates the MetadataSidebar on the JavaFX thread before each test.
     */
    @BeforeEach
    void setUp() throws Exception {
        // Mock ViewModel properties to avoid NPEs during binding
        // Use lenient() because not all tests trigger all listeners/properties, avoiding UnnecessaryStubbingException
        lenient().when(mockViewModel.activeMetadataProperty()).thenReturn(new SimpleObjectProperty<>());
        lenient().when(mockViewModel.activeRatingProperty()).thenReturn(new SimpleIntegerProperty());
        lenient().when(mockViewModel.currentFileProperty()).thenReturn(new SimpleObjectProperty<>());
        lenient().when(mockViewModel.getCollections()).thenReturn(FXCollections.observableArrayList());

        Platform.runLater(() -> {
            sidebar = new MetadataSidebar();
            // Inject mock ViewModel via reflection since we can't use Guice here easily
            try {
                Field vmField = MetadataSidebar.class.getDeclaredField("viewModel");
                vmField.setAccessible(true);
                vmField.set(sidebar, mockViewModel);
                
                // Manually trigger initialize since we're not using FluentViewLoader
                sidebar.initialize(null, null);
                sidebar.setActionHandler(mockHandler);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        waitForFxEvents();
    }

    // --- Test Cases ---

    @Test
    void testUpdateDataPopulatesFields() throws Exception {
        File file = new File("test_image.png");
        Map<String, String> meta = new HashMap<>();
        meta.put("Model", "Stable Diffusion XL");
        meta.put("Prompt", "A futuristic city with neon lights");
        meta.put("Seed", "123456789");
        meta.put("CFG", "7.0");

        Platform.runLater(() -> sidebar.updateData(file, meta));
        waitForFxEvents();

        assertFieldText("modelField", "Stable Diffusion XL");
        assertTextAreaText("promptArea", "A futuristic city with neon lights");
        assertFieldText("seedField", "123456789");
        assertFieldText("cfgField", "7.0");
        assertFieldText("inspectorFilename", "test_image.png");
    }

    @Test
    void testMissingMetadataHandlesGracefully() throws Exception {
        File file = new File("partial.png");
        Map<String, String> meta = new HashMap<>();
        meta.put("Software", "ComfyUI");

        Platform.runLater(() -> sidebar.updateData(file, meta));
        waitForFxEvents();

        assertFieldText("softwareField", "ComfyUI");
        assertFieldText("modelField", "-");
    }

    @Test
    void testClearData() throws Exception {
        Platform.runLater(() -> {
            sidebar.updateData(new File("prev.png"), Map.of("Model", "Old"));
        });
        waitForFxEvents();

        Platform.runLater(() -> sidebar.updateData(null, null));
        waitForFxEvents();

        assertFieldText("inspectorFilename", "No Selection");
        assertTextAreaText("promptArea", "");
    }

    // --- Private Helper Methods ---

    /**
     Uses reflection to verify the text content of a private TextField.
     */
    private void assertFieldText(String fieldName, String expected) throws Exception {
        Field f = MetadataSidebar.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        TextField tf = (TextField) f.get(sidebar);
        assertEquals(expected, tf.getText(), "Field '" + fieldName + "' mismatch");
    }

    /**
     Uses reflection to verify the text content of a private TextArea.
     */
    private void assertTextAreaText(String fieldName, String expected) throws Exception {
        Field f = MetadataSidebar.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        TextArea ta = (TextArea) f.get(sidebar);
        assertEquals(expected, ta.getText(), "TextArea '" + fieldName + "' mismatch");
    }

    /**
     Blocks the execution thread until the JavaFX Application Thread has processed
     all pending runnables in its queue.
     */
    private void waitForFxEvents() {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new RuntimeException("Timeout waiting for FX events");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}