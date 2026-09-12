# Client → Web SSO Handoff: Research & Proposal

Motivated by `UX_REVIEW.md` §7/§B ("Desktop and Android have no purchase/top-up
capability at all — a 'no active subscription' state is a dead end on both
real VPN clients, with no link back to the web dashboard's billing page").
This document (a) confirms that finding precisely, (b) proposes a concrete,
implementable mechanism for a seamless client→browser→web-dashboard handoff
that reuses this codebase's existing JWT/OAuth building blocks, and (c) lays
out a per-module implementation checklist.

All file/line references are against this worktree's `HEAD` (`0bd8297`,
tip of `main` at review time).

---

## 1. Current-state confirmation

### 1.1 The only web links either native client can currently produce

`GET /api/v1/user/profile` (`server/src/main/java/com/vpn/server/controller/UserController.java:60-101`)
returns exactly two web-facing URLs, both referral links, both read-only
marketing links with no auth handoff of any kind:

```java
// UserController.java:57-58
@Value("${vpn.public.web-base-url:https://vpn.struchev.site}")
private String publicWebBaseUrl = "https://vpn.struchev.site";
...
// UserController.java:93-94
response.put("referralLink", buildReferralWebLink(user.getReferralCode()));
response.put("referralTelegramLink", buildReferralTelegramLink(user.getReferralCode()));
```

```java
// UserController.java:111-116
private String buildReferralWebLink(String referralCode) {
    if (referralCode == null || referralCode.isBlank()) return "";
    String base = publicWebBaseUrl == null ? "" : publicWebBaseUrl.trim();
    while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
    return base + "/?ref=" + referralCode;
}
```

`vpn.public.web-base-url` is used **nowhere else** in `server/src/main/java`
(confirmed via `grep -rn "web-base-url\|webBaseUrl" server/src/main/java`).
There is no `/billing`, `/topup`, or any other client-initiated deep link
into the web app anywhere server-side.

### 1.2 Desktop: no purchase/top-up UI, and no generic "open web dashboard" affordance either

- `desktop/src/renderer/src/pages/ConnectPage.tsx:142` — the entire "no
  subscription" UI is `<p className="text-sm text-white/60">{t.noSubscription}</p>`.
  No button, no link, no `onClick`.
- `shell.openExternal` appears in exactly two places in `desktop/src/main`:
  - `desktop/src/main/index.ts:49`, inside `win.webContents.setWindowOpenHandler`
    — a generic safety net that routes any `target="_blank"`/`window.open`
    navigation out to the system browser instead of inside the app window.
    Nothing in the renderer currently triggers this for a billing link (no
    `target="_blank"` link to the web dashboard exists anywhere in
    `desktop/src/renderer`).
  - `desktop/src/main/auth/googleOAuth.ts:133`, inside the Google OAuth
    loopback flow (see §1.4 below).
- There is no IPC handler exposed from `desktop/src/main/ipc.ts` /
  `desktop/src/preload` that lets the renderer ask the main process to open
  an arbitrary external URL. The only paths to `shell.openExternal` are the
  two above, neither of which is wired to a "go to billing" affordance.
- `desktop/src/main/api/tokenStore.ts` stores `{ token, userId, deviceId,
  selectedRegion, deviceUuid }` via Electron `safeStorage`. The JWT never
  leaves this process today except as an `Authorization: Bearer` header to
  the API.

**Conclusion: UX_REVIEW's claim is accurate for desktop** — there is no
code path, wired or unwired, that takes a desktop user to the web dashboard
for billing.

### 1.3 Android: no purchase/top-up UI, and literally no browser-opening code exists at all

- `android/app/src/main/java/com/vpn/android/ui/connect/ConnectFragment.java:192-195`:
  ```java
  if (latestProfile != null && !latestProfile.hasActiveSubscription) {
      Snackbar.make(binding.getRoot(), R.string.state_no_subscription, Snackbar.LENGTH_LONG).show();
      return;
  }
  ```
  A toast-like `Snackbar` with no action button, then nothing.
