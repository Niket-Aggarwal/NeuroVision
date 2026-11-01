package com.visionguide.phone;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

final class SpeechGuide implements TextToSpeech.OnInitListener {
    public interface SpeechListener {
        void onSpeechStarted();
        void onSpeechFinished();
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ready = false;
    private String lastMessage = "";
    private long lastStartedAt = 0;
    private volatile boolean muted = false;
    private SpeechListener speechListener;

    // Content reading state & buffered cautions
    private volatile boolean isReadingContent = false;
    private volatile String pendingCautionMessage = null;
    private volatile int pendingCautionPriority = Guidance.PRIORITY_INFO;
    private volatile boolean pendingCautionEmergency = false;

    SpeechGuide(Context context) {
        tts = new TextToSpeech(context.getApplicationContext(), this);
    }

    void setSpeechListener(SpeechListener listener) {
        this.speechListener = listener;
    }

    @Override
    public void onInit(int status) {
        ready = status == TextToSpeech.SUCCESS;
        if (ready) {
            tts.setLanguage(Locale.US);
            tts.setSpeechRate(1.05f);
            tts.setPitch(1.0f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    if (speechListener != null) speechListener.onSpeechStarted();
                }

                @Override
                public void onDone(String utteranceId) {
                    if (utteranceId != null && utteranceId.startsWith("reading-content-")) {
                        isReadingContent = false;
                        final String caution = pendingCautionMessage;
                        final boolean emergency = pendingCautionEmergency;
                        final int priority = pendingCautionPriority;

                        pendingCautionMessage = null;
                        pendingCautionEmergency = false;
                        pendingCautionPriority = Guidance.PRIORITY_INFO;

                        // After reading finishes completely, announce any caution that occurred during reading
                        if (caution != null && !caution.trim().isEmpty()) {
                            mainHandler.postDelayed(() -> {
                                if (!muted && ready && tts != null && !isReadingContent) {
                                    speak("Caution: " + caution, emergency, priority);
                                }
                            }, 350);
                        }
                    }
                    if (speechListener != null) speechListener.onSpeechFinished();
                }

                @Override
                public void onError(String utteranceId) {
                    if (utteranceId != null && utteranceId.startsWith("reading-content-")) {
                        isReadingContent = false;
                        pendingCautionMessage = null;
                    }
                    if (speechListener != null) speechListener.onSpeechFinished();
                }
            });
        }
    }

    boolean isSpeaking() {
        return tts != null && tts.isSpeaking();
    }

    boolean isReadingContent() {
        return isReadingContent;
    }

    void setMuted(boolean muted) {
        this.muted = muted;
        if (muted && tts != null) {
            isReadingContent = false;
            pendingCautionMessage = null;
            tts.stop();
        }
    }

    boolean isMuted() {
        return muted;
    }

    void stop() {
        isReadingContent = false;
        pendingCautionMessage = null;
        if (tts != null) {
            tts.stop();
        }
    }

    /**
     * Speaks reading material (OCR document, text, prescription, Gemini explanation)
     * without allowing normal obstacle cautions to interrupt mid-speech.
     */
    void speakReading(String message) {
        if (muted || !ready || tts == null || message == null || message.trim().isEmpty()) {
            return;
        }
        isReadingContent = true;
        pendingCautionMessage = null;
        pendingCautionEmergency = false;
        pendingCautionPriority = Guidance.PRIORITY_INFO;
        lastMessage = message;
        lastStartedAt = System.currentTimeMillis();

        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "reading-content-" + System.nanoTime());
    }

    void setPendingCaution(String message, boolean emergency, int priority) {
        if (message == null || message.trim().isEmpty()) return;
        // Clean prefix if already present
        String clean = message.startsWith("Caution:") ? message.substring(8).trim() : message;
        if (pendingCautionMessage == null || priority >= pendingCautionPriority || emergency) {
            this.pendingCautionMessage = clean;
            this.pendingCautionEmergency = emergency;
            this.pendingCautionPriority = priority;
        }
    }

    void speak(String message, boolean emergency, int priority) {
        if (muted || !ready || tts == null) {
            return;
        }

        // When reading content, do not interrupt! Buffer the caution to speak right after reading completes.
        if (isReadingContent) {
            if (emergency || priority >= Guidance.PRIORITY_URGENT) {
                setPendingCaution(message, emergency, priority);
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (message.equals(lastMessage) && now - lastStartedAt < 8000) {
            return;
        }
        if (tts.isSpeaking() && !emergency && priority < Guidance.PRIORITY_URGENT) {
            return;
        }
        lastMessage = message;
        lastStartedAt = now;
        int mode = emergency || priority >= Guidance.PRIORITY_URGENT
                ? TextToSpeech.QUEUE_FLUSH
                : TextToSpeech.QUEUE_ADD;
        tts.speak(message, mode, null, "vision-guide-" + System.nanoTime());
    }

    void shutdown() {
        if (tts != null) {
            isReadingContent = false;
            pendingCautionMessage = null;
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }
}
