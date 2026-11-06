# Dispensador automático de alimento para peces

Dispensador de alimento basado en ESP32 para La Tienda de los Peces. Alimenta según horarios programados, vigila la humedad y la temperatura del alimento, mide cuánto queda en la tolva y avisa cuando se vacía o el tornillo dosificador se atasca. Se controla desde una app Android propia o desde el panel web que sirve el mismo ESP32 (sirve también en iPhone).

```
Firmware-ESP32/fish_feeder_firmware/   firmware del ESP32 (Arduino)
App-Android/                           app Android en Java
```

## Hardware

| Componente | Función | Pines |
|---|---|---|
| ESP32 | Controlador, WiFi y servidor HTTP | |
| Servo SG90 | Mueve el tornillo dosificador | GPIO13 |
| DHT22 | Humedad y temperatura del alimento | GPIO4 |
| RCWL-1601 | Nivel de la tolva (ultrasonido, modo Trig/Echo, a 3,3 V) | Trig GPIO33, Echo GPIO32 |
| DS3231 | Reloj de tiempo real para los horarios | SDA GPIO21, SCL GPIO22 |

El sensor de nivel va en la tapa apuntando hacia abajo. Los pads UART/IIC de la parte de atrás del RCWL-1601 quedan sin soldar.

## Firmware

- Hasta 10 horarios de alimentación guardados en flash (`Preferences`), con modo vacaciones para pausarlos.
- Sensores escalonados: cada 5 minutos se lee el DHT22 y luego se revisa el RTC, nunca los dos a la vez. El sensor de nivel solo dispara después de alimentar, al arrancar o cuando se pide desde la app.
- La velocidad del sonido se corrige con la temperatura del DHT22. El nivel se calcula por volumen (tolva en pirámide invertida), no lineal.
- Detección de atasco: si después de varias alimentaciones el nivel no baja, marca una alerta.
- Alertas por humedad alta, temperatura fuera de rango, tolva baja o vacía y sensores fallando. Los umbrales se cambian desde la app o el panel.
- Avisos opcionales por Telegram, configurables desde el panel.
- Se anuncia como `http://pezfeeder.local` (mDNS). Además intenta mantener la misma IP en cada red: la guarda la primera vez y la vuelve a pedir si nadie más la está usando (revisa por ARP).
- Si hay internet, pone el DS3231 en hora con NTP (UTC-5) al arrancar y cada 6 h.
- Si no logra conectarse al WiFi, crea su propia red (`PezFeeder-Config`, clave `pezfeeder123`) para configurarlo en `http://192.168.4.1`.
- Guarda en RAM las últimas 24 h de lecturas y las últimas 48 alimentaciones; la app las descarga para sus estadísticas.

### Panel web

En `http://pezfeeder.local` (o la IP del ESP32), desde cualquier navegador en la misma red: estado, alimentar, horarios, umbrales con calibración del sensor de nivel, Telegram, red/IP y hora. Es una sola página de ~15 KB para que el ESP32 la sirva rápido. En iPhone: Safari → Compartir → Agregar a pantalla de inicio.

### API HTTP

| Método | Ruta | Uso |
|---|---|---|
| GET | `/api/status` | Sensores, nivel de tolva, alertas, red e IP |
| POST | `/api/feed?porciones=N` | Alimentar ahora |
| GET/POST | `/api/schedule` | Leer o guardar horarios |
| GET/POST | `/api/thresholds` | Leer o cambiar umbrales (responde 400 con el motivo si un valor no sirve) |
| GET/POST | `/api/pause` | Pausar o reanudar los horarios |
| POST | `/api/time` | Poner la hora del RTC |
| GET/POST | `/api/wifi-config` | Cambiar la red WiFi |
| GET/POST | `/api/red` | Estado de la IP fija y activarla o desactivarla |
| GET | `/api/diagnostics` | Último estado de los tres sensores |
| POST | `/api/diagnostics/{nivel,dht22,rtc}` | Probar un sensor |
| GET | `/api/history?desde=<epoch>` | Lecturas guardadas (cada 5 min) |
| GET | `/api/feedings?desde=<epoch>` | Alimentaciones guardadas |
| GET/POST | `/api/telegram` | Configurar los avisos |
| POST | `/api/telegram/prueba` | Mandar un mensaje de prueba |

### Cargar el firmware

1. Instala el soporte de ESP32 en el Arduino IDE (core 3.x).
2. Instala las librerías: RTClib, DHT sensor library (con Adafruit Unified Sensor), ESP32Servo y ArduinoJson (6 o 7).
3. Abre `Firmware-ESP32/fish_feeder_firmware/fish_feeder_firmware.ino`. `pagina_web.h` tiene que quedar en la misma carpeta.
4. Pon tu red en `WIFI_SSID_DEFECTO` y `WIFI_PASSWORD_DEFECTO` (sin subirla al repositorio), o configúrala después desde la app o desde `PezFeeder-Config`.
5. Compila y sube.

## App Android

Java, Android 7.0+ (minSdk 24). Retrofit para hablar con el ESP32, Room para el historial, WorkManager para consultar el estado en segundo plano y MPAndroidChart para las gráficas.

Pantallas: conexión (busca el dispensador por mDNS o IP), inicio con estado en vivo, horarios, estadísticas con filtro por día, diagnóstico de cada sensor por separado, umbrales con calibración y configuración de WiFi. Más detalle en [App-Android/README_APP.md](App-Android/README_APP.md).

Para correrla: abre la carpeta `App-Android` en Android Studio, deja que Gradle sincronice y ejecútala en un celular conectado a la misma red WiFi que el ESP32.

## Autor

**Juan Diego Negrete Portillo**
Estudiante de Ingeniería de Sistemas — Universidad de Córdoba
[Portafolio](https://juanchiz1.github.io/Portafolio-Juan_Diego_Negrete/) · [LinkedIn](https://www.linkedin.com/in/juan-diego-negrete-portillo-352111195/) · [GitHub](https://github.com/Juanchiz1)
