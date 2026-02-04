package com.nilsson.imagetoolbox.main;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.nilsson.imagetoolbox.data.*;
import com.nilsson.imagetoolbox.service.MetadataService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

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
     * Provides a global thread pool for background tasks.
     * Configured with a Daemon ThreadFactory so tasks don't prevent app shutdown.
     */
    @Provides
    @Singleton
    public ExecutorService provideExecutorService() {
        return Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors() + 2,
                new ThreadFactory() {
                    private final AtomicInteger count = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r);
                        t.setDaemon(true); // Essential for UI background tasks
                        t.setName("Global-Worker-" + count.getAndIncrement());
                        return t;
                    }
                }
        );
    }
}