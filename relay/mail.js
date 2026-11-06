// Aviso por mail al DUEÑO de la app cada vez que alguien compra (plan o TV adicionales) y cuando un plan está por vencer.
//   - RESEND_API_KEY: clave de Resend (https://resend.com). No necesita librerías.
//   - OWNER_EMAIL: dirección que recibe los avisos. Si falta, no se manda nada.
//   - OWNER_LANG: idioma del mail (es, en o pt; por defecto es).
//   - MAIL_FROM: remitente. Por defecto "Familia TV <onboarding@resend.dev>", que Resend solo deja mandar a la
//     dirección con la que se creó la cuenta; para otras direcciones hay que verificar un dominio.
//   - MAIL_TEST=1: manda un mail de prueba al arrancar (sacar la variable después).
const EMAIL_RE = /^[^\s@<>"',;]{1,64}@[^\s@<>"',;]{1,190}\.[A-Za-z]{2,}$/;
const LANGS = ['es', 'en', 'pt'];

const L = {
  es: {
    title: 'Nueva compra',
    intro: 'Alguien acaba de comprar Familia TV.',
    labels: { amount: 'Monto', product: 'Compra', usd: 'Valor', network: 'Red', tx: 'Transacción', expires: 'Plan hasta', cap: 'TV habilitadas' },
    plan: 'Plan Familia TV', extra: 'TV adicionales', test: 'Mail de prueba',
    testText: 'Este es un mail de prueba: acá te van a llegar los avisos de cada compra de Familia TV.',
    foot: 'Aviso automático de Familia TV.',
  },
  en: {
    title: 'New purchase',
    intro: 'Someone just bought Familia TV.',
    labels: { amount: 'Amount', product: 'Purchase', usd: 'Value', network: 'Network', tx: 'Transaction', expires: 'Plan until', cap: 'TVs allowed' },
    plan: 'Familia TV plan', extra: 'Extra TVs', test: 'Test email',
    testText: 'This is a test email: alerts for every Familia TV purchase will arrive here.',
    foot: 'Automatic message from Familia TV.',
  },
  pt: {
    title: 'Nova compra',
    intro: 'Alguém acabou de comprar o Familia TV.',
    labels: { amount: 'Valor pago', product: 'Compra', usd: 'Valor', network: 'Rede', tx: 'Transação', expires: 'Plano até', cap: 'TVs liberadas' },
    plan: 'Plano Familia TV', extra: 'TVs adicionais', test: 'E-mail de teste',
    testText: 'Este é um e-mail de teste: os avisos de cada compra do Familia TV chegarão aqui.',
    foot: 'Aviso automático do Familia TV.',
  },
};

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

function createMail(opts = {}) {
  const env = opts.env || process.env;
  const doFetch = opts.fetch || globalThis.fetch;
  const apiKey = String(env.RESEND_API_KEY || '').trim();
  const from = String(env.MAIL_FROM || 'Familia TV <onboarding@resend.dev>').trim();
  const owner = String(env.OWNER_EMAIL || '').trim();
  const state = {
    to: EMAIL_RE.test(owner) && owner.length <= 254 ? owner : '',
    lang: LANGS.includes(String(env.OWNER_LANG || '').trim()) ? String(env.OWNER_LANG).trim() : 'es',
    lastError: '',
    lastOkAt: 0,
  };
  if (owner && !state.to) console.error('[mail] OWNER_EMAIL no es una dirección válida: no se mandan avisos');

  const mask = (a) => {
    const [u, d] = a.split('@');
    return `${u.slice(0, 2)}${'*'.repeat(Math.max(1, Math.min(6, u.length - 2)))}@${d}`;
  };

  const view = () => ({ configurado: !!apiKey, activo: !!state.to, para: state.to ? mask(state.to) : '', ultimo_error: state.lastError });

  async function send(subject, html, text) {
    if (!state.to) return { ok: false, error: 'sin_destino' };
    if (!apiKey) {
      state.lastError = 'sin_clave';
      return { ok: false, error: 'sin_clave' };
    }
    try {
      const r = await doFetch('https://api.resend.com/emails', {
        method: 'POST',
        headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ from, to: [state.to], subject, html, text }),
        signal: AbortSignal.timeout(15000),
      });
      if (!r.ok) {
        let detail = '';
        try { detail = (await r.json()).message || ''; } catch (e) { /* sin detalle */ }
        state.lastError = `http_${r.status}${detail ? ': ' + String(detail).slice(0, 160) : ''}`;
        console.error('[mail] Resend respondió', state.lastError);
        return { ok: false, error: state.lastError };
      }
      state.lastError = '';
      state.lastOkAt = Date.now();
      return { ok: true };
    } catch (e) {
      state.lastError = `red: ${e.message}`.slice(0, 200);
      console.error('[mail] no se pudo enviar:', e.message);
      return { ok: false, error: state.lastError };
    }
  }

  // HTML simple y compatible con todos los clientes de mail (tablas, estilos en línea)
  function page(title, lines, rows, foot, link) {
    const rowsHtml = rows.map(([k, v]) =>
      `<tr><td style="padding:6px 14px 6px 0;color:#8a90b8;font-size:14px">${esc(k)}</td><td style="padding:6px 0;color:#f5f6fa;font-size:15px;font-weight:600">${esc(v)}</td></tr>`).join('');
    const linesHtml = lines.map((l) => `<p style="margin:0 0 8px;color:#d9dcf2;font-size:15px;line-height:1.5">${esc(l)}</p>`).join('');
    const linkHtml = link ? `<p style="margin:14px 0 0"><a href="${esc(link.url)}" style="color:#a29bfe;font-size:14px">${esc(link.label)}</a></p>` : '';
    return `<!doctype html><html><body style="margin:0;padding:24px;background:#0f1020;font-family:Arial,Helvetica,sans-serif">
<table role="presentation" width="100%" style="max-width:520px;margin:0 auto;background:#1b1d36;border-radius:16px"><tr><td style="padding:26px">
<p style="margin:0 0 4px;color:#a29bfe;font-size:13px;letter-spacing:1px">📺 FAMILIA TV</p>
<h1 style="margin:0 0 16px;color:#f5f6fa;font-size:22px">${esc(title)}</h1>
${linesHtml}
<table role="presentation" style="margin-top:8px">${rowsHtml}</table>${linkHtml}
<p style="margin:22px 0 0;color:#6e739a;font-size:12px">${esc(foot)}</p>
</td></tr></table></body></html>`;
  }

  // info: { producto, monto, moneda, sig, usd, vence, red, capacidad }
  async function notifyPayment(recibo, info) {
    if (!state.to) return;
    const l = L[state.lang];
    const fmt = (ms) => new Date(ms).toLocaleDateString({ es: 'es-AR', en: 'en-US', pt: 'pt-BR' }[state.lang], { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'America/Argentina/Buenos_Aires' });
    const what = info.producto === 'extra' ? l.extra : l.plan;
    const rows = [
      [l.labels.product, what],
      [l.labels.amount, `${info.monto} ${info.moneda}`],
      [l.labels.usd, `${info.usd} USD`],
    ];
    if (info.capacidad) rows.push([l.labels.cap, String(info.capacidad)]);
    if (info.vence) rows.push([l.labels.expires, fmt(info.vence)]);
    if (info.red) rows.push([l.labels.network, info.red === 'devnet' ? 'Solana devnet' : 'Solana']);
    let link = null;
    if (info.sig) {
      const url = `https://solscan.io/tx/${info.sig}${info.red === 'devnet' ? '?cluster=devnet' : ''}`;
      link = { url, label: `${l.labels.tx}: ${info.sig.slice(0, 8)}…${info.sig.slice(-6)}` };
    }
    const subject = `💰 ${l.title}: ${what} · ${info.monto} ${info.moneda}`;
    const text = `${l.intro}\n\n${rows.map(([k, v]) => `${k}: ${v}`).join('\n')}${link ? `\n${link.url}` : ''}\n\n${l.foot}`;
    const r = await send(subject, page(l.title, [l.intro], rows, l.foot, link), text);
    console.log(r.ok ? `[mail] compra avisada a ${mask(state.to)}` : `[mail] no se pudo avisar la compra: ${r.error}`);
  }

  async function sendTest() {
    const l = L[state.lang || 'es'];
    return send(`📺 Familia TV · ${l.test}`, page(l.test, [l.testText], [], l.foot), `${l.testText}\n\n${l.foot}`);
  }

  return { view, notifyPayment, sendTest, enabled: () => !!(apiKey && state.to) };
}

module.exports = { createMail, EMAIL_RE };
