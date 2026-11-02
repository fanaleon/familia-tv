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
