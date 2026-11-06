// Relay Hey Family
//   POST /send                         (móvil) -> guarda el mensaje y lo empuja a la(s) TV  { tv?: id }
//   GET  /status                       (móvil) -> { tv, tvs[], capacidad, pendientes, bloqueo, pin, plan }
//   POST /control                      (móvil) -> bloquea / desbloquea una TV o todas (PIN opcional)
//   POST /tv/rename                    (móvil) -> { id, name }
//   POST /tv/forget                    (móvil) -> { id }  (solo TV desconectadas: libera un lugar)
//   POST /pay/create                   (TV)    -> pedido de pago { metodo: "usdc" | "sol", producto: "plan" | "extra" }
//   GET  /pay/status[?id=<pago>]       (TV)    -> estado del plan y, si se pide, de ese pago
//   WS   /ws?role=tv&id=<id>&name=<n>  (TV)    -> recibe mensajes; manda {type:"ack"} y {type:"reply"}
//   WS   /ws?role=phone&uid=<uid>      (móvil) -> recibe las respuestas que da la TV
// Todo (menos /health) exige el token: header "Authorization: Bearer <TOKEN>".

const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');
const { createPay } = require('./pay');
const { createMail } = require('./mail');

const PORT = process.env.PORT || 3000;
const TOKEN = process.env.TOKEN;
if (!TOKEN) {
  console.error('Falta la variable de entorno TOKEN');
  process.exit(1);
}

const MAX_TEXT = 500;            // caracteres por mensaje
const MAX_FROM = 30;
const MAX_TV_NAME = 24;
const DEFAULT_TV_ID = 'tv-principal'; // para la app vieja de la TV, que no manda id
const MAX_AVATAR = 40000;        // caracteres base64 de la foto (~30 KB)
const MAX_PENDING = 50;          // mensajes en cola por TV si está apagada
const MAX_REPLIES = 20;          // respuestas en cola por persona si su móvil está sin conexión
const MAX_REPLY_QUEUES = 100;    // personas distintas con respuestas en cola
// Los mensajes y las respuestas no se guardan más de 5 minutos (MSG_TTL_MIN): lo que pasa de ahí en cola se descarta.
const MAX_AGE_MS = (Number(process.env.MSG_TTL_MIN) > 0 ? Number(process.env.MSG_TTL_MIN) : 5) * 60 * 1000;
const CHOICES = new Set(['ok', 'voy', 'ahora_no', 'sin_respuesta']);
const COLORS = new Set(['azul', 'rosa', 'verde', 'amarillo']);
const MAX_FREEZE_MIN = 12 * 60;  // el bloqueo más largo que se puede pedir por minutos
const PIN_MAX_FAILS = 5;         // intentos de PIN fallidos antes de frenar
const PIN_BLOCK_MS = 10 * 60 * 1000;
const UID_RE = /^[A-Za-z0-9-]{8,64}$/;
const B64_RE = /^[A-Za-z0-9+/]+={0,2}$/;

// PIN de padres (opcional): si existe la variable PARENT_PIN, bloquear y desbloquear lo exigen.
const PARENT_PIN = (process.env.PARENT_PIN || '').trim();
// Zona horaria para "hasta las 7:00", en minutos respecto de UTC (Argentina = -180, sin horario de verano)
const TZ_OFFSET_MIN = Number.isFinite(Number(process.env.TZ_OFFSET_MIN)) && process.env.TZ_OFFSET_MIN !== undefined
  ? Number(process.env.TZ_OFFSET_MIN)
  : -180;

// RESET_STATE=1: al arrancar borra TV, pagos, licencia y avisos (para empezar una prueba de cero).
// Sacar la variable después del primer arranque, si no se vuelve a borrar en cada reinicio.
if (process.env.RESET_STATE === '1') {
  const dir = path.resolve(process.env.DATA_DIR || path.join(__dirname, 'data'));
  for (const f of ['tvs.json', 'licencia.json', 'pagos.json', 'avisos.json']) {
    try { fs.unlinkSync(path.join(dir, f)); console.log('[reset] borrado', f); } catch (_) {}
  }
}

