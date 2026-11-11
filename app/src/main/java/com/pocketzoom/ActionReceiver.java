package com.pocketzoom;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the action buttons on the ongoing notification. */
public class ActionReceiver extends BroadcastReceiver {
    public static final String ACTION_TOGGLE = "com.pocketzoom.action.TOGGLE";
    public static final String ACTION_STOP = "com.pocketzoom.action.STOP";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        ZoomAccessibilityService service = ZoomAccessibilityService.getInstance();

        if (ACTION_STOP.equals(action)) {
            AppState.setActive(context, false);
            AppState.setCapturing(context, false);
            if (service != null) {
                service.setMagnified(false);
            }
            Notifications.cancel(context);
        } else if (ACTION_TOGGLE.equals(action)) {
            if (service != null) {
                service.toggleMagnification();
            }
        }
    }
}
