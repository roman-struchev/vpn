# Desktop client (Phase 8 MVP)

Electron + React + TypeScript, system-proxy MVP (docs/PLAN.md §5): xray-core
runs as a local child process exposing SOCKS5/HTTP on `127.0.0.1`, and the OS
proxy settings are pointed at it. No TUN driver, no admin rights, no code
signing — so `electron-updater` auto-update keeps working unsigned, same
scheme as the reference `aurapad` project (`electron-builder` +
`electron-updater`, `publish: provider: github`, `mac.notarize: false`, NSIS
on Windows).

Standalone npm project (like `agent/` and `web/`) — not part of the root
Gradle build.

## One-time setup

```bash
cd desktop
npm install
npm run fetch:xray   # downloads the XTLS/Xray-core binary for your OS/arch
                      # into resources/bin/<os>-<arch>/ (not committed to git)
```

`fetch:xray` defaults to your current platform. To pre-fetch every target
before a release build: `npm run fetch:xray -- all`. Pin a specific
Xray-core release with `XRAY_CORE_VERSION=v26.x.y npm run fetch:xray`.

## Configuring the API endpoint

`src/main/api/apiClient.ts` defaults to the production server
`https://vpn.struchev.site/` (dev builds default to `http://localhost:8080/`
instead — see `hostsFromEnv()`). Point it at a different backend with the
`VPN_API_BASE_URL` environment variable, and (Phase 10) optionally list
backup domains tried in order on a network-level failure with
`VPN_API_BASE_URLS_BACKUP` (comma-separated):

```bash
VPN_API_BASE_URL=https://your-server.example.com/ \
VPN_API_BASE_URLS_BACKUP=https://backup1.example.com/,https://backup2.example.com/ \
npm run dev
```

For a packaged release build, set it at build time the same way (or edit
the default in `apiClient.ts` before `npm run build`) — there's no runtime
settings screen for this yet.

## Develop

```bash
npm run dev          # electron-vite dev server with HMR for the renderer
npm run typecheck    # tsc --noEmit for both the main and renderer programs
npm test             # vitest — pure-logic unit tests, no Electron needed
```

## Package

```bash
npm run pack                    # unpacked app in dist/ for the current OS — fastest smoke test
npx electron-builder --mac      # signed-if-available dmg (falls back to unsigned + a
npx electron-builder --win      # electron-builder warning when no cert is configured)
npx electron-builder --linux
```

`npm run release` runs `electron-builder --publish always`, which uploads
the built installers and the `latest.yml`/`latest-mac.yml` update manifests
to GitHub Releases (`build.publish.provider: "github"` in `package.json`).
Requires a `GH_TOKEN` with repo access in the environment — see
[electron-builder's publishing docs](https://www.electron.build/publish).

## What this talks to

Same server contract as `web/src/api.ts` and the Android client:
`POST /api/v1/auth/{register,login}`, `POST /api/v1/auth/google` (loopback OAuth),
`POST /api/v1/auth/device` (1-click anonymous trial), `POST /api/v1/auth/web-handoff` (SSO to web billing),
`GET /api/v1/user/profile`, `GET|POST|DELETE /api/v1/user/devices`, `GET /api/v1/user/regions` (regions & load score),
`GET /api/v1/user/subscription/links`, `GET /api/v1/client/config`, `POST /api/v1/client/telemetry`.

## Architecture notes

- `src/shared/` — framework-free TypeScript, covered by the vitest suite in
  `test/`: `connectionState.ts` (Disconnected/Connecting/Connected/
  Reconnecting/Error/OperatorBlocked — same contract as the Android state
  machine), `reconnectBackoffPolicy.ts` (15-20s first pause, node switch
  only after 2-3 consecutive failures, session-fixed fingerprint),
  `censorshipVerdict.ts` (honest operator-restriction screen decision),
  `vlessUri.ts`, `xrayConfigFactory.ts` (SOCKS5+HTTP inbound instead of TUN —
  the only structural difference from the Android/agent Xray configs).
- `src/main/xray/binaryManager.ts` + `xrayProcess.ts` — locate and supervise
  the local `xray run -c <config>` child process; `XRAY_LOCATION_ASSET` is
  set so it finds `geoip.dat`/`geosite.dat` next to the binary.
- `src/main/proxy/systemProxy.ts` — per-OS system proxy toggle: macOS via
  `networksetup`, Windows via the `Internet Settings` registry key + a
  `rundll32` refresh call, Linux via GNOME's `gsettings` (other desktop
  environments: the local proxy still runs, just isn't auto-applied).
- `src/shared/transportFallbackPolicy.ts` (Phase 9) — once every node has
  been tried on the starting transport without success, switches to the
  gRPC+Reality fallback inbound the server advertises per node
  (`RoutingConfigResponse.nodes[].grpcFallbackPort`), same Reality keys and
  client UUID, before falling through to the honest operator-blocked screen.
  The starting transport itself honors the server's
  `transport_policy.primaryTransport` (`RoutingConfigResponse.primaryTransport`)
  — a `GRPC` override only takes effect if at least one node actually
  advertises a gRPC fallback port, otherwise it stays on XHTTP defensively.
- `src/main/vpn/vpnController.ts` — orchestrates all of the above, mirrors
  `android/.../vpn/XrayVpnService.java` one-for-one in responsibility.
- `src/shared/apiHostRotation.ts` + `src/main/api/dohDispatcher.ts` (Phase
  10) — backup API domains tried in order on a network-level failure, and a
  global `undici` dispatcher that resolves hostnames over DoH (Cloudflare's
  JSON API, not the binary RFC 8484 wire format — no DNS-packet parsing
  needed) instead of the OS/ISP resolver, falling back to the system
  resolver if the DoH query itself fails.
- `src/preload/index.ts` exposes a typed `window.vpnApi` via
  `contextBridge`; the renderer (`src/renderer/src/`) never touches Node or
  Electron APIs directly (`contextIsolation: true`, `nodeIntegration: false`).

## Known gaps / not yet done

- No TUN mode (out of scope for this MVP per docs/PLAN.md §5 — a later,
  separate task, and it reintroduces the code-signing requirement this
  scheme was chosen to avoid).
- System tray icon and menu are implemented (`src/main/tray.ts`, with state icons in
  `resources/tray`), but main window app icon still uses electron default in package config.
- Linux system-proxy support only covers GNOME (`gsettings`); KDE and others
  need manual proxy configuration pointed at `127.0.0.1:10809` (HTTP) /
  `127.0.0.1:10808` (SOCKS5).
- Authentication supports Email/password, Google Sign-In (loopback OAuth on `http://127.0.0.1:*`),
  and 1-click anonymous device trial (`POST /api/v1/auth/device`).
- Auto-update is wired but unverified against a real GitHub Releases feed
  (no release has been published yet).
- Telemetry reports (`submitTelemetry`) carry a real `nodeId`, resolved by
  matching the currently-active node's host against
  `RoutingConfigResponse.nodes[].publicIp`. Successful connects report `TUNNEL_UP`
  with measured `connectTimeMs`, while connection failures report `FAILURE`.
