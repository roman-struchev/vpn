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

`src/main/api/apiClient.ts` defaults to the placeholder
`https://api.nextgenvpn.app/`. Point it at a real backend with the
`VPN_API_BASE_URL` environment variable:

```bash
VPN_API_BASE_URL=https://your-server.example.com/ npm run dev
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
`POST /api/v1/auth/{register,login}`, `GET /api/v1/user/profile`,
`GET|POST|DELETE /api/v1/user/devices`, `GET /api/v1/user/subscription/links`,
`GET /api/v1/client/config`, `POST /api/v1/client/telemetry` (best-effort).

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
  been tried on XHTTP without success, switches to the gRPC+Reality fallback
  inbound the server advertises per node
  (`RoutingConfigResponse.nodes[].grpcFallbackPort`), same Reality keys and
  client UUID, before falling through to the honest operator-blocked screen.
  Always starts on XHTTP — doesn't yet honor a server-side
  `primaryTransport=GRPC` override (same simplification on the Android client).
- `src/main/vpn/vpnController.ts` — orchestrates all of the above, mirrors
  `android/.../vpn/XrayVpnService.java` one-for-one in responsibility.
- `src/preload/index.ts` exposes a typed `window.vpnApi` via
  `contextBridge`; the renderer (`src/renderer/src/`) never touches Node or
  Electron APIs directly (`contextIsolation: true`, `nodeIntegration: false`).

## Known gaps / not yet done

- No TUN mode (out of scope for this MVP per docs/PLAN.md §5 — a later,
  separate task, and it reintroduces the code-signing requirement this
  scheme was chosen to avoid).
- No custom app icon yet (`build.directories.buildResources` = `build/`, but
  it's empty — electron-builder falls back to its default Electron icon).
- The desktop API client doesn't use DNS-over-HTTPS for its own requests
  (unlike the Xray tunnel's own `dns` block, which does) — Node's `fetch`
  doesn't support a custom DoH resolver without a fair amount of extra
  plumbing (a hand-rolled RFC 8484 client over `undici`'s `Agent.connect.lookup`).
  Not done for this MVP pass.
- Linux system-proxy support only covers GNOME (`gsettings`); KDE and others
  need manual proxy configuration pointed at `127.0.0.1:10809` (HTTP) /
  `127.0.0.1:10808` (SOCKS5).
- No instrumented UI tests — verified by `npm run typecheck` + `npm test` +
  manually launching both the dev build and an unsigned packaged
  `electron-builder --mac --dir` build on this machine (window opens,
  renders, IPC round-trips to a real — if unreachable — API host).
- Auto-update is wired but unverified against a real GitHub Releases feed
  (no release has been published yet).
- Telemetry reports (`submitTelemetry`) are sent with `nodeId=null`: same gap
  as the Android client — see android/README.md.
