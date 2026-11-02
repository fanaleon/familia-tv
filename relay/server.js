// Relay Familia TV
//   POST /send    (móvil)  -> guarda el mensaje y lo empuja a la TV
//   GET  /status  (móvil)  -> { tv: true|false, pendientes: n }
//   WS   /ws      (TV)     -> recibe mensajes, responde {type:"ack", id}
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
const MAX_PENDING = 50;          // mensajes en cola si la TV está apagada
const MAX_AGE_MS = 24 * 60 * 60 * 1000; // lo que pasa de 24 h en cola se descarta

const pending = [];              // { id, from, text, ts } hasta que la TV hace ack
let tv = null;                   // WebSocket de la TV conectada

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

function readBody(req, limit = 4096) {
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

const tvOnline = () => tv !== null && tv.readyState === tv.OPEN;

function prune() {
  const limit = Date.now() - MAX_AGE_MS;
  for (let i = pending.length - 1; i >= 0; i--) {
    if (pending[i].ts < limit) pending.splice(i, 1);
  }
  while (pending.length > MAX_PENDING) pending.shift();
}

function sendToTv(msg) {
  if (!tvOnline()) return false;
  try {
    tv.send(JSON.stringify({ type: 'msg', ...msg }));
    return true;
  } catch (e) {
    return false;
  }
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
    return json(res, 200, { ok: true, tv: tvOnline(), pendientes: pending.length });
  }

  if (req.method === 'POST' && url.pathname === '/send') {
    let data;
    try {
      data = JSON.parse(await readBody(req));
    } catch (e) {
      return json(res, 400, { ok: false, error: 'json_invalido' });
    }
    const text = typeof data.text === 'string' ? data.text.trim() : '';
    const from = (typeof data.from === 'string' ? data.from.trim() : '') || 'Familia';
    if (!text) return json(res, 400, { ok: false, error: 'mensaje_vacio' });
    if (text.length > MAX_TEXT) return json(res, 400, { ok: false, error: 'mensaje_muy_largo' });

    const msg = { id: crypto.randomUUID(), from: from.slice(0, MAX_FROM), text, ts: Date.now() };
    pending.push(msg);
    prune();
    const delivered = sendToTv(msg);
    console.log(`[send] ${msg.from}: ${msg.text.slice(0, 60)} -> ${delivered ? 'TV' : 'en cola'}`);
    return json(res, 200, { ok: true, id: msg.id, delivered, queued: !delivered });
  }

  json(res, 404, { ok: false, error: 'no_existe' });
});

// ---------- WebSocket (la TV) ----------
const wss = new WebSocketServer({ noServer: true, maxPayload: 4096 });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://localhost');
  const candidate = bearer(req) || url.searchParams.get('token') || '';
  if (url.pathname !== '/ws' || !tokenOk(candidate)) {
    socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
    return socket.destroy();
  }
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
});

wss.on('connection', (ws) => {
  if (tv && tv !== ws) {
    try { tv.close(4000, 'reemplazada'); } catch (e) { /* ya cerrada */ }
  }
  tv = ws;
  ws.isAlive = true;
  console.log('[ws] TV conectada');

  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (raw) => {
    ws.isAlive = true;
    let m;
    try { m = JSON.parse(raw.toString()); } catch (e) { return; }
    if (m && m.type === 'ack' && typeof m.id === 'string') {
      const i = pending.findIndex((p) => p.id === m.id);
      if (i !== -1) pending.splice(i, 1);
    }
  });

  ws.on('close', () => {
    if (tv === ws) tv = null;
    console.log('[ws] TV desconectada');
  });
  ws.on('error', () => { /* el 'close' se encarga */ });

  // Entregar lo que quedó pendiente mientras la TV estaba apagada
  prune();
  for (const m of pending) sendToTv(m);
});

// Si la TV deja de contestar (cortaron el wifi, la apagaron de golpe) la damos de baja
setInterval(() => {
  if (tv) {
    if (!tv.isAlive) {
      console.log('[ws] TV sin respuesta, cerrando');
      tv.terminate();
      return;
    }
    tv.isAlive = false;
    try { tv.ping(); } catch (e) { /* se cierra solo */ }
  }
}, 30000);

server.listen(PORT, () => console.log(`Relay escuchando en :${PORT}`));