- `grep -rn "CustomTabsIntent\|Intent.ACTION_VIEW\|browser" android/app/src/main/java -i`
  (excluding test files and unrelated matches — `ReconnectBackoffPolicy.java`
  and `XrayConfigFactory.java` only reference "browser" in the sense of a
  TLS/uTLS *fingerprint* name, not an actual browser-opening API) returns
  **no matches**. There is no `Intent.ACTION_VIEW`, no `CustomTabsIntent`,
  no `WebView`, anywhere in the Android app.
- `android/app/build.gradle:70-90` — the full dependency list has no
  `androidx.browser:browser`. Adding Chrome Custom Tabs support requires a
  new dependency (see §5.4).
- `android/app/src/main/java/com/vpn/android/api/TokenStore.java` stores the
  JWT in `EncryptedSharedPreferences` (Keystore-backed), same shape as
  desktop's store, and again the JWT never leaves the process today.

**Conclusion: UX_REVIEW's claim is accurate for Android, and more starkly
than desktop** — Android has no browser-launching capability of any kind
yet, for anything (not billing, not "visit our site," nothing).

### 1.4 The precedent this design should follow: desktop's Google OAuth loopback flow

`desktop/src/main/auth/googleOAuth.ts` already solves "hand the user to an
external, trusted browser and get a credential back" once, for a harder
problem (proving identity to a third party, Google). It:

1. Opens the system browser via `shell.openExternal` (`googleOAuth.ts:133`)
   rather than an embedded `BrowserWindow` — explicitly because Google
   fingerprints and blocks embedded webviews (`googleOAuth.ts:34-38`).
2. Uses PKCE (S256) + a `state` param (`googleOAuth.ts:66-68`, `115-124`) to
   defend a public/installed client against a stolen authorization code.
3. Receives the redirect on a one-shot, OS-assigned-port, `127.0.0.1`-only
   HTTP server (`receiveAuthorizationCode`, `googleOAuth.ts:72-138`), torn
   down on success, failure, *and* a 5-minute timeout
   (`FLOW_TIMEOUT_MS`, `googleOAuth.ts:9`).

