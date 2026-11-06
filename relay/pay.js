// Cobro con Solana Pay + licencia de la familia (prototipo de una sola familia).
//
// Cómo funciona:
//   1. La TV pide un pago (POST /pay/create). El servidor genera una "referencia" única (una clave
//      pública al azar) y arma el link de Solana Pay: solana:<billetera>?amount=…&reference=…
//   2. La TV dibuja ese link como QR; la persona lo escanea con Phantom, Solflare, etc. y paga.
//   3. El servidor mira la red cada pocos segundos: busca transacciones que incluyan esa referencia
//      y comprueba que a la billetera de cobro le haya llegado al menos el monto pedido (en USDC o
//      en SOL). Cada transacción se acepta una sola vez.
//   4. Con el pago confirmado se extiende la licencia (30 días por defecto). Todo se guarda en un
//      archivo (DATA_DIR) para que sobreviva a los reinicios del servidor.
// No hace falta ninguna librería de Solana: solo el JSON-RPC público.
//
// Robustez: la RPC pública limita los pedidos (error 429). Por eso se aceptan varias RPC separadas por
// coma en SOLANA_RPC_URL (si una falla se prueba la siguiente), se espera más entre intentos cuando
// hay errores, y los pagos pendientes se guardan en disco: si el servidor se reinicia o la red estuvo
// caída, un pago hecho hasta 24 h después de generar el QR igual se acepta.

'use strict';

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const DAY = 24 * 60 * 60 * 1000;
const B58 = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
const USDC_MAINNET = 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v';

// ---------- base58 (para claves de Solana) ----------
function b58encode(buf) {
  let n = BigInt('0x' + (buf.toString('hex') || '0'));
  let out = '';
  while (n > 0n) {
    out = B58[Number(n % 58n)] + out;
    n /= 58n;
  }
  for (const b of buf) {
    if (b === 0) out = '1' + out;
    else break;
  }
  return out;
}

function b58decode(str) {
  let n = 0n;
  for (const ch of str) {
    const i = B58.indexOf(ch);
    if (i < 0) throw new Error('base58_invalido');
    n = n * 58n + BigInt(i);
  }
  let hex = n.toString(16);
  if (hex.length % 2) hex = '0' + hex;
  const body = n === 0n ? Buffer.alloc(0) : Buffer.from(hex, 'hex');
  let zeros = 0;
  for (const ch of str) {
    if (ch === '1') zeros++;
    else break;
  }
  return Buffer.concat([Buffer.alloc(zeros), body]);
}

function isPubkey(s) {
  if (typeof s !== 'string' || s.length < 32 || s.length > 44) return false;
  try {
    return b58decode(s).length === 32;
  } catch (e) {
    return false;
  }
}

// Una clave pública nueva y válida para usar de referencia (nadie necesita la privada)
function newReference() {
  const { publicKey } = crypto.generateKeyPairSync('ed25519');
  const x = publicKey.export({ format: 'jwk' }).x;
  return b58encode(Buffer.from(x, 'base64url'));
}

// ---------- precio de SOL ----------
async function fetchJson(url, fetchImpl) {
  const r = await fetchImpl(url, { signal: AbortSignal.timeout(8000), headers: { accept: 'application/json' } });
  if (!r.ok) throw new Error('http_' + r.status);
  return r.json();
}

async function fetchSolUsd(fetchImpl) {
  const sources = [
    async () => Number((await fetchJson('https://api.coinbase.com/v2/prices/SOL-USD/spot', fetchImpl)).data.amount),
    async () => Number((await fetchJson('https://api.kraken.com/0/public/Ticker?pair=SOLUSD', fetchImpl)).result.SOLUSD.c[0]),
  ];
  for (const src of sources) {
    try {
      const p = await src();
      if (Number.isFinite(p) && p > 1 && p < 100000) return p;
    } catch (e) { /* prueba la siguiente fuente */ }
  }
  throw new Error('precio_no_disponible');
}

