package com.visionguide.phone;

import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class GuidanceEngine {
    private static final long CLEAR_MS = 5000;

    private final MultiObjectTracker tracker = new MultiObjectTracker();
    private final DepthEstimator depthEstimator = new DepthEstimator();

    private final Map<String, SpokenState> spokenStates = new HashMap<>();
    private long lastClear = 0;
    private long lastSpeechAt = 0;
    private int lastSpeechPriority = 0;
    private String lastSpokenSignature = "";

    DepthEstimator getDepthEstimator() {
        return depthEstimator;
    }

    MultiObjectTracker getTracker() {
        return tracker;
    }

    void reset() {
        tracker.reset();
        spokenStates.clear();
        lastClear = 0;
        lastSpeechAt = 0;
        lastSpeechPriority = 0;
        lastSpokenSignature = "";
    }

    boolean isMoving(Detection detection, int width, int height) {
        return tracker.isMoving(detection, width, height);
    }

    Guidance evaluate(List<Detection> detections, int width, int height, long now, AppSettings settings) {
        return evaluate(detections, width, height, now, settings, false);
    }

    Guidance evaluate(List<Detection> detections, int width, int height, long now, AppSettings settings, boolean isNavigating) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);

        // 1. Run camera-aware optical + hardware depth estimation on every detection
        if (detections != null) {
            for (Detection d : detections) {
                depthEstimator.estimate(d, w, h);
            }
        }

        // 2. Update multi-object tracker (assigns track IDs 1, 2, 3... and checks delta transitions)
        List<MultiObjectTracker.TrackEvent> trackEvents = tracker.update(detections, w, h, now, settings);

        // 3. Perform 3-corridor spatial scene & threat analysis
        List<Detection> leftThreats = new ArrayList<>();
        List<Detection> centerThreats = new ArrayList<>();
        List<Detection> rightThreats = new ArrayList<>();

        if (detections != null) {
            for (Detection d : detections) {
                if (!isRelevant(d, w, h, settings)) continue;

                float cx = d.centerX();
                float dist = d.distanceMeters;
                boolean isThreat = dist <= 3.8f || d.areaRatio(w, h) >= 0.04f;

                if (isThreat) {
                    if (cx < w / 3f) {
                        leftThreats.add(d);
                    } else if (cx > w * 2f / 3f) {
                        rightThreats.add(d);
                    } else {
                        centerThreats.add(d);
                    }
                }
            }
        }

        int totalThreatCount = leftThreats.size() + centerThreats.size() + rightThreats.size();

        // 4. Intelligent Navigation & Obstacle Avoidance Tree

        // Case A: Walking corridor is heavily crowded / congested
        if (totalThreatCount >= 3 || (!centerThreats.isEmpty() && !leftThreats.isEmpty() && !rightThreats.isEmpty())) {
            String msg = "Path is crowded with obstacles ahead. Stop. Check for a clear way.";
            Guidance g = new Guidance(msg, true, !centerThreats.isEmpty() ? centerThreats.get(0) : (!leftThreats.isEmpty() ? leftThreats.get(0) : rightThreats.get(0)), false, Guidance.PRIORITY_URGENT);
            return maybeSpeak(g, "scene:crowded", "crowded|" + totalThreatCount, now, settings);
        }

        // Case B: Center walking corridor is blocked by an obstacle directly in the walking path
        if (!centerThreats.isEmpty()) {
            Detection primary = nearest(centerThreats);
            boolean leftBlocked = !leftThreats.isEmpty();
            boolean rightBlocked = !rightThreats.isEmpty();

            // If both sides have obstacles
            if (leftBlocked && rightBlocked) {
                String msg = "Path is blocked ahead and on both sides. Stop! Scan around for a clear way.";
                Guidance g = new Guidance(msg, true, primary, tracker.isMoving(primary, w, h), Guidance.PRIORITY_URGENT);
                return maybeSpeak(g, "scene:blocked_both", "blocked_both", now, settings);
            }

            // If right is blocked, and left is confirmed clear
            if (rightBlocked) {
                String msg = "Obstacle directly ahead and on right. Step left to clear path.";
                Guidance g = new Guidance(msg, true, primary, tracker.isMoving(primary, w, h), Guidance.PRIORITY_URGENT);
                return maybeSpeak(g, "scene:step_left", "step_left", now, settings);
            }

            // If left is blocked, and right is confirmed clear
            if (leftBlocked) {
                String msg = "Obstacle directly ahead and on left. Step right to clear path.";
                Guidance g = new Guidance(msg, true, primary, tracker.isMoving(primary, w, h), Guidance.PRIORITY_URGENT);
                return maybeSpeak(g, "scene:step_right", "step_right", now, settings);
            }

            // Flanks clear: Alert user to step slightly aside
            String distPhrase = primary.distancePhrase.isEmpty() ? "ahead" : primary.distancePhrase;
            String msg = "Caution! " + capitalize(primary.label) + " directly in walking path, " + distPhrase + ". Step slightly right.";
            Guidance g = new Guidance(msg, primary.distanceMeters <= 1.2f, primary, tracker.isMoving(primary, w, h), Guidance.PRIORITY_URGENT);
            return maybeSpeak(g, "scene:center_" + primary.label, "center|" + primary.distanceBand, now, settings);
        }

        // Proper directional motion events (e.g. crossing motion directly into path)
        if (!trackEvents.isEmpty()) {
            for (MultiObjectTracker.TrackEvent ev : trackEvents) {
                if (ev.type == MultiObjectTracker.TrackEvent.Type.PROPER_MOTION) {
                    // During navigation, only alert on urgent proper motion crossing or approaching
                    if (!isNavigating || ev.priority == Guidance.PRIORITY_URGENT) {
                        Guidance g = new Guidance(ev.speechMessage, ev.priority == Guidance.PRIORITY_URGENT, null, true, ev.priority);
                        Guidance spoken = maybeSpeak(g, "event:motion:" + ev.label + ":" + ev.instanceNumber, ev.speechMessage, now, settings);
                        if (spoken != null) {
                            return spoken;
                        }
                    }
                }
            }
        }

        // If in navigation mode, walking corridor is clear!
        // Suppress peripheral side alerts and repetitive "Path is clear" so navigation guidance remains clear.
        if (isNavigating) {
            return null;
        }

        if (!rightThreats.isEmpty()) {
            Detection r = nearest(rightThreats);
            // Intelligence: Never advise moving left blindly when center is already open!
            String distPhrase = r.distancePhrase.isEmpty() ? "near you" : r.distancePhrase;
            String msg = capitalize(r.label) + " on your right, " + distPhrase + ". Path ahead is clear, continue straight.";
            Guidance g = new Guidance(msg, false, r, tracker.isMoving(r, w, h), Guidance.PRIORITY_INFO);
            return maybeSpeak(g, "scene:right_" + r.label, "right|" + r.distanceBand, now, settings);
        }

        if (!leftThreats.isEmpty()) {
            Detection l = nearest(leftThreats);
            // Intelligence: Never advise moving right blindly when center is already open!
            String distPhrase = l.distancePhrase.isEmpty() ? "near you" : l.distancePhrase;
            String msg = capitalize(l.label) + " on your left, " + distPhrase + ". Path ahead is clear, continue straight.";
            Guidance g = new Guidance(msg, false, l, tracker.isMoving(l, w, h), Guidance.PRIORITY_INFO);
            return maybeSpeak(g, "scene:left_" + l.label, "left|" + l.distanceBand, now, settings);
        }

        // 5. Track Events (Group appearances, cell moves, or objects leaving)
        if (!trackEvents.isEmpty()) {
            for (MultiObjectTracker.TrackEvent ev : trackEvents) {
                Guidance g = new Guidance(ev.speechMessage, false, null, false, ev.priority);
                Guidance spoken = maybeSpeak(g, "event:" + ev.label + ":" + ev.type, ev.speechMessage, now, settings);
                if (spoken != null) {
                    return spoken;
                }
            }
        }

        // 6. Path is completely clear
        expire(now);
        if (now - lastClear >= CLEAR_MS) {
            lastClear = now;
            return maybeSpeak(
                    new Guidance("Path is clear.", false, null, false, Guidance.PRIORITY_INFO),
                    "scene:clear",
                    "clear",
                    now,
                    settings
            );
        }

        return null;
    }

    private Detection nearest(List<Detection> list) {
        if (list == null || list.isEmpty()) return null;
        Detection best = list.get(0);
        for (int i = 1; i < list.size(); i++) {
            if (list.get(i).distanceMeters < best.distanceMeters) {
                best = list.get(i);
            }
        }
        return best;
    }

    static String gridCellFor(Detection detection, int width, int height) {
        int col = detection.centerX() < width / 3f ? 0 : detection.centerX() > width * 2f / 3f ? 2 : 1;
        int row = detection.centerY() < height / 3f ? 0 : detection.centerY() > height * 2f / 3f ? 2 : 1;
        String[] rows = {"upper", "middle", "lower"};
        String[] cols = {"left", "middle", "right"};
        if (row == 1 && col == 1) {
            return "center";
        }
        return rows[row] + " " + cols[col];
    }

    static String regionFor(Detection detection, int width) {
        if (detection.centerX() < width / 3f) {
            return "left";
        }
        if (detection.centerX() > width * 2f / 3f) {
            return "right";
        }
        return "center";
    }

    private Guidance maybeSpeak(Guidance guidance, String objectKey, String stateSignature, long now, AppSettings settings) {
        SpokenState oldState = spokenStates.get(objectKey);
        boolean sameObjectSameState = oldState != null && oldState.signature.equals(stateSignature);
        if (sameObjectSameState && guidance.priority <= oldState.priority && now - oldState.spokenAt < settings.repeatCooldownMs) {
            return null;
        }

        SpokenState oldSceneState = spokenStates.get("scene:" + stateSignature);
        boolean sameSceneSameState = oldSceneState != null
                && guidance.priority <= oldSceneState.priority
                && now - oldSceneState.spokenAt < settings.repeatCooldownMs;
        if (sameSceneSameState) {
            return null;
        }

        boolean priorityJump = settings.priorityOverride && guidance.priority > lastSpeechPriority;
        boolean due = lastSpeechAt == 0 || now - lastSpeechAt >= settings.speechDelayMs;
        boolean urgentDue = guidance.priority == Guidance.PRIORITY_URGENT && now - lastSpeechAt >= 1000;
        if (!due && !priorityJump && !urgentDue) {
            return null;
        }

        lastSpeechAt = now;
        lastSpeechPriority = guidance.priority;
        lastSpokenSignature = stateSignature;
        spokenStates.put(objectKey, new SpokenState(stateSignature, now, guidance.priority));
        spokenStates.put("scene:" + stateSignature, new SpokenState(stateSignature, now, guidance.priority));
        return guidance;
    }

    private static boolean isRelevant(Detection detection, int width, int height, AppSettings settings) {
        String label = detection.label.toLowerCase(Locale.US);
        return isImportantLabel(label)
                || label.equals("chair")
                || label.equals("dining table")
                || detection.distanceMeters <= 3.8f
                || detection.areaRatio(width, height) >= Math.min(0.02f, settings.notifyAreaRatio);
    }

    private static boolean isImportantLabel(String rawLabel) {
        String label = rawLabel.toLowerCase(Locale.US);
        return label.equals("person")
                || label.equals("car")
                || label.equals("bus")
                || label.equals("truck")
                || label.equals("train")
                || label.equals("bicycle")
                || label.equals("motorcycle");
    }

    private void expire(long now) {
        spokenStates.entrySet().removeIf(entry -> now - entry.getValue().spokenAt > 25000);
    }

    private static String capitalize(String text) {
        if (text == null || text.isEmpty()) return "";
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static final class SpokenState {
        final String signature;
        final long spokenAt;
        final int priority;

        SpokenState(String signature, long spokenAt, int priority) {
            this.signature = signature;
            this.spokenAt = spokenAt;
            this.priority = priority;
        }
    }
}
