package com.visionguide.phone;

import android.content.Context;
import android.graphics.RectF;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.SizeF;

import java.util.Locale;
import java.util.Set;

final class DepthEstimator {
    private static final String TAG = "DepthEstimator";

    // Defaults for typical modern smartphone camera sensor
    private float focalLengthMm = 4.25f;
    private float sensorWidthMm = 5.60f;
    private float sensorHeightMm = 4.20f;
    private boolean isMultiCamera = false;
    private int physicalCameraCount = 1;
    private boolean hasDepthCapability = false;

    private volatile float lastFocusDiopters = 0f;
    private volatile long lastFocusTimeMs = 0;

    void init(Context context) {
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return;

            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics chars = cm.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    float[] focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    if (focals != null && focals.length > 0) {
                        focalLengthMm = focals[0];
                    }
                    SizeF size = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                    if (size != null && size.getWidth() > 0 && size.getHeight() > 0) {
                        sensorWidthMm = size.getWidth();
                        sensorHeightMm = size.getHeight();
                    }

                    int[] caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                    if (caps != null) {
                        for (int c : caps) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                                    c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                                isMultiCamera = true;
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                    c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT) {
                                hasDepthCapability = true;
                            }
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        Set<String> physical = chars.getPhysicalCameraIds();
                        if (physical != null && physical.size() > 1) {
                            isMultiCamera = true;
                            physicalCameraCount = physical.size();
                        }
                    }
                    break;
                }
            }
        } catch (Exception ignored) {
        }
    }

    void updateHardwareFocusDistance(float diopters) {
        if (diopters > 0.01f) {
            lastFocusDiopters = diopters;
            lastFocusTimeMs = SystemClock.elapsedRealtime();
        }
    }

    float getHardwareFocusDistance() {
        long now = SystemClock.elapsedRealtime();
        if (lastFocusDiopters > 0.05f && (now - lastFocusTimeMs) < 2200) {
            return 1.0f / lastFocusDiopters;
        }
        return -1f;
    }

    boolean isMultiCamera() {
        return isMultiCamera;
    }

    int getPhysicalCameraCount() {
        return physicalCameraCount;
    }

    void estimate(Detection detection, int imageWidth, int imageHeight) {
        if (detection == null || imageWidth <= 0 || imageHeight <= 0) return;

        float hReal = realHeightFor(detection.label);
        float wReal = realWidthFor(detection.label);

        float fy = (focalLengthMm / sensorHeightMm) * imageHeight;
        float fx = (focalLengthMm / sensorWidthMm) * imageWidth;

        float boxH = Math.max(10f, detection.box.height());
        float distH = (fy * hReal) / boxH;

        float dist;
        if (wReal > 0f) {
            float boxW = Math.max(10f, detection.box.width());
            float distW = (fx * wReal) / boxW;
            dist = (distH * 0.65f) + (distW * 0.35f);
        } else {
            dist = distH;
        }

        // Fuse with hardware autofocus / dual-camera depth if available
        long now = SystemClock.elapsedRealtime();
        if (lastFocusDiopters > 0.05f && (now - lastFocusTimeMs) < 1800) {
            float hwDist = 1.0f / lastFocusDiopters;
            if (hwDist >= 0.3f && hwDist <= 9.0f) {
                float cxRel = Math.abs(detection.centerX() - imageWidth / 2f) / (imageWidth / 2f);
                float cyRel = Math.abs(detection.centerY() - imageHeight / 2f) / (imageHeight / 2f);
                if (cxRel < 0.65f && cyRel < 0.65f) {
                    dist = (hwDist * 0.60f) + (dist * 0.40f);
                }
            }
        }

        // Clamp to realistic navigation distance
        dist = Math.max(0.3f, Math.min(15.0f, dist));
        dist = Math.round(dist * 10f) / 10f;

        detection.distanceMeters = dist;
        detection.distanceBand = bandFor(dist);
        detection.distancePhrase = phraseFor(dist);
    }

    private static String bandFor(float dist) {
        if (dist <= 1.1f) return "very close";
        if (dist <= 2.2f) return "close";
        if (dist <= 4.0f) return "medium distance";
        return "far";
    }

    private static String phraseFor(float dist) {
        if (dist <= 0.8f) return "under 1 meter, very close";
        if (dist <= 1.4f) return "about 1 meter away";
        if (dist <= 2.4f) return "about 2 meters away";
        if (dist <= 3.5f) return "about 3 meters away";
        return String.format(Locale.US, "about %.0f meters away", dist);
    }

    private static float realHeightFor(String label) {
        String l = label.toLowerCase(Locale.US);
        switch (l) {
            case "person": return 1.70f;
            case "car": return 1.50f;
            case "bus":
            case "truck": return 3.10f;
            case "train": return 3.60f;
            case "bicycle":
            case "motorcycle": return 1.10f;
            case "chair": return 0.85f;
            case "couch": return 0.85f;
            case "dining table": return 0.75f;
            case "bench": return 0.80f;
            case "dog":
            case "cat": return 0.45f;
            case "potted plant": return 0.65f;
            case "bottle":
            case "cup": return 0.22f;
            case "cell phone": return 0.15f;
            case "backpack":
            case "handbag":
            case "suitcase": return 0.55f;
            case "traffic light": return 0.95f;
            case "stop sign": return 0.85f;
            case "fire hydrant": return 0.75f;
            default: return 0.75f;
        }
    }

    private static float realWidthFor(String label) {
        String l = label.toLowerCase(Locale.US);
        switch (l) {
            case "car": return 1.80f;
            case "bus":
            case "truck": return 2.50f;
            case "couch": return 1.60f;
            case "dining table": return 1.30f;
            case "person": return 0.50f;
            default: return 0f;
        }
    }
}

