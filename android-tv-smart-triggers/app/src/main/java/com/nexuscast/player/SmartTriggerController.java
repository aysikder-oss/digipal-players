package com.nexuscast.player;

import android.app.Activity;
import android.os.Build;
import android.webkit.PermissionRequest;
import android.webkit.WebView;
import android.content.pm.PackageManager;
import android.Manifest;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * ST-only lifecycle. No automatic camera, microphone or BLE activation:
 * web media permission requests and explicit Learn/Scan commands initiate them.
 * Native hardware emits one existing DOM event per signal, never a second
 * callback channel. The shared web player remains the sole trigger evaluator.
 */
final class SmartTriggerController implements HardwareManager.HardwareListener {
    static final int WEB_PERMISSION_REQUEST = 2101;
    static final int BLE_PERMISSION_REQUEST = 2102;
    interface Host {
        WebView webView();
        boolean trustedOrigin(String origin);
    }
    private final Activity activity;
    private final Host host;
    private final HardwareManager hardware;
    private boolean configured;
    private boolean learning;
    private boolean explicitlyEnabled;
    private PermissionRequest pendingWebPermission;
    private String[] pendingResources;
    private boolean bleRequested;
    private boolean destroyed;
    private boolean cameraConfigured;
    private boolean soundConfigured;
    private final Handler permissionHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<PermissionRequest> webQueue = new ArrayDeque<>();
    private final IdentityHashMap<PermissionRequest, Runnable> deadlines = new IdentityHashMap<>();
    private final IdentityHashMap<PermissionRequest, List<String>> queuedResources = new IdentityHashMap<>();
    private int activeDialog;
    private boolean bleQueued;
    private int navigationGeneration;
    private int bleGeneration;
    private final Runnable bleDeadline = () -> {
        if (!bleRequested && !bleQueued) return;
        bleQueued = false;
        bleGeneration = -1;
        Log.w("STPermissions", "Hardware permission timed out");
        emit("hw:permissionDenied", new JSONObject());
    };

    SmartTriggerController(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        this.hardware = new HardwareManager(activity, this);
        explicitlyEnabled = activity.getSharedPreferences("digipal_prefs", Activity.MODE_PRIVATE)
                .getBoolean("smart_triggers_enabled", false);
        if (explicitlyEnabled) hardware.start();
    }

    // Keep the ST shell active throughout native playback: camera frame capture,
    // audio analysis, hardware input and pending JS revert/cooldown timers may
    // exist even after a config update removes the trigger that created them.
    boolean requiresActiveWebView() { return !destroyed; }

    void setConfig(String json) {
        if (destroyed || json == null || json.length() > 262144) return;
        try {
            JSONArray triggers = new JSONArray(json);
            boolean hasSensor = false;
            boolean hasCamera = false;
            boolean hasSound = false;
            for (int i = 0; i < triggers.length(); i++) {
                JSONObject trigger = triggers.optJSONObject(i);
                if (trigger != null && "sensor".equals(trigger.optString("triggerType"))) {
                    hasSensor = true;
                }
                if (trigger != null) {
                    String type = trigger.optString("triggerType");
                    if ("gesture".equals(type) || "audience".equals(type) || "motion".equals(type)) hasCamera = true;
                    if ("sound".equals(type)) hasSound = true;
                }
            }
            if (cameraConfigured && !hasCamera) cancelInput(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
            if (soundConfigured && !hasSound) cancelInput(PermissionRequest.RESOURCE_AUDIO_CAPTURE);
            cameraConfigured = hasCamera;
            soundConfigured = hasSound;
            configured = hasSensor;
            updateHardware();
        } catch (org.json.JSONException ignored) {
            // Invalid incoming config must not implicitly enable hardware.
        }
    }

    void enable(boolean enabled) {
        explicitlyEnabled = enabled;
        activity.getSharedPreferences("digipal_prefs", Activity.MODE_PRIVATE).edit()
                .putBoolean("smart_triggers_enabled", enabled).apply();
        updateHardware();
    }

    void startLearn(String payload) {
        if (destroyed) return;
        try {
            JSONObject options = new JSONObject(payload == null ? "{}" : payload);
            String filter = options.optString("deviceId", options.optString("deviceFilter", ""));
            learning = true;
            updateHardware();
            hardware.startLearnMode(filter);
        } catch (org.json.JSONException ignored) {}
    }

    void stopLearn() {
        learning = false;
        hardware.stopLearnMode();
        updateHardware();
    }

    String connectedDevices() { return hardware.getConnectedDevices().toString(); }

    private void updateHardware() {
        if (destroyed) return;
        if (configured || learning || explicitlyEnabled) hardware.start();
        else {
            bleQueued = false;
            if (bleRequested) bleGeneration = -1;
            permissionHandler.removeCallbacks(bleDeadline);
            hardware.stop();
        }
    }

    void startBleScan() {
        if (destroyed || bleRequested || bleQueued) return;
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            for (String p : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}) {
                if (activity.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
            }
        } else if (Build.VERSION.SDK_INT >= 23 &&
                activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!missing.isEmpty()) {
            bleQueued = true;
            permissionHandler.removeCallbacks(bleDeadline);
            permissionHandler.postDelayed(bleDeadline, 30000);
            pumpPermissions();
            return;
        }
        enable(true);
        hardware.startBleScan();
    }

