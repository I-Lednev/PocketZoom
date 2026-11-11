package com.pocketzoom;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Region;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/** Single screen: status, key assignment, zoom level, on/off and diagnostics. */
public class MainActivity extends Activity implements AppState.Listener {
    private static final int REQUEST_NOTIFICATIONS = 41;

    private static final float MIN_SCALE = 1.5f;
    private static final float MAX_SCALE = 7.0f;
    private static final int SCALE_STEPS = Math.round((MAX_SCALE - MIN_SCALE) * 10f);

    /** Pan slider range: 3% .. 40% of the screen per step. */
    private static final int PAN_MIN_PERCENT = 3;
    private static final int PAN_STEPS = 37;

    /** Cursor sensitivity slider: 0.2x .. 3.0x in 0.1 steps. */
    private static final float SENS_MIN = 0.2f;
    private static final int SENS_STEPS = 28;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean updating;
    private Runnable sensitivityApply;

    private TextView tvServiceState;
    private TextView tvZoomState;
    private TextView tvActiveState;
    private TextView tvKey;
    private TextView tvScale;
    private TextView tvDiag;
    private TextView tvLastKey;

    private Button btnAccessibility;
    private Button btnAssign;
    private Button btnClearKey;
    private Button btnEnable;
    private Button btnDisable;
    private Button btnTest;
    private Button btnCopy;
    private Button btnWriteSettings;

    private CheckBox cbConsume;
    private CheckBox cbNav;
    private CheckBox cbPanPad;
    private CheckBox cbFollowCursor;
    private CheckBox cbAutoMouse;
    private CheckBox cbCursorVibrate;
    private CheckBox cbStickRight;
    private SeekBar sbScale;
    private SeekBar sbPan;
    private SeekBar sbCursorSpeed;
    private SeekBar sbVibrateStrength;
    private TextView tvPan;
    private TextView tvCursorSpeed;
    private TextView tvVibrateStrength;

