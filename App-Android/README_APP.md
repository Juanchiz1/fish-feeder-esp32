# App Android: dispensador de alimento para peces (Java)

## Cómo abrirla

1. En Android Studio: `Open`, y eliges la carpeta `FishFeederApp` (la que tiene `settings.gradle`).
2. Esperas a que Gradle sincronice. Retrofit, Room, WorkManager, Material y MPAndroidChart se descargan solos.
3. La corres en un celular por USB o en un emulador. El celular tiene que estar en la misma red WiFi que el ESP32.

**Usa esta app con el firmware de `Firmware-ESP32/fish_feeder_firmware`.** Las pantallas de Diagnóstico y Estadísticas usan endpoints que el firmware anterior no tenía.

## Pantallas

| Pantalla | Qué hace | Endpoints |
| --- | --- | --- |
| Conectar | Busca el dispensador por mDNS o por IP | `GET /api/status` |
| Inicio | Estado en vivo, alertas, alimentar, **Medir nivel ahora** | `GET /api/status`, `POST /api/feed`, `POST /api/diagnostics/nivel` |
| Horarios | Ver, agregar y quitar horarios | `GET/POST /api/schedule` |
| Estadísticas | **Filtro por día**, resumen, gráficas por hora, semana, alimentaciones | Room (sincronizado desde `/api/history` y `/api/feedings`) |
| Ajustes → Diagnóstico | **Un botón por sensor** + "probar los tres, uno por uno" | `GET /api/diagnostics`, `POST /api/diagnostics/{nivel,dht22,rtc}` |
| Ajustes → Umbrales | Calibrar; "Actualizar lectura" mide el nivel una vez | `GET/POST /api/thresholds`, `POST /api/diagnostics/nivel` |

## Cómo llegan los datos a Estadísticas

El ESP32 lee el DHT22 cada 5 minutos y guarda en RAM las últimas 24 h de lecturas, más las últimas 48 alimentaciones (con hora del RTC y el nivel antes y después). `HistorySync` descarga eso a Room:

- `PollingWorker`, cada 15 min, incluso con la app cerrada.
- Inicio, cada minuto mientras está abierta.
- Estadísticas, al abrir y al deslizar hacia abajo.

Sincronizar dos veces no duplica nada: `deviceTs` (la hora del RTC) es clave única. Si el celular pasa más de 24 h sin conectarse, las lecturas de ese hueco se pierden. Así funciona la RAM del ESP32.

La base de datos pasó a la versión 2 con una migración (`AppDatabase.MIGRATION_1_2`), así que el historial que ya tenías no se borra.

## Diagnóstico

Cada tarjeta enciende solo su sensor:

- **Nivel (RCWL-1601, ultrasonido):** dispara 10 pulsos y los muestra uno por uno (mm si fue válido, `mm*` si el eco volvió de más allá del fondo, `—` sin eco, `C` muy cerca, `E` sin respuesta). También muestra cuánto varían los ecos entre sí (si varían mucho, hay rebotes en las paredes) y la velocidad del sonido usada, corregida con la temperatura del DHT22.
- **Humedad y temperatura (DHT22):** una lectura, con un reintento a los 2 s si falla.
- **Reloj (DS3231):** reinicia la comunicación y muestra la hora y la temperatura interna.

"Probar los tres, uno por uno" los corre en cadena. Cada uno empieza 1 s después de que el anterior respondió.

## Notas

- `usesCleartextTraffic="true"` es necesario: el ESP32 sirve HTTP plano en la red local.
- El timeout de lectura es de 15 s porque alimentar varias porciones bloquea al ESP32 unos segundos antes de que pueda responder.
- Calibración del nivel: en Ajustes → Umbrales, con la tolva vacía toca "Actualizar lectura" y luego "Usar" en "distancia con la tolva vacía". Aunque el eco vuelva de más allá del fondo actual, la app te muestra esa distancia para que la uses.
- Umbrales: los campos aceptan coma o punto decimal ("65,5" o "65.5"). Antes, en celulares en español, `String.format("%.1f")` escribía "65,0" y `Float.parseFloat` lo rechazaba, por eso no guardaba. Si el ESP32 rechaza un valor, la app muestra el motivo que manda el firmware.
- iPhone: no hay app. Se usa el panel web del ESP32 en `http://pezfeeder.local` (Safari → Compartir → Agregar a pantalla de inicio).
- La hora del RTC es la hora local del celular (se sincroniza desde Ajustes). `DeviceTime` la convierte a hora real con la zona horaria del teléfono.
