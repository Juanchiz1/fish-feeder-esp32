/*
  Dispensador automático de alimento para peces - ESP32
  ------------------------------------------------------
  Actuador:  servo SG90 (tornillo dosificador)
  Sensores:  DHT22 (humedad/temperatura del alimento) + RCWL-1601 ultrasónico (nivel de la tolva)
  Reloj:     DS3231 (horarios de alimentación; siempre activo)
  Panel:     servidor web embebido + API REST para la app Android
  Red:       mDNS -> http://pezfeeder.local

  CICLO DE SENSORES (ver atenderCicloSensores())
    - El RTC nunca se apaga: se consulta cada segundo para disparar los horarios.
    - Cada 5 minutos arranca un ciclo, y cada sensor se lee en su propio turno,
      separados SEPARACION_PASOS_MS entre sí (nunca dos sensores a la vez):
          DHT22  ->  (3 s)  ->  revisión del RTC  ->  (3 s)  ->  guardar en historial
    - El sensor de nivel NO participa del ciclo de 5 minutos: solo dispara pulsos
        a) después de cada alimentación (servo),
        b) una vez al arrancar,
        c) cuando la app lo pide ("Medir ahora" o Diagnóstico).
      Si no se le manda pulso por Trig, no emite nada.
    - Si una alimentación cae en medio de un ciclo, el siguiente paso del ciclo
      espera SEPARACION_PASOS_MS después de que termine la medición del nivel.

  SENSOR DE NIVEL: RCWL-1601 en modo Trig/Echo (ver medirNivel())
    Va en la tapa apuntando hacia abajo, en el mismo lugar del VL53L0X, y usa los MISMOS
    cables del ESP32:
        VCC  <- 3V3          (el cable que iba a VIN del VL53L0X)
        GND  <- GND
        Trig <- GPIO33       (el cable que iba a SCL)
        Echo -> GPIO32       (el cable que iba a SDA)
        GPIO25 (el cable de XSHUT) queda libre: aíslalo con cinta.
    Los pads "UART" e "IIC" de la parte de atrás del módulo deben quedar SIN soldar
    (así trabaja en modo Trig/Echo). Alimentado con 3,3 V, Echo sale a 3,3 V: no hace
    falta divisor de voltaje.

    Cada medición dispara varios pulsos separados PAUSA_ENTRE_PULSOS_MS (para que se
    apaguen los rebotes dentro de la tolva) y vota: válido, sin eco, más allá del fondo,
    muy cerca o sin respuesta. La velocidad del sonido se corrige con la temperatura del
    DHT22 (la última leída; no lo vuelve a encender).
    Límite físico: por debajo de ~2,5 cm el ultrasonido no mide bien (zona ciega), así que
    no llenes la tolva hasta el borde. El % es por volumen (pirámide invertida -> cubo de
    la altura), no lineal.

  I2C: solo queda el bus del reloj DS3231 (SDA=GPIO21, SCL=GPIO22).

  PANEL WEB (sirve en iPhone y en cualquier navegador): http://pezfeeder.local o la IP
    El HTML está en pagina_web.h, que debe ir en la misma carpeta que este .ino.
    Es corto a propósito (~15 KB, una sola página): estado, alimentar, horarios, umbrales con
    calibración, Telegram, red/IP y hora. El historial con gráficas está en la app Android.

  HORA POR INTERNET
    Si hay internet, el DS3231 se pone en hora solo con NTP (ZONA_HORARIA_S = UTC-5) al
    arrancar y cada 6 h. Se desactiva con AJUSTAR_HORA_POR_INTERNET = false.

  IP FIJA POR RED (ver fijarIpSiCorresponde())
    La primera vez que se conecta a una red guarda la IP que le dio el router. Las siguientes
    veces se conecta por DHCP y, si el router le dio otra, vuelve a la guardada SOLO si nadie
    más la está usando (pregunta por ARP). Si la red cambió de rango (p. ej. el hotspot del
    celular), guarda la nueva. Se desactiva desde Ajustes -> Red e IP.

  AVISOS POR TELEGRAM (opcional, ver atenderAvisos())
    Con token de bot y chat id configurados desde el panel, manda un mensaje cuando aparece
    una alerta (tolva vacía, humedad, temperatura, atasco, sensor fallando) y lo repite cada
    12 h si sigue. Necesita internet en la red.

  Historial: el ESP32 guarda en RAM las últimas 24 h de lecturas (cada 5 min) y las
  últimas 48 alimentaciones con hora del RTC. La app las descarga con
  GET /api/history y GET /api/feedings. Se pierden si el ESP32 se reinicia antes
  de que la app las sincronice (la app sincroniza cada 15 min).
*/

#include <WiFi.h>
#include <WebServer.h>
#include <ESPmDNS.h>
#include <Wire.h>
#include <RTClib.h>
#include <DHT.h>
#include <ESP32Servo.h>
// El código usa la API de ArduinoJson 6 (StaticJsonDocument, createNestedObject) para compilar con
// v6 y v7. En v7 eso genera avisos de "deprecated"; esta línea los silencia y en v6 no tiene efecto.
#define ARDUINOJSON_DEPRECATED(msg)
#include <ArduinoJson.h>
#include <Preferences.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include "esp_netif.h"
#include "esp_netif_net_stack.h"
#include "lwip/etharp.h"
#include "lwip/tcpip.h"
#include <time.h>
#include "pagina_web.h"   // PAGINA_HTML

const char* FIRMWARE_VERSION = "2026.11";

// =====================================================================
// CONFIGURACIÓN DE RED
// =====================================================================
// Respaldo inicial: la credencial real se guarda en Preferences y se cambia desde la app.
const char* WIFI_SSID_DEFECTO     = "TECNO40";
const char* WIFI_PASSWORD_DEFECTO = "Julio4080";
const char* MDNS_HOSTNAME         = "pezfeeder";
const char* AP_SSID               = "PezFeeder-Config";
const char* AP_PASSWORD           = "pezfeeder123";

// =====================================================================
// PINES
// =====================================================================
#define PIN_DHT22        4
#define PIN_SERVO        13
#define PIN_SDA          21
#define PIN_SCL          22
#define PIN_ECHO_NIVEL   32  // Echo del RCWL-1601 (cable que iba a SDA del VL53L0X)
#define PIN_TRIG_NIVEL   33  // Trig del RCWL-1601 (cable que iba a SCL del VL53L0X)
#define PIN_LIBRE_25     25  // antes XSHUT; ya no se usa
#define DHT_TYPE         DHT22

// =====================================================================
// CICLO DE SENSORES
// =====================================================================
const unsigned long INTERVALO_CICLO_MS   = 5UL * 60UL * 1000UL; // cada 5 minutos
const unsigned long SEPARACION_PASOS_MS  = 3000;  // espacio entre un sensor y el siguiente
const unsigned long DHT_REINTENTO_MS     = 2500;  // el DHT22 necesita >= 2 s entre lecturas
const int           DHT_MAX_INTENTOS     = 3;

// =====================================================================
// UMBRALES (respaldo inicial; cargarUmbrales() los sobrescribe con lo guardado)
// =====================================================================
float    UMBRAL_HUMEDAD_PORCENTAJE = 65.0;
float    UMBRAL_TEMP_MIN           = 18.0;
float    UMBRAL_TEMP_MAX           = 30.0;
uint16_t DISTANCIA_TOLVA_LLENA_MM  = 40;   // el ultrasonido no es confiable por debajo de ~3-4 cm
const uint16_t PROFUNDIDAD_TOLVA_MM = 100; // sensor -> vértice del embudo
uint16_t DISTANCIA_TOLVA_VACIA_MM  = PROFUNDIDAD_TOLVA_MM;
float    UMBRAL_CAMBIO_MINIMO_MM   = 2.0;  // bajada mínima acumulada para descartar atasco

// =====================================================================
// SENSOR DE NIVEL (RCWL-1601, ultrasónico)
// =====================================================================
const uint16_t MARGEN_FONDO_MM          = 15;    // más lejos que fondo+margen = rebote en paredes, no alimento
const uint16_t ZONA_CIEGA_MM            = 25;    // más cerca que esto el eco se mezcla con la emisión
const unsigned long PAUSA_ENTRE_PULSOS_MS = 60;  // deja apagar los rebotes dentro de la tolva cerrada
const unsigned long ECO_ESPERA_SUBIDA_US  = 10000;  // tras el disparo, Echo sube en < 1 ms si el módulo responde
const unsigned long ECO_DURACION_MAX_US   = 30000;  // ~5 m ida y vuelta: más que esto = no volvió eco
const unsigned long ECO_FIN_ANTERIOR_MAX_MS = 250;  // espera máxima a que termine un pulso anterior
#define NIVEL_MUESTRAS_MAX 12
const int      NIVEL_MUESTRAS_ALIMENTAR   = 7;
const int      NIVEL_MUESTRAS_DIAGNOSTICO = 10;
const uint16_t NIVEL_DISPERSION_ALTA_MM   = 10;  // lecturas válidas más separadas que esto = rebotes
const int      NIVEL_BAJO_PCT           = 15;
const int      ALIMENTACIONES_PARA_ATASCO = 3;

// =====================================================================
// AVISOS POR TELEGRAM
// =====================================================================
const unsigned long AVISO_REPETIR_MS      = 12UL * 3600UL * 1000UL; // si la alerta sigue, se repite cada 12 h
const unsigned long AVISO_ENTRE_ENVIOS_MS = 5000;                   // no saturar: un mensaje cada 5 s como mucho

// =====================================================================
// HORA POR INTERNET
// =====================================================================
const long          ZONA_HORARIA_S            = -5L * 3600L;         // Colombia: UTC-5, sin horario de verano
const bool          AJUSTAR_HORA_POR_INTERNET = true;                // pone el DS3231 en hora con NTP

// =====================================================================
// SERVO
// =====================================================================
const int SERVO_ANGULO_REPOSO   = 0;
const int SERVO_ANGULO_DISPENSA = 150;
const int CICLOS_POR_PORCION    = 3;
const int MS_ASENTAMIENTO       = 400; // espera antes de medir para que el alimento se acomode
const int PORCIONES_MAX         = 10;

// =====================================================================
// HISTORIAL EN RAM
// =====================================================================
#define HIST_MAX   288  // 24 h a 1 lectura cada 5 min
#define ALIM_MAX   48
#define MAX_HORARIOS 10

// =====================================================================
// TIPOS
// =====================================================================
enum EstadoNivel : uint8_t {
  NIVEL_OK         = 0, // eco válido, nivel calculado
  NIVEL_SIN_ECO    = 1, // el sensor funciona pero no ve alimento -> tolva vacía
  NIVEL_MUY_CERCA  = 2, // algo dentro de la zona ciega: tolva sobrellenada o algo pegado a la rejilla
  NIVEL_ERROR      = 3, // el pin Echo se comportó raro en varios disparos (cables/soldaduras)
  NIVEL_SIN_SENSOR = 4, // Echo nunca sube tras el disparo (desconectado o dañado)
  NIVEL_SIN_MEDIR  = 5  // aún no se mide desde el arranque
};

