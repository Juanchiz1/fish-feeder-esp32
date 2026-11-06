package com.juanchiz.fishfeeder.network;

import com.juanchiz.fishfeeder.model.DeviceFeeding;
import com.juanchiz.fishfeeder.model.DhtDiagnostic;
import com.juanchiz.fishfeeder.model.FeederStatus;
import com.juanchiz.fishfeeder.model.HistoryEntry;
import com.juanchiz.fishfeeder.model.LevelDiagnostic;
import com.juanchiz.fishfeeder.model.PauseState;
import com.juanchiz.fishfeeder.model.RtcDiagnostic;
import com.juanchiz.fishfeeder.model.ScheduleItem;
import com.juanchiz.fishfeeder.model.SensorDiagnostics;
import com.juanchiz.fishfeeder.model.ThresholdsConfig;
import com.juanchiz.fishfeeder.model.TimeSyncRequest;
import com.juanchiz.fishfeeder.model.WifiConfig;
import com.juanchiz.fishfeeder.model.WifiConfigRequest;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;

/** Endpoints servidos por setupServidorWeb() en dispensador_peces.ino. */
public interface FeederApiService {

    @GET("api/status")
    Call<FeederStatus> getStatus();

    @POST("api/feed")
    Call<Void> feedNow(@Query("porciones") int porciones);

    @GET("api/schedule")
    Call<List<ScheduleItem>> getSchedule();

    @POST("api/schedule")
    Call<Void> setSchedule(@Body List<ScheduleItem> schedule);

    @GET("api/wifi-config")
    Call<WifiConfig> getWifiConfig();

    @POST("api/wifi-config")
    Call<Void> setWifiConfig(@Body WifiConfigRequest config);

    @POST("api/time")
    Call<Void> syncTime(@Body TimeSyncRequest time);

    @GET("api/pause")
    Call<PauseState> getPauseState();

    @POST("api/pause")
    Call<Void> setPauseState(@Body PauseState state);

    @GET("api/thresholds")
    Call<ThresholdsConfig> getThresholds();

    @POST("api/thresholds")
    Call<Void> setThresholds(@Body ThresholdsConfig thresholds);

    /** Último resultado guardado de los tres sensores. No dispara ninguna medición. */
    @GET("api/diagnostics")
    Call<SensorDiagnostics> getDiagnostics();

    /** Dispara 10 pulsos del sensor de nivel y devuelve el detalle de cada uno (~1 s). */
    @POST("api/diagnostics/nivel")
    Call<LevelDiagnostic> testNivel();

    /** Lee el DHT22 (con un reintento a los 2 s si falla). */
    @POST("api/diagnostics/dht22")
    Call<DhtDiagnostic> testDht22();

    /** Reinicia la comunicación con el DS3231 y lee hora y temperatura interna. */
    @POST("api/diagnostics/rtc")
    Call<RtcDiagnostic> testRtc();

    /** Lecturas del ciclo de 5 min guardadas en el ESP32 con ts > desde (segundos del RTC). */
    @GET("api/history")
    Call<List<HistoryEntry>> getHistory(@Query("desde") long desde);

    /** Alimentaciones registradas en el ESP32 con ts > desde. */
    @GET("api/feedings")
    Call<List<DeviceFeeding>> getFeedings(@Query("desde") long desde);
}
