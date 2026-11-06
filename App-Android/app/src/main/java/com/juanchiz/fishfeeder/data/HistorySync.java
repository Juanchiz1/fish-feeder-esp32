package com.juanchiz.fishfeeder.data;

import android.content.Context;

import androidx.annotation.WorkerThread;

import com.juanchiz.fishfeeder.model.DeviceFeeding;
import com.juanchiz.fishfeeder.model.HistoryEntry;
import com.juanchiz.fishfeeder.model.ThresholdsConfig;
import com.juanchiz.fishfeeder.network.FeederApiService;
import com.juanchiz.fishfeeder.network.RetrofitClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import retrofit2.Response;

/**
 * Descarga el historial que el ESP32 guarda en RAM (lecturas cada 5 min y alimentaciones) y lo
 * pasa a Room. El ESP32 guarda 24 h, así que basta con sincronizar al menos una vez al día;
 * PollingWorker lo hace cada 15 min y Estadísticas/Inicio al abrirse.
 *
 * Llamarlo dos veces no duplica nada: deviceTs es clave única y las inserciones ignoran repetidos.
 */
public final class HistorySync {

    public enum Result { OK, NO_CONNECTED, UNSUPPORTED_FIRMWARE, ERROR }

    /** Se piden unos minutos hacia atrás por si el RTC se corrigió; los repetidos se ignoran. */
    private static final long SOLAPE_S = 600;
    private static final long RETENCION_DIAS = 90;
    private static final Object LOCK = new Object();

    private HistorySync() {
    }

    @WorkerThread
    public static Result syncBlocking(Context context) {
        PrefsManager prefs = new PrefsManager(context);
        String baseUrl = prefs.getBaseUrl();
        if (baseUrl == null) return Result.NO_CONNECTED;

        synchronized (LOCK) {
            try {
                FeederApiService api = RetrofitClient.getApi(baseUrl);
                AppDatabase db = AppDatabase.getInstance(context);

                Long maxLectura = db.sensorReadingDao().maxDeviceTs();
                Response<List<HistoryEntry>> rh = api.getHistory(desde(maxLectura)).execute();
                if (rh.code() == 404) return Result.UNSUPPORTED_FIRMWARE;
                if (!rh.isSuccessful() || rh.body() == null) return Result.ERROR;
                db.sensorReadingDao().insertAll(toReadings(rh.body(), prefs));

                Long maxAlim = db.feedingEventDao().maxDeviceTs();
                Response<List<DeviceFeeding>> rf = api.getFeedings(desde(maxAlim)).execute();
                if (rf.isSuccessful() && rf.body() != null) {
                    db.feedingEventDao().insertAll(toFeedings(rf.body()));
                }

                // Umbrales en caché para dibujar las líneas de referencia en las gráficas.
                Response<ThresholdsConfig> rt = api.getThresholds().execute();
                if (rt.isSuccessful() && rt.body() != null) prefs.saveThresholds(rt.body());

                long limite = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENCION_DIAS);
                db.sensorReadingDao().deleteOlderThan(limite);
                db.feedingEventDao().deleteOlderThan(limite);

                prefs.setLastSyncMillis(System.currentTimeMillis());
                return Result.OK;
            } catch (Exception e) {
                return Result.ERROR;
            }
        }
    }

    private static long desde(Long maxDeviceTs) {
        return maxDeviceTs == null ? 0 : Math.max(0, maxDeviceTs - SOLAPE_S);
    }

    private static List<SensorReading> toReadings(List<HistoryEntry> entries, PrefsManager prefs) {
        float humMax = prefs.getCachedHumidityMax();
        List<SensorReading> out = new ArrayList<>(entries.size());
        for (HistoryEntry e : entries) {
            boolean dhtOk = e.humedad != null && e.temperatura != null;
            double h = dhtOk ? e.humedad : 0;
            double t = dhtOk ? e.temperatura : 0;
            boolean vacia = e.estado == SensorReading.NIVEL_SIN_ECO
                    || (e.estado == SensorReading.NIVEL_OK && e.nivel == 0);
            boolean humedadAlta = dhtOk && !Float.isNaN(humMax) && h >= humMax;

            SensorReading r = new SensorReading(DeviceTime.toMillis(e.ts), h, t, e.nivel, vacia, humedadAlta);
            r.deviceTs = e.ts;
            r.nivelEstado = e.estado;
            r.dhtOk = dhtOk;
            out.add(r);
        }
        return out;
    }

    private static List<FeedingEvent> toFeedings(List<DeviceFeeding> feedings) {
        List<FeedingEvent> out = new ArrayList<>(feedings.size());
        for (DeviceFeeding f : feedings) {
            FeedingEvent ev = new FeedingEvent(DeviceTime.toMillis(f.ts), Math.max(1, f.porciones),
                    f.origen == DeviceFeeding.ORIGEN_MANUAL);
            ev.deviceTs = f.ts;
            ev.nivelAntes = f.nivelAntes;
            ev.nivelDespues = f.nivelDespues;
            out.add(ev);
        }
        return out;
    }
}
