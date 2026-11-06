package com.juanchiz.fishfeeder.ui.thresholds;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.juanchiz.fishfeeder.R;
import com.juanchiz.fishfeeder.data.PrefsManager;
import com.juanchiz.fishfeeder.model.FeederStatus;
import com.juanchiz.fishfeeder.model.ThresholdsConfig;
import com.juanchiz.fishfeeder.model.LevelDiagnostic;
import com.juanchiz.fishfeeder.network.FeederApiService;
import com.juanchiz.fishfeeder.network.RetrofitClient;

import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Pantalla para calibrar/editar los umbrales que el ESP32 usa para decidir cuándo algo es
 * "anormal": humedad máxima, rango de temperatura normal, y las distancias del sensor de nivel que
 * definen tolva llena/vacía. Ver GET/POST /api/thresholds en el firmware.
 *
 * Además de escribir el número a mano, se puede poner la condición real a prueba (alimento
 * mojado bajo el DHT22, tolva llena/vacía bajo el sensor de nivel) y tocar "Usar lectura actual" junto
 * al campo correspondiente para copiar ahí el valor que el sensor está leyendo en ese momento
 * (humedad y temperatura de GET /api/status; la distancia, de una medición nueva del sensor de nivel).
 */
public class ThresholdsActivity extends AppCompatActivity {

    private TextInputEditText etHumedadMax;
    private TextInputEditText etTempMin;
    private TextInputEditText etTempMax;
    private TextInputEditText etTolvaLlena;
    private TextInputEditText etTolvaVacia;
    private TextInputEditText etCambioMinimo;
    private TextView tvMessage;
    private MaterialButton btnSaveThresholds;
    private View progressBar;

    private TextView tvLiveHumidity;
    private TextView tvLiveTemperature;
    private TextView tvLiveDistance;
    private MaterialButton btnRefreshLiveReading;

    private PrefsManager prefsManager;

