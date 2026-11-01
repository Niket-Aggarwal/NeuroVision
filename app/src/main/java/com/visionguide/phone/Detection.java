package com.visionguide.phone;

import android.graphics.RectF;

final class Detection {
    final RectF box;
    final String label;
    final float confidence;
    final int classId;

    int trackId = -1;
    int instanceNumber = 1;
    float distanceMeters = 0f;
    String distanceBand = "medium distance";
    String distancePhrase = "";
    boolean isMoving = false;
    String motionDirection = "";

    Detection(RectF box, String label, float confidence, int classId) {
        this.box = box;
        this.label = label;
        this.confidence = confidence;
        this.classId = classId;
    }

    float centerX() {
        return box.centerX();
    }

    float centerY() {
        return box.centerY();
    }

    float areaRatio(float width, float height) {
        return Math.max(0f, box.width()) * Math.max(0f, box.height()) / Math.max(1f, width * height);
    }
}
