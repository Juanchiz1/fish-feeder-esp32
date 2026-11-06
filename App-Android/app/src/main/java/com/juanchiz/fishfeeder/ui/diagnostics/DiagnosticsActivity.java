package com.juanchiz.fishfeeder.ui.diagnostics;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.juanchiz.fishfeeder.R;
import com.juanchiz.fishfeeder.data.PrefsManager;
import com.juanchiz.fishfeeder.model.DhtDiagnostic;
import com.juanchiz.fishfeeder.model.FeederStatus;
import com.juanchiz.fishfeeder.model.LevelDiagnostic;
import com.juanchiz.fishfeeder.model.RtcDiagnostic;
import com.juanchiz.fishfeeder.model.SensorDiagnostics;
import com.juanchiz.fishfeeder.model.SystemDiagnostic;
import com.juanchiz.fishfeeder.network.FeederApiService;
import com.juanchiz.fishfeeder.network.RetrofitClient;
import com.juanchiz.fishfeeder.ui.UiFormat;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Diagnóstico sensor por sensor. Cada tarjeta tiene su propio botón, que llama a
 * POST /api/diagnostics/{nivel|dht22|rtc} y solo usa ESE sensor. "Probar los tres" los
 * corre en cadena: el siguiente empieza un segundo después de que el anterior respondió.
 * Mientras una prueba está en curso, los demás botones quedan bloqueados (el ESP32 atiende una
 * petición a la vez y así nunca se usan dos sensores juntos).
 */
public class DiagnosticsActivity extends AppCompatActivity {

    private enum Sensor { NIVEL, DHT, RTC }

    private static final long PAUSA_ENTRE_PRUEBAS_MS = 1000;

    private TextView tvDiagMessage;
    private MaterialButton btnTestAll;

    private TextView tvLevelStatus, tvLevelMessage, tvLevelReading, tvLevelSamples, tvLevelSound, tvLevelDetails;
    private ProgressBar progressLevel;
    private MaterialButton btnTestLevel;

    private TextView tvDhtStatus, tvDhtMessage, tvDhtReading, tvDhtDetails, tvDhtWhen;
    private ProgressBar progressDht;
    private MaterialButton btnTestDht;

    private TextView tvRtcStatus, tvRtcMessage, tvRtcReading, tvRtcDetails;
    private ProgressBar progressRtc;
    private MaterialButton btnTestRtc;

    private TextView tvSystemInfo;

