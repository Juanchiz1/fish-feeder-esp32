package com.juanchiz.fishfeeder.ui.stats;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.DateValidatorPointBackward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.juanchiz.fishfeeder.R;
import com.juanchiz.fishfeeder.data.AppDatabase;
import com.juanchiz.fishfeeder.data.FeedingEvent;
import com.juanchiz.fishfeeder.data.HistorySync;
import com.juanchiz.fishfeeder.data.PrefsManager;
import com.juanchiz.fishfeeder.data.SensorReading;
import com.juanchiz.fishfeeder.ui.UiFormat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Estadísticas por día, construidas con lo que HistorySync baja del ESP32 a Room:
 * selector de día (flechas, calendario, o tocando una barra de la semana), resumen del día,
 * gráficas por hora con los umbrales como líneas de referencia y la lista de alimentaciones.
 */
public class StatsFragment extends Fragment {

    private static final long MINUTO = 60_000L;
    private static final long HORA = 60 * MINUTO;
    /** Si entre dos lecturas pasan más de esto, la línea se corta en vez de unirlas. */
    private static final long HUECO_MAX_MS = 20 * MINUTO;
    private static final int MINUTOS_POR_LECTURA = 5;
    private static final int DIAS_SEMANA = 7;
    /** Una subida de nivel mayor a esto entre dos lecturas se cuenta como relleno. */
    private static final int SUBIDA_RELLENO_PCT = 5;

    /** Lecturas de Room (rápidas). */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** Sincronización con el ESP32 (puede tardar hasta el timeout si no responde). */
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private PrefsManager prefs;

    private SwipeRefreshLayout swipeRefresh;
    private TextView tvSyncInfo, tvSelectedDay, tvDayEmpty, tvDayAlerts, tvCoverage;
    private MaterialButton btnPrevDay, btnNextDay, btnToday;
    private View containerDay;
    private TextView tvSumFeedings, tvSumFeedingsSub, tvSumLevel, tvSumLevelSub;
    private TextView tvSumHumidity, tvSumHumiditySub, tvSumTemp, tvSumTempSub;
    private TextView tvHumidityChartHint, tvTemperatureChartHint, tvHistoryTitle, tvHistoryEmpty;
    private LineChart chartHumidity, chartTemperature, chartLevel;
    private BarChart chartWeek;
    private LinearLayout containerFeedingHistory;

    /** Medianoche local del día que se está mostrando. */
    private long diaSeleccionado;
    /** Inicio de cada barra de la semana, para saber qué día se tocó. */
    private long[] diasSemana = new long[0];

    // ------------------------------------------------------------------ ciclo de vida

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_stats, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = new PrefsManager(requireContext());

        swipeRefresh = view.findViewById(R.id.swipeRefreshStats);
        tvSyncInfo = view.findViewById(R.id.tvSyncInfo);
        tvSelectedDay = view.findViewById(R.id.tvSelectedDay);
        btnPrevDay = view.findViewById(R.id.btnPrevDay);
        btnNextDay = view.findViewById(R.id.btnNextDay);
        btnToday = view.findViewById(R.id.btnToday);
        tvDayEmpty = view.findViewById(R.id.tvDayEmpty);
        containerDay = view.findViewById(R.id.containerDay);
        tvSumFeedings = view.findViewById(R.id.tvSumFeedings);
        tvSumFeedingsSub = view.findViewById(R.id.tvSumFeedingsSub);
        tvSumLevel = view.findViewById(R.id.tvSumLevel);
        tvSumLevelSub = view.findViewById(R.id.tvSumLevelSub);
        tvSumHumidity = view.findViewById(R.id.tvSumHumidity);
        tvSumHumiditySub = view.findViewById(R.id.tvSumHumiditySub);
        tvSumTemp = view.findViewById(R.id.tvSumTemp);
        tvSumTempSub = view.findViewById(R.id.tvSumTempSub);
        tvDayAlerts = view.findViewById(R.id.tvDayAlerts);
        tvHumidityChartHint = view.findViewById(R.id.tvHumidityChartHint);
        tvTemperatureChartHint = view.findViewById(R.id.tvTemperatureChartHint);
        tvHistoryTitle = view.findViewById(R.id.tvHistoryTitle);
        tvHistoryEmpty = view.findViewById(R.id.tvHistoryEmpty);
        tvCoverage = view.findViewById(R.id.tvCoverage);
        chartHumidity = view.findViewById(R.id.chartHumidity);
        chartTemperature = view.findViewById(R.id.chartTemperature);
        chartLevel = view.findViewById(R.id.chartLevel);
        chartWeek = view.findViewById(R.id.chartWeek);
        containerFeedingHistory = view.findViewById(R.id.containerFeedingHistory);

