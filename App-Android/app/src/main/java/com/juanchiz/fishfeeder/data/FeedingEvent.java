package com.juanchiz.fishfeeder.data;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Una alimentación. Desde la versión 2 de la base de datos sale del registro del ESP32
 * (GET /api/feedings), con la hora del RTC y el nivel medido antes y después.
 */
@Entity(tableName = "feeding_events",
        indices = {@Index(value = "deviceTs", unique = true)})
public class FeedingEvent {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long timestampMillis;
    public int porciones;
    public boolean manual;

    @Nullable
    public Long deviceTs;

    /** % de volumen antes y después de dispensar; -1 = desconocido. */
    @ColumnInfo(defaultValue = "-1")
    public int nivelAntes = -1;

    @ColumnInfo(defaultValue = "-1")
    public int nivelDespues = -1;

    public FeedingEvent(long timestampMillis, int porciones, boolean manual) {
        this.timestampMillis = timestampMillis;
        this.porciones = porciones;
        this.manual = manual;
    }
}
