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

    private boolean landscape;

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
        Configuration cfg = getResources().getConfiguration();
        return cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
            || cfg.screenWidthDp >= 600;
    }

    @SuppressLint("SetTextI18n")
    private View buildUI() {
        landscape = isWideScreen();
        return landscape ? buildLandscapeUI() : buildPortraitUI();
    }

    @SuppressLint("SetTextI18n")
    private View buildLandscapeUI() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#ffffff"));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#ffffff"));
        root.setPadding(dp(32), dp(90), dp(32), dp(30));

        addLogoAndSubtitle(root, dp(24));
        buildLandscapeCards(root);
        addPrivacyFooter(root);

        scroll.addView(root, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    @SuppressLint("SetTextI18n")
    private View buildPortraitUI() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#ffffff"));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#ffffff"));
        root.setPadding(dp(20), dp(60), dp(20), dp(14));

        addLogoAndSubtitle(root, dp(18));
        buildPortraitCards(root);
        addPrivacyFooter(root);

        scroll.addView(root, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    @SuppressLint("SetTextI18n")
    private void addLogoAndSubtitle(LinearLayout root, int subtitleBottom) {
        try {
            int logoResId = getResources().getIdentifier("player_logo", "drawable", getPackageName());
            if (logoResId != 0) {
                ImageView logo = new ImageView(this);
                logo.setImageResource(logoResId);
                logo.setAdjustViewBounds(true);
                logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int logoH = dp(landscape ? 36 : 24);
                LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, logoH);
                logoParams.gravity = Gravity.CENTER_HORIZONTAL;
                logoParams.bottomMargin = dp(landscape ? 6 : 8);
                logo.setLayoutParams(logoParams);
                root.addView(logo);
            }
        } catch (Exception ignored) {}

        TextView subtitle = new TextView(this);
        subtitle.setText("Connect to your signage server");
        subtitle.setTextColor(Color.parseColor("#94a3b8"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.bottomMargin = subtitleBottom;
        subtitle.setLayoutParams(subtitleParams);
        root.addView(subtitle);
    }

    private void buildLandscapeCards(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(rowParams);

        int cardH = dp(220);

        LinearLayout cloudCard = buildCard(buildCloudCardContent(), cardH);
        cloudCard.setLayoutParams(new LinearLayout.LayoutParams(0, cardH, 1f));
        row.addView(cloudCard);

        addOrDividerVertical(row, cardH);

        LinearLayout discoverCard = buildCard(buildDiscoverCardContent(), cardH);
        discoverCard.setLayoutParams(new LinearLayout.LayoutParams(0, cardH, 1f));
        row.addView(discoverCard);

        addOrDividerVertical(row, cardH);

        LinearLayout manualCard = buildCard(buildManualCardContent(), cardH);
        manualCard.setLayoutParams(new LinearLayout.LayoutParams(0, cardH, 1f));
        row.addView(manualCard);

        root.addView(row);
    }

    private void buildPortraitCards(LinearLayout root) {
        LinearLayout cardsArea = new LinearLayout(this);
        cardsArea.setOrientation(LinearLayout.VERTICAL);
        cardsArea.setPadding(0, dp(4), 0, 0);
        LinearLayout.LayoutParams areaParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardsArea.setLayoutParams(areaParams);

        int cardH = dp(185);

        LinearLayout cloudCard = buildCard(buildCloudCardContent(), cardH);
        cloudCard.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, cardH));
        cardsArea.addView(cloudCard);

        addOrDividerHorizontal(cardsArea);

        LinearLayout discoverCard = buildCard(buildDiscoverCardContent(), cardH);
        discoverCard.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, cardH));
        cardsArea.addView(discoverCard);

        addOrDividerHorizontal(cardsArea);

        LinearLayout manualCard = buildCard(buildManualCardContent(), cardH);
        manualCard.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, cardH));
        cardsArea.addView(manualCard);

        root.addView(cardsArea);
    }

    private LinearLayout buildCard(View content, int cardHeight) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int padTop = dp(landscape ? 20 : 14);
        int padH   = dp(landscape ? 18 : 16);
        int padBot = dp(landscape ? 16 : 14);
        card.setPadding(padH, padTop, padH, padBot);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#ffffff"));
        cardBg.setCornerRadius(dp(16));
        cardBg.setStroke(dp(1), Color.parseColor("#f1f5f9"));
        card.setBackground(cardBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(4));
        }

        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        content.setLayoutParams(contentParams);
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
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setAdjustViewBounds(true);
        int sz = dp(landscape ? 48 : 40);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(sz, sz);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = dp(landscape ? 10 : 6);
        icon.setLayoutParams(params);
        return icon;
    }

    private TextView buildCardTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(Color.parseColor("#0f172a"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, landscape ? 15 : 14);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(landscape ? 8 : 6);
        title.setLayoutParams(p);
        return title;
    }

    private TextView buildCardDesc(CharSequence text) {
        TextView desc = new TextView(this);
        desc.setText(text);
        desc.setTextColor(Color.parseColor("#64748b"));
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        desc.setLineSpacing(0f, landscape ? 1.45f : 1.4f);
        desc.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        desc.setLayoutParams(p);
        return desc;
    }

    private View buildSpacer() {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return spacer;
    }

    @SuppressLint("SetTextI18n")
    private View buildCloudCardContent() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        layout.addView(buildCardIcon("ic_cloud_server"));
        layout.addView(buildCardTitle("Use Cloud Server"));
        layout.addView(buildCardDesc("Connect to the Digipal cloud server"));
        layout.addView(buildSpacer());

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
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        layout.addView(buildCardIcon("ic_discover_servers"));
        layout.addView(buildCardTitle("Discover Local Servers"));
        layout.addView(buildCardDesc("Scan your network for\nDigipal local servers"));
        layout.addView(buildSpacer());

        scanButton = createButton("Scan for Local Servers \u203a", "#14b8a6");
        scanButton.setOnClickListener(v -> startDiscovery());
        layout.addView(scanButton);

        LinearLayout scanRow = new LinearLayout(this);
        scanRow.setOrientation(LinearLayout.HORIZONTAL);
        scanRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams scanRowParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scanRowParams.topMargin = dp(8);
        scanRow.setLayoutParams(scanRowParams);

        scanProgress = new ProgressBar(this);
        scanProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(20), dp(20));
        progressParams.rightMargin = dp(8);
        scanProgress.setLayoutParams(progressParams);
        scanRow.addView(scanProgress);

        scanStatus = new TextView(this);
        scanStatus.setTextColor(Color.parseColor("#64748b"));
        scanStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        scanStatus.setVisibility(View.GONE);
        scanRow.addView(scanStatus);

        layout.addView(scanRow);

        serverListContainer = new LinearLayout(this);
        serverListContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listParams.topMargin = dp(4);
        serverListContainer.setLayoutParams(listParams);
        layout.addView(serverListContainer);

        return layout;
    }

    @SuppressLint("SetTextI18n")
    private View buildManualCardContent() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        layout.addView(buildCardIcon("ic_manual_server"));
        layout.addView(buildCardTitle("Enter Server Address"));

        manualUrlInput = new EditText(this);
        manualUrlInput.setHint("e.g. http://192.168.1.100:8787");
        manualUrlInput.setHintTextColor(Color.parseColor("#94a3b8"));
        manualUrlInput.setTextColor(Color.parseColor("#0f172a"));
        manualUrlInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        manualUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        manualUrlInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        manualUrlInput.setSingleLine(true);
        manualUrlInput.setPadding(dp(10), dp(7), dp(10), dp(7));
        manualUrlInput.setMinHeight(0);
        manualUrlInput.setMinimumHeight(0);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(Color.parseColor("#f8fafc"));
        inputBg.setCornerRadius(dp(8));
        inputBg.setStroke(dp(1), Color.parseColor("#e2e8f0"));
        manualUrlInput.setBackground(inputBg);
        LinearLayout.LayoutParams manualInputParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        manualUrlInput.setLayoutParams(manualInputParams);
        layout.addView(manualUrlInput);

        layout.addView(buildSpacer());

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
        dividerRowParams.topMargin = dp(6);
        dividerRowParams.bottomMargin = dp(6);
        dividerRow.setLayoutParams(dividerRowParams);

        View lineLeft = new View(this);
        lineLeft.setBackgroundColor(Color.parseColor("#e2e8f0"));
        dividerRow.addView(lineLeft, new LinearLayout.LayoutParams(0, dp(1), 1));

        TextView pill = makeOrPill();
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillParams.leftMargin = dp(8);
        pillParams.rightMargin = dp(8);
        dividerRow.addView(pill, pillParams);

        View lineRight = new View(this);
        lineRight.setBackgroundColor(Color.parseColor("#e2e8f0"));
        dividerRow.addView(lineRight, new LinearLayout.LayoutParams(0, dp(1), 1));

        parent.addView(dividerRow);
    }

    @SuppressLint("SetTextI18n")
    private void addOrDividerVertical(LinearLayout parent, int cardH) {
        LinearLayout divCol = new LinearLayout(this);
        divCol.setOrientation(LinearLayout.VERTICAL);
        divCol.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams divColParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, cardH);
        divColParams.leftMargin = dp(12);
        divColParams.rightMargin = dp(12);
        divCol.setLayoutParams(divColParams);

        View lineTop = new View(this);
        lineTop.setBackgroundColor(Color.parseColor("#e2e8f0"));
        divCol.addView(lineTop, new LinearLayout.LayoutParams(dp(1), 0, 1));

        TextView pill = makeOrPill();
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillParams.topMargin = dp(8);
        pillParams.bottomMargin = dp(8);
        divCol.addView(pill, pillParams);

        View lineBottom = new View(this);
        lineBottom.setBackgroundColor(Color.parseColor("#e2e8f0"));
        divCol.addView(lineBottom, new LinearLayout.LayoutParams(dp(1), 0, 1));

        parent.addView(divCol);
    }

    @SuppressLint("SetTextI18n")
    private TextView makeOrPill() {
        TextView pill = new TextView(this);
        pill.setText("OR");
        pill.setTextColor(Color.parseColor("#94a3b8"));
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        pill.setTypeface(null, Typeface.BOLD);
        pill.setIncludeFontPadding(false);
        int padH = dp(landscape ? 9 : 10);
        int padV = dp(landscape ? 4 : 3);
        pill.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#f1f5f9"));
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), Color.parseColor("#e2e8f0"));
        pill.setBackground(bg);
        return pill;
    }

    @SuppressLint("SetTextI18n")
    private void addPrivacyFooter(LinearLayout root) {
        TextView footer = new TextView(this);
        footer.setText("\uD83D\uDD12 Your connection details are private and secure.");
        footer.setTextColor(Color.parseColor("#94a3b8"));
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, landscape ? 10 : 11);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(landscape ? 10 : 8);
        footer.setLayoutParams(params);
        root.addView(footer);
    }

    private Button createButton(String text, String colorHex) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        button.setAllCaps(false);
        button.setTypeface(null, Typeface.BOLD);
        button.setIncludeFontPadding(false);
        int padV = dp(landscape ? 10 : 9);
        button.setPadding(dp(12), padV, dp(12), padV);
        button.setMinHeight(0);
        button.setMinimumHeight(0);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(colorHex));
        bg.setCornerRadius(dp(10));
        button.setBackground(bg);

        button.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
            scanButton.setText("Scan for Local Servers \u203a");
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
        card.setPadding(dp(12), dp(8), dp(12), dp(8));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#ffffff"));
        cardBg.setCornerRadius(dp(8));
        cardBg.setStroke(dp(1), Color.parseColor("#c7d2fe"));
        card.setBackground(cardBg);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(6);
        card.setLayoutParams(cardParams);

        TextView nameView = new TextView(this);
        nameView.setText(server.name);
        nameView.setTextColor(Color.parseColor("#0f172a"));
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        nameView.setTypeface(null, Typeface.BOLD);
        card.addView(nameView);

        TextView addrView = new TextView(this);
        addrView.setText(server.host + ":" + server.port);
        addrView.setTextColor(Color.parseColor("#64748b"));
        addrView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LinearLayout.LayoutParams addrParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addrParams.topMargin = dp(2);
        addrView.setLayoutParams(addrParams);
        card.addView(addrView);

        Button connectBtn = createButton("Connect \u203a", "#14b8a6");
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.topMargin = dp(6);
        connectBtn.setLayoutParams(btnParams);
        connectBtn.setOnClickListener(v -> {
            saveServerChoice("local", server.getUrl());
            launchPlayer();
        });
        card.addView(connectBtn);

        serverListContainer.addView(card);
    }

    private static final Pattern PRIVATE_IP = Pattern.compile(
        "^(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|" +
        "172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}|" +
        "192\\.168\\.\\d{1,3}\\.\\d{1,3})$"
    );

    private boolean isPrivateNetworkUrl(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) return false;
            if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")) return true;
            return PRIVATE_IP.matcher(host).matches();
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
