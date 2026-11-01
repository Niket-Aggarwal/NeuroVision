package com.visionguide.phone;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GeminiVoiceAssistant {
    private static final String[] GEMINI_MODELS = new String[] {
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-3.8-flash",
            "gemini-flash-latest"
    };

    public enum Action {
        NAVIGATE,
        START_CAMERA,
        STOP_CAMERA,
        DESCRIBE_SCENE,
        NAV_NEXT_STEP,
        NAV_REPEAT,
        NAV_CANCEL,
        OPEN_SETTINGS,
        STATUS,
        ACTIVATE_EMERGENCY,
        CANCEL_EMERGENCY,
        FEED_CONTACT,
        UPDATE_SETTING,
        SPEAK_INFO,
        CONFIRM_READ,
        DISMISS_READ,
        MOVE_LEFT,
        MOVE_RIGHT,
        WALL_CHECK
    }

    public static final class AssistantResult {
        public final Action action;
        public final String spokenText;
        public final String payload;

        public AssistantResult(Action action, String spokenText, String payload) {
            this.action = action;
            this.spokenText = spokenText;
            this.payload = payload;
        }
    }

    private final Context context;
    private final AppSettings settings;

    public GeminiVoiceAssistant(Context context, AppSettings settings) {
        this.context = context.getApplicationContext();
        this.settings = settings;
    }

    public String getModelStatusDescription() {
        return "Google Gemini 3.5 Flash Active";
    }

    public AssistantResult interpret(String rawQuery, boolean cameraActive, boolean navigating,
                                     String destination, NavStep currentStep,
                                     List<Detection> currentDetections, int frameWidth, int frameHeight) {
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            return new AssistantResult(Action.SPEAK_INFO, "I did not hear anything. Please speak your command or question.", null);
        }

        String query = rawQuery.toLowerCase(Locale.ROOT).trim();

        // === PRIORITY COMMANDS (Start, Stop, Emergency, Confirmation) ===
        // 1. Emergency Commands
        if (query.contains("stop emergency") || query.contains("cancel emergency") ||
            query.contains("end emergency") || query.contains("deactivate emergency") || query.contains("turn off emergency")) {
            return new AssistantResult(Action.CANCEL_EMERGENCY, "Emergency protocol deactivated.", null);
        }

        if (query.contains("activate emergency") || query.contains("emergency mode") ||
            query.contains("call emergency") || query.contains("emergency call") ||
            query.contains("trigger emergency") || query.contains("sos") ||
            query.equals("emergency") || query.contains("help me emergency") || query.equals("help")) {
            return new AssistantResult(Action.ACTIVATE_EMERGENCY, "Activating emergency mode.", null);
        }

        // 2. Immediate Start / Stop Controls
        if (query.equals("start") || query.contains("start camera") || query.contains("start detection") ||
            query.contains("turn on camera") || query.contains("open camera") || query.contains("resume scanning") || query.contains("resume")) {
            return new AssistantResult(Action.START_CAMERA, "Starting camera scanner.", null);
        }

        if (query.contains("stop navigation") || query.contains("cancel navigation") || query.contains("stop the navigation") ||
            query.contains("stop navigating") || query.contains("end navigation") || query.contains("stop route") || query.contains("stop following")) {
            return new AssistantResult(Action.NAV_CANCEL, "Navigation stopped.", null);
        }

        if (query.equals("stop") || query.contains("stop camera") || query.contains("pause camera") ||
            query.contains("turn off camera") || query.contains("stop scanning") || query.contains("stop detection") || query.equals("pause")) {
            if (navigating) {
                return new AssistantResult(Action.NAV_CANCEL, "Navigation stopped.", null);
            }
            return new AssistantResult(Action.STOP_CAMERA, "Camera paused. Going to standby.", null);
        }

        // 3. Wall Avoidance & Directional Navigation Responses
        if (query.equals("left") || query.equals("go left") || query.equals("turn left") ||
            query.equals("move left") || query.contains("to the left") || query.contains("take left") ||
            query.contains("step left")) {
            return new AssistantResult(Action.MOVE_LEFT, "Left selected.", null);
        }

        if (query.equals("right") || query.equals("go right") || query.equals("turn right") ||
            query.equals("move right") || query.contains("to the right") || query.contains("take right") ||
            query.contains("step right")) {
            return new AssistantResult(Action.MOVE_RIGHT, "Right selected.", null);
        }

        if (query.contains("where to go") || query.contains("which way") || query.contains("which side") ||
            query.contains("where should i move") || query.contains("where to move") || query.contains("what should i do")) {
            return new AssistantResult(Action.WALL_CHECK, "Checking path clearance.", null);
        }

        // 4. OCR / NFC Reading Confirmations & Direct Requests
        if (query.equals("yes") || query.contains("read it") || query.equals("read") ||
            query.contains("read text") || query.contains("read medicine") || query.contains("read out loud") ||
            query.contains("yes read") || query.contains("sure") || query.contains("please read") ||
            query.contains("what does it say") || query.contains("what is written") || query.contains("read the text") ||
            query.contains("read this") || query.contains("read that") || query.contains("read this text") ||
            query.contains("read that text") || query.contains("read medicine tag") || query.contains("check medicine") ||
            query.contains("read document") || query.contains("read paper") || query.contains("read sign") ||
            query.contains("read signboard") || query.contains("read book") || query.contains("read label")) {
            return new AssistantResult(Action.CONFIRM_READ, "Reading now.", null);
        }

        if (query.equals("no") || query.contains("don't read") || query.contains("do not read") ||
            query.contains("cancel") || query.contains("dismiss") || query.contains("skip") || query.contains("no thanks")) {
            return new AssistantResult(Action.DISMISS_READ, "Dismissed.", null);
        }

        // 4. Settings Manipulation via Voice Commands
        if (query.contains("reset settings") || query.contains("default settings") || query.contains("restore settings")) {
            settings.resetDefaults();
            return new AssistantResult(Action.UPDATE_SETTING, "Settings have been reset to default values.", null);
        }

        if (query.contains("read settings") || query.contains("what are my settings") || query.contains("current settings")) {
            String summary = "Current settings: Speech delay is " + (settings.speechDelayMs / 1000f) + " seconds. " +
                    "Repeat cooldown is " + (settings.repeatCooldownMs / 1000) + " seconds. " +
                    "Motion sensitivity is " + settings.motionLabel() + ". " +
                    "Notify distance is " + settings.notifyDistanceLabel() + ". " +
                    "Priority override is " + (settings.priorityOverride ? "enabled." : "disabled.");
            return new AssistantResult(Action.SPEAK_INFO, summary, null);
        }

        if (query.contains("speech delay") || query.contains("speech interval")) {
            int sec = extractNumber(query);
            if (sec > 0) {
                sec = Math.max(1, Math.min(5, sec));
                settings.speechDelayMs = sec * 1000;
                settings.save();
                return new AssistantResult(Action.UPDATE_SETTING, "Speech delay set to " + sec + " seconds.", null);
            }
        }

        if (query.contains("repeat cooldown") || query.contains("repeat delay") || query.contains("repeat same object")) {
            int sec = extractNumber(query);
            if (sec > 0) {
                sec = Math.max(5, Math.min(30, sec));
                settings.repeatCooldownMs = sec * 1000;
                settings.save();
                return new AssistantResult(Action.UPDATE_SETTING, "Repeat delay set to " + sec + " seconds.", null);
            }
        }

        if (query.contains("motion") && (query.contains("sensitive") || query.contains("strong") || query.contains("balanced") || query.contains("high") || query.contains("low"))) {
            if (query.contains("sensitive") || query.contains("high")) {
                settings.motionThreshold = 0.025f;
                settings.motionStreakFrames = 2;
                settings.save();
                return new AssistantResult(Action.UPDATE_SETTING, "Motion priority set to sensitive.", null);
            } else if (query.contains("strong") || query.contains("low")) {
                settings.motionThreshold = 0.060f;
                settings.motionStreakFrames = 3;
                settings.save();
                return new AssistantResult(Action.UPDATE_SETTING, "Motion priority set to strong motion only.", null);
            } else if (query.contains("balanced")) {
                settings.motionThreshold = 0.040f;
                settings.motionStreakFrames = 3;
                settings.save();
                return new AssistantResult(Action.UPDATE_SETTING, "Motion priority set to balanced.", null);
            }
        }

        if ((query.contains("notify distance") || query.contains("detection distance") || query.contains("alert distance")) ||
            (query.contains("distance") && (query.contains("close") || query.contains("far") || query.contains("medium")))) {
            if (query.contains("very close")) {
                settings.notifyAreaRatio = 0.25f;
                settings.save();
                return new AssistantResult(Action.UPDATE_SETTING, "Notify distance set to very close only.", null);
            } else if (query.contains("close")) {
                settings.notifyAreaRatio = 0.10f;
                settings.save();
                return new AssistantResult(Action.UPDATE_SETTING, "Notify distance set to close or nearer.", null);
            } else if (query.contains("medium")) {
                settings.notifyAreaRatio = 0.035f;
                settings.save();
                return new AssistantResult(Action.UPDATE_SETTING, "Notify distance set to medium or nearer.", null);
            } else if (query.contains("far")) {
                settings.notifyAreaRatio = 0.015f;
                settings.save();
                return new AssistantResult(Action.UPDATE_SETTING, "Notify distance set to far or nearer.", null);
            }
        }

        if (query.contains("priority override")) {
            if (query.contains("enable") || query.contains("turn on") || query.contains("activate") || query.contains("yes")) {
                settings.priorityOverride = true;
                settings.save();
                return new AssistantResult(Action.UPDATE_SETTING, "Priority override enabled.", null);
            } else if (query.contains("disable") || query.contains("turn off") || query.contains("deactivate") || query.contains("no")) {
                settings.priorityOverride = false;
                settings.save();
                return new AssistantResult(Action.UPDATE_SETTING, "Priority override disabled.", null);
            }
        }

        if (query.contains("api key") || query.contains("gemini key")) {
            String extractedKey = extractApiKey(rawQuery);
            if (extractedKey != null && !extractedKey.isEmpty()) {
                settings.saveGeminiApiKey(extractedKey);
                return new AssistantResult(Action.UPDATE_SETTING, "Gemini API key updated successfully.", null);
            }
        }

        // 1. Emergency Mode & Emergency Contact Commands
        if (query.contains("activate emergency") || query.contains("emergency mode") ||
            query.contains("call emergency") || query.contains("emergency call") ||
            query.contains("trigger emergency") || query.contains("sos") ||
            query.equals("emergency") || query.contains("help me emergency")) {
            return new AssistantResult(Action.ACTIVATE_EMERGENCY, "Activating emergency mode.", null);
        }

        if (query.contains("feed contact") || query.contains("add contact") ||
            query.contains("save contact") || query.contains("set contact") ||
            query.contains("emergency contact") || query.contains("new contact") ||
            query.contains("edit contact")) {
            return new AssistantResult(Action.FEED_CONTACT, "Opening screen to feed emergency contact.", null);
        }

        // 2. Interactive Scene & "What is in front of me?" Questions
        if (isSceneQuery(query)) {
            if (!cameraActive) {
                return new AssistantResult(Action.START_CAMERA,
                        "The camera is currently stopped. Starting camera now to analyze what is in front of you.", null);
            }

            // Try Google Gemini Cloud Vision & Voice LLM first
            String systemContext = buildSystemContext(currentDetections, frameWidth, frameHeight, navigating, destination, currentStep);
            String geminiAnswer = queryGeminiModel(rawQuery, systemContext);
            if (geminiAnswer != null && !geminiAnswer.isEmpty()) {
                return new AssistantResult(Action.SPEAK_INFO, geminiAnswer, null);
            }

            // Fallback to on-device spatial reasoning engine if offline or network unavailable
            String interactiveAnswer = generateInteractiveSpatialAnswer(query, currentDetections, frameWidth, frameHeight);
            return new AssistantResult(Action.SPEAK_INFO, interactiveAnswer, null);
        }

        // 3. Navigation Intent Extraction
        if (query.startsWith("navigate to ") || query.startsWith("directions to ") ||
            query.startsWith("take me to ") || query.startsWith("go to ") ||
            query.startsWith("walk to ") || query.startsWith("guide me to ")) {

            String dest = "";
            if (query.startsWith("navigate to ")) dest = rawQuery.substring("navigate to ".length()).trim();
            else if (query.startsWith("directions to ")) dest = rawQuery.substring("directions to ".length()).trim();
            else if (query.startsWith("take me to ")) dest = rawQuery.substring("take me to ".length()).trim();
            else if (query.startsWith("go to ")) dest = rawQuery.substring("go to ".length()).trim();
            else if (query.startsWith("walk to ")) dest = rawQuery.substring("walk to ".length()).trim();
            else if (query.startsWith("guide me to ")) dest = rawQuery.substring("guide me to ".length()).trim();

            if (!dest.isEmpty()) {
                return new AssistantResult(Action.NAVIGATE, "Finding walking directions to " + dest + " with obstacle awareness.", dest);
            }
        }

        // 4. Camera Controls
        if (query.equals("start") || query.contains("start camera") || query.contains("scan") ||
            query.contains("turn on camera") || query.contains("open camera") || query.contains("resume scanning")) {
            return new AssistantResult(Action.START_CAMERA, "Starting camera scanner.", null);
        }

        if (query.equals("stop") || query.contains("stop camera") || query.contains("pause camera") ||
            query.contains("turn off camera") || query.contains("stop scanning")) {
            return new AssistantResult(Action.STOP_CAMERA, "Camera paused. Going to standby.", null);
        }

        // 5. Navigation Flow Controls
        if (query.contains("next step") || query.contains("what is next") || query.contains("next turn")) {
            return new AssistantResult(Action.NAV_NEXT_STEP, "Fetching next step.", null);
        }

        if (query.contains("repeat") || query.contains("say again") || query.contains("where was i")) {
            return new AssistantResult(Action.NAV_REPEAT, "Repeating last direction.", null);
        }

        if ((query.contains("stop") && (query.contains("navigat") || query.contains("route") || query.contains("direction") || query.contains("path") || query.contains("guidance"))) ||
            query.contains("cancel navigation") || query.contains("cancel the navigation") ||
            query.contains("stop navigation") || query.contains("stop the navigation") ||
            query.contains("end navigation") || query.contains("stop navigating") ||
            query.contains("end route") || query.contains("exit navigation") || query.contains("quit navigation") ||
            query.contains("stop following the path") || query.contains("stop path") ||
            (navigating && (query.equals("stop") || query.contains("stop route")))) {
            return new AssistantResult(Action.NAV_CANCEL, "Navigation stopped.", null);
        }

        // 6. Settings & App Status
        if (query.contains("settings") || query.contains("preferences") || query.contains("configure")) {
            return new AssistantResult(Action.OPEN_SETTINGS, "Opening voice and safety settings.", null);
        }

        if (query.contains("status") || query.contains("how are you") || query.contains("help")) {
            String state = cameraActive ? "Camera is active and detecting obstacles. " : "Camera is currently on standby. ";
            if (navigating) {
                state += "Active walking route in progress. ";
            }
            state += "You can ask: what is in front of me, navigate to a destination, change settings by voice, or say activate emergency mode.";
            return new AssistantResult(Action.STATUS, state, null);
        }

        // 7. Conversational query passed to Google Gemini
        String systemContext = buildSystemContext(currentDetections, frameWidth, frameHeight, navigating, destination, currentStep);
        String geminiAnswer = queryGeminiModel(rawQuery, systemContext);
        if (geminiAnswer != null && !geminiAnswer.isEmpty()) {
            return new AssistantResult(Action.SPEAK_INFO, geminiAnswer, null);
        }

        return new AssistantResult(Action.SPEAK_INFO,
                "I heard: " + rawQuery + ". You can ask what is in front of you, navigate to a location, or change settings.", null);
    }

    private static int extractNumber(String text) {
        String[] words = text.split("\\s+");
        for (String w : words) {
            try {
                return Integer.parseInt(w);
            } catch (NumberFormatException ignored) {}
            switch (w) {
                case "one": return 1;
                case "two": return 2;
                case "three": return 3;
                case "four": return 4;
                case "five": return 5;
                case "ten": return 10;
                case "fifteen": return 15;
                case "twenty": return 20;
                case "thirty": return 30;
            }
        }
        return -1;
    }

    private static String extractApiKey(String rawQuery) {
        String lower = rawQuery.toLowerCase(Locale.ROOT);
        String[] prefixes = new String[]{"set api key to", "set api key", "set gemini key to", "set gemini key", "api key is", "api key"};
        for (String p : prefixes) {
            int idx = lower.indexOf(p);
            if (idx != -1) {
                String candidate = rawQuery.substring(idx + p.length()).trim();
                if (candidate.startsWith("to ")) candidate = candidate.substring(3).trim();
                if (candidate.startsWith("is ")) candidate = candidate.substring(3).trim();
                if (candidate.length() > 8) return candidate;
            }
        }
        return null;
    }

    public String clarifyOcrText(String rawOcrText) {
        if (rawOcrText == null || rawOcrText.trim().isEmpty()) {
            return null;
        }
        String prompt = "You are an assistive vision assistant for a blind person. Read the following text that was detected by OCR on an object, medicine, product, or sign. Correct any OCR typos or fragmented words and explain clearly what it says in 1 to 2 natural, concise spoken sentences:\n\"" + rawOcrText + "\"";
        String systemInstruction = "You are a concise assistive voice assistant for blind users. Speak clearly, accurately, and naturally. Never use markdown, bullet points, or asterisks.";
        return queryGeminiModel(prompt, systemInstruction);
    }

    private boolean isSceneQuery(String query) {
        return query.contains("what is in front") ||
               query.contains("what's in front") ||
               query.contains("what is ahead") ||
               query.contains("what do you see") ||
               query.contains("describe") ||
               query.contains("tell me what") ||
               query.contains("can i walk") ||
               query.contains("is it safe") ||
               query.contains("look around") ||
               query.contains("surroundings");
    }

    /**
     * Queries Google Gemini Generative Language API directly over HTTPS with dynamic API key.
     */
    public String queryGeminiModel(String query, String systemPromptContext) {
        String apiKey = settings.getGeminiApiKey();
        for (String model : GEMINI_MODELS) {
            String result = querySingleGeminiModel(model, apiKey, query, systemPromptContext);
            if (result != null && !result.isEmpty()) {
                return result;
            }
        }
        return null;
    }

    private String querySingleGeminiModel(String modelName, String apiKey, String query, String systemPromptContext) {
        try {
            String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + modelName + ":generateContent?key=" + apiKey;
            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5500);
            conn.setDoOutput(true);

            JSONObject payload = new JSONObject();

            // System instruction
            JSONObject systemInstruction = new JSONObject();
            JSONArray sysParts = new JSONArray();
            JSONObject sysText = new JSONObject();
            sysText.put("text", systemPromptContext);
            sysParts.put(sysText);
            systemInstruction.put("parts", sysParts);
            payload.put("system_instruction", systemInstruction);

            // User content
            JSONArray contents = new JSONArray();
            JSONObject userContent = new JSONObject();
            userContent.put("role", "user");
            JSONArray userParts = new JSONArray();
            JSONObject userText = new JSONObject();
            userText.put("text", query);
            userParts.put(userText);
            userContent.put("parts", userParts);
            contents.put(userContent);
            payload.put("contents", contents);

            // Generation config
            JSONObject genConfig = new JSONObject();
            genConfig.put("temperature", 0.35);
            genConfig.put("maxOutputTokens", 90);
            payload.put("generationConfig", genConfig);

            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject respObj = new JSONObject(sb.toString());
                JSONArray candidates = respObj.optJSONArray("candidates");
                if (candidates != null && candidates.length() > 0) {
                    JSONObject candidate = candidates.getJSONObject(0);
                    JSONObject content = candidate.optJSONObject("content");
                    if (content != null) {
                        JSONArray parts = content.optJSONArray("parts");
                        if (parts != null && parts.length() > 0) {
                            String text = parts.getJSONObject(0).optString("text", "");
                            if (!text.trim().isEmpty()) {
                                return text.trim();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Try next fallback model
        }
        return null;
    }

    private String buildSystemContext(List<Detection> detections, int width, int height,
                                       boolean navigating, String destination, NavStep currentStep) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are Neuro Vision, an AI vision and voice assistant for a visually impaired user. Answer calmly and concisely in 1 or 2 natural sentences. ");
        sb.append("Current real-time camera scene: ");

        if (detections == null || detections.isEmpty()) {
            sb.append("The path ahead is completely clear with no detected obstacles. ");
        } else {
            int w = Math.max(1, width);
            int h = Math.max(1, height);
            for (Detection d : detections) {
                float cx = d.centerX();
                String pos = cx < w / 3f ? "on the left" : cx > w * 2f / 3f ? "on the right" : "directly in front in center";
                String dist = (d.distancePhrase != null && !d.distancePhrase.isEmpty()) ? d.distancePhrase : estimateDistancePhrase(d, w, h);
                String idStr = d.instanceNumber > 0 ? " #" + d.instanceNumber : "";
                sb.append("[").append(d.label).append(idStr).append(" ").append(pos).append(", ").append(dist).append("]. ");
            }
        }

        if (navigating && currentStep != null) {
            sb.append("Active navigation: destination is ").append(destination).append(". Current turn: ").append(currentStep.instruction).append(".");
        }

        return sb.toString();
    }

    /**
     * Generates a spatial, human-like interactive answer describing what is in front of the user,
     * including exact positions (left, center, right), distance estimates, and navigation safety advice.
     */
    public String generateInteractiveSpatialAnswer(String query, List<Detection> detections, int width, int height) {
        if (detections == null || detections.isEmpty()) {
            if (query.contains("can i walk") || query.contains("safe")) {
                return "Yes, you can walk forward. The path directly in front of you is completely clear.";
            }
            return "The path directly in front of you is open and clear. There are no obstacles detected within your walking path.";
        }

        int w = Math.max(1, width);
        int h = Math.max(1, height);

        List<Detection> centerObstacles = new ArrayList<>();
        List<Detection> leftObstacles = new ArrayList<>();
        List<Detection> rightObstacles = new ArrayList<>();
        Map<String, List<Detection>> byClass = new HashMap<>();

        for (Detection d : detections) {
            float cx = d.centerX();
            if (cx < w / 3f) {
                leftObstacles.add(d);
            } else if (cx > w * 2f / 3f) {
                rightObstacles.add(d);
            } else {
                centerObstacles.add(d);
            }
            String label = d.label.toLowerCase(Locale.US);
            List<Detection> list = byClass.get(label);
            if (list == null) {
                list = new ArrayList<>();
                byClass.put(label, list);
            }
            list.add(d);
        }

        StringBuilder response = new StringBuilder();

        // 1. Multi-object grouping announcement
        for (Map.Entry<String, List<Detection>> entry : byClass.entrySet()) {
            if (entry.getValue().size() > 1) {
                int count = entry.getValue().size();
                String name = entry.getKey();
                String plural = name.equals("person") ? "people" : (name.endsWith("s") || name.endsWith("ch") ? name + "es" : name + "s");
                response.append("There are ").append(count).append(" ").append(plural).append(" in front. ");
            }
        }

        // 2. Intelligent corridor collision & deadlock avoidance
        if (!centerObstacles.isEmpty()) {
            Detection primary = centerObstacles.get(0);
            String dist = (primary.distancePhrase != null && !primary.distancePhrase.isEmpty())
                    ? primary.distancePhrase
                    : estimateDistancePhrase(primary, w, h);
            response.append("Directly ahead, there is a ").append(primary.label).append(" ").append(dist).append(". ");

            if (!leftObstacles.isEmpty() && !rightObstacles.isEmpty()) {
                response.append("Path is blocked ahead and on both sides. Stop. Turn slowly left and right to scan for a clear way out.");
            } else if (!rightObstacles.isEmpty()) {
                response.append("The right side also has an obstacle. Step left into the clear path.");
            } else if (!leftObstacles.isEmpty()) {
                response.append("The left side also has an obstacle. Step right into the clear path.");
            } else {
                response.append("Both left and right are clear to step around.");
            }
        } else {
            // Center is clear!
            response.append("Your direct walking path ahead is clear. ");
            if (!leftObstacles.isEmpty() && !rightObstacles.isEmpty()) {
                response.append("Obstacles detected on both sides: ")
                        .append(leftObstacles.get(0).label).append(" on your left and ")
                        .append(rightObstacles.get(0).label).append(" on your right. Narrow corridor, stay centered.");
            } else if (!rightObstacles.isEmpty()) {
                Detection rightObj = rightObstacles.get(0);
                String dist = (rightObj.distancePhrase != null && !rightObj.distancePhrase.isEmpty()) ? rightObj.distancePhrase : estimateDistancePhrase(rightObj, w, h);
                response.append(capitalize(rightObj.label)).append(" on your right, ").append(dist).append(". Path ahead is clear, continue straight.");
            } else if (!leftObstacles.isEmpty()) {
                Detection leftObj = leftObstacles.get(0);
                String dist = (leftObj.distancePhrase != null && !leftObj.distancePhrase.isEmpty()) ? leftObj.distancePhrase : estimateDistancePhrase(leftObj, w, h);
                response.append(capitalize(leftObj.label)).append(" on your left, ").append(dist).append(". Path ahead is clear, continue straight.");
            }
        }

        return response.toString().trim();
    }

    private String estimateDistancePhrase(Detection d, int width, int height) {
        float areaRatio = d.areaRatio(width, height);
        if (areaRatio >= 0.22f) {
            return "very close, less than a meter away";
        } else if (areaRatio >= 0.10f) {
            return "close, about one to two meters ahead";
        } else if (areaRatio >= 0.04f) {
            return "at medium distance, about three meters ahead";
        } else {
            return "further ahead";
        }
    }

    private static String capitalize(String text) {
        if (text == null || text.isEmpty()) return "";
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
