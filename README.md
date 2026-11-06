# Familia TV

Escribís un mensaje en tu móvil y aparece en la tele de casa, arriba de lo que estén viendo, con sonido.

```
 Móvil (app)  --POST /send-->  Relay (Railway)  --WebSocket-->  TV (app, siempre conectada)
```

- `relay/` — servidor Node (WebSocket). Si la tele está apagada, el mensaje espera en cola y se muestra al prenderla.
- `android/` — **una sola app** para los dos: en el móvil te deja escribir, en la TV queda corriendo de fondo.

## Puesta en marcha

### 1. Elegí un código de acceso
Es una clave que comparten el servidor y las apps. Generala una vez:

```
openssl rand -hex 24
```

### 2. Subí el proyecto a GitHub
Hacelo en un repo **privado** (el APK lleva el código adentro).

### 3. Servidor en Railway
1. New Project → Deploy from GitHub repo → elegí este repo.
2. Settings → **Root Directory** = `relay`.
3. Variables → `TOKEN` = el código del paso 1.
4. Settings → Networking → **Generate Domain**. Copiá la URL (`https://algo.up.railway.app`).

Para comprobar que anda, abrí `https://tu-url/health`: tiene que decir `ok`.

### 4. Compilar el APK con GitHub Actions
En el repo: Settings → Secrets and variables → Actions.

| Tipo | Nombre | Valor |
|---|---|---|
| Secret | `RELAY_URL` | la URL de Railway, con `https://` |
| Secret | `RELAY_TOKEN` | el código del paso 1 |
| Variable | `SENDER_NAME` | cómo querés que te vean en la tele (ej. `Papá`) |

Después: pestaña Actions → **Build APK** → Run workflow. A los pocos minutos, bajá el artefacto `familia-tv-apk`.

### 5. Instalar
Es el mismo APK para los dos equipos.

- **Móvil:** abrilo e instalalo.
- **TV:** con adb: `adb connect IP_DE_LA_TV:5555` y `adb install -r app-debug.apk` (o con la app Downloader).

### 6. Permiso en la TV (una sola vez)
Para que el mensaje aparezca encima de lo que se está viendo:

```
adb shell appops set com.serchu.familiatv SYSTEM_ALERT_WINDOW allow
```

Abrí la app en la TV y probá con **Probar mensaje** y **Probar con respuesta**. Desde ahí arranca sola cada vez que se prende la tele.

Opcional, para que Android no la duerma:

```
adb shell dumpsys deviceidle whitelist +com.serchu.familiatv
```

## Novedades de la versión 1.1

- **Foto y nombre:** en el móvil, tocá la foto (o el nombre) de arriba. La foto se achica sola y viaja con cada mensaje.
- **Más de una persona:** mamá instala **el mismo APK** en su móvil, toca su nombre y su foto, y listo. Cada móvil tiene su propia identidad; no hace falta tocar el servidor.
- **Duración:** el botón *Dura: normal / larga* alarga el tiempo en pantalla (45 s o más, según el largo).
- **Respuestas:** *Pedir respuesta: sí* muestra en la tele los botones **OK / Voy / Ahora no**. Quien tenga el control elige uno y a quien mandó le llega una notificación. Si nadie contesta en 3 minutos (o cierran con Atrás), le avisa «Nadie respondió». Para que los botones aparezcan hace falta el permiso de la TV del paso 6.
- En el móvil la app deja una notificación mínima fija: es lo que la mantiene conectada para recibir las respuestas. Aceptá el permiso de notificaciones cuando lo pida.

## Novedades de la versión 1.3

- **Control de la tele (bloqueo):** en el móvil, tarjeta *Control de la tele* con **15 minutos**, **1 hora** o **Hasta las 7:00**. La tele muestra una pantalla de descanso con cuenta regresiva, se silencia y se pausa lo que se reproduzca. Se desbloquea sola al terminar el tiempo, o a mano con *Desbloquear ahora*. El fin del bloqueo queda guardado en la tele: si se corta internet o se reinicia la app, sigue.
- **Quién puede bloquear:** al tocar un botón, el móvil pide huella, cara, patrón o PIN del teléfono. Además, si definís la variable `PARENT_PIN` en Railway (por ejemplo 4 dígitos), el servidor exige ese PIN en cada orden y frena 10 minutos después de 5 intentos fallidos.
- **Zona horaria de "hasta las 7:00":** por defecto Argentina (UTC-3). Se cambia con la variable `TZ_OFFSET_MIN` (minutos respecto de UTC, por ejemplo `-180`).
- **Diseño nuevo:** fondo con degradado, tarjetas de vidrio, anillo de color en la foto, botones con animación, ícono nuevo, reloj en la pantalla de inicio de la tele y tarjetas de mensajes rediseñadas.
- **Límite:** es una pantalla que tapa todo, no un apagado. Quien tenga el control puede forzar la detención de la app desde los ajustes de Android; para cerrar eso usá un perfil restringido o Google Family Link.