const mail = createMail();         // aviso por mail al dueño de cada compra (ver mail.js)
if (process.env.MAIL_TEST === '1') {
  mail.sendTest().then((r) => console.log(r.ok ? '[mail] mail de prueba enviado' : `[mail] mail de prueba falló: ${r.error}`));
}
const pay = createPay({           // cobro con Solana Pay + licencia (ver pay.js)
  onPaid: (recibo, info) => {
    broadcastAviso(recibo.titulo, recibo.texto, recibo.textos);
    mail.notifyPayment(recibo, info);
  },
});

let pinFails = 0;
let pinBlockedUntil = 0;
// TV vinculadas: id -> { id, name, firstSeen, ws, pending[], freeze }. Cada TV tiene su cola y su bloqueo.
// La lista (id, nombre) se guarda en disco: una TV apagada sigue ocupando su lugar.
const tvs = new Map();
const tvsFile = path.join(path.resolve(process.env.DATA_DIR || path.join(__dirname, 'data')), 'tvs.json');
const phones = new Map();        // uid -> WebSocket del móvil de esa persona
const replies = new Map();       // uid -> respuestas pendientes de entregar a ese móvil

// ---------- utilidades ----------
const digest = (s) => crypto.createHash('sha256').update(String(s)).digest();
const tokenOk = (candidate) =>
  typeof candidate === 'string' && crypto.timingSafeEqual(digest(candidate), digest(TOKEN));

function bearer(req) {
  const h = req.headers['authorization'] || '';
  return h.startsWith('Bearer ') ? h.slice(7).trim() : '';
}

