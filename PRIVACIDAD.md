# Familia TV: datos que maneja la app

Familia TV no tiene cuentas ni pide datos personales. Guarda lo mínimo para llevar mensajes del móvil a la TV y cobrar el plan, y los mensajes no se conservan más de 5 minutos.

## Qué se guarda, dónde y por cuánto tiempo

| Dato | Para qué | Dónde queda | Cuánto tiempo |
| --- | --- | --- | --- |
| Mensajes (texto, nombre de quien envía, color, foto opcional) | Llevarlos a la TV | Memoria del servidor, solo si la TV está apagada. No se escriben en disco | Máximo 5 minutos; se borran al entregarse |
| Respuestas de la TV (OK, Voy, Ahora no) y las primeras palabras del mensaje respondido | Avisar al móvil | Memoria del servidor si el móvil está sin conexión; bandeja del móvil | Máximo 5 minutos |
| Identificador del móvil (código aleatorio, no es el número ni el email) | Saber a qué móvil devolver la respuesta | El móvil; memoria del servidor mientras está conectado | Hasta desinstalar la app |
| Nombre, foto y color del perfil | Mostrarlos junto al mensaje en la TV | El móvil; viajan con cada mensaje | Hasta cambiarlos o desinstalar |
| TV vinculadas (código aleatorio y nombre) | Recordar qué TV hay y respetar el límite del plan | Disco del servidor y la propia TV | Hasta que se borren |
| Bloqueo de la TV (hasta cuándo, motivo, quién lo pidió) | Mantener el bloqueo si la TV se reinicia | La TV y la memoria del servidor | Mientras dure el bloqueo |
| Idioma, sonido, TV de destino, hora de desbloqueo | Preferencias de uso | El móvil o la TV | Hasta desinstalar la app |
| Licencia y pagos (vencimiento, TV extra, firma de la transacción, producto, monto, moneda, fecha) | Controlar el plan y no aceptar dos veces el mismo pago | Disco del servidor | Los últimos 200 pagos, hasta que se borren |
| Pedidos de pago pendientes (referencia aleatoria, monto, link del QR) | Detectar que el pago llegó | Disco del servidor | Hasta 24 horas; luego se descartan |
| Avisos del sistema (pago recibido, plan por vencer) | Notificar al móvil | Disco del servidor y bandeja del móvil | Últimos 20 en el servidor, 50 en el móvil |
| PIN de padres (opcional) | Proteger el bloqueo de la TV | Configuración del servidor; la app lo guarda solo en memoria mientras está abierta | Hasta que el dueño lo cambie |
| Registros del servidor | Diagnóstico | Railway | Según la política de Railway; no incluyen el texto de los mensajes |

## Mensajes: nada dura más de 5 minutos

- El mensaje viaja del móvil al servidor y de ahí a la TV por una conexión cifrada (HTTPS); la app no admite tráfico sin cifrar.
- Si la TV está encendida, el servidor lo reenvía y lo saca de su cola cuando la TV confirma que lo recibió.
- Si la TV está apagada, queda en la memoria del servidor hasta 5 minutos y después se descarta. Nunca se escribe en disco, y la cola se limpia sola cada 30 segundos. El tiempo se puede cambiar con la variable `MSG_TTL_MIN` (5 por defecto).
- Las respuestas de la TV siguen la misma regla: 5 minutos en el servidor, y en el móvil desaparecen de la bandeja y la notificación se retira sola a los 5 minutos.
- En la TV, el «último mensaje» de la pantalla principal se borra a los 5 minutos y solo vive en memoria.
- Los registros del servidor anotan quién envía, cuántos caracteres tiene el mensaje y a qué TV va; no guardan el texto.

## Pagos con Solana Pay

El plan se cobra en USDC o SOL a una billetera del dueño. La app no pide nombre, email ni tarjeta, y el servidor no guarda la billetera de quien paga.

- Del pago, el servidor guarda la firma de la transacción, el producto, el monto, la moneda y la fecha. Sirve para no aceptar dos veces el mismo pago y para extender la licencia.
- La firma de la transacción es pública: cualquiera puede verla en la blockchain de Solana, con la billetera de quien pagó, pero eso no pasa por Familia TV.
- Para detectar el pago el servidor consulta la red de Solana con el SDK oficial `@solana/pay`, y consulta el precio de SOL en Coinbase o Kraken (una consulta pública, sin datos de usuarios).
- Aviso por mail al dueño (opcional): si se configura, cada compra le llega al dueño con el plan, el monto, las TV habilitadas, el vencimiento y el link a la transacción. No se manda nada a los usuarios.
- Hoy la app cobra en la red de pruebas (devnet), sin dinero real.

## Servicios de terceros

| Servicio | Para qué | Qué recibe |
| --- | --- | --- |
| Railway | Aloja el servidor y su disco | Todo lo de la tabla de arriba que se guarda en el servidor; sus propios registros de conexión según su política |
| Red Solana y su nodo RPC | Confirmar los pagos | Consultas públicas de direcciones y transacciones; el pago queda visible en la blockchain |
| Coinbase y Kraken | Precio de SOL | Nada del usuario: es una consulta pública que hace el servidor |
| Resend (opcional) | Mail de aviso al dueño | El email del dueño y el detalle de la compra |
| GitHub | Guarda el código y compila el APK | El código y las claves de conexión como secretos de la compilación |

La app no incluye herramientas de analítica, publicidad ni mensajería de terceros: solo usa una librería de red (OkHttp) y otra para dibujar el QR de pago (ZXing).

## Permisos de Android

| Permiso | Para qué sirve |
| --- | --- |
| Internet y estado de la red | Conectarse al servidor |
| Servicio en primer plano | Mantener la conexión abierta para recibir mensajes |
| Notificaciones | Avisos de respuestas, pagos y vencimiento en el móvil |
| Iniciar al encender | Reconectar sola cuando se prende la TV o el móvil |
| Mostrar sobre otras apps | Dibujar el mensaje encima de lo que se está viendo en la TV |
| Mantener activo (wake lock) | Que la conexión no se corte con la pantalla apagada |
| Biometría | Pedir huella, cara, patrón o PIN del móvil antes de bloquear la TV; lo valida Android y la app no accede a esos datos |

La copia de seguridad automática de Android está desactivada: los datos de la app no se suben a la nube de Google.

## Lo que la app no recolecta

- No hay cuentas ni registro: no se pide email, teléfono ni contraseña.
- No accede a ubicación, contactos, cámara, micrófono, archivos ni al historial de la TV.
- No tiene analítica, publicidad ni seguimiento.
- La única imagen es la foto de perfil que la persona elige, reducida a menos de 30 KB.

## Borrado y puntos a tener en cuenta

Desinstalar la app borra todo lo que guarda el móvil o la TV. En el servidor, el dueño puede borrar TV, pagos, licencia y avisos en cualquier momento.

- El límite de 5 minutos es para mensajes y respuestas. Los avisos de pago y de vencimiento se conservan (hasta 20 en el servidor y 50 en el móvil) porque sirven de comprobante.
- Los mensajes no tienen cifrado de punta a punta: el servidor los ve mientras los reenvía, aunque no los guarda.
- Todas las instalaciones comparten una misma clave de acceso al servidor: no hay cuentas por persona. Es un prototipo pensado para una sola familia.
- Los registros de conexión de Railway se rigen por la política de Railway, no por la de la app.
