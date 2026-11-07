# Hey Family: the data the app handles

🌎 [Español](PRIVACIDAD.md) · **English**

Hey Family has no accounts and asks for no personal data. It stores the bare minimum to carry messages from the phone to the TV and to charge for the plan, and messages are never kept longer than 5 minutes.

## What is stored, where and for how long

| Data | What for | Where it lives | How long |
| --- | --- | --- | --- |
| Messages (text, sender name, color, optional photo) | Delivering them to the TV | Server memory, only while the TV is off. Never written to disk | At most 5 minutes; deleted once delivered |
| TV replies (OK, I'm coming, 5 minutes, Not now, "seen" when an urgent alert is viewed or closed) and the first words of the message replied to | Notifying the phone | Server memory if the phone is offline; the phone's inbox | At most 5 minutes |
| Time of each phone's last urgent alert | Allowing one per minute | Server memory | Until the service restarts |
| Phone identifier (random code, not the phone number or email) | Knowing which phone to send the reply back to | The phone; server memory while connected | Until the app is uninstalled |
| Profile name, photo and color | Showing them next to the message on the TV | The phone; they travel with each message | Until changed or the app is uninstalled |
| Linked TVs (random code and name) | Remembering which TVs exist and enforcing the plan limit | Server disk and the TV itself | Until deleted |
| TV lock (until when, reason, who requested it) | Keeping the lock if the TV restarts | The TV and server memory | While the lock lasts |
| Language, sound, destination TV, unlock time | Usage preferences | The phone or the TV | Until the app is uninstalled |
| License and payments (expiry, extra TVs, transaction signature, product, amount, currency, date) | Controlling the plan and not accepting the same payment twice | Server disk | The last 200 payments, until deleted |
| Pending payment requests (random reference, amount, QR link) | Detecting that the payment arrived | Server disk | Up to 24 hours; then discarded |
| System notices (payment received, plan about to expire) | Notifying the phone | Server disk and the phone's inbox | Last 20 on the server, 50 on the phone |
| Parent PIN (optional) | Protecting the TV lock | Server configuration; the app keeps it only in memory while open | Until the owner changes it |
| Server logs | Diagnostics | Railway | According to Railway's policy; they do not include message text |

## Messages: nothing lasts longer than 5 minutes

- The message travels from the phone to the server and from there to the TV over an encrypted connection (HTTPS); the app does not allow unencrypted traffic.
- If the TV is on, the server forwards it and removes it from its queue when the TV confirms receipt.
- If the TV is off, it stays in server memory for up to 5 minutes and is then discarded. It is never written to disk, and the queue cleans itself every 30 seconds. The time can be changed with the `MSG_TTL_MIN` variable (5 by default).
- An urgent alert is just another message: fixed text ("Call me, urgent"), the same 5-minute limit and the same encrypted connection.
- TV replies follow the same rule: 5 minutes on the server, and on the phone they disappear from the inbox and the notification is withdrawn by itself after 5 minutes.
- On the TV, the "last message" on the home screen is deleted after 5 minutes and lives only in memory.
- Server logs record who sends, how many characters the message has and which TV it goes to; they do not store the text.

## Payments with Solana Pay

The plan is charged in USDC or SOL to the owner's wallet. The app asks for no name, email or card, and the server does not store the payer's wallet.

- For each payment, the server stores the transaction signature, the product, the amount, the currency and the date. It is used to avoid accepting the same payment twice and to extend the license.
- The transaction signature is public: anyone can see it on the Solana blockchain, along with the payer's wallet, but that does not go through Hey Family.
- To detect the payment the server queries the Solana network with the official `@solana/pay` SDK, and queries the SOL price at Coinbase or Kraken (a public query with no user data).
- Email to the owner (optional): if configured, each purchase is sent to the owner with the plan, the amount, the TVs enabled, the expiry and a link to the transaction. Nothing is sent to users.
- Today the app charges on the test network (devnet), with no real money.

## Third-party services

| Service | What for | What it receives |
| --- | --- | --- |
| Railway | Hosts the server and its disk | Everything in the table above that is stored on the server; its own connection logs according to its policy |
| Solana network and its RPC node | Confirming payments | Public queries of addresses and transactions; the payment is visible on the blockchain |
| Coinbase and Kraken | SOL price | Nothing from the user: it is a public query made by the server |
| Resend (optional) | Email notice to the owner | The owner's email and the purchase details |
| GitHub | Stores the code and builds the APK | The code and the connection keys as build secrets |

The app includes no analytics, advertising or third-party messaging tools: it only uses a networking library (OkHttp) and one to draw the payment QR (ZXing).

## Android permissions

| Permission | What it is for |
| --- | --- |
| Internet and network state | Connecting to the server |
| Foreground service | Keeping the connection open to receive messages |
| Notifications | Notices about replies, payments and expiry on the phone |
| Start on boot | Reconnecting by itself when the TV or phone turns on |
| Display over other apps | Drawing the message on top of what is being watched on the TV |
| Keep awake (wake lock) | Keeping the connection from dropping with the screen off |
| Biometrics | Asking for fingerprint, face, pattern or the phone PIN before locking the TV; Android validates it and the app has no access to that data |

Android's automatic backup is turned off: the app's data is not uploaded to Google's cloud.

## What the app does not collect

- There are no accounts or sign-up: no email, phone number or password is asked for.
- It does not access location, contacts, camera, microphone, files or the TV's history.
- It has no analytics, advertising or tracking.
- The only image is the profile photo the person chooses, reduced to under 30 KB.

## Deletion and points to keep in mind

Uninstalling the app deletes everything the phone or TV stores. On the server, the owner can delete TVs, payments, license and notices at any time.

- The 5-minute limit applies to messages and replies. Payment and expiry notices are kept (up to 20 on the server and 50 on the phone) because they serve as a receipt.
- Messages are not end-to-end encrypted: the server sees them while forwarding, although it does not store them.
- All installs share a single access key to the server: there are no per-person accounts. It is a prototype meant for a single family.
- Railway's connection logs are governed by Railway's policy, not by the app's.