This is the right shape to imitate for "open the system browser and get
something back," but the *problem* solved here is easier: we're not proving
identity to a third party, we're handing an already-authenticated session
from one first-party surface (the client) to another first-party surface
(the web dashboard) that trusts the same server. See §3 for what that
difference should (and shouldn't) simplify.

### 1.5 No existing short-lived/single-use code pattern to be consistent with

The task brief speculated there might be a similar "pending code" pattern
already added for Telegram Stars payment linking. Checked and **not found**:

- Telegram Stars payment support already exists (`de7c5d3`, "Phase 4
  Telegram Bot (Stars payments, referral program, ...)"), but
  `TelegramBotService.java` / `TelegramBotController.java` do not implement
  an account-linking flow with a short-lived code — Telegram auth
  (`TelegramAuthService.java`) instead verifies the Mini App's `initData`
  HMAC directly against the bot token, a different (and stateless)
  mechanism, not applicable here.
- `grep -rln "pendingCode\|PendingCode\|linkCode\|LinkCode\|verificationCode\|ConcurrentHashMap" server/src/main/java`
  turns up nothing relevant (`AgentStreamServiceImpl`'s `ConcurrentHashMap`
  is an in-memory registry of live gRPC stream observers, unrelated).
- The closest analog in spirit is `AntiEnumerationService` (`server/src/main/java/com/vpn/server/service/AntiEnumerationService.java`),
  which tracks short-window, per-user state — but it's DB-backed
  (`SubscriptionAccessLogRepository`), not in-memory, because its window is
  60 minutes and needs to survive a server restart / work across replicas.
  A 30-90 second exchange code has different requirements (see §5.1).

**Conclusion:** there is no precedent to match; the design below is free to
pick the simplest correct approach (§3.2).

---

## 2. Proposed mechanism

### 2.1 Summary

A short-lived, single-use, server-side **exchange code**, minted by an
already-authenticated client via a new endpoint, carried into the browser
as an opaque URL parameter (never the JWT itself), and redeemed exactly
once by the web app to obtain a real session JWT.

```
Client (has valid JWT)
   │  POST /api/v1/auth/web-handoff   (Authorization: Bearer <jwt>)
   ▼
Server: mint opaque code, store {userId, expiresAt, used=false}, return it
   │  { "code": "…", "expiresInSeconds": 60 }
   ▼
Client: open system browser / Custom Tab to
        <web-base-url>/handoff?code=<code>&next=/billing
   ▼
Browser: web app's /handoff route loads, immediately calls
   │  POST /api/v1/auth/web-handoff/exchange   { "code": "…" }
   ▼
Server: look up code — must exist, be unused, be unexpired, belong to a
        real user → mark used (atomically) → mint a normal JWT via the
        existing JwtUtil.generateToken(userId, email, role) → return it
        in the SAME AuthResponse shape as login/register/telegram/google/device
   ▼
Web app: setToken(response.token) (the same api.ts helper every other auth
        method already uses) → fetch profile → navigate to `next` (default "/")
```

### 2.2 Endpoints (server)

**`POST /api/v1/auth/web-handoff`** — new, requires a valid JWT
(add to `AuthController`, or a small new `WebHandoffController`; it needs
`Authentication auth` so it belongs behind the existing `JwtAuthFilter`,
unlike everything currently in `AuthController`, which is pre-auth. Cleanest
as its own controller so `AuthController` doesn't need a mixed
authenticated/unauthenticated `@RequestMapping`).

Request: no body needed (userId comes from the authenticated principal,
exactly like `UserController.getProfile`'s `Long userId = (Long) auth.getPrincipal();`).

Response:
```json
{ "code": "8f3a1c...(32+ random url-safe chars)", "expiresInSeconds": 60 }
```

**`POST /api/v1/auth/web-handoff/exchange`** — new, unauthenticated (the
code itself is the credential, like a password-reset token).

Request:
```json
{ "code": "8f3a1c..." }
```

Response: reuse `AuthResponse` verbatim — `{ token, userId, email, role,
referralCode }` — identical shape to every other `/api/v1/auth/*` endpoint,
so the web client's existing `setToken(data.token)` call sites need zero new
parsing logic (`web/src/api.ts:18-63` already has four near-identical blocks
doing `fetch → check ok → json → setToken(data.token) → return data`; this
is a fifth of the same shape).

On failure (unknown/expired/already-used code): `400` with
`{ "error": "..." }`, matching the existing convention in
`UserController`/`BillingService` call sites (`catch (IllegalArgumentException
| IllegalStateException e) { return ResponseEntity.badRequest()... }`).

### 2.3 Server-side state: a `WebHandoffService`

In-memory is sufficient and simplest (see §3.2 for why not DB-backed):

```java
@Service
public class WebHandoffService {
    private record PendingHandoff(Long userId, Instant expiresAt) {}
    private final Map<String, PendingHandoff> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @Value("${vpn.web-handoff.ttl-seconds:60}")
    private int ttlSeconds;

    public String issueCode(Long userId) {
        purgeExpired(); // cheap opportunistic sweep; also fine to add a @Scheduled sweep
        String code = base64url(random, 32); // ≥192 bits
        pending.put(code, new PendingHandoff(userId, Instant.now().plusSeconds(ttlSeconds)));
        return code;
    }

    /** Single-use: removes the entry on first successful lookup, whether or not it's expired. */
    public Long redeem(String code) {
        PendingHandoff h = pending.remove(code);
        if (h == null || Instant.now().isAfter(h.expiresAt())) return null;
        return h.userId();
    }
}
```

`redeem` uses `Map.remove` (atomic per-key) as the single-use guard — no
separate `used` flag/transaction needed, and it composes correctly with
concurrent requests racing on the same code (only one can win the `remove`).

The controller then does exactly what every other auth path does:
```java
User user = userRepository.findById(userId).orElseThrow();
String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());
return new AuthResponse(token, user.getId(), user.getEmail(), user.getRole(), user.getReferralCode());
```
— i.e. `WebHandoffService` only ever hands back a `userId`; token minting
stays centralized in `JwtUtil`/wherever `AuthService` currently builds
`AuthResponse`, so there's exactly one code path that turns "a user" into
"a JWT," same as today.

### 2.4 Web app: `/handoff` route

`web/src/App.tsx` has no router (`grep -n "Router\|Route"` — none); it
branches on `window.location.hash` (`#admin`) and reads one-off query params
via `URLSearchParams` (`?ref=`, `App.tsx:35-38`). Follow the same convention
rather than introducing a router dependency for one route:

```ts
// App.tsx, alongside the existing referralCode/showAdmin state
const [handoff] = useState(() => {
  const params = new URLSearchParams(window.location.search);
  const code = params.get('handoff_code');
  return code ? { code, next: params.get('next') || '/' } : null;
});

useEffect(() => {
  if (!handoff) return;
  (async () => {
    try {
      const data = await api.exchangeWebHandoff(handoff.code); // POST .../exchange, setToken internally
      history.replaceState(null, '', handoff.next); // strip the code from the URL/history immediately
      await initApp(); // re-run the existing profile-load bootstrap
    } catch {
      // fall through to normal logged-out landing page; show a small
      // "sign-in link expired, please log in" toast
    }
  })();
}, [handoff]);
```

Using a query param (`?handoff_code=...&next=...`) rather than a `/handoff`
path keeps this a one-file change (no static-hosting rewrite rules needed
for a new path — the SPA is presumably served with only `/` mapped to
`index.html` today; check `server`'s static resource config before
committing to a path-based route). `next` should be validated as a
same-origin relative path only (reject absolute/`//`-prefixed values) to
avoid turning this into an open redirect.

### 2.5 Desktop: opening the browser

New IPC call, e.g. `vpnApi.openWebHandoff(next: string)`:

- Renderer (e.g. `ConnectPage.tsx`'s "no subscription" block, and anywhere
  else a "manage billing" entry point is added per UX_REVIEW §B) calls
  `window.vpnApi.openWebHandoff('/billing')`.
- Main process handler: calls the server's `POST /api/v1/auth/web-handoff`
  with the stored JWT (via the existing `ApiClient`), then
  `shell.openExternal(`${webBaseUrl}?handoff_code=${code}&next=${next}`)`.
  This is the same `shell.openExternal` already used in `googleOAuth.ts:133`
  — no new Electron capability needed.
- `webBaseUrl` needs to reach the desktop build the same way it reaches the
  server config (`vpn.public.web-base-url`) — today desktop has no such
  config value; add one (build-time env var or a value returned by the API,
  e.g. folded into the handoff response itself: `{ code, webUrl,
  expiresInSeconds }` so the client never needs to know the base URL
  independently — **recommended**, since it also means the server is the
  only place that ever needs to change if the dashboard's domain changes).

### 2.6 Android: opening the browser via Chrome Custom Tabs

- Add `implementation 'androidx.browser:browser:1.8.0'` to
  `android/app/build.gradle` (confirmed absent, §1.3).
- New helper, e.g. `WebHandoffLauncher`, calls the same
  `POST /api/v1/auth/web-handoff` (via the existing `ApiClient`/OkHttp
  setup) and then:
  ```java
  CustomTabsIntent intent = new CustomTabsIntent.Builder().build();
  intent.launchUrl(context, Uri.parse(webUrl + "?handoff_code=" + code + "&next=" + next));
  ```
- Wire into `ConnectFragment.java:192-195` — replace the bare `Snackbar`
  with one that has an action button ("Add funds" / `R.string.add_funds`)
  that triggers this, matching the two-line fix UX_REVIEW #7 suggested,
  now with a real destination instead of "open the marketing site and hope
  they log in again."

---

## 3. Security reasoning

### 3.1 Why not just put the JWT in the URL?

The client's real, long-lived (`720`-hour, i.e. 30-day —
`JwtUtil.java:23`) session JWT must never appear in a URL:

- **Browser history**: persists locally indefinitely; anyone with later
  access to the device (shared computer, forensics, a synced-history
  account) gets a 30-day-valid credential.
- **Referrer leakage**: if the `/handoff` page ever loads any third-party
  resource (analytics, a font from Google Fonts — `web` already loads
  external stylesheets per its CSP-style constraints elsewhere in this
  product) before stripping the query string, the full URL — JWT included —
  leaks via the `Referer` header.
- **Server/proxy access logs**: reverse proxies, CDNs, and the Spring Boot
  access log itself commonly log full request URLs (query string included)
  by default; a request body is far less commonly logged.
- **Multi-use**: a JWT is valid for its full 30-day life however it's
  obtained; leaking it once via any of the above is a full account
  takeover for a month, not a 60-second window.

A separate opaque **exchange code** fixes all four: it's short-lived (60s
default), single-use (consumed on first redemption), and — even if it did
leak via the same channels — is worthless after use or after 60 seconds,
and was never itself a valid API credential (it only unlocks *one*
server-side exchange, and only while the client that requested it is the
only one who has seen it).

### 3.2 Why in-memory + single instance, not DB-backed

Contrast with `AntiEnumerationService`, which is DB-backed. The two have
opposite requirements:
- Anti-enumeration's window is 60 *minutes*, needs to survive a server
  restart mid-window, and must be correct across horizontally-scaled server
  replicas (so DB-backed is the only correct choice there).
- A handoff code's window is 60 *seconds*. If the server restarts in that
  window, the correct behavior is simply "the code stops working, the user
  retries" — an entirely acceptable failure mode for something this
  short-lived, and much cheaper than a DB round-trip on both mint and
  redeem for a security-sensitive, deliberately-throwaway value. If/when
  this server runs multiple replicas behind a load balancer without sticky
  sessions, this *would* need to move to a shared store (Redis, or the
  existing Postgres with a short TTL column + a cleanup job) — call this
  out explicitly as a scaling caveat, not a correctness bug today (check
  current deployment topology before shipping — this doc doesn't have that
  context).

### 3.3 Why single-use (remove-on-read) rather than a `used` boolean + separate check

A `Map.remove` is atomic; a `SELECT ... check used ... UPDATE ... SET used`
sequence (or even a `synchronized` boolean flip in a non-atomic map) is a
check-then-act race if two redemption requests for the same code arrive
concurrently (e.g. a flaky network causes the web page to fire the exchange
call twice, or a code somehow gets echoed to two tabs). `remove()` handing
back the value exactly once and `null` to every subsequent caller closes
that race by construction rather than by discipline.

### 3.4 Why the system browser / Custom Tabs, not an embedded webview

Two independent reasons, both already established precedent in this
codebase:
1. **Password manager / existing session continuity** — the entire point
   of this feature is "don't make the user log in again"; an embedded
   webview is a fresh, cookie-less context that defeats that even before
   this feature's own SSO code runs (no saved passwords, no existing web
   session to *not* need this mechanism for in the first place, on a repeat
   visit).
2. **Precedent already set for a harder case** — `googleOAuth.ts:34-38`'s
   comment explains Google outright blocks embedded webviews for OAuth;
   this codebase already made the "use the real system browser" call once
   and that reasoning applies at least as strongly here, since a system
   browser is also more trustworthy against a malicious app trying to
   intercept a redirect (see 3.5).

### 3.5 Is PKCE / state-param-level rigor warranted here?

**No — and explicitly, not by omission.** The Google OAuth flow's PKCE +
`state` + localhost-loopback-server posture defends against a fundamentally
different threat: an attacker intercepting an *authorization code that
proves identity to a third party* (Google), where the attacker doesn't
already have any relationship with the victim's account. Here:

- The "attacker" scenario would be: some other local process/app on the
  same device intercepts the `handoff_code` before the intended browser
  request happens, and races to redeem it first. This requires *local
  code execution or a malicious app already running on the same device the
  user is already using while logged into the VPN client* — at that point
  the attacker already has far cheaper ways to compromise the session (read
  the JWT straight out of `safeStorage`/`EncryptedSharedPreferences` isn't
  possible without the OS keystore unlocked to that app's own identity, but
  neither is intercepting an Electron `shell.openExternal` call or an
  Android `CustomTabsIntent` launch without a comparable level of device
  compromise — e.g. a malicious app registering itself as a competing
  handler for the same URL scheme). A generic `https://` URL opened via
  `shell.openExternal`/Custom Tabs goes to the user's actual default
  browser, not an app-claimable custom scheme, so classic "another app
  registered your custom URI scheme" interception (a real Android/iOS deep
  -link risk) doesn't apply — this design deliberately uses a plain
  `https://` URL, not a custom scheme, precisely to sidestep that class of
  attack.
