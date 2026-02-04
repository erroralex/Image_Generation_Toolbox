package com.nilsson.imagetoolbox.ui.components;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.util.Duration;

import java.util.List;

class AnimatedImage extends WritableImage {

    private final Timeline timeline;
    private int index = 0;

    AnimatedImage(List<Image> frames, int frameMillis) {
        super((int) frames.get(0).getWidth(),
                (int) frames.get(0).getHeight());

        timeline = new Timeline(new KeyFrame(
                Duration.millis(frameMillis),
                e -> {
                    getPixelWriter().setPixels(
                            0, 0,
                            (int) frames.get(index).getWidth(),
                            (int) frames.get(index).getHeight(),
                            frames.get(index).getPixelReader(),
                            0, 0
                    );
                    index = (index + 1) % frames.size();
                }
        ));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }
}
