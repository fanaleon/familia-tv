// Adaptador del SDK oficial de Solana Pay (@solana/pay, de la Solana Foundation).
//
// Se carga con import() dinámico y solo si está instalado: si falta (o no arranca) pay.js sigue funcionando
// con su verificación manual y lo avisa en el log.
//
// Lo que se usa del SDK:
//   - encodeURL        arma el link  solana:<billetera>?amount=…&spl-token=…&reference=…
//   - findReference    busca la transacción que incluye la referencia del QR
//   - validateTransfer comprueba que esa transacción sea un pago válido a la billetera (monto, token, referencia)
//
// Dos generaciones del SDK, mismas tres funciones:
//   - 0.2.x (la publicada en npm): usa @solana/web3.js 1.x (Connection, PublicKey) y bignumber.js.
//   - 1.x (repo de GitHub): usa @solana/kit, con createMerchantClient({ rpcUrl }).
// El adaptador detecta cuál hay instalada y ofrece la misma interfaz a pay.js.

'use strict';

// "Error de dominio": el SDK respondió y la transacción simplemente no es lo que se busca (no es un fallo de red)
function domainError(mod) {
  return (e) => !!e && (e.name === 'FindReferenceError' || e.name === 'ValidateTransferError' ||
    (typeof mod.FindReferenceError === 'function' && e instanceof mod.FindReferenceError) ||
    (typeof mod.ValidateTransferError === 'function' && e instanceof mod.ValidateTransferError));
}

// El SDK arma los parámetros con URLSearchParams (espacio = "+"); en un link de pago es más seguro %20.
const spaces = (url) => String(url).replace(/\+/g, '%20');

async function load(importer = (name) => import(name)) {
  const mod = await importer('@solana/pay');
  const isDomainError = domainError(mod);

  // ---- SDK 1.x (@solana/kit) ----
  if (typeof mod.createMerchantClient === 'function') {
    const clients = new Map(); // un cliente por URL de RPC
    const client = (rpcUrl) => {
      let c = clients.get(rpcUrl);
      if (!c) { c = mod.createMerchantClient({ rpcUrl }); clients.set(rpcUrl, c); }
      return c;
    };
    return {
      name: '@solana/pay 1.x',
      isDomainError,
      encodeURL: (f) => spaces(mod.encodeURL(f)),
      findReference: (rpcUrl, reference, o = {}) => client(rpcUrl).pay.findReference(reference, o),
      validateTransfer: (rpcUrl, signature, f, o) => client(rpcUrl).pay.validateTransfer(signature, f, o),
    };
  }

  // ---- SDK 0.2.x (@solana/web3.js 1.x + bignumber.js) ----
  if (typeof mod.findReference === 'function' && typeof mod.validateTransfer === 'function' && typeof mod.encodeURL === 'function') {
    const web3 = await importer('@solana/web3.js');
    const bn = await importer('bignumber.js');
    const BigNumber = bn.BigNumber || bn.default || bn;
    const { Connection, PublicKey } = web3;
    if (typeof Connection !== 'function' || typeof PublicKey !== 'function' || typeof BigNumber !== 'function') throw new Error('sdk_incompleto');
    const conns = new Map();
    const conn = (rpcUrl) => {
      let c = conns.get(rpcUrl);
      if (!c) { c = new Connection(rpcUrl, 'confirmed'); conns.set(rpcUrl, c); }
      return c;
    };
    const pk = (x) => new PublicKey(x);
    return {
      name: '@solana/pay 0.2.x',
      isDomainError,
      encodeURL: (f) => spaces(mod.encodeURL({
        recipient: pk(f.recipient),
        amount: new BigNumber(String(f.amount)),
        ...(f.splToken ? { splToken: pk(f.splToken) } : {}),
        reference: pk(f.reference),
        label: f.label,
        message: f.message,
      })),
      findReference: (rpcUrl, reference, o = {}) => {
        const { commitment, ...options } = o;
        return mod.findReference(conn(rpcUrl), pk(reference), options, commitment);
      },
      validateTransfer: (rpcUrl, signature, f, o) => mod.validateTransfer(conn(rpcUrl), signature, {
        recipient: pk(f.recipient),
        amount: new BigNumber(String(f.amount)),
        ...(f.splToken ? { splToken: pk(f.splToken) } : {}),
        reference: pk(f.reference),
      }, o),
    };
  }

  throw new Error('sdk_incompleto');
}

module.exports = { load };
