package com.visionguide.phone;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class NavigationManager implements LocationListener {
    public static final String GEOAPIFY_API_KEY = BuildConfig.GEOAPIFY_API_KEY;

    public interface NavigationCallback {
        void onRouteFound(String destination, String totalDistance, String totalDuration, List<NavStep> steps, String fluidInstruction, Bitmap mapTile);
        void onStepUpdated(int stepIndex, NavStep step, String fluidInstruction, Bitmap stepTile);
        void onStepCompleted(NavStep completedStep, NavStep nextStep, String fluidInstruction, Bitmap nextTile);
        void onWalkingProgress(int distanceRemainingMeters, String progressInstruction);
        void onApproachingWaypoint(NavStep currentStep, int distanceMeters, String approachInstruction);
        void onNavigationEnded(String reason);
        void onError(String message);
    }

    private final Context context;
    private final LocationManager locationManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    private NavigationCallback callback;
    private boolean navigating = false;
    private Location lastKnownLocation;
    private List<NavStep> currentSteps = new ArrayList<>();
    private int currentStepIndex = 0;
    private String destinationName = "";
    private String totalDistance = "";
    private String totalDuration = "";
    private boolean approachingAnnounced = false;
    private long lastMoveAnnounceTime = 0;
    private float lastAnnouncedDistance = -1f;

    public NavigationManager(Context context) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    public void startLocationTracking() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2500, 2f, this);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3500, 4f, this);
            }

            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location net = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (gps != null) {
                lastKnownLocation = gps;
            } else if (net != null) {
                lastKnownLocation = net;
            }
        } catch (Exception ignored) {
        }
    }

    public void stopLocationTracking() {
        try {
            locationManager.removeUpdates(this);
        } catch (Exception ignored) {
        }
    }

    public boolean isNavigating() {
        return navigating;
    }

    public NavStep getCurrentStep() {
        if (!navigating || currentSteps.isEmpty() || currentStepIndex >= currentSteps.size()) {
            return null;
        }
        return currentSteps.get(currentStepIndex);
    }

    public int getCurrentStepIndex() {
        return currentStepIndex;
    }

    public int getTotalStepsCount() {
        return currentSteps.size();
    }

    public String getDestinationName() {
        return destinationName;
    }

    public String getTotalDistance() {
        return totalDistance;
    }

    public String getTotalDuration() {
        return totalDuration;
    }

    public String getFluidInstruction(int index) {
        if (currentSteps == null || currentSteps.isEmpty() || index < 0 || index >= currentSteps.size()) {
            return "";
        }
        NavStep curr = currentSteps.get(index);
        NavStep next = (index + 1 < currentSteps.size()) ? currentSteps.get(index + 1) : null;
        return NavStep.formatFluidGuidance(curr, next, curr.distanceMeters, true);
    }

    public void navigateTo(String destination, NavigationCallback cb) {
        this.callback = cb;
        this.destinationName = destination.trim();
        this.approachingAnnounced = false;
        startLocationTracking();

        networkExecutor.execute(() -> {
            try {
                // 1. Determine Origin Coordinates
                double originLat = 0.0;
                double originLng = 0.0;

                if (lastKnownLocation != null) {
                    originLat = lastKnownLocation.getLatitude();
                    originLng = lastKnownLocation.getLongitude();
                } else if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    Location loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (loc == null) {
                        loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    }
                    if (loc != null) {
                        originLat = loc.getLatitude();
                        originLng = loc.getLongitude();
                    }
                }

                // If GPS is unavailable (e.g. indoors/testing), use default city reference
                if (originLat == 0.0 && originLng == 0.0) {
                    originLat = 51.5074; // London reference
                    originLng = -0.1278;
                }

                // 2. Geocoding API Search to get Destination Coordinates
                String geocodeUrl = "https://api.geoapify.com/v1/geocode/search?text="
                        + URLEncoder.encode(destinationName, StandardCharsets.UTF_8.name())
                        + "&apiKey=" + GEOAPIFY_API_KEY;

                String geocodeJson = httpGet(geocodeUrl);
                JSONObject geocodeRoot = new JSONObject(geocodeJson);
                JSONArray features = geocodeRoot.optJSONArray("features");

                if (features == null || features.length() == 0) {
                    notifyError("Location not found for: " + destinationName);
                    return;
                }

                JSONObject bestMatch = features.getJSONObject(0);
                JSONObject props = bestMatch.getJSONObject("properties");
                double destLat = props.getDouble("lat");
                double destLng = props.getDouble("lon");
                String formattedDest = props.optString("formatted", destinationName);

                // 3. Geoapify Routing API (mode=walk)
                String routingUrl = "https://api.geoapify.com/v1/routing?waypoints="
                        + originLat + "," + originLng + "|" + destLat + "," + destLng
                        + "&mode=walk"
                        + "&apiKey=" + GEOAPIFY_API_KEY;

                String routingJson = httpGet(routingUrl);
                JSONObject routingRoot = new JSONObject(routingJson);
                JSONArray routeFeatures = routingRoot.optJSONArray("features");

                if (routeFeatures == null || routeFeatures.length() == 0) {
                    notifyError("No walking route found to destination.");
                    return;
                }

                JSONObject routeFeature = routeFeatures.getJSONObject(0);
                JSONObject routeProps = routeFeature.getJSONObject("properties");
                int distMeters = routeProps.optInt("distance", 0);
                double timeSeconds = routeProps.optDouble("time", 0.0);

                totalDistance = formatDistance(distMeters);
                totalDuration = formatDuration(timeSeconds);

                // Extract LineString coordinates for step endpoints
                JSONObject geometry = routeFeature.getJSONObject("geometry");
                JSONArray coordinates = geometry.getJSONArray("coordinates");
                JSONArray linePoints = coordinates;
                if (coordinates.length() > 0 && coordinates.get(0) instanceof JSONArray) {
                    Object firstElem = coordinates.getJSONArray(0).get(0);
                    if (firstElem instanceof JSONArray) {
                        linePoints = coordinates.getJSONArray(0);
                    }
                }

                JSONArray legs = routeProps.getJSONArray("legs");
                if (legs.length() == 0) {
                    notifyError("No route legs found.");
                    return;
                }

                JSONArray stepsArray = legs.getJSONObject(0).getJSONArray("steps");
                List<NavStep> parsedSteps = new ArrayList<>();

                for (int i = 0; i < stepsArray.length(); i++) {
                    JSONObject s = stepsArray.getJSONObject(i);
                    JSONObject instrObj = s.optJSONObject("instruction");
                    String instruction = instrObj != null ? instrObj.optString("text", "") : "Continue";

                    int sDist = s.optInt("distance", 0);
                    double sTime = s.optDouble("time", 0.0);
                    String sDistText = formatDistance(sDist);
                    String sDurText = formatDuration(sTime);
                    int toIndex = s.optInt("to_index", -1);

                    double endLat = destLat;
                    double endLng = destLng;
                    if (toIndex >= 0 && toIndex < linePoints.length()) {
                        JSONArray pt = linePoints.getJSONArray(toIndex);
                        endLng = pt.getDouble(0);
                        endLat = pt.getDouble(1);
                    }

                    parsedSteps.add(new NavStep(instruction, sDistText, sDist, sDurText, instruction, endLat, endLng));
                }

                // 4. Fetch Map Tile preview using Geoapify Tile / Static Map API
                Bitmap mapTile = fetchMapTile(originLat, originLng, 15);

                final String initialFluid = !parsedSteps.isEmpty()
                        ? NavStep.formatFluidGuidance(parsedSteps.get(0), parsedSteps.size() > 1 ? parsedSteps.get(1) : null, parsedSteps.get(0).distanceMeters, true)
                        : "";
                final String finalDestName = formattedDest;
                mainHandler.post(() -> {
                    this.currentSteps = parsedSteps;
                    this.currentStepIndex = 0;
                    this.destinationName = finalDestName;
                    this.navigating = true;
                    this.approachingAnnounced = false;
                    this.lastMoveAnnounceTime = android.os.SystemClock.elapsedRealtime();
                    this.lastAnnouncedDistance = parsedSteps.isEmpty() ? -1f : parsedSteps.get(0).distanceMeters;

                    if (callback != null) {
                        callback.onRouteFound(destinationName, totalDistance, totalDuration, currentSteps, initialFluid, mapTile);
                        if (!currentSteps.isEmpty()) {
                            callback.onStepUpdated(0, currentSteps.get(0), initialFluid, mapTile);
                        }
                    }
                });

            } catch (Exception e) {
                notifyError("Navigation error: " + e.getMessage());
            }
        });
    }

    public void nextStep() {
        if (!navigating || currentSteps.isEmpty()) return;

        NavStep completedStep = currentSteps.get(currentStepIndex);
        currentStepIndex++;
        approachingAnnounced = false;
        lastMoveAnnounceTime = android.os.SystemClock.elapsedRealtime();

        if (currentStepIndex >= currentSteps.size()) {
            stopNavigation("You have arrived at your destination: " + destinationName);
        } else {
            NavStep nextStep = currentSteps.get(currentStepIndex);
            NavStep subsequentStep = (currentStepIndex + 1 < currentSteps.size()) ? currentSteps.get(currentStepIndex + 1) : null;
            String fluidInstruction = NavStep.formatFluidGuidance(nextStep, subsequentStep, nextStep.distanceMeters, true);
            lastAnnouncedDistance = nextStep.distanceMeters;

            networkExecutor.execute(() -> {
                Bitmap tile = fetchMapTile(nextStep.endLat, nextStep.endLng, 16);
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onStepCompleted(completedStep, nextStep, fluidInstruction, tile);
                        callback.onStepUpdated(currentStepIndex, nextStep, fluidInstruction, tile);
                    }
                });
            });
        }
    }

    public void previousStep() {
        if (!navigating || currentSteps.isEmpty()) return;
        if (currentStepIndex > 0) {
            currentStepIndex--;
            approachingAnnounced = false;
            NavStep step = currentSteps.get(currentStepIndex);
            NavStep next = (currentStepIndex + 1 < currentSteps.size()) ? currentSteps.get(currentStepIndex + 1) : null;
            String fluidInstruction = NavStep.formatFluidGuidance(step, next, step.distanceMeters, true);
            lastAnnouncedDistance = step.distanceMeters;
            lastMoveAnnounceTime = android.os.SystemClock.elapsedRealtime();

            networkExecutor.execute(() -> {
                Bitmap tile = fetchMapTile(step.endLat, step.endLng, 16);
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onStepUpdated(currentStepIndex, step, fluidInstruction, tile);
                    }
                });
            });
        }
    }

    public void stopNavigation(String reason) {
        navigating = false;
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onNavigationEnded(reason);
            }
        });
    }

    private void notifyError(String error) {
        mainHandler.post(() -> {
            navigating = false;
            if (callback != null) {
                callback.onError(error);
            }
        });
    }

    private String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(9000);
        conn.setReadTimeout(9000);

        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            throw new Exception("HTTP " + code + ": " + conn.getResponseMessage());
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    private Bitmap fetchMapTile(double lat, double lon, int zoom) {
        try {
            int x = (int) Math.floor((lon + 180.0) / 360.0 * (1 << zoom));
            double latRad = Math.toRadians(lat);
            int y = (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * (1 << zoom));

            String tileUrl = "https://maps.geoapify.com/v1/tile/carto/" + zoom + "/" + x + "/" + y + ".png?apiKey=" + GEOAPIFY_API_KEY;
            URL url = new URL(tileUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                InputStream is = conn.getInputStream();
                Bitmap bmp = BitmapFactory.decodeStream(is);
                is.close();
                return bmp;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String formatDistance(int meters) {
        if (meters < 1000) {
            return meters + " m";
        }
        return String.format(Locale.US, "%.1f km", meters / 1000f);
    }

    private String formatDuration(double seconds) {
        int mins = Math.max(1, (int) Math.round(seconds / 60.0));
        if (mins < 60) {
            return mins + " mins";
        }
        int hrs = mins / 60;
        int remMins = mins % 60;
        return hrs + " hr " + (remMins > 0 ? remMins + " mins" : "");
    }

    @Override
    public void onLocationChanged(Location location) {
        lastKnownLocation = location;
        if (!navigating || currentSteps.isEmpty() || currentStepIndex >= currentSteps.size()) {
            return;
        }

        NavStep current = currentSteps.get(currentStepIndex);
        float[] results = new float[1];
        Location.distanceBetween(
                location.getLatitude(), location.getLongitude(),
                current.endLat, current.endLng,
                results
        );

        float distanceToEnd = results[0];
        long now = android.os.SystemClock.elapsedRealtime();

        // 1. Automatic turn completion when reaching the waypoint (within 12m)
        if (distanceToEnd <= 12f && currentStepIndex < currentSteps.size() - 1) {
            nextStep();
            return;
        }

        // 2. Advance warning when approaching turn waypoint (between 14m and 32m)
        if (distanceToEnd <= 32f && distanceToEnd > 12f && !approachingAnnounced) {
            approachingAnnounced = true;
            NavStep nextStep = (currentStepIndex + 1 < currentSteps.size()) ? currentSteps.get(currentStepIndex + 1) : null;
            String approachPrompt = (nextStep != null)
                    ? "In " + Math.round(distanceToEnd) + " meters, " + NavStep.cleanInstructionLower(nextStep.instruction) + "."
                    : "In " + Math.round(distanceToEnd) + " meters, you will reach your destination.";
            if (callback != null) {
                callback.onApproachingWaypoint(current, Math.round(distanceToEnd), approachPrompt);
            }
            lastMoveAnnounceTime = now;
            lastAnnouncedDistance = distanceToEnd;
            return;
        }

        // 3. Continuous walking updates as person is moving along the path
        // Trigger if:
        // - Moved at least 35 meters along the route, OR
        // - At least 14 seconds have passed and distance decreased by at least 10 meters
        boolean distanceMovedTrigger = (lastAnnouncedDistance > 0 && (lastAnnouncedDistance - distanceToEnd >= 35f));
        boolean timeElapsedTrigger = (now - lastMoveAnnounceTime >= 14000 && (lastAnnouncedDistance < 0 || lastAnnouncedDistance - distanceToEnd >= 10f));

        if ((distanceMovedTrigger || timeElapsedTrigger) && distanceToEnd > 32f) {
            lastMoveAnnounceTime = now;
            lastAnnouncedDistance = distanceToEnd;
            NavStep nextStep = (currentStepIndex + 1 < currentSteps.size()) ? currentSteps.get(currentStepIndex + 1) : null;
            String progressPrompt = NavStep.formatFluidGuidance(current, nextStep, Math.round(distanceToEnd), false);
            if (callback != null) {
                callback.onWalkingProgress(Math.round(distanceToEnd), progressPrompt);
            }
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}
}
