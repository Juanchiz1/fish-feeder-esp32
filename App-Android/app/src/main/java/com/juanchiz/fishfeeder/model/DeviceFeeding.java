package com.juanchiz.fishfeeder.model;

import com.google.gson.annotations.SerializedName;

/** Una alimentación registrada por el ESP32 (GET /api/feedings). */
public class DeviceFeeding {

    public static final int ORIGEN_PROGRAMADA = 0;
    public static final int ORIGEN_MANUAL = 1;

    @SerializedName("ts")
    public long ts;

    @SerializedName("p")
    public int porciones;

    @SerializedName("o")
    public int origen;

    /** Nivel antes / después en % (-1 = desconocido). */
    @SerializedName("na")
    public int nivelAntes = -1;

    @SerializedName("nd")
    public int nivelDespues = -1;

    @SerializedName("e")
    public int estado;
}
