package com.digipal.signage;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class ServerSetupActivity extends Activity {

    private static final String PREFS_NAME = "DigipalPrefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_SERVER_MODE = "server_mode";
    private static final String SERVICE_TYPE = "_digipal._tcp.";

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private boolean isDiscovering = false;
    private final List<DiscoveredServer> discoveredServers = new ArrayList<>();
    private LinearLayout serverListContainer;
    private ProgressBar scanProgress;
    private TextView scanStatus;
    private Button scanButton;
    private EditText manualUrlInput;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable scanTimeoutRunnable;

    static class DiscoveredServer {
        String name;
        String host;
        int port;

        DiscoveredServer(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }

        String getUrl() {
            return "http://" + host + ":" + port;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedMode = prefs.getString(KEY_SERVER_MODE, null);
        if (savedMode != null) {
            launchPlayer();
            return;
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);

        setContentView(buildUI());
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    @SuppressLint("SetTextI18n")
    private View buildUI() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#0a0e1a"));
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(48));

        TextView title = new TextView(this);
        title.setText("Digipal Player");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Connect to your signage server");
        subtitle.setTextColor(Color.parseColor("#94a3b8"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(8);
        subtitleParams.bottomMargin = dp(32);
        subtitle.setLayoutParams(subtitleParams);
        root.addView(subtitle);

        Button cloudButton = createButton("Use Cloud Server (digipalsignage.com)", "#3b82f6");
        cloudButton.setOnClickListener(v -> {
            saveServerChoice("cloud", BuildConfig.SERVER_URL);
            launchPlayer();
        });
        root.addView(cloudButton);

        addSectionDivider(root, "OR connect to a local server");

        TextView scanLabel = new TextView(this);
        scanLabel.setText("Discover Local Servers");
        scanLabel.setTextColor(Color.WHITE);
        scanLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        scanLabel.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams scanLabelParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scanLabelParams.topMargin = dp(8);
        scanLabel.setLayoutParams(scanLabelParams);
        root.addView(scanLabel);

        TextView scanDesc = new TextView(this);
        scanDesc.setText("Scan your network for Digipal local servers");
        scanDesc.setTextColor(Color.parseColor("#94a3b8"));
        scanDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams scanDescParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scanDescParams.topMargin = dp(4);
        scanDescParams.bottomMargin = dp(12);
        scanDesc.setLayoutParams(scanDescParams);
        root.addView(scanDesc);

        scanButton = createButton("Scan for Local Servers", "#6366f1");
        scanButton.setOnClickListener(v -> startDiscovery());
        root.addView(scanButton);

        LinearLayout scanRow = new LinearLayout(this);
        scanRow.setOrientation(LinearLayout.HORIZONTAL);
        scanRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams scanRowParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scanRowParams.topMargin = dp(12);
        scanRow.setLayoutParams(scanRowParams);

        scanProgress = new ProgressBar(this);
        scanProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        progressParams.rightMargin = dp(8);
        scanProgress.setLayoutParams(progressParams);
        scanRow.addView(scanProgress);

        scanStatus = new TextView(this);
        scanStatus.setTextColor(Color.parseColor("#94a3b8"));
        scanStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        scanStatus.setVisibility(View.GONE);
        scanRow.addView(scanStatus);

        root.addView(scanRow);

        serverListContainer = new LinearLayout(this);
        serverListContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listParams.topMargin = dp(8);
        serverListContainer.setLayoutParams(listParams);
        root.addView(serverListContainer);

        addSectionDivider(root, "OR enter server address manually");

        manualUrlInput = new EditText(this);
        manualUrlInput.setHint("e.g. http://192.168.1.100:8787");
        manualUrlInput.setHintTextColor(Color.parseColor("#475569"));
        manualUrlInput.setTextColor(Color.WHITE);
        manualUrlInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        manualUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        manualUrlInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        manualUrlInput.setSingleLine(true);
        manualUrlInput.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(Color.parseColor("#1e293b"));
        inputBg.setCornerRadius(dp(8));
        inputBg.setStroke(dp(1), Color.parseColor("#334155"));
        manualUrlInput.setBackground(inputBg);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = dp(8);
        manualUrlInput.setLayoutParams(inputParams);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedMode = prefs.getString(KEY_SERVER_MODE, "cloud");
        if ("local".equals(savedMode)) {
            String savedUrl = prefs.getString(KEY_SERVER_URL, "");
            if (!savedUrl.isEmpty() && !savedUrl.equals(BuildConfig.SERVER_URL)) {
                manualUrlInput.setText(savedUrl);
            }
        }

        root.addView(manualUrlInput);

        Button connectManual = createButton("Connect to Manual Server", "#10b981");
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        connectParams.topMargin = dp(12);
        connectManual.setLayoutParams(connectParams);
        connectManual.setOnClickListener(v -> {
            String url = manualUrlInput.getText().toString().trim();
            if (url.isEmpty()) {
                manualUrlInput.setError("Please enter a server URL");
                return;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }
            if (url.startsWith("http://") && !isPrivateNetworkUrl(url)) {
                manualUrlInput.setError("HTTP is only allowed for local network addresses. Use https:// for public servers.");
                return;
            }
            saveServerChoice("local", url);
            launchPlayer();
        });
        root.addView(connectManual);

        int maxWidth = dp(500);

        FrameLayout.LayoutParams rootParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rootParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.setLayoutParams(rootParams);

        scrollView.addView(root);

        FrameLayout centerer = new FrameLayout(this);
        centerer.setBackgroundColor(Color.parseColor("#0a0e1a"));
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
            Math.min(maxWidth, getResources().getDisplayMetrics().widthPixels),
            ViewGroup.LayoutParams.MATCH_PARENT);
        scrollParams.gravity = Gravity.CENTER_HORIZONTAL;
        scrollView.setLayoutParams(scrollParams);
        centerer.addView(scrollView);

        return centerer;
    }

    @SuppressLint("SetTextI18n")
    private void addSectionDivider(LinearLayout parent, String text) {
        LinearLayout dividerRow = new LinearLayout(this);
        dividerRow.setOrientation(LinearLayout.HORIZONTAL);
        dividerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dividerRowParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dividerRowParams.topMargin = dp(24);
        dividerRowParams.bottomMargin = dp(16);
        dividerRow.setLayoutParams(dividerRowParams);

        View lineLeft = new View(this);
        lineLeft.setBackgroundColor(Color.parseColor("#334155"));
        dividerRow.addView(lineLeft, new LinearLayout.LayoutParams(0, dp(1), 1));

        TextView dividerText = new TextView(this);
        dividerText.setText(text);
        dividerText.setTextColor(Color.parseColor("#64748b"));
        dividerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dividerText.setPadding(dp(12), 0, dp(12), 0);
        dividerRow.addView(dividerText, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View lineRight = new View(this);
        lineRight.setBackgroundColor(Color.parseColor("#334155"));
        dividerRow.addView(lineRight, new LinearLayout.LayoutParams(0, dp(1), 1));

        parent.addView(dividerRow);
    }

    private Button createButton(String text, String colorHex) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setAllCaps(false);
        button.setTypeface(null, Typeface.BOLD);
        button.setPadding(dp(16), dp(14), dp(16), dp(14));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(colorHex));
        bg.setCornerRadius(dp(8));
        button.setBackground(bg);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        button.setLayoutParams(params);

        return button;
    }

    @SuppressLint("SetTextI18n")
    private void startDiscovery() {
        if (isDiscovering) {
            stopDiscovery();
            return;
        }

        discoveredServers.clear();
        serverListContainer.removeAllViews();
        scanProgress.setVisibility(View.VISIBLE);
        scanStatus.setVisibility(View.VISIBLE);
        scanStatus.setText("Scanning network...");
        scanButton.setText("Stop Scanning");

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                isDiscovering = true;
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo serviceInfo) {
                        String host = serviceInfo.getHost() != null
                            ? serviceInfo.getHost().getHostAddress() : "";
                        int port = serviceInfo.getPort();
                        String name = serviceInfo.getServiceName();

                        if (host != null && !host.isEmpty()) {
                            DiscoveredServer server = new DiscoveredServer(name, host, port);
                            mainHandler.post(() -> addDiscoveredServer(server));
                        }
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                isDiscovering = false;
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                isDiscovering = false;
                mainHandler.post(() -> {
                    scanProgress.setVisibility(View.GONE);
                    scanStatus.setText("Discovery failed. Try entering the address manually.");
                    scanButton.setText("Scan for Local Servers");
                });
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                isDiscovering = false;
            }
        };

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            scanProgress.setVisibility(View.GONE);
            scanStatus.setText("Network discovery not available on this device.");
            scanButton.setText("Scan for Local Servers");
            return;
        }

        if (scanTimeoutRunnable != null) {
            mainHandler.removeCallbacks(scanTimeoutRunnable);
        }
        scanTimeoutRunnable = () -> {
            stopDiscovery();
            if (discoveredServers.isEmpty()) {
                scanStatus.setText("No servers found. Make sure your local server is running.");
            } else {
                scanStatus.setText(discoveredServers.size() + " server(s) found");
            }
        };
        mainHandler.postDelayed(scanTimeoutRunnable, 8000);
    }

    private void stopDiscovery() {
        if (scanTimeoutRunnable != null) {
            mainHandler.removeCallbacks(scanTimeoutRunnable);
            scanTimeoutRunnable = null;
        }
        if (isDiscovering && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception ignored) {
            }
        }
        isDiscovering = false;
        if (scanProgress != null) {
            scanProgress.setVisibility(View.GONE);
        }
        if (scanButton != null) {
            scanButton.setText("Scan for Local Servers");
        }
    }

    @SuppressLint("SetTextI18n")
    private void addDiscoveredServer(DiscoveredServer server) {
        for (DiscoveredServer existing : discoveredServers) {
            if (existing.host.equals(server.host) && existing.port == server.port) {
                return;
            }
        }
        discoveredServers.add(server);
        scanStatus.setText(discoveredServers.size() + " server(s) found");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#1e293b"));
        cardBg.setCornerRadius(dp(8));
        cardBg.setStroke(dp(1), Color.parseColor("#334155"));
        card.setBackground(cardBg);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(8);
        card.setLayoutParams(cardParams);

        TextView nameView = new TextView(this);
        nameView.setText(server.name);
        nameView.setTextColor(Color.WHITE);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        nameView.setTypeface(null, Typeface.BOLD);
        card.addView(nameView);

        TextView addrView = new TextView(this);
        addrView.setText(server.host + ":" + server.port);
        addrView.setTextColor(Color.parseColor("#94a3b8"));
        addrView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams addrParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addrParams.topMargin = dp(2);
        addrView.setLayoutParams(addrParams);
        card.addView(addrView);

        Button connectBtn = createButton("Connect", "#10b981");
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        btnParams.topMargin = dp(8);
        connectBtn.setLayoutParams(btnParams);
        connectBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        connectBtn.setOnClickListener(v -> {
            saveServerChoice("local", server.getUrl());
            launchPlayer();
        });
        card.addView(connectBtn);

        serverListContainer.addView(card);
    }

    private void saveServerChoice(String mode, String url) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
            .putString(KEY_SERVER_MODE, mode)
            .putString(KEY_SERVER_URL, url)
            .apply();
    }

    private void launchPlayer() {
        stopDiscovery();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private static final Pattern PRIVATE_IP_PATTERN = Pattern.compile(
        "^(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}" +
        "|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}" +
        "|192\\.168\\.\\d{1,3}\\.\\d{1,3}" +
        "|127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}" +
        "|localhost" +
        "|\\[::1\\])$"
    );

    private boolean isPrivateNetworkUrl(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) return false;
            return PRIVATE_IP_PATTERN.matcher(host).matches();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        stopDiscovery();
        super.onDestroy();
    }
}
