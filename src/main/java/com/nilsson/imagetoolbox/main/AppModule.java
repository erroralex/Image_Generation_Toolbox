package com.nilsson.imagetoolbox.main;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.nilsson.imagetoolbox.data.*;
import com.nilsson.imagetoolbox.service.MetadataService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class AppModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(DatabaseService.class).in(Singleton.class);
        bind(UserDataManager.class).in(Singleton.class);
        bind(MetadataService.class).in(Singleton.class);
        bind(SettingsRepository.class).in(Singleton.class);
        bind(CollectionRepository.class).in(Singleton.class);
        bind(ImageRepository.class).in(Singleton.class);
    }

    /**
     * Provides a global executor service backed by Virtual Threads (Project Loom).
     * <p>
     * Instead of a fixed pool of platform threads, this executor creates a new virtual thread
     * for every task. Virtual threads are lightweight and always daemon threads, ensuring
     * they do not prevent application shutdown while offering superior throughput for I/O-bound operations.
     */
    @Provides
    @Singleton
    public ExecutorService provideExecutorService() {
        // Preserves the existing naming convention for observability in logs
        ThreadFactory factory = Thread.ofVirtual()
                .name("Global-Worker-", 1)
                .factory();

        return Executors.newThreadPerTaskExecutor(factory);
    }
}