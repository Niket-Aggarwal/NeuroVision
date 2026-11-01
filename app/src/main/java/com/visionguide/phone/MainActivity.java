package com.visionguide.phone;

import android.Manifest;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.telephony.SmsManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CAMERA = 42;
    private static final int REQUEST_AUDIO = 43;
    private static final int REQUEST_LOCATION = 44;
    private static final int REQUEST_CALL = 45;
    private static final int REQUEST_EMERGENCY_PERMISSIONS = 46;

    private PreviewView previewView;
    private OverlayView overlayView;
    private ImageView standbyImageView;
    private TextView guidanceText;
    private TextView statusText;
    private TextView loadingText;
    private TextView settingsSummary;
    private Button startStopButton;
    private Button voiceCmdButton;
    private Button navButton;
    private Button sosButton;
    private Button settingsButton;

    // Navigation UI
    private LinearLayout navCard;
    private TextView navManeuverIcon;
    private TextView navInstructionText;
    private TextView navSubtext;
    private ImageView navMapThumbnail;
    private Button navNextButton;
    private Button navCancelButton;

    private SpeechGuide speechGuide;
    private GuidanceEngine guidanceEngine;
    private NavigationManager navigationManager;
    private GeminiVoiceAssistant voiceAssistant;
    private AppSettings settings;
    private YoloOnnxDetector detector;
    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;

    private SpeechRecognizer speechRecognizer;
    private boolean isListeningForCommand = false;
    private String pendingNavDestination = null;
    private List<Detection> lastDetections = Collections.emptyList();
    private int lastFrameWidth = 640;
    private int lastFrameHeight = 480;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean analyzing = new AtomicBoolean(false);
    private long lastAnalysisTime = 0;
    private long lastNavAnnounceTime = 0;
    private boolean running = false;
    private boolean obstacleActive = false;

    // Wake Word, OCR, and NFC Managers
    private WakeWordListener wakeWordListener;
    private TextDetectorManager textDetectorManager;
    private NfcMedicineManager nfcManager;

    private String pendingOcrText = null;
    private boolean waitingForOcrConfirm = false;
    private String pendingMedicineText = null;
    private boolean waitingForNfcConfirm = false;

    // Wall Detection & Avoidance Manager
    private WallDetector wallDetector;
    private boolean waitingForWallDecision = false;

    private final Runnable loadingPulse = new Runnable() {
        @Override
        public void run() {
            if (!running || loadingText.getVisibility() != View.VISIBLE) {
                return;
            }
            loadingText.animate()
                    .alpha(loadingText.getAlpha() < 0.6f ? 1f : 0.35f)
                    .setDuration(520)
                    .withEndAction(() -> mainHandler.postDelayed(this, 80))
                    .start();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraExecutor = Executors.newSingleThreadExecutor();
        speechGuide = new SpeechGuide(this);
        guidanceEngine = new GuidanceEngine();
        guidanceEngine.getDepthEstimator().init(this);
        navigationManager = new NavigationManager(this);
        settings = new AppSettings(this);
        voiceAssistant = new GeminiVoiceAssistant(this, settings);

        // TTS Listener: pause wake word during TTS, resume when TTS finishes
        speechGuide.setSpeechListener(new SpeechGuide.SpeechListener() {
            @Override
            public void onSpeechStarted() {
                if (wakeWordListener != null) {
                    wakeWordListener.pause();
                }
            }

            @Override
            public void onSpeechFinished() {
                if (wakeWordListener != null && !isListeningForCommand && !speechGuide.isReadingContent()) {
                    wakeWordListener.resume();
                }
            }
        });

        // "Hello Vision" continuous wake-word detector
        wakeWordListener = new WakeWordListener(this, (trailingCommand) -> {
            vibrateShort(120);
            if (trailingCommand != null && !trailingCommand.trim().isEmpty()) {
                // User said "Hello Vision [command]" in one phrase
                statusText.setText("Vision: " + trailingCommand);
                handleVoiceCommand(trailingCommand);
            } else {
                // User said "Hello Vision" -> acknowledge and listen carefully
                statusText.setText("Listening... Speak now");
                speechGuide.speak("Yes, I'm listening.", false, Guidance.PRIORITY_URGENT);
                mainHandler.postDelayed(this::startVoiceCommandListening, 1300);
            }
        });

        // On-device OCR text detector
        textDetectorManager = new TextDetectorManager((fullText, previewText) -> {
            if (isListeningForCommand || speechGuide.isSpeaking() || speechGuide.isReadingContent()
                    || waitingForOcrConfirm || waitingForNfcConfirm) {
                return;
            }
            pendingOcrText = fullText;
            waitingForOcrConfirm = true;
            vibrateShort(120);
            speechGuide.speak("I see text: " + previewText + ". Say read this, or tap Voice.", false,
                    Guidance.PRIORITY_URGENT);
            mainHandler.postDelayed(() -> {
                waitingForOcrConfirm = false;
            }, 8000);
        });

        // NFC Medicine detector
        nfcManager = new NfcMedicineManager(this, (medicineDetails, previewTitle) -> {
            pendingMedicineText = medicineDetails;
            waitingForNfcConfirm = true;
            vibrateShort(250);
            statusText.setText("Medicine NFC Detected");
            speechGuide.speak("Medicine NFC tag detected: " + previewTitle + ". Say read it, or tap Voice.",
                    true, Guidance.PRIORITY_URGENT);
            mainHandler.postDelayed(() -> {
                waitingForNfcConfirm = false;
            }, 10000);
        });
        if (getIntent() != null) {
            nfcManager.handleIntent(getIntent());
        }

        wallDetector = new WallDetector();

        initSpeechRecognizer();
        buildUi();
        loadDetector();

        // Start continuous "Hello Vision" wake-word listening immediately if audio
        // permission granted
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            wakeWordListener.start();
        } else {
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, REQUEST_AUDIO);
        }
    }

    private void initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    speechGuide.setMuted(true);
                    speechGuide.stop();
                    statusText.setText("Listening... Say Navigate to [Place], Start, or Stop");
                }

                @Override
                public void onBeginningOfSpeech() {
                    speechGuide.setMuted(true);
                    speechGuide.stop();
                }

                @Override
                public void onRmsChanged(float rmsdB) {
                }

                @Override
                public void onBufferReceived(byte[] buffer) {
                }

                @Override
                public void onEndOfSpeech() {
                    statusText.setText(running ? "Processing voice command..." : "Processing...");
                    resetVoiceButton();
                }

                @Override
                public void onError(int error) {
                    resetVoiceButton();
                    speechGuide.setMuted(false);
                    statusText.setText(
                            running ? "Camera active" : (navigationManager.isNavigating() ? "Navigating" : "Standby"));
                }

                @Override
                public void onResults(Bundle results) {
                    resetVoiceButton();
                    speechGuide.setMuted(false);
                    ArrayList<String> matches = results != null
                            ? results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            : null;
                    if (matches != null && !matches.isEmpty()) {
                        handleVoiceCommandCandidates(matches);
                    } else {
                        statusText.setText(running ? "Camera active"
                                : (navigationManager.isNavigating() ? "Navigating" : "Standby"));
                    }
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    if (partialResults != null) {
                        ArrayList<String> partials = partialResults
                                .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (partials != null && !partials.isEmpty()) {
                            String p = partials.get(0);
                            if (p != null && !p.trim().isEmpty()) {
                                statusText.setText("Listening: \"" + p + "\"");
                            }
                        }
                    }
                }

                @Override
                public void onEvent(int eventType, Bundle params) {
                }
            });
        }
    }

    private void handleVoiceCommandCandidates(ArrayList<String> matches) {
        if (matches == null || matches.isEmpty())
            return;

        // Check if any candidate is an immediate priority command
        for (String m : matches) {
            if (m == null)
                continue;
            String lower = m.toLowerCase(Locale.ROOT).trim();
            if (isPriorityCandidate(lower)) {
                handleVoiceCommand(lower);
                return;
            }
        }

        // Default to the highest confidence candidate
        handleVoiceCommand(matches.get(0).toLowerCase(Locale.ROOT).trim());
    }

    private boolean isPriorityCandidate(String text) {
        return text.equals("start") || text.equals("stop") || text.equals("emergency") ||
                text.equals("sos") || text.equals("help") || text.equals("yes") || text.equals("no") ||
                text.equals("left") || text.equals("right") || text.contains("turn left") || text.contains("turn right") ||
                text.contains("stop emergency") || text.contains("stop navigation") || text.contains("read");
    }

    private long lastVoiceToggleTime = 0;

    private void triggerVoiceCommand() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastVoiceToggleTime < 800) {
            return;
        }
        lastVoiceToggleTime = now;

        if (isListeningForCommand) {
            if (speechRecognizer != null) {
                speechRecognizer.stopListening();
            }
            resetVoiceButton();
            return;
        }

        startVoiceCommandListening();
    }

    private void startVoiceCommandListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, REQUEST_AUDIO);
            return;
        }

        if (isListeningForCommand) {
            return;
        }

        if (speechRecognizer == null) {
            Toast.makeText(this, "Voice recognizer not available.", Toast.LENGTH_SHORT).show();
            speechGuide.speak("Voice recognition is not supported on this phone.", false, Guidance.PRIORITY_URGENT);
            return;
        }

        // Pause wake word listener while active command recognition is running
        if (wakeWordListener != null) {
            wakeWordListener.pause();
        }

        // When mic is turned on, immediately stop and mute background guidance
        speechGuide.setMuted(true);
        speechGuide.stop();

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

        // Listen carefully without cutting off user too early
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);

        try {
            speechRecognizer.startListening(intent);
            isListeningForCommand = true;
            voiceCmdButton.setText("Listening...");
            voiceCmdButton.setBackground(card(0xEE27503E, dp(18), 0xFF37D4A7));
            statusText.setText("Listening carefully... Speak now");
            vibrateShort(60);
        } catch (Exception e) {
            resetVoiceButton();
            speechGuide.setMuted(false);
            Toast.makeText(this, "Speech error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void resetVoiceButton() {
        isListeningForCommand = false;
        waitingForOcrConfirm = false;
        waitingForWallDecision = false;
        waitingForNfcConfirm = false;
        voiceCmdButton.setText("Voice Cmd");
        voiceCmdButton.setBackground(card(0xDD1A2A3B, dp(18), 0x6637D4A7));
        if (wakeWordListener != null && !speechGuide.isSpeaking() && !speechGuide.isReadingContent()) {
            mainHandler.postDelayed(() -> {
                if (wakeWordListener != null && !isListeningForCommand
                        && !speechGuide.isSpeaking() && !speechGuide.isReadingContent()) {
                    wakeWordListener.resume();
                }
            }, 600);
        }
    }

    private void vibrateShort(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(ms);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void handleVoiceCommand(String rawCommand) {
        String command = rawCommand.trim();
        Toast.makeText(this, "Heard: " + command, Toast.LENGTH_SHORT).show();

        // Interpret asynchronously using Google Gemini Voice Assistance & LLM Engine
        cameraExecutor.execute(() -> {
            GeminiVoiceAssistant.AssistantResult result = voiceAssistant.interpret(
                    command,
                    running,
                    navigationManager.isNavigating(),
                    navigationManager.getDestinationName(),
                    navigationManager.getCurrentStep(),
                    lastDetections,
                    lastFrameWidth,
                    lastFrameHeight);
            runOnUiThread(() -> executeAssistantResult(result));
        });
    }

    private void executeAssistantResult(GeminiVoiceAssistant.AssistantResult result) {
        speechGuide.setMuted(false);
        switch (result.action) {
            case NAVIGATE:
                if (result.payload != null && !result.payload.isEmpty()) {
                    startNavigationFlow(result.payload);
                } else {
                    showDestinationDialog();
                    speechGuide.speak(result.spokenText, false, Guidance.PRIORITY_INFO);
                }
                break;

            case START_CAMERA:
                speechGuide.speak(result.spokenText, true, Guidance.PRIORITY_URGENT);
                if (!running) {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        startDetection();
                    } else {
                        requestPermissions(new String[] { Manifest.permission.CAMERA }, REQUEST_CAMERA);
                    }
                }
                break;

            case STOP_CAMERA:
                speechGuide.speak(result.spokenText, true, Guidance.PRIORITY_URGENT);
                if (running) {
                    stopDetection();
                }
                break;

            case NAV_NEXT_STEP:
                if (navigationManager.isNavigating()) {
                    navigationManager.nextStep();
                } else {
                    speechGuide.speak(result.spokenText, false, Guidance.PRIORITY_INFO);
                }
                break;

            case NAV_REPEAT:
                speechGuide.speak(result.spokenText, true, Guidance.PRIORITY_URGENT);
                break;

            case SPEAK_INFO:
            case STATUS:
                speechGuide.speakReading(result.spokenText);
                break;

            case NAV_CANCEL:
                if (navigationManager.isNavigating()) {
                    navigationManager.stopNavigation("Navigation stopped.");
                } else {
                    speechGuide.speak("No active navigation to stop.", true, Guidance.PRIORITY_URGENT);
                }
                break;

            case ACTIVATE_EMERGENCY:
                activateEmergencyMode();
                break;

            case CANCEL_EMERGENCY:
                statusText.setText(running ? "Camera active" : "Standby");
                statusText.setTextColor(0xFF37D4A7);
                speechGuide.speak(result.spokenText, true, Guidance.PRIORITY_URGENT);
                Toast.makeText(this, result.spokenText, Toast.LENGTH_SHORT).show();
                break;

            case CONFIRM_READ:
                if (waitingForNfcConfirm && pendingMedicineText != null) {
                    waitingForNfcConfirm = false;
                    String med = pendingMedicineText;
                    pendingMedicineText = null;
                    cameraExecutor.execute(() -> {
                        String clarified = voiceAssistant.clarifyOcrText(med);
                        String toSpeak = (clarified != null && !clarified.isEmpty()) ? clarified : ("Medicine details: " + med);
                        runOnUiThread(() -> speechGuide.speakReading(toSpeak));
                    });
                } else if (waitingForOcrConfirm && pendingOcrText != null) {
                    waitingForOcrConfirm = false;
                    String raw = pendingOcrText;
                    pendingOcrText = null;
                    cameraExecutor.execute(() -> {
                        String clarified = voiceAssistant.clarifyOcrText(raw);
                        String toSpeak = (clarified != null && !clarified.isEmpty()) ? clarified : ("Reading text: " + raw);
                        runOnUiThread(() -> speechGuide.speakReading(toSpeak));
                    });
                } else if (textDetectorManager != null && textDetectorManager.getLastDetectedText() != null
                        && !textDetectorManager.getLastDetectedText().isEmpty()) {
                    String raw = textDetectorManager.getLastDetectedText();
                    cameraExecutor.execute(() -> {
                        String clarified = voiceAssistant.clarifyOcrText(raw);
                        String toSpeak = (clarified != null && !clarified.isEmpty()) ? clarified : ("Reading visible text: " + raw);
                        runOnUiThread(() -> speechGuide.speakReading(toSpeak));
                    });
                } else {
                    speechGuide.speak(
                            "No text or medicine tag detected to read. Hold phone steady facing the text or tap the medicine NFC tag.",
                            false, Guidance.PRIORITY_INFO);
                }
                break;

            case DISMISS_READ:
                waitingForNfcConfirm = false;
                waitingForOcrConfirm = false;
                waitingForWallDecision = false;
                pendingMedicineText = null;
                pendingOcrText = null;
                speechGuide.speak(result.spokenText, false, Guidance.PRIORITY_INFO);
                break;

            case FEED_CONTACT:
                showFeedContactDialog();
                break;

            case UPDATE_SETTING:
                settingsSummary.setText(settingsSummaryText());
                speechGuide.speak(result.spokenText, false, Guidance.PRIORITY_INFO);
                Toast.makeText(this, result.spokenText, Toast.LENGTH_SHORT).show();
                break;

            case OPEN_SETTINGS:
                speechGuide.speak(result.spokenText, false, Guidance.PRIORITY_INFO);
                openSettings();
                break;

            case MOVE_LEFT:
                handleWallTurnDecision(true);
                break;

            case MOVE_RIGHT:
                handleWallTurnDecision(false);
                break;

            case WALL_CHECK:
                handleWallBestDirection();
                break;
        }
    }

    private void handleWallTurnDecision(boolean chooseLeft) {
        waitingForWallDecision = false;
        WallDetector.CorridorClearance clearance = WallDetector.checkCorridors(lastDetections, lastFrameWidth, lastFrameHeight);

        if (chooseLeft) {
            if (clearance.leftClear) {
                speechGuide.speak("Left is clear. Turn left now and proceed forward.", true, Guidance.PRIORITY_URGENT);
            } else {
                String obs = clearance.leftObstacle != null ? clearance.leftObstacle : "an obstacle";
                if (clearance.rightClear) {
                    speechGuide.speak("Obstacle on your left, there is a " + obs + ". Turn right instead where the path is clear.", true, Guidance.PRIORITY_URGENT);
                } else {
                    speechGuide.speak("Both left and right are blocked. Please stop and take a step back.", true, Guidance.PRIORITY_URGENT);
                }
            }
        } else {
            if (clearance.rightClear) {
                speechGuide.speak("Right is clear. Turn right now and proceed forward.", true, Guidance.PRIORITY_URGENT);
            } else {
                String obs = clearance.rightObstacle != null ? clearance.rightObstacle : "an obstacle";
                if (clearance.leftClear) {
                    speechGuide.speak("Obstacle on your right, there is a " + obs + ". Turn left instead where the path is clear.", true, Guidance.PRIORITY_URGENT);
                } else {
                    speechGuide.speak("Both left and right are blocked. Please stop and take a step back.", true, Guidance.PRIORITY_URGENT);
                }
            }
        }
    }

    private void handleWallBestDirection() {
        waitingForWallDecision = false;
        WallDetector.CorridorClearance clearance = WallDetector.checkCorridors(lastDetections, lastFrameWidth, lastFrameHeight);
        if (clearance.leftClear && clearance.rightClear) {
            speechGuide.speak("Both left and right are clear. You can turn either left or right to proceed.", true, Guidance.PRIORITY_URGENT);
        } else if (clearance.leftClear) {
            speechGuide.speak("Left path is clear. Turn left now and proceed forward.", true, Guidance.PRIORITY_URGENT);
        } else if (clearance.rightClear) {
            speechGuide.speak("Right path is clear. Turn right now and proceed forward.", true, Guidance.PRIORITY_URGENT);
        } else {
            speechGuide.speak("Both left and right are blocked. Please stop and take a step back.", true, Guidance.PRIORITY_URGENT);
        }
    }

    private void startNavigationFlow(String destination) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingNavDestination = destination;
            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION }, REQUEST_LOCATION);
            return;
        }

        // Keep camera detection active during navigation to monitor the walking
        // corridor
        if (!running) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startDetection();
            } else {
                requestPermissions(new String[] { Manifest.permission.CAMERA }, REQUEST_CAMERA);
            }
        }

        statusText.setText("Routing to " + destination + "...");
        speechGuide.speak("Calculating walking route to " + destination, true, Guidance.PRIORITY_URGENT);

        navigationManager.navigateTo(destination, new NavigationManager.NavigationCallback() {
            @Override
            public void onRouteFound(String dest, String totalDist, String totalDur, List<NavStep> steps,
                    String fluidInstruction, Bitmap mapTile) {
                navCard.setVisibility(View.VISIBLE);
                statusText.setText("Navigating: " + dest);
                if (navMapThumbnail != null && mapTile != null) {
                    navMapThumbnail.setImageBitmap(mapTile);
                    navMapThumbnail.setVisibility(View.VISIBLE);
                }
                String welcomeMsg = "Walking route to " + dest + ". " + fluidInstruction;
                speechGuide.speak(welcomeMsg, true, Guidance.PRIORITY_URGENT);
            }

            @Override
            public void onStepUpdated(int stepIndex, NavStep step, String fluidInstruction, Bitmap stepTile) {
                updateNavUi(stepIndex, step, stepTile);
            }

            @Override
            public void onStepCompleted(NavStep completedStep, NavStep nextStep, String fluidInstruction,
                    Bitmap nextTile) {
                updateNavUi(navigationManager.getCurrentStepIndex(), nextStep, nextTile);
                // When a turn is completed, automatically announce the fluid next sequence!
                if (!obstacleActive) {
                    speechGuide.speak(fluidInstruction, true, Guidance.PRIORITY_URGENT);
                }
                lastNavAnnounceTime = System.currentTimeMillis();
            }

            @Override
            public void onWalkingProgress(int distanceRemainingMeters, String progressInstruction) {
                if (!obstacleActive && !speechGuide.isMuted()) {
                    speechGuide.speak(progressInstruction, false, Guidance.PRIORITY_INFO);
                }
            }

            @Override
            public void onApproachingWaypoint(NavStep currentStep, int distanceMeters, String approachInstruction) {
                if (!obstacleActive && !speechGuide.isMuted()) {
                    speechGuide.speak(approachInstruction, true, Guidance.PRIORITY_URGENT);
                }
            }

            @Override
            public void onNavigationEnded(String reason) {
                navCard.setVisibility(View.GONE);
                statusText.setText(running ? "Camera active" : "Standby");
                speechGuide.speak(reason != null ? reason : "Navigation ended.", true, Guidance.PRIORITY_URGENT);
            }

            @Override
            public void onError(String message) {
                navCard.setVisibility(View.GONE);
                statusText.setText("Navigation error");
                speechGuide.speak("Navigation notice: " + message, true, Guidance.PRIORITY_URGENT);
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void updateNavUi(int stepIndex, NavStep step, Bitmap tile) {
        navCard.setVisibility(View.VISIBLE);
        navManeuverIcon.setText(step.getManeuverSymbol());
        navInstructionText.setText(step.instruction);
        navSubtext.setText("Walking to " + navigationManager.getDestinationName()
                + " • In " + step.distanceText + " (" + step.durationText + ")");
        if (navMapThumbnail != null && tile != null) {
            navMapThumbnail.setImageBitmap(tile);
            navMapThumbnail.setVisibility(View.VISIBLE);
        }
    }

    private void showDestinationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Navigate (Walking)");

        final EditText input = new EditText(this);
        input.setHint("Enter destination (e.g. Central Park, Pharmacy)");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF8898AA);
        input.setPadding(dp(16), dp(16), dp(16), dp(16));
        builder.setView(input);

        builder.setPositiveButton("Navigate", (dialog, which) -> {
            String dest = input.getText().toString().trim();
            if (!dest.isEmpty()) {
                startNavigationFlow(dest);
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(card(0xEE142332, dp(18), 0xFF37D4A7));
        }
        dialog.show();
    }

    private void openSettings() {
        SettingsDialog.show(this, settings, () -> {
            settings.load();
            guidanceEngine.reset();
            settingsSummary.setText(settingsSummaryText());
            Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show();
        });
    }

    private void activateEmergencyMode() {
        if (!settings.hasEmergencyContact()) {
            speechGuide.speak("No emergency contact is saved. Please enter your contact person name and phone number.",
                    true, Guidance.PRIORITY_URGENT);
            showFeedContactDialog();
            return;
        }

        String name = settings.emergencyContactName.isEmpty() ? "your emergency contact"
                : settings.emergencyContactName;
        String phone = settings.emergencyContactPhone;

        statusText.setText("🚨 EMERGENCY: CALL & SMS TO " + name.toUpperCase(Locale.ROOT));
        statusText.setTextColor(0xFFFF4444);
        vibrateShort(400);

        speechGuide.speak("Emergency protocol activated. Placing phone call to " + name
                + " first, then sending emergency message with your location.", true, Guidance.PRIORITY_URGENT);

        // 1. Send Call FIRST
        placeEmergencyCall(phone);

        // 2. Then Send Message WITH GPS LOCATION
        mainHandler.postDelayed(() -> sendEmergencySmsWithLocation(phone, name), 2600);
    }

    private void placeEmergencyCall(String phone) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                Intent callIntent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + phone));
                callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(callIntent);
            } catch (Exception e) {
                Intent dialIntent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone));
                dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(dialIntent);
            }
        } else {
            requestPermissions(new String[] { Manifest.permission.CALL_PHONE }, REQUEST_CALL);
        }
    }

    private Location getCurrentLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }

        try {
            android.location.LocationManager lm = (android.location.LocationManager) getSystemService(
                    Context.LOCATION_SERVICE);
            if (lm != null) {
                Location gps = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
                Location net = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
                if (gps != null)
                    return gps;
                if (net != null)
                    return net;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void sendEmergencySmsWithLocation(String phone, String name) {
        try {
            Location loc = getCurrentLocation();
            StringBuilder msg = new StringBuilder();
            msg.append("EMERGENCY ALERT: I need immediate assistance!");

            if (loc != null) {
                double lat = loc.getLatitude();
                double lng = loc.getLongitude();
                msg.append("\n\nMy Location:\nhttps://maps.google.com/?q=").append(lat).append(",").append(lng);
                msg.append("\nCoordinates: ").append(String.format(Locale.US, "%.5f, %.5f", lat, lng));
            } else {
                msg.append("\n\n(Location being acquired by GPS)");
            }

            if (navigationManager.isNavigating()) {
                msg.append("\nWalking Destination: ").append(navigationManager.getDestinationName());
            }

            String finalMsg = msg.toString();

            // Also copy to clipboard so the user has immediate access to their emergency
            // location link
            try {
                android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(
                        Context.CLIPBOARD_SERVICE);
                if (cb != null) {
                    android.content.ClipData clip = android.content.ClipData.newPlainText("Emergency Location",
                            finalMsg);
                    cb.setPrimaryClip(clip);
                }
            } catch (Exception ignored) {
            }

            Intent smsIntent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone)));
            smsIntent.putExtra("sms_body", finalMsg);
            smsIntent.putExtra("android.intent.extra.TEXT", finalMsg);
            smsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(smsIntent);
            Toast.makeText(this, "Emergency SMS with location prepared for " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "SMS error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showFeedContactDialog() {
        boolean hasExisting = settings.hasEmergencyContact();
        String spokenPrompt = hasExisting
                ? "Emergency contact screen. Current contact is "
                        + (settings.emergencyContactName.isEmpty() ? settings.emergencyContactPhone
                                : settings.emergencyContactName)
                        + ". Enter new details to update."
                : "Add emergency contact screen. Please enter the contact name and phone number.";
        speechGuide.speak(spokenPrompt, false, Guidance.PRIORITY_INFO);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(hasExisting ? "Update Emergency Contact" : "Add Emergency Contact");

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(12), dp(20), dp(12));

        TextView nameLabel = new TextView(this);
        nameLabel.setText("Contact Person Name");
        nameLabel.setTextColor(0xFF37D4A7);
        nameLabel.setTextSize(13f);
        nameLabel.setPadding(0, 0, 0, dp(4));

        final EditText nameInput = new EditText(this);
        nameInput.setHint("e.g. Mom, Doctor, Alex");
        nameInput.setText(settings.emergencyContactName);
        nameInput.setTextColor(Color.WHITE);
        nameInput.setHintTextColor(0xFF8898AA);
        nameInput.setBackground(card(0xCC091522, dp(10), 0x5537D4A7));
        nameInput.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView phoneLabel = new TextView(this);
        phoneLabel.setText("Phone Number");
        phoneLabel.setTextColor(0xFF37D4A7);
        phoneLabel.setTextSize(13f);
        phoneLabel.setPadding(0, dp(14), 0, dp(4));

        final EditText phoneInput = new EditText(this);
        phoneInput.setHint("e.g. +1234567890");
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneInput.setText(settings.emergencyContactPhone);
        phoneInput.setTextColor(Color.WHITE);
        phoneInput.setHintTextColor(0xFF8898AA);
        phoneInput.setBackground(card(0xCC091522, dp(10), 0x5537D4A7));
        phoneInput.setPadding(dp(14), dp(12), dp(14), dp(12));

        container.addView(nameLabel);
        container.addView(nameInput);
        container.addView(phoneLabel);
        container.addView(phoneInput);

        builder.setView(container);

        String positiveButtonText = hasExisting ? "Update Contact" : "Save Contact";
        builder.setPositiveButton(positiveButtonText, (dialog, which) -> {
            String name = nameInput.getText().toString().trim();
            String phone = phoneInput.getText().toString().trim();
            if (phone.isEmpty()) {
                Toast.makeText(this, "Please enter a valid phone number.", Toast.LENGTH_SHORT).show();
                speechGuide.speak("Phone number cannot be empty.", false, Guidance.PRIORITY_INFO);
                return;
            }
            settings.saveEmergencyContact(name, phone);
            String savedMsg = "Emergency contact " + (name.isEmpty() ? phone : name) + " saved successfully.";
            Toast.makeText(this, savedMsg, Toast.LENGTH_SHORT).show();
            speechGuide.speak(savedMsg, false, Guidance.PRIORITY_IMPORTANT);
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(card(0xEE142332, dp(18), 0xFF37D4A7));
        }
        dialog.show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startDetection();
        } else if (requestCode == REQUEST_AUDIO && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (wakeWordListener != null) {
                wakeWordListener.start();
            }
        } else if (requestCode == REQUEST_LOCATION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingNavDestination != null) {
                String dest = pendingNavDestination;
                pendingNavDestination = null;
                startNavigationFlow(dest);
            }
        } else if ((requestCode == REQUEST_CALL || requestCode == REQUEST_EMERGENCY_PERMISSIONS)
                && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            activateEmergencyMode();
        } else if (requestCode == REQUEST_CALL || requestCode == REQUEST_EMERGENCY_PERMISSIONS) {
            if (settings.hasEmergencyContact()) {
                Intent dialIntent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + settings.emergencyContactPhone));
                startActivity(dialIntent);
            }
        } else {
            Toast.makeText(this, "Permission required for this feature.", Toast.LENGTH_LONG).show();
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(7, 12, 18));

        // Camera preview layer
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setAlpha(0f);

        // Standby background image (shown when stopped instead of text)
        standbyImageView = new ImageView(this);
        standbyImageView.setImageResource(R.drawable.ic_via_lux_standby);
        standbyImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        standbyImageView.setPadding(dp(36), dp(36), dp(36), dp(36));
        standbyImageView.setVisibility(View.VISIBLE);

        // Bounding box & grid overlay layer
        overlayView = new OverlayView(this);
        overlayView.setClickable(false);
        overlayView.setFocusable(false);

        // Top Header
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setPadding(dp(16), dp(16), dp(16), dp(4));
        topBar.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Neuro Vision");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);

        statusText = new TextView(this);
        statusText.setText("Standby - Ready");
        statusText.setTextColor(0xFF37D4A7);
        statusText.setTextSize(13f);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(2), 0, dp(4));

        // Navigation Banner Card (Appears during turn-by-turn navigation)
        navCard = new LinearLayout(this);
        navCard.setOrientation(LinearLayout.VERTICAL);
        navCard.setBackground(card(0xEE0B1A28, dp(16), 0xFF37D4A7));
        navCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        navCard.setVisibility(View.GONE);

        LinearLayout navHeaderRow = new LinearLayout(this);
        navHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        navHeaderRow.setGravity(Gravity.CENTER_VERTICAL);

        navManeuverIcon = new TextView(this);
        navManeuverIcon.setText("➤");
        navManeuverIcon.setTextSize(26f);
        navManeuverIcon.setTextColor(0xFF37D4A7);
        navManeuverIcon.setTypeface(Typeface.DEFAULT_BOLD);
        navManeuverIcon.setPadding(0, 0, dp(10), 0);

        navInstructionText = new TextView(this);
        navInstructionText.setText("Navigation ready");
        navInstructionText.setTextSize(16f);
        navInstructionText.setTextColor(Color.WHITE);
        navInstructionText.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams instrParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                1f);
        navInstructionText.setLayoutParams(instrParams);

        navCancelButton = new Button(this);
        navCancelButton.setText("✕");
        navCancelButton.setTextColor(0xFFFF7070);
        navCancelButton.setTextSize(14f);
        navCancelButton.setBackground(card(0x55FF4444, dp(12), 0x99FF4444));
        navCancelButton.setOnClickListener(v -> navigationManager.stopNavigation("Navigation cancelled."));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        navCancelButton.setLayoutParams(cancelParams);

        navHeaderRow.addView(navManeuverIcon);
        navHeaderRow.addView(navInstructionText);
        navHeaderRow.addView(navCancelButton);

        navSubtext = new TextView(this);
        navSubtext.setText("Walking route active");
        navSubtext.setTextSize(12f);
        navSubtext.setTextColor(0xFFB0C4DE);
        navSubtext.setPadding(dp(36), dp(2), 0, dp(4));

        navMapThumbnail = new ImageView(this);
        navMapThumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        navMapThumbnail.setVisibility(View.GONE);
        LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(110));
        mapParams.setMargins(0, dp(4), 0, dp(6));
        navMapThumbnail.setLayoutParams(mapParams);

        LinearLayout navActionsRow = new LinearLayout(this);
        navActionsRow.setOrientation(LinearLayout.HORIZONTAL);
        navActionsRow.setGravity(Gravity.END);

        navNextButton = new Button(this);
        navNextButton.setText("Next Turn ➔");
        navNextButton.setTextColor(0xFF37D4A7);
        navNextButton.setTextSize(12f);
        navNextButton.setAllCaps(false);
        navNextButton.setBackground(card(0x4437D4A7, dp(12), 0x7737D4A7));
        navNextButton.setOnClickListener(v -> navigationManager.nextStep());
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34));
        navNextButton.setLayoutParams(nextParams);

        navActionsRow.addView(navNextButton);

        navCard.addView(navHeaderRow);
        navCard.addView(navSubtext);
        navCard.addView(navMapThumbnail);
        navCard.addView(navActionsRow);

        topBar.addView(title);
        topBar.addView(statusText);
        topBar.addView(navCard);

        // Guidance feedback box (used during active scanning)
        guidanceText = new TextView(this);
        guidanceText.setTextColor(0xFFFFFFFF);
        guidanceText.setTextSize(18f);
        guidanceText.setTypeface(Typeface.DEFAULT_BOLD);
        guidanceText.setGravity(Gravity.CENTER);
        guidanceText.setPadding(dp(16), dp(14), dp(16), dp(14));
        guidanceText.setBackground(card(0xCC111C28, dp(20), 0x5537D4A7));
        guidanceText.setVisibility(View.GONE);

        // Loading indicator
        loadingText = new TextView(this);
        loadingText.setText("Opening camera...");
        loadingText.setTextColor(0xFF37D4A7);
        loadingText.setTextSize(18f);
        loadingText.setTypeface(Typeface.DEFAULT_BOLD);
        loadingText.setGravity(Gravity.CENTER);
        loadingText.setVisibility(View.GONE);

        // Bottom Controls Bar
        LinearLayout bottomChrome = new LinearLayout(this);
        bottomChrome.setOrientation(LinearLayout.VERTICAL);
        bottomChrome.setPadding(dp(12), dp(8), dp(12), dp(16));
        bottomChrome.setGravity(Gravity.CENTER_HORIZONTAL);

        settingsSummary = new TextView(this);
        settingsSummary.setText(settingsSummaryText());
        settingsSummary.setTextColor(0xFF9FB0C3);
        settingsSummary.setTextSize(12f);
        settingsSummary.setGravity(Gravity.CENTER);
        settingsSummary.setPadding(0, dp(2), 0, dp(8));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);

        startStopButton = button("Start");
        startStopButton.setOnClickListener(v -> {
            if (running) {
                stopDetection();
            } else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startDetection();
            } else {
                requestPermissions(new String[] { Manifest.permission.CAMERA }, REQUEST_CAMERA);
            }
        });

        voiceCmdButton = button("Voice Cmd");
        voiceCmdButton.setOnClickListener(v -> triggerVoiceCommand());

        navButton = button("Nav");
        navButton.setOnClickListener(v -> {
            if (navigationManager.isNavigating()) {
                NavStep step = navigationManager.getCurrentStep();
                if (step != null) {
                    speechGuide.speak(step.getSpokenDirection(), true, Guidance.PRIORITY_URGENT);
                }
            } else {
                showDestinationDialog();
            }
        });

        sosButton = button("SOS");
        sosButton.setTextColor(0xFFFF5252);
        sosButton.setBackground(card(0xEE2E1414, dp(18), 0xFFFF5252));
        // Clicking SOS button opens screen to add or update contact (calling is only
        // triggered by voice command)
        sosButton.setOnClickListener(v -> showFeedContactDialog());

        settingsButton = button("Settings");
        settingsButton.setOnClickListener(v -> openSettings());

        controls.addView(startStopButton, buttonParams());
        controls.addView(voiceCmdButton, buttonParams());
        controls.addView(navButton, buttonParams());
        controls.addView(sosButton, buttonParams());
        controls.addView(settingsButton, buttonParams());

        LinearLayout.LayoutParams fullWidth = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        fullWidth.setMargins(0, dp(4), 0, dp(4));

        bottomChrome.addView(guidanceText, fullWidth);
        bottomChrome.addView(loadingText, fullWidth);
        bottomChrome.addView(settingsSummary, fullWidth);
        bottomChrome.addView(controls, fullWidth);

        // Add layers to root frame
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Centered standby image shown when stopped
        FrameLayout.LayoutParams standbyParams = new FrameLayout.LayoutParams(
                dp(280),
                dp(280),
                Gravity.CENTER);
        root.addView(standbyImageView, standbyParams);

        root.addView(overlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(topBar, topParams);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(bottomChrome, bottomParams);

        setContentView(root);
    }

    private void loadDetector() {
        cameraExecutor.execute(() -> {
            try {
                detector = new YoloOnnxDetector(this);
                runOnUiThread(() -> statusText.setText("AI ready. Press Start or Voice Cmd."));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText("Detector error");
                    guidanceText.setVisibility(View.VISIBLE);
                    guidanceText.setText(e.getMessage());
                });
            }
        });
    }

    @ExperimentalCamera2Interop
    private void startDetection() {
        running = true;
        guidanceEngine.reset();
        startStopButton.setText("Stop");
        statusText.setText("Opening camera...");

        // Hide standby image and reveal active guidance box
        standbyImageView.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            standbyImageView.setVisibility(View.GONE);
        }).start();

        guidanceText.setVisibility(View.VISIBLE);
        guidanceText.setText("Hold phone upright and point forward.");
        loadingText.setVisibility(View.VISIBLE);
        loadingText.setAlpha(1f);
        mainHandler.post(loadingPulse);

        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis.Builder analysisBuilder = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888);

                Camera2Interop.Extender<ImageAnalysis> extender = new Camera2Interop.Extender<>(analysisBuilder);
                extender.setSessionCaptureCallback(new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(
                            @NonNull CameraCaptureSession session,
                            @NonNull CaptureRequest request,
                            @NonNull TotalCaptureResult result) {
                        Float focusDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
                        if (focusDist != null && focusDist > 0.01f) {
                            guidanceEngine.getDepthEstimator().updateHardwareFocusDistance(focusDist);
                        }
                    }
                });

                ImageAnalysis analysis = analysisBuilder.build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis);

                boolean isMultiCam = guidanceEngine.getDepthEstimator().isMultiCamera();
                String depthLabel = isMultiCam ? " (Dual Camera)" : " (Optical Depth)";
                statusText.setText(
                        navigationManager.isNavigating() ? "Navigating" + depthLabel : "Camera active" + depthLabel);
                loadingText.animate().cancel();
                loadingText.setVisibility(View.GONE);
                previewView.animate().alpha(1f).setDuration(450).start();
            } catch (Exception e) {
                running = false;
                startStopButton.setText("Start");
                guidanceText.setText("Camera error: " + e.getMessage());
                standbyImageView.setVisibility(View.VISIBLE);
                standbyImageView.setAlpha(1f);
            }
        }, command -> mainHandler.post(command));
    }

    private void stopDetection() {
        running = false;
        analyzing.set(false);
        loadingText.animate().cancel();
        loadingText.setVisibility(View.GONE);
        startStopButton.setText("Start");
        statusText.setText(navigationManager.isNavigating() ? "Navigating (Camera paused)" : "Standby");

        // Hide guidance text box and show standby image in background instead of text
        guidanceText.setVisibility(View.GONE);
        standbyImageView.setVisibility(View.VISIBLE);
        standbyImageView.animate().alpha(1f).setDuration(300).start();

        previewView.animate().alpha(0f).setDuration(250).start();
        overlayView.update(Collections.emptyList(), guidanceEngine, 1, 1);
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    private void analyzeImage(ImageProxy image) {
        if (!running) {
            image.close();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastAnalysisTime < 350 || !analyzing.compareAndSet(false, true)) {
            image.close();
            return;
        }
        lastAnalysisTime = now;
        try {
            YoloOnnxDetector localDetector = detector;
            if (localDetector == null) {
                image.close();
                analyzing.set(false);
                return;
            }
            Bitmap bitmap = ImageTools.bitmapFromRgbaImage(image);
            image.close();
            List<Detection> detections = localDetector.detect(bitmap);
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();

            // When mic is listening for voice commands, stop normal detection & TTS
            // feedback
            // UNLESS there is a very dangerous situation (e.g. car approaching)
            if (isListeningForCommand) {
                boolean carApproaching = false;
                if (detections != null) {
                    for (Detection d : detections) {
                        String label = d.label.toLowerCase(Locale.ROOT);
                        if ((label.equals("car") || label.equals("truck") || label.equals("bus")
                                || label.equals("motorcycle"))
                                && (guidanceEngine.isMoving(d, width, height) || d.distanceMeters <= 3.5f)) {
                            carApproaching = true;
                            break;
                        }
                    }
                }

                if (carApproaching) {
                    if (speechRecognizer != null) {
                        speechRecognizer.stopListening();
                    }
                    resetVoiceButton();
                    vibrateShort(400);
                    speechGuide.setMuted(false);
                    speechGuide.speak("Danger! Vehicle approaching directly ahead! Stop!", true,
                            Guidance.PRIORITY_URGENT);
                }

                // Keep mic audio clean: do not run OCR or speak regular detection messages
                // while listening
                bitmap.recycle();
                runOnUiThread(() -> overlayView.update(detections == null ? Collections.emptyList() : detections,
                        guidanceEngine, width, height));
                return;
            }

            // Real-time OCR Text Detection (detects stable text held still for >2s)
            if (textDetectorManager != null && !waitingForOcrConfirm && !waitingForNfcConfirm
                    && !speechGuide.isReadingContent()) {
                textDetectorManager.processFrame(bitmap, now);
            }

            // Real-time Wall Detection & Avoidance
            if (wallDetector != null && !waitingForWallDecision && !isListeningForCommand
                    && !waitingForOcrConfirm && !waitingForNfcConfirm && !speechGuide.isSpeaking()
                    && !speechGuide.isReadingContent()) {
                float hwDist = guidanceEngine.getDepthEstimator().getHardwareFocusDistance();
                WallDetector.WallResult wallResult = wallDetector.evaluate(bitmap, detections, hwDist, now);
                if (wallResult != null && wallResult.isWallDetected) {
                    runOnUiThread(() -> triggerWallAlert(wallResult));
                }
            }

            Guidance guidance = guidanceEngine.evaluate(detections, width, height, now, settings,
                    navigationManager.isNavigating());
            bitmap.recycle();
            runOnUiThread(() -> updateUi(detections, guidance, width, height));
        } catch (Exception e) {
            image.close();
            runOnUiThread(() -> guidanceText.setText("Analysis error: " + e.getMessage()));
        } finally {
            analyzing.set(false);
        }
    }

    private void triggerWallAlert(WallDetector.WallResult wall) {
        if (waitingForWallDecision || isListeningForCommand || speechGuide.isReadingContent()) return;
        waitingForWallDecision = true;
        vibrateShort(250);
        String distStr = (wall.distanceMeters >= 0.3f && wall.distanceMeters <= 2.5f)
                ? String.format(Locale.US, "about %.1f meters", wall.distanceMeters)
                : "ahead";
        String prompt = "Wall detected " + distStr + ". Turn left or right.";
        statusText.setText("Wall detected! Left or right?");
        speechGuide.speak(prompt, true, Guidance.PRIORITY_URGENT);

        mainHandler.postDelayed(() -> {
            waitingForWallDecision = false;
        }, 6000);
    }

    private void updateUi(List<Detection> detections, Guidance guidance, int width, int height) {
        if (!running) {
            return;
        }
        if (detections != null) {
            lastDetections = detections;
            lastFrameWidth = width;
            lastFrameHeight = height;
        }
        overlayView.update(detections == null ? Collections.emptyList() : detections, guidanceEngine, width, height);
        if (isListeningForCommand || speechGuide.isMuted()) {
            return;
        }
        if (guidance != null) {
            guidanceText.setText(guidance.message);

            // Obstacle prioritization fusion:
            boolean isHazard = guidance.detection != null
                    && (guidance.emergency || guidance.moving || guidance.priority >= Guidance.PRIORITY_URGENT);
            obstacleActive = isHazard;

            if (speechGuide.isReadingContent()) {
                // If an obstacle or hazard appears while reading, buffer it as the pending caution to tell after reading completes
                if (isHazard) {
                    speechGuide.setPendingCaution(guidance.message, guidance.emergency, guidance.priority);
                }
                return;
            }

            if (isHazard) {
                // Immediate emergency warning directly on walking corridor
                speechGuide.speak(guidance.message, guidance.emergency, guidance.priority);
            } else if (!navigationManager.isNavigating()) {
                if (guidance.detection != null) {
                    speechGuide.speak(guidance.message, false, guidance.priority);
                } else {
                    speechGuide.speak(guidance.message, false, guidance.priority);
                }
            }
        } else {
            obstacleActive = false;
        }
    }

    private String settingsSummaryText() {
        return "Speech " + (settings.speechDelayMs / 1000f) + "s"
                + " - Repeat " + (settings.repeatCooldownMs / 1000) + "s"
                + " - Motion " + settings.motionLabel()
                + " - Notify " + settings.notifyDistanceLabel();
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13f);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(card(0xDD1A2A3B, dp(18), 0x6637D4A7));
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private GradientDrawable card(int color, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (nfcManager != null) {
            nfcManager.handleIntent(intent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcManager != null) {
            nfcManager.onResume();
        }
        if (wakeWordListener != null && !isListeningForCommand && (speechGuide == null || !speechGuide.isSpeaking())) {
            wakeWordListener.resume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcManager != null) {
            nfcManager.onPause();
        }
        if (wakeWordListener != null) {
            wakeWordListener.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopDetection();
        if (wakeWordListener != null) {
            wakeWordListener.destroy();
        }
        if (textDetectorManager != null) {
            textDetectorManager.close();
        }
        if (navigationManager != null) {
            navigationManager.stopLocationTracking();
        }
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (speechGuide != null) {
            speechGuide.shutdown();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdownNow();
        }
        if (detector != null) {
            try {
                detector.close();
            } catch (Exception ignored) {
            }
        }
    }
}
