package com.nilsson.imagetoolbox.ui.components;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.util.Duration;
import org.controlsfx.control.Notifications;

import javax.inject.Singleton;

/**
 * Service for displaying non-blocking user notifications (Toasts).
 * <p>
 * This service wraps ControlsFX Notifications to provide a consistent
 * feedback mechanism for background tasks and error reporting.
 * It ensures all UI updates are dispatched to the JavaFX Application Thread.
 * </p>
 */
@Singleton
public class NotificationService {

    /**
     * Shows an information notification.
     *
     * @param title   The title of the notification.
     * @param message The message body.
     */
    public void showInfo(String title, String message) {
        Platform.runLater(() -> 
            Notifications.create()
                .title(title)
                .text(message)
                .position(Pos.BOTTOM_RIGHT)
                .hideAfter(Duration.seconds(3))
                .showInformation()
        );
    }

    /**
     * Shows a warning notification.
     *
     * @param title   The title of the notification.
     * @param message The message body.
     */
    public void showWarning(String title, String message) {
        Platform.runLater(() -> 
            Notifications.create()
                .title(title)
                .text(message)
                .position(Pos.BOTTOM_RIGHT)
                .hideAfter(Duration.seconds(4))
                .showWarning()
        );
    }

    /**
     * Shows an error notification.
     *
     * @param title   The title of the notification.
     * @param message The message body.
     */
    public void showError(String title, String message) {
        Platform.runLater(() -> 
            Notifications.create()
                .title(title)
                .text(message)
                .position(Pos.BOTTOM_RIGHT)
                .hideAfter(Duration.seconds(5))
                .showError()
        );
    }
}
