package com.juanchiz.fishfeeder.ui.dashboard;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.juanchiz.fishfeeder.R;
import com.juanchiz.fishfeeder.data.AppDatabase;
import com.juanchiz.fishfeeder.data.HistorySync;
import com.juanchiz.fishfeeder.data.PrefsManager;
import com.juanchiz.fishfeeder.data.SensorReading;
import com.juanchiz.fishfeeder.model.FeederStatus;
import com.juanchiz.fishfeeder.model.LevelDiagnostic;
import com.juanchiz.fishfeeder.network.FeederApiService;
import com.juanchiz.fishfeeder.network.RetrofitClient;
import com.juanchiz.fishfeeder.ui.UiFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Pantalla de inicio: estado en vivo de los sensores y alimentación manual. */
public class DashboardFragment extends Fragment {

    private static final int POLL_INTERVAL_MS = 5000;
    private static final long SYNC_INTERVAL_MS = 60_000;
    /** El ESP32 tarda ~2 s por porción + la medición del nivel antes de tener el resultado. */
    private static final int REFRESH_AFTER_FEED_MS = 6000;

    private SwipeRefreshLayout swipeRefresh;
    private View dotStatus;
    private TextView tvConnectionStatus;
    private TextView tvClock;
    private View alertBanner;
    private View tvVacationBanner;
    private TextView tvHumidity;
    private TextView tvTemperature;
    private TextView tvLevel;
    private ProgressBar progressLevel;
    private TextView tvLevelInfo;
    private MaterialButton btnMeasureLevel;
    private TextView tvDaysRemaining;
    private TextView tvLastFeeding;
    private MaterialButtonToggleGroup toggleGroupPortions;
    private MaterialButton btnFeedNow;

    private PrefsManager prefsManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = this::refreshStatus;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private int selectedPortions = 1;
    private long lastSyncAttempt = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefsManager = new PrefsManager(requireContext());

        swipeRefresh = view.findViewById(R.id.swipeRefresh);
        dotStatus = view.findViewById(R.id.dotStatus);
        tvConnectionStatus = view.findViewById(R.id.tvConnectionStatus);
        tvClock = view.findViewById(R.id.tvClock);
        alertBanner = view.findViewById(R.id.alertBanner);
        tvVacationBanner = view.findViewById(R.id.tvVacationBanner);
        tvHumidity = view.findViewById(R.id.tvHumidity);
        tvTemperature = view.findViewById(R.id.tvTemperature);
        tvLevel = view.findViewById(R.id.tvLevel);
        progressLevel = view.findViewById(R.id.progressLevel);
        tvLevelInfo = view.findViewById(R.id.tvLevelInfo);
        btnMeasureLevel = view.findViewById(R.id.btnMeasureLevel);
        tvDaysRemaining = view.findViewById(R.id.tvDaysRemaining);
        tvLastFeeding = view.findViewById(R.id.tvLastFeeding);
        toggleGroupPortions = view.findViewById(R.id.toggleGroupPortions);
        btnFeedNow = view.findViewById(R.id.btnFeedNow);

        swipeRefresh.setOnRefreshListener(() -> {
            lastSyncAttempt = 0; // fuerza también la sincronización del historial
            refreshStatus();
        });

