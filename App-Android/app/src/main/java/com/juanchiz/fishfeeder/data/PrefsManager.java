package com.juanchiz.fishfeeder.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.juanchiz.fishfeeder.model.ThresholdsConfig;

/** Guarda la IP del dispensador y las preferencias de notificación. Sin backend, todo es local. */
public class PrefsManager {

    private static final String PREFS_NAME = "fishfeeder_prefs";
    private static final String KEY_DEVICE_IP = "device_ip";
    private static final String KEY_NOTIFY_EMPTY = "notify_empty";
    private static final String KEY_NOTIFY_HUMIDITY = "notify_humidity";
    private static final String KEY_NOTIFY_TEMPERATURE = "notify_temperature";
    private static final String KEY_NOTIFY_DAILY_SUMMARY = "notify_daily_summary";
    private static final String KEY_NOTIFY_JAM = "notify_jam";
    private static final String KEY_LAST_SYNC = "last_history_sync";
    private static final String KEY_THR_HUM_MAX = "thr_hum_max";
    private static final String KEY_THR_TEMP_MIN = "thr_temp_min";
    private static final String KEY_THR_TEMP_MAX = "thr_temp_max";

    private final SharedPreferences prefs;

    public PrefsManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getDeviceIp() {
        return prefs.getString(KEY_DEVICE_IP, null);
    }

    public void setDeviceIp(String ip) {
        prefs.edit().putString(KEY_DEVICE_IP, ip).apply();
    }

    public boolean isConnected() {
        return getDeviceIp() != null;
    }

    public void disconnect() {
        prefs.edit().remove(KEY_DEVICE_IP).apply();
    }

    public boolean isNotifyEmptyEnabled() {
        return prefs.getBoolean(KEY_NOTIFY_EMPTY, true);
    }

    public void setNotifyEmptyEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NOTIFY_EMPTY, enabled).apply();
    }

    public boolean isNotifyHumidityEnabled() {
        return prefs.getBoolean(KEY_NOTIFY_HUMIDITY, true);
    }

    public void setNotifyHumidityEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NOTIFY_HUMIDITY, enabled).apply();
    }

    public boolean isNotifyTemperatureEnabled() {
        return prefs.getBoolean(KEY_NOTIFY_TEMPERATURE, true);
    }

    public void setNotifyTemperatureEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NOTIFY_TEMPERATURE, enabled).apply();
    }

    public boolean isNotifyJamEnabled() {
        return prefs.getBoolean(KEY_NOTIFY_JAM, true);
    }

    public void setNotifyJamEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NOTIFY_JAM, enabled).apply();
    }

    public String getBaseUrl() {
        String ip = getDeviceIp();
        return ip == null ? null : "http://" + ip + "/";
    }

    public boolean isNotifyDailySummaryEnabled() {
        return prefs.getBoolean(KEY_NOTIFY_DAILY_SUMMARY, true);
    }

    public void setNotifyDailySummaryEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NOTIFY_DAILY_SUMMARY, enabled).apply();
    }

    /** Última sincronización exitosa del historial del ESP32 (ver HistorySync). 0 = nunca. */
    public long getLastSyncMillis() {
        return prefs.getLong(KEY_LAST_SYNC, 0);
    }

    public void setLastSyncMillis(long millis) {
        prefs.edit().putLong(KEY_LAST_SYNC, millis).apply();
    }

    /** Copia local de los umbrales del ESP32, para las líneas de referencia de las gráficas. */
    public void saveThresholds(ThresholdsConfig t) {
        prefs.edit()
                .putFloat(KEY_THR_HUM_MAX, t.humedadMax)
                .putFloat(KEY_THR_TEMP_MIN, t.temperaturaMin)
                .putFloat(KEY_THR_TEMP_MAX, t.temperaturaMax)
                .apply();
    }

    /** NaN si todavía no se han descargado. */
    public float getCachedHumidityMax() {
        return prefs.getFloat(KEY_THR_HUM_MAX, Float.NaN);
    }

    public float getCachedTempMin() {
        return prefs.getFloat(KEY_THR_TEMP_MIN, Float.NaN);
    }

    public float getCachedTempMax() {
        return prefs.getFloat(KEY_THR_TEMP_MAX, Float.NaN);
    }
}
