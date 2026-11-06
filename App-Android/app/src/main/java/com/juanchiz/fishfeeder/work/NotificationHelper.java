package com.juanchiz.fishfeeder.work;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.juanchiz.fishfeeder.R;

/** Crea el canal de notificaciones y publica avisos locales; lo comparten PollingWorker
 *  (alertas de tolva vacía / humedad alta) y DailySummaryWorker (resumen diario). */
class NotificationHelper {

    static final String CHANNEL_ID = "feeder_alerts";

    static void createChannelIfNeeded(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.notif_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription(context.getString(R.string.notif_channel_desc));
                manager.createNotificationChannel(channel);
            }
        }
    }

    static void notify(Context context, int id, String title, String message) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return; // el usuario no concedió el permiso de notificaciones; no podemos avisar
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_fish)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
        NotificationManagerCompat.from(context).notify(id, builder.build());
    }

    static void notify(Context context, int id, String message) {
        notify(context, id, context.getString(R.string.app_name), message);
    }
}
