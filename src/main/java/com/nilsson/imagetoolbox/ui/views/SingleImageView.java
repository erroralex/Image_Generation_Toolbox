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
 A specialized UI component designed for high-fidelity image inspection within the Image Toolbox.
 This class extends {@link StackPane} to provide a layered viewing environment where an image
 remains centered and scaled appropriately while supporting interactive overlays.
 </p>
 * <h3>Key Functionalities:</h3>
 <ul>
 <li><b>Responsive Scaling:</b> The internal {@link ImageView} automatically scales to fit
 the container while strictly preserving the image's original aspect ratio.</li>
 <li><b>Contextual Navigation:</b> Implements proximity-aware navigation arrows that appear
 dynamically when the cursor approaches the left or right edges (15% boundary).</li>
 <li><b>Gesture Coordination:</b> Maps single-click events to metadata drawer toggling
 and supports custom navigation delegates for seamless gallery browsing.</li>
 <li><b>Visual Feedback:</b> Utilizes {@link FadeTransition} for smooth opacity changes in
 overlay components, ensuring a non-disruptive user experience.</li>
 </ul>
 * @author Nilsson

 @version 1.0 */
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
     Primary constructor that initializes the view with full navigation and interaction support.
     * @param onToggleDrawer Callback for toggling the side metadata drawer.

     @param onPrev Callback for navigating to the previous image in the sequence.
     @param onNext Callback for navigating to the next image in the sequence.
     */
    public SingleImageView(Runnable onToggleDrawer, Runnable onPrev, Runnable onNext) {
        this.onToggleDrawer = onToggleDrawer;

        this.setStyle("-fx-background-color: -app-bg-deepest;");

        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.fitWidthProperty().bind(this.widthProperty());
        imageView.fitHeightProperty().bind(this.heightProperty());

        imageView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 1 && onToggleDrawer != null) {
                onToggleDrawer.run();
                e.consume();
            }
        });

        leftArrow = createArrow("fa-chevron-left", Pos.CENTER_LEFT, onPrev);
        rightArrow = createArrow("fa-chevron-right", Pos.CENTER_RIGHT, onNext);

        this.getChildren().addAll(imageView, leftArrow, rightArrow);

        this.setOnMouseMoved(e -> {
            double w = this.getWidth();
            double x = e.getX();

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
     Simplified constructor for basic image viewing without navigation controls.
     * @param onToggleDrawer Callback for toggling the side metadata drawer.
     */
    public SingleImageView(Runnable onToggleDrawer) {
        this(onToggleDrawer, null, null);
    }

    // ------------------------------------------------------------------------
    // Public API / Property Accessors
    // ------------------------------------------------------------------------

    /**
     Updates the currently displayed image in the viewport.
     * @param img The {@link Image} instance to display.
     */
    public void setImage(Image img) {
        imageView.setImage(img);
    }

    /**
     Retrieves the image currently held by the viewer.
     * @return The active {@link Image}, or null if the viewer is empty.
     */
    public Image getImage() {
        return imageView.getImage();
    }

    /**
     Returns the underlying image property for binding purposes.
     * @return The {@link ObjectProperty} of the internal {@link ImageView}.
     */
    public ObjectProperty<Image> imageProperty() {
        return imageView.imageProperty();
    }

    // ------------------------------------------------------------------------
    // Internal Helper Methods
    // ------------------------------------------------------------------------

    /**
     Factory method for creating interactive navigation arrows.
     */
    private Label createArrow(String iconCode, Pos align, Runnable action) {
        FontIcon icon = new FontIcon(iconCode);
        icon.setIconSize(40);
        icon.setIconColor(javafx.scene.paint.Color.WHITE);

        Label lbl = new Label();
        lbl.setGraphic(icon);

        lbl.setMaxHeight(100);
        lbl.setPrefWidth(60);
        lbl.setAlignment(align);
        lbl.setPadding(new javafx.geometry.Insets(0, 10, 0, 10));

        lbl.setCursor(Cursor.HAND);
        lbl.setOpacity(0);
        lbl.setStyle("-fx-background-color: rgba(0,0,0,0.3); -fx-background-radius: 10;");

        lbl.setOnMouseClicked(e -> {
            if (e.getClickCount() == 1 && action != null) {
                action.run();
                e.consume();
            }
            if (e.getClickCount() == 2 && onToggleDrawer != null) {
                onToggleDrawer.run();
            }
        });

        StackPane.setAlignment(lbl, align);

        if (align == Pos.CENTER_RIGHT) {
            StackPane.setMargin(lbl, new javafx.geometry.Insets(0, 10, 0, 0));
        } else {
            StackPane.setMargin(lbl, new javafx.geometry.Insets(0, 0, 0, 10));
        }

        return lbl;
    }

    /**
     Handles the transition animations for the navigation overlays.
     */
    private void animateArrow(Label arrow, boolean visible) {
        double target = visible ? 1.0 : 0.0;
        if (Math.abs(arrow.getOpacity() - target) > 0.01) {
            FadeTransition ft = new FadeTransition(Duration.millis(200), arrow);
            ft.setToValue(target);
            ft.play();
        }
    }
}