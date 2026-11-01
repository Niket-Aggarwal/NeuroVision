package com.visionguide.phone;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class MultiObjectTracker {

    static final class TrackEvent {
        enum Type {
            GROUP_APPEARED,
            OBJECT_MOVED_CELL,
            PROPER_MOTION,
            OBJECT_LEFT,
            ALL_CLEARED
        }

        final Type type;
        final String label;
        final int count;
        final int instanceNumber;
        final String fromCell;
        final String toCell;
        final String speechMessage;
        final int priority;

        TrackEvent(Type type, String label, int count, int instanceNumber, String fromCell, String toCell, String speechMessage, int priority) {
            this.type = type;
            this.label = label;
            this.count = count;
            this.instanceNumber = instanceNumber;
            this.fromCell = fromCell;
            this.toCell = toCell;
            this.speechMessage = speechMessage;
            this.priority = priority;
        }
    }

    static final class HistoryPoint {
        final float x;
        final float y;
        final float area;
        final float dist;
        final long time;

        HistoryPoint(float x, float y, float area, float dist, long time) {
            this.x = x;
            this.y = y;
            this.area = area;
            this.dist = dist;
            this.time = time;
        }
    }

    static final class TrackedObject {
        final int trackId;
        final int instanceNumber;
        final String label;
        RectF box;
        PointF center;
        String gridCell;
        float distanceMeters;
        String distancePhrase;
        long firstSeen;
        long lastSeen;
        int missedFrames;
        boolean announced;
        String lastAnnouncedCell;
        int motionStreak;
        boolean isMoving;
        String currentMotionDirection = "";
        long lastMotionAnnouncedTime = 0;

        final List<HistoryPoint> history = new ArrayList<>();

        TrackedObject(int trackId, int instanceNumber, String label, RectF box, PointF center, String gridCell,
                      float distanceMeters, String distancePhrase, long firstSeen, float areaRatio) {
            this.trackId = trackId;
            this.instanceNumber = instanceNumber;
            this.label = label;
            this.box = box;
            this.center = center;
            this.gridCell = gridCell;
            this.distanceMeters = distanceMeters;
            this.distancePhrase = distancePhrase;
            this.firstSeen = firstSeen;
            this.lastSeen = firstSeen;
            this.missedFrames = 0;
            this.announced = false;
            this.lastAnnouncedCell = gridCell;
            this.motionStreak = 0;
            this.isMoving = false;
            this.history.add(new HistoryPoint(center.x, center.y, areaRatio, distanceMeters, firstSeen));
        }

        void addHistory(float x, float y, float area, float dist, long now) {
            history.add(new HistoryPoint(x, y, area, dist, now));
            while (history.size() > 8) {
                history.remove(0);
            }
            history.removeIf(hp -> now - hp.time > 2500);
        }

        String computeMotionDirection(int imageWidth, int imageHeight) {
            if (history.size() < 3) return "";
            HistoryPoint oldest = history.get(0);
            HistoryPoint latest = history.get(history.size() - 1);
            long dt = latest.time - oldest.time;
            if (dt < 320) return ""; // Need at least 320ms of tracking

            float w = Math.max(1f, imageWidth);
            float h = Math.max(1f, imageHeight);
            float dx = (latest.x - oldest.x) / w;
            float dy = (latest.y - oldest.y) / h;
            float dDist = latest.dist - oldest.dist;
            float dArea = (latest.area - oldest.area) / Math.max(0.01f, oldest.area);

            // Proper horizontal crossing motion (left to right / right to left)
            if (dx >= 0.065f && Math.abs(dx) > 1.25f * Math.abs(dy)) {
                return "moving left to right";
            }
            if (dx <= -0.065f && Math.abs(dx) > 1.25f * Math.abs(dy)) {
                return "moving right to left";
            }

            // Depth motion (approaching or moving away)
            if ((dArea >= 0.35f && latest.dist <= 4.2f) || (dDist <= -0.45f && latest.dist <= 4.2f)) {
                return "approaching towards you";
            }
            if (dArea <= -0.30f || dDist >= 0.50f) {
                return "moving away";
            }

            return "";
        }
    }

    private int nextGlobalTrackId = 1;
    private final Map<String, List<TrackedObject>> tracksByClass = new HashMap<>();
    private final Map<String, Long> lastGroupAnnounceTime = new HashMap<>();

    void reset() {
        tracksByClass.clear();
        lastGroupAnnounceTime.clear();
        nextGlobalTrackId = 1;
    }

    boolean isMoving(Detection detection, int width, int height) {
        if (detection == null) return false;
        String label = detection.label.toLowerCase(Locale.US);
        List<TrackedObject> tracks = tracksByClass.get(label);
        if (tracks == null) return false;
        for (TrackedObject t : tracks) {
            if (t.trackId == detection.trackId) {
                return t.isMoving;
            }
        }
        return false;
    }

    List<TrackEvent> update(List<Detection> detections, int width, int height, long now, AppSettings settings) {
        List<TrackEvent> events = new ArrayList<>();
        int w = Math.max(1, width);
        int h = Math.max(1, height);

        // Group incoming detections by class
        Map<String, List<Detection>> incomingByClass = new HashMap<>();
        if (detections != null) {
            for (Detection d : detections) {
                String label = d.label.toLowerCase(Locale.US);
                List<Detection> list = incomingByClass.get(label);
                if (list == null) {
                    list = new ArrayList<>();
                    incomingByClass.put(label, list);
                }
                list.add(d);
            }
        }

        // Process all known classes and incoming classes
        Set<String> allLabels = new HashSet<>(tracksByClass.keySet());
        allLabels.addAll(incomingByClass.keySet());

        for (String label : allLabels) {
            List<Detection> incoming = incomingByClass.get(label);
            if (incoming == null) incoming = Collections.emptyList();

            List<TrackedObject> activeTracks = tracksByClass.get(label);
            if (activeTracks == null) {
                activeTracks = new ArrayList<>();
                tracksByClass.put(label, activeTracks);
            }

            // Match incoming detections with active tracks using distance matrix
            boolean[] detectionMatched = new boolean[incoming.size()];
            boolean[] trackMatched = new boolean[activeTracks.size()];

            for (int i = 0; i < incoming.size(); i++) {
                Detection det = incoming.get(i);
                float bestDist = Float.MAX_VALUE;
                int bestTrackIdx = -1;

                for (int j = 0; j < activeTracks.size(); j++) {
                    if (trackMatched[j]) continue;
                    TrackedObject track = activeTracks.get(j);

                    float dx = (det.centerX() - track.center.x) / w;
                    float dy = (det.centerY() - track.center.y) / h;
                    float dist = (float) Math.hypot(dx, dy);

                    if (dist < 0.28f && dist < bestDist) {
                        bestDist = dist;
                        bestTrackIdx = j;
                    }
                }

                if (bestTrackIdx != -1) {
                    // Match found!
                    trackMatched[bestTrackIdx] = true;
                    detectionMatched[i] = true;
                    TrackedObject track = activeTracks.get(bestTrackIdx);

                    // Update track properties
                    track.box = det.box;
                    PointF newCenter = new PointF(det.centerX(), det.centerY());
                    float areaRatio = det.areaRatio(w, h);
                    track.addHistory(newCenter.x, newCenter.y, areaRatio, det.distanceMeters, now);

                    float moveDx = (newCenter.x - track.center.x) / w;
                    float moveDy = (newCenter.y - track.center.y) / h;
                    boolean rawMotion = Math.hypot(moveDx, moveDy) >= settings.motionThreshold;

                    track.motionStreak = rawMotion ? Math.min(5, track.motionStreak + 1) : Math.max(0, track.motionStreak - 1);
                    track.center = newCenter;
                    track.distanceMeters = det.distanceMeters;
                    track.distancePhrase = det.distancePhrase;
                    track.lastSeen = now;
                    track.missedFrames = 0;

                    // Proper directional motion analysis
                    String properMotion = track.computeMotionDirection(w, h);
                    track.currentMotionDirection = properMotion;
                    track.isMoving = !properMotion.isEmpty() || track.motionStreak >= settings.motionStreakFrames;

                    det.trackId = track.trackId;
                    det.instanceNumber = track.instanceNumber;
                    det.isMoving = track.isMoving;
                    det.motionDirection = properMotion;

                    String currentCell = GuidanceEngine.gridCellFor(det, w, h);
                    track.gridCell = currentCell;

                    int totalSameKind = activeTracks.size();

                    // Check for proper motion announcement (e.g. "Person moving left to right ahead")
                    if (!properMotion.isEmpty() && (now - track.lastMotionAnnouncedTime > 6000)) {
                        track.lastMotionAnnouncedTime = now;
                        String position = currentCell.contains("center") ? "ahead" : "on your " + (newCenter.x < w / 3f ? "left" : "right");
                        String motionSpeech;
                        if (totalSameKind > 1) {
                            motionSpeech = capitalize(label) + " " + track.instanceNumber + " is " + properMotion + " " + position + ".";
                        } else {
                            motionSpeech = capitalize(label) + " is " + properMotion + " " + position + ".";
                        }

                        events.add(new TrackEvent(
                                TrackEvent.Type.PROPER_MOTION,
                                label,
                                totalSameKind,
                                track.instanceNumber,
                                track.lastAnnouncedCell,
                                properMotion,
                                motionSpeech,
                                properMotion.contains("approaching") ? Guidance.PRIORITY_URGENT : Guidance.PRIORITY_IMPORTANT
                        ));
                    }

                    // Drastic change check: moved from one grid box to another!
                    if (track.announced && !currentCell.equals(track.lastAnnouncedCell)) {
                        String from = track.lastAnnouncedCell;
                        track.lastAnnouncedCell = currentCell;

                        String speech;
                        if (totalSameKind > 1) {
                            speech = capitalize(label) + " " + track.instanceNumber + " moved to " + currentCell + ".";
                        } else {
                            speech = capitalize(label) + " moved to " + currentCell + ".";
                        }

                        events.add(new TrackEvent(
                                TrackEvent.Type.OBJECT_MOVED_CELL,
                                label,
                                totalSameKind,
                                track.instanceNumber,
                                from,
                                currentCell,
                                speech,
                                Guidance.PRIORITY_IMPORTANT
                        ));
                    }
                }
            }

            // Create new tracks for unmatched detections
            for (int i = 0; i < incoming.size(); i++) {
                if (!detectionMatched[i]) {
                    Detection det = incoming.get(i);
                    int instanceNum = getNextAvailableInstanceNumber(activeTracks);
                    int trackId = nextGlobalTrackId++;

                    det.trackId = trackId;
                    det.instanceNumber = instanceNum;

                    String cell = GuidanceEngine.gridCellFor(det, w, h);
                    TrackedObject newTrack = new TrackedObject(
                            trackId,
                            instanceNum,
                            label,
                            det.box,
                            new PointF(det.centerX(), det.centerY()),
                            cell,
                            det.distanceMeters,
                            det.distancePhrase,
                            now,
                            det.areaRatio(w, h)
                    );
                    activeTracks.add(newTrack);
                }
            }

            // Handle tracks that had no detection in this frame
            Iterator<TrackedObject> iter = activeTracks.iterator();
            while (iter.hasNext()) {
                TrackedObject track = iter.next();
                int idx = activeTracks.indexOf(track);
                if (idx >= 0 && idx < trackMatched.length && !trackMatched[idx]) {
                    track.missedFrames++;
                    // After missing 4 consecutive frames (~1.2s), announce it left the frame
                    if (track.missedFrames == 4) {
                        int remaining = activeTracks.size() - 1;
                        String speech;
                        if (remaining == 0) {
                            speech = "All " + plural(label, 2) + " have cleared.";
                        } else if (remaining == 1) {
                            speech = "One " + label + " moved out. 1 " + label + " remains.";
                        } else {
                            speech = "One " + label + " moved out. " + remaining + " " + plural(label, remaining) + " remain.";
                        }

                        events.add(new TrackEvent(
                                TrackEvent.Type.OBJECT_LEFT,
                                label,
                                remaining,
                                track.instanceNumber,
                                track.gridCell,
                                "out",
                                speech,
                                Guidance.PRIORITY_IMPORTANT
                        ));
                    }
                }
            }

            // Clean up old missing tracks
            activeTracks.removeIf(t -> t.missedFrames >= 6);

            // Group announcement check for multiple objects of same kind
            List<TrackedObject> unannounced = new ArrayList<>();
            for (TrackedObject t : activeTracks) {
                if (!t.announced) {
                    unannounced.add(t);
                }
            }

            if (activeTracks.size() > 1 && !unannounced.isEmpty()) {
                Long lastAnnounced = lastGroupAnnounceTime.get(label);
                if (lastAnnounced == null || now - lastAnnounced > 12000) {
                    lastGroupAnnounceTime.put(label, now);
                    int count = activeTracks.size();
                    String speech = "There are " + count + " " + plural(label, count) + " in front.";

                    for (TrackedObject t : activeTracks) {
                        t.announced = true;
                        t.lastAnnouncedCell = t.gridCell;
                    }

                    events.add(new TrackEvent(
                            TrackEvent.Type.GROUP_APPEARED,
                            label,
                            count,
                            0,
                            "",
                            "",
                            speech,
                            Guidance.PRIORITY_IMPORTANT
                    ));
                }
            } else if (activeTracks.size() == 1 && !unannounced.isEmpty()) {
                // Single object marked as announced so it won't repeat unless cell changes
                TrackedObject t = unannounced.get(0);
                t.announced = true;
                t.lastAnnouncedCell = t.gridCell;
            }
        }

        return events;
    }

    private static int getNextAvailableInstanceNumber(List<TrackedObject> tracks) {
        Set<Integer> used = new HashSet<>();
        for (TrackedObject t : tracks) {
            used.add(t.instanceNumber);
        }
        int num = 1;
        while (used.contains(num)) {
            num++;
        }
        return num;
    }

    private static String plural(String label, int count) {
        if (count == 1) return label;
        String l = label.toLowerCase(Locale.US);
        if (l.equals("person")) return "people";
        if (l.endsWith("s") || l.endsWith("ch") || l.endsWith("sh") || l.endsWith("x")) {
            return label + "es";
        }
        return label + "s";
    }

    private static String capitalize(String text) {
        if (text == null || text.isEmpty()) return "";
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