- Even in the worst case (attacker wins the race and redeems the code
  first), the blast radius is bounded to *this one account's web session*,
  for as long as that JWT is valid (JwtUtil default 720h — see the scoping
  question in §4) — not "attacker now controls the account forever" and not
  "attacker learned the account password." It's a same-account,
  same-privilege session replay, not a privilege escalation or third-party
  identity compromise.
- Short expiry (60s) + single-use already closes the realistic remote/
  network-level interception window (no proxy or network observer has more
  than 60 seconds to both see and redeem the code, and doing so consumes it,
  which is user-visible — the legitimate exchange fails loudly).

**Verdict: skip PKCE/state/loopback-server complexity for this flow.** It
would be defense against a threat model (network/third-party MITM proving
identity to an external IdP) this flow doesn't have. The one thing worth
keeping from the OAuth precedent is *using the real system browser*
(§3.4) — the rest (PKCE, state, localhost callback server) is solving a
different problem and would be needless complexity here.

---

## 4. Edge cases / open questions

These are presented as options with tradeoffs per the task brief, not as
settled decisions — flagged explicitly where a default is still proposed.

### 4.1 No active client-side session (anonymous/never-logged-in state)

Does this apply to desktop's silent device-trial account
(`DeviceAuthService`, auto-created per `UX_REVIEW.md` §C)? **Yes, trivially
— a device-trial account already has a valid JWT** (it's a real, if
synthetic, account per `AuthResponse`), so the same `web-handoff` endpoint
works unchanged; the web session that results is just a "trial device
account" session, with the same synthetic
`device_<uuid>@device.local` email UX_REVIEW #D flags — that's a
pre-existing, orthogonal problem this feature doesn't need to fix (but
inherits the same synthetic-email-as-identity display concern once it's the
*web* app showing that email post-handoff instead of just the desktop app).