        estiloGraficaDia(chartHumidity);
        estiloGraficaDia(chartTemperature);
        estiloGraficaDia(chartLevel);
        estiloGraficaSemana();

        diaSeleccionado = inicioDelDia(System.currentTimeMillis());

        btnPrevDay.setOnClickListener(v -> seleccionarDia(sumarDias(diaSeleccionado, -1)));
        btnNextDay.setOnClickListener(v -> seleccionarDia(sumarDias(diaSeleccionado, 1)));
        btnToday.setOnClickListener(v -> seleccionarDia(inicioDelDia(System.currentTimeMillis())));
        view.findViewById(R.id.btnPickDay).setOnClickListener(v -> abrirCalendario());
        swipeRefresh.setOnRefreshListener(this::sincronizarYCargar);

        cargar();
        sincronizarYCargar();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        syncExecutor.shutdown();
    }

    // ------------------------------------------------------------------ navegación

    private void seleccionarDia(long inicio) {
        long hoy = inicioDelDia(System.currentTimeMillis());
        diaSeleccionado = Math.min(inicio, hoy);
        cargar();
    }

    private void abrirCalendario() {
        // MaterialDatePicker trabaja en UTC: se le pasa la misma fecha "civil" a medianoche UTC.
        Calendar local = Calendar.getInstance();
        local.setTimeInMillis(diaSeleccionado);
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH));

        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.stats_pick_day_title)
                .setSelection(utc.getTimeInMillis())
                .setCalendarConstraints(new CalendarConstraints.Builder()
                        .setValidator(DateValidatorPointBackward.now())
                        .build())
                .build();
        picker.addOnPositiveButtonClickListener(seleccion -> {
            Calendar elegido = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            elegido.setTimeInMillis(seleccion);
            Calendar dia = Calendar.getInstance();
            dia.clear();
            dia.set(elegido.get(Calendar.YEAR), elegido.get(Calendar.MONTH), elegido.get(Calendar.DAY_OF_MONTH));
            seleccionarDia(dia.getTimeInMillis());
        });
        picker.show(getParentFragmentManager(), "selector_dia");
    }

    // ------------------------------------------------------------------ datos

    /** Todo lo que se necesita para pintar un día, leído de Room en segundo plano. */
    private static class DatosDia {
        long inicio, fin;
        List<SensorReading> lecturas;
        List<FeedingEvent> alimentaciones;
        SensorReading nivelPrevio;          // última lectura con nivel antes del día
        long[] semanaInicios;
        int[] semanaPorciones;
        int[] semanaAlimentaciones;
    }

    private void sincronizarYCargar() {
        Context appContext = requireContext().getApplicationContext();
        swipeRefresh.setRefreshing(true);
        syncExecutor.execute(() -> {
            HistorySync.syncBlocking(appContext);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                swipeRefresh.setRefreshing(false);
                cargar();
            });
        });
    }

    private void cargar() {
        final long inicio = diaSeleccionado;
        AppDatabase db = AppDatabase.getInstance(requireContext());
        executor.execute(() -> {
            DatosDia d = new DatosDia();
            d.inicio = inicio;
            d.fin = sumarDias(inicio, 1);
            d.lecturas = db.sensorReadingDao().getBetween(d.inicio, d.fin);
            d.alimentaciones = db.feedingEventDao().getBetween(d.inicio, d.fin);
            d.nivelPrevio = db.sensorReadingDao().getLastWithLevelBefore(d.inicio);

            // Ventana de 7 días alrededor del seleccionado (sin pasar de hoy).
            long hoy = inicioDelDia(System.currentTimeMillis());
            long finSemana = Math.min(hoy, sumarDias(inicio, 3));
            long inicioSemana = sumarDias(finSemana, -(DIAS_SEMANA - 1));
            d.semanaInicios = new long[DIAS_SEMANA];
            d.semanaPorciones = new int[DIAS_SEMANA];
            d.semanaAlimentaciones = new int[DIAS_SEMANA];
            for (int i = 0; i < DIAS_SEMANA; i++) d.semanaInicios[i] = sumarDias(inicioSemana, i);
            for (FeedingEvent f : db.feedingEventDao().getBetween(inicioSemana, sumarDias(finSemana, 1))) {
                for (int i = DIAS_SEMANA - 1; i >= 0; i--) {
                    if (f.timestampMillis >= d.semanaInicios[i]) {
                        d.semanaPorciones[i] += f.porciones;
                        d.semanaAlimentaciones[i]++;
                        break;
                    }
                }
            }

            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (isAdded() && d.inicio == diaSeleccionado) pintar(d);
            });
        });
    }

    // ------------------------------------------------------------------ pintar

    private void pintar(DatosDia d) {
        long hoy = inicioDelDia(System.currentTimeMillis());
        boolean esHoy = d.inicio == hoy;
        tvSelectedDay.setText(tituloDia(d.inicio, hoy));
        btnNextDay.setEnabled(!esHoy);
        btnNextDay.setAlpha(esHoy ? 0.3f : 1f);
        btnToday.setVisibility(esHoy ? View.GONE : View.VISIBLE);

        long sync = prefs.getLastSyncMillis();
        tvSyncInfo.setText(sync == 0 ? getString(R.string.stats_never_synced)
                : getString(R.string.stats_synced, UiFormat.hace(requireContext(), (System.currentTimeMillis() - sync) / 1000)));

        pintarSemana(d);

        if (d.lecturas.isEmpty() && d.alimentaciones.isEmpty()) {
            tvDayEmpty.setVisibility(View.VISIBLE);
            tvDayEmpty.setText(esHoy ? R.string.stats_day_empty_today : R.string.stats_day_empty);
            containerDay.setVisibility(View.GONE);
            return;
        }
        tvDayEmpty.setVisibility(View.GONE);
        containerDay.setVisibility(View.VISIBLE);

        pintarResumen(d, esHoy);
        pintarGraficaHumedad(d);
        pintarGraficaTemperatura(d);
        pintarGraficaNivel(d);
        pintarAlimentaciones(d);
    }

    private void pintarResumen(DatosDia d, boolean esHoy) {
        // Alimentaciones
        int porciones = 0, manuales = 0;
        for (FeedingEvent f : d.alimentaciones) {
            porciones += f.porciones;
            if (f.manual) manuales++;
        }
        tvSumFeedings.setText(String.valueOf(d.alimentaciones.size()));
        tvSumFeedingsSub.setText(getString(R.string.stats_sum_feedings_sub, porciones, manuales));

        // Humedad / temperatura
        int n = 0, sobreHumedad = 0, fueraTemp = 0, fallasNivel = 0;
        double sumaH = 0, sumaT = 0;
        double minH = Double.MAX_VALUE, maxH = -Double.MAX_VALUE, minT = Double.MAX_VALUE, maxT = -Double.MAX_VALUE;
        float humMax = prefs.getCachedHumidityMax();
        float tMin = prefs.getCachedTempMin();
        float tMax = prefs.getCachedTempMax();
        for (SensorReading r : d.lecturas) {
            if (r.nivelEstado == SensorReading.NIVEL_ERROR || r.nivelEstado == SensorReading.NIVEL_SIN_SENSOR) fallasNivel++;
            if (!r.dhtOk) continue;
            n++;
            sumaH += r.humedad;
            sumaT += r.temperatura;
            minH = Math.min(minH, r.humedad);
            maxH = Math.max(maxH, r.humedad);
            minT = Math.min(minT, r.temperatura);
            maxT = Math.max(maxT, r.temperatura);
            if (!Float.isNaN(humMax) && r.humedad >= humMax) sobreHumedad++;
            if ((!Float.isNaN(tMax) && r.temperatura >= tMax) || (!Float.isNaN(tMin) && r.temperatura <= tMin)) fueraTemp++;
        }
        if (n > 0) {
            tvSumHumidity.setText(String.format(Locale.getDefault(), "%.0f%%", sumaH / n));
            tvSumHumiditySub.setText(getString(R.string.stats_min_max_pct, minH, maxH));
            tvSumTemp.setText(String.format(Locale.getDefault(), "%.1f°", sumaT / n));
            tvSumTempSub.setText(getString(R.string.stats_min_max_deg, minT, maxT));
        } else {
            tvSumHumidity.setText("--");
            tvSumHumiditySub.setText(R.string.stats_no_dht_data);
            tvSumTemp.setText("--");
            tvSumTempSub.setText(R.string.stats_no_dht_data);
        }

        // Nivel: inicio, final, consumo y rellenos a lo largo del día
        List<Integer> serie = serieNivel(d);
        if (serie.isEmpty()) {
            tvSumLevel.setText("--");
            tvSumLevelSub.setText(R.string.stats_no_level_data);
        } else {
            int consumo = 0, rellenos = 0;
            for (int i = 1; i < serie.size(); i++) {
                int delta = serie.get(i) - serie.get(i - 1);
                if (delta < 0) consumo -= delta;
                else if (delta > SUBIDA_RELLENO_PCT) rellenos++;
            }
            tvSumLevel.setText(getString(R.string.stats_level_value, serie.get(serie.size() - 1)));
            String sub = getString(R.string.stats_level_sub, serie.get(0), consumo);
            if (rellenos > 0) sub += " · " + getResources().getQuantityString(R.plurals.stats_refills, rellenos, rellenos);
            tvSumLevelSub.setText(sub);
        }

        // Alertas del día
        List<String> alertas = new ArrayList<>();
        if (sobreHumedad > 0) alertas.add(getString(R.string.stats_alert_humidity, duracion(sobreHumedad * MINUTOS_POR_LECTURA)));
        if (fueraTemp > 0) alertas.add(getString(R.string.stats_alert_temperature, duracion(fueraTemp * MINUTOS_POR_LECTURA)));
        if (fallasNivel > 0) alertas.add(getResources().getQuantityString(R.plurals.stats_alert_level_sensor, fallasNivel, fallasNivel));
        tvDayAlerts.setVisibility(alertas.isEmpty() ? View.GONE : View.VISIBLE);
        tvDayAlerts.setText(TextUtils.join("\n", alertas));

        // Cobertura: cuántas lecturas de 5 min hay respecto a las esperadas
        long minutosDia = esHoy ? (System.currentTimeMillis() - d.inicio) / MINUTO : (d.fin - d.inicio) / MINUTO;
        long esperadas = Math.max(1, minutosDia / MINUTOS_POR_LECTURA);
        int cobertura = (int) Math.min(100, Math.round(d.lecturas.size() * 100.0 / esperadas));
        tvCoverage.setText(getString(R.string.stats_coverage, d.lecturas.size(), cobertura));
    }

    /** Niveles conocidos del día en orden: el previo, las lecturas y lo medido en cada alimentación. */
    private List<Integer> serieNivel(DatosDia d) {
        List<long[]> puntos = new ArrayList<>(); // {timestamp, nivel}
        if (d.nivelPrevio != null) puntos.add(new long[]{d.inicio, d.nivelPrevio.nivelTolvaPct});
        for (SensorReading r : d.lecturas) {
            if (r.nivelConocido()) puntos.add(new long[]{r.timestampMillis, r.nivelTolvaPct});
        }
        for (FeedingEvent f : d.alimentaciones) {
            if (f.nivelDespues >= 0) puntos.add(new long[]{f.timestampMillis, f.nivelDespues});
        }
        puntos.sort((a, b) -> Long.compare(a[0], b[0]));
        List<Integer> serie = new ArrayList<>();
        for (long[] p : puntos) {
            // Las lecturas cada 5 min repiten el último nivel medido: se guardan solo los cambios.
            if (serie.isEmpty() || serie.get(serie.size() - 1) != (int) p[1]) serie.add((int) p[1]);
        }
        return serie;
    }

    private void pintarGraficaHumedad(DatosDia d) {
        float humMax = prefs.getCachedHumidityMax();
        List<ILineDataSet> sets = segmentos(d, r -> r.dhtOk ? (float) r.humedad : null, R.color.brand_secondary, false);
        YAxis y = chartHumidity.getAxisLeft();
        y.removeAllLimitLines();
        y.setAxisMinimum(0f);
        y.setAxisMaximum(100f);
        if (!Float.isNaN(humMax)) {
            y.addLimitLine(lineaUmbral(humMax, getString(R.string.stats_limit_max, numero(humMax), "%")));
            tvHumidityChartHint.setText(getString(R.string.stats_humidity_hint, numero(humMax)));
        } else {
            tvHumidityChartHint.setText(R.string.stats_chart_hint_generic);
        }
        mostrar(chartHumidity, sets);
    }

    private void pintarGraficaTemperatura(DatosDia d) {
        float tMin = prefs.getCachedTempMin();
        float tMax = prefs.getCachedTempMax();
        List<ILineDataSet> sets = segmentos(d, r -> r.dhtOk ? (float) r.temperatura : null, R.color.alert_warning, false);
        YAxis y = chartTemperature.getAxisLeft();
        y.removeAllLimitLines();
        y.resetAxisMinimum();
        y.resetAxisMaximum();
        if (!Float.isNaN(tMin) && !Float.isNaN(tMax)) {
            y.addLimitLine(lineaUmbral(tMax, getString(R.string.stats_limit_max, numero(tMax), "°")));
            y.addLimitLine(lineaUmbral(tMin, getString(R.string.stats_limit_min, numero(tMin), "°")));
            tvTemperatureChartHint.setText(getString(R.string.stats_temperature_hint, numero(tMin), numero(tMax)));
            // Que las líneas de umbral siempre queden dentro de la gráfica.
            float minDatos = tMin, maxDatos = tMax;
            for (SensorReading r : d.lecturas) {
                if (!r.dhtOk) continue;
                minDatos = Math.min(minDatos, (float) r.temperatura);
                maxDatos = Math.max(maxDatos, (float) r.temperatura);
            }
            y.setAxisMinimum((float) Math.floor(minDatos - 1));
            y.setAxisMaximum((float) Math.ceil(maxDatos + 1));
        } else {
            tvTemperatureChartHint.setText(R.string.stats_chart_hint_generic);
        }
        mostrar(chartTemperature, sets);
    }

    private void pintarGraficaNivel(DatosDia d) {
        List<ILineDataSet> sets = new ArrayList<>();
        List<Entry> puntos = new ArrayList<>();
        if (d.nivelPrevio != null) puntos.add(new Entry(0f, d.nivelPrevio.nivelTolvaPct));
        for (SensorReading r : d.lecturas) {
            if (r.nivelConocido()) puntos.add(new Entry(horaDelDia(r.timestampMillis, d.inicio), r.nivelTolvaPct));
        }
        if (!puntos.isEmpty()) {
            LineDataSet set = new LineDataSet(puntos, "nivel");
            estiloSerie(set, R.color.brand_primary, true);
            set.setMode(LineDataSet.Mode.STEPPED);
            sets.add(set);
        }

        // Cada alimentación como línea vertical punteada.
        XAxis x = chartLevel.getXAxis();
        x.removeAllLimitLines();
        int color = color(R.color.text_secondary_light);
        for (FeedingEvent f : d.alimentaciones) {
            LimitLine l = new LimitLine(horaDelDia(f.timestampMillis, d.inicio), "");
            l.setLineColor(ColorUtils.setAlphaComponent(color, 140));
            l.setLineWidth(1f);
            l.enableDashedLine(6f, 6f, 0f);
            x.addLimitLine(l);
        }
        x.setDrawLimitLinesBehindData(true);

        YAxis y = chartLevel.getAxisLeft();
        y.setAxisMinimum(0f);
        y.setAxisMaximum(100f);
        mostrar(chartLevel, sets);
    }

    private void pintarAlimentaciones(DatosDia d) {
        while (containerFeedingHistory.getChildCount() > 1) {
            containerFeedingHistory.removeViewAt(containerFeedingHistory.getChildCount() - 1);
        }
        tvHistoryTitle.setText(getString(R.string.stats_history_title_day, d.alimentaciones.size()));
        if (d.alimentaciones.isEmpty()) {
            tvHistoryEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvHistoryEmpty.setVisibility(View.GONE);

        SimpleDateFormat hora = new SimpleDateFormat("HH:mm", Locale.getDefault());
        for (FeedingEvent f : d.alimentaciones) {
            LinearLayout fila = new LinearLayout(requireContext());
            fila.setOrientation(LinearLayout.HORIZONTAL);
            fila.setGravity(Gravity.CENTER_VERTICAL);
            fila.setPadding(0, dp(8), 0, dp(8));

            TextView tvHora = texto(hora.format(new Date(f.timestampMillis)), 14, R.color.text_primary_light, true);
            fila.addView(tvHora, new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView tvOrigen = texto(getString(f.manual ? R.string.feeding_manual_label : R.string.feeding_scheduled_label),
                    11, f.manual ? R.color.brand_secondary : R.color.brand_primary, true);
            tvOrigen.setBackgroundResource(R.drawable.bg_status_pill);
            tvOrigen.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(color(f.manual ? R.color.brand_secondary : R.color.brand_primary), 36)));
            tvOrigen.setPadding(dp(8), dp(2), dp(8), dp(2));
            fila.addView(tvOrigen);

            TextView tvPorciones = texto(getResources().getQuantityString(R.plurals.feeding_portions, f.porciones, f.porciones),
                    13, R.color.text_primary_light, false);
            tvPorciones.setPadding(dp(10), 0, 0, 0);
            fila.addView(tvPorciones, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            String nivel = f.nivelAntes >= 0 && f.nivelDespues >= 0
                    ? getString(R.string.stats_feeding_level, f.nivelAntes, f.nivelDespues)
                    : f.nivelDespues >= 0 ? getString(R.string.stats_level_value, f.nivelDespues) : "—";
            fila.addView(texto(nivel, 12, R.color.text_secondary_light, false));

            containerFeedingHistory.addView(fila);
        }
    }

    private void pintarSemana(DatosDia d) {
        diasSemana = d.semanaInicios;
        List<BarEntry> barras = new ArrayList<>();
        List<Integer> colores = new ArrayList<>();
        int base = color(R.color.brand_primary);
        for (int i = 0; i < DIAS_SEMANA; i++) {
            barras.add(new BarEntry(i, d.semanaPorciones[i]));
            // El día seleccionado en color pleno, los demás atenuados.
            colores.add(d.semanaInicios[i] == d.inicio ? base : ColorUtils.setAlphaComponent(base, 90));
        }
        BarDataSet set = new BarDataSet(barras, "porciones");
        set.setColors(colores);
        set.setHighLightAlpha(0);
        set.setValueTextColor(color(R.color.text_secondary_light));
        set.setValueTextSize(10f);
        set.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return value > 0 ? String.valueOf((int) value) : "";
            }
        });
        BarData data = new BarData(set);
        data.setBarWidth(0.6f);

        final SimpleDateFormat etiqueta = new SimpleDateFormat("EEE d", Locale.getDefault());
        final long[] inicios = d.semanaInicios;
        chartWeek.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override
            public String getAxisLabel(float value, AxisBase axis) {
                int i = Math.round(value);
                return i >= 0 && i < inicios.length ? etiqueta.format(new Date(inicios[i])) : "";
            }
        });
        chartWeek.setData(data);
        chartWeek.highlightValues(null);
        chartWeek.invalidate();
    }

    // ------------------------------------------------------------------ helpers de gráficas

    private interface Valor {
        @Nullable Float de(SensorReading r);
    }

    /** Parte las lecturas en tramos continuos: si faltan datos más de 20 min, la línea se corta. */
    private List<ILineDataSet> segmentos(DatosDia d, Valor valor, @ColorRes int colorRes, boolean relleno) {
        List<ILineDataSet> sets = new ArrayList<>();
        List<Entry> actual = new ArrayList<>();
        long anterior = -1;
        for (SensorReading r : d.lecturas) {
            Float v = valor.de(r);
            if (v == null) continue;
            if (anterior >= 0 && r.timestampMillis - anterior > HUECO_MAX_MS && !actual.isEmpty()) {
                sets.add(nuevaSerie(actual, colorRes, relleno));
                actual = new ArrayList<>();
            }
            actual.add(new Entry(horaDelDia(r.timestampMillis, d.inicio), v));
            anterior = r.timestampMillis;
        }
        if (!actual.isEmpty()) sets.add(nuevaSerie(actual, colorRes, relleno));
        return sets;
    }

    private LineDataSet nuevaSerie(List<Entry> puntos, @ColorRes int colorRes, boolean relleno) {
        LineDataSet set = new LineDataSet(puntos, "");
        estiloSerie(set, colorRes, relleno);
        // Un tramo de un solo punto no se ve sin círculo.
        set.setDrawCircles(puntos.size() == 1);
        return set;
    }

    private void estiloSerie(LineDataSet set, @ColorRes int colorRes, boolean relleno) {
        int c = color(colorRes);
        set.setColor(c);
        set.setCircleColor(c);
        set.setCircleRadius(2.5f);
        set.setDrawCircleHole(false);
        set.setDrawCircles(false);
        set.setLineWidth(2f);
        set.setDrawValues(false);
        set.setHighlightEnabled(false);
        set.setDrawFilled(relleno);
        set.setFillColor(c);
        set.setFillAlpha(35);
    }

    private void mostrar(LineChart chart, List<ILineDataSet> sets) {
        if (sets.isEmpty()) {
            chart.clear();
            chart.setNoDataText(getString(R.string.stats_chart_no_data));
            chart.setNoDataTextColor(color(R.color.text_secondary_light));
        } else {
            chart.setData(new LineData(sets));
            chart.fitScreen();
        }
        chart.invalidate();
    }

    private LimitLine lineaUmbral(float valor, String etiqueta) {
        LimitLine l = new LimitLine(valor, etiqueta);
        int c = color(R.color.alert_danger);
        l.setLineColor(ColorUtils.setAlphaComponent(c, 170));
        l.setLineWidth(1f);
        l.enableDashedLine(10f, 8f, 0f);
        l.setTextColor(c);
        l.setTextSize(10f);
        l.setLabelPosition(LimitLine.LimitLabelPosition.RIGHT_TOP);
        return l;
    }

    private void estiloGraficaDia(LineChart chart) {
        int texto = color(R.color.text_secondary_light);
        int grilla = color(R.color.divider_light);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.getAxisRight().setEnabled(false);
        chart.setScaleYEnabled(false);
        chart.setPinchZoom(false);
        chart.setDoubleTapToZoomEnabled(false);
        chart.setHighlightPerTapEnabled(false);
        chart.setNoDataText(getString(R.string.stats_chart_no_data));
        chart.setNoDataTextColor(texto);
        chart.setExtraBottomOffset(4f);

        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setAxisMinimum(0f);
        x.setAxisMaximum(24f);
        x.setLabelCount(9, true);
        x.setTextColor(texto);
        x.setGridColor(grilla);
        x.setValueFormatter(new ValueFormatter() {
            @Override
            public String getAxisLabel(float value, AxisBase axis) {
                return String.format(Locale.getDefault(), "%02d:00", Math.round(value));
            }
        });

        YAxis y = chart.getAxisLeft();
        y.setTextColor(texto);
        y.setGridColor(grilla);
        y.setDrawLimitLinesBehindData(true);
    }

    private void estiloGraficaSemana() {
        int texto = color(R.color.text_secondary_light);
        chartWeek.getDescription().setEnabled(false);
        chartWeek.getLegend().setEnabled(false);
        chartWeek.getAxisRight().setEnabled(false);
        chartWeek.setScaleEnabled(false);
        chartWeek.setDoubleTapToZoomEnabled(false);
        chartWeek.setFitBars(true);
        chartWeek.setNoDataTextColor(texto);

        XAxis x = chartWeek.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawGridLines(false);
        x.setGranularity(1f);
        x.setTextColor(texto);

        YAxis y = chartWeek.getAxisLeft();
        y.setAxisMinimum(0f);
        y.setGranularity(1f);
        y.setDrawGridLines(false);
        y.setDrawLabels(false);
        y.setDrawAxisLine(false);

        chartWeek.setOnChartValueSelectedListener(new OnChartValueSelectedListener() {
            @Override
            public void onValueSelected(Entry e, Highlight h) {
                int i = Math.round(e.getX());
                if (i >= 0 && i < diasSemana.length) seleccionarDia(diasSemana[i]);
            }

            @Override
            public void onNothingSelected() {
            }
        });
    }

    // ------------------------------------------------------------------ utilidades

    private String tituloDia(long inicio, long hoy) {
        SimpleDateFormat f = new SimpleDateFormat("EEE d 'de' MMMM", Locale.getDefault());
        String fecha = f.format(new Date(inicio));
        if (inicio == hoy) return getString(R.string.stats_day_today, fecha);
        if (inicio == sumarDias(hoy, -1)) return getString(R.string.stats_day_yesterday, fecha);
        Calendar c = Calendar.getInstance();
        int anioActual = c.get(Calendar.YEAR);
        c.setTimeInMillis(inicio);
        return c.get(Calendar.YEAR) == anioActual ? fecha : fecha + " " + c.get(Calendar.YEAR);
    }

    /** 65 -> "65", 30.5 -> "30,5" (según el idioma del celular). */
    private static String numero(float v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.format(Locale.getDefault(), "%.1f", v);
    }

    private String duracion(int minutos) {
        if (minutos < 60) return getString(R.string.duration_minutes, minutos);
        return getString(R.string.duration_hours_minutes, minutos / 60, minutos % 60);
    }

    private static float horaDelDia(long timestamp, long inicioDia) {
        return (timestamp - inicioDia) / (float) HORA;
    }

    private static long inicioDelDia(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Suma días de calendario (respeta cambios de horario, a diferencia de sumar 24 h). */
    private static long sumarDias(long inicioDia, int dias) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(inicioDia);
        c.add(Calendar.DAY_OF_MONTH, dias);
        return c.getTimeInMillis();
    }

    private TextView texto(String s, int sp, @ColorRes int colorRes, boolean negrita) {
        TextView tv = new TextView(requireContext());
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color(colorRes));
        if (negrita) tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private int color(@ColorRes int res) {
        return ContextCompat.getColor(requireContext(), res);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
