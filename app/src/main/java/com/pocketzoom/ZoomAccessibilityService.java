package com.pocketzoom;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.MagnificationConfig;
import android.content.Intent;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * Runs in the background, receives hardware key events and drives the system
 * magnification through the public AccessibilityService API. No root and no
 * WRITE_SECURE_SETTINGS are needed.
 */
public class ZoomAccessibilityService extends AccessibilityService {
    private static final String TAG = "PocketZoom";
    private static final long TOGGLE_DEBOUNCE_MS = 350L;
    private static final long NAV_REPEAT_MS = 90L;
    private static final float ZOOM_STEP = 0.25f;
    private static final float ZOOM_MIN = 1.5f;
    private static final float ZOOM_MAX = 7.0f;

    private static volatile ZoomAccessibilityService instance;

    private long lastToggleAt;

    private final Handler navHandler = new Handler(Looper.getMainLooper());
    private Runnable navRepeat;
    private int navKeyCode;

    private PanPad panPad;
    private MouseCursor mouseCursor;
    private Vibrator vibrator;
    private Runnable keepAlive;
    private volatile boolean zoomWanted;
    private long lastManualPanAt;
    private long lastCursorUiAt;
    private long lastFollowLogAt;
    private long lastHapticAt;
    private float lastHapticX = -1f;
    private float lastHapticY = -1f;
    private float lastCenterX = -1f;
    private float lastCenterY = -1f;
    private long lastCenterSavedAt;

    public static ZoomAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        // Flags are declared in accessibility_service_config.xml; calling
        // setServiceInfo() here would make some firmwares re-bind the service
        // and reset magnification, so it is intentionally not used.

        // Magnification does not survive a rebind or a process restart. If the
        // previous session left the firmware pointer on, switch it back off so
        // the state always matches the (now disabled) zoom.
        if (!isMagnified() && AppState.isMouseOwned(this)) {
            Log.i(TAG, "stale mouse mode at service start, switching off");
            disableMouseMode();
        }

        Notifications.update(this);
        AppState.notifyChanged();
        startKeepAlive();
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (panPad == null) {
            panPad = new PanPad(this);
        }
        if (mouseCursor == null) {
            mouseCursor = new MouseCursor((x, y) -> navHandler.post(() -> onCursor(x, y)));
            DisplayMetrics metrics = screenMetrics();
            Log.i(TAG, "screen " + metrics.widthPixels + "x" + metrics.heightPixels);
            mouseCursor.setScreenSize(metrics.widthPixels, metrics.heightPixels);
            mouseCursor.start();
        }
        updatePanPad();
        Log.i(TAG, "accessibility service connected");
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        String device = deviceName(event.getDeviceId());
        int navCode = navCode(event);
        String description = KeyNames.label(navCode)
                + " (" + event.getKeyCode() + ")"
                + (event.getAction() == KeyEvent.ACTION_DOWN ? " · DOWN" : " · UP")
                + (device.isEmpty() ? "" : " · " + device)
                + " · src=0x" + Integer.toHexString(event.getSource());
        AppState.setLastEvent(this, description);

