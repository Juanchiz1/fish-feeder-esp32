package com.juanchiz.fishfeeder.model;

import com.google.gson.annotations.SerializedName;

/**
 * GET /api/diagnostics: último resultado guardado de cada sensor, sin disparar nada.
 * Para probar un sensor puntual se usan POST /api/diagnostics/{nivel|dht22|rtc}, que devuelven
 * directamente el objeto de ese sensor.
 */
public class SensorDiagnostics {

    @SerializedName("nivel")
    public LevelDiagnostic nivel;

    @SerializedName("dht22")
    public DhtDiagnostic dht22;

    @SerializedName("rtc")
    public RtcDiagnostic rtc;

    @SerializedName("sistema")
    public SystemDiagnostic sistema;
}
