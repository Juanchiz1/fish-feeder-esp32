package com.juanchiz.fishfeeder.model;

import com.google.gson.annotations.SerializedName;

/** Estado del DHT22 (llenarJsonDht() en el firmware). */
public class DhtDiagnostic {

    @SerializedName("ok")
    public boolean ok;

    @SerializedName("humedad")
    public float humedad;

    @SerializedName("temperatura")
    public float temperatura;

    @SerializedName("intentos")
    public int intentos;

    @SerializedName("fallos_seguidos")
    public int fallosSeguidos;

    /** Segundos desde la última lectura buena (-1 = nunca respondió). */
    @SerializedName("ultima_lectura_hace_s")
    public long ultimaLecturaHaceS = -1;

    @SerializedName("mensaje")
    public String mensaje;
}
