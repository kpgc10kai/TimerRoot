package com.kpgc.timerroot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import rikka.shizuku.Shizuku;

/**
 * Front end for CVE-2026-98260 (posix CPU timer UAF).
 *
 * The exploit cannot run in this process (an app is untrusted_app and cannot
 * read /sys/kernel/tracing), so it runs through a Shizuku user service that
 * Shizuku starts as the shell user (uid 2000, u:r:shell:s0).
 */
public class MainActivity extends Activity {

    private static final String TAG = "TimerRoot";
    private static final int REQUEST_CODE = 1001;

    private static final int BG = 0xFF0B0B0B;
    private static final int CARD = 0xFF1C1C1E;
    private static final int LINE = 0xFF2A2A2C;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFF9A9A9A;
    private static final int GREEN = 0xFF2FD06B;
    private static final int GREEN_DARK = 0xFF123D24;
    private static final int RED = 0xFFFF5C5C;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private IUserService service;
    private boolean bound;
    private boolean rootOpen;

    private LinearLayout statusCard;
    private TextView statusTitle;
    private TextView statusSubtitle;
    private TextView statusBadge;
    private TextView detailModel;
    private TextView detailKernel;
    private TextView detailKsud;
    private TextView detailKsu;
    private TextView detailSelinux;
    private TextView detailSeccomp;
    private TextView logText;
    private LinearLayout homePage;
    private LinearLayout logPage;
    private TextView tabHome;
    private TextView tabLog;
    private String ksud = "-";
    private String ksu = "unknown";
    private String selinux = "-";
    private String seccomp = "-";

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IUserService.Stub.asInterface(binder);
            bound = true;
            addLog("user service connected");
            refreshDetails();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            addLog("user service disconnected");
            refreshDetails();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate");

        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });

        homePage = buildHome();
        logPage = buildLog();
        logPage.setVisibility(View.GONE);

        FrameLayout.LayoutParams fill = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        fill.bottomMargin = dp(58);
        root.addView(homePage, fill);
        root.addView(logPage, fill);

        LinearLayout bottom = buildBottomBar();
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58), Gravity.BOTTOM);
        root.addView(bottom, barParams);

        setContentView(root);
        showTab(0);
        refreshDetails();
        addLog("TimerRoot ready");
        if (safePing() && safePermission() == 0) {
            bindService();
        }
    }

    // ------------------------------------------------------------------ UI

    private LinearLayout buildHome() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(16));
        scroll.addView(body);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("TimerRoot");
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(pill("Reboot", RED, CARD, v -> reboot()));
        body.addView(header);
        body.addView(spacer(12));

        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        statusCard.setBackground(cardBackground(CARD));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        statusTitle = new TextView(this);
        statusTitle.setText("Not connected");
        statusTitle.setTextColor(TEXT);
        statusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        statusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        statusSubtitle = new TextView(this);
        statusSubtitle.setText("Start Shizuku first");
        statusSubtitle.setTextColor(0xFFBFD8C6);
        statusSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        texts.addView(statusTitle);
        texts.addView(spacer(4));
        texts.addView(statusSubtitle);
        statusCard.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusBadge = new TextView(this);
        statusBadge.setText("?");
        statusBadge.setTextColor(GREEN);
        statusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusCard.addView(statusBadge);
        body.addView(statusCard);
        body.addView(spacer(14));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setBackground(cardBackground(CARD));
        detailModel = addRow(details, "Device", "-", true);
        detailKernel = addRow(details, "Kernel", "-", true);
        detailKsud = addRow(details, "ksud", "-", true);
        detailKsu = addRow(details, "KernelSU", "-", true);
        detailSelinux = addRow(details, "SELinux", "-", true);
        detailSeccomp = addRow(details, "Seccomp", "-", false);
        body.addView(details);
        body.addView(spacer(14));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.addView(pill("Shizuku", TEXT, CARD, v -> showShizuku()), rowParams());
        tools.addView(pill("Prepare", TEXT, CARD, v -> prepare()), rowParams());
        tools.addView(pill("Diagnose", TEXT, CARD, v -> run("ids", "ids")), rowParams());
        tools.addView(pill("Log", TEXT, CARD, v -> showTab(1)), rowParams());
        body.addView(tools);

        body.addView(bigButton("Get root shell", GREEN, 0xFF04140A, v -> confirmRoot()));
        body.addView(spacer(8));
        body.addView(bigButton("Load KernelSU", 0xFF1F7A3D, 0xFFE8FFF0, v -> confirmKsu()));
        body.addView(spacer(8));
        body.addView(bigButton("Stop", CARD, RED, v -> stopRoot()));

        body.addView(spacer(18));
        body.addView(smallHint("First run: allow Shizuku, then press Prepare. "
                + "The exploit needs the shell user, which is why it runs through Shizuku."));
        body.addView(spacer(8));



        return page;
    }

    private LinearLayout buildLog() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setPadding(dp(16), dp(12), dp(16), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Log");
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(pill("Copy", TEXT, CARD, v -> copyLog()));
        header.addView(pill("Clear", TEXT, CARD, v -> {
            logText.setText("");
            addLog("log cleared");
        }));
        page.addView(header);
        page.addView(spacer(12));

        logText = new TextView(this);
        logText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextColor(0xFFD6D6D6);
        logText.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(cardBackground(CARD));
        card.addView(logText);
        scroll.addView(card);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private LinearLayout buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF141416);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        tabHome = tab("Home", v -> showTab(0));
        tabLog = tab("Log", v -> showTab(1));
        bar.addView(tabHome, rowParams());
        bar.addView(tabLog, rowParams());
        return bar;
    }

    private void showTab(int index) {
        homePage.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        logPage.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        tabHome.setTextColor(index == 0 ? GREEN : MUTED);
        tabLog.setTextColor(index == 1 ? GREEN : MUTED);
    }

    // ------------------------------------------------------------ widgets

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private View spacer(int height) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(height)));
        return view;
    }

    private LinearLayout.LayoutParams rowParams() {
        return new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private GradientDrawable cardBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(24));
        if (color == GREEN_DARK) {
            drawable.setGradientType(GradientDrawable.LINEAR_GRADIENT);
            drawable.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
            drawable.setColors(new int[]{0xFF0F3A22, 0xFF1E7A46});
        }
        return drawable;
    }

    private TextView pill(String text, int color, int background, View.OnClickListener click) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setPadding(dp(14), dp(8), dp(14), dp(8));
        view.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(background);
        bg.setCornerRadius(dp(18));
        view.setBackground(bg);
        view.setOnClickListener(click);
        return view;
    }

    private TextView tab(String text, View.OnClickListener click) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(MUTED);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setGravity(Gravity.CENTER);
        view.setOnClickListener(click);
        return view;
    }

    private TextView bigButton(String text, int background, int color,
                               View.OnClickListener click) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(background);
        bg.setCornerRadius(dp(26));
        view.setBackground(bg);
        view.setOnClickListener(click);
        return view;
    }

    private TextView smallHint(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(MUTED);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setLineSpacing(dp(3), 1f);
        return view;
    }

    private TextView addRow(LinearLayout parent, String label, String value,
                            boolean divider) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        TextView val = new TextView(this);
        val.setText(value);
        val.setTextColor(MUTED);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        texts.addView(name);
        texts.addView(spacer(2));
        texts.addView(val);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);
        if (divider) {
            View line = new View(this);
            line.setBackgroundColor(LINE);
            parent.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        return val;
    }

    // ------------------------------------------------------------- state

    private void refreshDetails() {
        detailModel.setText(Build.MODEL + " / " + Build.PRODUCT);
        detailKernel.setText(System.getProperty("os.version", "?"));
        if (service != null) {
            new Thread(() -> {
                try {
                    parseInfo(service.info());
                    ui.post(this::applyDetails);
                } catch (Throwable t) {
                    Log.i(TAG, "info failed: " + t);
                }
            }).start();
        }
        applyDetails();
    }

    private void parseInfo(String raw) {
        for (String line : raw.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1).trim();
            if (key.equals("selinux")) {
                selinux = value;
            } else if (key.equals("seccomp")) {
                seccomp = value;
            } else if (key.equals("ksud")) {
                ksud = value;
            } else if (key.equals("ksu")) {
                ksu = value;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDetails();
    }

    private void applyDetails() {
        if (statusCard != null) {
            statusCard.setBackground(cardBackground(
                    rootOpen || "active".equals(ksu) ? GREEN_DARK : CARD));
        }
        detailKsud.setText(ksud);
        detailKsu.setText("active".equals(ksu) ? "jailbreak mode (u:r:ksu:s0)" : ksu);
        detailSelinux.setText(selinux);
        detailSeccomp.setText(seccomp);
        boolean shizuku = safePing();
        if ("active".equals(ksu)) {
            statusTitle.setText("Jailbreak mode");
            statusSubtitle.setText("KernelSU loaded · su in u:r:ksu:s0 · gone after reboot");
            statusBadge.setText("✓");
            statusBadge.setTextColor(GREEN);
        } else if (rootOpen) {
            statusTitle.setText("Root shell");
            statusSubtitle.setText("uid=0 · u:r:shell:s0 · gone after reboot");
            statusBadge.setText("✓");
            statusBadge.setTextColor(GREEN);
        } else if (shizuku && bound) {
            statusTitle.setText("Ready");
            statusSubtitle.setText("Shizuku connected · no root yet");
            statusBadge.setText("•");
            statusBadge.setTextColor(MUTED);
        } else {
            statusTitle.setText("Not connected");
            statusSubtitle.setText(shizuku
                    ? (safePermission() == 0
                        ? "Shizuku is running · connecting..."
                        : "Shizuku is running · grant permission")
                    : "Start Shizuku first");
            statusBadge.setText("?");
            statusBadge.setTextColor(MUTED);
        }
    }

    private boolean safePing() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    private void showShizuku() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("running: ").append(Shizuku.pingBinder()).append('\n');
            sb.append("permission: ").append(Shizuku.checkSelfPermission()).append('\n');
            sb.append("pre-v11: ").append(Shizuku.isPreV11()).append('\n');
            sb.append("bound: ").append(bound);
        } catch (Throwable t) {
            sb.append("Shizuku not available: ").append(t);
        }
        addLog(sb.toString());
        if (safePing() && safePermission() != 0) {
            try {
                Shizuku.requestPermission(REQUEST_CODE);
                addLog("permission requested");
            } catch (Throwable t) {
                addLog("request failed: " + t);
            }
        } else if (safePing() && !bound) {
            bindService();
        }
    }

    private int safePermission() {
        try {
            return Shizuku.checkSelfPermission();
        } catch (Throwable t) {
            return -1;
        }
    }

    private void prepare() {
        if (!safePing()) {
            new AlertDialog.Builder(this)
                    .setTitle("Shizuku is not running")
                    .setMessage("Start Shizuku first:\n\n"
                            + "1. open the Shizuku app\n"
                            + "2. use Start via wireless debugging (or pair over USB)\n"
                            + "3. come back here and press Prepare again")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        if (service == null) {
            bindService();
            toast("Connecting to Shizuku...");
            return;
        }
        toast("Preparing...");
        new Thread(() -> {
            try {
                final String result = service.install(getPackageCodePath());
                addLog(result);
                ui.post(() -> {
                    toast(result);
                    refreshDetails();
                });
            } catch (Throwable t) {
                addLog("prepare failed: " + t);
                ui.post(() -> toast("prepare failed: " + t));
            }
        }).start();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void confirmRoot() {
        new AlertDialog.Builder(this)
                .setTitle("Get a root shell?")
                .setMessage("Runs CVE-2026-98260 once and keeps a uid=0 shell open.\n\n"
                        + "- On a failed attempt the process is parked until you reboot\n"
                        + "- Root disappears when you reboot\n"
                        + "- Do not use the device while it runs")
                .setPositiveButton("Run", (d, w) -> runRoot())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runRoot() {
        if (service == null) {
            addLog("bind the service first");
            return;
        }
        new Thread(() -> {
            try {
                String out = service.openRoot();
                rootOpen = out.contains("uid=0(root)") || out.contains("started");
                addLog(out);
                ui.post(this::applyDetails);
                if (rootOpen && !"active".equals(ksu)) {
                    addLog("root shell ready - loading KernelSU...");
                    runKsu();
                }
            } catch (Throwable t) {
                addLog("root failed: " + t);
            }
        }).start();
    }

    private void confirmKsu() {
        new AlertDialog.Builder(this)
                .setTitle("Load KernelSU?")
                .setMessage("Late-loads ksud through a fake credential.\n\n"
                        + "- On success su works in u:r:ksu:s0\n"
                        + "- The parent process parks; reboot to clean up\n"
                        + "- Do not use the device while it runs")
                .setPositiveButton("Run", (d, w) -> runKsu())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runKsu() {
        if (service == null) {
            addLog("bind the service first");
            return;
        }
        new Thread(() -> {
            try {
                addLog(service.runDetached("fake"));
                final long start = System.currentTimeMillis();
                boolean loaded = false;
                String failure = null;
                while (System.currentTimeMillis() - start < 90000) {
                    Thread.sleep(1000);
                    String tail = service.readLog();
                    if (tail.contains("KSU_LOADED")) {
                        loaded = true;
                        break;
                    }
                    if (tail.contains("selinux write failed")) {
                        failure = "the selinux stage missed its race";
                        break;
                    }
                    if (tail.contains("parking without reap")) {
                        failure = "the exploit parked before loading ksud";
                        break;
                    }
                    final long secs = (System.currentTimeMillis() - start) / 1000;
                    if (secs % 5 == 0) {
                        ui.post(() -> {
                            if (statusSubtitle != null) {
                                statusSubtitle.setText("Loading KernelSU... " + secs + "s");
                            }
                        });
                    }
                }
                if (!loaded && failure == null) {
                    failure = "timed out after 90s";
                }
                if (loaded) {
                    parseInfo(service.info());
                }
                addLog(service.readLog());
                final boolean ok = loaded;
                final String reason = failure;
                ui.post(() -> {
                    applyDetails();
                    if (ok) {
                        toast("KernelSU loaded (jailbreak mode)");
                        refreshDetails();
                    } else {
                        statusTitle.setText("KernelSU failed");
                        statusSubtitle.setText(reason + " - reboot, then retry");
                        statusBadge.setText("!");
                        statusBadge.setTextColor(RED);
                        toast("KernelSU failed: " + reason);
                        addLog("KernelSU failed: " + reason
                                + " - parked processes remain; reboot to clean up");
                    }
                });
            } catch (Throwable t) {
                addLog("ksu failed: " + t);
            }
        }).start();
    }

    private void stopRoot() {
        if (service == null) {
            return;
        }
        new Thread(() -> {
            try {
                addLog(service.closeRoot());
                rootOpen = false;
                ui.post(this::applyDetails);
            } catch (Throwable t) {
                addLog("stop failed: " + t);
            }
        }).start();
    }

    private void reboot() {
        new AlertDialog.Builder(this)
                .setTitle("Reboot the device?")
                .setMessage("This is the only way to clean up parked processes.")
                .setPositiveButton("Reboot", (d, w) -> {
                    if (service == null) {
                        toast("bind the service first");
                        return;
                    }
                    new Thread(() -> {
                        try {
                            final String result = service.rebootDevice();
                            ui.post(() -> toast(result));
                        } catch (Throwable t) {
                            ui.post(() -> toast("reboot failed: " + t));
                        }
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void run(String label, String args) {
        if (service == null) {
            addLog("bind the service first");
            return;
        }
        new Thread(() -> {
            try {
                addLog("[" + label + "]\n" + service.run(args));
            } catch (Throwable t) {
                addLog("[" + label + "] failed: " + t);
            }
        }).start();
    }

    // ------------------------------------------------------------ plumbing

    private void bindService() {
        try {
            Shizuku.bindUserService(serviceArgs(), connection);
            addLog("binding user service...");
        } catch (Throwable t) {
            addLog("bind failed: " + t);
        }
    }

    private Shizuku.UserServiceArgs serviceArgs() {
        return new Shizuku.UserServiceArgs(
                new ComponentName(getPackageName(), UserService.class.getName()))
                .daemon(false)
                .processNameSuffix("timer")
                .debuggable(false)
                .version(5);
    }

    private void copyLog() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("TimerRoot", logText.getText()));
                addLog("log copied");
            }
        } catch (Throwable t) {
            addLog("copy failed: " + t);
        }
    }

    private void addLog(String text) {
        String stamp = clock.format(new Date());
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            sb.append(stamp).append("  ").append(line).append('\n');
        }
        Log.i(TAG, text.replace('\n', ' '));
        ui.post(() -> {
            if (logText != null) {
                logText.append(sb.toString());
            }
        });
    }
}