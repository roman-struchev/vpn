# Android client (Phase 7 MVP)

Native Java + Material 3 client. Standalone Gradle project (like `agent/` and
`web/` are standalone npm projects) — not part of the root multi-project build,
since the Android Gradle Plugin needs its own plugin/repository setup.

## One-time setup

```bash
cd android
./scripts/fetch-libxray.sh   # downloads XTLS/libXray's prebuilt Android .aar (~95MB, not in git)
```

Requires a JDK the installed Gradle wrapper (8.10.2) can run on — JDK 17-21.
If your default `java` is newer (e.g. a Java 25+ toolchain), point `JAVA_HOME`
at a supported JDK for these commands:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build
```

## Build & test

```bash
./gradlew :app:testDebugUnitTest   # pure-JVM conformance tests (state machine,
                                    # backoff policy, censorship verdict, vless
                                    # parsing, xray config factory) — no device needed
./gradlew :app:lintDebug
./gradlew :app:assembleDebug       # full APK, links against the real libXray .so
```

## What this talks to

Server endpoints consumed (see `server/src/main/java/com/vpn/server/controller`):
`POST /api/v1/auth/{register,login}`, `GET /api/v1/user/profile`,
`GET|POST|DELETE /api/v1/user/devices`, `GET /api/v1/user/subscription/links`
(VLESS URIs, paid plans only — see `SubscriptionExportService`),
`GET /api/v1/client/config` (transport policy: fingerprint, backoff timing,
node list), `POST /api/v1/client/telemetry` (best-effort).

`BuildConfig.API_BASE_URL` is a placeholder (`https://api.nextgenvpn.app/`);
override per environment with `-PapiBaseUrl=https://...`.

## Architecture notes

- `vpn/state/ConnectionStateMachine` — pure Java, the
  Disconnected/Connecting/Connected/Reconnecting/Error/OperatorBlocked states
  from docs/PLAN.md §9.
- `vpn/ReconnectBackoffPolicy` — Smart Reconnect Backoff invariants from
  docs/ROADMAP_PROGRESS.md §1.5 (15-20s first pause, node switch only after
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
- `vpn/TransportFallbackPolicy` (Phase 9) — once every node has been tried on
  XHTTP without success, switches to the gRPC+Reality fallback inbound the
  server advertises per node (`RoutingConfigResponse.NodeInfo.grpcFallbackPort`),
  same Reality keys and client UUID, before falling through to the honest
  operator-blocked screen. Always starts on XHTTP — doesn't yet honor a
  server-side `primaryTransport=GRPC` override (see desktop/README.md's
  matching note; same simplification on both clients).

## Known gaps / not yet done

- No instrumented (on-device) tests — only the pure-JVM conformance suite.
  The health-check/backoff loop, TUN establishment and DoH resolution are
  exercised by code review and the unit tests around their pure-logic pieces,
  not by running the app; this hasn't been verified on a real device or
  emulator.
- No Telegram-native login (the Mini App's `initData` HMAC flow is
  Telegram-WebView-specific); the app uses the same email/password
  `POST /api/v1/auth/{register,login}` the web client uses.
- Telemetry reports (`POST /api/v1/client/telemetry`) are sent with `nodeId=null`:
  subscription links don't carry the server-side node id today, only
  `/api/v1/client/config` does. Feeds the admin degradation dashboard, not
  yet `DynamicRoutingService`'s auto-quarantine (which keys off `nodeId`).
- Release signing / Play Store listing (docs/stores-and-liability.md) is out
  of scope for this MVP pass.
