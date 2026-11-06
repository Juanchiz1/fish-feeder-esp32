package com.juanchiz.fishfeeder.model;

import com.google.gson.annotations.SerializedName;

/** Una lectura del ciclo de 5 minutos guardada en el ESP32 (GET /api/history). */
public class HistoryEntry {

    /** Hora del RTC en segundos (hora local guardada como si fuera UTC, ver DeviceTime). */
    @SerializedName("ts")
    public long ts;

    /** Humedad %; null si el DHT22 no tenía dato en ese momento. */
    @SerializedName("h")
    public Double humedad;

    @SerializedName("t")
    public Double temperatura;

    /** Último nivel conocido (% volumen), -1 si nunca se midió. */
    @SerializedName("n")
    public int nivel = -1;

    /** Estado del sensor de nivel: 0 ok, 1 sin eco, 2 muy cerca, 3 error, 4 sin sensor, 5 sin medir. */
    @SerializedName("e")
    public int estado = 5;
}
