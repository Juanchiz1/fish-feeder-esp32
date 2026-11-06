package com.juanchiz.fishfeeder.model;

import com.google.gson.annotations.SerializedName;

/** Datos generales del ESP32 (llenarJsonSistema() en el firmware). */
public class SystemDiagnostic {

    @SerializedName("wifi_rssi")
    public int wifiRssi;

    @SerializedName("heap_libre")
    public long heapLibre;

    @SerializedName("loop_max_ms")
    public long loopMaxMs;

    @SerializedName("uptime_s")
    public long uptimeS;

    @SerializedName("proxima_lectura_s")
    public long proximaLecturaS;

    @SerializedName("ciclo_min")
    public int cicloMin = 5;
}
