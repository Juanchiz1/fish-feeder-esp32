package com.juanchiz.fishfeeder.ui.settings;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.juanchiz.fishfeeder.R;
import com.juanchiz.fishfeeder.data.PrefsManager;
import com.juanchiz.fishfeeder.model.PauseState;
import com.juanchiz.fishfeeder.model.TimeSyncRequest;
import com.juanchiz.fishfeeder.network.FeederApiService;
import com.juanchiz.fishfeeder.network.RetrofitClient;
import com.juanchiz.fishfeeder.ui.MainActivity;
import com.juanchiz.fishfeeder.ui.diagnostics.DiagnosticsActivity;
import com.juanchiz.fishfeeder.ui.thresholds.ThresholdsActivity;
import com.juanchiz.fishfeeder.ui.wifi.WifiConfigActivity;

import java.util.Calendar;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Muestra la IP conectada, permite ajustar notificaciones y desconectar el dispensador. */
public class SettingsFragment extends Fragment {

    private PrefsManager prefsManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefsManager = new PrefsManager(requireContext());

        TextView tvCurrentIp = view.findViewById(R.id.tvCurrentIp);
        MaterialButton btnDisconnect = view.findViewById(R.id.btnDisconnect);
        MaterialButton btnConnectDevice = view.findViewById(R.id.btnConnectDevice);
        MaterialButton btnConfigureWifi = view.findViewById(R.id.btnConfigureWifi);
        MaterialButton btnSyncTime = view.findViewById(R.id.btnSyncTime);
        MaterialButton btnConfigureThresholds = view.findViewById(R.id.btnConfigureThresholds);
        MaterialButton btnDiagnostics = view.findViewById(R.id.btnDiagnostics);

        boolean isConnected = prefsManager.isConnected();
        tvCurrentIp.setText(isConnected ? prefsManager.getDeviceIp() : getString(R.string.not_connected_yet));
        btnDisconnect.setVisibility(isConnected ? View.VISIBLE : View.GONE);
        btnConnectDevice.setVisibility(isConnected ? View.GONE : View.VISIBLE);
        btnConnectDevice.setOnClickListener(v -> goToConnectScreen());

        // Reconfigurar/sincronizar solo tienen sentido si ya hay un dispensador conectado.
        btnConfigureWifi.setEnabled(isConnected);
        btnSyncTime.setEnabled(isConnected);
        btnConfigureThresholds.setEnabled(isConnected);
        btnDiagnostics.setEnabled(isConnected);
        btnConfigureWifi.setOnClickListener(v -> goToWifiReconfigure());
        btnSyncTime.setOnClickListener(v -> syncTime(btnSyncTime));
        btnConfigureThresholds.setOnClickListener(v ->
                startActivity(new android.content.Intent(requireContext(), ThresholdsActivity.class)));
        btnDiagnostics.setOnClickListener(v ->
                startActivity(new android.content.Intent(requireContext(), DiagnosticsActivity.class)));

        SwitchMaterial switchNotifyEmpty = view.findViewById(R.id.switchNotifyEmpty);
        SwitchMaterial switchNotifyHumidity = view.findViewById(R.id.switchNotifyHumidity);
        SwitchMaterial switchNotifyTemperature = view.findViewById(R.id.switchNotifyTemperature);
        SwitchMaterial switchNotifyDailySummary = view.findViewById(R.id.switchNotifyDailySummary);
        SwitchMaterial switchNotifyJam = view.findViewById(R.id.switchNotifyJam);
        switchNotifyEmpty.setChecked(prefsManager.isNotifyEmptyEnabled());
        switchNotifyHumidity.setChecked(prefsManager.isNotifyHumidityEnabled());
        switchNotifyTemperature.setChecked(prefsManager.isNotifyTemperatureEnabled());
        switchNotifyDailySummary.setChecked(prefsManager.isNotifyDailySummaryEnabled());
        switchNotifyJam.setChecked(prefsManager.isNotifyJamEnabled());

