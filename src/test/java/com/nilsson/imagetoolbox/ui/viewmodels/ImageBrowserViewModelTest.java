package com.nilsson.imagetoolbox.ui.viewmodels;

import com.nilsson.imagetoolbox.data.ImageRepository;
import com.nilsson.imagetoolbox.data.UserDataManager;
import com.nilsson.imagetoolbox.service.IndexingService;
import com.nilsson.imagetoolbox.service.MetadataService;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 Unit tests for the {@link ImageBrowserViewModel} class.
 * <p>This test suite ensures the proper initialization and behavior of the Image Browser's
 view model logic. It utilizes Mockito for dependency mocking and a custom
 {@code DirectExecutor} to ensure asynchronous tasks are executed predictably
 on the calling thread during testing.</p>
 * <p>The class also handles the lifecycle of the JavaFX runtime to prevent
 toolkit initialization errors commonly encountered when testing UI-bound components.</p>
 */
class ImageBrowserViewModelTest {

    // --- Dependencies & Mocks ---

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

    // --- Lifecycle Methods ---

    /**
     Initializes the JavaFX runtime once before any tests in this class run.
     This prevents "Toolkit not initialized" exceptions.
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
     Prepares the test environment before each individual test case.
     Sets up Mockito mocks and defines default behaviors for repository calls
     to prevent NullPointerExceptions during view model instantiation.
     */
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(dataManager.getCollections()).thenReturn(Collections.emptyList());
        when(imageRepo.getDistinctValues(any())).thenReturn(Collections.emptyList());

        viewModel = new ImageBrowserViewModel(
                dataManager,
                metaService,
                imageRepo,
                indexingService,
                executor
        );
    }

    // --- Unit Tests ---

    @Test
    void testInitialization() {
        assertNotNull(viewModel.getFilteredFiles());
        assertNotNull(viewModel.getModels());
    }

    // --- Inner Helper Classes ---

    /**
     A synchronous implementation of {@link ExecutorService} for testing purposes.
     * <p>This class overrides the standard asynchronous behavior of an Executor,
     forcing all submitted tasks to run immediately on the current thread. This
     ensures that tests involving background tasks remain deterministic and thread-safe.</p>
     */
    static class DirectExecutor implements ExecutorService {

        @Override
        public void execute(Runnable command) {
            command.run();
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
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
            return true;
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
            try {
                return java.util.concurrent.CompletableFuture.completedFuture(task.call());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
            task.run();
            return java.util.concurrent.CompletableFuture.completedFuture(result);
        }

        @Override
        public java.util.concurrent.Future<?> submit(Runnable task) {
            task.run();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            return Collections.emptyList();
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks, long timeout, java.util.concurrent.TimeUnit unit) {
            return Collections.emptyList();
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            return null;
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks, long timeout, java.util.concurrent.TimeUnit unit) {
            return null;
        }
    }
}