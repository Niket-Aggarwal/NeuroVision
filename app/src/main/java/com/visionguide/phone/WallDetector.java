package com.visionguide.phone;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class WallDetector {
    private static final String TAG = "WallDetector";
    private static final long COOLDOWN_MS = 8000L;
    private static final int REQUIRED_CONSECUTIVE_FRAMES = 3;

    private int consecutiveFrames = 0;
    private long lastTriggerTimeMs = 0;

    public static final class WallResult {
        public final boolean isWallDetected;
        public final float distanceMeters;
        public final boolean leftClear;
        public final boolean rightClear;
        public final String leftObstacle;
        public final String rightObstacle;

        public WallResult(boolean isWallDetected, float distanceMeters,
                          boolean leftClear, boolean rightClear,
                          String leftObstacle, String rightObstacle) {
            this.isWallDetected = isWallDetected;
            this.distanceMeters = distanceMeters;
            this.leftClear = leftClear;
            this.rightClear = rightClear;
            this.leftObstacle = leftObstacle;
            this.rightObstacle = rightObstacle;
        }
    }

    public static final class CorridorClearance {
        public final boolean leftClear;
        public final boolean rightClear;
        public final String leftObstacle;
        public final String rightObstacle;

        public CorridorClearance(boolean leftClear, boolean rightClear,
                                 String leftObstacle, String rightObstacle) {
            this.leftClear = leftClear;
            this.rightClear = rightClear;
            this.leftObstacle = leftObstacle;
            this.rightObstacle = rightObstacle;
        }
    }

    public void resetCooldown() {
        lastTriggerTimeMs = 0;
        consecutiveFrames = 0;
    }

    public boolean isOnCooldown(long nowMs) {
        return (nowMs - lastTriggerTimeMs) < COOLDOWN_MS;
    }

    public static CorridorClearance checkCorridors(List<Detection> detections, int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);

        List<Detection> leftThreats = new ArrayList<>();
        List<Detection> rightThreats = new ArrayList<>();

        if (detections != null) {
            for (Detection d : detections) {
                if (d == null || d.box == null) continue;
                float cx = d.centerX();
                float dist = d.distanceMeters;
                // Threat threshold: within 2.8 meters or occupies noticeable frame area
                boolean isThreat = dist <= 2.8f || d.areaRatio(w, h) >= 0.05f;
                if (!isThreat) continue;

                if (cx < w * 0.38f) {
                    leftThreats.add(d);
                } else if (cx > w * 0.62f) {
                    rightThreats.add(d);
                }
            }
        }

        boolean leftClear = leftThreats.isEmpty();
        boolean rightClear = rightThreats.isEmpty();

        String leftObs = null;
        if (!leftClear) {
            Detection closest = nearest(leftThreats);
            leftObs = closest != null ? closest.label : "obstacle";
        }

        String rightObs = null;
        if (!rightClear) {
            Detection closest = nearest(rightThreats);
            rightObs = closest != null ? closest.label : "obstacle";
        }

        return new CorridorClearance(leftClear, rightClear, leftObs, rightObs);
    }

    public WallResult evaluate(Bitmap bitmap, List<Detection> detections, float hwFocusDist, long nowMs) {
        // If user moved away (focus distance > 2.6m), reset cooldown so new walls are detected immediately
        if (hwFocusDist > 2.6f) {
            lastTriggerTimeMs = 0;
        }

        if (isOnCooldown(nowMs)) {
            return null;
        }

        int width = bitmap != null ? bitmap.getWidth() : 640;
        int height = bitmap != null ? bitmap.getHeight() : 480;

        // Check if there are active dynamic moving objects like vehicle or person in center
        // (those are handled by the main obstacle alert system)
        boolean dynamicCenterObstacle = false;
        if (detections != null) {
            for (Detection d : detections) {
                if (d == null || d.box == null) continue;
                float cx = d.centerX();
                if (cx >= width * 0.35f && cx <= width * 0.65f) {
                    String lbl = d.label != null ? d.label.toLowerCase(Locale.ROOT) : "";
                    if (lbl.equals("person") || lbl.equals("car") || lbl.equals("truck") ||
                        lbl.equals("bus") || lbl.equals("motorcycle") || lbl.equals("bicycle")) {
                        dynamicCenterObstacle = true;
                        break;
                    }
                }
            }
        }

        if (dynamicCenterObstacle) {
            consecutiveFrames = 0;
            return null;
        }

        boolean hardwareDepthWall = (hwFocusDist >= 0.35f && hwFocusDist <= 2.1f);
        boolean visualPlanarWall = checkVisualPlanarSurface(bitmap, width, height);

        // Wall is detected if hardware autofocus locks on a barrier within 2.1m,
        // or if visual planar texture indicates a solid partition directly ahead
        boolean candidate = hardwareDepthWall || (visualPlanarWall && hwFocusDist <= 2.5f);

        if (candidate) {
            consecutiveFrames++;
            if (consecutiveFrames >= REQUIRED_CONSECUTIVE_FRAMES) {
                lastTriggerTimeMs = nowMs;
                consecutiveFrames = 0;

                float dist = (hwFocusDist >= 0.35f && hwFocusDist <= 2.5f)
                        ? (Math.round(hwFocusDist * 10f) / 10f)
                        : 1.2f;

                CorridorClearance clearance = checkCorridors(detections, width, height);
                return new WallResult(true, dist, clearance.leftClear, clearance.rightClear,
                        clearance.leftObstacle, clearance.rightObstacle);
            }
        } else {
            consecutiveFrames = Math.max(0, consecutiveFrames - 1);
        }

        return null;
    }

    private boolean checkVisualPlanarSurface(Bitmap bitmap, int width, int height) {
        if (bitmap == null || width < 20 || height < 20) return false;

        try {
            // Sample 8x8 grid (64 points) across the center 50% region of the frame
            int startX = (int) (width * 0.25f);
            int endX = (int) (width * 0.75f);
            int startY = (int) (height * 0.25f);
            int endY = (int) (height * 0.75f);

            int stepX = Math.max(1, (endX - startX) / 8);
            int stepY = Math.max(1, (endY - startY) / 8);

            double sumLum = 0.0;
            double sumSqLum = 0.0;
            int count = 0;

            for (int y = startY; y < endY; y += stepY) {
                for (int x = startX; x < endX; x += stepX) {
                    int pixel = bitmap.getPixel(x, y);
                    int r = Color.red(pixel);
                    int g = Color.green(pixel);
                    int b = Color.blue(pixel);
                    // Perceived luminance
                    double lum = 0.299 * r + 0.587 * g + 0.114 * b;
                    sumLum += lum;
                    sumSqLum += lum * lum;
                    count++;
                }
            }

            if (count > 0) {
                double mean = sumLum / count;
                double variance = (sumSqLum / count) - (mean * mean);
                // Planar walls/partitions have moderate-to-low localized variance and uniform surface lighting
                // avoiding high-contrast distant street horizons
                return variance < 1600.0 && mean > 25.0 && mean < 240.0;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static Detection nearest(List<Detection> list) {
        if (list == null || list.isEmpty()) return null;
        Detection best = list.get(0);
        for (int i = 1; i < list.size(); i++) {
            Detection d = list.get(i);
            if (d.distanceMeters < best.distanceMeters) {
                best = d;
            }
        }
        return best;
    }
}

