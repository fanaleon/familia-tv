# Hey Family (formerly Familia TV)

🌎 [Español](README.es.md) · **English**

**Type a message on your phone and it pops up on the living-room TV, on top of whatever the family is watching, with a sound.** It also has an **urgent alert** that takes over the whole screen, a **TV lock** for the kids and **payments with Solana Pay**.

```
 Phone (app)  --POST /send-->  Relay (Railway)  --WebSocket-->  TV (app, always connected)
      ^                                                             |
      +---------------- replies and "seen" (WebSocket) -------------+
```

Built for an online Solana hackathon. One Android app runs on both the phone and the TV, and a tiny Node server connects them.

## Contents

1. [What it does](#what-it-does)
2. [How it is built](#how-it-is-built)
3. [Notifications on the TV](#notifications-on-the-tv)
4. [TV lock and the guard](#tv-lock-and-the-guard)
5. [Payments with Solana Pay](#payments-with-solana-pay)
6. [Getting started](#getting-started)
7. [Server variables](#server-variables)
8. [Relay API](#relay-api)
9. [Tools we used](#tools-we-used)
10. [How we built it, step by step](#how-we-built-it-step-by-step)
11. [Demo video](#demo-video)
12. [Repository layout](#repository-layout)
13. [Changing things](#changing-things)
14. [Limits and things to know](#limits-and-things-to-know)

More: [`PRIVACY.md`](PRIVACY.md) ([Español](PRIVACIDAD.md)) explains what data the app handles, where it is stored, for how long and which permissions it asks for.

> **A note on the code:** the app's interface is in English by default (Spanish and Portuguese are one tap away), but the original code comments and some internal names are in Spanish, because the project started as a family tool in Argentina.

---

## What it does

- **Messages to the TV:** from the phone to the home TV in about a second. If the TV is off, the message waits in a queue for up to 5 minutes and shows up when the TV turns on.
- **A notification with life:** a centered card with a dark veil, a glow in the sender's color, an avatar with expanding rings and text that types itself.
- **Replies from the couch:** with the TV remote you answer **OK / I'm coming / 5 minutes / Not now**, and the sender gets a notification.
- **Urgent alert:** a phone button you have to hold for 1.5 s. The TV shows a **full red screen** with "Call me, urgent", rings every 10 seconds and pauses whatever is playing, until someone closes it with the remote. The sender gets "They've seen it on the TV".
- **Photo, name and color:** each family member installs the same APK and has their own identity.
- **Several TVs:** up to 3 TVs on the plan, and more can be added in packs of 3. The phone picks which one to send to.
- **TV lock:** a rest screen with a countdown (15 minutes, 1 hour or until 7:00) and an animated security guard who patrols and falls asleep.
- **Payments with Solana Pay:** a payment QR on the TV, in USDC or SOL, verified on-chain with the official `@solana/pay` SDK. 7-day trial and 30-day license.
- **Three languages:** English (default), Spanish and Portuguese. Chosen when the app opens, on both phone and TV.
- **No accounts:** no email or phone number needed. Messages are never kept longer than 5 minutes.

## How it is built

| Part | What it is | Where |
|---|---|---|
| **Relay** | Node 20 server with HTTP and WebSocket. Per-TV message queue, replies back to the phone, locks, license and payments. | [`relay/`](relay/) |
| **Android app** | A single APK (`com.serchu.familiatv`, named "Hey Family", Android 7.0+). On the phone it lets you write and control; on the TV it stays in the background and shows messages. | [`android/`](android/) |
| **Build** | GitHub Actions builds the APK and leaves it as an artifact (and on an `apk-*` branch). | [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml) |
| **Hosting** | Railway (a `relay` service plus a Volume that keeps the license). Every push to `main` deploys by itself. | Railway |

How the system behaves:

- The TV keeps a WebSocket open to the relay. The phone sends messages over HTTPS (`POST /send`) and receives replies on its own WebSocket.
- Everything is protected by a **shared access key** (`TOKEN`) carried by the server and the apps.
- The message queue lives **in memory** and cleans itself up. The only things written to disk are the license, payments, linked TVs and notices.

---

## Notifications on the TV

There are three kinds. All of them play the sound chosen in the app (or stay silent) and appear over whatever is on screen. For that, the TV needs the ["display over other apps" permission](#6-tv-permission-once).

### 1. Normal message
- A **centered** card with the sender's avatar, name and message.
- A soft **dark veil** over the content and a **glow in that person's color** behind the card, which breathes.
- The avatar has expanding **rings**, the card springs in from below and the text **types itself**.
- A **time bar** shows how long is left on screen (45 s or more, depending on message length or the *Stays: normal / long* button).
- It does not grab the remote, so it doesn't get in the way while someone is watching.

### 2. Message that asks for a reply
- Like the normal one, but the veil is darker and the card shows the buttons **OK / I'm coming / 5 minutes / Not now**.
- You pick one with the remote. The sender gets a notification ("They replied: …").
- The sound repeats every 20 seconds. If nobody answers in 3 minutes (or they close it with Back), the sender gets "Nobody replied".
- As soon as it appears on screen, the phone receives "They've seen it on the TV".

### 3. Urgent alert
- **From the phone:** a red *Urgent: press and hold* button. It fills up while you hold it and fires after 1.5 seconds, so it can't be sent by accident. The text is always the same and follows the sender's app language: **"Call me, urgent"** in English, **"Llamame Urgente"** in Spanish, **"Me liga, urgente"** in Portuguese.
- **On the TV:** a full red screen with the ⚡ URGENT badge, a big avatar with endless rings, "*Mom* needs you" and the message in huge type. **It has no buttons.**
- **Sound:** repeats every 10 seconds and, while it rings, pauses whatever is playing.
- **Closing it:** with **Back or OK** on the remote. That means they've seen it, and the caller gets "They've seen it on the TV". If nobody closes it in about 5 minutes, the caller gets "Nobody replied".
- **Limit:** one urgent alert per minute per person (the server answers `429`), so nobody uses it as a toy. It does not replace emergency services: it is an internal family alert.
- It keeps the TV screen on while it is visible.

To try it without a phone, the TV home screen has the buttons **Test message**, **With reply**, **Urgent** and **Lock (45 s)**.

## TV lock and the guard

- On the phone, the *TV control* card offers **15 minutes**, **1 hour** or **Until 7:00**. The TV shows a rest screen with a countdown, mutes itself and pauses whatever is playing. It unlocks by itself when the time is up, or by hand with *Unlock now*. The end of the lock is stored on the TV: if the internet drops or the app restarts, it keeps going.
- **The guard:** a security guard drawn entirely in code (no images) patrols along the bottom, leans on the "Do not disturb" sign, crosses his arms and falls asleep. If someone presses a remote button, he wakes up, knocks on the glass three times and wags his finger. He reacts at most 3 times per lock, so it doesn't turn into a game.
- **Who can lock:** when a button is tapped, the phone asks for fingerprint, face, pattern or the phone PIN. If you also set `PARENT_PIN` on the server, it requires that PIN on every order and stops for 10 minutes after 5 wrong attempts.
- **Time zone for "until 7:00":** Argentina (UTC-3) by default; change it with `TZ_OFFSET_MIN`.
- **Limit:** it is a screen that covers everything, not a power-off. Whoever holds the remote can force-stop the app from Android settings; to close that gap use a restricted profile or Google Family Link.

## Payments with Solana Pay

On the TV, the **Plan** button opens a screen with a **payment QR**: **5 USDC**, or the equivalent in **SOL** at the current price. It is scanned with Phantom, Solflare or any other wallet. The server watches the Solana network, checks that at least the requested amount arrived at the collection wallet and extends the license by **30 days** (or, if the TV pack is bought, adds slots). Each transaction is accepted only once and the QR expires after 15 minutes.

**Today it runs on devnet** (test network, no real money). To move to mainnet, change `SOLANA_RPC_URL` and remove `USDC_MINT` (see [variables](#server-variables)).

- **License:** starts with a **7-day trial**. By default the plan is **not mandatory**; with `REQUIRE_LICENSE=1` the server answers 402 to messages and locks once the license has expired (it always lets you unlock).
- **Persistence:** the license is stored in `DATA_DIR/licencia.json`. On Railway a *Volume* is mounted and `DATA_DIR` points to it (for example `/data`); without a volume it is lost on every deploy.
- **Try it without spending:** point `SOLANA_RPC_URL` at devnet and set `USDC_MINT=4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU` (devnet USDC).
- **Several TVs:** the plan includes up to **3 TVs** for 5 USD. To add more, buy **+3 TVs for 8 USD** from the TV (one-time payment, it does not expire with the month). Each TV links itself the first time it opens the app and keeps its slot even while off; an extra TV gets a notice with a button to add slots.
- **Choosing a TV:** with 2 or more TVs the phone shows a "To:" row with *All* and each TV. Messages and locks go to all of them or only to the chosen one. Long-press a TV to rename it or remove it (only while it is off). The list is stored in `DATA_DIR/tvs.json`.
- **Confirmation and reminder:** when the payment is detected, the TV shows the receipt (what you paid, which plan, when it expires) and the phone gets a notification with the same. **3 days before it expires**, the phone and TVs get a reminder (once per license). The phone has a bell inbox with the history.
- **Official SDK:** the QR link (`encodeURL`), the payment lookup by reference (`findReference`) and its validation (`validateTransfer`) use [`@solana/pay`](https://github.com/solana-foundation/pay) from the Solana Foundation, installed as an optional dependency (`relay/solanapay.js`, with `@solana/web3.js` and `bignumber.js`). The adapter supports npm's 0.2.x and 1.x. If the SDK is missing or fails, it falls back to manual JSON-RPC verification (the log says `[pago] Solana Pay: SDK oficial (…) activo` or `… no disponible`). If the SDK rejects a transaction because of its shape (for example a wallet that adds extra instructions) but the collection wallet's balance did go up by the requested amount, the payment is accepted anyway.
- **Wallets that don't send the reference:** if a wallet sends the payment without the QR's reference, it is recognized by the **exact amount** and by arriving after the QR was requested (`PAY_MATCH_BY_AMOUNT=0` turns it off). Identical requests are resolved together so the TV shows the receipt.
- **If the Solana network fails (error 429):** `SOLANA_RPC_URL` accepts several RPCs separated by commas and the server moves on to the next one if one fails. Pending payments are saved to disk, and a payment made up to 24 h after the QR was generated is still accepted. For real use, bring your own RPC (for example Helius's free plan).
- **Email to the owner:** every time a payment arrives, the server sends an email with the purchase, the amount, the value in USD, the TVs enabled, the expiry date and a Solscan link to the transaction. It uses [Resend](https://resend.com) with no libraries. If the email fails, the payment is still credited and the error goes to the log (`[mail] …`).
- **SOL price:** fetched from Coinbase or Kraken (public query); `SOL_USD_FIXED` pins it for testing.
- **Starting from zero:** with `RESET_STATE=1` the server wipes TVs, payments, license and notices on startup (remove the variable afterwards).
- **Limit:** there is no automatic monthly charge (Solana Pay doesn't have one): each month you pay again from the same button.

---

## Getting started

### 1. Pick an access key
A key shared by the server and the apps. Generate it once:

```
openssl rand -hex 24
```

### 2. Push the project to GitHub
Use a **private** repo (the APK carries the key inside).

### 3. Server on Railway
1. New Project → Deploy from GitHub repo → pick this repo.
2. Settings → **Root Directory** = `relay`.
3. Variables → `TOKEN` = the key from step 1.
4. (For payments) add a **Volume**, mount it at `/data` and set `DATA_DIR=/data`. Then the payment variables from the [table](#server-variables): `PAY_WALLET`, `SOLANA_RPC_URL`, `USDC_MINT`…
5. Settings → Networking → **Generate Domain**. Copy the URL (`https://something.up.railway.app`).

To check that it works, open `https://your-url/health`: it must say `ok`. The service log shows `[pago] Solana Pay: SDK oficial … activo`.

> The server needs **Node 20 or newer** (the Solana Pay SDK requires it); it is declared in `relay/package.json`.

### 4. Build the APK with GitHub Actions
In the repo: Settings → Secrets and variables → Actions.

| Type | Name | Value |
|---|---|---|
| Secret | `RELAY_URL` | the Railway URL, with `https://` |
| Secret | `RELAY_TOKEN` | the key from step 1 |
| Variable | `SENDER_NAME` | how you want to appear on the TV (e.g. `Dad`) |

Then: Actions tab → **Build APK** → Run workflow. After a few minutes, download the artifact `familia-tv-apk` (`app-debug.apk`). It also builds by itself every time something in `android/` is pushed to `main`.

The APK is built with **JDK 17 (Temurin)** and **Gradle 8.7** (`gradle assembleDebug`). The internal version number goes up by itself with each build (`GITHUB_RUN_NUMBER`).

**APK branches:** the same workflow, run on a `version-*` branch (for example `version-guardia`), also leaves the finished APK on a separate branch (`apk-guardia`) so you can grab it directly with `git fetch origin apk-guardia` and `git show FETCH_HEAD:familia-tv-guardia.apk > hey-family.apk`.

### 5. Install
The same APK goes on every device.

- **Phone:** open it and install. Accept the notifications permission when asked.
- **TV:** with adb: `adb connect TV_IP:5555` and `adb install -r app-debug.apk` (or use the Downloader app).

### 6. TV permission (once)
So that the message appears on top of what is being watched:

```
adb shell appops set com.serchu.familiatv SYSTEM_ALERT_WINDOW allow
```

Open the app on the TV, choose a language and try the **Test message**, **With reply** and **Urgent** buttons. From then on it starts by itself every time the TV turns on.

Optional, so Android doesn't put it to sleep:

```
adb shell dumpsys deviceidle whitelist +com.serchu.familiatv
```

### 7. Add the family
Everyone installs **the same APK** on their phone, taps their photo or name at the top and picks a color. Nothing needs to change on the server.

### Updating
Push the changes to `main` (`git add -A`, `git commit`, `git push`). Railway updates by itself and GitHub Actions builds the new APK; it installs on top of the old one (also on the TV) without uninstalling. Devices with the old app keep working with the new server.

---

## Server variables

All are optional except `TOKEN`.

| Variable | What it is for | Default |
|---|---|---|
| `TOKEN` | Access key shared with the apps (**required**) | — |
| `PORT` | Server port | `3000` (Railway sets it) |
| `MSG_TTL_MIN` | Minutes a message waits if the TV is off | `5` |
| `DATA_DIR` | Folder where license, TVs, payments and notices are stored | `relay/data` |
| `PARENT_PIN` | Parent PIN required to lock the TV | no PIN |
| `TZ_OFFSET_MIN` | Time zone for "until 7:00", in minutes from UTC | `-180` |
| `PAY_WALLET` | Collection wallet | the project's wallet |
| `SOLANA_RPC_URL` | Solana RPC; accepts several, comma-separated | public mainnet RPC |
| `USDC_MINT` | USDC address | mainnet USDC |
| `PRICE_USD` | Plan price | `5` |
| `PRICE_EXTRA_USD` | Price of +3 TVs | `8` |
| `TV_BASE` / `TV_PACK` | TVs included in the plan / TVs per pack | `3` / `3` |
| `LICENSE_DAYS` | License days per payment | `30` |
| `TRIAL_DAYS` | Trial days | `7` |
| `REMIND_DAYS` | Days before expiry to send a reminder | `3` |
| `REQUIRE_LICENSE` | `1` = the license is mandatory | off |
| `PAY_COMMITMENT` | `confirmed` or `finalized` | `confirmed` |
| `PAY_SDK` | `0` turns off the official Solana Pay SDK | on |
| `PAY_MATCH_BY_AMOUNT` | `0` turns off recognition by exact amount | on |
| `SOL_USD_FIXED` | Fixed SOL price (testing) | market price |
| `RESEND_API_KEY` | Resend key for purchase emails | no emails |
| `OWNER_EMAIL` | Who receives the emails (if missing, nothing is sent) | — |
| `OWNER_LANG` | Email language: `es`, `en` or `pt` | `es` |
| `MAIL_FROM` | Sender. With `onboarding@resend.dev` Resend only delivers to the account's own address; for others you must verify a domain | `Familia TV <onboarding@resend.dev>` |
| `MAIL_TEST` | `1` sends a test email on startup (remove it afterwards) | off |
| `RESET_STATE` | `1` wipes TVs, payments, license and notices on startup (remove it afterwards) | off |

## Relay API

All routes (except `/health`) require `Authorization: Bearer <TOKEN>`.

| Route | What it does |
|---|---|
| `GET /health` | Returns `ok`. No key needed. |
| `GET /status` | State: connected TVs and their queue, lock, plan, capacity, notices. |
| `POST /send` | Sends a message. Fields: `text` (up to 500), `from`, `uid` (phone identifier), `avatar` (base64, up to ~30 KB), `color` (`azul`, `rosa`, `verde`, `amarillo`), `duration` (`normal` / `larga`), `reply` (ask for a reply), `urgent` (urgent alert; needs `uid`, one per minute), `tv` (TV id; without it, goes to all). |
| `POST /control` | Locks or unlocks the TV: `action` (`freeze` / `unfreeze`), `minutes` (up to 720) or `mode: "until"` with `hour` and `minute`, `from`, `tv` and `pin` (if `PARENT_PIN` is set). |
| `POST /pay/create` | Creates a payment request (`metodo`: `usdc` or `sol`; `producto`: `plan` or `extra`) and returns the QR. |
| `GET /pay/status?id=…` | State of a payment and of the plan. |
| `POST /tv/rename`, `POST /tv/forget` | Renames or removes a TV (only while it is off). |
| `WS /ws?role=tv&id=…&name=…` | The TV receives messages and sends `ack` and `reply`. |
| `WS /ws?role=phone&uid=…` | The phone receives the TV's replies (`ok`, `voy`, `ahora_no`, `cinco_min`, `visto`, `sin_respuesta`). |

Useful error codes: `401` wrong key, `402` license expired, `403` PIN required or wrong, `429` too many wrong PINs or urgent alert too soon (`espera_s` says how long to wait).

---

## Tools we used

**The app and the server**

| Tool | What for |
|---|---|
| **Kotlin + Android SDK** (minSdk 24, targetSdk 34) | The app. Screens built from views in code (no Compose, no layout XML), custom animations and vector drawables for the logo. |
| **OkHttp 4.12** | HTTPS and WebSocket from the app. |
| **ZXing 3.5** | Draws the payment QR. |
| **AndroidX Core + Android BiometricPrompt** | Compatibility, and asking for fingerprint, face, pattern or phone PIN before locking the TV. |
| **Node.js 20 + `ws`** | The relay (HTTP and WebSocket), no framework. |
| **`@solana/pay`, `@solana/web3.js`, `bignumber.js`** | Official Solana Pay SDK: build the QR link, find and validate the payment. |
| **Solana (devnet) + devnet USDC** | Test network to charge without real money. |
| **Resend** | Email to the owner with each purchase. |
| **Coinbase / Kraken (public API)** | SOL price for the amount in SOL. |
| **Railway** | Hosting for the relay, variables and Volume; automatic deploys from GitHub. |
| **GitHub + GitHub Actions** | Private repo and APK builds (JDK 17 Temurin, Gradle 8.7). |
| **adb** | Install the APK on the TV and grant the "display over other apps" permission. |

**What we built it with**

| Tool | What for |
|---|---|
| **Claude (Claude Code and the Claude app)** | Wrote and reviewed the app and server code, designed the logo, made the sketches, produced the video and wrote this documentation. Worked with the repo through `git` and `gh`. |
| **Railway MCP** | From Claude: check deployments, variables and service logs. |
| **Claude Design (sketch canvas)** | Animated sketches of the notification (Aura, lower third, urgent, voice and stickers) before coding it. |
| **Node + `ws` (local test)** | A relay smoke test with a fake TV and phone: urgent alert, one-per-minute limit and replies. |
| **Playwright (Chromium) + ffmpeg** | Frame-by-frame rendering of the demo video and final encoding. |
| **Python (numpy / scipy)** | Soft background music and sound effects for the video. |
| **Grok** | The English voice-over for the video. |

## How we built it, step by step

1. **v1.0, the core:** relay on Railway + a single app. The phone sends the message, the TV shows it over whatever is playing, with a sound. A 5-minute queue if the TV is off. APK built on GitHub Actions.
2. **v1.1, the family:** photo and name, several people with the same APK, message duration and reply buttons (OK / I'm coming / Not now) with a notice back to the phone.
3. **v1.2, colors and sound:** a color per person and a configurable, softer alert sound.
4. **v1.3, TV control:** lock with countdown (15 min, 1 h, until 7:00), fingerprint or phone PIN, optional `PARENT_PIN` and a full redesign (gradient background, glass cards, clock on the TV home screen). The rest screen went through several characters before ending up with the security guard.
5. **Solana Pay payments:** QR on the TV, license with a 7-day trial, verification over JSON-RPC and then with the **official SDK**. USDC or SOL payments on devnet.
6. **Several TVs:** 3 on the plan and +3 for 8 USD; destination selector on the phone, per-TV queue and lock.
7. **More robust payments:** receipt on the TV and the phone, a reminder 3 days before expiry, several RPCs, retries on the 429 error, late payments (up to 24 h) and wallets that don't send the reference (recognition by exact amount).
8. **Email to the owner** with each purchase (Resend) and `RESET_STATE` to start from zero.
9. **Three languages:** Spanish, English and Portuguese, with a picker on first open.
10. **Privacy:** no message is kept longer than 5 minutes, and [`PRIVACY.md`](PRIVACY.md) was written.
11. **Hey Family:** the new name (starts in English), its own **logo** (house + TV + message with a heart), adaptive icon, Android TV banner and the "Built on Solana · Solana Pay" footer with the official Solana logo.
12. **New notification (v1.4):** first, four animated sketches were tried (Aura, lower third, urgent, voice and stickers), with a real photo of the TV in the loop. We kept the **Aura look**, centered with a veil and color glow, the **urgent alert** with a full red screen and the **"seen on the TV"** receipt back to the phone. The test photo showed that the card's halos were as tall as the whole screen; that was fixed and the card was centered.
13. **Urgent alert polished:** no buttons or caption, fixed text ("Call me, urgent") and closing with the remote (Back or OK) as the sign that they've seen it.

**How it was tested:** relay smoke tests (urgent alert, one-per-minute limit, replies to the phone), a build on GitHub Actions for every change, test payments on devnet and checks on a real TV (with the test buttons on the home screen).

## Demo video

The presentation video (≈ 78 s, 1080p) is made with open tools, no video editor:

1. **Script** and **English voice-over**, generated with Grok.
2. **HTML animation:** a single page draws every scene (phone, relay, TV, QR payment, guard, closing on an LED TV) and can be asked for an exact instant with `render(t)`.
3. **Frames with Playwright:** Chromium takes one screenshot per 1/30 of a second, with no dropped frames.
4. **Encoding with ffmpeg:** H.264 at 30 fps in four segments that are joined without re-encoding.
5. **Sound:** normalized voice, soft music generated with Python (it ducks itself when the voice speaks), alert and click effects, and burned-in captions.

## Repository layout

```
.
├── README.md                           (English)   ·  README.es.md (Español)
├── PRIVACY.md                          (English)   ·  PRIVACIDAD.md (Español)
├── .github/workflows/build-apk.yml     Builds the APK (and leaves it on apk-* branches from version-* branches)
├── relay/
│   ├── server.js                       HTTP + WebSocket: messages, replies, locks, TVs, notices
│   ├── pay.js                          License, payment requests, verification on Solana
│   ├── solanapay.js                    Adapter for the official @solana/pay SDK (0.2.x and 1.x)
│   ├── mail.js                         Email to the owner (Resend)
│   └── data/                           Sample tvs.json and licencia.json (on Railway the Volume is used)
└── android/app/src/main/
    ├── java/com/serchu/familiatv/
    │   ├── MainActivity.kt             Phone and TV screens, urgent button
    │   ├── Overlay.kt                  Notifications over other apps (normal, with reply, urgent)
    │   ├── RelayService.kt / RelayLink.kt   The TV's connection to the relay
    │   ├── PhoneService.kt             The phone's connection to receive replies
    │   ├── Freeze.kt / Guard.kt        TV lock and the animated guard
    │   ├── Sounds.kt                   Alert sounds
    │   ├── I18n.kt / Strings.kt        Languages (en, es, pt)
    │   ├── Support.kt / Cfg.kt / App.kt / BootReceiver.kt
    └── res/                            Logo, adaptive icon, TV banner and Solana logo
```

## Changing things
- **Quick phrases on the phone:** the `quickPhrases` list in `MainActivity.kt`.
- **How long a message stays on screen:** in `Overlay.kt`, the `stayMillis()` function.
- **How often an urgent alert's sound repeats:** in `Overlay.kt` (10 s); the one-per-minute limit is `URGENT_GAP_MS` in `relay/server.js`.
- **Texts and translations:** `Strings.kt` (Spanish is the source text; English and Portuguese are in the map).
- **The URL or the key changed:** update the secrets and run the workflow again. The new APK installs on top of the old one.

## Limits and things to know
- The relay's queue lives in memory: if Railway restarts the service, whatever was waiting is lost. Anything already shown is unaffected.
- Messages are limited to 500 characters and nothing is kept longer than 5 minutes (`MSG_TTL_MIN`).
- Anyone who has the APK has the access key. Don't share it outside the family. This is a prototype meant for a single family.
- Messages are not end-to-end encrypted: the server sees them while forwarding, although it doesn't store them.
- There is no automatic monthly charge with Solana Pay.
- The urgent alert is an internal family alert, not an emergency service.
- Ideas left for later: reading the message aloud, a ringtone per person, reply stickers, moving to mainnet and publishing on Google Play.
