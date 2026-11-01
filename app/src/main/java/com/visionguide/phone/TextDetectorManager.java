package com.visionguide.phone;

import android.graphics.Bitmap;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.concurrent.atomic.AtomicBoolean;

public final class TextDetectorManager {
    public interface TextDetectionCallback {
        void onStableTextDetected(String fullText, String previewText);
    }

    private final TextRecognizer recognizer;
    private final TextDetectionCallback callback;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    private long lastProcessTime = 0;
    private String lastDetectedText = "";
    private long firstSeenTime = 0;
    private String lastPromptedText = "";
    private long lastPromptTime = 0;

    public TextDetectorManager(TextDetectionCallback callback) {
        this.callback = callback;
        this.recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    public void processFrame(Bitmap bitmap, long now) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }

        // Run OCR approximately every 450ms to maintain real-time 60fps performance
        if (now - lastProcessTime < 450 || !isProcessing.compareAndSet(false, true)) {
            return;
        }
        lastProcessTime = now;

        try {
            final Bitmap ocrBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (ocrBitmap == null) {
                isProcessing.set(false);
                return;
            }
            InputImage image = InputImage.fromBitmap(ocrBitmap, 0);
            recognizer.process(image)
                    .addOnSuccessListener(visionText -> {
                        isProcessing.set(false);
                        try {
                            ocrBitmap.recycle();
                        } catch (Exception ignored) {
                        }
                        analyzeDetectedText(visionText, now);
                    })
                    .addOnFailureListener(e -> {
                        isProcessing.set(false);
                        try {
                            ocrBitmap.recycle();
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception e) {
            isProcessing.set(false);
        }
    }

    public String getLastDetectedText() {
        return lastDetectedText;
    }

    private void analyzeDetectedText(Text visionText, long now) {
        if (visionText == null) return;
        String text = extractCleanText(visionText);

        // Require at least 3 readable characters
        if (text.length() < 3) {
            firstSeenTime = 0;
            lastDetectedText = "";
            return;
        }

        String normalized = text.replaceAll("\\s+", " ").trim();

        if (isSimilar(normalized, lastDetectedText)) {
            if (firstSeenTime == 0) {
                firstSeenTime = now;
            } else {
                long durationNotMoving = now - firstSeenTime;
                // Text has been visible and not moving for more than 2 seconds (2000 ms)
                if (durationNotMoving >= 2000) {
                    if (!isSimilar(normalized, lastPromptedText) || now - lastPromptTime > 25000) {
                        lastPromptedText = normalized;
                        lastPromptTime = now;

                        // Create a concise preview for the prompt (e.g. first line or first 5-6 words)
                        String preview = generatePreview(normalized);
                        if (callback != null) {
                            callback.onStableTextDetected(normalized, preview);
                        }
                    }
                }
            }
        } else {
            // Text changed or moved
            lastDetectedText = normalized;
            firstSeenTime = now;
        }
    }

    private String extractCleanText(Text visionText) {
        if (visionText == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            if (block == null) continue;
            String blockText = block.getText().trim();
            // Filter out single punctuation or noise artifacts
            if (blockText.length() >= 2 && !blockText.matches("^[|._~\\-+*=]+$")) {
                if (sb.length() > 0) sb.append(". ");
                sb.append(blockText);
            }
        }
        return sb.length() > 0 ? sb.toString() : visionText.getText().trim();
    }

    private String generatePreview(String text) {
        String[] words = text.split(" ");
        if (words.length <= 5) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, words.length); i++) {
            if (i > 0) sb.append(" ");
            sb.append(words[i]);
        }
        return sb.toString();
    }

    private boolean isSimilar(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equalsIgnoreCase(b)) return true;
        if (a.isEmpty() || b.isEmpty()) return false;

        // Check if one contains the other
        if (a.contains(b) || b.contains(a)) {
            int minLen = Math.min(a.length(), b.length());
            int maxLen = Math.max(a.length(), b.length());
            return ((float) minLen / (float) maxLen) > 0.65f;
        }

        // Word overlap ratio
        String[] wordsA = a.toLowerCase().split("\\s+");
        String[] wordsB = b.toLowerCase().split("\\s+");
        int matchCount = 0;
        for (String wa : wordsA) {
            for (String wb : wordsB) {
                if (wa.equals(wb)) {
                    matchCount++;
                    break;
                }
            }
        }
        float ratio = (float) matchCount / (float) Math.max(wordsA.length, wordsB.length);
        return ratio >= 0.65f;
    }

    public void resetCooldown() {
        lastPromptedText = "";
        lastPromptTime = 0;
        firstSeenTime = 0;
    }

    public void close() {
        try {
            recognizer.close();
        } catch (Exception ignored) {
        }
    }
}

