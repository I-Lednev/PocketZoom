package com.pocketzoom;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;

/** Ongoing notification that shows the current state and offers quick actions. */
public final class Notifications {
    private static final String CHANNEL_ID = "pocketzoom_status";
    private static final int NOTIFICATION_ID = 101;

    private Notifications() {
    }

    public static void update(Context context) {
        if (!AppState.isActive(context)) {
            cancel(context);
            return;
        }

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);

        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        String keyName = AppState.getKeyName(context);
        String text = keyName.isEmpty() ? context.getString(R.string.key_none) : keyName;

        PendingIntent openIntent = PendingIntent.getActivity(context, 1,
                new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggleIntent = PendingIntent.getBroadcast(context, 2,
                new Intent(context, ActionReceiver.class).setAction(ActionReceiver.ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent = PendingIntent.getBroadcast(context, 3,
                new Intent(context, ActionReceiver.class).setAction(ActionReceiver.ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Icon actionIcon = Icon.createWithResource(context, R.drawable.ic_stat_zoom);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_zoom)
                .setContentTitle(context.getString(R.string.notif_title))
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(openIntent)
                .addAction(new Notification.Action.Builder(actionIcon,
                        context.getString(R.string.notif_action_toggle), toggleIntent).build())
                .addAction(new Notification.Action.Builder(actionIcon,
                        context.getString(R.string.notif_action_stop), stopIntent).build())
                .build();
        manager.notify(NOTIFICATION_ID, notification);
    }

    public static void cancel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }
}