enum OrigenAlimentacion : uint8_t { ORIGEN_PROGRAMADA = 0, ORIGEN_MANUAL = 1 };

enum PasoCiclo : uint8_t { PASO_INACTIVO, PASO_DHT, PASO_RTC, PASO_NIVEL_ARRANQUE, PASO_GUARDAR };

// Resultado de cada pulso (campo "st" de las muestras que ve la app).
const uint8_t ST_VALIDA        = 0;   // eco dentro de la tolva
const uint8_t ST_SIN_ECO       = 1;   // Echo subió pero el eco no volvió a tiempo
const uint8_t ST_MAS_ALLA      = 2;   // eco más lejos que el fondo: rebote en paredes = sin alimento
const uint8_t ST_MUY_CERCA     = 3;   // dentro de la zona ciega
const uint8_t ST_SIN_RESPUESTA = 255; // Echo nunca subió, o se quedó pegado en alto

struct SesionNivel {
  int      n = 0;
  uint16_t mm[NIVEL_MUESTRAS_MAX];
  uint8_t  st[NIVEL_MUESTRAS_MAX];
  int      validas = 0, sinEco = 0, muyCerca = 0, errores = 0;
  int      pegados = 0;            // de los errores, cuántos fueron Echo pegado en alto
  uint16_t medianaMm = 0;          // mediana de las válidas
  uint16_t crudaMm = 0;            // mediana de todo lo que tuvo eco (sirve para calibrar con la tolva vacía)
  uint16_t dispersionMm = 0;       // máx - mín de las válidas
  float    temperaturaUsada = 20;  // °C con que se calculó la velocidad del sonido
  float    velocidadSonido = 343;  // m/s
  EstadoNivel estado = NIVEL_SIN_MEDIR;
  unsigned long duracionMs = 0;
  unsigned long finMs = 0;         // millis() al terminar (0 = nunca)
};

struct HorarioAlimentacion {
  uint8_t hora;
  uint8_t minuto;
  uint8_t porciones;
  bool    disparadoHoy;
};

struct RegistroLectura {
  uint32_t ts;      // unixtime del RTC (hora local guardada como si fuera UTC)
  int16_t  h10;     // humedad x10, INT16_MIN = sin dato
  int16_t  t10;     // temperatura x10, INT16_MIN = sin dato
  int8_t   nivel;   // % de volumen, -1 = desconocido
  uint8_t  estado;  // EstadoNivel
};

struct RegistroAlimentacion {
  uint32_t ts;
  uint8_t  porciones;
  uint8_t  origen;       // OrigenAlimentacion
  int8_t   nivelAntes;   // -1 = desconocido
  int8_t   nivelDespues; // -1 = desconocido
  uint8_t  estado;       // EstadoNivel tras la medición posterior
};

// Consulta ARP que se ejecuta dentro del hilo de lwIP (ver ipEnUso()).
struct ConsultaArp {
  struct netif* nif;
  ip4_addr_t ip;
  volatile bool hecho;
  volatile bool encontrada;
};

// =====================================================================
// OBJETOS
// =====================================================================
DHT dht(PIN_DHT22, DHT_TYPE);
RTC_DS3231 rtc;
Servo servoTornillo;
WebServer server(80);
Preferences prefs;

// =====================================================================
// ESTADO GLOBAL
// =====================================================================
// --- DHT22 ---
float    humedadActual = NAN;
float    temperaturaActual = NAN;
bool     dhtOk = false;
int      dhtFallosSeguidos = 0;
int      dhtIntentosUltimaLectura = 0;
unsigned long dhtUltimaLecturaOkMs = 0;
bool     humedadAltaAlerta = false;
bool     temperaturaAltaAlerta = false;
bool     temperaturaBajaAlerta = false;

// --- RTC ---
bool     rtcOk = false;
bool     rtcPerdioEnergia = false;
float    rtcTemperatura = NAN; // sensor interno del DS3231
unsigned long rtcUltimaRevisionMs = 0;

// --- Nivel (RCWL-1601) ---
EstadoNivel estadoNivel = NIVEL_SIN_MEDIR;
int      nivelTolvaPorcentaje = -1; // último nivel conocido; -1 = nunca medido
uint16_t distanciaActualMM = 0;
unsigned long nivelMedidoMs = 0;    // millis() de la última sesión (0 = nunca)
bool     tolvaVaciaAlerta = false;
bool     tolvaBajaAlerta = false;
bool     posibleAtascoAlerta = false;
uint16_t distanciaRefAtasco = 0;
int      alimentacionesSinCambio = 0;
int      sesionesNivelFallidas = 0;
SesionNivel ultimaSesionNivel;

// --- Ciclo ---
PasoCiclo pasoActual = PASO_INACTIVO;
bool     primerCiclo = true;
bool     nivelPendienteArranque = true;
unsigned long inicioCicloMs = 0;
unsigned long proximoPasoMs = 0;
unsigned long noAntesDeMs = 0;      // ningún paso del ciclo corre antes de esto
unsigned long ultimoSensorCicloMs = 0; // última vez que el ciclo usó un sensor (DHT22 o RTC)
int      intentosDhtCiclo = 0;

// --- Alimentación / horarios ---
HorarioAlimentacion horarios[MAX_HORARIOS];
int      numHorarios = 0;
bool     horariosPausados = false;
int      lastMinuteChecked = -1;
String   ultimaAlimentacion = "Nunca";
int      ultimaPorciones = 0;

// --- Historial ---
RegistroLectura      historial[HIST_MAX];
int histInicio = 0, histCuenta = 0;
RegistroAlimentacion alimentaciones[ALIM_MAX];
int alimInicio = 0, alimCuenta = 0;

// --- Red / IP fija ---
bool     ipFijaActiva = true;
String   ipEstado = "Sin conectar todavía.";
String   ipGuardadaTexto = "";

// --- Telegram ---
String   tgToken = "";
String   tgChat = "";
int      tgUltimoCodigo = 0;
unsigned long tgUltimoEnvioMs = 0;

// --- Sistema ---
bool     modoAP = false;
unsigned long loopMaxMs = 0;
bool     operacionLarga = false; // la última vuelta de loop() bloqueó a propósito (servo/diagnóstico)

// =====================================================================
// UTILIDADES
// =====================================================================
const char* nombreEstadoNivel(EstadoNivel e) {
  switch (e) {
    case NIVEL_OK:         return "ok";
    case NIVEL_SIN_ECO:    return "sin_eco";
    case NIVEL_MUY_CERCA:  return "muy_cerca";
    case NIVEL_ERROR:      return "error";
    case NIVEL_SIN_SENSOR: return "sin_sensor";
    default:               return "sin_medir";
  }
}

bool nivelEsConfiable(EstadoNivel e) {
  return e == NIVEL_OK || e == NIVEL_SIN_ECO || e == NIVEL_MUY_CERCA;
}

long segundosDesde(unsigned long ms) {
  if (ms == 0) return -1;
  return (long)((millis() - ms) / 1000UL);
}

uint32_t ahoraEpoch() {
  if (!rtcOk) return 0;
  return rtc.now().unixtime();
}

String fechaHoraTexto(const DateTime& d, bool conFecha) {
  char buf[20];
  if (conFecha) snprintf(buf, sizeof(buf), "%02d/%02d %02d:%02d", d.day(), d.month(), d.hour(), d.minute());
  else          snprintf(buf, sizeof(buf), "%02d:%02d", d.hour(), d.minute());
  return String(buf);
}

void enviarJson(JsonDocument& doc) {
  String salida;
  serializeJson(doc, salida);
  server.send(200, "application/json", salida);
}

bool leerCuerpoJson(JsonDocument& doc) {
  if (!server.hasArg("plain")) {
    server.send(400, "application/json", "{\"error\":\"sin datos\"}");
    return false;
  }
  if (deserializeJson(doc, server.arg("plain")) != DeserializationError::Ok) {
    server.send(400, "application/json", "{\"error\":\"json invalido\"}");
    return false;
  }
  return true;
}



// =====================================================================
// WIFI
// =====================================================================
void cargarCredencialesWiFi(String& ssid, String& password) {
  prefs.begin("feeder", true);
  ssid = prefs.getString("wifi_ssid", "");
  password = prefs.getString("wifi_pass", "");
  prefs.end();
  if (ssid.length() == 0) {
    ssid = WIFI_SSID_DEFECTO;
    password = WIFI_PASSWORD_DEFECTO;
  }
}

void guardarCredencialesWiFi(const String& ssid, const String& password) {
  prefs.begin("feeder", false);
  prefs.putString("wifi_ssid", ssid);
  prefs.putString("wifi_pass", password);
  prefs.end();
}

void iniciarMDNS() {
  if (MDNS.begin(MDNS_HOSTNAME)) {
    MDNS.addService("http", "tcp", 80);
    Serial.printf("mDNS activo: http://%s.local\n", MDNS_HOSTNAME);
  } else {
    Serial.println("ADVERTENCIA: no se pudo iniciar mDNS. Usa la IP directamente.");
  }
}

// AP propio (192.168.4.1) + sigue intentando la red guardada en segundo plano (ver vigilarWiFi()).
void iniciarModoAP() {
  modoAP = true;
  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP(AP_SSID, AP_PASSWORD);
  String ssid, password;
  cargarCredencialesWiFi(ssid, password);
  WiFi.begin(ssid.c_str(), password.c_str());
  Serial.printf("No se pudo conectar. Modo configuración: red \"%s\", IP http://%s\n",
                AP_SSID, WiFi.softAPIP().toString().c_str());
  iniciarMDNS();
}

// ---------- IP fija por red ----------
void cargarConfigRed() {
  prefs.begin("red", true);
  ipFijaActiva = prefs.getBool("fija", true);
  prefs.end();
}

bool cargarRedGuardada(const String& ssid, uint32_t& ip, uint32_t& gw, uint32_t& mask, uint32_t& dns) {
  prefs.begin("red", true);
  bool hay = prefs.getString("ssid", "") == ssid && prefs.getUInt("ip", 0) != 0;
  ip = prefs.getUInt("ip", 0);
  gw = prefs.getUInt("gw", 0);
  mask = prefs.getUInt("mask", 0);
  dns = prefs.getUInt("dns", 0);
  prefs.end();
  return hay;
}

void guardarRed(const String& ssid, uint32_t ip, uint32_t gw, uint32_t mask, uint32_t dns) {
  prefs.begin("red", false);
  prefs.putString("ssid", ssid);
  prefs.putUInt("ip", ip);
  prefs.putUInt("gw", gw);
  prefs.putUInt("mask", mask);
  prefs.putUInt("dns", dns);
  prefs.end();
  ipGuardadaTexto = IPAddress(ip).toString();
}

void olvidarRed() {
  prefs.begin("red", false);
  prefs.remove("ssid");
  prefs.remove("ip");
  prefs.end();
  ipGuardadaTexto = "";
}

static void arpPedir(void* arg) {
  ConsultaArp* c = (ConsultaArp*)arg;
  etharp_request(c->nif, &c->ip);
  c->hecho = true;
}

static void arpBuscar(void* arg) {
  ConsultaArp* c = (ConsultaArp*)arg;
  struct eth_addr* mac;
  const ip4_addr_t* ipRet;
  c->encontrada = etharp_find_addr(c->nif, &c->ip, &mac, &ipRet) >= 0;
  c->hecho = true;
}

