// Panel web del dispensador (lo sirve el ESP32 en http://pezfeeder.local).
// Va en un .h aparte porque el preprocesador de Arduino (ctags) confunde el JavaScript con C++
// cuando está dentro del .ino y genera prototipos rotos. Tiene que estar en la misma carpeta que el .ino.
// Versión corta a propósito (~15 KB): el WebServer del ESP32 atiende una petición a la vez y mientras
// manda la página no corre loop(). El historial completo lo tiene la app Android.
#pragma once
#include <Arduino.h>

const char PAGINA_HTML[] PROGMEM = R"HTMLPAGE(<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes">
<title>Dispensador</title>
<style>
:root{--bg:#f2f4f7;--card:#fff;--text:#0f1b2a;--muted:#66768a;--line:#e3e8ee;--soft:#eef2f6;--acc:#0e9f8f;--warn:#c27a00;--warn-s:#fdf1dc;--bad:#d64545;--bad-s:#fbe5e5;--ok:#1f9d55}
@media (prefers-color-scheme:dark){:root{--bg:#0b1219;--card:#141d27;--text:#e8eef4;--muted:#8b9bb0;--line:#223040;--soft:#1b2633;--acc:#2cc4b0;--warn:#f0b24a;--warn-s:#33270f;--bad:#ff7676;--bad-s:#3a1a1c;--ok:#4fd88a}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font:15px/1.4 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
main{max-width:520px;margin:0 auto;padding:calc(env(safe-area-inset-top) + 12px) 14px calc(env(safe-area-inset-bottom) + 24px)}
header{display:flex;align-items:center;gap:8px;margin:0 4px 12px}
h1{font-size:22px;margin:0;flex:1}
h2{font-size:15px;margin:0 0 10px}
.dot{width:8px;height:8px;border-radius:50%;background:var(--muted)}.dot.on{background:var(--ok)}.dot.off{background:var(--bad)}
.card{background:var(--card);border-radius:16px;padding:14px;margin-bottom:10px}
.row{display:flex;align-items:center;gap:10px}.grow{flex:1}
.sub{color:var(--muted);font-size:12.5px}
.big{font-size:26px;font-weight:700}
.al{border-radius:12px;padding:9px 12px;margin-bottom:8px;font-size:14px;font-weight:600}
.al.w{background:var(--warn-s);color:var(--warn)}.al.d{background:var(--bad-s);color:var(--bad)}.al.i{background:var(--soft);color:var(--muted)}
.ring{--p:0;width:96px;height:96px;border-radius:50%;flex:none;display:grid;place-items:center;background:conic-gradient(var(--acc) calc(var(--p)*1%),var(--soft) 0)}
.ring b{width:74px;height:74px;border-radius:50%;background:var(--card);display:grid;place-items:center;font-size:22px}
button,.btn{font:inherit;font-weight:600;border:0;border-radius:11px;padding:10px 13px;background:var(--acc);color:#fff;cursor:pointer;text-decoration:none;display:inline-block;text-align:center}
button.s{background:var(--soft);color:var(--text)}button.x{background:var(--bad-s);color:var(--bad)}
button:disabled{opacity:.5}.w100{width:100%}
input,select{font:inherit;color:var(--text);background:var(--soft);border:1px solid transparent;border-radius:9px;padding:8px 9px;width:100%;min-width:0}
label{display:block;margin-bottom:8px}label span{display:block;font-size:12.5px;color:var(--muted);margin-bottom:3px}
.g2{display:grid;grid-template-columns:1fr 1fr;gap:8px}
.hor{display:grid;grid-template-columns:1fr 84px 36px;gap:6px;margin-bottom:6px}
details.card{padding:0}summary{padding:14px;font-weight:600;cursor:pointer}details>div{padding:0 14px 14px}
.mt{margin-top:8px}.pre{white-space:pre-line}
#toast{position:fixed;left:50%;bottom:24px;transform:translateX(-50%);background:var(--text);color:var(--bg);padding:9px 14px;border-radius:11px;font-weight:600;display:none;max-width:90vw}
#toast.e{background:var(--bad);color:#fff}
</style>
</head>
<body>
<main>
<header><h1>Dispensador</h1><span class="dot" id="dot"></span><span class="sub" id="hora">--:--</span></header>
<div id="alertas"></div>

<div class="card row">
  <div class="ring" id="ring"><b id="nivel">--</b></div>
  <div class="grow">
    <div class="sub" id="nivelTxt">Tolva</div>
    <div class="row mt"><div class="grow"><div class="sub">Humedad</div><div class="big" id="hum">--</div></div>
    <div class="grow"><div class="sub">Temp.</div><div class="big" id="temp">--</div></div></div>
    <button class="s mt" id="bMedir" onclick="medir(false)">Medir nivel</button>
  </div>
</div>

<div class="card">
  <h2>Alimentar ahora</h2>
  <div class="row"><select id="porc" style="width:110px"><option value="1">1 porción</option><option value="2">2</option><option value="3">3</option></select>
  <button class="grow" id="bAlim" onclick="alimentar()">Alimentar</button></div>
  <div class="sub mt">Última: <b id="ultima">--</b></div>
</div>

<details class="card"><summary>Horarios</summary><div>
  <div class="row" style="margin-bottom:10px"><span class="grow">Modo vacaciones (pausa los horarios)</span><input type="checkbox" id="pausa" style="width:auto" onchange="pausar(this.checked)"></div>
  <div id="lista"></div>
  <div class="row mt"><button class="s grow" onclick="H.push({hora:12,minuto:0,porciones:1});pintar()">+ Agregar</button><button class="grow" onclick="guardarH()">Guardar</button></div>
</div></details>

<details class="card" ontoggle="if(this.open)cargarU()"><summary>Umbrales y calibración</summary><div>
  <div class="g2">
    <label><span>Humedad máx. (%)</span><input id="u_humedad_max" inputmode="decimal"></label>
    <label><span>Bajada mín. (mm)</span><input id="u_cambio_minimo_mm" inputmode="decimal"></label>
    <label><span>Temp. mín. (°C)</span><input id="u_temperatura_min" inputmode="decimal"></label>
    <label><span>Temp. máx. (°C)</span><input id="u_temperatura_max" inputmode="decimal"></label>
    <label><span>Tolva llena (mm)</span><input id="u_tolva_llena_mm" inputmode="numeric"></label>
    <label><span>Tolva vacía (mm)</span><input id="u_tolva_vacia_mm" inputmode="numeric"></label>
  </div>
  <div class="row"><button class="s" onclick="medir(true)">Medir distancia</button><span class="sub grow" id="medida">--</span></div>
  <div class="row mt"><button class="s grow" onclick="usar('u_tolva_llena_mm')">Usar en llena</button><button class="s grow" onclick="usar('u_tolva_vacia_mm')">Usar en vacía</button></div>
  <button class="w100 mt" onclick="guardarU()">Guardar umbrales</button>
</div></details>

<details class="card" ontoggle="if(this.open)cargarT()"><summary>Avisos por Telegram</summary><div>
  <div class="sub" id="tgEstado" style="margin-bottom:8px">--</div>
  <label><span>Token del bot (@BotFather)</span><input type="password" id="tgToken" placeholder="Vacío = conservar el actual"></label>
  <label><span>Chat id (@userinfobot)</span><input id="tgChat" inputmode="numeric"></label>
  <div class="row"><button class="grow" onclick="guardarT()">Guardar</button><button class="s grow" onclick="probarT()">Prueba</button><button class="x" onclick="borrarT()">Quitar</button></div>
</div></details>

<details class="card" ontoggle="if(this.open)cargarR()"><summary>Red, IP y hora</summary><div>
  <div class="sub pre" id="red">--</div>
  <div class="row mt"><span class="grow">Mantener siempre la misma IP</span><input type="checkbox" id="ipFija" style="width:auto" onchange="ipFija(this.checked)"></div>
  <button class="s w100 mt" onclick="ponerHora()">Poner la hora de este teléfono</button>
  <h2 class="mt">Cambiar de red WiFi</h2>
  <label><span>Nombre de la red</span><input id="ssid"></label>
  <label><span>Contraseña</span><input type="password" id="pass"></label>
  <button class="s w100" onclick="cambiarWifi()">Guardar y reiniciar</button>
</div></details>
<div class="sub" style="text-align:center" id="sistema"></div>
</main>
<div id="toast"></div>
<script>
const $ = id => document.getElementById(id);
let H = [], medida = null, tt;
const NIV = {ok:'Lectura normal', sin_eco:'Vacía (sin eco)', muy_cerca:'Muy cerca: ¿sobrellenada?', error:'Sensor fallando', sin_sensor:'Sensor no responde', sin_medir:'Sin medir'};
const U = ['humedad_max','cambio_minimo_mm','temperatura_min','temperatura_max','tolva_llena_mm','tolva_vacia_mm'];
const n1 = (v, d) => Number(v).toFixed(d).replace('.', ',');
const p2 = n => String(n).padStart(2, '0');
function toast(m, e){ const t = $('toast'); t.textContent = m; t.className = e ? 'e' : ''; t.style.display = 'block'; clearTimeout(tt); tt = setTimeout(() => t.style.display = 'none', 3200); }
async function api(path, body, metodo){
  const r = await fetch(path, body !== undefined ? {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)} : {method:metodo || 'GET'});
  let d = null; try { d = await r.json(); } catch(e) {}
  if (!r.ok) throw new Error((d && d.error) || 'HTTP ' + r.status);
  return d;
}
async function estado(){
  try {
    const d = await api('/api/status');
    $('dot').className = 'dot on'; $('hora').textContent = d.hora_actual;
    const ok = ['ok','sin_eco','muy_cerca'].includes(d.nivel_estado) && d.nivel_tolva_pct >= 0;
    $('ring').style.setProperty('--p', ok ? d.nivel_tolva_pct : 0);
    $('nivel').textContent = ok ? d.nivel_tolva_pct + '%' : '--';
    $('nivelTxt').textContent = 'Tolva: ' + (NIV[d.nivel_estado] || d.nivel_estado);
    $('hum').textContent = d.dht_ok ? n1(d.humedad, 0) + '%' : '--';
    $('temp').textContent = d.dht_ok ? n1(d.temperatura, 1) + '°' : '--';
    $('ultima').textContent = d.ultima_alimentacion;
    $('pausa').checked = !!d.horarios_pausados;
    const a = [];
    if (!ok && d.nivel_estado !== 'sin_medir') a.push(['d', 'Sensor de nivel: ' + NIV[d.nivel_estado]]);
    else if (d.tolva_vacia) a.push(['d', 'La tolva está vacía']);
    else if (d.tolva_baja) a.push(['w', 'Queda poco alimento']);
    if (!d.rtc_ok) a.push(['d', 'El reloj no responde: los horarios no se disparan']);
    if (d.humedad_alta) a.push(['w', 'Humedad alta en el alimento']);
    if (d.temperatura_alta) a.push(['w', 'Temperatura alta']);
    if (d.temperatura_baja) a.push(['w', 'Temperatura baja']);
    if (d.posible_atasco) a.push(['d', 'Posible atasco del tornillo']);
    if (d.horarios_pausados) a.push(['i', 'Modo vacaciones: horarios en pausa']);
    $('alertas').innerHTML = a.map(x => '<div class="al ' + x[0] + '">' + x[1] + '</div>').join('');
    $('sistema').textContent = (d.modo_ap ? 'Modo configuración' : d.wifi_ssid + ' · ' + d.wifi_rssi + ' dBm') + ' · ' + d.ip + ' · ' + (d.firmware || '');
  } catch(e) { $('dot').className = 'dot off'; }
}
async function alimentar(){
  const b = $('bAlim'), p = Number($('porc').value); b.disabled = true; b.textContent = 'Alimentando…';
  try { await api('/api/feed?porciones=' + p, undefined, 'POST'); toast('Listo: ' + p + (p === 1 ? ' porción' : ' porciones')); }
  catch(e) { toast('No se pudo: ' + e.message, true); }
  setTimeout(() => { b.disabled = false; b.textContent = 'Alimentar'; estado(); }, 6000);
}
async function medir(calibrar){
  const b = $('bMedir'); b.disabled = true; if (calibrar) $('medida').textContent = 'Midiendo…';
  try {
    const t = await api('/api/diagnostics/nivel', undefined, 'POST');
    medida = t.estado === 'ok' ? t.distancia_mm : (t.distancia_cruda_mm > 0 ? t.distancia_cruda_mm : null);
    if (calibrar) $('medida').textContent = medida != null ? medida + ' mm' : t.mensaje;
    else if (!['ok','sin_eco','muy_cerca'].includes(t.estado)) toast(t.mensaje, true);
  } catch(e) { toast('Sin respuesta: ' + e.message, true); }
  b.disabled = false; estado();
}
function usar(id){ if (medida == null) { toast('Primero toca "Medir distancia"', true); return; } $(id).value = medida; }
function pintar(){
  $('lista').innerHTML = H.length ? H.map((h, i) => '<div class="hor"><input type="time" value="' + p2(h.hora) + ':' + p2(h.minuto) + '" onchange="horaDe(' + i + ',this.value)">' +
    '<select onchange="H[' + i + '].porciones=Number(this.value)">' + [1,2,3,4,5,6,7,8,9,10].map(n => '<option' + (n === h.porciones ? ' selected' : '') + '>' + n + '</option>').join('') + '</select>' +
    '<button class="x" onclick="H.splice(' + i + ',1);pintar()">✕</button></div>').join('') : '<div class="sub">Sin horarios.</div>';
}
function horaDe(i, v){ const [h, m] = v.split(':').map(Number); if (!isNaN(h)) { H[i].hora = h; H[i].minuto = m; } }
async function cargarH(){ try { H = await api('/api/schedule'); pintar(); } catch(e) {} }
async function guardarH(){
  if (!H.length) { toast('Deja al menos un horario. Para no alimentar usa el modo vacaciones.', true); return; }
  try { await api('/api/schedule', H); toast('Horarios guardados'); cargarH(); } catch(e) { toast('No se guardaron: ' + e.message, true); }
}
async function pausar(v){ try { await api('/api/pause', {pausado:v}); toast(v ? 'Horarios en pausa' : 'Horarios activos'); estado(); } catch(e) { toast(e.message, true); $('pausa').checked = !v; } }
async function cargarU(){ try { const u = await api('/api/thresholds'); U.forEach(k => $('u_' + k).value = u[k]); } catch(e) {} }
async function guardarU(){
  const v = {};
  for (const k of U) { const x = parseFloat($('u_' + k).value.replace(',', '.')); if (isNaN(x)) { toast('Completa todos los campos', true); return; } v[k] = x; }
  try { await api('/api/thresholds', v); toast('Umbrales guardados'); } catch(e) { toast('No se guardaron: ' + e.message, true); }
}
async function cargarT(){
  try { const t = await api('/api/telegram'); $('tgChat').value = t.chat_id || '';
    $('tgEstado').textContent = t.configurado ? 'Activo' + (t.ultimo_envio_hace_s >= 0 ? ' · último envío: código ' + t.ultimo_codigo : '') : 'Desactivado'; } catch(e) {}
}
async function guardarT(){
  const c = {chat_id:$('tgChat').value.trim()}, k = $('tgToken').value.trim(); if (k) c.token = k;
  try { await api('/api/telegram', c); $('tgToken').value = ''; toast('Guardado'); cargarT(); } catch(e) { toast(e.message, true); }
}
async function probarT(){ toast('Enviando…'); try { const r = await api('/api/telegram/prueba', undefined, 'POST'); toast(r.ok ? 'Enviado: revisa Telegram' : 'Telegram respondió ' + r.codigo, !r.ok); } catch(e) { toast(e.message, true); } }
async function borrarT(){ try { await api('/api/telegram', {borrar:true}); toast('Avisos desactivados'); cargarT(); } catch(e) { toast(e.message, true); } }
async function cargarR(){
  try { const r = await api('/api/red'); $('ipFija').checked = !!r.ip_fija;
    $('red').textContent = 'Red: ' + r.ssid + '\nIP: ' + r.ip + (r.ip_guardada ? ' (guardada: ' + r.ip_guardada + ')' : '') + '\n' + r.estado + (r.mdns ? '\nTambién: ' + r.mdns : ''); } catch(e) {}
}
async function ipFija(v){ try { await api('/api/red', {ip_fija:v}); toast(v ? 'Se mantendrá esta IP' : 'IP por DHCP'); setTimeout(cargarR, 1500); } catch(e) { toast(e.message, true); } }
async function ponerHora(){
  const n = new Date();
  try { await api('/api/time', {anio:n.getFullYear(), mes:n.getMonth()+1, dia:n.getDate(), hora:n.getHours(), minuto:n.getMinutes(), segundo:n.getSeconds()}); toast('Hora actualizada'); estado(); }
  catch(e) { toast(e.message, true); }
}
async function cambiarWifi(){
  const ssid = $('ssid').value.trim(); if (!ssid) { toast('Escribe el nombre de la red', true); return; }
  try { await api('/api/wifi-config', {ssid, password:$('pass').value}); toast('Reiniciando…'); } catch(e) { toast(e.message, true); }
}
cargarH(); estado();
setInterval(() => { if (!document.hidden) estado(); }, 5000);
</script>
</body>
</html>
)HTMLPAGE";
