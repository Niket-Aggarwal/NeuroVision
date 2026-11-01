package com.visionguide.phone;

import android.content.Context;
import android.content.SharedPreferences;

final class AppSettings {
    private static final String PREFS = "via_lux_settings";
    public static final String DEFAULT_GEMINI_KEY = BuildConfig.DEFAULT_GEMINI_KEY;

    private final SharedPreferences prefs;

    int speechDelayMs;
    int repeatCooldownMs;
    float motionThreshold;
    int motionStreakFrames;
    float notifyAreaRatio;
    float edgeIgnoreRatio;
    boolean priorityOverride;

    String emergencyContactName;
    String emergencyContactPhone;
    String geminiApiKey;

    AppSettings(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    void load() {
        speechDelayMs = prefs.getInt("speechDelayMs", 2000);
        repeatCooldownMs = prefs.getInt("repeatCooldownMs", 10000);
        motionThreshold = prefs.getFloat("motionThreshold", 0.040f);
        motionStreakFrames = prefs.getInt("motionStreakFrames", 3);
        notifyAreaRatio = prefs.getFloat("notifyAreaRatio", 0.035f);
        edgeIgnoreRatio = prefs.getFloat("edgeIgnoreRatio", 0.18f);
        priorityOverride = prefs.getBoolean("priorityOverride", true);

        emergencyContactName = prefs.getString("emergencyContactName", "");
        emergencyContactPhone = prefs.getString("emergencyContactPhone", "");
        geminiApiKey = prefs.getString("geminiApiKey", DEFAULT_GEMINI_KEY);
    }

    void save() {
        prefs.edit()
                .putInt("speechDelayMs", speechDelayMs)
                .putInt("repeatCooldownMs", repeatCooldownMs)
                .putFloat("motionThreshold", motionThreshold)
                .putInt("motionStreakFrames", motionStreakFrames)
                .putFloat("notifyAreaRatio", notifyAreaRatio)
                .putFloat("edgeIgnoreRatio", edgeIgnoreRatio)
                .putBoolean("priorityOverride", priorityOverride)
                .putString("emergencyContactName", emergencyContactName)
                .putString("emergencyContactPhone", emergencyContactPhone)
                .putString("geminiApiKey", getGeminiApiKey())
                .apply();
    }

    void saveEmergencyContact(String name, String phone) {
        this.emergencyContactName = name != null ? name.trim() : "";
        this.emergencyContactPhone = phone != null ? phone.trim() : "";
        prefs.edit()
                .putString("emergencyContactName", this.emergencyContactName)
                .putString("emergencyContactPhone", this.emergencyContactPhone)
                .apply();
    }

    boolean hasEmergencyContact() {
        return emergencyContactPhone != null && !emergencyContactPhone.trim().isEmpty();
    }

    void saveGeminiApiKey(String key) {
        if (key != null && !key.trim().isEmpty()) {
            this.geminiApiKey = key.trim();
        } else {
            this.geminiApiKey = DEFAULT_GEMINI_KEY;
        }
        prefs.edit().putString("geminiApiKey", this.geminiApiKey).apply();
    }

    String getGeminiApiKey() {
        if (geminiApiKey == null || geminiApiKey.trim().isEmpty()) {
            return DEFAULT_GEMINI_KEY;
        }
        return geminiApiKey.trim();
    }

    void resetDefaults() {
        speechDelayMs = 2000;
        repeatCooldownMs = 10000;
        motionThreshold = 0.040f;
        motionStreakFrames = 3;
        notifyAreaRatio = 0.035f;
        edgeIgnoreRatio = 0.18f;
        priorityOverride = true;
        save();
    }

    String notifyDistanceLabel() {
        if (notifyAreaRatio >= 0.25f) return "Very close only";
        if (notifyAreaRatio >= 0.10f) return "Close or nearer";
        if (notifyAreaRatio >= 0.035f) return "Medium or nearer";
        return "Far or nearer";
    }

    String motionLabel() {
        if (motionThreshold >= 0.055f) return "Strong motion only";
        if (motionThreshold <= 0.025f) return "Sensitive";
        return "Balanced";
    }
}
