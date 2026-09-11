# TODO: bring Android to parity with desktop's guest/trial profile UX

**Status:** not started. **Scope:** `android/` only — the server already supports
everything this needs (see "Already done server-side" below).

## Why this exists

The desktop client had a UX problem: a fresh install silently logs into an
auto-created, no-signup "device-trial" account and then presents it exactly
like a real one — fake email as the profile name, a "Logout" button that
means nothing for an account nobody consciously created, a Devices tab
managing a device list that doesn't apply, balance/referral/billing UI for
an account with no real identity yet. Three commits fixed this on desktop:

- `13a71eb` — server: `isGuest` on `/user/profile`, `POST /auth/upgrade`
  (converts the guest row in place), guest→existing-account merge on
  `/auth/login` (`GuestMergeService`), guard against silently re-entering an
  upgraded account via `/auth/device`. Desktop: `ProfilePage`/`DevicesPage`
  branch on `isGuest`, `LoginPage` calls upgrade instead of register from a
  guest session, `App.tsx` hides the Devices tab for guests.
- `b4c1ce7` — same merge wired into Google sign-in (`/auth/google` now takes
  `deviceUuid` too).
- `81bdb96` — guest gets one nav-less screen (Connect + a single sign-in/
  register CTA) instead of a tabbed shell; `ProfilePage`/`DevicesPage` no
  longer need to know about guests at all, since they're never mounted for
  one; `ConnectPage` drops its billing CTA for guests.

**Android was believed to have no guest flow at all when this was first
raised — that's no longer true.** Sometime after the desktop work, a
no-signup device-trial flow was ported to Android too (`LoginActivity
.attemptDeviceLogin()`, `TokenStore.getOrCreateDeviceUuid()`, `ApiClient
.deviceAuth()`), matching desktop's *original* (pre-`13a71eb`) shape. So
Android now has the exact bug this whole effort was about: a guest sees
`profile_logout` right next to `profile_sign_in_existing`, plus balance,
referral link, and "Пополнить баланс" for an account that isn't really
theirs, and `DevicesFragment` manages a device list that doesn't apply
either. This doc is the port of the *fix*, not the original feature.

## Already done server-side — no server changes needed

- `GET /api/v1/user/profile` returns `isGuest` (no password/Telegram/Google
  credential on the row).
