package com.juanchiz.fishfeeder.model;

import com.google.gson.annotations.SerializedName;

/** Estado del DS3231 (llenarJsonRtc() en el firmware). */
public class RtcDiagnostic {

    @SerializedName("ok")
    public boolean ok;

    @SerializedName("perdio_energia")
    public boolean perdioEnergia;

    /** Sensor de temperatura interno del DS3231 (°C). */
    @SerializedName("temperatura_interna")
    public float temperaturaInterna;

    @SerializedName("revisado_hace_s")
    public long revisadoHaceS = -1;

    @SerializedName("fecha")
    public String fecha;

    @SerializedName("hora")
    public String hora;

    @SerializedName("mensaje")
    public String mensaje;
}