// ---------- el módulo ----------
function createPay(opts = {}) {
  const env = opts.env || process.env;
  const fetchImpl = opts.fetch || globalThis.fetch;
  const now = opts.now || (() => Date.now());

  const wallet = (env.PAY_WALLET || 'Ag2v5B3GFfnCxSJcwS7RBKn9ijf2oqMWz2uEFoLbevYH').trim();
  const rpcUrls = String(env.SOLANA_RPC_URL || 'https://api.mainnet-beta.solana.com')
    .split(',').map((u) => u.trim()).filter(Boolean);
  let rpcIdx = 0;
  const usdcMint = (env.USDC_MINT || USDC_MAINNET).trim();
  const commitment = env.PAY_COMMITMENT === 'finalized' ? 'finalized' : 'confirmed';
  const priceUsd = Number(env.PRICE_USD) > 0 ? Number(env.PRICE_USD) : 5;           // plan: hasta tvBase TV
  const priceExtraUsd = Number(env.PRICE_EXTRA_USD) > 0 ? Number(env.PRICE_EXTRA_USD) : 8; // cada grupo extra de TV
  const tvBase = Number(env.TV_BASE) >= 1 ? Math.floor(Number(env.TV_BASE)) : 3;     // TV incluidas en el plan
  const tvPack = Number(env.TV_PACK) >= 1 ? Math.floor(Number(env.TV_PACK)) : 3;     // TV que suma cada grupo extra
  const licenseDays = Number(env.LICENSE_DAYS) > 0 ? Number(env.LICENSE_DAYS) : 30;
  const trialDays = env.TRIAL_DAYS !== undefined && Number(env.TRIAL_DAYS) >= 0 ? Number(env.TRIAL_DAYS) : 7;
  const requireLicense = ['1', 'true', 'si', 'sí'].includes(String(env.REQUIRE_LICENSE || '').toLowerCase());
  const quoteMs = 15 * 60 * 1000;      // cuánto dura un QR (y el precio de SOL que lleva)
  const graceMs = 2 * 60 * 1000;       // se sigue mirando un rato más por si pagaron justo al final
  const lateMs = 24 * 60 * 60 * 1000;  // y después, con menos frecuencia, hasta un día (por si la red falló)
  const lateEveryMs = 60 * 1000;
  const remindDays = Number(env.REMIND_DAYS) >= 0 ? Number(env.REMIND_DAYS) : 3; // aviso antes de que venza
  const dataDir = path.resolve(env.DATA_DIR || path.join(__dirname, 'data'));
  const stateFile = path.join(dataDir, 'licencia.json');
  const pendingFile = path.join(dataDir, 'pagos.json');
  const fixedSol = Number(env.SOL_USD_FIXED) > 0 ? Number(env.SOL_USD_FIXED) : 0; // para pruebas/demos

  const enabled = isPubkey(wallet) && isPubkey(usdcMint);
  if (!enabled) console.error('[pago] PAY_WALLET o USDC_MINT no son claves válidas: los pagos quedan desactivados');

  // ----- estado guardado en disco -----
  let state = { firstSeen: now(), licenseUntil: 0, packs: 0, remindedFor: 0, paid: [] };
  try {
    const raw = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
    if (raw && typeof raw === 'object') {
      state = {
        firstSeen: Number(raw.firstSeen) || state.firstSeen,
        licenseUntil: Number(raw.licenseUntil) || 0,
        packs: Number.isInteger(raw.packs) && raw.packs > 0 ? raw.packs : 0,
        remindedFor: Number(raw.remindedFor) || 0,
        paid: Array.isArray(raw.paid) ? raw.paid.slice(-200) : [],
      };
    }
  } catch (e) { /* primer arranque */ }

  function save() {
    try {
      fs.mkdirSync(dataDir, { recursive: true });
      const tmp = stateFile + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(state));
      fs.renameSync(tmp, stateFile);
    } catch (e) {
      console.error('[pago] no se pudo guardar la licencia en disco:', e.message);
    }
  }
  save();

  // ----- licencia -----
  function plan() {
    const t = now();
    const trialEnds = state.firstSeen + trialDays * DAY;
    let estado = 'vencido';
    let hasta = null;
    if (state.licenseUntil > t) {
      estado = 'activo';
      hasta = state.licenseUntil;
    } else if (trialEnds > t) {
      estado = 'prueba';
      hasta = trialEnds;
    }
    return {
      estado,
      hasta,
      exigida: requireLicense,
      precio_usd: priceUsd,
      dias: licenseDays,
      pagos: enabled,
      tvs_incluidas: tvBase,
      tvs_por_grupo: tvPack,
      grupos_extra: state.packs,
      capacidad: capacity(),
      precio_extra_usd: priceExtraUsd,
    };
  }

  // Cuántas TV se pueden vincular: las del plan + las de cada grupo extra comprado
  function capacity() {
    return tvBase + state.packs * tvPack;
  }

  // ¿Se puede usar la app? Solo se corta cuando la licencia es obligatoria y ya venció.
  const allowed = () => !requireLicense || plan().estado !== 'vencido';

  // ----- pagos en curso -----
  const pending = new Map(); // id -> pago
  let timer = null;
  let lastErrLog = 0;
  let failStreak = 0;

  function savePending() {
    try {
      fs.mkdirSync(dataDir, { recursive: true });
      const list = [...pending.values()].map((p) => ({ ...p, expected: String(p.expected), rejected: [...(p.rejected || [])] }));
      const tmp = pendingFile + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(list));
      fs.renameSync(tmp, pendingFile);
    } catch (e) {
      console.error('[pago] no se pudieron guardar los pagos pendientes:', e.message);
    }
  }

  try {
    const raw = JSON.parse(fs.readFileSync(pendingFile, 'utf8'));
    for (const p of Array.isArray(raw) ? raw : []) {
      if (p && typeof p.id === 'string' && isPubkey(p.ref) && /^\d+$/.test(String(p.expected))) {
        pending.set(p.id, { ...p, expected: BigInt(p.expected), rejected: new Set(p.rejected || []) });
      }
    }
  } catch (e) { /* primer arranque */ }

  // Los que esperan se guardan un día (por si el pago se hizo y la red no respondió); el resto, una hora
  function prunePending() {
    const t = now();
    let changed = false;
    for (const [id, p] of pending) {
      const old = p.estado === 'esperando' ? t > p.expiresAt + lateMs : t > (p.doneAt || p.expiresAt) + 60 * 60 * 1000;
      if (old) { pending.delete(id); changed = true; }
    }
    while (pending.size > 30) { pending.delete(pending.keys().next().value); changed = true; }
    if (changed) savePending();
  }

  function view(p) {
    const t = now();
    const estado = p.estado === 'esperando' && t > p.expiresAt ? 'vencido' : p.estado;
    return {
      id: p.id,
      metodo: p.method,
      producto: p.product,
      estado,
      monto: p.amount,
      moneda: p.symbol,
      url: p.url,
      vence_en: Math.max(0, p.expiresAt - t),
      recibo: p.estado === 'pagado' ? p.recibo || null : null,
    };
  }

  async function create(method, product) {
    if (!enabled) throw Object.assign(new Error('pagos_desactivados'), { code: 503 });
    if (method !== 'usdc' && method !== 'sol') throw Object.assign(new Error('metodo_invalido'), { code: 400 });
    if (product === undefined || product === null) product = 'plan';
    if (product !== 'plan' && product !== 'extra') throw Object.assign(new Error('producto_invalido'), { code: 400 });
    prunePending();
    const price = product === 'extra' ? priceExtraUsd : priceUsd;

    const ref = newReference();
    let amount;
    let expected; // en la unidad mínima (lamports o millonésimas de USDC)
    let url;
    const label = encodeURIComponent('Familia TV');
    const message = encodeURIComponent(product === 'extra' ? `Familia TV: ${tvPack} TV más` : 'Plan Familia TV');
    if (method === 'usdc') {
      amount = String(price);
      expected = BigInt(Math.round(price * 1e6));
      url = `solana:${wallet}?amount=${amount}&spl-token=${usdcMint}&reference=${ref}&label=${label}&message=${message}`;
    } else {
      let px;
      try {
        px = fixedSol || (await fetchSolUsd(fetchImpl));
      } catch (e) {
        throw Object.assign(new Error('precio_no_disponible'), { code: 503 });
      }
      const sol = Math.ceil((price / px) * 1e4) / 1e4; // se redondea hacia arriba, 4 decimales
      amount = sol.toFixed(4).replace(/0+$/, '').replace(/\.$/, '');
      expected = BigInt(Math.round(sol * 1e4)) * 100000n;
      url = `solana:${wallet}?amount=${amount}&reference=${ref}&label=${label}&message=${message}`;
    }
    const p = {
      id: crypto.randomUUID(),
      method,
      product,
      symbol: method === 'usdc' ? 'USDC' : 'SOL',
      ref,
      amount,
      expected,
      url,
      createdAt: now(),
      expiresAt: now() + quoteMs,
      estado: 'esperando',
      rejected: new Set(),
    };
    pending.set(p.id, p);
    savePending();
    console.log(`[pago] pedido ${product} ${method}: ${amount} ${p.symbol}`);
    start();
    return view(p);
  }

  function get(id) {
    const p = pending.get(id);
    return p ? view(p) : null;
  }

  // ----- verificación en la red -----
  async function rpcOne(url, method, params) {
    const r = await fetchImpl(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }),
      signal: AbortSignal.timeout(10000),
    });
    if (!r.ok) throw new Error('rpc_http_' + r.status);
    const j = await r.json();
    if (j.error) throw new Error('rpc_' + (j.error.message || j.error.code));
    return j.result;
  }

  // Prueba la RPC actual y, si falla, las otras; se queda con la que responde
  async function rpc(method, params) {
    let lastErr;
    for (let k = 0; k < rpcUrls.length; k++) {
      const idx = (rpcIdx + k) % rpcUrls.length;
      try {
        const out = await rpcOne(rpcUrls[idx], method, params);
        rpcIdx = idx;
        return out;
      } catch (e) {
        lastErr = e;
      }
    }
    throw lastErr;
  }

  // ¿A la billetera de cobro le llegó, en esta transacción, al menos lo que se pidió?
  function paidEnough(tx, p) {
    if (!tx || !tx.meta || tx.meta.err) return false;
    if (p.method === 'sol') {
      const keys = (tx.transaction.message.accountKeys || []).map((k) => (typeof k === 'string' ? k : k.pubkey));
      const i = keys.indexOf(wallet);
      if (i < 0) return false;
      const delta = BigInt(tx.meta.postBalances[i]) - BigInt(tx.meta.preBalances[i]);
      return delta >= p.expected;
    }
    const sum = (list) =>
      (list || [])
        .filter((b) => b.owner === wallet && b.mint === usdcMint)
        .reduce((acc, b) => acc + BigInt(b.uiTokenAmount.amount), 0n);
    return sum(tx.meta.postTokenBalances) - sum(tx.meta.preTokenBalances) >= p.expected;
  }

  const used = () => new Set(state.paid.map((x) => x.sig));

  const fmtDate = (ms) => {
    try {
      return new Date(ms).toLocaleDateString('es-AR', { day: 'numeric', month: 'long', timeZone: 'America/Argentina/Buenos_Aires' });
    } catch (e) {
      return new Date(ms).toISOString().slice(0, 10);
    }
  };

  // Texto de confirmación: lo que pagó, qué compró, cuándo vence y cuándo se le avisa
  function buildReceipt(p) {
    const usd = p.product === 'extra' ? priceExtraUsd : priceUsd;
    const lines = [`Pagaste ${p.amount} ${p.symbol} (${p.product === 'extra' ? `${tvPack} TV más` : 'plan'}, ${usd} USD).`];
    const pl = plan();
    if (p.product === 'extra') lines.push(`Ya podés vincular hasta ${capacity()} TV.`);
    else lines.push(`Plan Familia TV: hasta ${capacity()} TV, ${licenseDays} días.`);
    if (pl.estado === 'activo') {
      lines.push(`Vence el ${fmtDate(pl.hasta)}.`);
      if (remindDays > 0) lines.push(`Te aviso ${remindDays} días antes (el ${fmtDate(pl.hasta - remindDays * DAY)}).`);
    } else if (pl.estado === 'prueba') {
      lines.push(`Seguís en la prueba, que termina el ${fmtDate(pl.hasta)}: activá el plan antes.`);
    }
    return { texto: lines.join('\n'), titulo: '✅ Pago recibido', usd, vence: pl.estado === 'activo' ? pl.hasta : null };
  }

  function accept(p, sig) {
    const t = now();
    if (p.product === 'extra') {
      state.packs += 1;
    } else {
      const base = Math.max(t, state.licenseUntil);
      state.licenseUntil = base + licenseDays * DAY;
    }
    state.paid.push({ sig, producto: p.product, metodo: p.method, monto: p.amount, moneda: p.symbol, at: t });
    state.paid = state.paid.slice(-200);
    save();
    p.estado = 'pagado';
    p.sig = sig;
    p.doneAt = t;
    p.recibo = buildReceipt(p);
    savePending();
    try { if (opts.onPaid) opts.onPaid(p.recibo, { producto: p.product, monto: p.amount, moneda: p.symbol }); } catch (e) { console.error('[pago] onPaid:', e.message); }
    console.log(`[pago] ¡recibido! ${p.product} ${p.amount} ${p.symbol} (${sig.slice(0, 12)}…) -> capacidad ${capacity()} TV, licencia hasta ${state.licenseUntil ? new Date(state.licenseUntil).toISOString() : '—'}`);
  }

  async function check(p) {
    const sigs = await rpc('getSignaturesForAddress', [p.ref, { limit: 5, commitment }]);
    for (const s of sigs || []) {
      if (s.err || used().has(s.signature) || (p.rejected && p.rejected.has(s.signature))) continue;
      const tx = await rpc('getTransaction', [s.signature, { encoding: 'jsonParsed', commitment, maxSupportedTransactionVersion: 0 }]);
      if (!tx) continue; // todavía no está disponible: se vuelve a mirar
      if (paidEnough(tx, p)) {
        accept(p, s.signature);
        return true;
      }
      if (p.rejected) p.rejected.add(s.signature); // llegó, pero no alcanza (o no es a nuestra billetera)
    }
    return false;
  }

  // Un solo ciclo a la vez. Los pagos recientes se miran cada vez; los que ya vencieron, una vez por minuto.
  async function poll() {
    const t = now();
    const waiting = [...pending.values()].filter((p) => {
      if (p.estado !== 'esperando') return false;
      if (t <= p.expiresAt + graceMs) return true;
      if (t > p.expiresAt + lateMs) return false;
      return t - (p.lastLate || 0) >= lateEveryMs;
    });
    let failed = false;
    for (const p of waiting) {
      if (t > p.expiresAt + graceMs) p.lastLate = t;
      try {
        await check(p);
      } catch (e) {
        failed = true;
        if (t - lastErrLog > 60000) {
          lastErrLog = t;
          console.error('[pago] no pude consultar la red:', e.message);
        }
        break; // si la red no responde, no se insiste con el resto: se reintenta después
      }
    }
    failStreak = failed ? failStreak + 1 : 0;
    prunePending();
    const anyWaiting = [...pending.values()].some((p) => p.estado === 'esperando' && now() <= p.expiresAt + lateMs);
    return anyWaiting;
  }

  // Con errores (por ejemplo 429) se espera cada vez más: 5 s, 10 s, 20 s… hasta 60 s
  function schedule() {
    if (timer) return;
    const base = opts.pollMs || 5000;
    const wait = Math.min(60000, base * Math.pow(2, Math.min(failStreak, 4)));
    timer = setTimeout(async () => {
      timer = null;
      let more = false;
      try { more = await poll(); } catch (e) { /* se reintenta */ }
      if (more && !stopped) schedule();
    }, wait);
  }
  let stopped = false;

  function start() {
    stopped = false;
    schedule();
  }

  function stop() {
    stopped = true;
    if (timer) clearTimeout(timer);
    timer = null;
  }

  // ----- aviso antes de que venza -----
  // Devuelve un aviso (una sola vez por licencia) cuando falta remindDays o menos para que venza.
  function dueReminder() {
    const t = now();
    if (remindDays <= 0 || state.licenseUntil <= t || state.remindedFor === state.licenseUntil) return null;
    if (state.licenseUntil - t > remindDays * DAY) return null;
    const dias = Math.max(1, Math.ceil((state.licenseUntil - t) / DAY));
    return {
      until: state.licenseUntil,
      titulo: '⏰ Tu plan de Familia TV está por vencer',
      texto: `Vence el ${fmtDate(state.licenseUntil)} (en ${dias} ${dias === 1 ? 'día' : 'días'}). Para renovarlo, abrí «Plan» en la tele y pagá desde el QR.`,
    };
  }

  function markReminded(until) {
    state.remindedFor = until;
    save();
  }

  // Si el servidor se reinició con pagos pendientes, se retoma la vigilancia
  if ([...pending.values()].some((p) => p.estado === 'esperando')) setImmediate(() => start());

  return { create, get, plan, allowed, capacity, poll, stop, dueReminder, markReminded, _state: () => state, enabled, wallet };
}

module.exports = { createPay, b58encode, b58decode, isPubkey, newReference, USDC_MAINNET };
