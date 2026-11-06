// Adaptador del SDK oficial de Solana Pay (@solana/pay, de la Solana Foundation).
//
// El SDK es un módulo ESM y depende de @solana/kit, así que se carga con import() dinámico y solo si está
// instalado: si falta (o no arranca) pay.js sigue funcionando con su verificación manual y avisa en el log.
//
// Lo que se usa del SDK:
//   - encodeURL        arma el link  solana:<billetera>?amount=…&spl-token=…&reference=…
//   - findReference    busca la transacción que incluye la referencia del QR
//   - validateTransfer comprueba que esa transacción sea un pago válido a la billetera (monto, token, referencia)

'use strict';

async function load() {
  const mod = await import('@solana/pay');
  if (typeof mod.createMerchantClient !== 'function') throw new Error('sdk_incompleto');
  const clients = new Map(); // un cliente por URL de RPC

  function client(rpcUrl) {
    let c = clients.get(rpcUrl);
    if (!c) {
      c = mod.createMerchantClient({ rpcUrl });
      clients.set(rpcUrl, c);
    }
    return c;
  }

  // "Error de dominio": el SDK respondió, la transacción simplemente no es lo que se busca (no es un fallo de red)
  function isDomainError(e) {
    return !!e && (e.name === 'FindReferenceError' || e.name === 'ValidateTransferError' ||
      (mod.FindReferenceError && e instanceof mod.FindReferenceError) ||
      (mod.ValidateTransferError && e instanceof mod.ValidateTransferError));
  }

  // El SDK arma los parámetros con URLSearchParams (espacio = "+"); en un link de pago es más seguro %20.
  function encodeURL(fields) {
    return mod.encodeURL(fields).toString().replace(/\+/g, '%20');
  }

  return {
    name: '@solana/pay',
    encodeURL,
    isDomainError,
    findReference: (rpcUrl, reference, options) => client(rpcUrl).pay.findReference(reference, options),
    validateTransfer: (rpcUrl, signature, fields, options) => client(rpcUrl).pay.validateTransfer(signature, fields, options),
  };
}

module.exports = { load };
