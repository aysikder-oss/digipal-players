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
import java.util.Locale;
import java.util.regex.Pattern;

public class ServerSetupActivity extends Activity {

    private static final String PREFS_NAME = "DigipalPrefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_SERVER_MODE = "server_mode";
    private static final String SERVICE_TYPE = "_digipal._tcp.";
    private static final String PREF_VIDEO_RENDERER = "pref_video_renderer";

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
        boolean showSettings = getIntent() != null && getIntent().getBooleanExtra("show_settings", false);
        if (showSettings) {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
            setContentView(buildRendererSettingsUI(prefs));
            return;
        }
        String savedMode = prefs.getString(KEY_SERVER_MODE, null);
        if (savedMode != null) {
            launchPlayer();
            return;
        }
        // Cloud was previously chosen (cloud mode stores no KEY_SERVER_MODE).
        if (prefs.getBoolean("cloud_pairing_pending", false)) {
            launchPlayer();
            return;
        }
        // First-ever launch — auto-connect to cloud instead of showing the
        // 3-card picker.  Same effect as the user tapping "Connect to Cloud Server".
        // The picker is still reachable via Settings -> Reconfigure Server Connection.
        prefs.edit().putBoolean("cloud_pairing_pending", true).apply();
        launchPlayer();
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
        // Mockup outer padding: 90 top, 32 sides, 30 bottom — applied uniformly
        root.setPadding(dp(32), dp(90), dp(32), dp(30));

