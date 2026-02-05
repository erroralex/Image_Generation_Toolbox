package com.nilsson.imagetoolbox.ui.viewmodels;

import com.nilsson.imagetoolbox.data.ImageRepository;
import com.nilsson.imagetoolbox.data.UserDataManager;
import com.nilsson.imagetoolbox.service.IndexingService;
import com.nilsson.imagetoolbox.service.MetadataService;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 <h2>ImageBrowserViewModelTest</h2>
 <p>
 This test suite validates the orchestration logic within the {@link ImageBrowserViewModel}.
 It ensures that user interactions (filtering, selection, searching) correctly invoke
 underlying repositories and update the observable UI state.
 </p>
 * <h3>Key Testing Strategies:</h3>
 <ul>
 <li><b>Direct Execution:</b> Background tasks are executed synchronously via {@link DirectExecutor}
 to maintain test determinism.</li>
 <li><b>FX Thread Synchronization:</b> Uses {@code Platform.runLater} with a {@code CountDownLatch}
 to bridge the gap between JUnit threads and the JavaFX Application Thread.</li>
 <li><b>Mocking Layer:</b> Isolates business logic from I/O bound services using Mockito.</li>
 </ul>
 */
class ImageBrowserViewModelTest {

    // --- Dependencies ---

    @Mock
    private UserDataManager dataManager;
    @Mock
    private MetadataService metaService;
    @Mock
    private ImageRepository imageRepo;
    @Mock
    private IndexingService indexingService;

    private ImageBrowserViewModel viewModel;
    private final ExecutorService executor = new DirectExecutor();

    @TempDir
    Path tempDir;

    // --- Lifecycle Methods ---

    @BeforeAll
    static void initToolkit() {
        try {
            Platform.startup(() -> {
            });
        } catch (IllegalStateException e) {
            // Toolkit already initialized
        }
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(dataManager.getCollections()).thenReturn(Collections.emptyList());
        when(imageRepo.getDistinctValues(any())).thenReturn(Collections.emptyList());

        viewModel = new ImageBrowserViewModel(dataManager, metaService, imageRepo, indexingService, executor);
        waitForFxEvents();
    }

    // --- Logic Tests ---

    @Test
    void testInitialization() {
        assertNotNull(viewModel.getFilteredFiles());
    }

    @Test
    void testModelFilterTriggersSearch() throws IOException {
        File dummyFile = tempDir.resolve("test.png").toFile();
        assertTrue(dummyFile.createNewFile());

        when(imageRepo.findPaths(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(dummyFile.getAbsolutePath()));

        viewModel.selectedModelProperty().set("SDXL");

        Map<String, String> expected = new HashMap<>();
        expected.put("Model", "SDXL");
        verify(imageRepo).findPaths(eq(""), eq(expected), anyInt());

        waitForFxEvents();
        assertEquals(1, viewModel.getFilteredFiles().size());
    }

    @Test
    void testSelectionUpdatesActiveMetadata() {
        File file = new File("test.png");
        Map<String, String> meta = Map.of("Prompt", "Sunset", "Model", "v1.5");
        when(dataManager.hasCachedMetadata(file)).thenReturn(false);
        when(metaService.getExtractedData(file)).thenReturn(meta);

        viewModel.updateSelection(List.of(file));
        waitForFxEvents();

        assertEquals("Sunset", viewModel.activeMetadataProperty().get().get("Prompt"));
        verify(dataManager).cacheMetadata(file, meta);
    }

    @Test
    void testCombinedFilters() throws IOException {
        File dummyFile = tempDir.resolve("f.png").toFile();
        assertTrue(dummyFile.createNewFile());
        when(imageRepo.findPaths(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(dummyFile.getAbsolutePath()));

        viewModel.selectedModelProperty().set("SDXL");
        viewModel.selectedSamplerProperty().set("Euler");
        waitForFxEvents();

        Map<String, String> expected = new HashMap<>();
        expected.put("Model", "SDXL");
        expected.put("Sampler", "Euler");
        verify(imageRepo).findPaths(eq(""), eq(expected), anyInt());
    }

    // --- Helper Logic ---

    private void waitForFxEvents() {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Platform.runLater(latch::countDown);
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     A synchronous ExecutorService implementation used to flatten asynchronous logic
     during unit tests, ensuring sequential execution on the test thread.
     */
    static class DirectExecutor implements ExecutorService {

        @Override
        public void execute(Runnable r) {
            r.run();
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> t) {
            try {
                return java.util.concurrent.CompletableFuture.completedFuture(t.call());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Runnable t, T r) {
            t.run();
            return java.util.concurrent.CompletableFuture.completedFuture(r);
        }

        @Override
        public java.util.concurrent.Future<?> submit(Runnable t) {
            t.run();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long t, java.util.concurrent.TimeUnit u) {
            return true;
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends java.util.concurrent.Callable<T>> t) {
            return Collections.emptyList();
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends java.util.concurrent.Callable<T>> t, long o, java.util.concurrent.TimeUnit u) {
            return Collections.emptyList();
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> t) {
            return null;
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> t, long o, java.util.concurrent.TimeUnit u) {
            return null;
        }
    }
}