static bool esperarArp(ConsultaArp& c) {
  unsigned long t0 = millis();
  while (!c.hecho) {
    if (millis() - t0 > 300) return false;
    delay(2);
  }
  return true;
}

// true si otro equipo responde por esa IP. Ante cualquier duda devuelve true (no se arriesga).
bool ipEnUso(IPAddress ip) {
  esp_netif_t* en = esp_netif_get_handle_from_ifkey("WIFI_STA_DEF");
  if (en == nullptr) return true;
  struct netif* nif = (struct netif*)esp_netif_get_netif_impl(en);
  if (nif == nullptr) return true;

  ConsultaArp c;
  c.nif = nif;
  IP4_ADDR(&c.ip, ip[0], ip[1], ip[2], ip[3]);
  for (int intento = 0; intento < 3; intento++) {
    c.hecho = false;
    if (tcpip_callback(arpPedir, &c) != ERR_OK || !esperarArp(c)) return true;
    delay(500); // tiempo para que llegue la respuesta
    c.hecho = false;
    c.encontrada = false;
    if (tcpip_callback(arpBuscar, &c) != ERR_OK || !esperarArp(c)) return true;
    if (c.encontrada) return true;
  }
  return false;
}

// Se llama apenas el ESP32 queda conectado por DHCP.
void fijarIpSiCorresponde() {
  String ssid = WiFi.SSID();
  uint32_t ip = WiFi.localIP(), gw = WiFi.gatewayIP(), mask = WiFi.subnetMask(), dns = WiFi.dnsIP();
  if (dns == 0) dns = gw;

  if (!ipFijaActiva) {
    ipEstado = "IP por DHCP: el router decide y puede cambiar.";
    return;
  }
  uint32_t gIp, gGw, gMask, gDns;
  if (!cargarRedGuardada(ssid, gIp, gGw, gMask, gDns)) {
    guardarRed(ssid, ip, gw, mask, dns);
    ipEstado = "Primera conexión a esta red: se guardó " + IPAddress(ip).toString() + ".";
    return;
  }
  ipGuardadaTexto = IPAddress(gIp).toString();
  if (gIp == ip) {
    guardarRed(ssid, ip, gw, mask, dns); // por si cambió el DNS
    ipEstado = "Misma IP que la vez pasada.";
    return;
  }
  if (gGw != gw || gMask != mask) {
    guardarRed(ssid, ip, gw, mask, dns);
    ipEstado = "La red cambió de rango (puerta de enlace distinta): se guardó la IP nueva " + IPAddress(ip).toString() + ".";
    return;
  }
  IPAddress guardada(gIp);
  Serial.printf("[red] El router dio %s; revisando si %s está libre...\n", IPAddress(ip).toString().c_str(), guardada.toString().c_str());
  if (ipEnUso(guardada)) {
    guardarRed(ssid, ip, gw, mask, dns);
    ipEstado = "La IP " + guardada.toString() + " ya la usa otro equipo: se queda con " + IPAddress(ip).toString() + " y la guarda.";
    return;
  }
  WiFi.config(guardada, IPAddress(gw), IPAddress(mask), IPAddress(dns));
  delay(200);
  ipEstado = "Recuperó su IP " + guardada.toString() + " (el router le había dado " + IPAddress(ip).toString() + ").";
  MDNS.end();
  iniciarMDNS();
}

void conectarWiFi() {
  String ssid, password;
  cargarCredencialesWiFi(ssid, password);
  modoAP = false;
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  WiFi.begin(ssid.c_str(), password.c_str());
  Serial.printf("Conectando a WiFi \"%s\"", ssid.c_str());
  for (int i = 0; i < 40 && WiFi.status() != WL_CONNECTED; i++) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();
  if (WiFi.status() == WL_CONNECTED) {
    iniciarMDNS();
    fijarIpSiCorresponde();
    Serial.printf("Conectado. IP: http://%s · %s\n", WiFi.localIP().toString().c_str(), ipEstado.c_str());
  } else {
    iniciarModoAP();
  }
}

void vigilarWiFi() {
  static unsigned long ultimoIntento = 0;
  if (modoAP && WiFi.status() == WL_CONNECTED) {
    modoAP = false;
    WiFi.softAPdisconnect(true);
    MDNS.end();
    iniciarMDNS();
    fijarIpSiCorresponde();
    Serial.printf("Conectado a la red guardada. IP: http://%s · %s\n", WiFi.localIP().toString().c_str(), ipEstado.c_str());
    return;
  }
  if (WiFi.status() != WL_CONNECTED && millis() - ultimoIntento > 30000) {
    ultimoIntento = millis();
    WiFi.reconnect();
  }
}

// =====================================================================
// PERSISTENCIA (Preferences)
// =====================================================================
void cargarHorarios() {
  prefs.begin("feeder", true);
  String json = prefs.getString("horarios", "");
  prefs.end();

  numHorarios = 0;
  if (json.length() > 0) {
    StaticJsonDocument<1024> doc;
    if (deserializeJson(doc, json) == DeserializationError::Ok) {
      for (JsonObject o : doc.as<JsonArray>()) {
        if (numHorarios >= MAX_HORARIOS) break;
        horarios[numHorarios].hora = o["hora"];
        horarios[numHorarios].minuto = o["minuto"];
        horarios[numHorarios].porciones = o["porciones"] | 1;
        horarios[numHorarios].disparadoHoy = false;
        numHorarios++;
      }
    }
  }
  if (numHorarios == 0) {
    horarios[0] = {8, 0, 1, false};
    horarios[1] = {13, 0, 1, false};
    horarios[2] = {18, 0, 1, false};
    numHorarios = 3;
  }
}

void guardarHorarios() {
  StaticJsonDocument<1024> doc;
  JsonArray arr = doc.to<JsonArray>();
  for (int i = 0; i < numHorarios; i++) {
    JsonObject o = arr.createNestedObject();
    o["hora"] = horarios[i].hora;
    o["minuto"] = horarios[i].minuto;
    o["porciones"] = horarios[i].porciones;
  }
  String salida;
  serializeJson(doc, salida);
  prefs.begin("feeder", false);
  prefs.putString("horarios", salida);
  prefs.end();
}

void cargarUmbrales() {
  prefs.begin("feeder", true);
  UMBRAL_HUMEDAD_PORCENTAJE = prefs.getFloat("hum_max", UMBRAL_HUMEDAD_PORCENTAJE);
  UMBRAL_TEMP_MIN           = prefs.getFloat("temp_min", UMBRAL_TEMP_MIN);
  UMBRAL_TEMP_MAX           = prefs.getFloat("temp_max", UMBRAL_TEMP_MAX);
  DISTANCIA_TOLVA_LLENA_MM  = prefs.getUShort("tolva_llena", DISTANCIA_TOLVA_LLENA_MM);
  DISTANCIA_TOLVA_VACIA_MM  = prefs.getUShort("tolva_vacia", DISTANCIA_TOLVA_VACIA_MM);
  UMBRAL_CAMBIO_MINIMO_MM   = prefs.getFloat("cambio_min", UMBRAL_CAMBIO_MINIMO_MM);
  prefs.end();
}

void guardarUmbrales() {
  prefs.begin("feeder", false);
  prefs.putFloat("hum_max", UMBRAL_HUMEDAD_PORCENTAJE);
  prefs.putFloat("temp_min", UMBRAL_TEMP_MIN);
  prefs.putFloat("temp_max", UMBRAL_TEMP_MAX);
  prefs.putUShort("tolva_llena", DISTANCIA_TOLVA_LLENA_MM);
  prefs.putUShort("tolva_vacia", DISTANCIA_TOLVA_VACIA_MM);
  prefs.putFloat("cambio_min", UMBRAL_CAMBIO_MINIMO_MM);
  prefs.end();
}

void cargarPausa() {
  prefs.begin("feeder", true);
  horariosPausados = prefs.getBool("pausado", false);
  prefs.end();
}

void guardarPausa(bool pausado) {
  prefs.begin("feeder", false);
  prefs.putBool("pausado", pausado);
  prefs.end();
  horariosPausados = pausado;
}

// =====================================================================
// HISTORIAL EN RAM
// =====================================================================
void agregarLectura(const RegistroLectura& r) {
  int idx = (histInicio + histCuenta) % HIST_MAX;
  if (histCuenta < HIST_MAX) histCuenta++;
  else histInicio = (histInicio + 1) % HIST_MAX; // lleno: se pisa la más vieja
  historial[idx] = r;
}

void agregarAlimentacion(const RegistroAlimentacion& r) {
  int idx = (alimInicio + alimCuenta) % ALIM_MAX;
  if (alimCuenta < ALIM_MAX) alimCuenta++;
  else alimInicio = (alimInicio + 1) % ALIM_MAX;
  alimentaciones[idx] = r;
}

void guardarLecturaEnHistorial() {
  uint32_t ts = ahoraEpoch();
  if (ts == 0) {
    Serial.println("[ciclo] Sin RTC: la lectura no se guarda en el historial.");
    return;
  }
  RegistroLectura r;
  r.ts = ts;
  r.h10 = isnan(humedadActual) ? INT16_MIN : (int16_t)lroundf(humedadActual * 10.0f);
  r.t10 = isnan(temperaturaActual) ? INT16_MIN : (int16_t)lroundf(temperaturaActual * 10.0f);
  r.nivel = (int8_t)nivelTolvaPorcentaje;
  r.estado = estadoNivel;
  agregarLectura(r);
}

// =====================================================================
// DHT22
// =====================================================================
// Una lectura. Actualiza valores y alertas solo si es válida y está en un rango físico posible.
bool leerDHT() {
  bool leyo = dht.read(true);
  float h = dht.readHumidity();
  float t = dht.readTemperature();
  bool valida = leyo && !isnan(h) && !isnan(t) && h >= 0 && h <= 100 && t > -40 && t < 80;

  if (valida) {
    humedadActual = h;
    temperaturaActual = t;
    dhtOk = true;
    dhtFallosSeguidos = 0;
    dhtUltimaLecturaOkMs = millis();
    humedadAltaAlerta = humedadActual >= UMBRAL_HUMEDAD_PORCENTAJE;
    temperaturaAltaAlerta = temperaturaActual >= UMBRAL_TEMP_MAX;
    temperaturaBajaAlerta = temperaturaActual <= UMBRAL_TEMP_MIN;
  } else {
    dhtFallosSeguidos++;
    // Tras un ciclo completo fallido se marca como caído y se apagan sus alertas (no son confiables).
    if (dhtFallosSeguidos >= DHT_MAX_INTENTOS) {
      dhtOk = false;
      humedadAltaAlerta = temperaturaAltaAlerta = temperaturaBajaAlerta = false;
    }
  }
  return valida;
}