        // Header (logo + subtitle)
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        addLogoAndSubtitle(header, dp(24));
        root.addView(header, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Cards row
        buildLandscapeCards(root);

        // Spacer pushes footer to the bottom of the viewport (mockup: cards row flex:1 + footer at bottom)
        root.addView(buildSpacer());

        // Footer
        addPrivacyFooter(root);

        scroll.addView(root, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
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
        // No root horizontal padding — each section sets its own (mockup: header 20, cards 16, footer 16)
        root.setPadding(0, 0, 0, 0);

        // Header section: padding 60/20/14 (mockup)
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(60), dp(20), dp(14));
        addLogoAndSubtitle(header, dp(0));
        root.addView(header, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Cards area: padding 4/16/0 (mockup)
        buildPortraitCards(root);

        // Spacer pushes footer to bottom (mockup: cards area flex:1 + footer fixed at bottom)
        root.addView(buildSpacer());

        // Footer section: padding 8/16/14 (mockup)
        LinearLayout footerWrap = new LinearLayout(this);
        footerWrap.setOrientation(LinearLayout.VERTICAL);
        footerWrap.setPadding(dp(16), dp(8), dp(16), dp(14));
        addPrivacyFooter(footerWrap);
        root.addView(footerWrap, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.addView(root, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    @SuppressLint("SetTextI18n")
    private void addLogoAndSubtitle(LinearLayout root, int subtitleBottom) {
        try {
            int logoResId = iconForName("player_logo");
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

        int cardMinH = dp(220);

        LinearLayout cloudCard = buildCard(buildCloudCardContent(), cardMinH);
        cloudCard.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(cloudCard);

        addOrDividerVertical(row);

        LinearLayout discoverCard = buildCard(buildDiscoverCardContent(), cardMinH);
        discoverCard.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(discoverCard);

        addOrDividerVertical(row);

        LinearLayout manualCard = buildCard(buildManualCardContent(), cardMinH);
        manualCard.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(manualCard);

        root.addView(row);
    }

    private void buildPortraitCards(LinearLayout root) {
        LinearLayout cardsArea = new LinearLayout(this);
        cardsArea.setOrientation(LinearLayout.VERTICAL);
        // Mockup: cards area padding 4 16 0 (top, sides, bottom)
        cardsArea.setPadding(dp(16), dp(4), dp(16), 0);
        LinearLayout.LayoutParams areaParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardsArea.setLayoutParams(areaParams);

        int cardMinH = dp(185);

        LinearLayout cloudCard = buildCard(buildCloudCardContent(), cardMinH);
        cloudCard.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cardsArea.addView(cloudCard);

        addOrDividerHorizontal(cardsArea);

        LinearLayout discoverCard = buildCard(buildDiscoverCardContent(), cardMinH);
        discoverCard.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cardsArea.addView(discoverCard);

        addOrDividerHorizontal(cardsArea);

        LinearLayout manualCard = buildCard(buildManualCardContent(), cardMinH);
        manualCard.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cardsArea.addView(manualCard);

        root.addView(cardsArea);
    }

    private LinearLayout buildCard(View content, int cardMinHeight) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int padTop = dp(landscape ? 20 : 14);
        int padH   = dp(landscape ? 18 : 16);
        int padBot = dp(landscape ? 16 : 14);
        card.setPadding(padH, padTop, padH, padBot);
        card.setMinimumHeight(cardMinHeight);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#ffffff"));
        cardBg.setCornerRadius(dp(16));
        cardBg.setStroke(dp(1), Color.parseColor("#f1f5f9"));
        card.setBackground(cardBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(4));
        }

        // WRAP_CONTENT so the card can grow beyond minHeight when content expands (e.g. scan results)
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        content.setLayoutParams(contentParams);
        card.addView(content);
        return card;
    }

    private ImageView buildCardIcon(String drawableName) {
        ImageView icon = new ImageView(this);
        try {
            int resId = iconForName(drawableName);
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

    // Weight-based spacer — used in the page layout to push footer to the bottom of the viewport
    private View buildSpacer() {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return spacer;
    }

    // Fixed-height spacer — used inside cards between desc/input and button.
    // Cards are now WRAP_CONTENT height so a weight spacer would collapse to zero;
    // a fixed dp gap ensures consistent visual breathing room in every card state.
    private View buildCardSpacer() {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(12)));
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
        layout.addView(buildCardSpacer());

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
        layout.addView(buildCardSpacer());

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
        // Mockup: landscape input fontSize 11, portrait input fontSize 12
        manualUrlInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, landscape ? 11 : 12);
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

        layout.addView(buildCardSpacer());

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
            if (!UrlPolicy.isAllowedServerUrl(url)) {
                manualUrlInput.setError("Use HTTPS for public servers. HTTP is only allowed for private network addresses.");
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
    private void addOrDividerVertical(LinearLayout parent) {
        LinearLayout divCol = new LinearLayout(this);
        divCol.setOrientation(LinearLayout.VERTICAL);
        divCol.setGravity(Gravity.CENTER_HORIZONTAL);
        // MATCH_PARENT height: stretches to match the tallest card in the horizontal row,
        // so the divider lines always run full height even when the Discover card grows.
        LinearLayout.LayoutParams divColParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
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

    private int iconForName(String drawableName) {
        switch (drawableName) {
            case "ic_cloud_server": return R.drawable.ic_cloud_server;
            case "ic_discover_servers": return R.drawable.ic_discover_servers;
            case "ic_manual_server": return R.drawable.ic_manual_server;
            case "player_logo": return R.drawable.player_logo;
            default: return 0;
        }
    }

    private boolean isPrivateNetworkUrl(String url) {
        try {
            URI uri = new URI(url);
            return UrlPolicy.isPrivateHost(uri.getHost());
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

    @SuppressLint("SetTextI18n")
      private View buildRendererSettingsUI(SharedPreferences prefs) {
          android.widget.ScrollView scroll = new android.widget.ScrollView(this);
          scroll.setBackgroundColor(Color.parseColor("#ffffff"));
          scroll.setFillViewport(true);
          LinearLayout root = new LinearLayout(this);
          root.setOrientation(LinearLayout.VERTICAL);
          root.setBackgroundColor(Color.parseColor("#ffffff"));
          root.setPadding(dp(24), dp(56), dp(24), dp(24));
          // Title
          TextView title = new TextView(this);
          title.setText("Player Settings");
          title.setTextColor(Color.parseColor("#0f172a"));
          title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
          title.setTypeface(null, Typeface.BOLD);
          LinearLayout.LayoutParams titleP = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
          titleP.bottomMargin = dp(24);
          title.setLayoutParams(titleP);
          root.addView(title);
          // Video Renderer label
          TextView rendererLabel = new TextView(this);
          rendererLabel.setText("Video Renderer");
          rendererLabel.setTextColor(Color.parseColor("#334155"));
          rendererLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
          rendererLabel.setTypeface(null, Typeface.BOLD);
          LinearLayout.LayoutParams rlP = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
          rlP.bottomMargin = dp(4);
          rendererLabel.setLayoutParams(rlP);
          root.addView(rendererLabel);
          // Video Renderer description
          TextView rendererDesc = new TextView(this);
          rendererDesc.setText("TextureView is recommended for Fire TV devices. If video appears blank, switch to SurfaceView. Changes apply when you return to the player.");
          rendererDesc.setTextColor(Color.parseColor("#64748b"));
          rendererDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
          LinearLayout.LayoutParams rdP = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
          rdP.bottomMargin = dp(14);
          rendererDesc.setLayoutParams(rdP);
          root.addView(rendererDesc);
          // Determine current and default renderer
          boolean isFireTvDevice = Build.MANUFACTURER.equalsIgnoreCase("Amazon")
                  || Build.MODEL.toUpperCase(Locale.ROOT).startsWith("AFT");
          String[] curRef = { prefs.getString(PREF_VIDEO_RENDERER, isFireTvDevice ? "texture" : "surface") };
          // Renderer buttons
          LinearLayout btnRow = new LinearLayout(this);
          btnRow.setOrientation(LinearLayout.HORIZONTAL);
          LinearLayout.LayoutParams brP = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
          brP.bottomMargin = dp(24);
          btnRow.setLayoutParams(brP);
          Button btnTexture = createRendererBtn("TextureView");
          Button btnSurface = createRendererBtn("SurfaceView");
          btnTexture.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
          View btnGap = new View(this); btnGap.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
          btnSurface.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
          Runnable[] refreshRef = new Runnable[1];
          refreshRef[0] = () -> {
              applyRendererBtnState(btnTexture, curRef[0].equals("texture"), "TextureView");
              applyRendererBtnState(btnSurface, curRef[0].equals("surface"), "SurfaceView");
          };
          refreshRef[0].run();
          btnTexture.setOnClickListener(v -> { curRef[0] = "texture"; prefs.edit().putString(PREF_VIDEO_RENDERER, "texture").apply(); refreshRef[0].run(); });
          btnSurface.setOnClickListener(v -> { curRef[0] = "surface"; prefs.edit().putString(PREF_VIDEO_RENDERER, "surface").apply(); refreshRef[0].run(); });
          btnRow.addView(btnTexture); btnRow.addView(btnGap); btnRow.addView(btnSurface);
          root.addView(btnRow);
          // Divider
          View div = new View(this);
          div.setBackgroundColor(Color.parseColor("#f1f5f9"));
          LinearLayout.LayoutParams divP = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
          divP.bottomMargin = dp(20);
          div.setLayoutParams(divP);
          root.addView(div);
          // Reconfigure Server
          Button btnReconfig = createButton("Reconfigure Server Connection ›", "#ef4444");
          LinearLayout.LayoutParams recP = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
          recP.bottomMargin = dp(10);
          btnReconfig.setLayoutParams(recP);
          btnReconfig.setOnClickListener(v -> { prefs.edit().remove(KEY_SERVER_MODE).remove(KEY_SERVER_URL).remove("cloud_pairing_pending").apply(); setContentView(buildUI()); });
          root.addView(btnReconfig);
          // Back to Player
          Button btnBack = createButton("Back to Player", "#64748b");
          btnBack.setOnClickListener(v -> launchPlayer());
          root.addView(btnBack);
          scroll.addView(root, new android.widget.ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
          return scroll;
      }

      private Button createRendererBtn(String label) {
          Button btn = new Button(this);
          btn.setText(label);
          btn.setAllCaps(false);
          btn.setTypeface(null, Typeface.BOLD);
          btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
          btn.setPadding(dp(8), dp(10), dp(8), dp(10));
          btn.setMinHeight(0); btn.setMinimumHeight(0);
          return btn;
      }

      private void applyRendererBtnState(Button btn, boolean active, String label) {
          GradientDrawable bg = new GradientDrawable();
          bg.setCornerRadius(dp(10));
          if (active) {
              bg.setColor(Color.parseColor("#3b82f6"));
              btn.setTextColor(Color.WHITE);
              btn.setText(label + " ✓");
          } else {
              bg.setColor(Color.parseColor("#f8fafc"));
              bg.setStroke(dp(1), Color.parseColor("#cbd5e1"));
              btn.setTextColor(Color.parseColor("#64748b"));
              btn.setText(label);
          }
          btn.setBackground(bg);
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
