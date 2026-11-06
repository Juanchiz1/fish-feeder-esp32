package com.juanchiz.fishfeeder.model;

import com.google.gson.annotations.SerializedName;

/** Cuerpo de GET/POST /api/thresholds: valores de calibración/alerta configurables desde la app. */
public class ThresholdsConfig {

    @SerializedName("humedad_max")
    public float humedadMax;

    @SerializedName("temperatura_min")
    public float temperaturaMin;

    @SerializedName("temperatura_max")
    public float temperaturaMax;

    @SerializedName("tolva_llena_mm")
    public int tolvaLlenaMm;

    @SerializedName("tolva_vacia_mm")
    public int tolvaVaciaMm;

    @SerializedName("cambio_minimo_mm")
    public float cambioMinimoMm;

    public ThresholdsConfig() {
    }

    public ThresholdsConfig(float humedadMax, float temperaturaMin, float temperaturaMax,
                             int tolvaLlenaMm, int tolvaVaciaMm, float cambioMinimoMm) {
        this.humedadMax = humedadMax;
        this.temperaturaMin = temperaturaMin;
        this.temperaturaMax = temperaturaMax;
        this.tolvaLlenaMm = tolvaLlenaMm;
        this.tolvaVaciaMm = tolvaVaciaMm;
        this.cambioMinimoMm = cambioMinimoMm;
    }
}