If a client somehow has no token at all (fresh install, before even the
silent device-trial call completes) — the "manage billing"/"no
subscription" entry points that trigger a handoff are gated behind already
having a profile loaded (`latestProfile != null` in `ConnectFragment.java:191`
already guards this case on Android; equivalent guard needed wherever
desktop wires this up) — so this shouldn't be reachable in practice. If it
is (race condition, corrupted store), falling back to opening the plain web
base URL with no `handoff_code` (i.e. the normal logged-out landing page,
`AuthModal`) is the correct degrade — never block the user on an error.

### 4.2 Risk of code interception by another local process/app

Addressed in §3.5 — assessed as materially lower stakes than the OAuth
case, no additional hardening (PKCE/state/loopback) recommended. Open
question worth a second opinion: **should the exchange endpoint also bind
the code to something client-observable** (e.g. return the code together
with a short human-readable confirmation string shown in the client's own
UI, so a user who somehow gets shown *two* browser tabs — e.g. a
double-click — can tell which one is legitimate)? Judged low-value for a
first version; flagging rather than deciding.

### 4.3 Should the exchanged web session be same-privilege as the client's, or scoped down?

**Default proposed: same privilege (issue a normal, full JWT via the
existing `JwtUtil.generateToken`), with one explicit exception.**

