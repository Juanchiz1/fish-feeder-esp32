package com.juanchiz.fishfeeder.work;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.juanchiz.fishfeeder.R;
import com.juanchiz.fishfeeder.data.AppDatabase;
import com.juanchiz.fishfeeder.data.PrefsManager;
import com.juanchiz.fishfeeder.data.SensorReading;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Una vez al día (alrededor de las {@link #HORA_RESUMEN}:00), arma un resumen del día con lo
 * que ya está guardado en Room —alimentaciones de hoy, humedad promedio, nivel actual— y lo
 * publica como notificación. No consulta el ESP32 directamente: reutiliza lo que PollingWorker
 * ya fue guardando durante el día, así que no depende de que el dispensador esté disponible
 * justo a esa hora.
 */
public class DailySummaryWorker extends Worker {

    private static final String WORK_NAME = "fish_feeder_daily_summary";
    private static final int NOTIF_ID_DAILY_SUMMARY = 3;
    private static final int HORA_RESUMEN = 20; // 8:00 p.m.

    public DailySummaryWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                        DailySummaryWorker.class, 24, TimeUnit.HOURS)
                .setInitialDelay(millisHastaProximoResumen(), TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    private static long millisHastaProximoResumen() {
        Calendar objetivo = Calendar.getInstance();
        objetivo.set(Calendar.HOUR_OF_DAY, HORA_RESUMEN);
        objetivo.set(Calendar.MINUTE, 0);
        objetivo.set(Calendar.SECOND, 0);
        objetivo.set(Calendar.MILLISECOND, 0);

        long ahora = System.currentTimeMillis();
        if (objetivo.getTimeInMillis() <= ahora) {
            objetivo.add(Calendar.DAY_OF_YEAR, 1);
        }
        return objetivo.getTimeInMillis() - ahora;
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        PrefsManager prefs = new PrefsManager(context);

        if (!prefs.isConnected() || !prefs.isNotifyDailySummaryEnabled()) {
            return Result.success();
        }

        AppDatabase db = AppDatabase.getInstance(context);
        long inicioDelDia = startOfTodayMillis();

        int alimentacionesHoy = db.feedingEventDao().countSince(inicioDelDia);
        Double humedadPromedio = db.sensorReadingDao().avgHumedadSince(inicioDelDia);
        List<SensorReading> ultima = db.sensorReadingDao().getRecent(1);

        // Sin ninguna lectura de hoy, no hay nada útil que resumir todavía.
        if (humedadPromedio == null && ultima.isEmpty()) {
            return Result.success();
        }

        int nivelActual = ultima.isEmpty() ? -1 : ultima.get(0).nivelTolvaPct;
        String humedadTexto = humedadPromedio == null
                ? "--" : String.format(Locale.getDefault(), "%.0f%%", humedadPromedio);
        String nivelTexto = nivelActual < 0 ? "--" : nivelActual + "%";

        String mensaje = context.getString(R.string.notif_daily_summary_body,
                alimentacionesHoy, humedadTexto, nivelTexto);

        NotificationHelper.createChannelIfNeeded(context);
        NotificationHelper.notify(context, NOTIF_ID_DAILY_SUMMARY,
                context.getString(R.string.notif_daily_summary_title), mensaje);

        return Result.success();
    }

    private long startOfTodayMillis() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