function json(res, code, body) {
  const data = JSON.stringify(body);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(data) });
  res.end(data);
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) {
        reject(new Error('too_large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

const isOpen = (ws) => ws !== null && ws !== undefined && ws.readyState === ws.OPEN;

// ---------- TV vinculadas ----------
function loadTvs() {
  try {
    const raw = JSON.parse(fs.readFileSync(tvsFile, 'utf8'));
    for (const t of (raw && raw.tvs) || []) {
      if (t && UID_RE.test(String(t.id))) {
        tvs.set(t.id, { id: t.id, name: cleanName(t.name, 'TV'), firstSeen: Number(t.firstSeen) || Date.now(), ws: null, pending: [], freeze: null });
      }
    }
  } catch (e) { /* primer arranque */ }
}

function saveTvs() {
  try {
    fs.mkdirSync(path.dirname(tvsFile), { recursive: true });
    const tmp = tvsFile + '.tmp';
    const list = [...tvs.values()].map((t) => ({ id: t.id, name: t.name, firstSeen: t.firstSeen }));
    fs.writeFileSync(tmp, JSON.stringify({ tvs: list }));
    fs.renameSync(tmp, tvsFile);
  } catch (e) {
    console.error('[tv] no se pudo guardar la lista de TV:', e.message);
  }
}

function cleanName(v, fallback) {
  const n = (typeof v === 'string' ? v.replace(/[\u0000-\u001f]/g, ' ').trim() : '').slice(0, MAX_TV_NAME);
  return n || fallback;
}

const tvOnline = (t) => isOpen(t.ws);

// ---------- limpieza de colas ----------
function prune() {
  const limit = Date.now() - MAX_AGE_MS;
  for (const t of tvs.values()) {
    for (let i = t.pending.length - 1; i >= 0; i--) {
      if (t.pending[i].ts < limit) t.pending.splice(i, 1);
    }
    while (t.pending.length > MAX_PENDING) t.pending.shift();
  }
  for (const [uid, list] of replies) {
    const fresh = list.filter((r) => r.ts >= limit);
    if (fresh.length) replies.set(uid, fresh);
    else replies.delete(uid);
  }
}

setInterval(prune, 30 * 1000); // las colas se vacían solas aunque no pase nada

function sendToTv(t, msg) {
  if (!tvOnline(t)) return false;
  try {
    t.ws.send(JSON.stringify({ type: 'msg', ...msg }));
    return true;
  } catch (e) {
    return false;
  }
}

// ---------- avisos del sistema (pago recibido, plan por vencer) ----------
// Llegan como notificación a los móviles; los que estaban sin conexión los reciben al volver.
const avisos = [];                // { aid, title, text, ts, i18n? }  (i18n: { es|en|pt: { titulo, texto } })
const avisosAcked = new Map();    // uid -> Set de aid ya recibidos
const MAX_AVISOS = 20;
const AVISO_AGE_MS = 3 * 24 * 60 * 60 * 1000;
const avisosFile = path.join(path.dirname(tvsFile), 'avisos.json');

function loadAvisos() {
  try {
    const raw = JSON.parse(fs.readFileSync(avisosFile, 'utf8'));
    for (const a of Array.isArray(raw) ? raw : []) {
      if (a && typeof a.aid === 'string' && typeof a.title === 'string' && typeof a.text === 'string') {
        avisos.push({ aid: a.aid, title: a.title, text: a.text, ts: Number(a.ts) || Date.now(), i18n: a.i18n || undefined });
      }
    }
  } catch (e) { /* primer arranque */ }
}

function saveAvisos() {
  try {
    fs.mkdirSync(path.dirname(avisosFile), { recursive: true });
    const tmp = avisosFile + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(avisos));
    fs.renameSync(tmp, avisosFile);
  } catch (e) {
    console.error('[aviso] no se pudo guardar:', e.message);
  }
}

function sendAviso(uid, a) {
  const ws = phones.get(uid);
  if (!isOpen(ws)) return;
  try { ws.send(JSON.stringify({ type: 'aviso', aid: a.aid, title: a.title, text: a.text, ts: a.ts, i18n: a.i18n })); } catch (e) { /* se reenvía al volver */ }
}

function broadcastAviso(title, text, i18n) {
  const a = { aid: crypto.randomUUID(), title, text: String(text).slice(0, 600), ts: Date.now(), i18n: i18n || undefined };
  avisos.push(a);
  while (avisos.length > MAX_AVISOS) avisos.shift();
  saveAvisos();
  for (const uid of phones.keys()) sendAviso(uid, a);
  console.log(`[aviso] ${title}`);
}

// Mensaje a todas las TV, como uno más de la familia (si alguna está apagada, queda en cola)
function noticeToTvs(text, i18n) {
  const msg = { id: crypto.randomUUID(), uid: '', from: 'Hey Family', text: String(text).slice(0, 500), i18n, avatar: undefined, duration: 'larga', color: '', reply: false, ts: Date.now() };
  for (const t of tvs.values()) {
    t.pending.push(msg);
    if (t.pending.length > MAX_PENDING) t.pending.shift();
    sendToTv(t, msg);
  }
}

// Cada 10 minutos: si falta poco para que venza el plan, se avisa (una sola vez por licencia)
function checkReminder() {
  const r = pay.dueReminder();
  if (!r) return;
  broadcastAviso(r.titulo, r.texto, r.textos);
  const porIdioma = {};
  for (const l of Object.keys(r.textos || {})) porIdioma[l] = { texto: `${r.textos[l].titulo}\n${r.textos[l].texto}` };
  noticeToTvs(`${r.titulo}\n${r.texto}`, porIdioma);
  pay.markReminded(r.until);
}

function sendReplyToPhone(uid, entry) {
  const ws = phones.get(uid);
  if (!isOpen(ws)) return false;
  try {
    ws.send(JSON.stringify({ type: 'reply', ...entry }));
    return true;
  } catch (e) {
    return false;
  }
}

// ---------- bloqueo de la TV ----------
function activeFreeze(t) {
  if (t.freeze && t.freeze.endsAt <= Date.now()) t.freeze = null;
  return t.freeze;
}

// Próxima vez que en el reloj local sean las `hour`:`minute` (si faltan menos de 1 min, la del día siguiente)
function nextLocalTime(hour, minute) {
  const off = TZ_OFFSET_MIN * 60000;
  const local = new Date(Date.now() + off);
  const target = Date.UTC(local.getUTCFullYear(), local.getUTCMonth(), local.getUTCDate(), hour, minute, 0);
  let end = target - off;
  if (end <= Date.now() + 60000) end += 24 * 60 * 60 * 1000;
  return end;
}

function sendFreezeToTv(t) {
  if (!tvOnline(t)) return false;
  const f = activeFreeze(t);
  try {
    t.ws.send(JSON.stringify(
      f
        ? { type: 'freeze', endsIn: f.endsAt - Date.now(), label: f.label, lk: f.lk, by: f.by }
        : { type: 'unfreeze' }
    ));
    return true;
  } catch (e) {
    return false;
  }
}

function freezeInfo(t) {
  const f = activeFreeze(t);
  return f ? { hasta: f.endsAt, ahora: Date.now(), label: f.label, lk: f.lk, by: f.by } : null;
}

// A qué TV va una orden: "tv" ausente o "todas" = todas las vinculadas; si no, esa TV.
function targets(id) {
  if (id === undefined || id === null || id === '' || id === 'todas') return { list: [...tvs.values()] };
  if (typeof id !== 'string' || !tvs.has(id)) return { error: 'tv_desconocida' };
  return { list: [tvs.get(id)] };
}

const planView = () => ({ ...pay.plan(), tvs_usadas: tvs.size });

// ---------- HTTP ----------
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');

  if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/health')) {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    return res.end('ok');
  }

  if (!tokenOk(bearer(req))) return json(res, 401, { ok: false, error: 'no_autorizado' });

  if (req.method === 'GET' && url.pathname === '/status') {
    prune();
    const list = [...tvs.values()];
    const frozen = list.map((t) => freezeInfo(t)).find((b) => b);
    return json(res, 200, {
      ok: true,
      tv: list.some(tvOnline),             // (compatibilidad) hay alguna TV conectada
      tvs: list.map((t) => ({ id: t.id, name: t.name, online: tvOnline(t), pendientes: t.pending.length, bloqueo: freezeInfo(t) })),
      capacidad: pay.capacity(),
      pendientes: list.reduce((n, t) => n + t.pending.length, 0),
      bloqueo: frozen || null,
      pin: PARENT_PIN !== '',
      plan: planView(),
      avisos: avisos.map((a) => ({ aid: a.aid, title: a.title, text: a.text, ts: a.ts, i18n: a.i18n })), // historial para el móvil
    });
  }

  if (req.method === 'GET' && url.pathname === '/pay/status') {
    const id = url.searchParams.get('id');
    const pago = id ? pay.get(id) : null;
    return json(res, 200, { ok: true, plan: planView(), pago, ultimo_pago: pay.lastReceipt() });
  }

  if (req.method === 'POST' && url.pathname === '/pay/create') {
    let data;
    try {
      data = JSON.parse(await readBody(req, 1024));
    } catch (e) {
      return json(res, 400, { ok: false, error: 'json_invalido' });
    }
    try {
      const pago = await pay.create(data && data.metodo, data && data.producto);
      return json(res, 200, { ok: true, plan: planView(), pago, ultimo_pago: pay.lastReceipt() });
    } catch (e) {
      return json(res, e.code || 500, { ok: false, error: e.message });
    }
  }

  if (req.method === 'POST' && (url.pathname === '/tv/rename' || url.pathname === '/tv/forget')) {
    let data;
    try {
      data = JSON.parse(await readBody(req, 1024));
    } catch (e) {
      return json(res, 400, { ok: false, error: 'json_invalido' });
    }
    const t = data && typeof data.id === 'string' ? tvs.get(data.id) : null;
    if (!t) return json(res, 404, { ok: false, error: 'tv_desconocida' });
    if (url.pathname === '/tv/rename') {
      t.name = cleanName(data.name, t.name);
      saveTvs();
      console.log(`[tv] renombrada: ${t.name}`);
      return json(res, 200, { ok: true, name: t.name });
    }
    // Quitar una TV libera su lugar. Solo si está desconectada: una que sigue prendida se volvería a vincular sola.
    if (tvOnline(t)) return json(res, 409, { ok: false, error: 'tv_conectada' });
    tvs.delete(t.id);
    saveTvs();
    console.log(`[tv] quitada: ${t.name}`);
    return json(res, 200, { ok: true, tvs: tvs.size, capacidad: pay.capacity() });
  }

  if (req.method === 'POST' && url.pathname === '/control') {
    let data;
    try {
      data = JSON.parse(await readBody(req, 4096));
    } catch (e) {
      return json(res, 400, { ok: false, error: 'json_invalido' });
    }
    if (data === null || typeof data !== 'object') return json(res, 400, { ok: false, error: 'json_invalido' });

    // Plan vencido (solo si la licencia es obligatoria). Desbloquear siempre se puede.
    if (data.action !== 'unfreeze' && !pay.allowed()) return json(res, 402, { ok: false, error: 'licencia_vencida' });

    // PIN de padres (si está configurado)
    if (PARENT_PIN) {
      const now = Date.now();
      if (now < pinBlockedUntil) {
        return json(res, 429, { ok: false, error: 'bloqueado', reintentar_en: Math.ceil((pinBlockedUntil - now) / 1000) });
      }
      if (typeof data.pin !== 'string' || data.pin === '') {
        return json(res, 403, { ok: false, error: 'pin_requerido' });
      }
      if (!crypto.timingSafeEqual(digest(data.pin), digest(PARENT_PIN))) {
        pinFails += 1;
        if (pinFails >= PIN_MAX_FAILS) {
          pinFails = 0;
          pinBlockedUntil = now + PIN_BLOCK_MS;
          console.log('[control] demasiados PIN incorrectos: bloqueado 10 min');
        }
        return json(res, 403, { ok: false, error: 'pin_incorrecto' });
      }
      pinFails = 0;
    }

    const by = ((typeof data.from === 'string' ? data.from.trim() : '') || 'Los padres').slice(0, MAX_FROM);

    const target = targets(data.tv);
    if (target.error) return json(res, 404, { ok: false, error: target.error });
    const dest = target.list;
    if (dest.length === 0 && (data.action === 'freeze' || data.action === 'unfreeze')) {
      return json(res, 409, { ok: false, error: 'sin_tvs' });
    }
    // "delivered": todas las TV elegidas recibieron la orden ahora. "entregadas"/"total" dan el detalle.
    const result = (extra) => {
      const entregadas = dest.filter(tvOnline).length;
      return { ok: true, delivered: entregadas === dest.length, entregadas, total: dest.length, ...extra };
    };

    if (data.action === 'unfreeze') {
      for (const t of dest) {
        t.freeze = null;
        sendFreezeToTv(t);
      }
      console.log(`[control] desbloqueo (${by}) -> ${dest.map((t) => `${t.name}${tvOnline(t) ? '' : ' (sin conexión)'}`).join(', ')}`);
      return json(res, 200, result({ bloqueo: null }));
    }

    if (data.action === 'freeze') {
      let endsAt;
      let label;
      let lk; // la misma etiqueta en forma estructurada: cada app la escribe en su idioma
      if (data.mode === 'until') {
        const hour = Number.isInteger(data.hour) && data.hour >= 0 && data.hour <= 23 ? data.hour : 7;
        const minute = Number.isInteger(data.minute) && data.minute >= 0 && data.minute <= 59 ? data.minute : 0;
        endsAt = nextLocalTime(hour, minute);
        label = `La tele vuelve a las ${hour}:${String(minute).padStart(2, '0')}`;
        lk = { k: 'until', h: hour, m: minute };
      } else {
        const minutes = Number.isInteger(data.minutes) && data.minutes >= 1 && data.minutes <= MAX_FREEZE_MIN
          ? data.minutes
          : 15;
        endsAt = Date.now() + minutes * 60000;
        label = minutes >= 60 && minutes % 60 === 0
          ? `Descanso de ${minutes / 60} h`
          : `Descanso de ${minutes} min`;
        lk = { k: 'rest', min: minutes };
      }
      for (const t of dest) {
        t.freeze = { endsAt, label, lk, by };
        sendFreezeToTv(t);
      }
      console.log(`[control] bloqueo (${by}): ${label} -> ${dest.map((t) => `${t.name}${tvOnline(t) ? '' : ' (sin conexión, queda anotado)'}`).join(', ')}`);
      return json(res, 200, result({ bloqueo: freezeInfo(dest[0]) }));
    }

    return json(res, 400, { ok: false, error: 'accion_invalida' });
  }

  if (req.method === 'POST' && url.pathname === '/send') {
    let data;
    try {
      data = JSON.parse(await readBody(req, 65536));
    } catch (e) {
      return json(res, 400, { ok: false, error: 'json_invalido' });
    }
    if (data === null || typeof data !== 'object') return json(res, 400, { ok: false, error: 'json_invalido' });
    if (!pay.allowed()) return json(res, 402, { ok: false, error: 'licencia_vencida' });

    const target = targets(data.tv);
    if (target.error) return json(res, 404, { ok: false, error: target.error });
    const dest = target.list;
    if (dest.length === 0) return json(res, 409, { ok: false, error: 'sin_tvs' });

    const text = typeof data.text === 'string' ? data.text.trim() : '';
    const from = (typeof data.from === 'string' ? data.from.trim() : '') || 'Family';
    if (!text) return json(res, 400, { ok: false, error: 'mensaje_vacio' });
    if (text.length > MAX_TEXT) return json(res, 400, { ok: false, error: 'mensaje_muy_largo' });

    // uid: identifica a quien manda (para devolverle las respuestas). Es opcional para
    // que la versión anterior de la app siga funcionando.
    let uid = '';
    if (data.uid !== undefined && data.uid !== null && data.uid !== '') {
      if (typeof data.uid !== 'string' || !UID_RE.test(data.uid)) {
        return json(res, 400, { ok: false, error: 'uid_invalido' });
      }
      uid = data.uid;
    }

    let avatar;
    if (data.avatar !== undefined && data.avatar !== null && data.avatar !== '') {
      if (typeof data.avatar !== 'string' || data.avatar.length > MAX_AVATAR || !B64_RE.test(data.avatar)) {
        return json(res, 400, { ok: false, error: 'avatar_invalido' });
      }
      avatar = data.avatar;
    }

    const msg = {
      id: crypto.randomUUID(),
      uid,
      from: from.slice(0, MAX_FROM),
      text,
      avatar,
      duration: data.duration === 'larga' ? 'larga' : 'normal',
      color: COLORS.has(data.color) ? data.color : '',
      reply: data.reply === true && uid !== '',
      ts: Date.now(),
    };
    for (const t of dest) {
      t.pending.push(msg);
      if (t.pending.length > MAX_PENDING) t.pending.shift();
    }
    prune();
    const entregadas = dest.filter((t) => sendToTv(t, msg)).length;
    const delivered = entregadas > 0;
    const enCola = dest.length - entregadas;
    console.log(`[send] ${msg.from}${msg.reply ? ' (pide respuesta)' : ''}: ${msg.text.length} caracteres -> ${dest.map((t) => t.name).join(', ')} (${entregadas} ahora, ${enCola} en cola)`);
    return json(res, 200, { ok: true, id: msg.id, delivered, queued: enCola > 0, entregadas, en_cola: enCola, total: dest.length });
  }

  json(res, 404, { ok: false, error: 'no_existe' });
});