Reasoning: this flow's only intended purpose today is "let the user manage
billing/top-up from a browser." The web dashboard's regular user-facing
surface (`web/src/components/*`) doesn't expose anything more sensitive
than what the client itself already trusts the user with (balance, devices,
referral code) — a device that already holds a full JWT can already call
every non-admin endpoint directly. Scoping the *web* session down while the
*client's own* JWT remains full-privilege wouldn't reduce real risk, only
add complexity (a second token type, a second `JwtUtil` code path, claims
the `JwtAuthFilter` needs to understand) for a boundary that doesn't
correspond to any actual privilege difference today.

**Exception: the admin panel.** `web/src/admin/AdminPanel.tsx` is reachable
from the *same* web app/session (`App.tsx`'s `#admin` hash route, §2.4) and
today gates on `role === 'ADMIN'` client-side plus (presumably) server-side
checks on admin endpoints. A handed-off session for an admin's own account
would, as designed above, be able to open `#admin` in that browser tab too
— which may be *more* exposure than intended for a flow whose only trigger
today is "buy a subscription" (an admin's device/desktop app has no reason
to ever need admin-panel access via this path). Two options, no firm
recommendation:
  - (a) Ship as designed (full-privilege) — simplest, and an admin who
    wants admin-panel access already has a browser bookmark/password-manager
    entry for that; this flow doesn't take anything away from them.
  - (b) Add a `scope` claim (e.g. `"web-handoff"`) to the token minted via
    this path and have `JwtAuthFilter`/admin endpoints reject that scope
    for `/api/v1/admin/*`, forcing a real login for admin actions even from
    a handed-off session. More defense-in-depth, more code, and a new kind
    of JWT the auth filter has to understand for the first time.

Given the trigger for this whole feature is "billing/top-up," and no admin
today is expected to hit "buy a subscription" from a native client's
handoff button, **(a) is the pragmatic default** — but this is exactly the
kind of judgment call worth a second opinion before implementation, since
it's a one-line decision now and a breaking-change-shaped one later if
reversed after the token shape is already relied on by clients.

### 4.4 `next` destination validation

`next` must be restricted to same-origin relative paths (e.g. allow-list
`/`, `/billing`, `/devices`, ... or just regex-reject anything starting
with `//` or containing `://`) both server-side (when minting, if the
client is trusted to name the destination — it is, since it's the client's
own choice of where to send its own user) and web-side (before calling
`history.replaceState`/navigating) to avoid the exchange endpoint being
usable as an open-redirect primitive by a malicious deep link crafted
outside the client apps (e.g. someone hand-crafts
`.../?handoff_code=...&next=https://evil.example`). Since the JWT itself
never travels through `next`, the worst case of getting this wrong is a
phishing-style redirect after a legitimate login, not a token leak — but
it's a one-line guard, no reason to skip it.

### 4.5 Rate limiting `POST /api/v1/auth/web-handoff`

Not discussed above; worth a short mention. Since it's authenticated
(requires an existing valid JWT) the abuse case is narrow (a compromised
client credential minting many codes), but a basic per-user rate limit
(e.g. reuse whatever pattern, if any, currently throttles other
authenticated write endpoints — none was found in this pass; flag for the
implementer to check) is cheap insurance against a buggy client retry-loop
flooding the in-memory map.

---

## 5. Implementation checklist by module

### 5.1 Server
- [ ] New `WebHandoffService` (in-memory `ConcurrentHashMap<String,
      PendingHandoff>`, `issueCode(Long userId)` / `redeem(String code)`,
      `@Value("${vpn.web-handoff.ttl-seconds:60}")`).
- [ ] New endpoint(s) — either add to `AuthController` or a new
      `WebHandoffController`:
  - `POST /api/v1/auth/web-handoff` (authenticated) → `{ code,
    webUrl, expiresInSeconds }` (include `webUrl` = `vpn.public.web-base-url`
    so clients never hardcode/independently configure it).
  - `POST /api/v1/auth/web-handoff/exchange` (unauthenticated, body
    `{ code }`) → `AuthResponse` on success, `400 { error }` on
    invalid/expired/already-used.
- [ ] Reuse `JwtUtil.generateToken` — no changes to `JwtUtil` needed unless
      §4.3's option (b) (scoped token) is chosen, in which case add a
      `claim("scope", "web-handoff")` overload and a filter-side check.
- [ ] Validate `next` isn't needed server-side if the server never sees it
      (client builds the final browser URL itself) — confirm which side
      constructs the full `?handoff_code=&next=` URL and validate on
      whichever side accepts `next` as input from something less trusted
      than the client's own compiled code (the web `/handoff` param parse,
      §5.2, is the one that must validate, since that URL could be crafted
      by anyone).