// =====================================================================
// RTC
// =====================================================================
// El RTC no se "activa" por ciclos: siempre está corriendo. Esto solo verifica que responde
// y que la hora es razonable. reiniciar=true repite rtc.begin() (para diagnóstico).
void revisarRTC(bool reiniciar) {
  if (reiniciar || !rtcOk) rtcOk = rtc.begin(&Wire);
  if (rtcOk) {
    DateTime n = rtc.now();
    if (n.year() < 2024 || n.year() > 2099) rtcOk = false; // lectura basura: bus o módulo fallando
    rtcPerdioEnergia = rtc.lostPower();
    rtcTemperatura = rtc.getTemperature();
  }
  rtcUltimaRevisionMs = millis();
}

// =====================================================================
// SENSOR DE NIVEL (RCWL-1601)
// =====================================================================
// Un pulso: devuelve la duración del eco en µs, o 0 si no hubo eco válido. En 'st' deja por qué.
unsigned long dispararPulso(uint8_t& st, bool& pegado) {
  pegado = false;
  // Si Echo sigue en alto por un pulso anterior sin eco, se espera a que baje.
  unsigned long t0 = millis();
  while (digitalRead(PIN_ECHO_NIVEL) == HIGH) {
    if (millis() - t0 > ECO_FIN_ANTERIOR_MAX_MS) {
      st = ST_SIN_RESPUESTA;
      pegado = true;
      return 0;
    }
    delay(1);
  }

  digitalWrite(PIN_TRIG_NIVEL, LOW);
  delayMicroseconds(4);
  digitalWrite(PIN_TRIG_NIVEL, HIGH);
  delayMicroseconds(12);
  digitalWrite(PIN_TRIG_NIVEL, LOW);

  unsigned long esperaSubida = micros();
  while (digitalRead(PIN_ECHO_NIVEL) == LOW) {
    if (micros() - esperaSubida > ECO_ESPERA_SUBIDA_US) {
      st = ST_SIN_RESPUESTA; // nunca subió: desconectado, sin VCC o dañado
      return 0;
    }
  }
  unsigned long inicio = micros();
  while (digitalRead(PIN_ECHO_NIVEL) == HIGH) {
    if (micros() - inicio > ECO_DURACION_MAX_US) {
      st = ST_SIN_ECO;
      return 0;
    }
  }
  st = ST_VALIDA; // se reclasifica por distancia en medirNivel()
  return micros() - inicio;
}

// % de volumen a partir de la distancia: pirámide invertida -> volumen ~ altura^3.
int calcularNivelPct(uint16_t distancia) {
  long alturaMax = (long)DISTANCIA_TOLVA_VACIA_MM - (long)DISTANCIA_TOLVA_LLENA_MM;
  if (alturaMax <= 0) alturaMax = 1;
  long altura = constrain((long)DISTANCIA_TOLVA_VACIA_MM - (long)distancia, 0L, alturaMax);
  float frac = (float)altura / (float)alturaMax;
  return (int)(frac * frac * frac * 100.0f + 0.5f);
}

void actualizarAlertasNivel() {
  tolvaVaciaAlerta = (estadoNivel == NIVEL_SIN_ECO) || (estadoNivel == NIVEL_OK && nivelTolvaPorcentaje == 0);
  tolvaBajaAlerta  = (estadoNivel == NIVEL_SIN_ECO || estadoNivel == NIVEL_OK) &&
                     nivelTolvaPorcentaje >= 0 && nivelTolvaPorcentaje <= NIVEL_BAJO_PCT;
}

// Decide el estado de la sesión a partir de la clasificación de cada muestra.
void decidirEstadoSesion(SesionNivel& s) {
  const int minimo = (s.n / 2) + 1; // mayoría simple
  if (s.validas >= minimo) {
    uint16_t v[NIVEL_MUESTRAS_MAX];
    int k = 0;
    for (int i = 0; i < s.n; i++) {
      if (s.st[i] == ST_VALIDA) v[k++] = s.mm[i];
    }
    for (int i = 1; i < k; i++) { // inserción, k <= 12
      uint16_t x = v[i];
      int j = i - 1;
      while (j >= 0 && v[j] > x) { v[j + 1] = v[j]; j--; }
      v[j + 1] = x;
    }
    s.medianaMm = v[k / 2];
    s.dispersionMm = v[k - 1] - v[0];
    s.estado = NIVEL_OK;
  } else if (s.errores == s.n) {
    s.estado = NIVEL_SIN_SENSOR;
  } else if (s.errores >= 2) {
    // Con fallos eléctricos de por medio no se puede concluir "vacía".
    s.estado = NIVEL_ERROR;
  } else if (s.muyCerca + s.validas >= minimo && s.muyCerca > 0) {
    s.estado = NIVEL_MUY_CERCA;
  } else {
    s.estado = NIVEL_SIN_ECO;
  }
}

// Aplica el resultado de una sesión al estado global del nivel.
void aplicarSesion(const SesionNivel& s) {
  estadoNivel = s.estado;
  nivelMedidoMs = millis();
  switch (s.estado) {
    case NIVEL_OK:
      distanciaActualMM = s.medianaMm;
      nivelTolvaPorcentaje = calcularNivelPct(distanciaActualMM);
      break;
    case NIVEL_MUY_CERCA:
      distanciaActualMM = DISTANCIA_TOLVA_LLENA_MM;
      nivelTolvaPorcentaje = 100;
      break;
    case NIVEL_SIN_ECO:
      nivelTolvaPorcentaje = 0;
      break;
    default:
      break; // ERROR / SIN_SENSOR: se conserva el último nivel conocido, el estado avisa que no es actual
  }
  sesionesNivelFallidas = nivelEsConfiable(s.estado) ? 0 : sesionesNivelFallidas + 1;
  actualizarAlertasNivel();
}

// Mediana de un arreglo pequeño (lo ordena).
uint16_t medianaDe(uint16_t* v, int k) {
  for (int i = 1; i < k; i++) {
    uint16_t x = v[i];
    int j = i - 1;
    while (j >= 0 && v[j] > x) { v[j + 1] = v[j]; j--; }
    v[j + 1] = x;
  }
  return v[k / 2];
}

// Sesión de medición: N pulsos separados, cada uno clasificado, y votación.
void medirNivel(int muestras) {
  SesionNivel s;
  if (muestras > NIVEL_MUESTRAS_MAX) muestras = NIVEL_MUESTRAS_MAX;
  unsigned long t0 = millis();
  operacionLarga = true;

  // Velocidad del sonido corregida con la última temperatura del DHT22 (no lo enciende).
  s.temperaturaUsada = (dhtOk && !isnan(temperaturaActual)) ? temperaturaActual : 20.0f;
  s.velocidadSonido = 331.3f + 0.606f * s.temperaturaUsada;
  const float mmPorUs = s.velocidadSonido / 1000.0f / 2.0f; // ida y vuelta

  uint16_t conEco[NIVEL_MUESTRAS_MAX];
  int nConEco = 0;
  for (int i = 0; i < muestras; i++) {
    if (i > 0) delay(PAUSA_ENTRE_PULSOS_MS);
    uint8_t st;
    bool pegado;
    unsigned long us = dispararPulso(st, pegado);
    uint16_t mm = (uint16_t)min(65535.0f, us * mmPorUs + 0.5f); // redondeo, no truncado

    if (st == ST_VALIDA) {
      if (mm < ZONA_CIEGA_MM) st = ST_MUY_CERCA;
      else if (mm > DISTANCIA_TOLVA_VACIA_MM + MARGEN_FONDO_MM) st = ST_MAS_ALLA;
      conEco[nConEco++] = mm;
    }
    switch (st) {
      case ST_VALIDA:        s.validas++; break;
      case ST_MUY_CERCA:     s.muyCerca++; break;
      case ST_SIN_RESPUESTA: s.errores++; if (pegado) s.pegados++; break;
      default:               s.sinEco++; break; // sin eco o más allá del fondo
    }
    s.mm[i] = mm;
    s.st[i] = st;
    s.n++;
  }
  if (nConEco > 0) s.crudaMm = medianaDe(conEco, nConEco);
  decidirEstadoSesion(s);

  s.duracionMs = millis() - t0;
  s.finMs = millis();
  ultimaSesionNivel = s;
  aplicarSesion(s);

  Serial.printf("[nivel] %s · %d%% · %u mm (cruda %u) · %d/%d válidas, %d sin eco, %d cerca, %d sin resp. · %.1f °C · %lu ms\n",
                nombreEstadoNivel(s.estado), nivelTolvaPorcentaje, s.medianaMm, s.crudaMm, s.validas, s.n,
                s.sinEco, s.muyCerca, s.errores, s.temperaturaUsada, s.duracionMs);
}

// Tras una medición SIN alimentar (arranque, diagnóstico): solo actualiza la referencia de atasco
// si es la primera o si la tolva se rellenó (distancia menor).
void referenciaAtascoSinAlimentar() {
  if (estadoNivel != NIVEL_OK) return;
  if (distanciaRefAtasco == 0 || distanciaActualMM + UMBRAL_CAMBIO_MINIMO_MM < distanciaRefAtasco) {
    distanciaRefAtasco = distanciaActualMM;
    alimentacionesSinCambio = 0;
    posibleAtascoAlerta = false;
  }
}

// Atasco acumulado: en la parte ancha de la tolva una porción baja < 1 mm, así que se compara
// contra una referencia que solo se mueve cuando el nivel sí bajó.
void evaluarAtasco() {
  switch (estadoNivel) {
    case NIVEL_OK: break;
    case NIVEL_SIN_ECO:   // se acabó el alimento: sí estaba saliendo
    case NIVEL_MUY_CERCA: // tolva llena: una porción no se nota
      distanciaRefAtasco = 0;
      alimentacionesSinCambio = 0;
      posibleAtascoAlerta = false;
      return;
    default:              // error / sin sensor: no hay datos para opinar
      return;
  }

  if (distanciaRefAtasco == 0 || distanciaActualMM + UMBRAL_CAMBIO_MINIMO_MM < distanciaRefAtasco) {
    distanciaRefAtasco = distanciaActualMM; // primera referencia, o rellenaron
    alimentacionesSinCambio = 0;
    posibleAtascoAlerta = false;
    return;
  }

  float cambioMM = (float)distanciaActualMM - (float)distanciaRefAtasco;
  if (cambioMM >= UMBRAL_CAMBIO_MINIMO_MM) {
    distanciaRefAtasco = distanciaActualMM;
    alimentacionesSinCambio = 0;
    posibleAtascoAlerta = false;
  } else {
    alimentacionesSinCambio++;
    posibleAtascoAlerta = alimentacionesSinCambio >= ALIMENTACIONES_PARA_ATASCO;
  }
}

// =====================================================================
// CICLO DE SENSORES (cada 5 min, un sensor a la vez)
// =====================================================================
void posponerCiclo(unsigned long ms) {
  unsigned long hasta = millis() + ms;
  if ((long)(hasta - noAntesDeMs) > 0) noAntesDeMs = hasta;
}

// Lo contrario: antes de medir el nivel tras una alimentación, espera (como mucho
// SEPARACION_PASOS_MS) si el ciclo acaba de leer el DHT22 o el RTC.
void esperarTurnoSensor() {
  while (ultimoSensorCicloMs != 0 && millis() - ultimoSensorCicloMs < SEPARACION_PASOS_MS) delay(20);
}

