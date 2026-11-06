package com.juanchiz.fishfeeder.network;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Retrofit apunta a la IP local del ESP32 (misma red WiFi).
 * Se reconstruye cada vez que cambia la IP guardada en PrefsManager.
 */
public class RetrofitClient {

    private static FeederApiService apiService;
    private static String currentBaseUrl;

    public static synchronized FeederApiService getApi(String baseUrl) {
        if (apiService == null || !baseUrl.equals(currentBaseUrl)) {
            // 15 s de lectura: alimentar varias porciones o probar un sensor que no responde bloquea
            // al ESP32 unos segundos antes de contestar.
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(8, TimeUnit.SECONDS)
                    .build();

            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();

            apiService = retrofit.create(FeederApiService.class);
            currentBaseUrl = baseUrl;
        }
        return apiService;
    }

    /** Fuerza la reconstrucción del cliente, por ejemplo tras desconectar y reconectar con otra IP. */
    public static synchronized void reset() {
        apiService = null;
        currentBaseUrl = null;
    }
}