// ---------- WebSocket ----------
const wss = new WebSocketServer({ noServer: true, maxPayload: 4096 });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://localhost');
  const candidate = bearer(req) || url.searchParams.get('token') || '';
  if (url.pathname !== '/ws' || !tokenOk(candidate)) {
    socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
    return socket.destroy();
  }
  if (url.searchParams.get('role') === 'phone' && !UID_RE.test(url.searchParams.get('uid') || '')) {
    socket.write('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
    return socket.destroy();
  }
  if (url.searchParams.get('role') !== 'phone' && url.searchParams.has('id') && !UID_RE.test(url.searchParams.get('id'))) {
    socket.write('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
    return socket.destroy();
  }
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
});

function handleTvMessage(t, m) {
  if (m.type === 'ack' && typeof m.id === 'string') {
    const i = t.pending.findIndex((p) => p.id === m.id);
    if (i !== -1) t.pending.splice(i, 1);
    return;
  }
  if (m.type === 'reply') {
    if (typeof m.to !== 'string' || !UID_RE.test(m.to)) return;
    if (typeof m.choice !== 'string' || !CHOICES.has(m.choice)) return;
    if (!replies.has(m.to) && replies.size >= MAX_REPLY_QUEUES) return; // tope de colas distintas
    const entry = {
      rid: crypto.randomUUID(),
      id: String(m.id || '').slice(0, 64),
      choice: m.choice,
      re: String(m.re || '').slice(0, 80),
      tv: t.name,
      ts: Date.now(),
    };
    const list = replies.get(m.to) || [];
    list.push(entry);
    while (list.length > MAX_REPLIES) list.shift();
    replies.set(m.to, list);
    const sent = sendReplyToPhone(m.to, entry);
    console.log(`[reply] ${m.choice} -> ${sent ? 'móvil' : 'en cola'}`);
  }
}

