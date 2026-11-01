package com.visionguide.phone;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.os.Build;
import android.os.Parcelable;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

public final class NfcMedicineManager {
    public interface NfcMedicineCallback {
        void onMedicineTagDetected(String medicineDetails, String previewTitle);
    }

    private final Activity activity;
    private final NfcAdapter nfcAdapter;
    private final NfcMedicineCallback callback;
    private PendingIntent pendingIntent;
    private IntentFilter[] intentFiltersArray;

    public NfcMedicineManager(Activity activity, NfcMedicineCallback callback) {
        this.activity = activity;
        this.callback = callback;
        this.nfcAdapter = NfcAdapter.getDefaultAdapter(activity);
        initForegroundDispatch();
    }

    public boolean isNfcAvailable() {
        return nfcAdapter != null;
    }

    public boolean isNfcEnabled() {
        return nfcAdapter != null && nfcAdapter.isEnabled();
    }

    private void initForegroundDispatch() {
        if (nfcAdapter == null) return;

        Intent intent = new Intent(activity, activity.getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;

        pendingIntent = PendingIntent.getActivity(activity, 0, intent, flags);

        IntentFilter ndef = new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED);
        try {
            ndef.addDataType("*/*");
        } catch (IntentFilter.MalformedMimeTypeException ignored) {
        }

        IntentFilter tag = new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED);
        IntentFilter tech = new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED);

        intentFiltersArray = new IntentFilter[]{ndef, tag, tech};
    }

    public void onResume() {
        if (nfcAdapter != null && nfcAdapter.isEnabled() && pendingIntent != null) {
            try {
                nfcAdapter.enableForegroundDispatch(activity, pendingIntent, intentFiltersArray, null);
            } catch (Exception ignored) {
            }
        }
    }

    public void onPause() {
        if (nfcAdapter != null) {
            try {
                nfcAdapter.disableForegroundDispatch(activity);
            } catch (Exception ignored) {
            }
        }
    }

    public boolean handleIntent(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action) ||
            NfcAdapter.ACTION_TAG_DISCOVERED.equals(action) ||
            NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {

            String detectedDetails = extractNfcContent(intent);
            if (detectedDetails != null && !detectedDetails.isEmpty()) {
                String preview = extractTitle(detectedDetails);
                if (callback != null) {
                    callback.onMedicineTagDetected(detectedDetails, preview);
                }
                return true;
            }
        }
        return false;
    }

    private String extractNfcContent(Intent intent) {
        // 1. Try NDEF messages first
        Parcelable[] rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
        if (rawMessages != null && rawMessages.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (Parcelable rawMsg : rawMessages) {
                NdefMessage msg = (NdefMessage) rawMsg;
                for (NdefRecord record : msg.getRecords()) {
                    String parsed = parseRecord(record);
                    if (parsed != null && !parsed.isEmpty()) {
                        if (sb.length() > 0) sb.append(". ");
                        sb.append(parsed);
                    }
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }

        // 2. Try NDEF tech direct read from Tag
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag != null) {
            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                try {
                    ndef.connect();
                    NdefMessage ndefMessage = ndef.getCachedNdefMessage();
                    if (ndefMessage != null) {
                        StringBuilder sb = new StringBuilder();
                        for (NdefRecord record : ndefMessage.getRecords()) {
                            String parsed = parseRecord(record);
                            if (parsed != null && !parsed.isEmpty()) {
                                if (sb.length() > 0) sb.append(". ");
                                sb.append(parsed);
                            }
                        }
                        ndef.close();
                        if (sb.length() > 0) {
                            return sb.toString();
                        }
                    }
                    ndef.close();
                } catch (Exception ignored) {
                }
            }

            // 3. If tag has no NDEF records, return Tag Serial Number / ID for identification
            byte[] idBytes = tag.getId();
            if (idBytes != null && idBytes.length > 0) {
                StringBuilder hex = new StringBuilder();
                for (byte b : idBytes) {
                    hex.append(String.format(Locale.US, "%02X", b));
                }
                return "Medicine Tag ID " + hex.toString();
            }
        }

        return null;
    }

    private String parseRecord(NdefRecord record) {
        if (record == null) return null;
        short tnf = record.getTnf();
        byte[] type = record.getType();

        // Well-Known Text Record
        if (tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type, NdefRecord.RTD_TEXT)) {
            try {
                byte[] payload = record.getPayload();
                if (payload == null || payload.length == 0) return null;
                // Status byte: bit 7 = UTF-8 (0) or UTF-16 (1), bits 5-0 = language code length
                String textEncoding = ((payload[0] & 0x80) == 0) ? "UTF-8" : "UTF-16";
                int languageCodeLength = payload[0] & 0x3F;
                int textLength = payload.length - 1 - languageCodeLength;
                if (textLength <= 0) return null;

                return new String(payload, 1 + languageCodeLength, textLength, Charset.forName(textEncoding)).trim();
            } catch (Exception ignored) {
            }
        }

        // Well-Known URI Record
        if (tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type, NdefRecord.RTD_URI)) {
            try {
                byte[] payload = record.getPayload();
                if (payload != null && payload.length > 1) {
                    return new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8).trim();
                }
            } catch (Exception ignored) {
            }
        }

        // MIME Media text / plain
        if (tnf == NdefRecord.TNF_MIME_MEDIA) {
            try {
                byte[] payload = record.getPayload();
                if (payload != null && payload.length > 0) {
                    return new String(payload, StandardCharsets.UTF_8).trim();
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private String extractTitle(String fullText) {
        String[] lines = fullText.split("[.\\n]");
        if (lines.length > 0 && !lines[0].trim().isEmpty()) {
            return lines[0].trim();
        }
        return fullText.length() > 30 ? fullText.substring(0, 30) + "..." : fullText;
    }
}

