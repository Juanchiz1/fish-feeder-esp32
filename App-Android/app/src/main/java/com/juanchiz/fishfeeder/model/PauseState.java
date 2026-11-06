package com.juanchiz.fishfeeder.model;

/** Cuerpo de GET/POST /api/pause: estado del "modo vacaciones" (horarios pausados o no). */
public class PauseState {
    public boolean pausado;

    public PauseState() {
    }

    public PauseState(boolean pausado) {
        this.pausado = pausado;
    }
}