    private PrefsManager prefsManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean pruebaEnCurso = false;
    private boolean secuenciaEnCurso = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);
        prefsManager = new PrefsManager(this);

        tvDiagMessage = findViewById(R.id.tvDiagMessage);
        btnTestAll = findViewById(R.id.btnTestAll);

        tvLevelStatus = findViewById(R.id.tvLevelStatus);
        tvLevelMessage = findViewById(R.id.tvLevelMessage);
        tvLevelReading = findViewById(R.id.tvLevelReading);
        tvLevelSamples = findViewById(R.id.tvLevelSamples);
        tvLevelSound = findViewById(R.id.tvLevelSound);
        tvLevelDetails = findViewById(R.id.tvLevelDetails);
        progressLevel = findViewById(R.id.progressLevel);
        btnTestLevel = findViewById(R.id.btnTestLevel);

        tvDhtStatus = findViewById(R.id.tvDhtStatus);
        tvDhtMessage = findViewById(R.id.tvDhtMessage);
        tvDhtReading = findViewById(R.id.tvDhtReading);
        tvDhtDetails = findViewById(R.id.tvDhtDetails);
        tvDhtWhen = findViewById(R.id.tvDhtWhen);
        progressDht = findViewById(R.id.progressDht);
        btnTestDht = findViewById(R.id.btnTestDht);

        tvRtcStatus = findViewById(R.id.tvRtcStatus);
        tvRtcMessage = findViewById(R.id.tvRtcMessage);
        tvRtcReading = findViewById(R.id.tvRtcReading);
        tvRtcDetails = findViewById(R.id.tvRtcDetails);
        progressRtc = findViewById(R.id.progressRtc);
        btnTestRtc = findViewById(R.id.btnTestRtc);

        tvSystemInfo = findViewById(R.id.tvSystemInfo);

        btnTestLevel.setOnClickListener(v -> probar(Sensor.NIVEL, null));
        btnTestDht.setOnClickListener(v -> probar(Sensor.DHT, null));
        btnTestRtc.setOnClickListener(v -> probar(Sensor.RTC, null));
        btnTestAll.setOnClickListener(v -> probarTodos());

        cargarDiagnostico();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    @Nullable
    private FeederApiService api() {
        String baseUrl = prefsManager.getBaseUrl();
        return baseUrl == null ? null : RetrofitClient.getApi(baseUrl);
    }

    // ------------------------------------------------------------------ carga inicial

    private void cargarDiagnostico() {
        FeederApiService api = api();
        if (api == null) return;
        api.getDiagnostics().enqueue(new Callback<SensorDiagnostics>() {
            @Override
            public void onResponse(@NonNull Call<SensorDiagnostics> call, @NonNull Response<SensorDiagnostics> response) {
                if (isFinishing()) return;
                SensorDiagnostics d = response.body();
                if (!response.isSuccessful() || d == null) {
                    showMessage(getString(R.string.diagnostics_load_error), true);
                    return;
                }
                if (d.nivel != null) pintarNivel(d.nivel);
                if (d.dht22 != null) pintarDht(d.dht22);
                if (d.rtc != null) pintarRtc(d.rtc);
                if (d.sistema != null) pintarSistema(d.sistema);
            }

            @Override
            public void onFailure(@NonNull Call<SensorDiagnostics> call, @NonNull Throwable t) {
                if (!isFinishing()) showMessage(getString(R.string.diagnostics_load_error), true);
            }
        });
    }

    // ------------------------------------------------------------------ pruebas

    /** Prueba un solo sensor. alTerminar (opcional) se ejecuta al recibir respuesta o fallo. */
    private void probar(Sensor sensor, @Nullable Runnable alTerminar) {
        FeederApiService api = api();
        if (api == null || (pruebaEnCurso && alTerminar == null)) return;
        setOcupado(sensor, true);

        switch (sensor) {
            case NIVEL:
                api.testNivel().enqueue(new Respuesta<LevelDiagnostic>(sensor, alTerminar) {
                    @Override void pintar(LevelDiagnostic body) { pintarNivel(body); }
                });
                break;
            case DHT:
                api.testDht22().enqueue(new Respuesta<DhtDiagnostic>(sensor, alTerminar) {
                    @Override void pintar(DhtDiagnostic body) { pintarDht(body); }
                });
                break;
            case RTC:
                api.testRtc().enqueue(new Respuesta<RtcDiagnostic>(sensor, alTerminar) {
                    @Override void pintar(RtcDiagnostic body) { pintarRtc(body); }
                });
                break;
        }
    }

    /** RTC -> DHT22 -> nivel, cada uno cuando el anterior ya respondió. */
    private void probarTodos() {
        if (pruebaEnCurso) return;
        secuenciaEnCurso = true;
        actualizarBotones();
        showMessage(getString(R.string.diag_sequence_step, 1, getString(R.string.diag_rtc_name)), false);
        probar(Sensor.RTC, () -> handler.postDelayed(() -> {
            showMessage(getString(R.string.diag_sequence_step, 2, getString(R.string.diag_dht_name)), false);
            probar(Sensor.DHT, () -> handler.postDelayed(() -> {
                showMessage(getString(R.string.diag_sequence_step, 3, getString(R.string.diag_level_name)), false);
                probar(Sensor.NIVEL, () -> {
                    secuenciaEnCurso = false;
                    actualizarBotones();
                    showMessage(getString(R.string.diag_sequence_done), false);
                    cargarDiagnostico(); // refresca la tarjeta de sistema
                });
            }, PAUSA_ENTRE_PRUEBAS_MS));
        }, PAUSA_ENTRE_PRUEBAS_MS));
    }

    private abstract class Respuesta<T> implements Callback<T> {
        private final Sensor sensor;
        @Nullable private final Runnable alTerminar;

        Respuesta(Sensor sensor, @Nullable Runnable alTerminar) {
            this.sensor = sensor;
            this.alTerminar = alTerminar;
        }

        abstract void pintar(T body);

        @Override
        public void onResponse(@NonNull Call<T> call, @NonNull Response<T> response) {
            if (isFinishing()) return;
            setOcupado(sensor, false);
            if (response.isSuccessful() && response.body() != null) {
                pintar(response.body());
                if (!secuenciaEnCurso) tvDiagMessage.setVisibility(View.GONE);
            } else {
                showMessage(getString(R.string.diagnostics_load_error), true);
            }
            if (alTerminar != null) alTerminar.run();
        }

        @Override
        public void onFailure(@NonNull Call<T> call, @NonNull Throwable t) {
            if (isFinishing()) return;
            setOcupado(sensor, false);
            showMessage(getString(R.string.diagnostics_load_error), true);
            // Si falla la red se corta la secuencia: no tiene sentido seguir probando.
            if (secuenciaEnCurso) {
                secuenciaEnCurso = false;
                actualizarBotones();
            }
        }
    }

    private void setOcupado(Sensor sensor, boolean ocupado) {
        pruebaEnCurso = ocupado;
        ProgressBar spinner = sensor == Sensor.NIVEL ? progressLevel : sensor == Sensor.DHT ? progressDht : progressRtc;
        MaterialButton boton = sensor == Sensor.NIVEL ? btnTestLevel : sensor == Sensor.DHT ? btnTestDht : btnTestRtc;
        spinner.setVisibility(ocupado ? View.VISIBLE : View.GONE);
        boton.setText(ocupado ? R.string.diagnostics_checking : R.string.btn_test_this_sensor);
        actualizarBotones();
    }

    private void actualizarBotones() {
        boolean libre = !pruebaEnCurso && !secuenciaEnCurso;
        btnTestLevel.setEnabled(libre);
        btnTestDht.setEnabled(libre);
        btnTestRtc.setEnabled(libre);
        btnTestAll.setEnabled(libre);
    }

    // ------------------------------------------------------------------ pintar

    private void pintarNivel(LevelDiagnostic t) {
        String estado = t.estado == null ? FeederStatus.NIVEL_SIN_MEDIR : t.estado;
        switch (estado) {
            case FeederStatus.NIVEL_OK:
                pill(tvLevelStatus, getString(R.string.diagnostics_ok), R.color.alert_ok, R.color.alert_ok_bg);
                tvLevelReading.setText(getString(R.string.diag_level_reading_ok, t.distanciaMm, t.nivelPct));
                break;
            case FeederStatus.NIVEL_SIN_ECO:
                pill(tvLevelStatus, getString(R.string.diag_pill_empty), R.color.alert_ok, R.color.alert_ok_bg);
                tvLevelReading.setText(t.distanciaCrudaMm > 0
                        ? getString(R.string.diag_level_reading_beyond, t.distanciaCrudaMm)
                        : getString(R.string.diag_level_reading_empty));
                break;
            case FeederStatus.NIVEL_MUY_CERCA:
                pill(tvLevelStatus, getString(R.string.diag_pill_too_close), R.color.alert_warning, R.color.alert_warning_bg);
                tvLevelReading.setText(getString(R.string.diag_level_reading_full, t.zonaCiegaMm));
                break;
            case FeederStatus.NIVEL_ERROR:
                pill(tvLevelStatus, getString(R.string.diagnostics_failed), R.color.alert_danger, R.color.alert_danger_bg);
                break;
            case FeederStatus.NIVEL_SIN_SENSOR:
                pill(tvLevelStatus, getString(R.string.diag_pill_no_response), R.color.alert_danger, R.color.alert_danger_bg);
                break;
            default:
                pill(tvLevelStatus, getString(R.string.diag_pill_untested), R.color.text_secondary_light, R.color.divider_light);
                break;
        }
        tvLevelReading.setVisibility(t.funciona() ? View.VISIBLE : View.GONE);
        tvLevelMessage.setText(t.mensaje);

        // Cada pulso: mm si el eco fue válido, "mm*" si volvió de más allá del fondo, y una
        // marca si no hubo distancia útil.
        StringBuilder sb = new StringBuilder();
        boolean hayMarcas = false;
        if (t.muestras != null) {
            for (LevelDiagnostic.Muestra m : t.muestras) {
                if (sb.length() > 0) sb.append("  ");
                switch (m.st) {
                    case LevelDiagnostic.ST_VALIDA:        sb.append(m.mm); break;
                    case LevelDiagnostic.ST_MAS_ALLA:      sb.append(m.mm).append('*'); hayMarcas = true; break;
                    case LevelDiagnostic.ST_MUY_CERCA:     sb.append('C'); hayMarcas = true; break;
                    case LevelDiagnostic.ST_SIN_RESPUESTA: sb.append('E'); hayMarcas = true; break;
                    default:                               sb.append('—'); hayMarcas = true; break;
                }
            }
        }
        if (sb.length() == 0) sb.append("--");
        if (hayMarcas) sb.append('\n').append(getString(R.string.diag_level_samples_legend));
        tvLevelSamples.setText(sb.toString());

        int total = t.muestras == null ? 0 : t.muestras.size();
        tvLevelSound.setVisibility(total > 0 ? View.VISIBLE : View.GONE);
        tvLevelSound.setText(getString(R.string.diag_level_sound, t.velocidadSonido, t.temperaturaUsada));

        List<String> det = new ArrayList<>();
        if (total > 0) {
            det.add(getString(R.string.diag_level_counts, t.validas, total, t.sinEco, t.muyCerca, t.errores));
            if (t.validas > 1) det.add(getString(R.string.diag_level_spread, t.dispersionMm));
            det.add(getString(R.string.diag_level_duration, t.duracionMs / 1000f));
        }
        if (t.sesionesFallidasSeguidas > 1) det.add(getString(R.string.diag_level_failed_sessions, t.sesionesFallidasSeguidas));
        det.add(getString(R.string.diag_last_test, UiFormat.hace(this, t.medidoHaceS)));
        tvLevelDetails.setText(android.text.TextUtils.join("\n", det));
    }

    private void pintarDht(DhtDiagnostic d) {
        boolean nuncaProbado = !d.ok && d.ultimaLecturaHaceS < 0 && d.intentos == 0;
        if (d.ok) pill(tvDhtStatus, getString(R.string.diagnostics_ok), R.color.alert_ok, R.color.alert_ok_bg);
        else if (nuncaProbado) pill(tvDhtStatus, getString(R.string.diag_pill_untested), R.color.text_secondary_light, R.color.divider_light);
        else pill(tvDhtStatus, getString(R.string.diagnostics_failed), R.color.alert_danger, R.color.alert_danger_bg);

        tvDhtMessage.setText(d.mensaje);
        tvDhtReading.setVisibility(d.ok ? View.VISIBLE : View.GONE);
        tvDhtReading.setText(getString(R.string.diag_dht_reading, d.humedad, d.temperatura));
        tvDhtDetails.setText(getString(R.string.diag_dht_details,
                UiFormat.hace(this, d.ultimaLecturaHaceS), d.intentos, d.fallosSeguidos));
    }

    private void pintarRtc(RtcDiagnostic r) {
        if (!r.ok) pill(tvRtcStatus, getString(R.string.diagnostics_failed), R.color.alert_danger, R.color.alert_danger_bg);
        else if (r.perdioEnergia) pill(tvRtcStatus, getString(R.string.diag_pill_check_battery), R.color.alert_warning, R.color.alert_warning_bg);
        else pill(tvRtcStatus, getString(R.string.diagnostics_ok), R.color.alert_ok, R.color.alert_ok_bg);

        tvRtcMessage.setText(r.mensaje);
        tvRtcReading.setVisibility(r.ok ? View.VISIBLE : View.GONE);
        tvRtcReading.setText(getString(R.string.diag_rtc_reading, r.fecha, r.hora));
        tvRtcDetails.setText(r.ok
                ? getString(R.string.diag_rtc_details, r.temperaturaInterna, UiFormat.hace(this, r.revisadoHaceS))
                : getString(R.string.diag_last_test, UiFormat.hace(this, r.revisadoHaceS)));
    }

    private void pintarSistema(SystemDiagnostic s) {
        tvSystemInfo.setText(getString(R.string.diag_system_info,
                s.wifiRssi, s.heapLibre / 1024, UiFormat.hace(this, s.uptimeS),
                s.proximaLecturaS / 60, s.proximaLecturaS % 60, s.cicloMin));
        tvDhtWhen.setText(getString(R.string.diag_dht_when_format, s.cicloMin));
    }

    private void pill(TextView v, String texto, @ColorRes int texColor, @ColorRes int fondo) {
        v.setText(texto);
        v.setTextColor(ContextCompat.getColor(this, texColor));
        v.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, fondo)));
    }

    private void showMessage(String message, boolean isError) {
        tvDiagMessage.setText(message);
        tvDiagMessage.setTextColor(ContextCompat.getColor(this, isError ? R.color.alert_danger : R.color.text_secondary_light));
        tvDiagMessage.setVisibility(View.VISIBLE);
    }
}