void atenderCicloSensores() {
  unsigned long ahora = millis();

  if (pasoActual == PASO_INACTIVO) {
    if (!primerCiclo && ahora - inicioCicloMs < INTERVALO_CICLO_MS) return;
    primerCiclo = false;
    inicioCicloMs = ahora;
    pasoActual = PASO_DHT;
    intentosDhtCiclo = 0;
    proximoPasoMs = ahora;
  }

  if ((long)(ahora - proximoPasoMs) < 0) return;
  if ((long)(ahora - noAntesDeMs) < 0) return; // acaba de usarse otro sensor (servo/nivel/diagnóstico)

  switch (pasoActual) {
    case PASO_DHT: {
      intentosDhtCiclo++;
      dhtIntentosUltimaLectura = intentosDhtCiclo;
      bool ok = leerDHT();
      ultimoSensorCicloMs = millis();
      if (ok || intentosDhtCiclo >= DHT_MAX_INTENTOS) {
        if (ok) Serial.printf("[ciclo] DHT22: %.1f %%HR · %.1f °C (intento %d)\n", humedadActual, temperaturaActual, intentosDhtCiclo);
        else    Serial.printf("[ciclo] DHT22 sin respuesta tras %d intentos\n", intentosDhtCiclo);
        pasoActual = PASO_RTC;
        proximoPasoMs = ahora + SEPARACION_PASOS_MS;
      } else {
        proximoPasoMs = ahora + DHT_REINTENTO_MS;
      }
      break;
    }
    case PASO_RTC:
      revisarRTC(false);
      ultimoSensorCicloMs = millis();
      if (rtcOk) Serial.printf("[ciclo] RTC OK · %s · %.2f °C interna\n", fechaHoraTexto(rtc.now(), true).c_str(), rtcTemperatura);
      else       Serial.println("[ciclo] RTC sin respuesta");
      pasoActual = nivelPendienteArranque ? PASO_NIVEL_ARRANQUE : PASO_GUARDAR;
      proximoPasoMs = ahora + SEPARACION_PASOS_MS;
      break;

    case PASO_NIVEL_ARRANQUE: // solo en el primer ciclo tras encender
      medirNivel(NIVEL_MUESTRAS_ALIMENTAR);
      referenciaAtascoSinAlimentar();
      nivelPendienteArranque = false;
      pasoActual = PASO_GUARDAR;
      proximoPasoMs = millis() + SEPARACION_PASOS_MS;
      break;

    case PASO_GUARDAR:
      guardarLecturaEnHistorial();
      pasoActual = PASO_INACTIVO;
      break;

    default:
      pasoActual = PASO_INACTIVO;
      break;
  }
}

long segundosParaProximaLectura() {
  if (pasoActual != PASO_INACTIVO) return 0;
  long restante = (long)(INTERVALO_CICLO_MS - (millis() - inicioCicloMs));
  return restante > 0 ? restante / 1000 : 0;
}

// =====================================================================
// ALIMENTACIÓN Y HORARIOS
// =====================================================================
void alimentar(int porciones, OrigenAlimentacion origen) {
  porciones = constrain(porciones, 1, PORCIONES_MAX);
  Serial.printf("Alimentando: %d porción(es) · %s\n", porciones, origen == ORIGEN_MANUAL ? "manual" : "programada");
  operacionLarga = true;

  int nivelAntes = nivelEsConfiable(estadoNivel) ? nivelTolvaPorcentaje : -1;

  for (int p = 0; p < porciones; p++) {
    for (int c = 0; c < CICLOS_POR_PORCION; c++) {
      servoTornillo.write(SERVO_ANGULO_DISPENSA);
      delay(300);
      servoTornillo.write(SERVO_ANGULO_REPOSO);
      delay(300);
    }
  }

  delay(MS_ASENTAMIENTO);
  esperarTurnoSensor();
  medirNivel(NIVEL_MUESTRAS_ALIMENTAR);
  evaluarAtasco();
  posponerCiclo(SEPARACION_PASOS_MS); // el DHT22 no se lee pegado a la medición del nivel

  uint32_t ts = ahoraEpoch();
  if (ts != 0) {
    RegistroAlimentacion r;
    r.ts = ts;
    r.porciones = porciones;
    r.origen = origen;
    r.nivelAntes = (int8_t)nivelAntes;
    r.nivelDespues = (int8_t)(nivelEsConfiable(estadoNivel) ? nivelTolvaPorcentaje : -1);
    r.estado = estadoNivel;
    agregarAlimentacion(r);
    ultimaAlimentacion = fechaHoraTexto(rtc.now(), true);
  } else {
    ultimaAlimentacion = "Sin hora (RTC)";
  }
  ultimaPorciones = porciones;
}

void revisarHorarios() {
  if (horariosPausados || !rtcOk) return;
  static unsigned long ultimoRtc = 0;
  if (millis() - ultimoRtc < 1000) return;
  ultimoRtc = millis();

  DateTime ahora = rtc.now();
  if (ahora.minute() == lastMinuteChecked) return;
  lastMinuteChecked = ahora.minute();

  if (ahora.hour() == 0 && ahora.minute() == 0) {
    for (int i = 0; i < numHorarios; i++) horarios[i].disparadoHoy = false;
  }

  for (int i = 0; i < numHorarios; i++) {
    if (!horarios[i].disparadoHoy && horarios[i].hora == ahora.hour() && horarios[i].minuto == ahora.minute()) {
      // Se alimenta aunque el sensor diga vacía: "sin eco" también puede ser alimento muy oscuro
      // o mal asentado. Girar en vacío no hace daño; dejar a los peces sin comer sí.
      if (tolvaVaciaAlerta) Serial.println("Aviso: el sensor reporta tolva vacía; se alimenta igual.");
      alimentar(horarios[i].porciones, ORIGEN_PROGRAMADA);
      horarios[i].disparadoHoy = true;
    }
  }
}

// =====================================================================
// AVISOS POR TELEGRAM
// =====================================================================
void cargarTelegram() {
  prefs.begin("feeder", true);
  tgToken = prefs.getString("tg_token", "");
  tgChat = prefs.getString("tg_chat", "");
  prefs.end();
}

void guardarTelegram() {
  prefs.begin("feeder", false);
  prefs.putString("tg_token", tgToken);
  prefs.putString("tg_chat", tgChat);
  prefs.end();
}

bool telegramConfigurado() {
  return tgToken.length() > 0 && tgChat.length() > 0;
}

// Envío bloqueante (1-3 s por el TLS). Sin verificación de certificado (setInsecure): suficiente
// para avisos; para producción habría que cargar el certificado raíz de api.telegram.org.
bool enviarTelegram(const String& texto) {
  if (!telegramConfigurado() || modoAP || WiFi.status() != WL_CONNECTED) return false;
  operacionLarga = true;
  WiFiClientSecure cliente;
  cliente.setInsecure();
  HTTPClient http;
  http.setTimeout(8000);
  tgUltimoEnvioMs = millis();
  if (!http.begin(cliente, "https://api.telegram.org/bot" + tgToken + "/sendMessage")) {
    tgUltimoCodigo = -1;
    return false;
  }
  http.addHeader("Content-Type", "application/json");
  StaticJsonDocument<768> doc;
  doc["chat_id"] = tgChat;
  doc["text"] = texto;
  String cuerpo;
  serializeJson(doc, cuerpo);
  tgUltimoCodigo = http.POST(cuerpo);
  http.end();
  Serial.printf("[telegram] código %d\n", tgUltimoCodigo);
  return tgUltimoCodigo == 200;
}

enum Aviso : uint8_t {
  AV_TOLVA_VACIA, AV_TOLVA_BAJA, AV_HUMEDAD_ALTA, AV_TEMP_ALTA, AV_TEMP_BAJA,
  AV_ATASCO, AV_SENSOR_NIVEL, AV_SENSOR_DHT, AV_RTC, AV_TOTAL
};
bool avisoActivo[AV_TOTAL] = {false};
unsigned long avisoEnviadoMs[AV_TOTAL] = {0};
uint16_t avisosPendientes = 0; // bit por aviso

bool condicionAviso(int a) {
  switch (a) {
    case AV_TOLVA_VACIA:  return tolvaVaciaAlerta;
    case AV_TOLVA_BAJA:   return tolvaBajaAlerta && !tolvaVaciaAlerta;
    case AV_HUMEDAD_ALTA: return humedadAltaAlerta;
    case AV_TEMP_ALTA:    return temperaturaAltaAlerta;
    case AV_TEMP_BAJA:    return temperaturaBajaAlerta;
    case AV_ATASCO:       return posibleAtascoAlerta;
    case AV_SENSOR_NIVEL: return (estadoNivel == NIVEL_ERROR || estadoNivel == NIVEL_SIN_SENSOR) && sesionesNivelFallidas >= 2;
    case AV_SENSOR_DHT:   return !dhtOk && dhtFallosSeguidos >= DHT_MAX_INTENTOS;
    case AV_RTC:          return !rtcOk;
    default:              return false;
  }
}

String textoAviso(int a) {
  String t = "🐟 Dispensador (" + String(MDNS_HOSTNAME) + "): ";
  switch (a) {
    case AV_TOLVA_VACIA:  return t + "la tolva está vacía. Rellénala.";
    case AV_TOLVA_BAJA:   return t + "queda poco alimento (" + String(nivelTolvaPorcentaje) + "%).";
    case AV_HUMEDAD_ALTA: return t + "humedad alta en el alimento: " + String(humedadActual, 1) + "% (máx " + String(UMBRAL_HUMEDAD_PORCENTAJE, 0) + "%).";
    case AV_TEMP_ALTA:    return t + "temperatura alta: " + String(temperaturaActual, 1) + " °C.";
    case AV_TEMP_BAJA:    return t + "temperatura baja: " + String(temperaturaActual, 1) + " °C.";
    case AV_ATASCO:       return t + "posible atasco: el nivel no baja después de alimentar.";
    case AV_SENSOR_NIVEL: return t + "el sensor de nivel está fallando. Revisa Ajustes > Diagnóstico.";
    case AV_SENSOR_DHT:   return t + "el sensor de humedad (DHT22) no responde.";
    case AV_RTC:          return t + "el reloj no responde: los horarios no se van a disparar.";
    default:              return t;
  }
}

// Se llama en cada vuelta de loop(): detecta alertas nuevas y manda como mucho un mensaje.
void atenderAvisos() {
  if (!telegramConfigurado()) return;
  unsigned long ahora = millis();
  for (int a = 0; a < AV_TOTAL; a++) {
    bool activo = condicionAviso(a);
    bool repetir = activo && avisoEnviadoMs[a] != 0 && ahora - avisoEnviadoMs[a] > AVISO_REPETIR_MS;
    if ((activo && !avisoActivo[a]) || repetir) avisosPendientes |= (1 << a);
    if (!activo) avisosPendientes &= ~(1 << a);
    avisoActivo[a] = activo;
  }
  if (avisosPendientes == 0 || ahora - tgUltimoEnvioMs < AVISO_ENTRE_ENVIOS_MS) return;
  for (int a = 0; a < AV_TOTAL; a++) {
    if (!(avisosPendientes & (1 << a))) continue;
    avisosPendientes &= ~(1 << a);
    if (enviarTelegram(textoAviso(a))) avisoEnviadoMs[a] = millis();
    else avisoEnviadoMs[a] = millis() - AVISO_REPETIR_MS + 10UL * 60UL * 1000UL; // si falló, reintenta en 10 min
    break;
  }
}

