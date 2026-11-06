package com.juanchiz.fishfeeder.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** Resultado de una medición del sensor de nivel RCWL-1601 (llenarJsonNivel() en el firmware). */
public class LevelDiagnostic {

    // Valores de Muestra.st (mismos que ST_* en el firmware).
    public static final int ST_VALIDA = 0;
    public static final int ST_SIN_ECO = 1;
    public static final int ST_MAS_ALLA = 2;
    public static final int ST_MUY_CERCA = 3;
    public static final int ST_SIN_RESPUESTA = 255;

    @SerializedName("sensor")
    public String sensor;

    @SerializedName("estado")
    public String estado = FeederStatus.NIVEL_SIN_MEDIR;

    /** Mediana de los ecos válidos (dentro de la tolva). */
    @SerializedName("distancia_mm")
    public int distanciaMm;

    /** Mediana de todo lo que tuvo eco, aunque esté más allá del fondo (para calibrar la tolva vacía). */
    @SerializedName("distancia_cruda_mm")
    public int distanciaCrudaMm;

    @SerializedName("nivel_pct")
    public int nivelPct = -1;

    @SerializedName("validas")
    public int validas;

    @SerializedName("sin_eco")
    public int sinEco;

    @SerializedName("muy_cerca")
    public int muyCerca;

    @SerializedName("errores")
    public int errores;

    /** Máximo - mínimo de los ecos válidos: si es alto, hay rebotes en las paredes. */
    @SerializedName("dispersion_mm")
    public int dispersionMm;

    @SerializedName("temperatura_usada")
    public float temperaturaUsada;

    @SerializedName("velocidad_sonido")
    public float velocidadSonido;

    @SerializedName("duracion_ms")
    public long duracionMs;

    @SerializedName("medido_hace_s")
    public long medidoHaceS = -1;

    @SerializedName("profundidad_tolva_mm")
    public int profundidadTolvaMm;

    @SerializedName("zona_ciega_mm")
    public int zonaCiegaMm;

    @SerializedName("sesiones_fallidas_seguidas")
    public int sesionesFallidasSeguidas;

    @SerializedName("mensaje")
    public String mensaje;

    @SerializedName("muestras")
    public List<Muestra> muestras;

    public static class Muestra {
        @SerializedName("mm")
        public int mm;

        @SerializedName("st")
        public int st;
    }

    public boolean funciona() {
        return FeederStatus.NIVEL_OK.equals(estado) || FeederStatus.NIVEL_SIN_ECO.equals(estado)
                || FeederStatus.NIVEL_MUY_CERCA.equals(estado);
    }
}
