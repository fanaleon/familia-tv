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
  const rpcUrl = (env.SOLANA_RPC_URL || 'https://api.mainnet-beta.solana.com').trim();
  const usdcMint = (env.USDC_MINT || USDC_MAINNET).trim();
  const commitment = env.PAY_COMMITMENT === 'finalized' ? 'finalized' : 'confirmed';
  const priceUsd = Number(env.PRICE_USD) > 0 ? Number(env.PRICE_USD) : 5;
  const licenseDays = Number(env.LICENSE_DAYS) > 0 ? Number(env.LICENSE_DAYS) : 30;
  const trialDays = env.TRIAL_DAYS !== undefined && Number(env.TRIAL_DAYS) >= 0 ? Number(env.TRIAL_DAYS) : 7;
  const requireLicense = ['1', 'true', 'si', 'sí'].includes(String(env.REQUIRE_LICENSE || '').toLowerCase());
  const quoteMs = 15 * 60 * 1000;      // cuánto dura un QR (y el precio de SOL que lleva)
  const graceMs = 2 * 60 * 1000;       // se sigue mirando un rato más por si pagaron justo al final
  const dataDir = path.resolve(env.DATA_DIR || path.join(__dirname, 'data'));
  const stateFile = path.join(dataDir, 'licencia.json');
  const fixedSol = Number(env.SOL_USD_FIXED) > 0 ? Number(env.SOL_USD_FIXED) : 0; // para pruebas/demos

  const enabled = isPubkey(wallet) && isPubkey(usdcMint);
  if (!enabled) console.error('[pago] PAY_WALLET o USDC_MINT no son claves válidas: los pagos quedan desactivados');

  // ----- estado guardado en disco -----
  let state = { firstSeen: now(), licenseUntil: 0, paid: [] };
  try {
    const raw = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
    if (raw && typeof raw === 'object') {
      state = {
        firstSeen: Number(raw.firstSeen) || state.firstSeen,
        licenseUntil: Number(raw.licenseUntil) || 0,
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
    };
  }

  // ¿Se puede usar la app? Solo se corta cuando la licencia es obligatoria y ya venció.
  const allowed = () => !requireLicense || plan().estado !== 'vencido';

  // ----- pagos en curso -----
  const pending = new Map(); // id -> pago
  let timer = null;
  let lastErrLog = 0;

  function prunePending() {
    const t = now();
    for (const [id, p] of pending) {
      if (t > p.expiresAt + graceMs + 10 * 60 * 1000) pending.delete(id); // se guarda un rato para que la TV vea el resultado
    }
    while (pending.size > 12) pending.delete(pending.keys().next().value);
  }

  function view(p) {
    const t = now();
    const estado = p.estado === 'esperando' && t > p.expiresAt ? 'vencido' : p.estado;
    return {
      id: p.id,
      metodo: p.method,
      estado,
      monto: p.amount,
      moneda: p.symbol,
      url: p.url,
      vence_en: Math.max(0, p.expiresAt - t),
    };
  }

  async function create(method) {
    if (!enabled) throw Object.assign(new Error('pagos_desactivados'), { code: 503 });
    if (method !== 'usdc' && method !== 'sol') throw Object.assign(new Error('metodo_invalido'), { code: 400 });
    prunePending();

    const ref = newReference();
    let amount;
    let expected; // en la unidad mínima (lamports o millonésimas de USDC)
    let url;
    const label = encodeURIComponent('Familia TV');
    const message = encodeURIComponent('Plan Familia TV');
    if (method === 'usdc') {
      amount = String(priceUsd);
      expected = BigInt(Math.round(priceUsd * 1e6));
      url = `solana:${wallet}?amount=${amount}&spl-token=${usdcMint}&reference=${ref}&label=${label}&message=${message}`;
    } else {
      let px;
      try {
        px = fixedSol || (await fetchSolUsd(fetchImpl));
      } catch (e) {
        throw Object.assign(new Error('precio_no_disponible'), { code: 503 });
      }
      const sol = Math.ceil((priceUsd / px) * 1e4) / 1e4; // se redondea hacia arriba, 4 decimales
      amount = sol.toFixed(4).replace(/0+$/, '').replace(/\.$/, '');
      expected = BigInt(Math.round(sol * 1e4)) * 100000n;
      url = `solana:${wallet}?amount=${amount}&reference=${ref}&label=${label}&message=${message}`;
    }
    const p = {
      id: crypto.randomUUID(),
      method,
      symbol: method === 'usdc' ? 'USDC' : 'SOL',
      ref,
      amount,
      expected,
      url,
      createdAt: now(),
      expiresAt: now() + quoteMs,
      estado: 'esperando',
    };
    pending.set(p.id, p);
    console.log(`[pago] pedido ${method}: ${amount} ${p.symbol}`);
    start();
    return view(p);
  }

  function get(id) {
    const p = pending.get(id);
    return p ? view(p) : null;
  }

  // ----- verificación en la red -----
  async function rpc(method, params) {
    const r = await fetchImpl(rpcUrl, {
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

  function accept(p, sig) {
    const t = now();
    const base = Math.max(t, state.licenseUntil);
    state.licenseUntil = base + licenseDays * DAY;
    state.paid.push({ sig, metodo: p.method, monto: p.amount, moneda: p.symbol, at: t });
    state.paid = state.paid.slice(-200);
    save();
    p.estado = 'pagado';
    p.sig = sig;
    console.log(`[pago] ¡recibido! ${p.amount} ${p.symbol} (${sig.slice(0, 12)}…) -> licencia hasta ${new Date(state.licenseUntil).toISOString()}`);
  }

  async function check(p) {
    const sigs = await rpc('getSignaturesForAddress', [p.ref, { limit: 5, commitment }]);
    for (const s of sigs || []) {
      if (s.err || used().has(s.signature)) continue;
      const tx = await rpc('getTransaction', [s.signature, { encoding: 'jsonParsed', commitment, maxSupportedTransactionVersion: 0 }]);
      if (paidEnough(tx, p)) {
        accept(p, s.signature);
        return true;
      }
    }
    return false;
  }

  async function poll() {
    const t = now();
    const waiting = [...pending.values()].filter((p) => p.estado === 'esperando' && t <= p.expiresAt + graceMs);
    for (const p of waiting) {
      try {
        await check(p);
      } catch (e) {
        if (t - lastErrLog > 60000) {
          lastErrLog = t;
          console.error('[pago] no pude consultar la red:', e.message);
        }
      }
    }
    prunePending();
    const anyWaiting = [...pending.values()].some((p) => p.estado === 'esperando' && now() <= p.expiresAt + graceMs);
    if (!anyWaiting) stop();
  }

  function start() {
    if (timer) return;
    timer = setInterval(() => { poll().catch(() => {}); }, opts.pollMs || 5000);
  }

  function stop() {
    if (timer) clearInterval(timer);
    timer = null;
  }

  return { create, get, plan, allowed, poll, stop, _state: () => state, enabled, wallet };
}

module.exports = { createPay, b58encode, b58decode, isPubkey, newReference, USDC_MAINNET };