## Cobro con Solana Pay (prototipo de una familia)

En la tele, el botón **Plan** abre una pantalla con un **QR de pago**: **5 USDC**, o su equivalente en **SOL** al precio del momento. Se escanea con Phantom, Solflare u otra billetera. El servidor mira la red de Solana, comprueba que a la billetera de cobro le llegó al menos el monto pedido y extiende la licencia **30 días** (o, si se compra el pack de TV, suma lugares). Cada transacción se acepta una sola vez y el QR vence a los 15 minutos.

- **Licencia:** arranca con **7 días de prueba**. Por defecto el plan **no es obligatorio**, así la app sigue funcionando; para exigirlo, definí `REQUIRE_LICENSE=1` (con la licencia vencida el servidor responde 402 a mensajes y bloqueos, pero siempre deja desbloquear).
- **Persistencia:** la licencia se guarda en `DATA_DIR/licencia.json`. En Railway conviene montar un *Volume* y apuntar `DATA_DIR` a él (por ejemplo `/data`); sin volumen se pierde en cada despliegue.
- **Variables (todas opcionales):** `PAY_WALLET` (billetera de cobro), `SOLANA_RPC_URL` (por defecto el RPC público; para uso real conviene uno propio, como el plan gratis de Helius), `USDC_MINT` (por defecto el de mainnet), `PRICE_USD` (5), `LICENSE_DAYS` (30), `TRIAL_DAYS` (7), `PAY_COMMITMENT` (`confirmed` o `finalized`), `SOL_USD_FIXED` (precio fijo de SOL, para pruebas).
- **Probar sin gastar:** apuntá `SOLANA_RPC_URL` a devnet y poné `USDC_MINT=4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU`.
- **Varias TV:** el plan incluye hasta **3 TV** por 5 USD. Para sumar más, desde la tele se compra **+3 TV por 8 USD** (cada grupo de 3 se paga aparte; es un pago único, no vence con el mes). Cada TV se vincula sola la primera vez que abre la app y ocupa su lugar aunque esté apagada; la TV de más recibe un aviso con el botón para sumar lugares. Variables: `PRICE_EXTRA_USD` (8), `TV_BASE` (3), `TV_PACK` (3).
- **Elegir TV:** con 2 o más TV, el móvil muestra una fila «Para:» con *Todas* y cada TV. Los mensajes y los bloqueos van a todas por defecto o solo a la elegida. Mantené apretada una TV para cambiarle el nombre o quitarla (solo si está apagada; así se libera el lugar). La lista de TV se guarda en `DATA_DIR/tvs.json`.
- **Límite:** no hay cobro automático mensual (Solana Pay no lo tiene): cada mes se vuelve a pagar desde el mismo botón.

### Actualizar desde la 1.0
Reemplazá los archivos del proyecto, hacé `git add -A`, `git commit -m "v1.1"` y `git push`. Railway se actualiza solo y GitHub Actions compila el APK nuevo; se instala encima del viejo (en la tele también). Los móviles/tele con la app vieja siguen funcionando con el servidor nuevo.

## Cambiar cosas
- **Frases rápidas del móvil:** lista `quickPhrases` en `MainActivity.kt`.
- **Cuánto dura el mensaje en pantalla:** en `Overlay.kt`, función `stayMillis()`.
- **Cambió la URL o el código:** actualizá los secrets y volvé a correr el workflow. El APK nuevo se instala encima del viejo, sin desinstalar.

## Cosas a saber
- La cola del relay vive en memoria: si Railway reinicia el servicio, lo que estaba esperando se pierde. Lo ya mostrado no se ve afectado.
- Los mensajes se limitan a 500 caracteres y los que esperan más de 24 h se descartan.
- Cualquiera que tenga el APK tiene el código de acceso. No lo compartas fuera de la familia.
