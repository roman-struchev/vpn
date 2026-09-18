# Android client (Phase 7 MVP)

Native Java + Material 3 client. Standalone Gradle project (like `agent/` and
`web/` are standalone npm projects) — not part of the root multi-project build,
since the Android Gradle Plugin needs its own plugin/repository setup.

## One-time setup

```bash
cd android
./scripts/fetch-libxray.sh   # downloads XTLS/libXray's prebuilt Android .aar (~95MB, not in git)
```

Requires JDK 17+ — the installed Gradle wrapper (9.1.0) and AGP (9.0.1) both
run fine under a JDK 25 daemon, no `JAVA_HOME` override needed.

## Build & test

```bash
./gradlew :app:testDebugUnitTest   # pure-JVM conformance tests (state machine,
                                    # backoff policy, censorship verdict, vless
                                    # parsing, xray config factory) — no device needed
./gradlew :app:lintDebug
./gradlew :app:assembleDebug       # full APK, links against the real libXray .so
```

## Release signing

Every published APK must be signed with the project's **one** release key.
Android refuses to install an update signed by a different key — it fails with
"App not installed as package conflicts with an existing package", and the only
way out on that device is uninstalling the app (losing the saved login and all
local settings). Releases v0.1.7…v0.1.12 each shipped with a *different*
certificate because the release build fell back to the SDK's auto-generated
debug keystore, which CI regenerates on every run.

The keystore is never committed. `android/app/build.gradle` picks it up from
`-PreleaseKeystore=...`/`-PreleaseKeystorePassword=...`/`-PreleaseKeyAlias=...`/
`-PreleaseKeyPassword=...` or the equivalent `ANDROID_KEYSTORE_FILE`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
environment variables:

```bash
ANDROID_KEYSTORE_FILE=~/.android/vpn-release.jks \
ANDROID_KEYSTORE_PASSWORD=... ANDROID_KEY_ALIAS=vpn \
./gradlew :app:assembleRelease -PversionName=0.1.13 -PversionCode=113

./scripts/verify-apk-signature.sh app/build/outputs/apk/release/app-release.apk
```

With nothing configured, `assembleRelease` still produces a debug-signed APK
for local `adb install` use, but it logs a warning and
`verify-apk-signature.sh` rejects it — that script pins the release
certificate's SHA-256 and runs in `.github/workflows/release.yml` before the
APK is attached to a release, so a signing regression fails the build instead
of reaching a phone.

CI reads the keystore from the repo secrets `ANDROID_KEYSTORE_BASE64` (the
`.jks` file, base64-encoded), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`
and `ANDROID_KEY_PASSWORD`; the release job hard-fails if the first is missing.
**Back the keystore file and its password up somewhere durable** — losing them
means minting a new key, which forces every existing install to be uninstalled
by hand.

## What this talks to

Server endpoints consumed (see `server/src/main/java/com/vpn/server/controller`):
`POST /api/v1/auth/{register,login}`, `POST /api/v1/auth/google` (Google Sign-In),
`POST /api/v1/auth/device` (1-click anonymous trial), `POST /api/v1/auth/web-handoff` (SSO to web billing),
`GET /api/v1/user/profile`, `GET|POST|DELETE /api/v1/user/devices`, `GET /api/v1/user/regions` (regions & load score),
`GET /api/v1/user/subscription/links` (VLESS URIs for active subscriptions, including trials),
`GET /api/v1/client/config` (transport policy: fingerprint, backoff timing, node list),
`POST /api/v1/client/telemetry` (failure and connect time telemetry).

`BuildConfig.API_BASE_URL` defaults to `http://217.216.79.46:8080/` (temporarily
pointed at the test server instead of the `vpn.struchev.site` production domain);
override per environment with `-PapiBaseUrl=https://...`. Phase 10: comma-separated
backup domains, tried in order on a network-level (not HTTP-error) failure —
`-PapiBaseUrlsBackup=https://api-backup1.example/,https://api-backup2.example/`
(see `api/ApiHostRotation.java`).

## Architecture notes

- `vpn/state/ConnectionStateMachine` — pure Java, the
  Disconnected/Connecting/Connected/Reconnecting/Error/OperatorBlocked states
  from docs/ARCHITECTURE.md §8.1.
- `vpn/ReconnectBackoffPolicy` — Smart Reconnect Backoff invariants from
  docs/ARCHITECTURE.md §2 and §4 (15-20s first pause, node switch only after
  2-3 consecutive failures, fingerprint fixed for the session).
- `vpn/CensorshipVerdict` + `CensorshipProbeService` — the honest
  "ограничение оператора" screen: probes a whitelisted RU host and a foreign
  host outside the tunnel.
- `vpn/xray/XrayConfigFactory` — builds the Xray-core JSON (tun inbound +
  VLESS/XHTTP/Reality outbound + DoH `dns` block). TUN wiring follows
  Xray-core's own Android contract: the VpnService TUN fd is written into the
  config's root `env.xray.tun.fd`, not passed as a separate libXray call
  (`SetTunFd` was removed upstream — see XTLS/Xray-core
  `proxy/tun/README.md`, "ANDROID SUPPORT").
- `vpn/xray/XrayInvoker` — wraps libXray's single `invoke(String)` JSON
  entrypoint (API version 3); the strongly-typed gomobile request/response
  classes in the .aar can't carry `method`/`payload`, so the raw envelope is
  the only usable surface.
- `vpn/XrayVpnService` — owns the TUN fd, the Xray lifecycle, and implements
  libXray's `DialerController` (`VpnService.protect()`) so every Go-initiated
  socket bypasses the tunnel.
- `api/ApiHostRotation` (Phase 10) — rotates through backup API domains on a
  network-level failure ("Пул резервных доменов для API сервера"); DoH for
  the API client itself was already in place from Phase 7 (`api/DohDns`).
- `vpn/TransportFallbackPolicy` (Phase 9) — once every node has been tried on
  the starting transport without success, switches to the gRPC+Reality
  fallback inbound the server advertises per node
  (`RoutingConfigResponse.NodeInfo.grpcFallbackPort`), same Reality keys and
  client UUID, before falling through to the honest operator-blocked screen.
  The starting transport itself honors the server's
  `transport_policy.primaryTransport` (`RoutingConfigResponse.primaryTransport`,
  region/operator/global scoped) — a `GRPC` override only takes effect if at
  least one node actually advertises a gRPC fallback port, otherwise it stays
  on XHTTP defensively.

## Known gaps / not yet done

- No instrumented (on-device) tests — only the pure-JVM conformance suite.
  The health-check/backoff loop, TUN establishment and DoH resolution are
  exercised by code review and the unit tests around their pure-logic pieces,
  not by running the app; this hasn't been verified on a real device or
  emulator.
- Authentication options: Email/password, Google Sign-In (Credential Manager),
  and 1-click anonymous device trial (`POST /api/v1/auth/device`). Telegram login is only
  available on the Web / Telegram Mini App, but accounts can be linked.
- Telemetry reports (`POST /api/v1/client/telemetry`) carry a real `nodeId`,
  resolved by matching the currently-active `VlessUri` host against
  `RoutingConfigResponse.NodeInfo.publicIp`. Successful connects report `TUNNEL_UP`
  with measured `connectTimeMs`, while connection failures report `FAILURE`.
- Play Store listing (docs/stores-and-liability.md) is out of scope for this
  MVP pass; the APK is self-signed and side-loaded (see "Release signing").
  Note that `google_web_client_id` in `strings.xml` requires configuring your
  Web OAuth Client ID from Google Cloud Console.
