package com.juanchiz.fishfeeder.model;

import com.google.gson.annotations.SerializedName;

/**
 * Refleja el JSON de GET /api/status (handleStatus() en el firmware).
 */
public class FeederStatus {

    // Estados del sensor de nivel (campo nivel_estado).
    public static final String NIVEL_OK = "ok";
    public static final String NIVEL_SIN_ECO = "sin_eco";
    public static final String NIVEL_MUY_CERCA = "muy_cerca";
    public static final String NIVEL_ERROR = "error";
    public static final String NIVEL_SIN_SENSOR = "sin_sensor";
    public static final String NIVEL_SIN_MEDIR = "sin_medir";

    @SerializedName("humedad")
    public double humedad;

    @SerializedName("temperatura")
    public double temperatura;

    /** false si el DHT22 no ha respondido: humedad/temperatura no son confiables. */
    @SerializedName("dht_ok")
    public boolean dhtOk = true;

    /** Segundos desde la última lectura buena del DHT22 (-1 = nunca). */
    @SerializedName("dht_hace_s")
    public long dhtHaceS = -1;

    @SerializedName("distancia_mm")
    public int distanciaMm;

    /** Último nivel conocido en % de volumen; -1 si nunca se ha medido. */
    @SerializedName("nivel_tolva_pct")
    public int nivelTolvaPct = -1;

    @SerializedName("nivel_estado")
    public String nivelEstado = NIVEL_SIN_MEDIR;

    /** Segundos desde la última medición del sensor de nivel (-1 = nunca). Solo mide al alimentar. */
    @SerializedName("nivel_medido_hace_s")
    public long nivelMedidoHaceS = -1;

    @SerializedName("sensor_nivel_ok")
    public boolean sensorNivelOk;

    @SerializedName("tolva_vacia")
    public boolean tolvaVacia;

    @SerializedName("tolva_baja")
    public boolean tolvaBaja;

    @SerializedName("humedad_alta")
    public boolean humedadAlta;

    @SerializedName("temperatura_alta")
    public boolean temperaturaAlta;

    @SerializedName("temperatura_baja")
    public boolean temperaturaBaja;

    @SerializedName("posible_atasco")
    public boolean posibleAtasco;

    @SerializedName("ultima_alimentacion")
    public String ultimaAlimentacion;

    @SerializedName("ultima_porciones")
    public int ultimaPorciones;

    @SerializedName("horarios_pausados")
    public boolean horariosPausados;

    @SerializedName("rtc_ok")
    public boolean rtcOk = true;

    @SerializedName("proxima_lectura_s")
    public long proximaLecturaS;

    @SerializedName("ciclo_min")
    public int cicloMin = 5;

    @SerializedName("hora_actual")
    public String horaActual;

    @SerializedName("fecha_actual")
    public String fechaActual;

    @SerializedName("modo_ap")
    public boolean modoAp;

    @SerializedName("wifi_ssid")
    public String wifiSsid;

    @SerializedName("wifi_rssi")
    public int wifiRssi;

    /** true si el nivel reportado corresponde a una medición válida (vacía, normal o llena). */
    public boolean nivelConfiable() {
        return NIVEL_OK.equals(nivelEstado) || NIVEL_SIN_ECO.equals(nivelEstado)
                || NIVEL_MUY_CERCA.equals(nivelEstado);
    }
}