// =====================================================================
// JSON DE DIAGNÓSTICO
// =====================================================================
void mensajeNivel(const SesionNivel& s, char* buf, size_t len) {
  switch (s.estado) {
    case NIVEL_OK:
      if (s.dispersionMm > NIVEL_DISPERSION_ALTA_MM)
        snprintf(buf, len, "Lectura válida (%d de %d), pero los ecos varían %u mm entre sí: hay rebotes en las paredes. Revisa que el sensor quede centrado y apuntando recto hacia abajo.", s.validas, s.n, s.dispersionMm);
      else
        snprintf(buf, len, "Lectura estable: %d de %d ecos válidos (varían %u mm).", s.validas, s.n, s.dispersionMm);
      break;
    case NIVEL_SIN_ECO:
      if (s.crudaMm > 0)
        snprintf(buf, len, "El eco vuelve de %u mm, más lejos que el fondo configurado (%u mm): se toma como tolva vacía. Si está vacía de verdad, usa esa distancia para calibrar \"sensor-fondo\".", s.crudaMm, DISTANCIA_TOLVA_VACIA_MM);
      else
        snprintf(buf, len, "El sensor responde pero el eco no vuelve: se toma como tolva vacía. Si tiene alimento, revisa que apunte recto hacia abajo.");
      break;
    case NIVEL_MUY_CERCA:
      snprintf(buf, len, "Algo está a menos de %u mm del sensor: tolva sobrellenada o algo pegado a la rejilla.", ZONA_CIEGA_MM);
      break;
    case NIVEL_ERROR:
      snprintf(buf, len, "El pin Echo falló en %d de %d disparos. Revisa soldaduras y que los cables de Trig (D33) y Echo (D32) no se doblen al cerrar la tapa.", s.errores, s.n);
      break;
    case NIVEL_SIN_SENSOR:
      if (s.pegados > 0)
        snprintf(buf, len, "El pin Echo se quedó en alto: el cable de Echo (D32) está en corto con VCC o el módulo está dañado.");
      else
        snprintf(buf, len, "El sensor no responde: Echo nunca subió. Revisa VCC->3V3, GND, Trig->D33 y Echo->D32, y que los pads UART/IIC de atrás estén sin soldar.");
      break;
    default:
      snprintf(buf, len, "Aún no se ha medido desde el arranque.");
      break;
  }
}

void llenarJsonNivel(JsonObject o) {
  const SesionNivel& s = ultimaSesionNivel;
  char msg[240];
  mensajeNivel(s, msg, sizeof(msg));
  o["sensor"] = "RCWL-1601";
  o["estado"] = nombreEstadoNivel(s.estado);
  o["distancia_mm"] = s.medianaMm;
  o["distancia_cruda_mm"] = s.crudaMm;
  o["nivel_pct"] = nivelTolvaPorcentaje;
  o["validas"] = s.validas;
  o["sin_eco"] = s.sinEco;
  o["muy_cerca"] = s.muyCerca;
  o["errores"] = s.errores;
  o["dispersion_mm"] = s.dispersionMm;
  o["temperatura_usada"] = s.temperaturaUsada;
  o["velocidad_sonido"] = s.velocidadSonido;
  o["duracion_ms"] = s.duracionMs;
  o["medido_hace_s"] = segundosDesde(s.finMs);
  o["profundidad_tolva_mm"] = DISTANCIA_TOLVA_VACIA_MM;
  o["zona_ciega_mm"] = ZONA_CIEGA_MM;
  o["sesiones_fallidas_seguidas"] = sesionesNivelFallidas;
  o["mensaje"] = msg;
  JsonArray arr = o.createNestedArray("muestras");
  for (int i = 0; i < s.n; i++) {
    JsonObject m = arr.createNestedObject();
    m["mm"] = s.mm[i];
    m["st"] = s.st[i];
  }
}

void llenarJsonDht(JsonObject o) {
  o["ok"] = dhtOk;
  o["humedad"] = isnan(humedadActual) ? 0 : humedadActual;
  o["temperatura"] = isnan(temperaturaActual) ? 0 : temperaturaActual;
  o["intentos"] = dhtIntentosUltimaLectura;
  o["fallos_seguidos"] = dhtFallosSeguidos;
  o["ultima_lectura_hace_s"] = segundosDesde(dhtUltimaLecturaOkMs);
  if (dhtOk)
    o["mensaje"] = "Lectura correcta.";
  else if (dhtUltimaLecturaOkMs == 0)
    o["mensaje"] = "Nunca ha respondido. Revisa VCC->3V3, GND, DATA->D4 y la resistencia de 10 k entre DATA y VCC.";
  else
    o["mensaje"] = "Dejó de responder. Revisa el cable de datos (D4) y la alimentación.";
}

void llenarJsonRtc(JsonObject o) {
  o["ok"] = rtcOk;
  o["perdio_energia"] = rtcPerdioEnergia;
  o["temperatura_interna"] = isnan(rtcTemperatura) ? 0 : rtcTemperatura;
  o["revisado_hace_s"] = segundosDesde(rtcUltimaRevisionMs);
  if (rtcOk) {
    DateTime n = rtc.now();
    char f[16], h[16];
    snprintf(f, sizeof(f), "%04d-%02d-%02d", n.year(), n.month(), n.day());
    snprintf(h, sizeof(h), "%02d:%02d:%02d", n.hour(), n.minute(), n.second());
    o["fecha"] = f;
    o["hora"] = h;
    o["mensaje"] = rtcPerdioEnergia
      ? "Responde, pero perdió la hora en algún momento (¿pila agotada?). Sincronízala desde la app."
      : "Responde y la hora es válida.";
  } else {
    o["fecha"] = "";
    o["hora"] = "";
    o["mensaje"] = "No responde. Revisa VCC->3V3, GND, SDA->D21 y SCL->D22. Sin reloj no se disparan los horarios.";
  }
}

void llenarJsonSistema(JsonObject o) {
  o["wifi_rssi"] = WiFi.RSSI();
  o["heap_libre"] = ESP.getFreeHeap();
  o["loop_max_ms"] = loopMaxMs;
  o["uptime_s"] = millis() / 1000;
  o["proxima_lectura_s"] = segundosParaProximaLectura();
  o["ciclo_min"] = INTERVALO_CICLO_MS / 60000UL;
}

// =====================================================================
// ESTADO Y CONFIGURACIÓN (lo usan el panel web y la app Android)
// =====================================================================
void llenarEstado(JsonObject doc) {
  doc["humedad"] = isnan(humedadActual) ? 0 : humedadActual;
  doc["temperatura"] = isnan(temperaturaActual) ? 0 : temperaturaActual;
  doc["dht_ok"] = dhtOk;
  doc["dht_hace_s"] = segundosDesde(dhtUltimaLecturaOkMs);
  doc["distancia_mm"] = distanciaActualMM;
  doc["nivel_tolva_pct"] = nivelTolvaPorcentaje;
  doc["nivel_estado"] = nombreEstadoNivel(estadoNivel);
  doc["nivel_medido_hace_s"] = segundosDesde(nivelMedidoMs);
  doc["sensor_nivel_ok"] = nivelEsConfiable(estadoNivel);
  doc["tolva_vacia"] = tolvaVaciaAlerta;
  doc["tolva_baja"] = tolvaBajaAlerta;
  doc["posible_atasco"] = posibleAtascoAlerta;
  doc["humedad_alta"] = humedadAltaAlerta;
  doc["temperatura_alta"] = temperaturaAltaAlerta;
  doc["temperatura_baja"] = temperaturaBajaAlerta;
  doc["ultima_alimentacion"] = ultimaAlimentacion;
  doc["ultima_porciones"] = ultimaPorciones;
  doc["horarios_pausados"] = horariosPausados;
  doc["rtc_ok"] = rtcOk;
  doc["proxima_lectura_s"] = segundosParaProximaLectura();
  doc["ciclo_min"] = INTERVALO_CICLO_MS / 60000UL;
  if (rtcOk) {
    DateTime ahora = rtc.now();
    char f[16];
    snprintf(f, sizeof(f), "%04d-%02d-%02d", ahora.year(), ahora.month(), ahora.day());
    doc["hora_actual"] = fechaHoraTexto(ahora, false);
    doc["fecha_actual"] = f;
    doc["epoch"] = ahora.unixtime();
  } else {
    doc["hora_actual"] = "--:--";
    doc["fecha_actual"] = "";
    doc["epoch"] = 0;
  }
  doc["modo_ap"] = modoAP;
  doc["wifi_ssid"] = modoAP ? String(AP_SSID) : WiFi.SSID();
  doc["wifi_rssi"] = modoAP ? 0 : WiFi.RSSI();
  doc["ip"] = modoAP ? WiFi.softAPIP().toString() : WiFi.localIP().toString();
}

void llenarHorarios(JsonArray arr) {
  for (int i = 0; i < numHorarios; i++) {
    JsonObject o = arr.createNestedObject();
    o["hora"] = horarios[i].hora;
    o["minuto"] = horarios[i].minuto;
    o["porciones"] = horarios[i].porciones;
  }
}

void llenarUmbrales(JsonObject doc) {
  doc["humedad_max"] = UMBRAL_HUMEDAD_PORCENTAJE;
  doc["temperatura_min"] = UMBRAL_TEMP_MIN;
  doc["temperatura_max"] = UMBRAL_TEMP_MAX;
  doc["tolva_llena_mm"] = DISTANCIA_TOLVA_LLENA_MM;
  doc["tolva_vacia_mm"] = DISTANCIA_TOLVA_VACIA_MM;
  doc["cambio_minimo_mm"] = UMBRAL_CAMBIO_MINIMO_MM;
}

void llenarConfig(JsonObject doc) {
  llenarHorarios(doc.createNestedArray("horarios"));
  llenarUmbrales(doc.createNestedObject("umbrales"));
  doc["pausado"] = horariosPausados;
  JsonObject tg = doc.createNestedObject("telegram");
  tg["configurado"] = telegramConfigurado();
  tg["chat_id"] = tgChat;
  doc["ip_fija"] = ipFijaActiva;
}

// Formato compacto de una lectura: ts, h (humedad), t (temperatura), n (nivel %, -1 desconocido),
// e (estado del nivel).
void jsonLectura(const RegistroLectura& r, char* buf, size_t len) {
  char hTxt[10], tTxt[10];
  if (r.h10 == INT16_MIN) strcpy(hTxt, "null"); else snprintf(hTxt, sizeof(hTxt), "%.1f", r.h10 / 10.0f);
  if (r.t10 == INT16_MIN) strcpy(tTxt, "null"); else snprintf(tTxt, sizeof(tTxt), "%.1f", r.t10 / 10.0f);
  snprintf(buf, len, "{\"ts\":%lu,\"h\":%s,\"t\":%s,\"n\":%d,\"e\":%u}",
           (unsigned long)r.ts, hTxt, tTxt, r.nivel, r.estado);
}

