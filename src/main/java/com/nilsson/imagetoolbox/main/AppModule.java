package com.nilsson.imagetoolbox.main;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.nilsson.imagetoolbox.data.DatabaseService;
import com.nilsson.imagetoolbox.data.UserDataManager;
import com.nilsson.imagetoolbox.service.MetadataService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 <h2>AppModule</h2>
 <p>
 This class serves as the primary configuration module for Google Guice dependency injection.
 It defines the wiring, scope, and lifecycle of the core services used throughout the
 Image Toolbox application.
 </p>

 <h3>Dependency Graph:</h3>
 <ul>
 <li><b>Data Persistence:</b> Manages the {@link DatabaseService} and {@link UserDataManager}
 as singletons to ensure consistent state and connection pooling across the application.</li>
 <li><b>Domain Services:</b> Binds the {@link MetadataService} for handling image file
 extraction and processing.</li>
 <li><b>Resource Management:</b> Configures a global {@link ExecutorService} utilized for
 background processing, such as library scanning and search indexing.</li>
 </ul>

 <h3>Concurrency Strategy:</h3>
 <p>
 The provided thread pool is sized dynamically based on the host system's hardware profile:
 {@code availableProcessors + 2}. This ensures optimal throughput for I/O bound tasks like
 image loading and metadata extraction while preventing thread exhaustion.
 </p>
 */
public class AppModule extends AbstractModule {

    // ------------------------------------------------------------------------
    // Static Binding Configuration
    // ------------------------------------------------------------------------

    @Override
    protected void configure() {
        bind(DatabaseService.class).in(Singleton.class);
        bind(UserDataManager.class).in(Singleton.class);
        bind(MetadataService.class).in(Singleton.class);
    }

    // ------------------------------------------------------------------------
    // Managed Instance Providers
    // ------------------------------------------------------------------------

    /**
     Provides a thread pool for background tasks.
     * @return A fixed thread pool sized to the current system CPU count plus an I/O buffer.
     */
    @Provides
    @Singleton
    public ExecutorService provideExecutorService() {
        return Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() + 2);
    }
}