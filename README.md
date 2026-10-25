# Dispensador automático de alimento para peces

Dispensador de alimento basado en ESP32 que alimenta a los peces según horarios programados, vigila el estado del alimento y avisa cuando la tolva se vacía o el tornillo dosificador se atasca. Se controla desde una app Android propia o desde el panel web que sirve el mismo ESP32.

El repositorio tiene dos partes:

```
Firmware-ESP32/fish_feeder_firmware/   firmware del ESP32 (Arduino)
App-Android/                           app Android en Java
```

## Hardware

| Componente | Función |
|---|---|
| ESP32 | Controlador, WiFi y servidor HTTP |
| Servo SG90 | Mueve el tornillo dosificador |
| Motor vibrador | Evita que el alimento se compacte en la tolva |
| DHT22 | Humedad y temperatura del alimento |
| VL53L0X | Nivel de la tolva (sensor de distancia láser) |
| DS3231 | Reloj de tiempo real para los horarios |

El DS3231 y el VL53L0X van en buses I2C distintos (GPIO21/22 y GPIO32/33) usando los dos controladores I2C del ESP32. El resto de pines está definido al inicio del `.ino`.

## Firmware

- Hasta 10 horarios de alimentación, guardados en la memoria flash (`Preferences`) para que sobrevivan a un reinicio.
- Cada porción son varios vaivenes del servo con el vibrador encendido.
- Detección de atasco: después de alimentar compara el nivel de la tolva; si no bajó lo suficiente, marca una alerta.
- Alertas por humedad alta, temperatura fuera de rango y tolva vacía. Los umbrales se pueden cambiar desde la app sin volver a compilar.
- Se anuncia en la red como `http://pezfeeder.local` (mDNS), así la app no depende de la IP que asigne el router.
- Si no logra conectarse al WiFi, crea su propia red (`PezFeeder-Config`) para recibir las credenciales desde la app.

### API HTTP

| Método | Ruta | Uso |
|---|---|---|
| GET | `/api/status` | Sensores, nivel de tolva y alertas |
| POST | `/api/feed` | Alimentar ahora |
| GET/POST | `/api/schedule` | Leer o guardar horarios |
| GET/POST | `/api/thresholds` | Leer o cambiar umbrales de alerta |
| GET/POST | `/api/pause` | Pausar o reanudar la alimentación automática |
| POST | `/api/time` | Sincronizar la hora del RTC |
| GET/POST | `/api/wifi-config` | Configurar la red WiFi |

### Cargar el firmware

1. Instala el soporte de ESP32 en el Arduino IDE.
2. Instala las librerías: RTClib, DHT sensor library, Adafruit VL53L0X, ESP32Servo y ArduinoJson.
3. Cambia `WIFI_SSID_DEFECTO` y `WIFI_PASSWORD_DEFECTO` en el `.ino`, o configúralos después desde la app.
4. Compila y sube `fish_feeder_firmware.ino`.

## App Android

Java, Android 7.0+ (minSdk 24). Usa Retrofit para hablar con el ESP32, Room para guardar el historial de lecturas, WorkManager para consultar el estado en segundo plano cada 15 minutos y MPAndroidChart para las gráficas.

Pantallas: conexión (busca el dispensador por mDNS), inicio con estado en vivo, horarios, estadísticas, ajustes y configuración de WiFi. Más detalle en [App-Android/README_APP.md](App-Android/README_APP.md).

Para correrla: abre la carpeta `App-Android` en Android Studio, deja que Gradle sincronice y ejecútala en un celular conectado a la misma red WiFi que el ESP32.

## Autor

**Juan Diego Negrete Portillo**
Estudiante de Ingeniería de Sistemas — Universidad de Córdoba
[Portafolio](https://juanchiz1.github.io/Portafolio-Juan_Diego_Negrete/) · [LinkedIn](https://www.linkedin.com/in/juan-diego-negrete-portillo-352111195/) · [GitHub](https://github.com/Juanchiz1)