// p porciones, o origen (0 programada, 1 manual), na/nd nivel antes/después (-1 desconocido), e estado.
void jsonAlimentacion(const RegistroAlimentacion& r, char* buf, size_t len) {
  snprintf(buf, len, "{\"ts\":%lu,\"p\":%u,\"o\":%u,\"na\":%d,\"nd\":%d,\"e\":%u}",
           (unsigned long)r.ts, r.porciones, r.origen, r.nivelAntes, r.nivelDespues, r.estado);
}

// Cada aplicar*() valida, guarda y devuelve nullptr, o devuelve el motivo del error sin cambiar nada.

// Acepta un arreglo [{hora,minuto,porciones}] (también un objeto {"0":{...},"1":{...}}).
const char* aplicarHorarios(JsonVariantConst lista) {
  HorarioAlimentacion nuevos[MAX_HORARIOS];
  int n = 0;
  auto agregar = [&](JsonVariantConst o) {
    if (n >= MAX_HORARIOS) return;
    int h = o["hora"] | -1, m = o["minuto"] | -1;
    if (h < 0 || h > 23 || m < 0 || m > 59) return;
    nuevos[n].hora = h;
    nuevos[n].minuto = m;
    nuevos[n].porciones = constrain((int)(o["porciones"] | 1), 1, PORCIONES_MAX);
    nuevos[n].disparadoHoy = false;
    n++;
  };
  if (lista.is<JsonArrayConst>()) {
    for (JsonVariantConst o : lista.as<JsonArrayConst>()) agregar(o);
  } else if (lista.is<JsonObjectConst>()) {
    for (JsonPairConst kv : lista.as<JsonObjectConst>()) agregar(kv.value());
  } else if (!lista.isNull()) {
    return "Los horarios no tienen el formato esperado.";
  }
  for (int i = 0; i < n; i++) horarios[i] = nuevos[i];
  numHorarios = n;
  guardarHorarios();
  return nullptr;
}

// Los campos que falten conservan su valor.
const char* aplicarUmbrales(JsonVariantConst doc) {
  // Todo se lee como float: así "95", 95 y 95.0 sirven igual.
  float humMax = doc["humedad_max"] | UMBRAL_HUMEDAD_PORCENTAJE;
  float tMin   = doc["temperatura_min"] | UMBRAL_TEMP_MIN;
  float tMax   = doc["temperatura_max"] | UMBRAL_TEMP_MAX;
  float cambio = doc["cambio_minimo_mm"] | UMBRAL_CAMBIO_MINIMO_MM;
  long llena   = lroundf(doc["tolva_llena_mm"] | (float)DISTANCIA_TOLVA_LLENA_MM);
  long vacia   = lroundf(doc["tolva_vacia_mm"] | (float)DISTANCIA_TOLVA_VACIA_MM);

  if (isnan(humMax) || humMax <= 0 || humMax > 100)     return "La humedad máxima tiene que estar entre 1 y 100.";
  if (isnan(tMin) || isnan(tMax) || tMin >= tMax)      return "La temperatura mínima tiene que ser menor que la máxima.";
  if (llena < ZONA_CIEGA_MM)                           return "La distancia con la tolva llena no puede ser menor que la zona ciega del sensor (25 mm).";
  if (vacia > 1000)                                    return "La distancia con la tolva vacía es demasiado grande (máx. 1000 mm).";
  if (llena >= vacia)                                  return "La distancia con la tolva llena tiene que ser menor que con la tolva vacía.";
  if (isnan(cambio) || cambio <= 0 || cambio > 50)     return "La bajada mínima por alimentación tiene que estar entre 0 y 50 mm.";

  UMBRAL_HUMEDAD_PORCENTAJE = humMax;
  UMBRAL_TEMP_MIN = tMin;
  UMBRAL_TEMP_MAX = tMax;
  UMBRAL_CAMBIO_MINIMO_MM = cambio;
  DISTANCIA_TOLVA_LLENA_MM = (uint16_t)llena;
  DISTANCIA_TOLVA_VACIA_MM = (uint16_t)vacia;
  guardarUmbrales();
  if (estadoNivel == NIVEL_OK) nivelTolvaPorcentaje = calcularNivelPct(distanciaActualMM);
  if (dhtOk) {
    humedadAltaAlerta = humedadActual >= UMBRAL_HUMEDAD_PORCENTAJE;
    temperaturaAltaAlerta = temperaturaActual >= UMBRAL_TEMP_MAX;
    temperaturaBajaAlerta = temperaturaActual <= UMBRAL_TEMP_MIN;
  }
  actualizarAlertasNivel();
  return nullptr;
}

// {"token":"...", "chat_id":"..."} o {"borrar": true}. Si no viene token, se conserva el que había.
const char* aplicarTelegram(JsonVariantConst doc) {
  if (doc["borrar"] | false) {
    tgToken = "";
    tgChat = "";
  } else {
    String token = doc["token"] | "";
    String chat = doc["chat_id"] | "";
    token.trim();
    chat.trim();
    if (token.length() > 0 && token.indexOf(':') < 0) return "El token no tiene el formato de BotFather (numero:letras).";
    if (chat.length() == 0) return "Falta el chat id.";
    if (token.length() > 0) tgToken = token;
    tgChat = chat;
  }
  guardarTelegram();
  for (int a = 0; a < AV_TOTAL; a++) avisoActivo[a] = false; // que vuelva a avisar lo que esté activo
  return nullptr;
}

// {"anio":2026,"mes":10,"dia":25,"hora":14,"minuto":30,"segundo":0} (hora local del celular)
void aplicarHora(JsonVariantConst doc) {
  rtc.adjust(DateTime(doc["anio"] | 2026, doc["mes"] | 1, doc["dia"] | 1,
                      doc["hora"] | 0, doc["minuto"] | 0, doc["segundo"] | 0));
  revisarRTC(false);
  lastMinuteChecked = -1;
}

// =====================================================================
// HORA POR INTERNET (NTP -> DS3231)
// =====================================================================
// configTime() en setup() deja corriendo SNTP. Esto revisa cada 30 s hasta tener hora de internet;
// después, cada 6 h. Solo corrige si el DS3231 se desvió más de 20 s.
void ajustarRelojPorInternet() {
  if (!AJUSTAR_HORA_POR_INTERNET || modoAP) return;
  static unsigned long ultimoMs = 0;
  static bool yaAjusto = false;
  unsigned long espera = yaAjusto ? 6UL * 3600UL * 1000UL : 30000UL;
  if (ultimoMs != 0 && millis() - ultimoMs < espera) return;
  ultimoMs = millis();
  time_t utc = time(nullptr);
  if (utc < 1700000000 || !rtcOk) return; // SNTP todavía sin respuesta, o sin reloj
  yaAjusto = true;
  uint32_t local = (uint32_t)(utc + ZONA_HORARIA_S);
  long dif = (long)local - (long)rtc.now().unixtime();
  if (labs(dif) > 20) {
    rtc.adjust(DateTime(local));
    revisarRTC(false);
      Serial.printf("[hora] DS3231 corregido con la hora de internet (%+ld s)\n", dif);
  }
}

// =====================================================================
// HANDLERS HTTP
// =====================================================================
void handleRoot() {
  server.send_P(200, "text/html", PAGINA_HTML);
}

void handleStatus() {
  DynamicJsonDocument doc(2048);
  llenarEstado(doc.to<JsonObject>());
  doc["firmware"] = FIRMWARE_VERSION;
  enviarJson(doc);
}

void responderError(const char* error) {
  StaticJsonDocument<384> r;
  r["error"] = error;
  String salida;
  serializeJson(r, salida);
  server.send(400, "application/json", salida);
}

void handleFeedNow() {
  int porciones = server.hasArg("porciones") ? server.arg("porciones").toInt() : 1;
  porciones = constrain(porciones, 1, PORCIONES_MAX);
  // Se responde antes de mover el servo: alimentar() bloquea varios segundos.
  server.send(200, "application/json", "{\"ok\":true}");
  alimentar(porciones, ORIGEN_MANUAL);
}

void handleGetSchedule() {
  StaticJsonDocument<1024> doc;
  llenarHorarios(doc.to<JsonArray>());
  enviarJson(doc);
}

void handleSetSchedule() {
  StaticJsonDocument<1024> doc;
  if (!leerCuerpoJson(doc)) return;
  const char* error = aplicarHorarios(doc.as<JsonVariantConst>());
  if (error) { responderError(error); return; }
  server.send(200, "application/json", "{\"ok\":true}");
}

void handleGetWifiConfig() {
  String ssid, password;
  cargarCredencialesWiFi(ssid, password);
  StaticJsonDocument<256> doc;
  doc["ssid"] = ssid;
  doc["modo_ap"] = modoAP;
  enviarJson(doc);
}

void handlePostWifiConfig() {
  StaticJsonDocument<256> doc;
  if (!leerCuerpoJson(doc)) return;
  String nuevoSsid = doc["ssid"] | "";
  String nuevaPass = doc["password"] | "";
  if (nuevoSsid.length() == 0) {
    server.send(400, "application/json", "{\"error\":\"ssid vacio\"}");
    return;
  }
  guardarCredencialesWiFi(nuevoSsid, nuevaPass);
  server.send(200, "application/json", "{\"ok\":true,\"mensaje\":\"Reiniciando para conectar a la nueva red...\"}");
  server.handleClient();
  delay(500);
  ESP.restart();
}

// body: {"anio":2026,"mes":10,"dia":25,"hora":14,"minuto":30,"segundo":0} (hora local del celular)
void handleSetTime() {
  StaticJsonDocument<256> doc;
  if (!leerCuerpoJson(doc)) return;
  aplicarHora(doc.as<JsonVariantConst>());
  server.send(200, "application/json", "{\"ok\":true}");
}

void handleGetPause() {
  StaticJsonDocument<64> doc;
  doc["pausado"] = horariosPausados;
  enviarJson(doc);
}

void handleSetPause() {
  StaticJsonDocument<128> doc;
  if (!leerCuerpoJson(doc)) return;
  guardarPausa(doc["pausado"] | false);
  server.send(200, "application/json", "{\"ok\":true}");
}

void handleGetThresholds() {
  StaticJsonDocument<256> doc;
  llenarUmbrales(doc.to<JsonObject>());
  enviarJson(doc);
}

// Responde 400 con {"error": "..."} si algo no tiene sentido, en vez de guardarlo en silencio.
void handleSetThresholds() {
  StaticJsonDocument<384> doc;
  if (!leerCuerpoJson(doc)) return;
  const char* error = aplicarUmbrales(doc.as<JsonVariantConst>());
  if (error) { responderError(error); return; }
  server.send(200, "application/json", "{\"ok\":true}");
}

// GET /api/diagnostics -> estado guardado de los tres sensores (no dispara nada).
void handleGetDiagnostics() {
  DynamicJsonDocument doc(4096);
  llenarJsonNivel(doc.createNestedObject("nivel"));
  llenarJsonDht(doc.createNestedObject("dht22"));
  llenarJsonRtc(doc.createNestedObject("rtc"));
  llenarJsonSistema(doc.createNestedObject("sistema"));
  enviarJson(doc);
}

