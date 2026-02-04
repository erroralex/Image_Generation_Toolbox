package com.nilsson.imagetoolbox.ui.viewmodels;

import com.nilsson.imagetoolbox.data.ImageRepository;
import com.nilsson.imagetoolbox.data.UserDataManager;
import com.nilsson.imagetoolbox.service.MetadataService;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * <h2>ImageBrowserViewModelTest</h2>
 * <p>
 * This test suite provides unit testing for the {@link ImageBrowserViewModel} using the Mockito framework.
 * It validates the interaction between the ViewModel and its data/service dependencies while ensuring
 * JavaFX property bindings and thread-dependent tasks behave correctly.
 * </p>
 * * <h3>Key Testing Areas:</h3>
 * <ul>
 * <li><b>Initialization:</b> Verifies that distinct filter values are loaded from the repository upon startup.</li>
 * <li><b>State Management:</b> Ensures that selecting an image correctly updates the sidebar properties (ratings, metadata).</li>
 * <li><b>Search Logic:</b> Validates that search queries are correctly propagated through the view model properties.</li>
 * <li><b>Threading:</b> Utilizes a custom {@code DirectExecutor} and {@code CountDownLatch} to synchronize
 * background tasks and JavaFX {@code Platform.runLater} calls during testing.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ImageBrowserViewModelTest {

    @Mock UserDataManager dataManager;
    @Mock MetadataService metaService;
    @Mock ImageRepository imageRepo;

    private ImageBrowserViewModel viewModel;
    private final DirectExecutor directExecutor = new DirectExecutor();

    // --- Lifecycle and Setup ---

    /**
     * Initialize JavaFX Toolkit once for the duration of the test class.
     */
    @BeforeAll
    static void initToolkit() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException ignored) {
            // Toolkit already initialized
        }
    }

    @BeforeEach
    void setUp() {
        when(imageRepo.getDistinctValues("Model"))
                .thenReturn(List.of("SDXL", "Flux"));

        viewModel = new ImageBrowserViewModel(
                dataManager,
                metaService,
                imageRepo,
                directExecutor
        );
    }

    // --- Test Cases ---

    @Test
    void testFiltersLoadOnStartup() throws InterruptedException {
        waitForRunLater();

        assertTrue(viewModel.getModels().contains("SDXL"));
        assertTrue(viewModel.getModels().contains("Flux"));
    }

    @Test
    void testSelectionUpdatesSidebar() throws InterruptedException {
        File mockFile = new File("test.png");

        when(dataManager.getRating(mockFile)).thenReturn(3);
        when(dataManager.hasCachedMetadata(mockFile)).thenReturn(true);
        when(dataManager.getCachedMetadata(mockFile))
                .thenReturn(Map.of("Model", "SDXL"));

        viewModel.updateSelection(Collections.singletonList(mockFile));

        waitForRunLater();

        assertEquals(3, viewModel.activeRatingProperty().get());
        assertEquals(
                "SDXL",
                viewModel.activeMetadataProperty().get().get("Model")
        );
    }

    @Test
    void testSearchFilter() {
        viewModel.search("cyberpunk");
        assertEquals("cyberpunk", viewModel.searchQueryProperty().get());
    }

    // --- Internal Utilities ---

    /**
     * Helper method to block the test thread until the JavaFX Application thread
     * has finished processing tasks in the queue.
     */
    private void waitForRunLater() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);

        if (!latch.await(2, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout waiting for JavaFX");
        }
    }

    /**
     * A synchronous executor implementation that runs commands immediately
     * on the calling thread to simplify asynchronous testing.
     */
    static class DirectExecutor extends AbstractExecutorService {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }
    }
}