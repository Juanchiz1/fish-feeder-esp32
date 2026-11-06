package com.juanchiz.fishfeeder.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface SensorReadingDao {

    @Insert
    void insert(SensorReading reading);

    /** Las que ya existen (mismo deviceTs) se ignoran: sincronizar dos veces no duplica. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAll(List<SensorReading> readings);

    @Query("SELECT MAX(deviceTs) FROM sensor_readings")
    Long maxDeviceTs();

    @Query("SELECT * FROM sensor_readings ORDER BY timestampMillis DESC LIMIT :limit")
    List<SensorReading> getRecent(int limit);

    @Query("SELECT * FROM sensor_readings WHERE timestampMillis >= :fromMillis AND timestampMillis < :toMillis ORDER BY timestampMillis ASC")
    List<SensorReading> getBetween(long fromMillis, long toMillis);

    /** Última lectura con nivel conocido antes de un instante (nivel con que empezó el día). */
    @Query("SELECT * FROM sensor_readings WHERE timestampMillis < :beforeMillis AND nivelTolvaPct >= 0 AND nivelEstado IN (0, 1, 2) ORDER BY timestampMillis DESC LIMIT 1")
    SensorReading getLastWithLevelBefore(long beforeMillis);

    @Query("SELECT COUNT(*) FROM sensor_readings WHERE timestampMillis >= :sinceMillis")
    int countSince(long sinceMillis);

    @Query("DELETE FROM sensor_readings WHERE timestampMillis < :beforeMillis")
    void deleteOlderThan(long beforeMillis);

    /** La lectura más antigua con nivel conocido dentro de la ventana (tasa de consumo). */
    @Query("SELECT * FROM sensor_readings WHERE timestampMillis >= :sinceMillis AND nivelTolvaPct >= 0 AND nivelEstado IN (0, 1, 2) ORDER BY timestampMillis ASC LIMIT 1")
    SensorReading getOldestSince(long sinceMillis);

    @Query("SELECT AVG(humedad) FROM sensor_readings WHERE timestampMillis >= :sinceMillis AND dhtOk = 1")
    Double avgHumedadSince(long sinceMillis);
}