// POST /api/diagnostics/nivel -> 10 pulsos del sensor de nivel, con el detalle de cada uno.
void handleTestNivel() {
  medirNivel(NIVEL_MUESTRAS_DIAGNOSTICO);
  referenciaAtascoSinAlimentar();
  posponerCiclo(SEPARACION_PASOS_MS);
  DynamicJsonDocument doc(2048);
  llenarJsonNivel(doc.to<JsonObject>());
  enviarJson(doc);
}

// POST /api/diagnostics/dht22 -> una lectura, y un reintento a los 2,1 s si falla.
void handleTestDht22() {
  operacionLarga = true;
  dhtIntentosUltimaLectura = 1;
  if (!leerDHT()) {
    delay(2100);
    dhtIntentosUltimaLectura = 2;
    leerDHT();
  }
  posponerCiclo(SEPARACION_PASOS_MS);
  StaticJsonDocument<512> doc;
  llenarJsonDht(doc.to<JsonObject>());
  enviarJson(doc);
}

// POST /api/diagnostics/rtc -> rtc.begin() de nuevo + lectura de hora y temperatura interna.
void handleTestRtc() {
  revisarRTC(true);
  StaticJsonDocument<512> doc;
  llenarJsonRtc(doc.to<JsonObject>());
  enviarJson(doc);
}

// POST /api/diagnostics -> los tres, uno después del otro (compatibilidad con la app vieja).
void handleTestAll() {
  revisarRTC(true);
  delay(300);
  dhtIntentosUltimaLectura = 1;
  leerDHT();
  delay(300);
  medirNivel(NIVEL_MUESTRAS_DIAGNOSTICO);
  referenciaAtascoSinAlimentar();
  posponerCiclo(SEPARACION_PASOS_MS);
  handleGetDiagnostics();
}

uint32_t argDesde() {
  if (!server.hasArg("desde")) return 0;
  return strtoul(server.arg("desde").c_str(), nullptr, 10);
}

// GET /api/history?desde=<epoch> -> lecturas del ciclo con ts > desde, en orden cronológico
// (formato en jsonLectura()).
void handleHistory() {
  uint32_t desde = argDesde();
  String out;
  out.reserve(histCuenta * 52 + 4);
  out += '[';
  bool primero = true;
  char buf[96];
  for (int k = 0; k < histCuenta; k++) {
    const RegistroLectura& r = historial[(histInicio + k) % HIST_MAX];
    if (r.ts <= desde) continue;
    if (!primero) out += ',';
    jsonLectura(r, buf, sizeof(buf));
    out += buf;
    primero = false;
  }
  out += ']';
  server.send(200, "application/json", out);
}

// GET /api/feedings?desde=<epoch> -> alimentaciones con ts > desde (formato en jsonAlimentacion()).
void handleFeedings() {
  uint32_t desde = argDesde();
  String out;
  out.reserve(alimCuenta * 64 + 4);
  out += '[';
  bool primero = true;
  char buf[96];
  for (int k = 0; k < alimCuenta; k++) {
    const RegistroAlimentacion& r = alimentaciones[(alimInicio + k) % ALIM_MAX];
    if (r.ts <= desde) continue;
    if (!primero) out += ',';
    jsonAlimentacion(r, buf, sizeof(buf));
    out += buf;
    primero = false;
  }
  out += ']';
  server.send(200, "application/json", out);
}

// GET /api/red -> red actual, IP y estado de la IP fija.
void handleGetRed() {
  StaticJsonDocument<512> doc;
  doc["ssid"] = modoAP ? String(AP_SSID) : WiFi.SSID();
  doc["ip"] = modoAP ? WiFi.softAPIP().toString() : WiFi.localIP().toString();
  doc["gateway"] = WiFi.gatewayIP().toString();
  doc["mascara"] = WiFi.subnetMask().toString();
  doc["ip_fija"] = ipFijaActiva;
  doc["ip_guardada"] = ipGuardadaTexto;
  doc["estado"] = ipEstado;
  doc["mdns"] = String("http://") + MDNS_HOSTNAME + ".local";
  enviarJson(doc);
}

// POST /api/red {"ip_fija": true|false}. Al desactivarla vuelve a DHCP en ese momento
// (la IP puede cambiar y la página perder la conexión unos segundos).
void handleSetRed() {
  StaticJsonDocument<128> doc;
  if (!leerCuerpoJson(doc)) return;
  bool fija = doc["ip_fija"] | true;
  ipFijaActiva = fija;
  prefs.begin("red", false);
  prefs.putBool("fija", fija);
  prefs.end();
  server.send(200, "application/json", "{\"ok\":true}");
  if (!fija) {
    olvidarRed();
    ipEstado = "IP por DHCP: el router decide y puede cambiar.";
    server.handleClient();
    delay(300);
    WiFi.config(INADDR_NONE, INADDR_NONE, INADDR_NONE); // vuelve a pedir IP por DHCP
  } else if (!modoAP && WiFi.status() == WL_CONNECTED) {
    fijarIpSiCorresponde();
  }
}

// GET /api/telegram -> si está configurado (el token nunca se devuelve).
void handleGetTelegram() {
  StaticJsonDocument<256> doc;
  doc["configurado"] = telegramConfigurado();
  doc["chat_id"] = tgChat;
  doc["ultimo_codigo"] = tgUltimoCodigo;
  doc["ultimo_envio_hace_s"] = segundosDesde(tgUltimoEnvioMs);
  enviarJson(doc);
}

// POST /api/telegram {"token":"...", "chat_id":"..."} o {"borrar": true}. Si no viene token,
// se conserva el que ya estaba.
void handleSetTelegram() {
  StaticJsonDocument<512> doc;
  if (!leerCuerpoJson(doc)) return;
  const char* error = aplicarTelegram(doc.as<JsonVariantConst>());
  if (error) { responderError(error); return; }
  server.send(200, "application/json", "{\"ok\":true}");
}

// POST /api/telegram/prueba -> manda un mensaje de prueba y devuelve el código de Telegram.
void handleTestTelegram() {
  if (!telegramConfigurado()) {
    server.send(400, "application/json", "{\"error\":\"Primero guarda el token y el chat id.\"}");
    return;
  }
  bool ok = enviarTelegram("🐟 Prueba del dispensador (" + String(MDNS_HOSTNAME) + "): los avisos llegan bien.");
  StaticJsonDocument<96> doc;
  doc["ok"] = ok;
  doc["codigo"] = tgUltimoCodigo;
  enviarJson(doc);
}

void setupServidorWeb() {
  server.on("/", HTTP_GET, handleRoot);
  server.on("/api/status", HTTP_GET, handleStatus);
  server.on("/api/feed", HTTP_POST, handleFeedNow);
  server.on("/api/schedule", HTTP_GET, handleGetSchedule);
  server.on("/api/schedule", HTTP_POST, handleSetSchedule);
  server.on("/api/wifi-config", HTTP_GET, handleGetWifiConfig);
  server.on("/api/wifi-config", HTTP_POST, handlePostWifiConfig);
  server.on("/api/time", HTTP_POST, handleSetTime);
  server.on("/api/pause", HTTP_GET, handleGetPause);
  server.on("/api/pause", HTTP_POST, handleSetPause);
  server.on("/api/thresholds", HTTP_GET, handleGetThresholds);
  server.on("/api/thresholds", HTTP_POST, handleSetThresholds);
  server.on("/api/diagnostics", HTTP_GET, handleGetDiagnostics);
  server.on("/api/diagnostics", HTTP_POST, handleTestAll);
  server.on("/api/diagnostics/nivel", HTTP_POST, handleTestNivel);
  server.on("/api/diagnostics/dht22", HTTP_POST, handleTestDht22);
  server.on("/api/diagnostics/rtc", HTTP_POST, handleTestRtc);
  server.on("/api/history", HTTP_GET, handleHistory);
  server.on("/api/feedings", HTTP_GET, handleFeedings);
  server.on("/api/red", HTTP_GET, handleGetRed);
  server.on("/api/red", HTTP_POST, handleSetRed);
  server.on("/api/telegram", HTTP_GET, handleGetTelegram);
  server.on("/api/telegram", HTTP_POST, handleSetTelegram);
  server.on("/api/telegram/prueba", HTTP_POST, handleTestTelegram);
  server.onNotFound([]() { server.send(404, "application/json", "{\"error\":\"no existe\"}"); });
  server.begin();
}

// =====================================================================
// SETUP / LOOP
// =====================================================================
void setup() {
  Serial.begin(115200);

  pinMode(PIN_TRIG_NIVEL, OUTPUT);
  digitalWrite(PIN_TRIG_NIVEL, LOW);
  pinMode(PIN_ECHO_NIVEL, INPUT_PULLDOWN); // sin sensor, Echo queda en bajo y se detecta como "no responde"
  pinMode(PIN_LIBRE_25, INPUT);

  ESP32PWM::allocateTimer(0);
  servoTornillo.setPeriodHertz(50);
  servoTornillo.attach(PIN_SERVO, 500, 2400);
  servoTornillo.write(SERVO_ANGULO_REPOSO);

  Wire.begin(PIN_SDA, PIN_SCL);

  dht.begin();
  cargarUmbrales();
  cargarHorarios();
  cargarPausa();
  cargarConfigRed();
  cargarTelegram();

  revisarRTC(true);
  if (!rtcOk) Serial.println("ERROR: no se detectó el DS3231. Revisa SDA=D21, SCL=D22 y 3V3.");
  else if (rtcPerdioEnergia) rtc.adjust(DateTime(F(__DATE__), F(__TIME__)));

  conectarWiFi();
  configTime(0, 0, "pool.ntp.org", "time.google.com"); // UTC; ajustarRelojPorInternet() le suma la zona horaria
  setupServidorWeb();

  // El primer ciclo arranca en loop(): DHT22 -> RTC -> nivel (solo este primer ciclo) -> historial,
  // cada uno en su turno.
  Serial.println("Sistema listo.");
}

void loop() {
  unsigned long t0 = millis();
  operacionLarga = false;

  server.handleClient();
  revisarHorarios();
  atenderCicloSensores();
  vigilarWiFi();
  atenderAvisos();
  ajustarRelojPorInternet();

  unsigned long dt = millis() - t0;
  if (!operacionLarga) {
    if (dt > loopMaxMs) loopMaxMs = dt;
    if (dt > 300) Serial.printf("AVISO: loop() tardó %lu ms sin una operación larga en curso\n", dt);
  }

  static unsigned long ultimoInforme = 0;
  if (millis() - ultimoInforme > 60000) {
    ultimoInforme = millis();
    Serial.printf("[diag] wifi=%d rssi=%d heap=%lu loop_max=%lu ms · nivel=%s %d%% · dht=%s · rtc=%s · próxima lectura en %ld s\n",
                  WiFi.status() == WL_CONNECTED, WiFi.RSSI(), (unsigned long)ESP.getFreeHeap(), loopMaxMs,
                  nombreEstadoNivel(estadoNivel), nivelTolvaPorcentaje, dhtOk ? "ok" : "falla",
                  rtcOk ? "ok" : "falla", segundosParaProximaLectura());
  }
}
