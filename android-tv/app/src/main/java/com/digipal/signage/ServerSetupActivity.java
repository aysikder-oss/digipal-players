package com.digipal.signage;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
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
import android.widget.ImageView;
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

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        stopDiscovery();
        setContentView(buildUI());
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private boolean isWideScreen() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        return w > h || w > dp(900);
    }

    @SuppressLint("SetTextI18n")
    private View buildUI() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#ffffff"));
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.parseColor("#ffffff"));

        boolean wide = isWideScreen();
        root.setPadding(dp(24), wide ? dp(32) : dp(48), dp(24), dp(32));

        ImageView logo = new ImageView(this);
        try {
            int logoResId = getResources().getIdentifier("player_logo", "drawable", getPackageName());
            if (logoResId != 0) {
                logo.setImageResource(logoResId);
                logo.setAdjustViewBounds(true);
                logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(280), dp(68));
                logoParams.gravity = Gravity.CENTER_HORIZONTAL;
                logoParams.bottomMargin = dp(8);
                logo.setLayoutParams(logoParams);
                root.addView(logo);
            }
        } catch (Exception ignored) {}

        TextView subtitle = new TextView(this);
        subtitle.setText("Connect to your signage server");
        subtitle.setTextColor(Color.parseColor("#64748b"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(4);
        subtitleParams.bottomMargin = wide ? dp(24) : dp(36);
        subtitle.setLayoutParams(subtitleParams);
        root.addView(subtitle);

        if (wide) {
            buildLandscapeCards(root);
        } else {
            buildPortraitCards(root);
        }

        addPrivacyFooter(root);

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int rootWidth = wide ? Math.min(dp(1400), screenWidth - dp(48)) : Math.min(dp(540), screenWidth - dp(32));

        FrameLayout.LayoutParams rootParams = new FrameLayout.LayoutParams(
            rootWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        rootParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.setLayoutParams(rootParams);

        scrollView.addView(root);

        FrameLayout centerer = new FrameLayout(this);
        centerer.setBackgroundColor(Color.parseColor("#ffffff"));
        centerer.addView(scrollView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return centerer;
    }

    private void buildPortraitCards(LinearLayout root) {
        root.addView(buildCard(Color.parseColor("#ffffff"), Color.parseColor("#f1f5f9"), buildCloudCardContent()));
        addOrDividerHorizontal(root);
        root.addView(buildCard(Color.parseColor("#ffffff"), Color.parseColor("#f1f5f9"), buildDiscoverCardContent()));
        addOrDividerHorizontal(root);
        root.addView(buildCard(Color.parseColor("#ffffff"), Color.parseColor("#f1f5f9"), buildManualCardContent()));
    }

    private void buildLandscapeCards(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(rowParams);

        LinearLayout cloudCard = buildCard(Color.parseColor("#ffffff"), Color.parseColor("#f1f5f9"), buildCloudCardContent());
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cloudCard.setLayoutParams(p1);
        row.addView(cloudCard);

        addOrDividerVertical(row);

        LinearLayout discoverCard = buildCard(Color.parseColor("#ffffff"), Color.parseColor("#f1f5f9"), buildDiscoverCardContent());
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        discoverCard.setLayoutParams(p2);
        row.addView(discoverCard);

        addOrDividerVertical(row);

        LinearLayout manualCard = buildCard(Color.parseColor("#ffffff"), Color.parseColor("#f1f5f9"), buildManualCardContent());
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        manualCard.setLayoutParams(p3);
        row.addView(manualCard);

        root.addView(row);
    }

    private LinearLayout buildCard(int bgColor, int borderColor, View content) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(bgColor);
        cardBg.setCornerRadius(dp(20));
        cardBg.setStroke(dp(1), borderColor);
        card.setBackground(cardBg);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(4));
        }

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(cardParams);
        card.addView(content);
        return card;
    }

    private ImageView buildCardIcon(String drawableName) {
        ImageView icon = new ImageView(this);
        try {
            int resId = getResources().getIdentifier(drawableName, "drawable", getPackageName());
            if (resId != 0) {
                icon.setImageResource(resId);
            }
        } catch (Exception ignored) {}
        icon.setScaleType(ImageView.ScaleType.FIT_START);
        icon.setAdjustViewBounds(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(56), dp(56));
        params.bottomMargin = dp(14);
        icon.setLayoutParams(params);
        return icon;
    }

    @SuppressLint("SetTextI18n")
    private View buildCloudCardContent() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        layout.addView(buildCardIcon("ic_cloud_server"));

        TextView title = new TextView(this);
        title.setText("Use Cloud Server");
        title.setTextColor(Color.parseColor("#0f172a"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(null, Typeface.BOLD);
        layout.addView(title);

        TextView desc = new TextView(this);
        desc.setText("Managed hosting — always up to date");
        desc.setTextColor(Color.parseColor("#64748b"));
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descParams.topMargin = dp(6);
        descParams.bottomMargin = dp(16);
        desc.setLayoutParams(descParams);
        layout.addView(desc);

        Button btn = createButton("Connect to Cloud Server \u203a", "#3b82f6");
        btn.setOnClickListener(v -> {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit()
                .remove(KEY_SERVER_MODE)
                .remove(KEY_SERVER_URL)
                .putBoolean("cloud_pairing_pending", true)
                .apply();
            launchPlayer();
        });
        layout.addView(btn);

        return layout;
    }

    @SuppressLint("SetTextI18n")
    private View buildDiscoverCardContent() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        layout.addView(buildCardIcon("ic_discover_servers"));

        TextView title = new TextView(this);
        title.setText("Discover Local Servers");
        title.setTextColor(Color.parseColor("#0f172a"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(null, Typeface.BOLD);
        layout.addView(title);

        TextView desc = new TextView(this);
        desc.setText("Scan your network for Digipal local servers");
        desc.setTextColor(Color.parseColor("#64748b"));
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descParams.topMargin = dp(6);
        descParams.bottomMargin = dp(16);
        desc.setLayoutParams(descParams);
        layout.addView(desc);

        scanButton = createButton("Scan for Local Servers \u203a", "#14b8a6");
        scanButton.setOnClickListener(v -> startDiscovery());
        layout.addView(scanButton);

        LinearLayout scanRow = new LinearLayout(this);
        scanRow.setOrientation(LinearLayout.HORIZONTAL);
        scanRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams scanRowParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scanRowParams.topMargin = dp(10);
        scanRow.setLayoutParams(scanRowParams);

        scanProgress = new ProgressBar(this);
        scanProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        progressParams.rightMargin = dp(8);
        scanProgress.setLayoutParams(progressParams);
        scanRow.addView(scanProgress);

        scanStatus = new TextView(this);
        scanStatus.setTextColor(Color.parseColor("#64748b"));
        scanStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        scanStatus.setVisibility(View.GONE);
        scanRow.addView(scanStatus);

        layout.addView(scanRow);

        serverListContainer = new LinearLayout(this);
        serverListContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listParams.topMargin = dp(6);
        serverListContainer.setLayoutParams(listParams);
        layout.addView(serverListContainer);

        return layout;
    }

    @SuppressLint("SetTextI18n")
    private View buildManualCardContent() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        layout.addView(buildCardIcon("ic_manual_server"));

        TextView title = new TextView(this);
        title.setText("Enter Server Address");
        title.setTextColor(Color.parseColor("#0f172a"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(null, Typeface.BOLD);
        layout.addView(title);

        TextView desc = new TextView(this);
        desc.setText("Enter your server URL directly. Use http:// for local network servers.");
        desc.setTextColor(Color.parseColor("#64748b"));
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descParams.topMargin = dp(6);
        descParams.bottomMargin = dp(14);
        desc.setLayoutParams(descParams);
        layout.addView(desc);

        manualUrlInput = new EditText(this);
        manualUrlInput.setHint("e.g. http://192.168.1.100:8787");
        manualUrlInput.setHintTextColor(Color.parseColor("#94a3b8"));
        manualUrlInput.setTextColor(Color.parseColor("#0f172a"));
        manualUrlInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        manualUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        manualUrlInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        manualUrlInput.setSingleLine(true);
        manualUrlInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(Color.parseColor("#f8fafc"));
        inputBg.setCornerRadius(dp(8));
        inputBg.setStroke(dp(1), Color.parseColor("#cbd5e1"));
        manualUrlInput.setBackground(inputBg);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.bottomMargin = dp(12);
        manualUrlInput.setLayoutParams(inputParams);
        layout.addView(manualUrlInput);

        Button connectBtn = createButton("Connect to Manual Server \u203a", "#f97316");
        connectBtn.setOnClickListener(v -> {
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
        layout.addView(connectBtn);

        return layout;
    }

    @SuppressLint("SetTextI18n")
    private void addOrDividerHorizontal(LinearLayout parent) {
        LinearLayout dividerRow = new LinearLayout(this);
        dividerRow.setOrientation(LinearLayout.HORIZONTAL);
        dividerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dividerRowParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dividerRowParams.topMargin = dp(16);
        dividerRowParams.bottomMargin = dp(16);
        dividerRow.setLayoutParams(dividerRowParams);

        View lineLeft = new View(this);
        lineLeft.setBackgroundColor(Color.parseColor("#e2e8f0"));
        dividerRow.addView(lineLeft, new LinearLayout.LayoutParams(0, dp(1), 1));

        TextView orText = new TextView(this);
        orText.setText("OR");
        orText.setTextColor(Color.parseColor("#94a3b8"));
        orText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        orText.setTypeface(null, Typeface.BOLD);
        orText.setPadding(dp(14), 0, dp(14), 0);
        dividerRow.addView(orText, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View lineRight = new View(this);
        lineRight.setBackgroundColor(Color.parseColor("#e2e8f0"));
        dividerRow.addView(lineRight, new LinearLayout.LayoutParams(0, dp(1), 1));

        parent.addView(dividerRow);
    }

    @SuppressLint("SetTextI18n")
    private void addOrDividerVertical(LinearLayout parent) {
        LinearLayout divCol = new LinearLayout(this);
        divCol.setOrientation(LinearLayout.VERTICAL);
        divCol.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams divColParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        divColParams.leftMargin = dp(10);
        divColParams.rightMargin = dp(10);
        divCol.setLayoutParams(divColParams);

        View lineTop = new View(this);
        lineTop.setBackgroundColor(Color.parseColor("#e2e8f0"));
        divCol.addView(lineTop, new LinearLayout.LayoutParams(dp(1), 0, 1));

        TextView orText = new TextView(this);
        orText.setText("OR");
        orText.setTextColor(Color.parseColor("#94a3b8"));
        orText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        orText.setTypeface(null, Typeface.BOLD);
        orText.setPadding(0, dp(10), 0, dp(10));
        divCol.addView(orText, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View lineBottom = new View(this);
        lineBottom.setBackgroundColor(Color.parseColor("#e2e8f0"));
        divCol.addView(lineBottom, new LinearLayout.LayoutParams(dp(1), 0, 1));

        parent.addView(divCol);
    }

    @SuppressLint("SetTextI18n")
    private void addPrivacyFooter(LinearLayout parent) {
        TextView footer = new TextView(this);
        footer.setText("Your connection details are private and secure.");
        footer.setTextColor(Color.parseColor("#94a3b8"));
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(28);
        footer.setLayoutParams(params);
        parent.addView(footer);
    }

    private Button createButton(String text, String colorHex) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setAllCaps(false);
        button.setTypeface(null, Typeface.BOLD);
        button.setPadding(dp(16), dp(12), dp(16), dp(12));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(colorHex));
        bg.setCornerRadius(dp(10));
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
        cardBg.setColor(Color.parseColor("#ffffff"));
        cardBg.setCornerRadius(dp(8));
        cardBg.setStroke(dp(1), Color.parseColor("#c7d2fe"));
        card.setBackground(cardBg);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(8);
        card.setLayoutParams(cardParams);

        TextView nameView = new TextView(this);
        nameView.setText(server.name);
        nameView.setTextColor(Color.parseColor("#0f172a"));
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        nameView.setTypeface(null, Typeface.BOLD);
        card.addView(nameView);

        TextView addrView = new TextView(this);
        addrView.setText(server.host + ":" + server.port);
        addrView.setTextColor(Color.parseColor("#64748b"));
        addrView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams addrParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addrParams.topMargin = dp(2);
        addrView.setLayoutParams(addrParams);
        card.addView(addrView);

        Button connectBtn = createButton("Connect \u203a", "#14b8a6");
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        btnParams.topMargin = dp(8);
        connectBtn.setLayoutParams(btnParams);
        connectBtn.setOnClickListener(v -> {
            saveServerChoice("local", server.getUrl());
            launchPlayer();
        });
        card.addView(connectBtn);

        serverListContainer.addView(card);
    }

    private boolean isPrivateNetworkUrl(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) return false;
            if (host.equals("localhost") || host.equals("127.0.0.1")) return true;
            Pattern privateIp = Pattern.compile(
                "^(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|" +
                "172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}|" +
                "192\\.168\\.\\d{1,3}\\.\\d{1,3})$"
            );
            return privateIp.matcher(host).matches();
        } catch (Exception e) {
            return false;
        }
    }

    private void saveServerChoice(String mode, String url) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
            .putString(KEY_SERVER_MODE, mode)
            .putString(KEY_SERVER_URL, url)
            .apply();
    }

    private void launchPlayer() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopDiscovery();
    }
}
