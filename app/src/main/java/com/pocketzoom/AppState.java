package com.pocketzoom;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Small shared state holder. The activity, the accessibility service and the
 * notification receiver all live in the same process, so a static listener
 * list is enough to keep the UI in sync.
 */
public final class AppState {
    private static final String PREFS = "pocketzoom_state";

    private static final String KEY_ACTIVE = "active";
    private static final String KEY_CAPTURING = "capturing";
    private static final String KEY_CODE = "key_code";
    private static final String KEY_NAME = "key_name";
    private static final String KEY_DEVICE = "key_device";
    private static final String KEY_CONSUME = "consume";
    private static final String KEY_SCALE = "scale";
    private static final String KEY_LAST_EVENT = "last_event";
    private static final String KEY_NAV = "nav_enabled";
    private static final String KEY_PAN_STEP = "pan_step";
    private static final String KEY_PAN_PAD = "pan_pad";
    private static final String KEY_FOLLOW_CURSOR = "follow_cursor";
    private static final String KEY_AUTO_MOUSE = "auto_mouse_mode";
    private static final String KEY_MOUSE_OWNED = "mouse_mode_owned";
    private static final String KEY_CURSOR_SENSITIVITY = "cursor_sensitivity";
    private static final String KEY_CURSOR_SENSITIVITY_CUSTOM = "cursor_sensitivity_custom";
    private static final String KEY_STICK_RIGHT = "cursor_stick_right";
    private static final String KEY_STICK_CUSTOM = "cursor_stick_custom";
    private static final String KEY_CURSOR_VIBRATE = "cursor_vibrate";
    private static final String KEY_VIBRATE_STRENGTH = "cursor_vibrate_strength";
    private static final String KEY_AUTO_UPDATE = "auto_update_check";
    private static final String KEY_LAST_UPDATE_CHECK = "last_update_check";
    private static final String KEY_CENTER_X = "center_x";
    private static final String KEY_CENTER_Y = "center_y";

    public interface Listener {
        void onStateChanged();
    }

    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static SharedPreferences prefs;

    private AppState() {
    }