- [ ] Basic rate limit / abuse guard on `web-handoff` mint (see §4.5) —
      check if a shared rate-limiting utility already exists elsewhere
      before adding a new one.
- [ ] Config: `vpn.web-handoff.ttl-seconds` (default 60), reuse existing
      `vpn.public.web-base-url`.

### 5.2 Web (`web/src`)
- [ ] `api.ts`: add `exchangeWebHandoff(code: string)` following the exact
      shape of `login`/`register`/`telegramAuth`/`googleAuth`
      (`web/src/api.ts:18-63`) — POST, check `res.ok`, `setToken(data.token)`,
      return `data`.
- [ ] `App.tsx`: parse `?handoff_code=&next=` alongside the existing
      `?ref=`/`?start=` parsing (`App.tsx:35-38`), call
      `exchangeWebHandoff`, `history.replaceState` to strip the code from
      the URL immediately (don't leave it sitting in browser history even
      though it's single-use — belt and suspenders, and avoids a confusing
      "expired code" error on refresh), then re-run `initApp()`/navigate to
      the validated `next` (allow-list or regex-reject absolute/protocol-
      relative values, §4.4).
  - Consider extracting the existing `initApp` profile-bootstrap logic
    (`App.tsx:65+`) into something callable standalone from both the normal
    boot path and the post-handoff path if it isn't already reusable as-is.