function attachTv(ws, id, name) {
  let t = tvs.get(id);
  // Migración: la app vieja de la TV no mandaba id y quedó anotada como 'tv-principal'. Cuando la misma TV se
  // actualiza y se presenta con su id propio, toma ese lugar en vez de ocupar uno más.
  if (!t && id !== DEFAULT_TV_ID && tvs.has(DEFAULT_TV_ID) && !tvOnline(tvs.get(DEFAULT_TV_ID))) {
    const legacy = tvs.get(DEFAULT_TV_ID);
    tvs.delete(DEFAULT_TV_ID);
    t = { ...legacy, id, ws: null };
    tvs.set(id, t);
    saveTvs();
    console.log(`[tv] "${t.name}" se actualizó: ahora tiene su propio id`);
  }
  if (!t) {
    // TV nueva: entra solo si queda lugar. Si no, se le avisa para que ofrezca comprar más lugares.
    if (tvs.size >= pay.capacity()) {
      try { ws.send(JSON.stringify({ type: 'limit', capacidad: pay.capacity(), usadas: tvs.size })); } catch (e) { /* cerrada */ }
      console.log(`[ws] TV rechazada: límite de ${pay.capacity()} TV`);
      ws.close(4001, 'limite');
      return;
    }
    t = { id, name: cleanName(name, `TV ${tvs.size + 1}`), firstSeen: Date.now(), ws: null, pending: [], freeze: null };
    tvs.set(id, t);
    saveTvs();
  }
  if (t.ws && t.ws !== ws) {
    try { t.ws.close(4000, 'reemplazada'); } catch (e) { /* ya cerrada */ }
  }
  t.ws = ws;
  console.log(`[ws] TV conectada: ${t.name}`);
  try { ws.send(JSON.stringify({ type: 'hello', name: t.name, capacidad: pay.capacity(), usadas: tvs.size })); } catch (e) { /* cerrada */ }

  ws.on('message', (raw) => {
    ws.isAlive = true;
    let m;
    try { m = JSON.parse(raw.toString()); } catch (e) { return; }
    if (m && typeof m === 'object') handleTvMessage(t, m);
  });
  ws.on('close', () => {
    if (t.ws === ws) t.ws = null;
    console.log(`[ws] TV desconectada: ${t.name}`);
  });

  // Si hay un bloqueo en curso, la TV lo retoma (por ejemplo, la prendieron de nuevo)
  if (activeFreeze(t)) sendFreezeToTv(t);

  // Entregar lo que quedó pendiente mientras la TV estaba apagada
  prune();
  for (const m of t.pending) sendToTv(t, m);
}