    void requestWebPermission(PermissionRequest request) {
        if (destroyed || request == null || !host.trustedOrigin(request.getOrigin().toString())) {
            if (request != null) request.deny();
            return;
        }
        List<String> resources = new ArrayList<>();
        List<String> permissions = new ArrayList<>();
        for (String r : request.getResources()) {
            String permission = PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) ? Manifest.permission.CAMERA
                    : PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) ? Manifest.permission.RECORD_AUDIO : null;
            if (permission == null) continue;
            resources.add(r);
            if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(permission);
            }
        }
        if (resources.isEmpty()) { request.deny(); return; }
        // Already-authorized capture must not depend on an unrelated camera,
        // microphone or Bluetooth dialog completing.
        if (permissions.isEmpty()) {
            request.grant(resources.toArray(new String[0]));
            Log.i("STPermissions", "Authorized media capture granted");
            return;
        }
        if (deadlines.size() >= 8) {
            request.deny();
            Log.w("STPermissions", "Media permission queue full");
            return;
        }
        webQueue.add(request);
        queuedResources.put(request, resources);
        Runnable deadline = () -> {
            if (!deadlines.containsKey(request)) return;
            webQueue.remove(request);
            if (pendingWebPermission == request) {
                pendingWebPermission = null;
                pendingResources = null;
            }
            removeDeadline(request);
            request.deny();
            Log.w("STPermissions", "Media permission timed out");
            // The Android dialog remains in flight until its matching result.
            // Do not open another dialog or apply its result to a newer request.
            pumpPermissions();
        };
        deadlines.put(request, deadline);
        permissionHandler.postDelayed(deadline, 30000);
        Log.i("STPermissions", "Media permission queued");
        pumpPermissions();
    }

    private void removeDeadline(PermissionRequest request) {
        queuedResources.remove(request);
        Runnable timer = deadlines.remove(request);
        if (timer != null) permissionHandler.removeCallbacks(timer);
    }

    private void cancelInput(String resource) {
        for (PermissionRequest request : new ArrayList<>(webQueue)) {
            List<String> resources = queuedResources.get(request);
            if (resources != null) resources.remove(resource);
            if (resources == null || resources.isEmpty()) {
                webQueue.remove(request);
                removeDeadline(request);
                request.deny();
            }
        }
        if (pendingWebPermission != null) {
            List<String> resources = new ArrayList<>(java.util.Arrays.asList(pendingResources));
            resources.remove(resource);
            pendingResources = resources.toArray(new String[0]);
            if (resources.isEmpty()) {
                PermissionRequest request = pendingWebPermission;
                pendingWebPermission = null;
                pendingResources = null;
                removeDeadline(request);
                request.deny();
            }
        }
        // Keep activeDialog until Android acknowledges the original dialog;
        // its result must not be mistaken for a new permission operation.
        Log.i("STPermissions", "Disabled input permission cancelled");
        pumpPermissions();
    }

    private void pumpPermissions() {
        if (destroyed || activeDialog != 0) return;
        PermissionRequest request = webQueue.poll();
        if (request != null) {
            if (!host.trustedOrigin(request.getOrigin().toString())) {
                removeDeadline(request);
                request.deny();
                pumpPermissions();
                return;
            }
            List<String> resources = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String resource : queuedResources.get(request)) {
                String permission = PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) ? Manifest.permission.CAMERA
                        : PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) ? Manifest.permission.RECORD_AUDIO : null;
                if (permission == null) continue;
                resources.add(resource);
                if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(permission);
                }
            }
            pendingWebPermission = request;
            pendingResources = resources.toArray(new String[0]);
            if (missing.isEmpty()) { finishWebPermission(); pumpPermissions(); return; }
            activeDialog = WEB_PERMISSION_REQUEST;
            Log.i("STPermissions", "Media permission dialog started");
            activity.requestPermissions(missing.toArray(new String[0]), WEB_PERMISSION_REQUEST);
            return;
        }
        if (bleQueued) {
            bleQueued = false;
            bleRequested = true;
            bleGeneration = navigationGeneration;
            List<String> missing = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 31) {
                for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}) {
                    if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
                }
            } else if (Build.VERSION.SDK_INT >= 23
                    && activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
            if (missing.isEmpty()) { permissionsResult(BLE_PERMISSION_REQUEST); return; }
            activeDialog = BLE_PERMISSION_REQUEST;
            Log.i("STPermissions", "Hardware permission dialog started");
            activity.requestPermissions(missing.toArray(new String[0]), BLE_PERMISSION_REQUEST);
        }
    }

    void cancelWebPermission(PermissionRequest request) {
        webQueue.remove(request);
        removeDeadline(request);
        if (request == pendingWebPermission) {
            pendingWebPermission = null;
            pendingResources = null;
        }
        pumpPermissions();
    }

    void navigationStarted() {
        ++navigationGeneration;
        bleQueued = false;
        permissionHandler.removeCallbacks(bleDeadline);
        stopLearn();
        if (pendingWebPermission != null) {
            pendingWebPermission.deny();
            pendingWebPermission = null;
            pendingResources = null;
        }
        for (PermissionRequest request : webQueue) request.deny();
        webQueue.clear();
        for (Runnable timer : deadlines.values()) permissionHandler.removeCallbacks(timer);
        deadlines.clear();
        queuedResources.clear();
    }

    private void finishWebPermission() {
        PermissionRequest request = pendingWebPermission;
        if (request == null) return;
        removeDeadline(request);
        List<String> granted = new ArrayList<>();
        if (!destroyed && host.trustedOrigin(request.getOrigin().toString())) {
            for (String r : pendingResources) {
                String p = PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) ? Manifest.permission.CAMERA : Manifest.permission.RECORD_AUDIO;
                if (Build.VERSION.SDK_INT < 23 || activity.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) granted.add(r);
            }
        }
        pendingWebPermission = null;
        pendingResources = null;
        if (granted.isEmpty()) request.deny();
        else request.grant(granted.toArray(new String[0]));
    }

    void permissionsResult(int code) {
        if (code != WEB_PERMISSION_REQUEST && code != BLE_PERMISSION_REQUEST) return;
        if (activeDialog != code && !(code == BLE_PERMISSION_REQUEST && bleRequested)) return;
        activeDialog = 0;
        Log.i("STPermissions", "Permission dialog completed");
        if (code == WEB_PERMISSION_REQUEST) finishWebPermission();
        if (code == BLE_PERMISSION_REQUEST) {
            permissionHandler.removeCallbacks(bleDeadline);
            bleRequested = false;
            if (destroyed) return;
            boolean granted = Build.VERSION.SDK_INT >= 31
                    ? activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    : Build.VERSION.SDK_INT < 23 || activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (bleGeneration == navigationGeneration) {
                if (granted) { enable(true); hardware.startBleScan(); }
                else emit("hw:permissionDenied", new JSONObject());
            }
        }
        pumpPermissions();
    }

    void destroy() {
        destroyed = true;
        navigationStarted();
        hardware.stop();
    }

    private void emit(String name, JSONObject value) {
        activity.runOnUiThread(() -> {
            WebView view = host.webView();
            if (destroyed || view == null || !host.trustedOrigin(view.getUrl())) return;
            view.evaluateJavascript("window.dispatchEvent(new CustomEvent(" + JSONObject.quote(name)
                    + ",{detail:" + value + "}))", null);
        });
    }
    @Override public void onDeviceConnected(JSONObject value) { emit("hw:deviceConnected", value); }
    @Override public void onDeviceDisconnected(JSONObject value) { emit("hw:deviceDisconnected", value); }
    @Override public void onSignalCaptured(JSONObject value) { emit("hw:signalCaptured", value); }
    @Override public void onSignalEvent(JSONObject value) { emit("hw:signalEvent", value); }
}
