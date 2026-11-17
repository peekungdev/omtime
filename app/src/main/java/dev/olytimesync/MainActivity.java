package dev.olytimesync;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int REQ_FIND = 10;

    private View statusCircle;
    private ImageView statusIcon;
    private TextView statusTitle;
    private TextView statusBody;
    private TextView statusWhen;
    private TextView phoneTime;
    private TextView chip;
    private ProgressBar progress;
    private Button sync;

    private LinearLayout camerasBox;

    private View bgCircle;
    private ImageView bgIcon;
    private TextView bgStatus;
    private Button bgButton;
    private Button oemButton;
    private View autoCircle;
    private ImageView autoIcon;
    private TextView autoStatus;
    private Button autoButton;
    private Intent autoStartPage;
    private Intent oemBatteryPage;

    private TextView log;

    /** The next setup step to offer. It only moves forward, so a step the user declines is not asked again in a loop. */
    private int guideStep;
    private boolean askingNotifications;
    private AlertDialog dialog;
    private int busy;

    private final Handler clock = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            showPhoneTime();
            showWhen();
            clock.postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);

        statusCircle = findViewById(R.id.status_circle);
        statusIcon = findViewById(R.id.status_icon);
        statusTitle = findViewById(R.id.status_title);
        statusBody = findViewById(R.id.status_body);
        statusWhen = findViewById(R.id.status_when);
        phoneTime = findViewById(R.id.phone_time);
        chip = findViewById(R.id.chip);
        progress = findViewById(R.id.progress);
        sync = findViewById(R.id.sync);
        camerasBox = findViewById(R.id.cameras);
        bgCircle = findViewById(R.id.bg_circle);
        bgIcon = findViewById(R.id.bg_icon);
        bgStatus = findViewById(R.id.bg_status);
        bgButton = findViewById(R.id.bg_button);
        oemButton = findViewById(R.id.oem_battery_button);
        autoCircle = findViewById(R.id.autostart_circle);
        autoIcon = findViewById(R.id.autostart_icon);
        autoStatus = findViewById(R.id.autostart_status);
        autoButton = findViewById(R.id.autostart_button);
        log = findViewById(R.id.log);
        autoStartPage = Background.autoStartPage(this);
        oemBatteryPage = Background.oemBatteryPage(this);

        // Rounded cards clip their row ripples.
        ViewGroup content = findViewById(R.id.content);
        for (int i = 0; i < content.getChildCount(); i++) {
            View v = content.getChildAt(i);
            if ("card".equals(v.getTag())) v.setClipToOutline(true);
        }

        Switch auto = findViewById(R.id.auto);
        auto.setChecked(Prefs.auto(this));
        auto.setOnCheckedChangeListener((v, on) -> {
            Prefs.of(this).edit().putBoolean(Prefs.AUTO, on).apply();
            if (on) Watcher.arm(this);
            else Watcher.disarm(this);
            applyWifi();
            showChip();
        });
        findViewById(R.id.row_auto).setOnClickListener(v -> auto.toggle());

        Switch autoConnect = findViewById(R.id.auto_connect);
        autoConnect.setChecked(Prefs.autoConnect(this));
        autoConnect.setOnCheckedChangeListener((v, on) -> {
            Prefs.of(this).edit().putBoolean(Prefs.AUTO_CONNECT, on).apply();
            applyWifi();
            showCameras();
        });
        findViewById(R.id.row_auto_connect).setOnClickListener(v -> autoConnect.toggle());
        ((TextView) findViewById(R.id.auto_connect_desc)).setText(
                CameraWifi.canSwitch() ? R.string.auto_connect_desc_q : R.string.auto_connect_desc_legacy);
        findViewById(R.id.row_wifi).setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));

        Button find = findViewById(R.id.find_camera);
        find.setVisibility(CameraPicker.available(this) ? View.VISIBLE : View.GONE);
        find.setOnClickListener(v -> CameraPicker.start(this, REQ_FIND));
        findViewById(R.id.add_manual).setOnClickListener(v -> typeCamera());

        sync.setOnClickListener(v -> syncNow());
        bgButton.setOnClickListener(v -> fixBackground());
        oemButton.setOnClickListener(v -> openOemBattery());
        autoButton.setOnClickListener(v -> openAutoStart());

        foldable(R.id.guide_header, R.id.guide_body, R.id.guide_chevron, Camera.load(this).isEmpty());
        foldable(R.id.log_header, R.id.log, R.id.log_chevron, false);

        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.version)).setText(getString(R.string.version, version));
        } catch (PackageManager.NameNotFoundException e) {
            // cannot happen for our own package
        }
        ((TextView) findViewById(R.id.credit)).setText(getString(R.string.made_by, getString(R.string.credit)));

        Notes.channels(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askingNotifications = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (Prefs.auto(this)) Watcher.arm(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        // onResume follows and carries on with the setup steps.
        askingNotifications = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        Prefs.of(this).registerOnSharedPreferenceChangeListener(this);
        // Coming back from the exemption dialog is the moment the service may start from here on.
        if (Prefs.auto(this)) Watcher.startService(this);
        show();
        showCameras();
        clock.post(tick);
        if (!askingNotifications && (dialog == null || !dialog.isShowing())) guide();
    }

    @Override
    protected void onPause() {
        clock.removeCallbacks(tick);
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        runOnUiThread(this::show);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FIND || resultCode != RESULT_OK) return;
        String[] picked = CameraPicker.result(data);
        if (picked == null || picked[0] == null || picked[0].isEmpty()) {
            Toast.makeText(this, R.string.err_pick_failed, Toast.LENGTH_LONG).show();
            typeCamera();
            return;
        }
        askPassword(picked[0], picked[1]);
    }

    // ---- what the screen shows ----

    private void show() {
        showStatus();
        showChip();
        showBackground();
        String text = Prefs.of(this).getString(Prefs.LOG, "");
        log.setText(text.isEmpty() ? getString(R.string.log_empty) : text);
    }

    private void showStatus() {
        SharedPreferences p = Prefs.of(this);
        String kind = p.getString(Prefs.LAST_KIND, null);
        if ("ok".equals(kind)) {
            paint(statusCircle, statusIcon, R.drawable.ic_check, R.color.ok, R.color.ok_container);
            statusTitle.setText(R.string.hero_ok_title);
            long set = p.getLong(Prefs.LAST_SET, 0);
            String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(new Date(set));
            statusBody.setText(getString(R.string.hero_ok_body, p.getString(Prefs.LAST_MODEL, ""), when,
                    p.getString(Prefs.LAST_OFFSET, "")));
        } else if ("fail".equals(kind)) {
            paint(statusCircle, statusIcon, R.drawable.ic_error, R.color.error, R.color.error_container);
            statusTitle.setText(R.string.hero_fail_title);
            statusBody.setText(p.getString(Prefs.LAST_ERROR, ""));
        } else {
            paint(statusCircle, statusIcon, R.drawable.ic_clock, R.color.primary, R.color.primary_container);
            statusTitle.setText(R.string.hero_never_title);
            statusBody.setText(R.string.hero_never_body);
        }
        showWhen();
    }

    /** "5 minutes ago", kept current by the clock tick. */
    private void showWhen() {
        long at = Prefs.of(this).getString(Prefs.LAST_KIND, null) == null ? 0 : Prefs.of(this).getLong(Prefs.LAST_AT, 0);
        statusWhen.setVisibility(at > 0 ? View.VISIBLE : View.GONE);
        if (at > 0) {
            statusWhen.setText(DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS));
        }
    }

    /** The time and zone the next sync would send. */
    private void showPhoneTime() {
        long now = System.currentTimeMillis();
        String pattern = android.text.format.DateFormat.is24HourFormat(this) ? "HH:mm:ss" : "h:mm:ss a";
        String time = new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(now));
        phoneTime.setText(getString(R.string.phone_time, time, SyncEngine.offsetLabel(TimeZone.getDefault(), now)));
    }

    private void showChip() {
        boolean on = Prefs.auto(this);
        int fg = getColor(on ? R.color.ok : R.color.text2);
        chip.setText(on ? R.string.chip_watching : R.string.chip_off);
        chip.setTextColor(fg);
        chip.setCompoundDrawableTintList(ColorStateList.valueOf(fg));
        chip.setBackgroundTintList(ColorStateList.valueOf(getColor(on ? R.color.ok_container : R.color.neutral_container)));
    }

    private void showBackground() {
        boolean allowed = Background.allowed(this);
        if (allowed) paint(bgCircle, bgIcon, R.drawable.ic_check, R.color.ok, R.color.ok_container);
        else paint(bgCircle, bgIcon, R.drawable.ic_warning, R.color.warn, R.color.warn_container);
        bgStatus.setText(allowed ? R.string.bg_ok : R.string.bg_no);
        bgButton.setVisibility(allowed ? View.GONE : View.VISIBLE);
        oemButton.setVisibility(allowed && oemBatteryPage != null ? View.VISIBLE : View.GONE);
        oemButton.setText(getString(R.string.btn_oem_battery, Background.brand()));

        boolean seen = Prefs.of(this).getBoolean(Prefs.AUTOSTART_SEEN, false);
        if (autoStartPage == null || seen) {
            paint(autoCircle, autoIcon, R.drawable.ic_check, R.color.ok, R.color.ok_container);
        } else {
            paint(autoCircle, autoIcon, R.drawable.ic_warning, R.color.warn, R.color.warn_container);
        }
        if (autoStartPage == null) {
            autoStatus.setText(R.string.autostart_none);
            autoButton.setVisibility(View.GONE);
        } else {
            autoStatus.setText(getString(seen ? R.string.autostart_seen : R.string.autostart_todo, Background.brand()));
            autoButton.setVisibility(View.VISIBLE);
        }
    }

    private void showCameras() {
        camerasBox.removeAllViews();
        List<Camera> cams = Camera.load(this);
        if (cams.isEmpty()) {
            TextView none = new TextView(this);
            none.setTextAppearance(R.style.Text_Body);
            none.setText(R.string.cameras_none);
            none.setPadding(0, dp(12), dp(8), dp(4));
            camerasBox.addView(none);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < cams.size(); i++) {
            Camera cam = cams.get(i);
            if (i > 0) {
                View line = new View(this);
                line.setBackgroundColor(getColor(R.color.divider));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                lp.setMarginStart(dp(54));
                camerasBox.addView(line, lp);
            }
            View row = inflater.inflate(R.layout.camera_row, camerasBox, false);
            ((TextView) row.findViewById(R.id.cam_name)).setText(cam.ssid);
            ((TextView) row.findViewById(R.id.cam_how)).setText(!CameraWifi.canSwitch() ? R.string.camera_row_saved
                    : cam.bssid == null ? R.string.camera_row_join
                    : cam.joined ? R.string.camera_row_switch : R.string.camera_row_pending);
            row.findViewById(R.id.cam_remove).setOnClickListener(v -> confirmRemove(cam));
            camerasBox.addView(row);
        }
    }

    private void paint(View circle, ImageView icon, int drawable, int fg, int bg) {
        icon.setImageResource(drawable);
        icon.setImageTintList(ColorStateList.valueOf(getColor(fg)));
        circle.setBackgroundTintList(ColorStateList.valueOf(getColor(bg)));
    }

    private void foldable(int headerId, int bodyId, int chevronId, boolean open) {
        View body = findViewById(bodyId);
        View chevron = findViewById(chevronId);
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        chevron.setRotation(open ? 180 : 0);
        findViewById(headerId).setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(show ? 180 : 0).setDuration(150).start();
        });
    }

    private void working(boolean start) {
        busy += start ? 1 : -1;
        boolean on = busy > 0;
        progress.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        sync.setEnabled(!on);
        sync.setText(on ? R.string.btn_syncing : R.string.btn_sync);
    }

    // ---- cameras ----

    private void askPassword(String ssid, String bssid) {
        EditText pass = field(R.string.hint_password);
        LinearLayout box = box();
        box.addView(pass);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_password_title)
                .setMessage(getString(R.string.dlg_password_body, ssid))
                .setView(box)
                .setPositiveButton(R.string.btn_save, null)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pw = pass.getText().toString();
            int bad = Camera.problem(ssid, pw);
            if (bad != 0) {
                pass.setError(getString(bad));
                return;
            }
            d.dismiss();
            addCamera(new Camera(ssid, pw, bssid, -1));
        });
    }

    private void typeCamera() {
        EditText ssid = field(R.string.hint_ssid);
        EditText pass = field(R.string.hint_password);
        LinearLayout box = box();
        box.addView(ssid);
        box.addView(pass);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_manual_title)
                .setView(box)
                .setPositiveButton(R.string.btn_save, null)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = ssid.getText().toString().trim();
            String pw = pass.getText().toString();
            int bad = Camera.problem(name, pw);
            if (bad != 0) {
                (bad == R.string.err_ssid ? ssid : pass).setError(getString(bad));
                return;
            }
            d.dismiss();
            addCamera(new Camera(name, pw, null, -1));
        });
    }

    private void addCamera(Camera cam) {
        List<Camera> cams = Camera.load(this);
        Camera.upsert(cams, cam);
        Camera.save(this, cams);
        showCameras();
        Toast.makeText(this, getString(R.string.camera_saved, cam.ssid), Toast.LENGTH_LONG).show();
        working(true);
        new Thread(() -> {
            CameraWifi.apply(this);
            CameraWifi.syncCamera(this, cam);
            runOnUiThread(() -> {
                working(false);
                showCameras();
            });
        }).start();
    }

    private void confirmRemove(Camera cam) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dlg_remove_title, cam.ssid))
                .setMessage(R.string.dlg_remove_body)
                .setPositiveButton(R.string.btn_remove, (d, w) -> removeCamera(cam))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void removeCamera(Camera cam) {
        List<Camera> cams = Camera.load(this);
        for (int i = cams.size() - 1; i >= 0; i--) {
            if (cams.get(i).ssid.equals(cam.ssid)) cams.remove(i);   // no removeIf on Android 6
        }
        Camera.save(this, cams);
        CameraPicker.forget(this, cam);
        showCameras();
        new Thread(() -> {
            CameraWifi.forget(this, cam);
            CameraWifi.apply(this);
        }).start();
    }

    private void applyWifi() {
        new Thread(() -> CameraWifi.apply(this)).start();
    }

    private EditText field(int hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setSingleLine(true);
        return e;
    }

    private LinearLayout box() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        return box;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---- keeping the app alive ----

    /**
     * Walks through what keeps the app running, one step per visit: the battery exemption (Android's own dialog),
     * a background restriction, the maker's auto-start page, the maker's battery saver. Steps already done are
     * skipped; nothing is asked while automatic sync is off.
     */
    private void guide() {
        if (!Prefs.auto(this)) return;
        SharedPreferences p = Prefs.of(this);
        while (guideStep < 4) {
            int step = guideStep++;
            if (step == 0 && !Background.exempt(this)) {
                Background.requestExemption(this);
                return;
            }
            if (step == 1 && Background.restricted(this)) {
                explain(R.string.dlg_restricted_title, getString(R.string.dlg_restricted_body),
                        () -> Background.open(this, Background.appDetails(this)));
                return;
            }
            if (step == 2 && autoStartPage != null && !p.getBoolean(Prefs.AUTOSTART_SEEN, false)) {
                explain(R.string.dlg_autostart_title, getString(R.string.dlg_autostart_body, Background.brand()),
                        this::openAutoStart);
                return;
            }
            if (step == 3 && oemBatteryPage != null && Background.exempt(this)
                    && !p.getBoolean(Prefs.OEM_BATTERY_SEEN, false)) {
                explain(R.string.dlg_oem_battery_title, getString(R.string.dlg_oem_battery_body),
                        this::openOemBattery);
                return;
            }
        }
    }

    private void explain(int title, String body, Runnable go) {
        dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton(R.string.dlg_open, (d, w) -> go.run())
                .setNegativeButton(R.string.dlg_later, (d, w) -> guide())
                .setCancelable(false)
                .show();
    }

    private void fixBackground() {
        if (!Background.exempt(this)) Background.requestExemption(this);
        else Background.open(this, Background.appDetails(this));
    }

    private void openAutoStart() {
        Prefs.of(this).edit().putBoolean(Prefs.AUTOSTART_SEEN, true).apply();
        Background.open(this, autoStartPage);
    }

    private void openOemBattery() {
        Prefs.of(this).edit().putBoolean(Prefs.OEM_BATTERY_SEEN, true).apply();
        Background.open(this, oemBatteryPage);
    }

    // ---- actions ----

    private void syncNow() {
        working(true);
        new Thread(() -> {
            CameraWifi.syncNow(this);
            runOnUiThread(() -> {
                working(false);
                showCameras();
            });
        }).start();
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }
}