function attachPhone(ws, uid) {
  const old = phones.get(uid);
  if (old && old !== ws) {
    try { old.close(4000, 'reemplazada'); } catch (e) { /* ya cerrada */ }
  }
  phones.set(uid, ws);
  console.log('[ws] móvil conectado');

  ws.on('message', (raw) => {
    ws.isAlive = true;
    let m;
    try { m = JSON.parse(raw.toString()); } catch (e) { return; }
    if (m && m.type === 'ack' && typeof m.id === 'string' && avisos.some((a) => a.aid === m.id)) {
      const set = avisosAcked.get(uid) || new Set();
      set.add(m.id);
      avisosAcked.set(uid, set);
      return;
    }
    if (m && m.type === 'ack' && typeof m.id === 'string') {
      const list = replies.get(uid);
      if (!list) return;
      const rest = list.filter((r) => r.rid !== m.id);
      if (rest.length) replies.set(uid, rest);
      else replies.delete(uid);
    }
  });
  ws.on('close', () => {
    if (phones.get(uid) === ws) phones.delete(uid);
    console.log('[ws] móvil desconectado');
  });

  // Respuestas que llegaron mientras el móvil estaba sin conexión
  prune();
  for (const entry of replies.get(uid) || []) sendReplyToPhone(uid, entry);
  // Avisos del sistema que todavía no recibió (últimos 3 días)
  const got = avisosAcked.get(uid);
  for (const a of avisos) {
    if (Date.now() - a.ts < AVISO_AGE_MS && !(got && got.has(a.aid))) sendAviso(uid, a);
  }
}

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://localhost');
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  ws.on('error', () => { /* el 'close' se encarga */ });

  if (url.searchParams.get('role') === 'phone') attachPhone(ws, url.searchParams.get('uid'));
  else attachTv(ws, url.searchParams.get('id') || DEFAULT_TV_ID, url.searchParams.get('name'));
});

// Si alguien deja de contestar (cortaron el wifi, apagaron de golpe) lo damos de baja
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    try { ws.ping(); } catch (e) { /* se cierra solo */ }
  }
}, 30000);

loadTvs();
loadAvisos();
setInterval(checkReminder, 10 * 60 * 1000);
setTimeout(checkReminder, 20 * 1000);
server.listen(PORT, () => console.log(`Relay escuchando en :${PORT}`));