        switchNotifyEmpty.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefsManager.setNotifyEmptyEnabled(isChecked));
        switchNotifyHumidity.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefsManager.setNotifyHumidityEnabled(isChecked));
        switchNotifyTemperature.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefsManager.setNotifyTemperatureEnabled(isChecked));
        switchNotifyDailySummary.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefsManager.setNotifyDailySummaryEnabled(isChecked));
        switchNotifyJam.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefsManager.setNotifyJamEnabled(isChecked));

        SwitchMaterial switchVacationMode = view.findViewById(R.id.switchVacationMode);
        switchVacationMode.setEnabled(isConnected);
        if (isConnected) {
            loadVacationMode(switchVacationMode);
        }

        btnDisconnect.setOnClickListener(v -> confirmDisconnect());
    }

    /** El "modo vacaciones" vive en el ESP32 (no en el celular), así que se consulta su estado
     *  real al abrir la pantalla en vez de asumir lo último que se dejó localmente. */
    private void loadVacationMode(SwitchMaterial switchVacationMode) {
        String baseUrl = prefsManager.getBaseUrl();
        if (baseUrl == null) return;

        FeederApiService api = RetrofitClient.getApi(baseUrl);
        api.getPauseState().enqueue(new Callback<PauseState>() {
            @Override
            public void onResponse(Call<PauseState> call, Response<PauseState> response) {
                if (!isAdded() || !response.isSuccessful() || response.body() == null) return;
                switchVacationMode.setChecked(response.body().pausado);
                switchVacationMode.setOnCheckedChangeListener((buttonView, isChecked) ->
                        setVacationMode(isChecked));
            }

            @Override
            public void onFailure(Call<PauseState> call, Throwable t) {
                // Sin conexión momentánea: dejamos el switch como esté, no forzamos un estado.
            }
        });
    }

    private void setVacationMode(boolean pausado) {
        String baseUrl = prefsManager.getBaseUrl();
        if (baseUrl == null) return;

        FeederApiService api = RetrofitClient.getApi(baseUrl);
        api.setPauseState(new PauseState(pausado)).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(),
                        pausado ? R.string.vacation_mode_active_banner : R.string.vacation_mode_resumed,
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), R.string.vacation_mode_error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void confirmDisconnect() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.disconnect_confirm_title)
                .setMessage(R.string.disconnect_confirm_message)
                .setPositiveButton(R.string.accept, (dialog, which) -> disconnect())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void disconnect() {
        RetrofitClient.reset();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).restartToConnectScreen();
        }
    }

    /** Manda a la pantalla de Conectar sin borrar nada; a diferencia de disconnect(), esto es
     *  para cuando el usuario entró con "Entrar sin conectar" y ahora sí quiere ingresar la IP. */
    private void goToConnectScreen() {
        android.content.Intent intent = new android.content.Intent(requireContext(),
                com.juanchiz.fishfeeder.ui.connect.ConnectActivity.class);
        startActivity(intent);
    }

    /** El dispensador ya está conectado y en la misma red: se puede pedirle que cambie de WiFi
     *  directamente por su IP actual (ver WifiConfigActivity, modo MODE_RECONFIGURE). */
    private void goToWifiReconfigure() {
        android.content.Intent intent = new android.content.Intent(requireContext(), WifiConfigActivity.class);
        intent.putExtra(WifiConfigActivity.EXTRA_MODE, WifiConfigActivity.MODE_RECONFIGURE);
        startActivity(intent);
    }

    /** Envía la hora local del celular al DS3231 del ESP32 (ver handleSetTime en el firmware). */
    private void syncTime(MaterialButton button) {
        String baseUrl = prefsManager.getBaseUrl();
        if (baseUrl == null) return;

        button.setEnabled(false);
        Calendar ahora = Calendar.getInstance();
        TimeSyncRequest cuerpo = new TimeSyncRequest(
                ahora.get(Calendar.YEAR),
                ahora.get(Calendar.MONTH) + 1, // Calendar.MONTH empieza en 0
                ahora.get(Calendar.DAY_OF_MONTH),
                ahora.get(Calendar.HOUR_OF_DAY),
                ahora.get(Calendar.MINUTE),
                ahora.get(Calendar.SECOND));

        FeederApiService api = RetrofitClient.getApi(baseUrl);
        api.syncTime(cuerpo).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (!isAdded()) return;
                button.setEnabled(true);
                if (response.isSuccessful()) {
                    Toast.makeText(requireContext(), R.string.time_sync_success, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(), R.string.time_sync_error, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                if (!isAdded()) return;
                button.setEnabled(true);
                Toast.makeText(requireContext(), R.string.time_sync_error, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
