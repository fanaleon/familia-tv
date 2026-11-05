// Relay Familia TV
//   POST /send                         (móvil) -> guarda el mensaje y lo empuja a la TV
//   GET  /status                       (móvil) -> { tv, pendientes, bloqueo, pin }
//   POST /control                      (móvil) -> bloquea / desbloquea la TV (PIN opcional)
//   WS   /ws?role=tv                   (TV)    -> recibe mensajes; manda {type:"ack"} y {type:"reply"}
//   WS   /ws?role=phone&uid=<uid>      (móvil) -> recibe las respuestas que da la TV
// Todo (menos /health) exige el token: header "Authorization: Bearer <TOKEN>".

const http = require('http');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 3000;
const TOKEN = process.env.TOKEN;
if (!TOKEN) {
  console.error('Falta la variable de entorno TOKEN');
  process.exit(1);
}

const MAX_TEXT = 500;            // caracteres por mensaje
const MAX_FROM = 30;
const MAX_AVATAR = 40000;        // caracteres base64 de la foto (~30 KB)
const MAX_PENDING = 50;          // mensajes en cola si la TV está apagada
const MAX_REPLIES = 20;          // respuestas en cola por persona si su móvil está sin conexión
const MAX_REPLY_QUEUES = 100;    // personas distintas con respuestas en cola
const MAX_AGE_MS = 24 * 60 * 60 * 1000; // lo que pasa de 24 h en cola se descarta
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

const pending = [];              // mensajes para la TV hasta que haga ack
let freeze = null;               // bloqueo activo: { endsAt, label, by }
let pinFails = 0;
let pinBlockedUntil = 0;
let tv = null;                   // WebSocket de la TV conectada
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

function prune() {
  const limit = Date.now() - MAX_AGE_MS;
  for (let i = pending.length - 1; i >= 0; i--) {
    if (pending[i].ts < limit) pending.splice(i, 1);
  }
  while (pending.length > MAX_PENDING) pending.shift();
  for (const [uid, list] of replies) {
    const fresh = list.filter((r) => r.ts >= limit);
    if (fresh.length) replies.set(uid, fresh);
    else replies.delete(uid);
  }
}

function sendToTv(msg) {
  if (!isOpen(tv)) return false;
  try {
    tv.send(JSON.stringify({ type: 'msg', ...msg }));
    return true;
  } catch (e) {
    return false;
  }
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
function activeFreeze() {
  if (freeze && freeze.endsAt <= Date.now()) freeze = null;
  return freeze;
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

function sendFreezeToTv() {
  if (!isOpen(tv)) return false;
  const f = activeFreeze();
  try {
    tv.send(JSON.stringify(
      f
        ? { type: 'freeze', endsIn: f.endsAt - Date.now(), label: f.label, by: f.by }
        : { type: 'unfreeze' }
    ));
    return true;
  } catch (e) {
    return false;
  }
}

function freezeInfo() {
  const f = activeFreeze();
  return f ? { hasta: f.endsAt, ahora: Date.now(), label: f.label, by: f.by } : null;
}

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
    return json(res, 200, {
      ok: true,
      tv: isOpen(tv),
      pendientes: pending.length,
      bloqueo: freezeInfo(),
      pin: PARENT_PIN !== '',
    });
  }

  if (req.method === 'POST' && url.pathname === '/control') {
    let data;
    try {
      data = JSON.parse(await readBody(req, 4096));
    } catch (e) {
      return json(res, 400, { ok: false, error: 'json_invalido' });
    }
    if (data === null || typeof data !== 'object') return json(res, 400, { ok: false, error: 'json_invalido' });

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

    if (data.action === 'unfreeze') {
      freeze = null;
      const delivered = isOpen(tv) ? sendFreezeToTv() : false;
      console.log(`[control] desbloqueo (${by}) -> ${delivered ? 'TV' : 'TV sin conexión'}`);
      return json(res, 200, { ok: true, delivered, bloqueo: null });
    }

    if (data.action === 'freeze') {
      let endsAt;
      let label;
      if (data.mode === 'until') {
        const hour = Number.isInteger(data.hour) && data.hour >= 0 && data.hour <= 23 ? data.hour : 7;
        const minute = Number.isInteger(data.minute) && data.minute >= 0 && data.minute <= 59 ? data.minute : 0;
        endsAt = nextLocalTime(hour, minute);
        label = `La tele vuelve a las ${hour}:${String(minute).padStart(2, '0')}`;
      } else {
        const minutes = Number.isInteger(data.minutes) && data.minutes >= 1 && data.minutes <= MAX_FREEZE_MIN
          ? data.minutes
          : 15;
        endsAt = Date.now() + minutes * 60000;
        label = minutes >= 60 && minutes % 60 === 0
          ? `Descanso de ${minutes / 60} h`
          : `Descanso de ${minutes} min`;
      }
      freeze = { endsAt, label, by };
      const delivered = sendFreezeToTv();
      console.log(`[control] bloqueo (${by}): ${label} -> ${delivered ? 'TV' : 'TV sin conexión, queda anotado'}`);
      return json(res, 200, { ok: true, delivered, bloqueo: freezeInfo() });
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

    const text = typeof data.text === 'string' ? data.text.trim() : '';
    const from = (typeof data.from === 'string' ? data.from.trim() : '') || 'Familia';
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
    pending.push(msg);
    prune();
    const delivered = sendToTv(msg);
    console.log(`[send] ${msg.from}${msg.reply ? ' (pide respuesta)' : ''}: ${msg.text.slice(0, 60)} -> ${delivered ? 'TV' : 'en cola'}`);
    return json(res, 200, { ok: true, id: msg.id, delivered, queued: !delivered });
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
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
});

function handleTvMessage(m) {
  if (m.type === 'ack' && typeof m.id === 'string') {
    const i = pending.findIndex((p) => p.id === m.id);
    if (i !== -1) pending.splice(i, 1);
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

function attachTv(ws) {
  if (tv && tv !== ws) {
    try { tv.close(4000, 'reemplazada'); } catch (e) { /* ya cerrada */ }
  }
  tv = ws;
  console.log('[ws] TV conectada');

  ws.on('message', (raw) => {
    ws.isAlive = true;
    let m;
    try { m = JSON.parse(raw.toString()); } catch (e) { return; }
    if (m && typeof m === 'object') handleTvMessage(m);
  });
  ws.on('close', () => {
    if (tv === ws) tv = null;
    console.log('[ws] TV desconectada');
  });

  // Si hay un bloqueo en curso, la TV lo retoma (por ejemplo, la prendieron de nuevo)
  if (activeFreeze()) sendFreezeToTv();

  // Entregar lo que quedó pendiente mientras la TV estaba apagada
  prune();
  for (const m of pending) sendToTv(m);
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
}

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://localhost');
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  ws.on('error', () => { /* el 'close' se encarga */ });

  if (url.searchParams.get('role') === 'phone') attachPhone(ws, url.searchParams.get('uid'));
  else attachTv(ws);
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

server.listen(PORT, () => console.log(`Relay escuchando en :${PORT}`));
