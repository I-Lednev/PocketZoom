package com.pocketzoom;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/**
 * The Retroid firmware keeps its "Mouse mode" switch in a plain system
 * setting ({@code global_gamepad_to_mouse_mode}). Normal apps may read it
 * without any permission and write it once the user grants the "Modify
 * system settings" special access, so Pocket Zoom can turn the pointer on
 * together with the zoom even though the mapping service itself is private.
 *
 * <p>Values observed on the Retroid Pocket 6: "1" = on, "0" = off.</p>
 */
final class MouseMode {
    private static final String TAG = "PocketZoom";
    private static final String KEY = "global_gamepad_to_mouse_mode";

    private MouseMode() {
    }

    /** True when this firmware exposes the mouse-mode switch. */
    static boolean isSupported(Context context) {
        try {
            return Settings.System.getString(context.getContentResolver(), KEY) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isOn(Context context) {
        try {
            String value = Settings.System.getString(context.getContentResolver(), KEY);
            return "1".equals(value) || "true".equalsIgnoreCase(value);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether the special "Modify system settings" access was granted. */
    static boolean canWrite(Context context) {
        try {
            return Settings.System.canWrite(context);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Switches the system pointer on or off. Returns true on success. */
    static boolean set(Context context, boolean on) {
        try {
            boolean ok = Settings.System.putInt(context.getContentResolver(), KEY, on ? 1 : 0);
            if (!ok) {
                Log.w(TAG, "mouse mode write rejected");
            }
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "mouse mode write failed", t);
            return false;
        }
    }
}
