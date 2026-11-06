package com.juanchiz.fishfeeder.ui;

import android.content.Context;

import com.juanchiz.fishfeeder.R;
import com.juanchiz.fishfeeder.model.FeederStatus;

/** Textos compartidos entre pantallas. */
public final class UiFormat {

    private UiFormat() {
    }

    /** "hace 40 s" / "hace 12 min" / "hace 3,5 h" / "nunca". */
    public static String hace(Context c, long segundos) {
        if (segundos < 0) return c.getString(R.string.time_never);
        if (segundos < 60) return c.getString(R.string.time_ago_seconds, segundos);
        if (segundos < 3600) return c.getString(R.string.time_ago_minutes, Math.round(segundos / 60.0));
        if (segundos < 48 * 3600) return c.getString(R.string.time_ago_hours, segundos / 3600.0);
        return c.getString(R.string.time_ago_days, Math.round(segundos / 86400.0));
    }

    /** Descripción corta del estado del sensor de nivel (nivel_estado del firmware). */
    public static String estadoNivel(Context c, String estado) {
        if (estado == null) return c.getString(R.string.level_state_not_measured);
        switch (estado) {
            case FeederStatus.NIVEL_OK:        return c.getString(R.string.level_state_ok);
            case FeederStatus.NIVEL_SIN_ECO:   return c.getString(R.string.level_state_no_echo);
            case FeederStatus.NIVEL_MUY_CERCA: return c.getString(R.string.level_state_too_close);
            case FeederStatus.NIVEL_ERROR:     return c.getString(R.string.level_state_error);
            case FeederStatus.NIVEL_SIN_SENSOR: return c.getString(R.string.level_state_no_sensor);
            default:                           return c.getString(R.string.level_state_not_measured);
        }
    }
}
