# App Android — Dispensador de alimento para peces (Java)

## Cómo abrirlo

1. Abre Android Studio → `Open` → selecciona la carpeta `App-Android` (la que contiene `settings.gradle`).
2. Deja que Gradle sincronice (descarga Retrofit, Room, WorkManager, Material Components y MPAndroidChart automáticamente — todo vía Maven Central y JitPack, ya declarados en `settings.gradle` y `app/build.gradle`).
3. Ejecuta en un celular Android real conectado por USB (con "Depuración USB" activada) o en un emulador — en ambos casos el celular/emulador debe estar en la **misma red WiFi** que el ESP32.

## Flujo de pantallas

| Pantalla | Qué hace | Endpoint del ESP32 que usa |
| --- | --- | --- |
| Conectar (`ConnectActivity`) | Pide la IP del ESP32, valida con una llamada real antes de continuar | `GET /api/status` |
| Inicio (`DashboardFragment`) | Estado en vivo (humedad, temperatura, nivel), alertas, alimentar ahora | `GET /api/status`, `POST /api/feed` |
| Horarios (`ScheduleFragment`) | Ver/agregar/quitar horarios, guardar en el dispositivo | `GET /api/schedule`, `POST /api/schedule` |
| Estadísticas (`StatsFragment`) | Gráficas de humedad y nivel, conteo de alimentaciones del día | Lee de la base de datos local (Room), no del ESP32 directamente |
| Ajustes (`SettingsFragment`) | Ver IP conectada, activar/desactivar alertas, desconectar | — |

## Por qué hay una base de datos local (Room)

Tu ESP32 solo responde con el estado **actual** — no guarda historial. Para las gráficas de estadísticas, `PollingWorker` (usando `WorkManager`) consulta `/api/status` cada 15 minutos, **incluso con la app cerrada**, y guarda cada lectura en una tabla local (`sensor_readings`). Ese mismo worker es el que dispara las notificaciones de tolva vacía / humedad alta, comparando el estado contra tus preferencias guardadas en Ajustes.

## Antes de correrla, ajusta esto

- El proyecto usa `usesCleartextTraffic="true"` en el manifiesto porque el ESP32 sirve HTTP plano en tu red local (no HTTPS) — es correcto para este caso de uso, no lo quites.
- `PollingWorker.schedule()` en `MainActivity.onCreate()` programa el polling cada 15 minutos como mínimo (es el límite de Android para `PeriodicWorkRequest`); el dashboard en pantalla hace su propio polling cada 5 segundos mientras la app está abierta, independiente de ese worker.
- Si cambias los nombres de campos JSON en el firmware (`.ino`), tienes que actualizar `FeederStatus.java` y `ScheduleItem.java` para que sigan coincidiendo — están anotados con `@SerializedName` justamente en los mismos nombres que ya usa `fish_feeder_firmware.ino`.

## Siguiente paso (cuando migres a Firebase)

Todo el código que habla con el ESP32 está aislado en el paquete `network/` (`FeederApiService`, `RetrofitClient`) y en `PollingWorker`. Cuando decidas mover las alertas a Firebase Cloud Messaging para recibirlas fuera de la red WiFi, esos son los únicos puntos que cambian — el resto de la UI (Dashboard, Horarios, Estadísticas, Ajustes) no necesita tocarse.