        // Do not swallow Back while the user is choosing a key: it closes the app
        // and cancels the capture via onPause().
        if (AppState.isCapturing(this) && event.getKeyCode() != KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                String label = KeyNames.label(event.getKeyCode());
                AppState.assignKey(this, event.getKeyCode(), label, device);
                toast(getString(R.string.toast_key_assigned, label));
            }
            return true;
        }

        int mapped = AppState.getKeyCode(this);
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            Log.i(TAG, "key " + event.getKeyCode() + " scan=" + event.getScanCode()
                    + " dev='" + device + "' active=" + AppState.isActive(this)
                    + " mapped=" + mapped);
        }

        if (!AppState.isActive(this)) {
            return false;
        }

        if (mapped >= 0 && event.getKeyCode() == mapped && deviceMatches(device)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                long now = SystemClock.uptimeMillis();
                if (now - lastToggleAt >= TOGGLE_DEBOUNCE_MS) {
                    lastToggleAt = now;
                    toggleMagnification();
                }
            }
            return AppState.isConsume(this);
        }

        if (AppState.isNavEnabled(this) && isMagnified()) {
            int nav = navCode(event);
            if (isNavKey(nav)) {
                return handleNavKey(event, nav);
            }
        }
        return false;
    }

    // ------------------------------------------------- zoom / pan while magnified

    /**
     * The Retroid D-pad reports raw BTN_DPAD_* scan codes that Generic.kl does
     * not map, so the framework delivers them as KEYCODE_UNKNOWN. Recover the
     * direction from the scan code.
     */
    private static int navCode(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_UNKNOWN) {
            return code;
        }
        switch (event.getScanCode()) {
            case 544:
                return KeyEvent.KEYCODE_DPAD_UP;
            case 545:
                return KeyEvent.KEYCODE_DPAD_DOWN;
            case 546:
                return KeyEvent.KEYCODE_DPAD_LEFT;
            case 547:
                return KeyEvent.KEYCODE_DPAD_RIGHT;
            default:
                return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    private static boolean isNavKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return false;
        }
    }

    /**
     * Consumes R2/L2 (zoom) and the D-pad (pan) while the screen is magnified, so
     * the game never sees these presses. Holding a key repeats the action.
     */
    private boolean handleNavKey(KeyEvent event, int code) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                navKeyCode = code;
                performNav(code);
                startNavRepeat(code);
            }
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_UP) {
            stopNavRepeat(code);
            AppState.notifyChanged();
            return true;
        }

        return true;
    }

    private void startNavRepeat(final int code) {
        stopNavRepeat(code);
        navRepeat = new Runnable() {
            @Override
            public void run() {
                if (!isMagnified() || !AppState.isNavEnabled(ZoomAccessibilityService.this)) {
                    stopNavRepeat(code);
                    return;
                }
                performNav(code);
                navHandler.postDelayed(this, NAV_REPEAT_MS);
            }
        };
        navHandler.postDelayed(navRepeat, 260L);
    }

    private void stopNavRepeat(int code) {
        if (navRepeat != null) {
            navHandler.removeCallbacks(navRepeat);
            navRepeat = null;
        }
        if (navKeyCode == code) {
            navKeyCode = 0;
        }
    }

    private void performNav(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_R2:
                zoomBy(ZOOM_STEP);
                break;
            case KeyEvent.KEYCODE_BUTTON_L2:
                zoomBy(-ZOOM_STEP);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                panBy(-1f, 0f);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                panBy(1f, 0f);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                panBy(0f, -1f);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                panBy(0f, 1f);
                break;
            default:
                break;
        }
    }

    private void zoomBy(float delta) {
        try {
            MagnificationController controller = getMagnificationController();
            float current = controller.getScale();
            float next = current + delta;
            if (next < ZOOM_MIN) {
                next = ZOOM_MIN;
            } else if (next > ZOOM_MAX) {
                next = ZOOM_MAX;
            }
            if (Math.abs(next - current) < 0.01f) {
                return;
            }
            controller.setScale(next, false);
            AppState.setScaleSilently(this, next);
            lastCenterX = controller.getCenterX();
            lastCenterY = controller.getCenterY();
            persistCenter(false);
            if (panPad != null) {
                panPad.sync();
            }
        } catch (Throwable t) {
            Log.w(TAG, "zoomBy failed", t);
        }
    }

    /**
     * Real display size for the current rotation. The service resources can
     * briefly report portrait metrics while the app is rotating, which would
     * store a centre that is off-screen in landscape.
     */
    DisplayMetrics screenMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        try {
            android.view.Display display = getDisplay();
            if (display != null) {
                display.getRealMetrics(metrics);
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return metrics;
                }
            }
        } catch (Throwable ignored) {
            // fall back to the resources below
        }
        return getResources().getDisplayMetrics();
    }
    private void panBy(float dx, float dy) {
        try {
            MagnificationController controller = getMagnificationController();
            DisplayMetrics metrics = screenMetrics();
            float stepX = metrics.widthPixels * AppState.getPanStep(this);
            float stepY = metrics.heightPixels * AppState.getPanStep(this);
            setCenterClamped(controller, controller.getCenterX() + dx * stepX,
                    controller.getCenterY() + dy * stepY);
            lastManualPanAt = SystemClock.uptimeMillis();
            persistCenter(false);
        } catch (Throwable t) {
            Log.w(TAG, "panBy failed", t);
        }
    }

    /**
     * Moves the magnified area by a finger drag measured in screen pixels.
     * Used by the corner pad: the content follows the finger, so the centre
     * moves in the opposite direction, scaled down by the zoom factor.
     */
    public void panByScreenDelta(float dx, float dy) {
        try {
            MagnificationController controller = getMagnificationController();
            float scale = controller.getScale();
            if (scale <= 1.01f) {
                return;
            }
            setCenterClamped(controller,
                    controller.getCenterX() - dx / scale,
                    controller.getCenterY() - dy / scale);
            lastManualPanAt = SystemClock.uptimeMillis();
            persistCenter(false);
        } catch (Throwable t) {
            Log.w(TAG, "panByScreenDelta failed", t);
        }
    }

    /**
     * Keeps the magnified area inside the screen: at scale s only half a
     * screen divided by s may be cut off on each side.
     */
    private void setCenterClamped(MagnificationController controller, float x, float y) {
        DisplayMetrics metrics = screenMetrics();
        float scale = Math.max(1.01f, controller.getScale());
        float halfW = metrics.widthPixels / (2f * scale);
        float halfH = metrics.heightPixels / (2f * scale);
        x = Math.max(halfW, Math.min(metrics.widthPixels - halfW, x));
        y = Math.max(halfH, Math.min(metrics.heightPixels - halfH, y));
        controller.setCenter(x, y, false);
        lastCenterX = controller.getCenterX();
        lastCenterY = controller.getCenterY();
        if (panPad != null) {
            panPad.sync();
        }
    }

    /** Called by the activity when the pad preference changes. */
    public void refreshPanPad() {
        updatePanPad();
    }

    /** Shows or hides the finger pad depending on zoom state and settings. */
    private void updatePanPad() {
        if (panPad == null) {
            return;
        }
        try {
            if (isMagnified() && AppState.isPanPadEnabled(this)) {
                panPad.show();
                panPad.sync();
            } else {
                panPad.hide();
            }
        } catch (Throwable t) {
            Log.w(TAG, "updatePanPad failed", t);
        }
    }

    // ------------------------------------------------- right stick (raw input)


    private void startKeepAlive() {
        if (keepAlive != null) {
            return;
        }
        keepAlive = new Runnable() {
            @Override
            public void run() {
                if (zoomWanted) {
                    if (!isMagnified()) {
                        applyMagnification();
                    } else if (SystemClock.uptimeMillis() - lastManualPanAt > 1500L) {
                        // Toasts and other transient windows make the system
                        // magnifier pan to the bottom of the screen. Put the
                        // view back where the user left it.
                        try {
                            MagnificationController controller = getMagnificationController();
                            float dx = controller.getCenterX() - lastCenterX;
                            float dy = controller.getCenterY() - lastCenterY;
                            if (Math.abs(dx) > 4f || Math.abs(dy) > 4f) {
                                Log.i(TAG, "center guard "
                                        + Math.round(controller.getCenterX()) + ","
                                        + Math.round(controller.getCenterY()) + " -> "
                                        + Math.round(lastCenterX) + ","
                                        + Math.round(lastCenterY));
                                setCenterClamped(controller, lastCenterX, lastCenterY);
                            }
                        } catch (Throwable ignored) {
                            // controller is not ready
                        }
                    }
                }
                if (panPad != null) {
                    panPad.sync();
                }
                navHandler.postDelayed(this, 150L);
            }
        };
        navHandler.postDelayed(keepAlive, 700L);
    }

    private void stopKeepAlive() {
        if (keepAlive != null) {
            navHandler.removeCallbacks(keepAlive);
            keepAlive = null;
        }
    }

    /**
     * Android does not hand the raw stick to accessibility services, but the
     * Retroid firmware exposes the system cursor position through a readable
     * virtual-mouse node. The status line shows whether that stream is alive.
     */
    public String getStickStatus() {
        if (mouseCursor == null) {
            return "нет доступа к /dev/input/event8";
        }
        if (!mouseCursor.hasPosition()) {
            return "режим мыши выключен или курсор ещё не двигался";
        }
        return "режим мыши: X " + Math.round(mouseCursor.getX())
                + ", Y " + Math.round(mouseCursor.getY());
    }

    /** Centres the magnified area on the system mouse cursor. */
    private void followCursor(float x, float y) {
        if (!zoomWanted || mouseCursor == null || !AppState.isFollowCursor(this)) {
            return;
        }
        if (SystemClock.uptimeMillis() - lastManualPanAt < 1500L) {
            return;
        }
        try {
            MagnificationController controller = getMagnificationController();
            if (!isMagnified()) {
                return;
            }
            DisplayMetrics metrics = screenMetrics();
            mouseCursor.setScreenSize(metrics.widthPixels, metrics.heightPixels);
            setCenterClamped(controller, x, y);
            persistCenter(false);
            long now = SystemClock.uptimeMillis();
            if (now - lastFollowLogAt >= 1000L) {
                lastFollowLogAt = now;
                Log.i(TAG, "follow cursor " + Math.round(x) + "," + Math.round(y)
                        + " -> center " + Math.round(controller.getCenterX())
                        + "," + Math.round(controller.getCenterY()));
            }
        } catch (Throwable t) {
            Log.w(TAG, "followCursor failed", t);
        }
    }

    /**
     * A very short, low-amplitude tick while the pointer moves. The firmware
     * reports the cursor up to 40 times a second; the interval keeps the
     * vibration soft instead of turning into a continuous buzz.
     */
    private void hapticTick(float x, float y) {
        if (!zoomWanted || vibrator == null || !AppState.isCursorVibrate(this)) {
            return;
        }
        if (!vibrator.hasVibrator()) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastHapticAt < 80L) {
            return;
        }
        if (lastHapticX >= 0f) {
            float dx = x - lastHapticX;
            float dy = y - lastHapticY;
            if (dx * dx + dy * dy < 4f) {
                return;
            }
        }
        lastHapticAt = now;
        lastHapticX = x;
        lastHapticY = y;
        int amplitude = Math.max(1, Math.min(255,
                Math.round(AppState.getVibrateStrength(this) * 2.55f)));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(10L, amplitude));
            } else {
                vibrator.vibrate(10L);
            }
        } catch (Throwable t) {
            Log.w(TAG, "vibrate failed", t);
        }
    }

    /** Re-applies the user's cursor settings to the running mouse mapping. */
    public void applyCursorSettings() {
        if (!MouseMode.isOn(this)) {
            return;
        }
        boolean sensitivity = AppState.isCursorSensitivityCustom(this);
        boolean stick = AppState.isStickCustom(this);
        if (!sensitivity && !stick) {
            return;
        }
        MouseTuning.apply(this, AppState.getCursorSensitivity(this), sensitivity,
                AppState.isStickRight(this), stick);
    }

    /**
     * Cursor events arrive up to 30 times a second. They drive the zoom and
     * also refresh the diagnostics card, but the UI is only touched twice a
     * second so the list does not flicker.
     */
    private void onCursor(float x, float y) {
        followCursor(x, y);
        hapticTick(x, y);
        long now = SystemClock.uptimeMillis();
        if (now - lastCursorUiAt >= 500L) {
            lastCursorUiAt = now;
            AppState.notifyChanged();
        }
    }
    private boolean deviceMatches(String actualDevice) {
        String wanted = AppState.getKeyDevice(this);
        return wanted.isEmpty() || actualDevice.isEmpty() || wanted.equals(actualDevice);
    }

    /** Toggles full-screen system magnification. */
    public void toggleMagnification() {
        boolean wasMagnified = isMagnified();
        boolean success = setMagnified(!wasMagnified);
        if (!success) {
            toast(getString(R.string.toast_mag_fail));
            return;
        }
        AppState.notifyChanged();
    }

    public boolean isMagnified() {
        try {
            MagnificationController controller = getMagnificationController();
            MagnificationConfig config = controller.getMagnificationConfig();
            if (config == null) {
                return false;
            }
            // getCurrentMagnificationRegion() reports the whole screen on some
            // Android 13 builds even when nothing is zoomed, so trust the scale.
            return config.getScale() > 1.01f;
        } catch (Throwable t) {
            Log.w(TAG, "isMagnified failed", t);
            return false;
        }
    }

    public boolean setMagnified(boolean enable) {
        try {
            AccessibilityService.MagnificationController controller = getMagnificationController();

            if (enable) {
                zoomWanted = true;
                enableMouseMode();
                // Restore the area the user was looking at last time. The values
                // are kept in memory while the service lives and persisted in
                // preferences, so they survive M2 off/on and even a restart.
                if (lastCenterX < 0f || lastCenterY < 0f) {
                    lastCenterX = AppState.getSavedCenterX(this);
                    lastCenterY = AppState.getSavedCenterY(this);
                }
                // The cursor is the most recent point of interest, so prefer
                // it over the remembered area when following is enabled.
                if (AppState.isFollowCursor(this) && mouseCursor != null
                        && mouseCursor.hasPosition()) {
                    lastCenterX = mouseCursor.getX();
                    lastCenterY = mouseCursor.getY();
                }
                Log.i(TAG, "enable zoom: follow=" + AppState.isFollowCursor(this)
                        + " cursor=" + (mouseCursor == null ? "none"
                                : (mouseCursor.hasPosition()
                                        ? Math.round(mouseCursor.getX()) + "," + Math.round(mouseCursor.getY())
                                        : "no position"))
                        + " center=" + Math.round(lastCenterX) + "," + Math.round(lastCenterY));
                applyMagnification();
                updatePanPad();
                return isMagnified();
            }

            zoomWanted = false;
            saveCurrentView(controller);
            boolean reset = controller.resetCurrentMagnification(false);
            if (isMagnified()) {
                reset = controller.reset(false) || reset;
            }
            if (reset) {
                stopNavRepeat(navKeyCode);
            }
            updatePanPad();
            disableMouseMode();
            if (vibrator != null) {
                vibrator.cancel();
            }
            return reset || !isMagnified();
        } catch (Throwable t) {
            Log.w(TAG, "setMagnified failed", t);
            return false;
        }
    }

    /**
     * Turns the firmware pointer on together with the zoom, so M2 gets the
     * game into "look around" mode in one press. The firmware keeps its own
     * switch in a system setting, which is readable and (with the "Modify
     * system settings" access) writable by ordinary apps.
     */
    private void enableMouseMode() {
        if (!AppState.isAutoMouseMode(this) || !MouseMode.isSupported(this)) {
            return;
        }
        if (!MouseMode.canWrite(this)) {
            Log.i(TAG, "mouse mode: WRITE_SETTINGS not granted");
            toast(getString(R.string.toast_mouse_mode_permission));
            return;
        }
        boolean turnedOn = false;
        if (!MouseMode.isOn(this)) {
            turnedOn = MouseMode.set(this, true);
            if (turnedOn) {
                AppState.setMouseOwned(this, true);
            }
            Log.i(TAG, "mouse mode on: " + turnedOn);
        }
        if (AppState.isCursorSensitivityCustom(this) || AppState.isStickCustom(this)) {
            // The settings app reacts to the same setting and pushes its saved
            // mapping right after the change, so wait briefly before patching.
            // A second attempt covers a slow settings-app restart.
            final long delay = turnedOn ? 450L : 80L;
            navHandler.postDelayed(this::applyCursorSettings, delay);
            navHandler.postDelayed(this::applyCursorSettings, delay + 700L);
        }
    }

    private void disableMouseMode() {
        if (!AppState.isMouseOwned(this)) {
            return;
        }
        AppState.setMouseOwned(this, false);
        if (!MouseMode.isSupported(this) || !MouseMode.canWrite(this)) {
            return;
        }
        boolean ok = MouseMode.set(this, false);
        Log.i(TAG, "mouse mode off: " + ok);
    }

    /** Short human-readable state of the firmware pointer for diagnostics. */
    public String getMouseModeStatus() {
        if (!MouseMode.isSupported(this)) {
            return getString(R.string.diag_mouse_unsupported);
        }
        String state = MouseMode.isOn(this)
                ? getString(R.string.diag_state_on)
                : getString(R.string.diag_state_off);
        if (!MouseMode.canWrite(this)) {
            state += getString(R.string.diag_mouse_no_access);
        }
        return state;
    }

    /**
     * Remembers the current scale and screen centre, so the next enable can
     * reproduce exactly the same magnified area. Called when zoom is turned
     * off, and throttled while the user pans.
     */
    private void saveCurrentView(AccessibilityService.MagnificationController controller) {
        try {
            if (!isMagnified()) {
                return;
            }
            float scale = controller.getScale();
            if (scale > 1.01f) {
                AppState.setScaleSilently(this, scale);
            }
            lastCenterX = controller.getCenterX();
            lastCenterY = controller.getCenterY();
            persistCenter(true);
        } catch (Throwable t) {
            Log.w(TAG, "saveCurrentView failed", t);
        }
    }

    /** Persists the last centre; throttled because the stick polls every 70 ms. */
    private void persistCenter(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastCenterSavedAt < 400L) {
            return;
        }
        lastCenterSavedAt = now;
        if (lastCenterX >= 0f && lastCenterY >= 0f) {
            AppState.setSavedCenter(this, lastCenterX, lastCenterY);
        }
    }

    /**
     * Applies the configured scale. Separated from {@link #setMagnified(boolean)}
     * so the keep-alive watchdog can restore the zoom after Android or the
     * launcher drops it on a foreground app change.
     */
    private void applyMagnification() {
        try {
            AccessibilityService.MagnificationController controller = getMagnificationController();
            float scale = AppState.getScale(this);
            MagnificationConfig config = new MagnificationConfig.Builder()
                    .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
                    .setScale(scale)
                    .build();
            boolean applied = controller.setMagnificationConfig(config, false);
            if (!applied && !isMagnified()) {
                controller.setScale(scale, false);
            }
            if (!isMagnified()) {
                return;
            }
            DisplayMetrics metrics = screenMetrics();
            if (lastCenterX >= 0f && lastCenterY >= 0f) {
                // Clamp as well: a centre stored in another rotation can be
                // outside the current screen.
                float halfW = metrics.widthPixels / (2f * Math.max(1.01f, scale));
                float halfH = metrics.heightPixels / (2f * Math.max(1.01f, scale));
                float x = Math.max(halfW, Math.min(metrics.widthPixels - halfW, lastCenterX));
                float y = Math.max(halfH, Math.min(metrics.heightPixels - halfH, lastCenterY));
                Log.i(TAG, "set center " + Math.round(x) + "," + Math.round(y)
                        + " (want " + Math.round(lastCenterX) + "," + Math.round(lastCenterY)
                        + " screen " + metrics.widthPixels + "x" + metrics.heightPixels
                        + " scale " + scale + ")");
                controller.setCenter(x, y, false);
                Log.i(TAG, "center now " + Math.round(controller.getCenterX())
                        + "," + Math.round(controller.getCenterY()));
                lastCenterX = x;
                lastCenterY = y;
                // The framework applies a new MagnificationConfig
                // asynchronously and can restore its own (stale) centre a
                // moment after our setCenter(). Re-apply the wanted centre a
                // few hundred milliseconds later so the zoom really lands on
                // the cursor.
                final float wantX = x;
                final float wantY = y;
                navHandler.postDelayed(() -> {
                    if (!zoomWanted) {
                        return;
                    }
                    try {
                        MagnificationController c = getMagnificationController();
                        if (!isMagnified()) {
                            return;
                        }
                        c.setCenter(wantX, wantY, false);
                        Log.i(TAG, "center re-applied " + Math.round(c.getCenterX())
                                + "," + Math.round(c.getCenterY()));
                    } catch (Throwable ignored) {
                        // magnifier went away
                    }
                }, 600L);
            } else {
                // First run: remember the default area so later re-applies keep it.
                lastCenterX = controller.getCenterX();
                lastCenterY = controller.getCenterY();
            }
            updatePanPad();
            AppState.notifyChanged();
        } catch (Throwable t) {
            Log.w(TAG, "applyMagnification failed", t);
        }
    }

    /** Applies a new zoom level without changing the on/off state. */
    public void applyScale(float scale) {
        if (!isMagnified()) {
            return;
        }
        try {
            MagnificationConfig config = new MagnificationConfig.Builder()
                    .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
                    .setScale(scale)
                    .build();
            getMagnificationController().setMagnificationConfig(config, false);
            if (panPad != null) {
                panPad.sync();
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyScale failed", t);
        }
    }

    private String deviceName(int deviceId) {
        try {
            InputManager manager = (InputManager) getSystemService(INPUT_SERVICE);
            if (manager == null) {
                return "";
            }
            InputDevice device = manager.getInputDevice(deviceId);
            if (device == null || device.getName() == null) {
                return "";
            }
            return device.getName();
        } catch (Throwable t) {
            return "";
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used: the service only needs key events and magnification control.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopKeepAlive();
        disableMouseMode();
        if (vibrator != null) {
            vibrator.cancel();
        }
        if (panPad != null) {
            panPad.hide();
            panPad = null;
        }
        if (mouseCursor != null) {
            mouseCursor.stop();
            mouseCursor = null;
        }
        if (instance == this) {
            instance = null;
        }
        Notifications.cancel(this);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopKeepAlive();
        disableMouseMode();
        if (vibrator != null) {
            vibrator.cancel();
        }
        if (panPad != null) {
            panPad.hide();
            panPad = null;
        }
        if (mouseCursor != null) {
            mouseCursor.stop();
            mouseCursor = null;
        }
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }
}

