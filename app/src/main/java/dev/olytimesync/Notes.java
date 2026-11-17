package dev.olytimesync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** The two notifications: the quiet "waiting for the camera" one and the result of a sync. */
final class Notes {
    static final int WATCH_ID = 1;
    static final int RESULT_ID = 2;
    private static final String WATCH = "watch";
    private static final String RESULT = "result";

    private Notes() {
    }

    /** Notification channels exist from Android 8; older versions use the per-notification priority instead. */
    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(WATCH, c.getString(R.string.channel_watch),
                NotificationManager.IMPORTANCE_MIN));
        nm.createNotificationChannel(new NotificationChannel(RESULT, c.getString(R.string.channel_result),
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    @SuppressWarnings("deprecation")
    private static Notification.Builder builder(Context c, String channel, int priority) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(c, channel);
        return new Notification.Builder(c).setPriority(priority);
    }

    static Notification watching(Context c) {
        return builder(c, WATCH, Notification.PRIORITY_MIN)
                .setSmallIcon(R.drawable.ic_stat_clock)
                .setContentTitle(c.getString(R.string.notif_waiting))
                .setContentText(Prefs.status(c))
                .setContentIntent(open(c))
                .setOngoing(true)
                .build();
    }

    static void result(Context c, String text) {
        channels(c);
        Notification n = builder(c, RESULT, Notification.PRIORITY_DEFAULT)
                .setSmallIcon(R.drawable.ic_stat_clock)
                .setContentTitle(c.getString(R.string.app_name))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open(c))
                .setAutoCancel(true)
                .build();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.notify(RESULT_ID, n);
        if (WatchService.running) nm.notify(WATCH_ID, watching(c));
    }

    private static PendingIntent open(Context c) {
        return PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
