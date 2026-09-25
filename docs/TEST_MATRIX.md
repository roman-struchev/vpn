# Test matrix

What combinations the product has to work in, which test covers each, and
what is still open. When a bug turns out to be one cell of a matrix, add the
row or column here and the test that covers it — the cell next to it is
usually broken the same way.

## 1. Account state × surface

States are set by `e2e/tests/stateMatrix.spec.ts` (real API where cheap, SQL
where it takes time). Each web cell is checked in ru/en × desktop/phone width
for: no page error, no `undefined`/`NaN`/`Invalid Date`, no year ≥ 2100, no US
date on the Russian page, no sideways scroll. Screenshots and the profile JSON
land in `e2e/test-results/state-matrix/`.

| State                         | email | guest (device) | Telegram-only | Web dashboard | Export (v2rayTun/Happ) | Desktop/Android parsing |
|-------------------------------|:-----:|:--------------:|:-------------:|:-------------:|:----------------------:|:-----------------------:|
| no plan                       |   ✓   |       —        |       ✓       | stateMatrix   | stateMatrix            | unit fixtures           |
| trial                         |   ✓   |       ✓        |               | stateMatrix   | stateMatrix, happExport| unit fixtures           |
| trial used up                 |   ✓   |       ✓        |               | stateMatrix   | stateMatrix            | unit fixtures           |
| paid                          |   ✓   |       ✓        |       ✓       | stateMatrix   | stateMatrix, happExport| unit fixtures           |
| paid, 90%+ traffic            |   ✓   |                |               | stateMatrix, accountAndPlanStates | stateMatrix |             |
| paid, traffic used up         |   ✓   |                |       ✓       | stateMatrix   | stateMatrix            | unit fixtures           |
| paid, expired                 |   ✓   |                |               | stateMatrix   | stateMatrix            | unit fixtures           |
| paid, renewal short of balance|   ✓   |                |               | stateMatrix   | stateMatrix            | unit fixtures           |
| Pro, downgrade to Basic queued|   ✓   |                |               | stateMatrix (names the new plan) | stateMatrix |          |
| admin temporary tariff        |   ✓   |                |               | stateMatrix (real admin API) | stateMatrix | UserControllerTest |
| session unusable (garbage, tampered, account deleted) | ✓ |       |               | stateMatrix   | —                      | apiClientSession tests  |

Open: desktop/Android render these states only from hand-written fixtures,
not from the real server's JSON.

## 2. Entry point × arrival mode

`e2e/tests/webEntryPoints.spec.ts`. Where each link comes from:
desktop `openWebHandoff` / `openExternal`, Android `openUrl` /
`WebHandoffLauncher` / P2P settings, server `TrafficNotifier` (Telegram) and
the referral link.

| Destination   | signed out | signed in | handoff | dead handoff code | in-page hash change |
|---------------|:----------:|:---------:|:-------:|:-----------------:|:-------------------:|
| `#privacy`    |     ✓      |     ✓     |    ✓    |         ✓         |          ✓          |
| `#terms`      |     ✓      |     ✓     |    ✓    |         ✓         |          ✓          |
| `#p2p-terms`  |     ✓      |     ✓     |    ✓    |         ✓         |          ✓          |
| `#tariffs`    |     ✓      |     ✓     |    ✓    |         ✓         |          ✓          |
| `#admin`      | —          | ✓ (+ non-admin refused) | ✓ |       —         |                     |
| `?ref=CODE`   |     ✓ (also with a hash) |  |       |                   |                     |

## 3. Third-party clients (subscription link)

`e2e/tests/happExport.spec.ts`: the public export fetched with Happ's
User-Agent, headers checked against Happ's documentation
(happ.su/main/dev-docs/app-management), links read by the generic share-link
rules, then dialled with **Happ's own xray-core** through a real node over the
primary XHTTP+REALITY transport. Needs `XRAY_BIN_PATH` (node xray ≥ 26.x) and
Happ installed (`HAPP_XRAY_PATH`); skips otherwise.

| Client     | Headers | Link parses | Real connection | Survives a user on many networks |
|------------|:-------:|:-----------:|:---------------:|:--------------------------------:|
| Happ       |    ✓    |      ✓      | ✓ (trial, paid) |                ✓                 |
| v2rayTun   |         |             |                 |                                  |
| Hiddify    |         |             |                 |                                  |

Open: the Happ GUI itself (import via `happ://add/…`) is not driven, and
v2rayTun/Hiddify are only covered by sharing the same link format.

## 4. Existing coverage not repeated here

API access control and malformed input (`accessControl.spec.ts`), payments
(`cryptoDeposit`, `promoCode`, `telegramStars`), plan changes (`planSwitch`,
`tariffUpgrade`), P2P relay (`p2pRelay.spec.ts`), real tunnel over gRPC
(`tunnel.spec.ts`), Android/desktop real-node tests (see
`android/scripts/real-tunnel-e2e.sh`, `desktop/test/realTunnel.integration.test.ts`).
