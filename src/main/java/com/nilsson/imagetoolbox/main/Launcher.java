package com.nilsson.imagetoolbox.main;

/**
 <h2>Launcher</h2>
 <p>
 This class serves as the secondary entry point for the <b>Image Generation Toolbox</b>.
 </p>
 * <h3>Technical Purpose:</h3>
 <p>
 In certain Java runtime environments and deployment scenarios (such as shaded JARs),
 executing a class that extends {@code javafx.application.Application} directly can
 result in an error regarding missing JavaFX runtime components.
 </p>
 * <p>
 This launcher bypasses that check by providing a {@code main} method that does not
 inherit from the JavaFX Application class, delegating the actual startup logic
 to {@link MainApp}.
 </p>
 * <h3>Execution Flow:</h3>
 <ul>
 <li>User/System invokes {@code Launcher.main()}</li>
 <li>The JVM loads JavaFX dependencies without strict inheritance checks.</li>
 <li>Execution is handed off to {@code MainApp.main()}, bootstrapping the
 MVVMFX and Guice environments.</li>
 </ul>
 */
public class Launcher {

    // ------------------------------------------------------------------------
    // Entry Point
    // ------------------------------------------------------------------------

    /**
     The main method that triggers the application startup.
     * @param args Command line arguments passed to the application.
     */
    public static void main(String[] args) {
        MainApp.main(args);
    }
}