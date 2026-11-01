package com.visionguide.phone;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

public final class WakeWordListener {
    public interface WakeWordCallback {
        void onWakeWordDetected(String trailingCommand);
    }

    private final Context context;
    private final WakeWordCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private boolean isListening = false;
    private boolean isPaused = false;
    private boolean isDestroyed = false;
    private long lastTriggerTime = 0;

    public WakeWordListener(Context context, WakeWordCallback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        initRecognizer();
    }

    private void initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return;
        }

        try {
            if (recognizer != null) {
                try {
                    recognizer.cancel();
                    recognizer.destroy();
                } catch (Exception ignored) {
                }
                recognizer = null;
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    isListening = true;
                }

                @Override
                public void onBeginningOfSpeech() {}

                @Override
                public void onRmsChanged(float rmsdB) {}

                @Override
                public void onBufferReceived(byte[] buffer) {}

                @Override
                public void onEndOfSpeech() {
                    isListening = false;
                    scheduleRestart(1000);
                }

                @Override
                public void onError(int error) {
                    isListening = false;
                    if (isPaused || isDestroyed) {
                        return;
                    }

                    // On client or busy errors, wait a bit longer to prevent tight retry loops
                    if (error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_SERVER ||
                        error == SpeechRecognizer.ERROR_AUDIO) {
                        mainHandler.removeCallbacksAndMessages(null);
                        mainHandler.postDelayed(() -> {
                            if (!isPaused && !isDestroyed) {
                                initRecognizer();
                                start();
                            }
                        }, 1500);
                    } else {
                        scheduleRestart(1200);
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    isListening = false;
                    if (checkMatches(results)) {
                        return;
                    }
                    scheduleRestart(800);
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    if (checkMatches(partialResults)) {
                        stop();
                    }
                }

                @Override
                public void onEvent(int eventType, Bundle params) {}
            });
        } catch (Exception ignored) {
        }
    }

    private boolean checkMatches(Bundle bundle) {
        if (bundle == null || isPaused || isDestroyed) return false;
        long now = System.currentTimeMillis();
        if (now - lastTriggerTime < 5000) return false;

        ArrayList<String> matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) return false;

        for (String match : matches) {
            if (match == null) continue;
            String text = match.trim();
            if (isWakeWordMatch(text)) {
                lastTriggerTime = now;
                String trailing = extractWakeWordTrailing(text);
                triggerWakeWord(trailing);
                return true;
            }
        }
        return false;
    }

    private boolean isWakeWordMatch(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.ROOT).trim();

        // Require intentional wake phrases
        String[] validPhrases = new String[]{
                "hello vision", "hey vision", "hi vision", "ok vision",
                "hello neuro", "hey neuro", "hi neuro", "ok neuro",
                "vision guide", "neuro vision", "neurovision"
        };
        for (String phrase : validPhrases) {
            if (t.contains(phrase)) {
                return true;
            }
        }

        // Direct command addressing starting with Vision or Neuro
        if (t.startsWith("vision ") || t.startsWith("neuro ") || t.equals("vision") || t.equals("neuro")) {
            return true;
        }

        return false;
    }

    private String extractWakeWordTrailing(String raw) {
        if (raw == null) return "";
        String t = raw.toLowerCase(Locale.ROOT).trim();
        String[] prefixes = new String[]{
                "hello vision", "hey vision", "hi vision", "ok vision",
                "hello neuro", "hey neuro", "hi neuro", "ok neuro",
                "vision guide", "neuro vision", "neurovision",
                "vision", "neuro"
        };
        for (String p : prefixes) {
            int idx = t.indexOf(p);
            if (idx >= 0) {
                String after = raw.substring(idx + p.length()).trim();
                while (after.startsWith(",") || after.startsWith(".") || after.startsWith("!") || after.startsWith("?") || after.startsWith("-")) {
                    after = after.substring(1).trim();
                }
                return after;
            }
        }
        return "";
    }

    private void triggerWakeWord(String trailing) {
        stop();
        mainHandler.post(() -> {
            if (callback != null && !isPaused && !isDestroyed) {
                callback.onWakeWordDetected(trailing);
            }
        });
    }

    public void start() {
        if (isDestroyed || isPaused) return;

        mainHandler.post(() -> {
            if (isDestroyed || isPaused) return;

            try {
                if (recognizer == null) {
                    initRecognizer();
                }
                if (recognizer == null) return;

                try {
                    recognizer.cancel();
                } catch (Exception ignored) {
                }

                isListening = false;

                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
                intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
                intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());

                recognizer.startListening(intent);
                isListening = true;
            } catch (Exception e) {
                isListening = false;
                scheduleRestart(1500);
            }
        });
    }

    public void stop() {
        isListening = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    public void pause() {
        isPaused = true;
        stop();
    }

    public void resume() {
        isPaused = false;
        scheduleRestart(800);
    }

    private void scheduleRestart(long delayMs) {
        if (isPaused || isDestroyed) return;
        mainHandler.removeCallbacksAndMessages(null);
        mainHandler.postDelayed(() -> {
            if (!isPaused && !isDestroyed && !isListening) {
                start();
            }
        }, delayMs);
    }

    public void destroy() {
        isDestroyed = true;
        isPaused = true;
        stop();
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
    }
}