- [ ] Small UX touch: a brief loading state while the exchange call is in
      flight (should be near-instant, but the page shouldn't flash the
      logged-out landing page first).

### 5.3 Desktop (`desktop/src`)
- [ ] `main`: add an IPC handler (`desktop/src/main/ipc.ts` +
      `desktop/src/preload`) e.g. `openWebHandoff(next: string)` that:
      calls the existing `ApiClient` to `POST /api/v1/auth/web-handoff`,
      then `shell.openExternal(`${webUrl}?handoff_code=${code}&next=${next}`)`.
- [ ] `renderer`: wire `ConnectPage.tsx`'s `{t.noSubscription}` block
      (line 142) into a real button calling
      `window.vpnApi.openWebHandoff('/billing')` — this is the concrete fix
      for UX_REVIEW #7 on desktop. Consider also a persistent "Manage
      billing" entry point per UX_REVIEW §B (e.g. in `ProfilePage.tsx`),
      not just the dead-end error state.
- [ ] i18n: add the new button's copy to `desktop/src/renderer/src/i18n.ts`.
- [ ] No new Electron capability needed — `shell.openExternal` is already
      used and already the sanctioned pattern (§1.4).

### 5.4 Android (`android/app`)
- [ ] `build.gradle`: add `implementation 'androidx.browser:browser:1.8.0'`
      (confirmed missing, §1.3/§5.4 header).
- [ ] New helper class (e.g. `WebHandoffLauncher`) calling the existing
      OkHttp-based API client for `POST /api/v1/auth/web-handoff`, then
      launching a `CustomTabsIntent` to `webUrl + "?handoff_code=" + code +
      "&next=" + next`.
- [ ] `ConnectFragment.java:192-195`: replace the plain `Snackbar` with one
      that has an action (`Snackbar.setAction(...)`) invoking the launcher
      with `next=/billing` — concrete fix for UX_REVIEW #7 on Android.
- [ ] `strings.xml` / `values-en/strings.xml`: add the action button's
      copy (e.g. `R.string.add_funds` / `R.string.manage_billing`).
- [ ] Consider also surfacing an entry point outside the dead-end state
      (e.g. a "Billing" row somewhere in Android's profile/settings screen,
      mirroring desktop's ProfilePage suggestion above) per UX_REVIEW §B.

### 5.5 Cross-cutting
- [ ] Decide §4.3 (same-privilege vs. scoped web session) before shipping
      the admin panel is reachable from a handed-off session either way
      unless option (b) is chosen.
- [ ] Confirm deployment topology (single instance vs. multiple replicas
      behind a load balancer) before relying on the in-memory map (§3.2) —
      if multi-replica without sticky sessions, move `WebHandoffService`'s
      store to Redis or a short-TTL DB table instead.
- [ ] Telegram bot/mini-app is explicitly out of scope for this pass (per
      task brief, "maybe in the future") but the design generalizes
      directly: a bot command could call the same `web-handoff` endpoint
      (using whatever server-side credential the bot already holds for that
      Telegram user, e.g. via `TelegramAuthService`) and send the resulting
      URL as a message instead of opening a browser itself — no protocol
      changes needed to extend this later.
