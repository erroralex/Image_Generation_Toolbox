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
 * Main dependency injection module for the Image Toolbox application.
 * Configures the Google Guice bindings for core services and manages the
 * lifecycle of the application's global ExecutorService.
 */
public class AppModule extends AbstractModule {

    // --- Configuration ---
    @Override
    protected void configure() {
        bind(DatabaseService.class).in(Singleton.class);
        bind(UserDataManager.class).in(Singleton.class);
        bind(MetadataService.class).in(Singleton.class);
    }

    // --- Managed Providers ---
    @Provides
    @Singleton
    public ExecutorService provideExecutorService() {
        return Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() + 2);
    }
}