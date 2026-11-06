package com.juanchiz.fishfeeder.data;

import java.util.TimeZone;

/**
 * El RTC del ESP32 guarda la hora LOCAL del celular (POST /api/time manda año/mes/día/hora tal
 * cual), y RTClib la convierte a "unixtime" como si fuera UTC. Estas funciones pasan de ese valor
 * a milisegundos reales y viceversa usando la zona horaria del celular.
 */
public final class DeviceTime {

    private DeviceTime() {
    }

    /** Segundos del RTC -> milisegundos epoch reales. */
    public static long toMillis(long deviceTs) {
        long comoUtc = deviceTs * 1000L;
        TimeZone tz = TimeZone.getDefault();
        return comoUtc - tz.getOffset(comoUtc - tz.getRawOffset());
    }

    /** Milisegundos epoch reales -> segundos del RTC. */
    public static long toDeviceTs(long millis) {
        return (millis + TimeZone.getDefault().getOffset(millis)) / 1000L;
    }
}
