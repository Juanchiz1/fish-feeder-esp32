package com.juanchiz.fishfeeder.data;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Una lectura histórica guardada en el celular. Desde la versión 2 de la base de datos sale del
 * historial del ESP32 (GET /api/history, una cada 5 min) y {@link #deviceTs} evita duplicados.
 * Las lecturas viejas (versión 1) venían de /api/status y tienen deviceTs = null.
 */
@Entity(tableName = "sensor_readings",
        indices = {@Index(value = "deviceTs", unique = true)})
public class SensorReading {

    // Valores de nivelEstado (mismos códigos que EstadoNivel en el firmware).
    public static final int NIVEL_OK = 0;
    public static final int NIVEL_SIN_ECO = 1;
    public static final int NIVEL_MUY_CERCA = 2;
    public static final int NIVEL_ERROR = 3;
    public static final int NIVEL_SIN_SENSOR = 4;
    public static final int NIVEL_SIN_MEDIR = 5;

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Hora real de la lectura (ya convertida desde el RTC, ver DeviceTime). */
    public long timestampMillis;
    public double humedad;
    public double temperatura;
    /** % de volumen; -1 = desconocido. */
    public int nivelTolvaPct;
    public boolean tolvaVacia;
    public boolean humedadAlta;

    /** Segundos del RTC del ESP32; clave única para no duplicar al sincronizar. */
    @Nullable
    public Long deviceTs;

    @ColumnInfo(defaultValue = "0")
    public int nivelEstado;

    /** false si el DHT22 no tenía dato válido (humedad/temperatura en 0 y se ignoran). */
    @ColumnInfo(defaultValue = "1")
    public boolean dhtOk = true;

    public SensorReading(long timestampMillis, double humedad, double temperatura,
                          int nivelTolvaPct, boolean tolvaVacia, boolean humedadAlta) {
        this.timestampMillis = timestampMillis;
        this.humedad = humedad;
        this.temperatura = temperatura;
        this.nivelTolvaPct = nivelTolvaPct;
        this.tolvaVacia = tolvaVacia;
        this.humedadAlta = humedadAlta;
    }

    public boolean nivelConocido() {
        return nivelTolvaPct >= 0 && (nivelEstado == NIVEL_OK || nivelEstado == NIVEL_SIN_ECO
                || nivelEstado == NIVEL_MUY_CERCA);
    }
}
