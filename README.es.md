# Hey Family (antes Familia TV)

🌎 **Español** · [English](README.md)

**Escribís un mensaje en tu móvil y aparece en la tele de casa, arriba de lo que estén viendo, con sonido.** Con un botón de **aviso urgente** que ocupa toda la pantalla, **control de la tele** para los chicos y **cobro con Solana Pay**.

```
 Móvil (app)  --POST /send-->  Relay (Railway)  --WebSocket-->  TV (app, siempre conectada)
      ^                                                             |
      +------------------ respuestas y "visto" (WebSocket) ---------+
```

Proyecto hecho para un hackathon online de Solana. Una sola app de Android sirve para el móvil y para la TV; un servidor Node chiquito los conecta.

## Contenido

1. [Qué hace](#qué-hace)
2. [Cómo está armado](#cómo-está-armado)
3. [Las notificaciones en la TV](#las-notificaciones-en-la-tv)
4. [Control de la tele y guardia](#control-de-la-tele-y-guardia)
5. [Cobro con Solana Pay](#cobro-con-solana-pay)
6. [Puesta en marcha](#puesta-en-marcha)
7. [Variables del servidor](#variables-del-servidor)
8. [API del relay](#api-del-relay)
9. [Herramientas que usamos](#herramientas-que-usamos)
10. [Cómo lo fuimos construyendo (paso a paso)](#cómo-lo-fuimos-construyendo-paso-a-paso)
11. [Video de demo](#video-de-demo)
12. [Estructura del repositorio](#estructura-del-repositorio)
13. [Cambiar cosas](#cambiar-cosas)
14. [Límites y cosas a saber](#límites-y-cosas-a-saber)

Más datos: [`PRIVACIDAD.md`](PRIVACIDAD.md) ([English](PRIVACY.md)) explica qué datos maneja la app, dónde se guardan, por cuánto tiempo y qué permisos pide.

---

## Qué hace

- **Mensajes a la tele:** del móvil a la TV de casa en un segundo. Si la TV está apagada, el mensaje espera en cola hasta 5 minutos y se muestra al prenderla.
- **Notificación con vida:** tarjeta en el centro con velo oscuro, luz del color de quien escribe, avatar con ondas y texto que se escribe solo.
- **Respuestas desde el sillón:** con el control de la tele se contesta **OK / Voy / 5 minutos / Ahora no** y a quien escribió le llega una notificación.
- **Aviso urgente:** botón del móvil (hay que mantenerlo apretado 1,5 s). La tele muestra una **pantalla roja completa** con «Llamame Urgente», suena cada 10 segundos y pausa lo que se esté viendo, hasta que alguien la cierra con el control. A quien llamó le llega «Ya lo vieron en la tele».
- **Foto, nombre y color:** cada persona de la familia instala el mismo APK y tiene su propia identidad.
- **Varias TV:** hasta 3 TV con el plan, y se pueden sumar de a 3. El móvil elige a cuál mandar.
- **Control de la tele (bloqueo):** pantalla de descanso con cuenta regresiva (15 minutos, 1 hora o hasta las 7:00), con un guardia de seguridad animado que patrulla y se duerme.
- **Cobro con Solana Pay:** QR de pago en la tele, en USDC o SOL, verificado en la red con el SDK oficial `@solana/pay`. 7 días de prueba y licencia de 30 días.
- **Tres idiomas:** inglés (por defecto), español y portugués. Se elige al abrir la app, tanto en el móvil como en la TV.
- **Sin cuentas:** no pide email ni teléfono. Los mensajes no se guardan más de 5 minutos.

## Cómo está armado

| Parte | Qué es | Dónde |
|---|---|---|
| **Relay** | Servidor Node 20 con HTTP y WebSocket. Cola de mensajes por TV, respuestas hacia el móvil, bloqueos, licencia y pagos. | [`relay/`](relay/) |
| **App Android** | Un solo APK (`com.serchu.familiatv`, nombre «Hey Family», Android 7.0 o superior). En el móvil deja escribir y controlar; en la TV queda de fondo mostrando mensajes. | [`android/`](android/) |
| **Compilación** | GitHub Actions arma el APK y lo deja como artefacto (y en una rama `apk-*`). | [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml) |
| **Hosting** | Railway (servicio `relay` + un Volume para guardar la licencia). Cada `push` a `main` se despliega solo. | Railway |

Reglas que sigue el sistema:

- La TV mantiene una conexión WebSocket abierta con el relay; el móvil manda mensajes por HTTPS (`POST /send`) y recibe las respuestas por su propio WebSocket.
- Todo está protegido con una **clave de acceso compartida** (`TOKEN`) que llevan el servidor y las apps.
- La cola de mensajes vive **en memoria** y se limpia sola; lo único que se escribe en disco es la licencia, los pagos, las TV vinculadas y los avisos.

---

## Las notificaciones en la TV

Hay tres tipos. Todos suenan con el sonido que se elija en la app (o silencio) y se ven por encima de lo que se esté mirando. Para eso la TV necesita el permiso de [«mostrar sobre otras apps»](#6-permiso-en-la-tv-una-sola-vez).

### 1. Mensaje normal
- Tarjeta **centrada**, con el avatar de quien escribe, su nombre y el mensaje.
- **Velo oscuro** suave sobre el contenido y una **luz del color de esa persona** detrás de la tarjeta, que respira.
- El avatar tiene **ondas** que se expanden, la tarjeta entra desde abajo con rebote y el texto **se escribe solo**.
- Una **barra de tiempo** muestra cuánto queda en pantalla (45 s o más, según el largo del mensaje o el botón *Dura: normal / larga*).
- No toma el control remoto: no molesta mientras alguien mira algo.

### 2. Mensaje que pide respuesta
- Igual que el normal, pero el velo es más oscuro y la tarjeta muestra los botones **OK / Voy / 5 minutos / Ahora no**.
- Se elige con el control. A quien escribió le llega una notificación («Respondieron: …»).
- El sonido se repite cada 20 segundos. Si nadie contesta en 3 minutos (o cierran con Atrás), llega «Nadie respondió».
- Apenas aparece en pantalla, el móvil recibe «Ya lo vieron en la tele».

### 3. Aviso urgente
- **Desde el móvil:** botón rojo *Urgente: mantené apretado*. Se llena mientras lo mantenés apretado y a los 1,5 segundos sale (así no se manda sin querer). El texto es siempre **«Llamame Urgente»**.
- **En la TV:** pantalla roja completa con la insignia ⚡ URGENTE, avatar grande con ondas que no paran, «*Mamá* te necesita» y el mensaje enorme. **No tiene botones.**
- **Sonido:** se repite cada 10 segundos y, mientras suena, pausa lo que se esté reproduciendo.
- **Cerrarlo:** con **Atrás u OK** del control. Eso significa que ya lo vieron y a quien llamó le llega «Ya lo vieron en la tele». Si nadie lo cierra en unos 5 minutos, llega «Nadie respondió».
- **Límite:** un aviso urgente por minuto por persona (el servidor responde `429`), para que nadie lo use de juego. No reemplaza al 911: es un aviso interno de la familia.
- Mantiene la pantalla de la tele encendida mientras está visible.

Para probar sin móvil, en la pantalla de inicio de la TV hay botones **Probar mensaje**, **Con respuesta**, **Urgente** y **Bloqueo (45 s)**.

## Control de la tele y guardia

- En el móvil, la tarjeta *Control de la tele* ofrece **15 minutos**, **1 hora** o **Hasta las 7:00**. La tele muestra una pantalla de descanso con cuenta regresiva, se silencia y se pausa lo que se reproduzca. Se desbloquea sola al terminar, o a mano con *Desbloquear ahora*. El fin del bloqueo queda guardado en la tele: si se corta internet o se reinicia la app, sigue.
- **El guardia:** un guardia de seguridad dibujado por código (sin imágenes) patrulla por abajo, se apoya en el cartel de «No molestar», se cruza de brazos y se duerme. Si tocan un botón del control, se despierta, golpea el vidrio tres veces y mueve el dedo diciendo que no. Reacciona hasta 3 veces por bloqueo para que no se vuelva un juego.
- **Quién puede bloquear:** al tocar un botón, el móvil pide huella, cara, patrón o PIN del teléfono. Si además definís `PARENT_PIN` en el servidor, exige ese PIN en cada orden y frena 10 minutos después de 5 intentos fallidos.
- **Zona horaria de «hasta las 7:00»:** por defecto Argentina (UTC-3); se cambia con `TZ_OFFSET_MIN`.
- **Límite:** es una pantalla que tapa todo, no un apagado. Quien tenga el control puede forzar la detención de la app desde los ajustes de Android; para cerrar eso usá un perfil restringido o Google Family Link.

## Cobro con Solana Pay

En la tele, el botón **Plan** abre una pantalla con un **QR de pago**: **5 USDC**, o su equivalente en **SOL** al precio del momento. Se escanea con Phantom, Solflare u otra billetera. El servidor mira la red de Solana, comprueba que a la billetera de cobro le llegó al menos el monto pedido y extiende la licencia **30 días** (o, si se compra el pack de TV, suma lugares). Cada transacción se acepta una sola vez y el QR vence a los 15 minutos.

**Hoy corre en devnet** (red de pruebas, sin dinero real). Para pasar a mainnet hay que cambiar `SOLANA_RPC_URL` y quitar `USDC_MINT` (ver [variables](#variables-del-servidor)).

- **Licencia:** arranca con **7 días de prueba**. Por defecto el plan **no es obligatorio**; con `REQUIRE_LICENSE=1` el servidor responde 402 a mensajes y bloqueos con la licencia vencida (siempre deja desbloquear).
- **Persistencia:** la licencia se guarda en `DATA_DIR/licencia.json`. En Railway va un *Volume* montado y `DATA_DIR` apuntando a él (por ejemplo `/data`); sin volumen se pierde en cada despliegue.
- **Probar sin gastar:** apuntá `SOLANA_RPC_URL` a devnet y poné `USDC_MINT=4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU` (el USDC de devnet).
- **Varias TV:** el plan incluye hasta **3 TV** por 5 USD. Para sumar más, desde la tele se compra **+3 TV por 8 USD** (pago único, no vence con el mes). Cada TV se vincula sola la primera vez que abre la app y ocupa su lugar aunque esté apagada; la TV de más recibe un aviso con el botón para sumar lugares.
- **Elegir TV:** con 2 o más TV, el móvil muestra una fila «Para:» con *Todas* y cada TV. Los mensajes y los bloqueos van a todas o solo a la elegida. Mantené apretada una TV para cambiarle el nombre o quitarla (solo si está apagada). La lista se guarda en `DATA_DIR/tvs.json`.
- **Confirmación y aviso:** al detectar el pago, la tele muestra el recibo (qué pagaste, qué plan, cuándo vence) y el móvil recibe una notificación con lo mismo. **3 días antes de que venza**, el móvil y las teles reciben un aviso (una sola vez por licencia). En el móvil hay una bandeja (campana) con el historial.
- **SDK oficial:** el link del QR (`encodeURL`), la búsqueda del pago por referencia (`findReference`) y su validación (`validateTransfer`) usan [`@solana/pay`](https://github.com/solana-foundation/pay) de la Solana Foundation, instalado como dependencia opcional (`relay/solanapay.js`, con `@solana/web3.js` y `bignumber.js`). El adaptador soporta la 0.2.x de npm y la 1.x. Si el SDK no está o falla, se usa la verificación manual por JSON-RPC (el log dice `[pago] Solana Pay: SDK oficial (…) activo` o `… no disponible`). Si el SDK rechaza una transacción por su forma (por ejemplo, una billetera que agrega instrucciones extra) pero el saldo de la billetera de cobro sí subió lo pedido, el pago se acepta igual.
- **Billeteras que no mandan la referencia:** si una billetera manda el pago sin la referencia del QR, se reconoce por el **monto exacto** y porque llegó después de pedir el QR (`PAY_MATCH_BY_AMOUNT=0` lo apaga). Los pedidos iguales se resuelven juntos para que la tele muestre el recibo.
- **Si la red de Solana falla (error 429):** `SOLANA_RPC_URL` acepta varias RPC separadas por coma y el servidor usa la siguiente si una falla. Los pagos pendientes se guardan en disco y un pago hecho hasta 24 h después de generar el QR igual se acepta. Para uso real conviene una RPC propia (por ejemplo, el plan gratis de Helius).
- **Aviso por mail al dueño:** cada vez que entra un pago, el servidor manda un mail con la compra, el monto, el valor en USD, las TV habilitadas, el vencimiento y el link a la transacción en Solscan. Usa [Resend](https://resend.com) sin librerías. Si el mail falla, el pago se acredita igual y el error queda en el log (`[mail] …`).
- **Precio de SOL:** se consulta a Coinbase o Kraken (consulta pública); `SOL_USD_FIXED` lo fija para pruebas.
- **Empezar de cero:** con `RESET_STATE=1` el servidor borra TV, pagos, licencia y avisos al arrancar (sacá la variable después).
- **Límite:** no hay cobro automático mensual (Solana Pay no lo tiene): cada mes se vuelve a pagar desde el mismo botón.

---

## Puesta en marcha

### 1. Elegí un código de acceso
Es una clave que comparten el servidor y las apps. Generala una vez:

```
openssl rand -hex 24
```

### 2. Subí el proyecto a GitHub
Hacelo en un repo **privado** (el APK lleva la clave adentro).

### 3. Servidor en Railway
1. New Project → Deploy from GitHub repo → elegí este repo.
2. Settings → **Root Directory** = `relay`.
3. Variables → `TOKEN` = el código del paso 1.
4. (Para el cobro) agregá un **Volume**, montalo en `/data` y poné `DATA_DIR=/data`. Después las variables de pago de la [tabla](#variables-del-servidor): `PAY_WALLET`, `SOLANA_RPC_URL`, `USDC_MINT`…
5. Settings → Networking → **Generate Domain**. Copiá la URL (`https://algo.up.railway.app`).

Para comprobar que anda, abrí `https://tu-url/health`: tiene que decir `ok`. El log del servicio muestra `[pago] Solana Pay: SDK oficial … activo`.

> El servidor pide **Node 20 o más** (lo necesita el SDK de Solana Pay); está indicado en `relay/package.json`.

### 4. Compilar el APK con GitHub Actions
En el repo: Settings → Secrets and variables → Actions.

| Tipo | Nombre | Valor |
|---|---|---|
| Secret | `RELAY_URL` | la URL de Railway, con `https://` |
| Secret | `RELAY_TOKEN` | el código del paso 1 |
| Variable | `SENDER_NAME` | cómo querés que te vean en la tele (ej. `Papá`) |

Después: pestaña Actions → **Build APK** → Run workflow. A los pocos minutos, bajá el artefacto `familia-tv-apk` (`app-debug.apk`). También compila solo cada vez que se cambia algo en `android/` y se sube a `main`.

El APK se arma con **JDK 17 (Temurin)** y **Gradle 8.7** (`gradle assembleDebug`). El número de versión interno sube solo con cada compilación (`GITHUB_RUN_NUMBER`).

**Ramas de APK:** el mismo workflow, corrido sobre una rama `version-*` (por ejemplo `version-guardia`), además deja el APK listo en una rama aparte (`apk-guardia`) para bajarlo directo con `git fetch origin apk-guardia` y `git show FETCH_HEAD:familia-tv-guardia.apk > hey-family.apk`.

### 5. Instalar
Es el mismo APK para todos los equipos.

- **Móvil:** abrilo e instalalo. Aceptá el permiso de notificaciones cuando lo pida.
- **TV:** con adb: `adb connect IP_DE_LA_TV:5555` y `adb install -r app-debug.apk` (o con la app Downloader).

### 6. Permiso en la TV (una sola vez)
Para que el mensaje aparezca encima de lo que se está viendo:

```
adb shell appops set com.serchu.familiatv SYSTEM_ALERT_WINDOW allow
```

Abrí la app en la TV, elegí idioma y probá con los botones **Probar mensaje**, **Con respuesta** y **Urgente**. Desde ahí arranca sola cada vez que se prende la tele.

Opcional, para que Android no la duerma:

```
adb shell dumpsys deviceidle whitelist +com.serchu.familiatv
```

### 7. Sumar a la familia
Cada persona instala **el mismo APK** en su móvil, toca su foto o nombre arriba y elige su color. No hace falta tocar el servidor.

### Actualizar
Subí los cambios a `main` (`git add -A`, `git commit`, `git push`). Railway se actualiza solo y GitHub Actions compila el APK nuevo; se instala encima del viejo (también en la tele), sin desinstalar. Los equipos con la app vieja siguen funcionando con el servidor nuevo.

---

## Variables del servidor

Todas son opcionales salvo `TOKEN`.

| Variable | Para qué | Por defecto |
|---|---|---|
| `TOKEN` | Clave de acceso compartida con las apps (**obligatoria**) | — |
| `PORT` | Puerto del servidor | `3000` (Railway lo pone solo) |
| `MSG_TTL_MIN` | Minutos que espera un mensaje si la TV está apagada | `5` |
| `DATA_DIR` | Carpeta donde se guardan licencia, TV, pagos y avisos | `relay/data` |
| `PARENT_PIN` | PIN de padres para bloquear la tele | sin PIN |
| `TZ_OFFSET_MIN` | Zona horaria de «hasta las 7:00», en minutos desde UTC | `-180` |
| `PAY_WALLET` | Billetera que cobra | la del proyecto |
| `SOLANA_RPC_URL` | RPC de Solana; acepta varias separadas por coma | RPC público de mainnet |
| `USDC_MINT` | Dirección del USDC | USDC de mainnet |
| `PRICE_USD` | Precio del plan | `5` |
| `PRICE_EXTRA_USD` | Precio de +3 TV | `8` |
| `TV_BASE` / `TV_PACK` | TV incluidas en el plan / TV por cada pack | `3` / `3` |
| `LICENSE_DAYS` | Días de licencia por pago | `30` |
| `TRIAL_DAYS` | Días de prueba | `7` |
| `REMIND_DAYS` | Días antes del vencimiento para avisar | `3` |
| `REQUIRE_LICENSE` | `1` = la licencia es obligatoria | apagado |
| `PAY_COMMITMENT` | `confirmed` o `finalized` | `confirmed` |
| `PAY_SDK` | `0` apaga el SDK oficial de Solana Pay | encendido |
| `PAY_MATCH_BY_AMOUNT` | `0` apaga el reconocimiento por monto exacto | encendido |
| `SOL_USD_FIXED` | Precio fijo de SOL (pruebas) | precio del mercado |
| `RESEND_API_KEY` | Clave de Resend para los mails de compra | sin mails |
| `OWNER_EMAIL` | Quién recibe los mails (si falta, no se manda nada) | — |
| `OWNER_LANG` | Idioma de los mails: `es`, `en` o `pt` | `es` |
| `MAIL_FROM` | Remitente. Con `onboarding@resend.dev` Resend solo manda a la dirección de la cuenta; para otras hay que verificar un dominio | `Familia TV <onboarding@resend.dev>` |
| `MAIL_TEST` | `1` manda un mail de prueba al arrancar (sacarla después) | apagado |
| `RESET_STATE` | `1` borra TV, pagos, licencia y avisos al arrancar (sacarla después) | apagado |

## API del relay

Todas las rutas (salvo `/health`) piden `Authorization: Bearer <TOKEN>`.

| Ruta | Qué hace |
|---|---|
| `GET /health` | Devuelve `ok`. Sin clave. |
| `GET /status` | Estado: TV conectadas y su cola, bloqueo, plan, capacidad, avisos. |
| `POST /send` | Manda un mensaje. Campos: `text` (hasta 500), `from`, `uid` (identificador del móvil), `avatar` (base64, hasta ~30 KB), `color` (`azul`, `rosa`, `verde`, `amarillo`), `duration` (`normal` / `larga`), `reply` (pide respuesta), `urgent` (aviso urgente; necesita `uid`, uno por minuto), `tv` (id de la TV; sin él va a todas). |
| `POST /control` | Bloquea o desbloquea la tele: `action` (`freeze` / `unfreeze`), `minutes` (hasta 720) o `mode: "until"` con `hour` y `minute`, `from`, `tv` y `pin` (si hay `PARENT_PIN`). |
| `POST /pay/create` | Crea un pedido de pago (`metodo`: `usdc` o `sol`; `producto`: `plan` o `extra`) y devuelve el QR. |
| `GET /pay/status?id=…` | Estado de un pago y del plan. |
| `POST /tv/rename`, `POST /tv/forget` | Renombra o quita una TV (solo si está apagada). |
| `WS /ws?role=tv&id=…&name=…` | La TV recibe mensajes y manda `ack` y `reply`. |
| `WS /ws?role=phone&uid=…` | El móvil recibe las respuestas de la TV (`ok`, `voy`, `ahora_no`, `cinco_min`, `visto`, `sin_respuesta`). |

Códigos de error útiles: `401` clave incorrecta, `402` licencia vencida, `403` PIN requerido o incorrecto, `429` demasiados PIN erróneos o aviso urgente muy seguido (`espera_s` dice cuánto falta).

---

## Herramientas que usamos

**La app y el servidor**

| Herramienta | Para qué |
|---|---|
| **Kotlin + Android SDK** (minSdk 24, targetSdk 34) | La app. Pantallas hechas con vistas por código (sin Compose ni XML de layouts), animaciones propias y vectores para el logo. |
| **OkHttp 4.12** | Conexión HTTPS y WebSocket desde la app. |
| **ZXing 3.5** | Dibuja el QR de pago. |
| **AndroidX Core + BiometricPrompt de Android** | Compatibilidad, y pedido de huella, cara, patrón o PIN del teléfono antes de bloquear la tele. |
| **Node.js 20 + `ws`** | El relay (HTTP y WebSocket), sin framework. |
| **`@solana/pay`, `@solana/web3.js`, `bignumber.js`** | SDK oficial de Solana Pay: crear el link del QR, buscar y validar el pago. |
| **Solana (devnet) + USDC de devnet** | Red de pruebas para cobrar sin dinero real. |
| **Resend** | Mail de aviso al dueño con cada compra. |
| **Coinbase / Kraken (API pública)** | Precio de SOL para el monto en SOL. |
| **Railway** | Hosting del relay, variables y Volume; despliegue automático desde GitHub. |
| **GitHub + GitHub Actions** | Repositorio privado y compilación del APK (JDK 17 Temurin, Gradle 8.7). |
| **adb** | Instalar el APK en la TV y dar el permiso de «mostrar sobre otras apps». |

**Con qué lo construimos**

| Herramienta | Para qué |
|---|---|
| **Claude (Claude Code y la app de Claude)** | Escribió y revisó el código de la app y del servidor, diseñó el logo, armó los bocetos, hizo el video y esta documentación. Trabajó con el repo por `git` y `gh`. |
| **Railway MCP** | Desde Claude: ver despliegues, variables y registros del servicio. |
| **Diseño de Claude (lienzo de bocetos)** | Bocetos animados de la notificación (Aura, cinta inferior, urgente, voz y stickers) antes de programarla. |
| **Node + `ws` (prueba local)** | Prueba de humo del relay: una TV y un móvil de mentira, aviso urgente, límite por minuto y respuestas. |
| **Playwright (Chromium) + ffmpeg** | Cuadro a cuadro del video de demo y codificación final. |
| **Python (numpy / scipy)** | Música suave de fondo y efectos de sonido del video. |
| **Grok** | La voz en off en inglés del video. |

## Cómo lo fuimos construyendo (paso a paso)

1. **v1.0, el núcleo:** relay en Railway + app única. El móvil manda el mensaje, la TV lo muestra encima de lo que se esté viendo, con sonido. Cola de 5 minutos si la TV está apagada. APK compilado en GitHub Actions.
2. **v1.1, la familia:** foto y nombre, varias personas con el mismo APK, duración del mensaje y botones de respuesta (OK / Voy / Ahora no) con aviso de vuelta al móvil.
3. **v1.2, colores y sonido:** colores por persona y sonido de aviso configurable y más suave.
4. **v1.3, control de la tele:** bloqueo con cuenta regresiva (15 min, 1 h, hasta las 7:00), huella o PIN del teléfono, `PARENT_PIN` opcional y rediseño completo (fondo con degradado, tarjetas de vidrio, reloj en la pantalla de inicio). La pantalla de descanso fue probando personajes hasta quedar con el guardia de seguridad.
5. **Cobro con Solana Pay:** QR en la tele, licencia con 7 días de prueba, verificación por JSON-RPC y después con el **SDK oficial**. Pagos con USDC o SOL en devnet.
6. **Varias TV:** 3 con el plan y +3 por 8 USD; selector de destino en el móvil, cola y bloqueo por TV.
7. **Pagos más robustos:** recibo en la tele y en el móvil, aviso 3 días antes del vencimiento, varias RPC, reintentos ante el error 429, pagos tardíos (hasta 24 h) y billeteras que no mandan la referencia (reconocimiento por monto exacto).
8. **Mail al dueño** con cada compra (Resend) y `RESET_STATE` para empezar de cero.
9. **Tres idiomas:** español, inglés y portugués, con selector al abrir.
10. **Privacidad:** ningún mensaje se guarda más de 5 minutos y se escribió [`PRIVACIDAD.md`](PRIVACIDAD.md).
11. **Hey Family:** nuevo nombre (arranca en inglés), **logo** propio (casa + TV + mensaje con corazón), ícono adaptativo, banner de Android TV y el pie «Built on Solana · Solana Pay» con el logo oficial de Solana.
12. **Notificación nueva (v1.4):** primero se probaron cuatro bocetos animados (Aura, cinta inferior, urgente, voz y stickers) con una foto real de la tele de por medio. Quedó el **look de Aura** centrado con velo y luz de color, el **aviso urgente** con pantalla roja completa y el **«visto en la tele»** de vuelta al móvil. En la foto de prueba se vio que los halos de la tarjeta medían toda la pantalla; se corrigió y se centró.
13. **Aviso urgente afinado:** sin botones ni leyenda, texto fijo «Llamame Urgente» y cierre con el control (Atrás u OK) como señal de que ya lo vieron.

**Cómo se probó:** pruebas de humo del relay (aviso urgente, límite de un minuto, respuestas hacia el móvil), compilación en GitHub Actions en cada cambio, pagos de prueba en devnet y comprobación en una TV real (con los botones de prueba de la pantalla de inicio).

## Video de demo

El video de presentación (≈ 78 s, 1080p) está hecho con herramientas abiertas, sin editor de video:

1. **Guion** y **voz en off en inglés**, generada con Grok.
2. **Animación en HTML:** una sola página dibuja todas las escenas (móvil, relay, tele, pago con QR, guardia, cierre en una TV LED) y se puede pedir un instante exacto con `render(t)`.
3. **Cuadros con Playwright:** Chromium saca una captura por cada 1/30 de segundo, sin saltos de cuadro.
4. **Codificación con ffmpeg:** H.264 a 30 fps en cuatro tramos que se unen sin recodificar.
5. **Sonido:** voz normalizada, música suave generada con Python (baja sola cuando habla la voz), efectos de aviso y de clic, y subtítulos quemados.

## Estructura del repositorio

```
.
├── README.md
├── PRIVACIDAD.md
├── .github/workflows/build-apk.yml     Compila el APK (y lo deja en ramas apk-* desde ramas version-*)
├── relay/
│   ├── server.js                       HTTP + WebSocket: mensajes, respuestas, bloqueos, TV, avisos
│   ├── pay.js                          Licencia, pedidos de pago, verificación en Solana
│   ├── solanapay.js                    Adaptador del SDK oficial @solana/pay (0.2.x y 1.x)
│   ├── mail.js                         Aviso por mail al dueño (Resend)
│   └── data/                           Ejemplos de tvs.json y licencia.json (en Railway va el Volume)
└── android/app/src/main/
    ├── java/com/serchu/familiatv/
    │   ├── MainActivity.kt             Pantalla del móvil y de la TV, botón urgente
    │   ├── Overlay.kt                  Notificaciones sobre otras apps (normal, con respuesta, urgente)
    │   ├── RelayService.kt / RelayLink.kt   Conexión de la TV con el relay
    │   ├── PhoneService.kt             Conexión del móvil para recibir respuestas
    │   ├── Freeze.kt / Guard.kt        Bloqueo de la tele y el guardia animado
    │   ├── Sounds.kt                   Sonidos de aviso
    │   ├── I18n.kt / Strings.kt        Idiomas (en, es, pt)
    │   ├── Support.kt / Cfg.kt / App.kt / BootReceiver.kt
    └── res/                            Logo, ícono adaptativo, banner de TV y logo de Solana
```

## Cambiar cosas
- **Frases rápidas del móvil:** lista `quickPhrases` en `MainActivity.kt`.
- **Cuánto dura el mensaje en pantalla:** en `Overlay.kt`, función `stayMillis()`.
- **Cada cuánto se repite el sonido de un aviso urgente:** en `Overlay.kt` (10 s) y el límite de uno por minuto, `URGENT_GAP_MS` en `relay/server.js`.
- **Textos y traducciones:** `Strings.kt`.
- **Cambió la URL o el código:** actualizá los secrets y volvé a correr el workflow. El APK nuevo se instala encima del viejo.

## Límites y cosas a saber
- La cola del relay vive en memoria: si Railway reinicia el servicio, lo que estaba esperando se pierde. Lo ya mostrado no se ve afectado.
- Los mensajes se limitan a 500 caracteres y nada se conserva más de 5 minutos (`MSG_TTL_MIN`).
- Cualquiera que tenga el APK tiene el código de acceso. No lo compartas fuera de la familia. Es un prototipo pensado para una sola familia.
- Los mensajes no tienen cifrado de punta a punta: el servidor los ve mientras los reenvía, aunque no los guarda.
- No hay cobro automático mensual con Solana Pay.
- El aviso urgente es un aviso interno de la familia, no un servicio de emergencias.
- Ideas que quedaron para después: lectura del mensaje en voz alta, melodía propia por persona, stickers de respuesta, pasar a mainnet y publicar en Google Play.
