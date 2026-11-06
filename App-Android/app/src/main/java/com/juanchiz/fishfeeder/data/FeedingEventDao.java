package com.juanchiz.fishfeeder.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface FeedingEventDao {

    @Insert
    void insert(FeedingEvent event);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAll(List<FeedingEvent> events);

    @Query("SELECT MAX(deviceTs) FROM feeding_events")
    Long maxDeviceTs();

    @Query("SELECT * FROM feeding_events WHERE timestampMillis >= :sinceMillis ORDER BY timestampMillis DESC")
    List<FeedingEvent> getSince(long sinceMillis);

    @Query("SELECT * FROM feeding_events WHERE timestampMillis >= :fromMillis AND timestampMillis < :toMillis ORDER BY timestampMillis ASC")
    List<FeedingEvent> getBetween(long fromMillis, long toMillis);

    @Query("SELECT COUNT(*) FROM feeding_events WHERE timestampMillis >= :sinceMillis")
    int countSince(long sinceMillis);

    @Query("DELETE FROM feeding_events WHERE timestampMillis < :beforeMillis")
    void deleteOlderThan(long beforeMillis);
}