    // Última lectura en vivo obtenida de /api/status, para que los botones "Usar lectura actual"
    // sepan qué copiar sin tener que volver a consultar el dispensador.
    private FeederStatus ultimaLectura;
    // El sensor de nivel no mide en continuo: la distancia en vivo sale de una medición pedida a
    // propósito (POST /api/diagnostics/nivel). null = sin eco o sin lectura.
    private Integer distanciaEnVivoMm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_thresholds);

        prefsManager = new PrefsManager(this);

        etHumedadMax = findViewById(R.id.etHumedadMax);
        etTempMin = findViewById(R.id.etTempMin);
        etTempMax = findViewById(R.id.etTempMax);
        etTolvaLlena = findViewById(R.id.etTolvaLlena);
        etTolvaVacia = findViewById(R.id.etTolvaVacia);
        etCambioMinimo = findViewById(R.id.etCambioMinimo);
        tvMessage = findViewById(R.id.tvMessage);
        btnSaveThresholds = findViewById(R.id.btnSaveThresholds);
        progressBar = findViewById(R.id.progressBar);

        tvLiveHumidity = findViewById(R.id.tvLiveHumidity);
        tvLiveTemperature = findViewById(R.id.tvLiveTemperature);
        tvLiveDistance = findViewById(R.id.tvLiveDistance);
        btnRefreshLiveReading = findViewById(R.id.btnRefreshLiveReading);

        MaterialButton btnUseHumedad = findViewById(R.id.btnUseHumedad);
        MaterialButton btnUseTempMin = findViewById(R.id.btnUseTempMin);
        MaterialButton btnUseTempMax = findViewById(R.id.btnUseTempMax);
        MaterialButton btnUseTolvaLlena = findViewById(R.id.btnUseTolvaLlena);
        MaterialButton btnUseTolvaVacia = findViewById(R.id.btnUseTolvaVacia);

        btnSaveThresholds.setOnClickListener(v -> guardarUmbrales());
        btnRefreshLiveReading.setOnClickListener(v -> cargarLecturaEnVivo());

        btnUseHumedad.setOnClickListener(v -> usarLectura(etHumedadMax, ultimaLectura == null ? null : ultimaLectura.humedad));
        btnUseTempMin.setOnClickListener(v -> usarLectura(etTempMin, ultimaLectura == null ? null : ultimaLectura.temperatura));
        btnUseTempMax.setOnClickListener(v -> usarLectura(etTempMax, ultimaLectura == null ? null : ultimaLectura.temperatura));
        btnUseTolvaLlena.setOnClickListener(v -> usarLectura(etTolvaLlena, distanciaEnVivoMm == null ? null : (double) distanciaEnVivoMm));
        btnUseTolvaVacia.setOnClickListener(v -> usarLectura(etTolvaVacia, distanciaEnVivoMm == null ? null : (double) distanciaEnVivoMm));

        cargarUmbralesActuales();
        cargarLecturaEnVivo();
    }

    /** Consulta /api/status y muestra humedad/temperatura/distancia actuales para que el usuario
     *  pueda comparar contra la condición física que acaba de poner a prueba. */
    private void cargarLecturaEnVivo() {
        String baseUrl = prefsManager.getBaseUrl();
        if (baseUrl == null) return;

        btnRefreshLiveReading.setEnabled(false);
        FeederApiService api = RetrofitClient.getApi(baseUrl);
        api.getStatus().enqueue(new Callback<FeederStatus>() {
            @Override
            public void onResponse(Call<FeederStatus> call, Response<FeederStatus> response) {
                btnRefreshLiveReading.setEnabled(true);
                if (response.isSuccessful() && response.body() != null) {
                    ultimaLectura = response.body();
                    tvLiveHumidity.setText(getString(R.string.thresholds_live_humidity,
                            String.format(Locale.getDefault(), "%.1f%%", ultimaLectura.humedad)));
                    tvLiveTemperature.setText(getString(R.string.thresholds_live_temperature,
                            String.format(Locale.getDefault(), "%.1f°C", ultimaLectura.temperatura)));
                    medirDistanciaEnVivo(api);
                } else {
                    mostrarLecturaNoDisponible();
                }
            }

            @Override
            public void onFailure(Call<FeederStatus> call, Throwable t) {
                btnRefreshLiveReading.setEnabled(true);
                mostrarLecturaNoDisponible();
            }
        });
    }

    /** Mide el nivel una vez para tener una distancia actual (la tolva llena o vacía que el
     *  usuario acaba de preparar), no la de la última alimentación. */
    private void medirDistanciaEnVivo(FeederApiService api) {
        btnRefreshLiveReading.setEnabled(false);
        tvLiveDistance.setText(getString(R.string.thresholds_live_distance, getString(R.string.measuring)));
        api.testNivel().enqueue(new Callback<LevelDiagnostic>() {
            @Override
            public void onResponse(Call<LevelDiagnostic> call, Response<LevelDiagnostic> response) {
                btnRefreshLiveReading.setEnabled(true);
                LevelDiagnostic t = response.body();
                if (!response.isSuccessful() || t == null) {
                    distanciaEnVivoMm = null;
                    tvLiveDistance.setText(getString(R.string.thresholds_live_distance, getString(R.string.thresholds_live_unavailable)));
                    return;
                }
                if (FeederStatus.NIVEL_OK.equals(t.estado)) {
                    distanciaEnVivoMm = t.distanciaMm;
                    tvLiveDistance.setText(getString(R.string.thresholds_live_distance, t.distanciaMm + " mm"));
                } else if (FeederStatus.NIVEL_SIN_ECO.equals(t.estado) && t.distanciaCrudaMm > 0) {
                    // Con la tolva vacía el eco vuelve de más allá del fondo configurado: esa
                    // distancia es justo la que sirve para calibrar "sensor-fondo".
                    distanciaEnVivoMm = t.distanciaCrudaMm;
                    tvLiveDistance.setText(getString(R.string.thresholds_live_distance,
                            getString(R.string.thresholds_live_distance_beyond, t.distanciaCrudaMm)));
                } else {
                    // Sin eco (tolva vacía), muy cerca o falla: no hay distancia que copiar.
                    distanciaEnVivoMm = null;
                    tvLiveDistance.setText(getString(R.string.thresholds_live_distance,
                            com.juanchiz.fishfeeder.ui.UiFormat.estadoNivel(ThresholdsActivity.this, t.estado)));
                }
            }

            @Override
            public void onFailure(Call<LevelDiagnostic> call, Throwable t) {
                btnRefreshLiveReading.setEnabled(true);
                distanciaEnVivoMm = null;
                tvLiveDistance.setText(getString(R.string.thresholds_live_distance, getString(R.string.thresholds_live_unavailable)));
            }
        });
    }

    private void mostrarLecturaNoDisponible() {
        String na = getString(R.string.thresholds_live_unavailable);
        tvLiveHumidity.setText(getString(R.string.thresholds_live_humidity, na));
        tvLiveTemperature.setText(getString(R.string.thresholds_live_temperature, na));
        tvLiveDistance.setText(getString(R.string.thresholds_live_distance, na));
        showMessage(getString(R.string.thresholds_live_error), true);
    }

    /** Copia el valor en vivo (si ya se cargó) al campo indicado. */
    private void usarLectura(TextInputEditText campo, Double valor) {
        if (valor == null) {
            showMessage(getString(R.string.thresholds_live_error), true);
            return;
        }
        campo.setText(formatNumber(valor.floatValue()));
    }

    private void cargarUmbralesActuales() {
        String baseUrl = prefsManager.getBaseUrl();
        if (baseUrl == null) return;

        setLoading(true);
        FeederApiService api = RetrofitClient.getApi(baseUrl);
        api.getThresholds().enqueue(new Callback<ThresholdsConfig>() {
            @Override
            public void onResponse(Call<ThresholdsConfig> call, Response<ThresholdsConfig> response) {
                setLoading(false);
                if (response.isSuccessful() && response.body() != null) {
                    ThresholdsConfig t = response.body();
                    etHumedadMax.setText(formatNumber(t.humedadMax));
                    etTempMin.setText(formatNumber(t.temperaturaMin));
                    etTempMax.setText(formatNumber(t.temperaturaMax));
                    etTolvaLlena.setText(String.valueOf(t.tolvaLlenaMm));
                    etTolvaVacia.setText(String.valueOf(t.tolvaVaciaMm));
                    etCambioMinimo.setText(formatNumber(t.cambioMinimoMm));
                } else {
                    showMessage(getString(R.string.thresholds_load_error), true);
                }
            }

            @Override
            public void onFailure(Call<ThresholdsConfig> call, Throwable t) {
                setLoading(false);
                showMessage(getString(R.string.thresholds_load_error), true);
            }
        });
    }

    private void guardarUmbrales() {
        String baseUrl = prefsManager.getBaseUrl();
        if (baseUrl == null) return;

        Float humedadMax = parseFloat(etHumedadMax);
        Float tempMin = parseFloat(etTempMin);
        Float tempMax = parseFloat(etTempMax);
        Integer tolvaLlena = parseInt(etTolvaLlena);
        Integer tolvaVacia = parseInt(etTolvaVacia);
        Float cambioMinimo = parseFloat(etCambioMinimo);

        if (humedadMax == null || tempMin == null || tempMax == null || tolvaLlena == null
                || tolvaVacia == null || cambioMinimo == null) {
            showMessage(getString(R.string.thresholds_error_empty), true);
            return;
        }
        // Las mismas reglas que valida handleSetThresholds() en el firmware, para avisar antes.
        if (humedadMax <= 0 || humedadMax > 100) {
            showMessage(getString(R.string.thresholds_error_humidity), true);
            return;
        }
        if (tempMin >= tempMax) {
            showMessage(getString(R.string.thresholds_error_temp_range), true);
            return;
        }
        if (tolvaLlena >= tolvaVacia) {
            showMessage(getString(R.string.thresholds_error_hopper_range), true);
            return;
        }
        if (cambioMinimo <= 0) {
            showMessage(getString(R.string.thresholds_error_min_change), true);
            return;
        }

        setLoading(true);
        ThresholdsConfig config = new ThresholdsConfig(humedadMax, tempMin, tempMax, tolvaLlena, tolvaVacia, cambioMinimo);
        FeederApiService api = RetrofitClient.getApi(baseUrl);
        api.setThresholds(config).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                setLoading(false);
                if (response.isSuccessful()) {
                    prefsManager.saveThresholds(config); // líneas de referencia de Estadísticas
                    showMessage(getString(R.string.thresholds_success), false);
                } else {
                    // El firmware responde 400 con {"error":"..."} cuando un valor no tiene sentido.
                    showMessage(getString(R.string.thresholds_error_device, leerError(response)), true);
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                setLoading(false);
                showMessage(getString(R.string.thresholds_error_network), true);
            }
        });
    }

    private String leerError(Response<?> response) {
        try {
            if (response.errorBody() != null) {
                String cuerpo = response.errorBody().string();
                int i = cuerpo.indexOf("\"error\":\"");
                if (i >= 0) {
                    int ini = i + 9;
                    int fin = cuerpo.indexOf('"', ini);
                    if (fin > ini) return cuerpo.substring(ini, fin);
                }
            }
        } catch (Exception ignored) {
        }
        return "HTTP " + response.code();
    }

    /**
     * Acepta "65.5" y "65,5": con el celular en español el teclado y String.format usan coma, y
     * Float.parseFloat solo entiende punto. Antes esto hacía que "Guardar" fallara siempre.
     */
    private Float parseFloat(TextInputEditText field) {
        String text = field.getText() != null ? field.getText().toString().trim() : "";
        if (TextUtils.isEmpty(text)) return null;
        try {
            float v = Float.parseFloat(text.replace(',', '.'));
            return Float.isNaN(v) || Float.isInfinite(v) ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Las distancias son enteras, pero "Usar lectura actual" o el usuario pueden dejar "95,0". */
    private Integer parseInt(TextInputEditText field) {
        Float v = parseFloat(field);
        return v == null ? null : Math.round(v);
    }

    /** Siempre con punto (Locale.US) y sin ".0" sobrante, para que parseFloat lo pueda leer. */
    private String formatNumber(float value) {
        if (value == Math.rint(value)) return String.valueOf((int) value);
        return String.format(Locale.US, "%.1f", value);
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnSaveThresholds.setEnabled(!loading);
        btnSaveThresholds.setText(loading ? getString(R.string.thresholds_saving) : getString(R.string.btn_save_thresholds));
    }

    private void showMessage(String message, boolean isError) {
        tvMessage.setText(message);
        tvMessage.setTextColor(ContextCompat.getColor(this, isError ? R.color.alert_danger : R.color.alert_ok));
        tvMessage.setVisibility(View.VISIBLE);
    }
}
