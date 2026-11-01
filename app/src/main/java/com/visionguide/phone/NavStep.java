package com.visionguide.phone;

public final class NavStep {
    public final String instruction;
    public final String distanceText;
    public final int distanceMeters;
    public final String durationText;
    public final String maneuver;
    public final double endLat;
    public final double endLng;

    public NavStep(String instruction, String distanceText, int distanceMeters, String durationText, String maneuver, double endLat, double endLng) {
        this.instruction = instruction;
        this.distanceText = distanceText;
        this.distanceMeters = distanceMeters;
        this.durationText = durationText;
        this.maneuver = maneuver == null ? "" : maneuver;
        this.endLat = endLat;
        this.endLng = endLng;
    }

    public String getManeuverSymbol() {
        String lower = (instruction + " " + maneuver).toLowerCase();
        if (lower.contains("u-turn") || lower.contains("uturn")) return "↶";
        if (lower.contains("slight left")) return "↖";
        if (lower.contains("slight right")) return "↗";
        if (lower.contains("sharp left") || lower.contains("turn left") || lower.contains("left")) return "⬅";
        if (lower.contains("sharp right") || lower.contains("turn right") || lower.contains("right")) return "➡";
        if (lower.contains("arrive") || lower.contains("destination")) return "🏁";
        if (lower.contains("continue") || lower.contains("straight") || lower.contains("walk") || lower.contains("head")) return "⬆";
        return "➤";
    }

    public String getSpokenDirection() {
        return instruction;
    }

    public static String cleanInstruction(String text) {
        if (text == null) return "";
        String s = text.trim();
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    public static String cleanInstructionLower(String text) {
        String s = cleanInstruction(text);
        if (!s.isEmpty() && Character.isUpperCase(s.charAt(0))) {
            return Character.toLowerCase(s.charAt(0)) + s.substring(1);
        }
        return s;
    }

    public static String formatFluidGuidance(NavStep current, NavStep next, int distanceMeters, boolean isTurnAction) {
        if (current == null) return "";

        String currInstr = cleanInstruction(current.instruction);
        int dist = distanceMeters > 0 ? distanceMeters : current.distanceMeters;
        String distStr = dist < 1000 ? (dist + " meters") : String.format(java.util.Locale.US, "%.1f kilometers", dist / 1000f);

        if (next == null) {
            if (isTurnAction) {
                return currInstr + ", continue straight for " + distStr + " to reach your destination.";
            } else {
                return "Continue straight for " + distStr + " to your destination.";
            }
        }

        String nextInstr = cleanInstructionLower(next.instruction);

        if (isTurnAction) {
            return currInstr + ", go straight for " + distStr + ", then " + nextInstr + ".";
        } else {
            return "Continue straight for " + distStr + ", then " + nextInstr + ".";
        }
    }
}
