package com.nilsson.imagetoolbox.ui.views;

import javafx.animation.FadeTransition;
import javafx.beans.property.ObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 <h2>SingleImageView</h2>
 <p>
 A high-level UI component designed for focused image viewing within the Image Toolbox.
 This class provides a centered, aspect-ratio-locked display of a single image and
 implements interactive navigation overlays.
 </p>
 * <h3>Core Features:</h3>
 <ul>
 <li><b>Dynamic Scaling:</b> The internal {@link ImageView} is bidirectionally bound
 to the container dimensions, ensuring the image fills the available space while
 preserving its original proportions.</li>
 <li><b>Interactive Overlays:</b> Includes contextual navigation arrows (Left/Right)
 that utilize a proximity-based hover logic to appear only when the user's cursor
 nears the edges of the view.</li>
 <li><b>Gesture Integration:</b> Supports single-click interactions to toggle
 the metadata drawer and double-click logic for secondary actions.</li>
 <li><b>Smooth Animations:</b> Employs {@link FadeTransition} for the navigation
 controls to provide a polished, modern user experience.</li>
 </ul>
 *
 */
public class SingleImageView extends StackPane {

    // ------------------------------------------------------------------------
    // UI Components
    // ------------------------------------------------------------------------

    private final ImageView imageView;
    private final Label leftArrow;
    private final Label rightArrow;

    // ------------------------------------------------------------------------
    // Action Delegates
    // ------------------------------------------------------------------------

    private final Runnable onToggleDrawer;

    // ------------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------------

    /**
     Primary constructor for SingleImageView with full navigation support.
     * @param onToggleDrawer Action to perform when toggling the sidebar.

     @param onPrev Action to navigate to the previous image.
     @param onNext Action to navigate to the next image.
     */
    public SingleImageView(Runnable onToggleDrawer, Runnable onPrev, Runnable onNext) {
        this.onToggleDrawer = onToggleDrawer;

        this.setStyle("-fx-background-color: -app-bg-deepest;");

        // Image Display Configuration
        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.fitWidthProperty().bind(this.widthProperty());
        imageView.fitHeightProperty().bind(this.heightProperty());

        // Toggle Drawer on Click on IMAGE
        imageView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 1 && onToggleDrawer != null) {
                onToggleDrawer.run();
                e.consume();
            }
        });

        // Navigation Overlays
        leftArrow = createArrow("fa-chevron-left", Pos.CENTER_LEFT, onPrev);
        rightArrow = createArrow("fa-chevron-right", Pos.CENTER_RIGHT, onNext);

        this.getChildren().addAll(imageView, leftArrow, rightArrow);

        // Proximity-based Hover Logic
        this.setOnMouseMoved(e -> {
            double w = this.getWidth();
            double x = e.getX();

            // 15% threshold for edges to trigger arrow visibility
            boolean nearLeft = x < (w * 0.15);
            boolean nearRight = x > (w * 0.85);

            animateArrow(leftArrow, nearLeft);
            animateArrow(rightArrow, nearRight);
        });

        this.setOnMouseExited(e -> {
            animateArrow(leftArrow, false);
            animateArrow(rightArrow, false);
        });
    }

    /**
     Compatibility constructor for basic viewing without navigation delegates.
     * @param onToggleDrawer Action to perform when toggling the sidebar.
     */
    public SingleImageView(Runnable onToggleDrawer) {
        this(onToggleDrawer, null, null);
    }

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------

    /**
     Updates the currently displayed image.

     @param img The new {@link Image} to display.
     */
    public void setImage(Image img) {
        imageView.setImage(img);
    }

    /**
     Accessor for the image property, useful for external data binding.

     @return The underlying {@link ObjectProperty} of the ImageView.
     */
    public ObjectProperty<Image> imageProperty() {
        return imageView.imageProperty();
    }

    // ------------------------------------------------------------------------
    // Internal Helper Methods
    // ------------------------------------------------------------------------

    private Label createArrow(String iconCode, Pos align, Runnable action) {
        FontIcon icon = new FontIcon(iconCode);
        icon.setIconSize(40);
        icon.setIconColor(javafx.scene.paint.Color.WHITE);

        Label lbl = new Label();
        lbl.setGraphic(icon);

        // Constraints to prevent the hit area from blocking central clicks
        lbl.setMaxHeight(100);
        lbl.setPrefWidth(60);
        lbl.setAlignment(align);
        lbl.setPadding(new javafx.geometry.Insets(0, 10, 0, 10));

        lbl.setCursor(Cursor.HAND);
        lbl.setOpacity(0); // Hidden by default
        lbl.setStyle("-fx-background-color: rgba(0,0,0,0.3); -fx-background-radius: 10;");

        lbl.setOnMouseClicked(e -> {
            // Handle Navigation
            if (e.getClickCount() == 1 && action != null) {
                action.run();
                e.consume();
            }
            // Handle secondary Drawer toggle if clicked on arrow region
            if (e.getClickCount() == 2 && onToggleDrawer != null) {
                onToggleDrawer.run();
            }
        });

        StackPane.setAlignment(lbl, align);

        // Logical margins to offset from the absolute edge of the container
        if (align == Pos.CENTER_RIGHT) {
            StackPane.setMargin(lbl, new javafx.geometry.Insets(0, 10, 0, 0));
        } else {
            StackPane.setMargin(lbl, new javafx.geometry.Insets(0, 0, 0, 10));
        }

        return lbl;
    }

    private void animateArrow(Label arrow, boolean visible) {
        double target = visible ? 1.0 : 0.0;
        // Optimization: Only trigger transition if the state actually changed
        if (Math.abs(arrow.getOpacity() - target) > 0.01) {
            FadeTransition ft = new FadeTransition(Duration.millis(200), arrow);
            ft.setToValue(target);
            ft.play();
        }
    }
}