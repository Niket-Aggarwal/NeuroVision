package com.visionguide.phone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OverlayView extends View {
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint movingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dangerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Detection> detections = new ArrayList<>();
    private GuidanceEngine guidanceEngine;
    private int imageWidth = 1;
    private int imageHeight = 1;

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public OverlayView(Context context) {
        super(context);
        init();
    }

    private void init() {
        gridPaint.setColor(Color.argb(140, 80, 95, 110));
        gridPaint.setStrokeWidth(2f);

        dangerPaint.setColor(Color.rgb(255, 65, 65));
        dangerPaint.setStyle(Paint.Style.STROKE);
        dangerPaint.setStrokeWidth(6f);

        movingPaint.setColor(Color.rgb(255, 170, 70));
        movingPaint.setStyle(Paint.Style.STROKE);
        movingPaint.setStrokeWidth(5f);

        stillPaint.setColor(Color.rgb(70, 215, 170));
        stillPaint.setStyle(Paint.Style.STROKE);
        stillPaint.setStrokeWidth(5f);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(34f);
        textPaint.setFakeBoldText(true);

        textBgPaint.setColor(Color.argb(200, 16, 26, 36));
        textBgPaint.setStyle(Paint.Style.FILL);
    }

    void update(List<Detection> newDetections, GuidanceEngine engine, int sourceWidth, int sourceHeight) {
        detections.clear();
        detections.addAll(newDetections);
        guidanceEngine = engine;
        imageWidth = Math.max(1, sourceWidth);
        imageHeight = Math.max(1, sourceHeight);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        canvas.drawLine(w / 3f, 0, w / 3f, h, gridPaint);
        canvas.drawLine(w * 2f / 3f, 0, w * 2f / 3f, h, gridPaint);
        canvas.drawLine(0, h / 3f, w, h / 3f, gridPaint);
        canvas.drawLine(0, h * 2f / 3f, w, h * 2f / 3f, gridPaint);

        float scaleX = w / imageWidth;
        float scaleY = h / imageHeight;
        for (Detection detection : detections) {
            boolean moving = guidanceEngine != null && guidanceEngine.isMoving(detection, imageWidth, imageHeight);
            boolean isDanger = detection.distanceMeters <= 1.2f && detection.distanceMeters > 0f;

            Paint boxPaint = isDanger ? dangerPaint : (moving ? movingPaint : stillPaint);
            RectF mapped = new RectF(
                    detection.box.left * scaleX,
                    detection.box.top * scaleY,
                    detection.box.right * scaleX,
                    detection.box.bottom * scaleY
            );
            canvas.drawRect(mapped, boxPaint);

            String cell = GuidanceEngine.gridCellFor(detection, imageWidth, imageHeight);
            String distStr = detection.distanceMeters > 0f ? String.format(Locale.US, "%.1fm", detection.distanceMeters) : "";
            String idStr = detection.instanceNumber > 0 ? " #" + detection.instanceNumber : "";

            String motionStr = "";
            if (detection.motionDirection != null && !detection.motionDirection.isEmpty()) {
                if (detection.motionDirection.contains("left to right")) motionStr = " | → L to R";
                else if (detection.motionDirection.contains("right to left")) motionStr = " | ← R to L";
                else if (detection.motionDirection.contains("approaching")) motionStr = " | ▼ approaching";
                else if (detection.motionDirection.contains("away")) motionStr = " | ▲ moving away";
                else motionStr = " | moving";
            } else if (moving) {
                motionStr = " | moving";
            }

            String text = String.format(Locale.US, "%s%s [%s] | %s%s",
                    detection.label, idStr, distStr.isEmpty() ? "detect" : distStr, cell, motionStr);

            float textWidth = textPaint.measureText(text);
            float topY = Math.max(46f, mapped.top - 8f);
            RectF bgRect = new RectF(mapped.left, topY - 34f, mapped.left + textWidth + 16f, topY + 8f);
            canvas.drawRoundRect(bgRect, 8f, 8f, textBgPaint);
            canvas.drawText(text, mapped.left + 8f, topY, textPaint);
        }
    }
}