    private TextView tvUpdateStatus;
    private TextView tvReleaseNotes;
    private TextView tvAbout;
    private ProgressBar pbUpdate;
    private Button btnCheckUpdates;
    private Button btnInstallUpdate;
    private Button btnGithub;
    private CheckBox cbAutoUpdate;
    private UpdateChecker.UpdateInfo pendingUpdate;
    private File downloadedApk;
    private boolean updateBusy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvServiceState = findViewById(R.id.tvServiceState);
        tvZoomState = findViewById(R.id.tvZoomState);
        tvActiveState = findViewById(R.id.tvActiveState);
        tvKey = findViewById(R.id.tvKey);
        tvScale = findViewById(R.id.tvScale);
        tvDiag = findViewById(R.id.tvDiag);
        tvLastKey = findViewById(R.id.tvLastKey);
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus);
        tvReleaseNotes = findViewById(R.id.tvReleaseNotes);
        tvAbout = findViewById(R.id.tvAbout);
        pbUpdate = findViewById(R.id.pbUpdate);

        btnAccessibility = findViewById(R.id.btnAccessibility);
        btnAssign = findViewById(R.id.btnAssign);
        btnClearKey = findViewById(R.id.btnClearKey);
        btnEnable = findViewById(R.id.btnEnable);
        btnDisable = findViewById(R.id.btnDisable);
        btnTest = findViewById(R.id.btnTest);
        btnCopy = findViewById(R.id.btnCopy);
        btnWriteSettings = findViewById(R.id.btnWriteSettings);
        btnCheckUpdates = findViewById(R.id.btnCheckUpdates);
        btnInstallUpdate = findViewById(R.id.btnInstallUpdate);
        btnGithub = findViewById(R.id.btnGithub);
        cbAutoUpdate = findViewById(R.id.cbAutoUpdate);

        cbConsume = findViewById(R.id.cbConsume);
        sbScale = findViewById(R.id.sbScale);

        cbNav = findViewById(R.id.cbNav);
        cbPanPad = findViewById(R.id.cbPanPad);
        cbFollowCursor = findViewById(R.id.cbFollowCursor);
        cbAutoMouse = findViewById(R.id.cbAutoMouse);
        sbPan = findViewById(R.id.sbPan);
        tvPan = findViewById(R.id.tvPan);
        sbCursorSpeed = findViewById(R.id.sbCursorSpeed);
        tvCursorSpeed = findViewById(R.id.tvCursorSpeed);
        cbCursorVibrate = findViewById(R.id.cbCursorVibrate);
        cbStickRight = findViewById(R.id.cbStickRight);
        sbVibrateStrength = findViewById(R.id.sbVibrateStrength);
        tvVibrateStrength = findViewById(R.id.tvVibrateStrength);

        sbScale.setMax(SCALE_STEPS);
        sbPan.setMax(PAN_STEPS);
        sbCursorSpeed.setMax(SENS_STEPS);
        sbVibrateStrength.setMax(90);

        btnAccessibility.setOnClickListener(v -> openAccessibilitySettings());
        btnAssign.setOnClickListener(v -> toggleCapture());
        btnClearKey.setOnClickListener(v -> clearKey());
        btnEnable.setOnClickListener(v -> enable());
        btnDisable.setOnClickListener(v -> disable());
        btnTest.setOnClickListener(v -> testToggle());
        btnCopy.setOnClickListener(v -> copyReport());
        btnWriteSettings.setOnClickListener(v -> openWriteSettingsAccess());
        btnCheckUpdates.setOnClickListener(v -> checkForUpdates());
        btnInstallUpdate.setOnClickListener(v -> downloadOrInstall());
        btnGithub.setOnClickListener(v -> openUrl(UpdateChecker.releasesUrl()));
        cbAutoUpdate.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!updating) {
                AppState.setAutoUpdateCheck(this, checked);
            }
        });

        cbConsume.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!updating) {
                AppState.setConsume(this, checked);
            }
        });

        cbNav.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!updating) {
                AppState.setNavEnabled(this, checked);
            }
        });

        cbPanPad.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!updating) {
                AppState.setPanPadEnabled(this, checked);
                ZoomAccessibilityService service = ZoomAccessibilityService.getInstance();
                if (service != null) {
                    service.refreshPanPad();
                }
            }
        });

        cbFollowCursor.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!updating) {
                AppState.setFollowCursor(this, checked);
            }
        });

        cbAutoMouse.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!updating) {
                AppState.setAutoMouseMode(this, checked);
            }
        });

        cbCursorVibrate.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!updating) {
                AppState.setCursorVibrate(this, checked);
            }
        });

        cbStickRight.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!updating) {
                AppState.setStickRight(this, checked);
                scheduleCursorSettingsApply();
            }
        });

        sbCursorSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = sensitivityForProgress(progress);
                tvCursorSpeed.setText(getString(R.string.cursor_speed, formatScale(value)));
                if (fromUser) {
                    AppState.setCursorSensitivity(MainActivity.this, value);
                    scheduleCursorSettingsApply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        sbVibrateStrength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = progress + 10;
                tvVibrateStrength.setText(getString(R.string.vibrate_strength, percent));
                if (fromUser) {
                    AppState.setVibrateStrength(MainActivity.this, percent);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        sbPan.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvPan.setText(getString(R.string.nav_pan_step, progress + PAN_MIN_PERCENT));
                if (fromUser) {
                    AppState.setPanStep(MainActivity.this,
                            (progress + PAN_MIN_PERCENT) / 100f);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        sbScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float scale = scaleForProgress(progress);
                tvScale.setText(formatScale(scale));
                if (fromUser) {
                    AppState.setScale(MainActivity.this, scale);
                    ZoomAccessibilityService service = ZoomAccessibilityService.getInstance();
                    if (service != null) {
                        service.applyScale(scale);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        String version = UpdateChecker.currentVersion(this);
        tvAbout.setText(getString(R.string.about_text, version));
        tvUpdateStatus.setText(getString(R.string.update_current, version));

        requestNotificationPermission();
        update();
        long lastCheck = AppState.getLastUpdateCheck(this);
        if (AppState.isAutoUpdateCheck(this)
                && System.currentTimeMillis() - lastCheck > 12L * 60L * 60L * 1000L) {
            checkForUpdates();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppState.addListener(this);
        update();
        if (downloadedApk != null && downloadedApk.isFile()
                && !updateBusy && UpdateChecker.canInstall(this)) {
            tvUpdateStatus.setText(R.string.update_ready_install);
            btnInstallUpdate.setText(R.string.install_update);
            btnInstallUpdate.setVisibility(View.VISIBLE);
            btnInstallUpdate.setEnabled(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        AppState.removeListener(this);
        if (AppState.isCapturing(this)) {
            AppState.setCapturing(this, false);
        }
    }

    @Override
    public void onStateChanged() {
        handler.post(this::update);
    }

    // -------------------------------------------------------------- updates

    private void checkForUpdates() {
        if (updateBusy) {
            return;
        }
        updateBusy = true;
        pendingUpdate = null;
        downloadedApk = null;
        pbUpdate.setVisibility(View.VISIBLE);
        pbUpdate.setIndeterminate(true);
        tvReleaseNotes.setVisibility(View.GONE);
        btnInstallUpdate.setVisibility(View.GONE);
        btnCheckUpdates.setEnabled(false);
        tvUpdateStatus.setText(R.string.update_checking);
        UpdateChecker.check(this, updateListener);
    }

    private void downloadOrInstall() {
        if (updateBusy) {
            return;
        }
        if (downloadedApk != null && downloadedApk.isFile()) {
            installDownloaded();
            return;
        }
        if (pendingUpdate == null) {
            return;
        }
        if (!UpdateChecker.canInstall(this)) {
            UpdateChecker.openInstallPermission(this);
            tvUpdateStatus.setText(R.string.update_allow_install);
            return;
        }
        startDownload();
    }

    private void startDownload() {
        if (pendingUpdate == null) {
            return;
        }
        updateBusy = true;
        pbUpdate.setVisibility(View.VISIBLE);
        pbUpdate.setIndeterminate(false);
        pbUpdate.setMax(100);
        pbUpdate.setProgress(0);
        btnCheckUpdates.setEnabled(false);
        btnInstallUpdate.setEnabled(false);
        tvUpdateStatus.setText(R.string.update_downloading);
        UpdateChecker.download(this, pendingUpdate, updateListener);
    }

    private void installDownloaded() {
        if (downloadedApk == null || !downloadedApk.isFile()) {
            tvUpdateStatus.setText(R.string.update_file_missing);
            return;
        }
        if (!UpdateChecker.canInstall(this)) {
            UpdateChecker.openInstallPermission(this);
            tvUpdateStatus.setText(R.string.update_allow_install);
            return;
        }
        if (UpdateChecker.launchInstaller(this, downloadedApk)) {
            tvUpdateStatus.setText(R.string.update_installing);
        } else {
            tvUpdateStatus.setText(R.string.update_installer_missing);
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            toast(getString(R.string.update_link_failed));
        }
    }

    private final UpdateChecker.Listener updateListener = new UpdateChecker.Listener() {
        @Override
        public void onCheckResult(UpdateChecker.UpdateInfo info, String error) {
            updateBusy = false;
            pbUpdate.setVisibility(View.GONE);
            btnCheckUpdates.setEnabled(true);
            AppState.setLastUpdateCheck(MainActivity.this, System.currentTimeMillis());
            if (error != null) {
                tvUpdateStatus.setText(getString(R.string.update_error, error));
                return;
            }
            if (info == null) {
                tvUpdateStatus.setText(getString(R.string.update_latest,
                        UpdateChecker.currentVersion(MainActivity.this)));
                return;
            }
            pendingUpdate = info;
            tvUpdateStatus.setText(getString(R.string.update_available, info.version));
            String notes = info.notes == null ? "" : info.notes.trim();
            tvReleaseNotes.setText(notes.isEmpty()
                    ? getString(R.string.update_no_notes)
                    : getString(R.string.update_notes_prefix, notes));
            tvReleaseNotes.setVisibility(View.VISIBLE);
            btnInstallUpdate.setText(R.string.download_install);
            btnInstallUpdate.setVisibility(View.VISIBLE);
            btnInstallUpdate.setEnabled(true);
        }

        @Override
        public void onDownloadProgress(int percent) {
            if (percent < 0) {
                pbUpdate.setIndeterminate(true);
                tvUpdateStatus.setText(R.string.update_downloading);
            } else {
                pbUpdate.setIndeterminate(false);
                pbUpdate.setProgress(percent);
                tvUpdateStatus.setText(getString(R.string.update_download_progress, percent));
            }
        }

        @Override
        public void onDownloadResult(File apk, String error) {
            updateBusy = false;
            pbUpdate.setVisibility(View.GONE);
            btnCheckUpdates.setEnabled(true);
            btnInstallUpdate.setEnabled(true);
            if (error != null) {
                tvUpdateStatus.setText(getString(R.string.update_failed, error));
                return;
            }
            downloadedApk = apk;
            tvUpdateStatus.setText(R.string.update_downloaded);
            btnInstallUpdate.setText(R.string.install_update);
            btnInstallUpdate.setVisibility(View.VISIBLE);
            if (UpdateChecker.canInstall(MainActivity.this)) {
                if (UpdateChecker.launchInstaller(MainActivity.this, apk)) {
                    tvUpdateStatus.setText(R.string.update_installing);
                } else {
                    tvUpdateStatus.setText(R.string.update_installer_missing);
                }
            } else {
                UpdateChecker.openInstallPermission(MainActivity.this);
                tvUpdateStatus.setText(R.string.update_allow_install);
            }
        }
    };

    // ---------------------------------------------------------------- actions

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Throwable ignored) {
                toast(getString(R.string.toast_settings_fail));
            }
        }
    }

    private void openWriteSettingsAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Throwable ignored) {
                toast(getString(R.string.toast_write_settings_failed));
            }
        }
    }

    private void toggleCapture() {
        if (AppState.isCapturing(this)) {
            AppState.setCapturing(this, false);
            toast(getString(R.string.toast_capture_cancelled));
            return;
        }
        if (!isServiceEnabled() || ZoomAccessibilityService.getInstance() == null) {
            toast(getString(R.string.toast_service_off));
            openAccessibilitySettings();
            return;
        }
        AppState.setCapturing(this, true);
        toast(getString(R.string.toast_capture_started));
    }

    private void clearKey() {
        if (AppState.getKeyCode(this) < 0) {
            toast(getString(R.string.toast_key_none));
            return;
        }
        AppState.clearKey(this);
        AppState.setCapturing(this, false);
        toast(getString(R.string.toast_key_cleared));
    }

    private void enable() {
        if (!isServiceEnabled()) {
            toast(getString(R.string.toast_service_off));
            openAccessibilitySettings();
            return;
        }
        ZoomAccessibilityService service = ZoomAccessibilityService.getInstance();
        if (service == null) {
            AppState.setActive(this, true);
            Notifications.update(this);
            toast(getString(R.string.toast_not_connected));
            return;
        }
        AppState.setActive(this, true);
        boolean magnified = service.setMagnified(true);
        Notifications.update(this);
        if (!magnified) {
            toast(getString(R.string.toast_mag_fail));
        }
    }

    private void disable() {
        ZoomAccessibilityService service = ZoomAccessibilityService.getInstance();
        if (service != null) {
            service.setMagnified(false);
        }
        AppState.setActive(this, false);
        AppState.setCapturing(this, false);
        Notifications.cancel(this);
    }

    private void testToggle() {
        ZoomAccessibilityService service = ZoomAccessibilityService.getInstance();
        if (service == null) {
            toast(getString(R.string.toast_service_off));
            openAccessibilitySettings();
            return;
        }
        service.toggleMagnification();
    }

    private void copyReport() {
        String report = buildReport();
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), report));
                toast(getString(R.string.toast_report_copied));
                return;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        toast(getString(R.string.toast_report_failed));
    }

    // ------------------------------------------------------------- ui update

    private void update() {
        if (updating) {
            return;
        }
        updating = true;
        try {
            boolean serviceEnabled = isServiceEnabled();
            ZoomAccessibilityService service = ZoomAccessibilityService.getInstance();

            tvServiceState.setText(serviceEnabled
                    ? (service != null ? R.string.service_on : R.string.service_pending)
                    : R.string.service_off);

            if (service == null) {
                tvZoomState.setText(R.string.zoom_unknown);
            } else {
                tvZoomState.setText(service.isMagnified()
                        ? R.string.zoom_on
                        : R.string.zoom_off);
            }

            tvActiveState.setText(AppState.isActive(this)
                    ? R.string.active_on
                    : R.string.active_off);

            int code = AppState.getKeyCode(this);
            if (code < 0) {
                tvKey.setText(R.string.key_none);
            } else {
                tvKey.setText(getString(R.string.key_value,
                        AppState.getKeyName(this), code));
            }

            float scale = AppState.getScale(this);
            sbScale.setProgress(progressForScale(scale));
            tvScale.setText(formatScale(scale));

            cbConsume.setChecked(AppState.isConsume(this));

            cbNav.setChecked(AppState.isNavEnabled(this));
            cbPanPad.setChecked(AppState.isPanPadEnabled(this));
            cbFollowCursor.setChecked(AppState.isFollowCursor(this));
            cbAutoMouse.setChecked(AppState.isAutoMouseMode(this));
            float sensitivity = AppState.getCursorSensitivity(this);
            sbCursorSpeed.setProgress(progressForSensitivity(sensitivity));
            tvCursorSpeed.setText(getString(R.string.cursor_speed, formatScale(sensitivity)));
            cbCursorVibrate.setChecked(AppState.isCursorVibrate(this));
            cbStickRight.setChecked(AppState.isStickRight(this));
            cbAutoUpdate.setChecked(AppState.isAutoUpdateCheck(this));
            int vibrateStrength = AppState.getVibrateStrength(this);
            sbVibrateStrength.setProgress(vibrateStrength - 10);
            tvVibrateStrength.setText(getString(R.string.vibrate_strength, vibrateStrength));
            btnWriteSettings.setVisibility(MouseMode.canWrite(this)
                    ? View.GONE
                    : View.VISIBLE);
            int panPercent = Math.round(AppState.getPanStep(this) * 100f);
            sbPan.setProgress(Math.max(0, Math.min(PAN_STEPS, panPercent - PAN_MIN_PERCENT)));

            btnAssign.setText(AppState.isCapturing(this)
                    ? R.string.assign_wait
                    : R.string.assign);
            btnClearKey.setEnabled(code >= 0);
            boolean magnified = service != null && service.isMagnified();
            btnEnable.setEnabled(!AppState.isActive(this) || !magnified);
            btnDisable.setEnabled(AppState.isActive(this) || magnified);

            String lastEvent = AppState.getLastEvent(this);
            tvLastKey.setText(lastEvent.isEmpty()
                    ? getString(R.string.diag_none)
                    : getString(R.string.diag_last, lastEvent));

            tvDiag.setText(getString(R.string.diag_device,
                    Build.MANUFACTURER + " " + Build.MODEL,
                    Build.VERSION.RELEASE,
                    Build.VERSION.SDK_INT)
                    + "\n" + getString(R.string.diag_service,
                    serviceEnabled
                            ? (service != null ? getString(R.string.diag_state_on)
                            : getString(R.string.diag_state_pending))
                            : getString(R.string.diag_state_off))
                    + "\n" + getString(R.string.diag_mag_line,
                    service == null
                            ? "—"
                            : (service.isMagnified() ? getString(R.string.diag_yes)
                            : getString(R.string.diag_no)))
                    + "\n" + getString(R.string.diag_cursor,
                    service == null ? "—" : service.getStickStatus())
                    + "\n" + getString(R.string.diag_mouse_line,
                    service == null ? "—" : service.getMouseModeStatus()));
        } finally {
            updating = false;
        }
    }

    // ----------------------------------------------------------------- utils

    private String buildReport() {
        ZoomAccessibilityService service = ZoomAccessibilityService.getInstance();
        StringBuilder sb = new StringBuilder();

        sb.append("Pocket Zoom 1.0\n");
        sb.append("Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Устройство: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n');
        sb.append("Служба в настройках: ")
                .append(isServiceEnabled() ? "да" : "нет").append('\n');
        sb.append("Служба подключена: ")
                .append(service != null ? "да" : "нет").append('\n');
        sb.append("Фоновая работа: ")
                .append(AppState.isActive(this) ? "вкл" : "выкл").append('\n');

        int code = AppState.getKeyCode(this);
        if (code < 0) {
            sb.append("Кнопка: не назначена\n");
        } else {
            sb.append("Кнопка: ").append(KeyNames.label(code))
                    .append(" (код ").append(code).append("), устройство: ")
                    .append(AppState.getKeyDevice(this)).append('\n');
        }
        sb.append("Не передавать нажатие: ")
                .append(AppState.isConsume(this) ? "да" : "нет").append('\n');
        sb.append("Масштаб: ").append(formatScale(AppState.getScale(this))).append('\n');

        if (service != null) {
            sb.append("Увеличение сейчас: ")
                    .append(service.isMagnified() ? "да" : "нет").append('\n');
            sb.append("Курсор: ").append(service.getStickStatus()).append('\n');
            sb.append("Режим мыши: ").append(service.getMouseModeStatus()).append('\n');
            sb.append("Шаг перемещения: ")
                    .append(Math.round(AppState.getPanStep(this) * 100f)).append("%\n");
            try {
                Region region = service.getMagnificationController()
                        .getCurrentMagnificationRegion();
                sb.append("Регион: ")
                        .append(region == null || region.isEmpty()
                                ? "пусто"
                                : region.toString())
                        .append('\n');
            } catch (Throwable ignored) {
                sb.append("Регион: недоступен\n");
            }
        }

        String last = AppState.getLastEvent(this);
        sb.append("Последнее событие: ")
                .append(last.isEmpty() ? "нет" : last).append('\n');
        return sb.toString();
    }

    private boolean isServiceEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabled)) {
                return false;
            }
            ComponentName expected = new ComponentName(this, ZoomAccessibilityService.class);
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(enabled);
            while (splitter.hasNext()) {
                ComponentName name = ComponentName.unflattenFromString(splitter.next());
                if (expected.equals(name)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // treat as disabled
        }
        return ZoomAccessibilityService.getInstance() != null;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATIONS);
            } catch (Throwable ignored) {
                // the notification is optional; the app works without it
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            Notifications.update(this);
        }
    }

    private static float scaleForProgress(int progress) {
        float scale = MIN_SCALE + progress / 10f;
        if (scale < MIN_SCALE) {
            scale = MIN_SCALE;
        } else if (scale > MAX_SCALE) {
            scale = MAX_SCALE;
        }
        return scale;
    }

    private static int progressForScale(float scale) {
        int progress = Math.round((scale - MIN_SCALE) * 10f);
        if (progress < 0) {
            progress = 0;
        } else if (progress > SCALE_STEPS) {
            progress = SCALE_STEPS;
        }
        return progress;
    }

    private static String formatScale(float scale) {
        return String.format(Locale.US, "%.1f??", scale);
    }

    private static float sensitivityForProgress(int progress) {
        float value = SENS_MIN + progress / 10f;
        if (value < SENS_MIN) {
            value = SENS_MIN;
        } else if (value > 3.0f) {
            value = 3.0f;
        }
        return value;
    }

    private static int progressForSensitivity(float value) {
        int progress = Math.round((value - SENS_MIN) * 10f);
        if (progress < 0) {
            progress = 0;
        } else if (progress > SENS_STEPS) {
            progress = SENS_STEPS;
        }
        return progress;
    }

    private void scheduleCursorSettingsApply() {
        if (sensitivityApply != null) {
            handler.removeCallbacks(sensitivityApply);
        }
        sensitivityApply = () -> {
            ZoomAccessibilityService service = ZoomAccessibilityService.getInstance();
            if (service != null) {
                service.applyCursorSettings();
            }
        };
        handler.postDelayed(sensitivityApply, 350L);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