    public static synchronized SharedPreferences prefs(Context context) {
        if (prefs == null) {
            prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
        return prefs;
    }

    public static void addListener(Listener listener) {
        synchronized (LISTENERS) {
            if (!LISTENERS.contains(listener)) {
                LISTENERS.add(listener);
            }
        }
    }

    public static void removeListener(Listener listener) {
        synchronized (LISTENERS) {
            LISTENERS.remove(listener);
        }
    }

    public static void notifyChanged() {
        List<Listener> copy;
        synchronized (LISTENERS) {
            copy = new ArrayList<>(LISTENERS);
        }
        for (Listener listener : copy) {
            listener.onStateChanged();
        }
    }

    public static boolean isActive(Context context) {
        return prefs(context).getBoolean(KEY_ACTIVE, false);
    }

    public static void setActive(Context context, boolean active) {
        prefs(context).edit().putBoolean(KEY_ACTIVE, active).apply();
        notifyChanged();
    }

    public static boolean isCapturing(Context context) {
        return prefs(context).getBoolean(KEY_CAPTURING, false);
    }

    public static void setCapturing(Context context, boolean capturing) {
        prefs(context).edit().putBoolean(KEY_CAPTURING, capturing).apply();
        notifyChanged();
    }

    /**
     * True while Pocket Zoom itself switched the firmware pointer on. The flag is
     * persisted so a restarted service can still switch it back off, even if
     * the process died while the zoom was active.
     */
    public static boolean isMouseOwned(Context context) {
        return prefs(context).getBoolean(KEY_MOUSE_OWNED, false);
    }

    public static void setMouseOwned(Context context, boolean owned) {
        prefs(context).edit().putBoolean(KEY_MOUSE_OWNED, owned).apply();
    }

    public static int getKeyCode(Context context) {
        return prefs(context).getInt(KEY_CODE, -1);
    }

    public static String getKeyName(Context context) {
        String name = prefs(context).getString(KEY_NAME, "");
        return name == null ? "" : name;
    }

    public static String getKeyDevice(Context context) {
        String name = prefs(context).getString(KEY_DEVICE, "");
        return name == null ? "" : name;
    }

    public static void assignKey(Context context, int code, String name, String device) {
        prefs(context).edit()
                .putInt(KEY_CODE, code)
                .putString(KEY_NAME, name)
                .putString(KEY_DEVICE, device == null ? "" : device)
                .putBoolean(KEY_CAPTURING, false)
                .apply();
        notifyChanged();
    }

    public static void clearKey(Context context) {
        prefs(context).edit()
                .remove(KEY_CODE)
                .remove(KEY_NAME)
                .remove(KEY_DEVICE)
                .apply();
        notifyChanged();
    }

    public static boolean isConsume(Context context) {
        return prefs(context).getBoolean(KEY_CONSUME, true);
    }

    public static void setConsume(Context context, boolean consume) {
        prefs(context).edit().putBoolean(KEY_CONSUME, consume).apply();
    }

    public static float getScale(Context context) {
        float scale = prefs(context).getFloat(KEY_SCALE, 2.0f);
        if (scale < 1.5f) {
            scale = 1.5f;
        } else if (scale > 7.0f) {
            scale = 7.0f;
        }
        return scale;
    }

    public static void setScale(Context context, float scale) {
        setScaleSilently(context, scale);
        notifyChanged();
    }

    /** Same as {@link #setScale} but without waking the UI (used while zooming). */
    public static void setScaleSilently(Context context, float scale) {
        if (scale < 1.5f) {
            scale = 1.5f;
        } else if (scale > 7.0f) {
            scale = 7.0f;
        }
        prefs(context).edit().putFloat(KEY_SCALE, scale).apply();
    }

    public static boolean isNavEnabled(Context context) {
        return prefs(context).getBoolean(KEY_NAV, true);
    }

    public static void setNavEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_NAV, enabled).apply();
        notifyChanged();
    }

    public static float getPanStep(Context context) {
        float value = prefs(context).getFloat(KEY_PAN_STEP, 0.12f);
        if (value < 0.03f) {
            value = 0.03f;
        } else if (value > 0.40f) {
            value = 0.40f;
        }
        return value;
    }

    public static void setPanStep(Context context, float value) {
        if (value < 0.03f) {
            value = 0.03f;
        } else if (value > 0.40f) {
            value = 0.40f;
        }
        prefs(context).edit().putFloat(KEY_PAN_STEP, value).apply();
    }

    /** Finger trackpad in the corner, shown while the screen is magnified. */
    public static boolean isPanPadEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PAN_PAD, true);
    }

    public static void setPanPadEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PAN_PAD, enabled).apply();
        notifyChanged();
    }

    /**
     * Follow the system mouse cursor while magnified. The cursor itself is
     * moved by the Retroid "Mouse mode", which the user toggles in the
     * handheld settings; this preference only decides whether zoom follows it.
     */
    public static boolean isFollowCursor(Context context) {
        return prefs(context).getBoolean(KEY_FOLLOW_CURSOR, true);
    }

    public static void setFollowCursor(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_FOLLOW_CURSOR, enabled).apply();
        notifyChanged();
    }

    /**
     * Switch the firmware "Mouse mode" on while the screen is magnified and
     * off again afterwards. This makes M2 behave like a single "pointer"
     * toggle; the mapping service itself is private to the firmware, but the
     * switch it reacts to is a plain system setting.
     */
    public static boolean isAutoMouseMode(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_MOUSE, true);
    }

    public static void setAutoMouseMode(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_MOUSE, enabled).apply();
        notifyChanged();
    }

    /**
     * Cursor sensitivity used while the firmware "Mouse mode" is on. The
     * Retroid mapping service keeps this value inside its own mapping config,
     * so it is only pushed when the user has actually changed the slider.
     */
    public static float getCursorSensitivity(Context context) {
        return prefs(context).getFloat(KEY_CURSOR_SENSITIVITY, 1.0f);
    }

    public static boolean isCursorSensitivityCustom(Context context) {
        return prefs(context).getBoolean(KEY_CURSOR_SENSITIVITY_CUSTOM, false);
    }

    public static void setCursorSensitivity(Context context, float value) {
        prefs(context).edit()
                .putFloat(KEY_CURSOR_SENSITIVITY, value)
                .putBoolean(KEY_CURSOR_SENSITIVITY_CUSTOM, true)
                .apply();
        notifyChanged();
    }

    /** Soft haptic tick while the pointer moves. */
    public static boolean isCursorVibrate(Context context) {
        return prefs(context).getBoolean(KEY_CURSOR_VIBRATE, false);
    }

    public static void setCursorVibrate(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_CURSOR_VIBRATE, enabled).apply();
        notifyChanged();
    }

    /**
     * Which physical stick moves the pointer: false = left (firmware default),
     * true = right. The flag {@link #isStickCustom} tells whether the user has
     * touched the switch, so an untouched app never overrides the Retroid
     * "Cursor settings" screen.
     */
    public static boolean isStickRight(Context context) {
        return prefs(context).getBoolean(KEY_STICK_RIGHT, false);
    }

    public static boolean isStickCustom(Context context) {
        return prefs(context).getBoolean(KEY_STICK_CUSTOM, false);
    }

    public static void setStickRight(Context context, boolean right) {
        prefs(context).edit()
                .putBoolean(KEY_STICK_RIGHT, right)
                .putBoolean(KEY_STICK_CUSTOM, true)
                .apply();
        notifyChanged();
    }

    /** 10..100 percent of the vibration amplitude. */
    public static int getVibrateStrength(Context context) {
        return prefs(context).getInt(KEY_VIBRATE_STRENGTH, 35);
    }

    public static void setVibrateStrength(Context context, int percent) {
        prefs(context).edit()
                .putInt(KEY_VIBRATE_STRENGTH, Math.max(10, Math.min(100, percent)))
                .apply();
        notifyChanged();
    }

    /** Check GitHub Releases once when the app starts. */
    public static boolean isAutoUpdateCheck(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_UPDATE, true);
    }

    public static void setAutoUpdateCheck(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply();
        notifyChanged();
    }

    public static long getLastUpdateCheck(Context context) {
        return prefs(context).getLong(KEY_LAST_UPDATE_CHECK, 0L);
    }

    public static void setLastUpdateCheck(Context context, long time) {
        prefs(context).edit().putLong(KEY_LAST_UPDATE_CHECK, time).apply();
    }

    /** Last magnified area (screen centre) to restore when zoom is re-enabled. */
    public static float getSavedCenterX(Context context) {
        return prefs(context).getFloat(KEY_CENTER_X, -1f);
    }

    public static float getSavedCenterY(Context context) {
        return prefs(context).getFloat(KEY_CENTER_Y, -1f);
    }

    public static void setSavedCenter(Context context, float x, float y) {
        prefs(context).edit()
                .putFloat(KEY_CENTER_X, x)
                .putFloat(KEY_CENTER_Y, y)
                .apply();
    }

    public static String getLastEvent(Context context) {
        String value = prefs(context).getString(KEY_LAST_EVENT, "");
        return value == null ? "" : value;
    }

    public static void setLastEvent(Context context, String value) {
        prefs(context).edit().putString(KEY_LAST_EVENT, value).apply();
        notifyChanged();
    }
}