        toggleGroupPortions.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btnPortion1) selectedPortions = 1;
            else if (checkedId == R.id.btnPortion2) selectedPortions = 2;
            else if (checkedId == R.id.btnPortion3) selectedPortions = 3;
        });

        btnFeedNow.setOnClickListener(v -> feedNow());
        btnMeasureLevel.setOnClickListener(v -> measureLevelNow());

        if (prefsManager.getBaseUrl() == null) {
            setConnected(false);
            btnFeedNow.setEnabled(false);
            btnMeasureLevel.setEnabled(false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.post(pollRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(pollRunnable);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        syncExecutor.shutdown();
    }

    private void refreshStatus() {
        handler.removeCallbacks(pollRunnable);
        String baseUrl = prefsManager.getBaseUrl();
        if (baseUrl == null) return;

        RetrofitClient.getApi(baseUrl).getStatus().enqueue(new Callback<FeederStatus>() {
            @Override
            public void onResponse(@NonNull Call<FeederStatus> call, @NonNull Response<FeederStatus> response) {
                if (!isAdded()) return;
                swipeRefresh.setRefreshing(false);
                boolean ok = response.isSuccessful() && response.body() != null;
                if (ok) bindStatus(response.body());
                setConnected(ok);
                btnFeedNow.setEnabled(ok);
                btnMeasureLevel.setEnabled(ok);
                if (ok) syncHistoryIfDue();
                handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
            }

            @Override
            public void onFailure(@NonNull Call<FeederStatus> call, @NonNull Throwable t) {
                if (!isAdded()) return;
                swipeRefresh.setRefreshing(false);
                setConnected(false);
                handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
            }
        });
    }

    /** Mientras la pantalla está abierta, baja el historial del ESP32 a Room cada minuto. */
    private void syncHistoryIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastSyncAttempt < SYNC_INTERVAL_MS) return;
        lastSyncAttempt = now;
        android.content.Context appContext = requireContext().getApplicationContext();
        syncExecutor.execute(() -> HistorySync.syncBlocking(appContext));
    }

    private void bindStatus(FeederStatus status) {
        if (status.dhtOk) {
            tvHumidity.setText(String.format(Locale.getDefault(), "%.0f%%", status.humedad));
            tvTemperature.setText(String.format(Locale.getDefault(), "%.1f°C", status.temperatura));
        } else {
            tvHumidity.setText("--");
            tvTemperature.setText("--");
        }

        boolean nivelOk = status.nivelConfiable() && status.nivelTolvaPct >= 0;
        tvLevel.setText(nivelOk ? status.nivelTolvaPct + "%" : "--");
        progressLevel.setProgress(nivelOk ? status.nivelTolvaPct : 0);
        tvLevelInfo.setText(getString(R.string.level_info_format,
                UiFormat.hace(requireContext(), status.nivelMedidoHaceS),
                UiFormat.estadoNivel(requireContext(), status.nivelEstado)));

        tvLastFeeding.setText(status.ultimaAlimentacion);
        tvClock.setText(status.horaActual);
        tvVacationBanner.setVisibility(status.horariosPausados ? View.VISIBLE : View.GONE);

        if (nivelOk) estimateDaysRemaining(status.nivelTolvaPct);
        else tvDaysRemaining.setVisibility(View.GONE);

        bindAlerts(status, nivelOk);
    }

    private void bindAlerts(FeederStatus status, boolean nivelOk) {
        List<String> alertas = new ArrayList<>();
        boolean urgente = false;

        if (!nivelOk && !FeederStatus.NIVEL_SIN_MEDIR.equals(status.nivelEstado)) {
            alertas.add(getString(R.string.alert_level_sensor));
        } else if (status.tolvaVacia) {
            alertas.add(getString(R.string.alert_empty));
            urgente = true;
        } else if (status.tolvaBaja) {
            alertas.add(getString(R.string.alert_low));
        }
        if (!status.dhtOk) alertas.add(getString(R.string.alert_dht_sensor));
        if (!status.rtcOk) {
            alertas.add(getString(R.string.alert_rtc));
            urgente = true;
        }
        if (status.humedadAlta) alertas.add(getString(R.string.alert_humidity));
        if (status.temperaturaAlta) alertas.add(getString(R.string.alert_temp_high));
        if (status.temperaturaBaja) alertas.add(getString(R.string.alert_temp_low));
        if (status.posibleAtasco) {
            alertas.add(getString(R.string.alert_jam));
            urgente = true;
        }

        if (alertas.isEmpty()) {
            alertBanner.setVisibility(View.GONE);
            return;
        }

        alertBanner.setVisibility(View.VISIBLE);
        int bgColor = ContextCompat.getColor(requireContext(), urgente ? R.color.alert_danger_bg : R.color.alert_warning_bg);
        int textColor = ContextCompat.getColor(requireContext(), urgente ? R.color.alert_danger : R.color.alert_warning);
        if (alertBanner.getBackground() != null) alertBanner.getBackground().mutate().setTint(bgColor);

        if (alertBanner instanceof LinearLayout) {
            TextView label = alertBanner.findViewWithTag("alertLabel");
            if (label == null) {
                label = new TextView(requireContext());
                label.setTag("alertLabel");
                label.setTextSize(13);
                ((LinearLayout) alertBanner).addView(label);
            }
            label.setTextColor(textColor);
            label.setText(android.text.TextUtils.join(" · ", alertas));
        }
    }

    /**
     * Proyecta los días restantes con la bajada de nivel de los últimos 3 días (lecturas en Room).
     */
    private void estimateDaysRemaining(int nivelActual) {
        AppDatabase db = AppDatabase.getInstance(requireContext());
        executor.execute(() -> {
            long haceTresDias = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3);
            SensorReading masAntigua = db.sensorReadingDao().getOldestSince(haceTresDias);
            if (!isAdded()) return;

            double horas = masAntigua == null ? 0 : (System.currentTimeMillis() - masAntigua.timestampMillis) / 3_600_000.0;
            double perdido = masAntigua == null ? 0 : masAntigua.nivelTolvaPct - nivelActual;

            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                // Menos de 6 h de historial, o el nivel no bajó (se rellenó): no hay tasa confiable.
                if (masAntigua == null || horas < 6 || perdido <= 0) {
                    tvDaysRemaining.setVisibility(View.GONE);
                    return;
                }
                double dias = (nivelActual / (perdido / horas)) / 24.0;
                String prefijo = getString(R.string.label_days_remaining) + ": ";
                tvDaysRemaining.setVisibility(View.VISIBLE);
                tvDaysRemaining.setText(prefijo + (dias < 1
                        ? getString(R.string.days_remaining_less_than_one)
                        : getString(R.string.days_remaining_format, (int) Math.round(dias))));
            });
        });
    }

    private void setConnected(boolean connected) {
        tvConnectionStatus.setText(connected ? R.string.status_connected : R.string.status_disconnected);
        dotStatus.setBackgroundResource(R.drawable.dot_status);
        dotStatus.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(),
                connected ? R.color.alert_ok : R.color.alert_danger)));
    }

    private void feedNow() {
        String baseUrl = prefsManager.getBaseUrl();
        if (baseUrl == null) return;

        btnFeedNow.setEnabled(false);
        btnFeedNow.setText(R.string.feeding_in_progress);

        RetrofitClient.getApi(baseUrl).feedNow(selectedPortions).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> response) {
                if (!isAdded()) return;
                // El ESP32 responde antes de mover el servo; el nivel nuevo y el registro de la
                // alimentación están listos unos segundos después.
                handler.postDelayed(() -> {
                    if (!isAdded()) return;
                    btnFeedNow.setEnabled(true);
                    btnFeedNow.setText(R.string.btn_feed_now);
                    lastSyncAttempt = 0;
                    refreshStatus();
                }, REFRESH_AFTER_FEED_MS);
            }

            @Override
            public void onFailure(@NonNull Call<Void> call, @NonNull Throwable t) {
                if (!isAdded()) return;
                btnFeedNow.setEnabled(true);
                btnFeedNow.setText(R.string.btn_feed_now);
                Toast.makeText(requireContext(), R.string.feed_error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Mide el nivel a pedido (por ejemplo justo después de rellenar la tolva). */
    private void measureLevelNow() {
        String baseUrl = prefsManager.getBaseUrl();
        if (baseUrl == null) return;

        btnMeasureLevel.setEnabled(false);
        btnMeasureLevel.setText(R.string.measuring);
        FeederApiService api = RetrofitClient.getApi(baseUrl);
        api.testNivel().enqueue(new Callback<LevelDiagnostic>() {
            @Override
            public void onResponse(@NonNull Call<LevelDiagnostic> call, @NonNull Response<LevelDiagnostic> response) {
                if (!isAdded()) return;
                btnMeasureLevel.setEnabled(true);
                btnMeasureLevel.setText(R.string.btn_measure_level);
                LevelDiagnostic t = response.body();
                if (response.isSuccessful() && t != null && !t.funciona() && t.mensaje != null) {
                    Toast.makeText(requireContext(), t.mensaje, Toast.LENGTH_LONG).show();
                }
                refreshStatus();
            }

            @Override
            public void onFailure(@NonNull Call<LevelDiagnostic> call, @NonNull Throwable t) {
                if (!isAdded()) return;
                btnMeasureLevel.setEnabled(true);
                btnMeasureLevel.setText(R.string.btn_measure_level);
                Toast.makeText(requireContext(), R.string.measure_error, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