- `POST /api/v1/auth/upgrade` (authenticated with the guest's own JWT):
  converts the guest row in place — same id/balance/trial, just adds
  email+password.
- `POST /api/v1/auth/login` and `POST /api/v1/auth/google` both accept an
  optional `deviceUuid`; if it resolves to a genuine guest row, its balance
  (and, if the target has no active subscription yet, its trial) get folded
  into the account just signed into, then the guest row is deleted
  (`GuestMergeService`). Best-effort — never fails the login itself.
- `POST /api/v1/auth/device` refuses to auto-login a `deviceUuid` that's
  since been upgraded to a credentialed account (`DeviceAuthService`), so a
  client falling back to LoginPage/LoginActivity on any device-auth failure
  is safe by construction.

## Android gaps

**`api/model/UserProfile.java`**
- Add `public boolean isGuest;` — mirrors the JSON field already returned.

**`api/ApiClient.java`**
- `login()` and `googleAuth()`: attach `tokenStore.getOrCreateDeviceUuid()`
  as `deviceUuid` in the request body (see desktop's `apiClient.ts#login`/
  `#googleAuth` for the exact shape) so the server-side merge actually
  triggers.
- Add `upgradeGuest(String email, String password)`: `POST
  /api/v1/auth/upgrade` with the current bearer token (`true` for the
  authenticated flag), same response handling as `register()`/`login()`
  (`tokenStore.save(resp.token, resp.userId)`). See desktop's
  `apiClient.ts#upgradeGuest`.
- `deviceAuth()` needs no change — the server-side guard is enough as long
  as callers already fall back to the login/register form on any failure
  (`LoginActivity.attemptDeviceLogin`'s error callback already does this).

**`api/TokenStore.java`**
- `clear()` currently does `prefs.edit().clear().apply()`, wiping
  `KEY_DEVICE_UUID` along with the session. That's the same bug desktop had
  before `13a71eb`: every logout mints a brand new `deviceUuid` on next
  launch, which mints a brand new 3-day trial — an unlimited-trial loophole.
  Fix: preserve `KEY_DEVICE_UUID` (and probably `KEY_SELECTED_REGION`, same
  as desktop) across `clear()`. Safe to do given the server-side guard in
  `DeviceAuthService` above — a `deviceUuid` that's since been upgraded to a
  real account won't silently auto-login without a password.

**`ui/login/LoginActivity.java`**
- Needs an `isGuestSession` concept, same role as desktop's `LoginPage`
  prop: when the "register" action fires while a guest session is still
  active (reached via `ProfileFragment`'s "sign in with an existing
  account" — see `EXTRA_FORCE_FORM`), call `upgradeGuest()` instead of
  `register()`. "Login" and "Google sign-in" need no branching — they
  always send `deviceUuid` per the `ApiClient` changes above, and the
  server no-ops the merge when there's no guest row to fold in.
- Whatever launches `LoginActivity` in "force form" mode (today just
  `ProfileFragment`, see below) needs to tell it whether the session being
  left behind is a guest one — e.g. an `EXTRA_IS_GUEST_SESSION` alongside
  `EXTRA_FORCE_FORM`, or `LoginActivity` just calls `getProfile()` itself
  before showing the form.

**`ui/MainActivity.java` + guest single-screen**
- Desktop's `81bdb96` replaced the tabbed shell with one nav-less screen for
  guests (Connect + trial-access explanation + one CTA), rather than gating
  each tab individually. Port the same shape: on launch, check
  `profile.isGuest` (one extra `getProfile()` call, same as `App.tsx`'s
  `checkAuth`) and either show the existing `bottomNav` + fragment-switching
  behavior (registered account) or a single guest layout with
  `ConnectFragment`'s content plus a small "Пробный доступ" card and one
  sign-in/register button (reusing `LoginActivity.createShowFormIntent`).
  The exact mechanism (a dedicated `GuestFragment` composing the same views
  `ConnectFragment` uses, vs. inflating a different `activity_main` layout
  variant) is an Android-idiomatic-implementation call, not prescribed here.

**`ui/profile/ProfileFragment.java`, `ui/devices/DevicesFragment.java`**
- Once the guest screen above exists, neither of these is ever shown to a
  guest anymore (same as desktop's `81bdb96`, which deleted their now-dead
  `isGuest` branches) — no changes needed *inside* these two beyond making
  sure they're simply unreachable for a guest session.

**`ui/connect/ConnectFragment.java`**
- Drop the `get_plan_action` / "Пополнить баланс" billing CTA for guests
  (mirrors desktop's `ConnectPage` change in `81bdb96`) — nothing to manage
  yet, and the sign-in/register CTA on the same guest screen is the actual
  way to raise limits. Needs an `isGuest` flag passed in (or fetched via the
  same `getProfile()` call `MainActivity` already needs to make).

**`res/values/strings.xml` + `res/values-en/strings.xml`**
- Add the guest-screen copy — mirrors desktop's `i18n.ts` additions
  (`guestProfileTitle`, `guestProfileDesc`, `signInOrRegister`): something
  like `guest_profile_title` ("Пробный доступ" / "Trial access"),
  `guest_profile_desc` (same copy as desktop's `guestProfileDesc`), and
  `sign_in_or_register` ("Войти или зарегистрироваться" / "Sign in or
  register"). `profile_sign_in_existing` and `profile_logout` stay as-is —
  they're still correct for the registered-account view.

## Suggested order

1. `TokenStore.clear()` fix (small, standalone, closes the trial-abuse
   loophole immediately regardless of the rest).
2. `UserProfile.isGuest` + `ApiClient` changes (`login`/`googleAuth`
   deviceUuid, `upgradeGuest`) — no UI yet, just wiring; can be verified
   against the already-shipped server behavior directly.
3. `LoginActivity` upgrade-vs-register branching.
4. `MainActivity` guest single-screen + `ConnectFragment` billing-CTA gating.
5. Manual pass through the guest flow end to end: fresh install → connect
   works → "sign in or register" → register folds the guest row in place →
   relaunch shows the registered profile, no guest screen.
