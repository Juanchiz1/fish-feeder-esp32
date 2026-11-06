package com.juanchiz.fishfeeder.work;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.juanchiz.fishfeeder.R;
import com.juanchiz.fishfeeder.data.AppDatabase;
import com.juanchiz.fishfeeder.data.HistorySync;
import com.juanchiz.fishfeeder.data.PrefsManager;
import com.juanchiz.fishfeeder.data.SensorReading;
import com.juanchiz.fishfeeder.model.FeederStatus;
import com.juanchiz.fishfeeder.network.FeederApiService;
import com.juanchiz.fishfeeder.network.RetrofitClient;

import java.util.concurrent.TimeUnit;

import retrofit2.Response;

/**
 * Corre cada 15 min en segundo plano (WorkManager), aunque la app esté cerrada:
 *  1. Descarga el historial del ESP32 (lecturas cada 5 min + alimentaciones) a Room.
 *  2. Consulta /api/status y publica o quita las notificaciones de alerta.
 */
public class PollingWorker extends Worker {

    private static final String WORK_NAME = "fish_feeder_polling";
    private static final int NOTIF_ID_EMPTY = 1;
    private static final int NOTIF_ID_HUMIDITY = 2;
    private static final int NOTIF_ID_TEMPERATURE = 4;
    private static final int NOTIF_ID_JAM = 5;
    private static final int NOTIF_ID_LEVEL_SENSOR = 6;

    public PollingWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                        PollingWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        PrefsManager prefs = new PrefsManager(context);
        String baseUrl = prefs.getBaseUrl();
        if (baseUrl == null) return Result.success();

        HistorySync.Result sync = HistorySync.syncBlocking(context);

        try {
            FeederApiService api = RetrofitClient.getApi(baseUrl);
            Response<FeederStatus> response = api.getStatus().execute();
            if (!response.isSuccessful() || response.body() == null) return Result.retry();
            FeederStatus status = response.body();

            // Firmware viejo sin /api/history: se guarda la foto actual como antes.
            if (sync == HistorySync.Result.UNSUPPORTED_FIRMWARE) {
                AppDatabase.getInstance(context).sensorReadingDao().insert(new SensorReading(
                        System.currentTimeMillis(), status.humedad, status.temperatura,
                        status.nivelTolvaPct, status.tolvaVacia, status.humedadAlta));
            }

            actualizarNotificaciones(context, prefs, status);
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }

    private void actualizarNotificaciones(Context context, PrefsManager prefs, FeederStatus status) {
        NotificationHelper.createChannelIfNeeded(context);
        NotificationManagerCompat nm = NotificationManagerCompat.from(context);

        if (status.tolvaVacia && prefs.isNotifyEmptyEnabled()) {
            NotificationHelper.notify(context, NOTIF_ID_EMPTY, context.getString(R.string.alert_empty));
        } else {
            nm.cancel(NOTIF_ID_EMPTY);
        }

        // Sensor de nivel con fallas en dos mediciones seguidas: se avisa con la misma
        // preferencia de "tolva vacía", porque sin sensor no hay forma de saber si se vació.
        boolean sensorNivelFalla = FeederStatus.NIVEL_ERROR.equals(status.nivelEstado)
                || FeederStatus.NIVEL_SIN_SENSOR.equals(status.nivelEstado);
        if (sensorNivelFalla && prefs.isNotifyEmptyEnabled()) {
            NotificationHelper.notify(context, NOTIF_ID_LEVEL_SENSOR, context.getString(R.string.alert_level_sensor));
        } else {
            nm.cancel(NOTIF_ID_LEVEL_SENSOR);
        }

        if (status.humedadAlta && prefs.isNotifyHumidityEnabled()) {
            NotificationHelper.notify(context, NOTIF_ID_HUMIDITY, context.getString(R.string.alert_humidity));
        } else {
            nm.cancel(NOTIF_ID_HUMIDITY);
        }

        boolean temperaturaAnormal = status.temperaturaAlta || status.temperaturaBaja;
        if (temperaturaAnormal && prefs.isNotifyTemperatureEnabled()) {
            String mensaje = context.getString(status.temperaturaAlta ? R.string.alert_temp_high : R.string.alert_temp_low);
            NotificationHelper.notify(context, NOTIF_ID_TEMPERATURE, mensaje);
        } else {
            nm.cancel(NOTIF_ID_TEMPERATURE);
        }

        if (status.posibleAtasco && prefs.isNotifyJamEnabled()) {
            NotificationHelper.notify(context, NOTIF_ID_JAM, context.getString(R.string.alert_jam));
        } else {
            nm.cancel(NOTIF_ID_JAM);
        }
    }
}
