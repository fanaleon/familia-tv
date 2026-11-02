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

Abrí la app en la TV y probá con **Enviar mensaje de prueba**. Desde ahí arranca sola cada vez que se prende la tele.

Opcional, para que Android no la duerma:

```
adb shell dumpsys deviceidle whitelist +com.serchu.familiatv
```

## Cambiar cosas
- **Frases rápidas del móvil:** lista `quickPhrases` en `MainActivity.kt`.
- **Cuánto dura el mensaje en pantalla:** en `Overlay.kt`, función `next()` (8 s + 70 ms por letra, máximo 30 s).
- **Cambió la URL o el código:** actualizá los secrets y volvé a correr el workflow. El APK nuevo se instala encima del viejo, sin desinstalar.

## Cosas a saber
- La cola del relay vive en memoria: si Railway reinicia el servicio, lo que estaba esperando se pierde. Lo ya mostrado no se ve afectado.
- Los mensajes se limitan a 500 caracteres y los que esperan más de 24 h se descartan.
- Cualquiera que tenga el APK tiene el código de acceso. No lo compartas fuera de la familia.
