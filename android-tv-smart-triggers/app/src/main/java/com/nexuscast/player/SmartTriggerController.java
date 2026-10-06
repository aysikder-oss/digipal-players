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
            for (int i = 0; i < triggers.length(); i++) {
                JSONObject trigger = triggers.optJSONObject(i);
                if (trigger != null && "sensor".equals(trigger.optString("triggerType"))) {
                    hasSensor = true;
                }
            }
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
        else hardware.stop();
    }

    void startBleScan() {
        if (destroyed) return;
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
            if (bleRequested || pendingWebPermission != null) {
                emit("hw:permissionDenied", new JSONObject());
                return;
            }
            bleRequested = true;
            activity.requestPermissions(missing.toArray(new String[0]), BLE_PERMISSION_REQUEST);
            return;
        }
        enable(true);
        hardware.startBleScan();
    }

    void requestWebPermission(PermissionRequest request) {
        if (destroyed || request == null || !host.trustedOrigin(request.getOrigin().toString())
                || pendingWebPermission != null || bleRequested) {
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
        pendingWebPermission = request;
        pendingResources = resources.toArray(new String[0]);
        if (permissions.isEmpty()) finishWebPermission();
        else activity.requestPermissions(permissions.toArray(new String[0]), WEB_PERMISSION_REQUEST);
    }

    void cancelWebPermission(PermissionRequest request) {
        if (request == pendingWebPermission) {
            pendingWebPermission = null;
            pendingResources = null;
        }
    }

    void navigationStarted() {
        stopLearn();
        if (pendingWebPermission != null) {
            pendingWebPermission.deny();
            pendingWebPermission = null;
            pendingResources = null;
        }
    }

    private void finishWebPermission() {
        PermissionRequest request = pendingWebPermission;
        if (request == null) return;
        List<String> granted = new ArrayList<>();
        if (host.trustedOrigin(request.getOrigin().toString())) {
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
        if (code == WEB_PERMISSION_REQUEST) finishWebPermission();
        if (code == BLE_PERMISSION_REQUEST) {
            bleRequested = false;
            if (destroyed) return;
            boolean granted = Build.VERSION.SDK_INT >= 31
                    ? activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    : Build.VERSION.SDK_INT < 23 || activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (granted) { enable(true); hardware.startBleScan(); }
            else emit("hw:permissionDenied", new JSONObject());
        }
    }

    void destroy() {
        navigationStarted();
        destroyed = true;
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
