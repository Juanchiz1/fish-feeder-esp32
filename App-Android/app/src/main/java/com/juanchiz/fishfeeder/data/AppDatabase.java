package com.juanchiz.fishfeeder.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {SensorReading.class, FeedingEvent.class}, version = 2, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract SensorReadingDao sensorReadingDao();
    public abstract FeedingEventDao feedingEventDao();

    /**
     * v1 -> v2: columnas para sincronizar con el historial del ESP32 sin perder lo ya guardado.
     * deviceTs es nullable para que las filas viejas (NULL) no choquen con el índice único.
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE sensor_readings ADD COLUMN deviceTs INTEGER");
            db.execSQL("ALTER TABLE sensor_readings ADD COLUMN nivelEstado INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sensor_readings ADD COLUMN dhtOk INTEGER NOT NULL DEFAULT 1");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sensor_readings_deviceTs ON sensor_readings (deviceTs)");

            db.execSQL("ALTER TABLE feeding_events ADD COLUMN deviceTs INTEGER");
            db.execSQL("ALTER TABLE feeding_events ADD COLUMN nivelAntes INTEGER NOT NULL DEFAULT -1");
            db.execSQL("ALTER TABLE feeding_events ADD COLUMN nivelDespues INTEGER NOT NULL DEFAULT -1");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_feeding_events_deviceTs ON feeding_events (deviceTs)");
        }
    };

    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "fishfeeder.db")
                            .addMigrations(MIGRATION_1_2)
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return instance;
    }
}
