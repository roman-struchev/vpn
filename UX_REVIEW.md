# UX Review — Web Dashboard, Admin Panel, Desktop, Android

Scope: read the actual UI code for all four surfaces (`web/src`, `web/src/admin`, `desktop/src/renderer`, `android/app/src/main`) plus the server-side logic behind billing/trial/device-auth where the client behavior only makes sense in light of it. All file/line references point at code as of `d01d10e` (head of `main` at review time). Findings are concrete and cite the exact spot; proposed fixes are specific, not "polish this."

---

## Quick wins (small, clearly net-positive, no product debate needed)

### 1. Raw internal numbers leak into a user-facing error message
**File:** `server/src/main/java/com/vpn/server/service/BillingService.java:244-245`, surfaced via `server/src/main/java/com/vpn/server/controller/UserController.java:180-182` → `web/src/api.ts:129-140` → `web/src/components/DashboardView.tsx:352-357` (`purchaseError`).
**Current behavior:** insufficient-balance throws `IllegalStateException("Insufficient balance. Required: " + price + ", current: " + user.getBalanceUsdtMicro())` — `price` and `balanceUsdtMicro` are raw micro-USDT integers. This exact string is returned as `{"error": ...}` and rendered verbatim in the purchase-error banner.
**Problem:** a user trying to buy a $5/mo plan sees literally "Insufficient balance. Required: 5000000, current: 0" — internal units with no `$` formatting, in English regardless of the site's selected language.
**Fix:** format the message server-side in dollars (`Required: $5.00, current: $0.00`), or better, have the client catch this specific error and render its own localized "You need $X.XX more — top up now" message with a button that opens the top-up modal pre-filled with the shortfall.

### 2. AuthModal is not localized — hardcodes Russian regardless of selected language
**File:** `web/src/components/AuthModal.tsx:164` (`"Регистрация"`), `:218` ("Referral Code (Optional)" — English hardcoded here instead), `:244` (`"Создать аккаунт"`), `:256-258` (`"Уже есть аккаунт? Войти"` / `"Нет аккаунта? Зарегистрироваться"`).
**Problem:** every other component in `web/src` routes copy through `translations[lang]` (`t.login`, etc. — see `Navbar.tsx`, `DashboardView.tsx`). This one modal — the single most important conversion screen on the site — mixes hardcoded Russian and hardcoded English literals instead of `t`. An English-language visitor who clicks "Get Started" sees the modal title, submit button, and toggle link in Russian, and the referral label in English, while everything around it is in their chosen language.
**Fix:** add the missing keys to `web/src/i18n.ts` (`register`, `createAccount`, `alreadyHaveAccount`, `noAccountRegister`, `referralCodeOptional`) and use `t.*` throughout the modal like every other component does.

### 3. Admin panel: dangerous actions (block user, adjust balance) have no confirmation, while a less consequential one does
**File:** `web/src/admin/sections/UsersSection.tsx:249-260` (adjust balance — applies immediately on click), `:283-297` (block/unblock — applies immediately on click). Compare to `web/src/admin/sections/NodesSection.tsx:173-176`, where restarting a node's xray process *does* get a `window.confirm(t.restartXrayConfirm)` guard, and to the ordinary user-facing `web/src/components/DashboardView.tsx:124` (`confirm('Are you sure you want to revoke this device?')`).
**Problem:** blocking a paying customer's account or crediting/debiting arbitrary USDT to their balance are both higher-stakes than restarting a background process, yet they're one click from the row-click → dialog → button path with zero "are you sure." A misclick or double-click on `Block` instantly locks a user out.
**Fix:** wrap `adjustBalance` (at least for negative amounts / amounts over some threshold) and `setUserStatus('BLOCKED')` in the same `window.confirm(...)` pattern already used for `restartXray` and device revocation.

### 4. Android: manual "Add Device" has no explanation, unlike web/desktop
**File:** `android/app/src/main/res/layout/fragment_devices.xml` (no hint text above the list), `android/app/src/main/java/com/vpn/android/ui/devices/DevicesFragment.java:66-82`. Compare to `web/src/components/DashboardView.tsx:266` (`t.deviceAutoAddedHint`) and `desktop/src/renderer/src/pages/DevicesPage.tsx:30` (`t.thisDeviceAutoAdded`), and to the desktop-specific fix in commit `0a090ff` ("clarify Desktop's manual add device is for other devices, not this one").
**Problem:** the exact confusion that `0a090ff` fixed on desktop — a user assumes the "Add device" button is how they register *this* phone, when in fact this device auto-registers on connect and the button is only for naming a slot for some other device — was never backported to Android. Android's devices screen just shows the list and a bare "Add Device" button.
**Fix:** add the equivalent hint string (`values/strings.xml` + `values-en/strings.xml` already exist and are the right place) above the list, mirroring the other two platforms' copy.

### 5. Android's device-revoke confirmation dialog doesn't actually ask anything
**File:** `android/app/src/main/java/com/vpn/android/ui/devices/DevicesFragment.java:91-97`.
```java
new AlertDialog.Builder(requireContext())
        .setMessage(device.deviceName)
        .setPositiveButton(R.string.revoke_device_action, ...)
```
**Problem:** the dialog's only body text is the device's name — there's no title and no question ("Revoke access for this device?"). It reads as a bug (a dialog that just displays a name and two buttons) rather than a confirmation.
**Fix:** add a title (e.g. `R.string.revoke_device_action`) and a real message, e.g. `getString(R.string.confirm_revoke_device, device.deviceName)` → "Revoke access for \"%s\"? It will need to reconnect to use the VPN again."

### 6. Desktop: revoking a device has no confirmation at all
**File:** `desktop/src/renderer/src/pages/DevicesPage.tsx:18-25` — `revoke()` calls `window.vpnApi.deleteDevice(id)` directly on click, no `confirm()`.
**Problem:** the same destructive action is guarded on web (`confirm(...)` in `DashboardView.tsx:124`) and on Android (an `AlertDialog`, even if its copy needs fixing — see #5), but unguarded on desktop. A misclick removes a device with no undo.
**Fix:** add a `window.confirm(...)`-equivalent (or a small modal, consistent with the rest of the desktop app's style) before calling `deleteDevice`.

### 7. "No active subscription" is a dead end on both native clients — no path forward
**File:** `desktop/src/renderer/src/pages/ConnectPage.tsx:142` (`<p>{t.noSubscription}</p>`, no link/button), `android/.../ConnectFragment.java:192-195` (a `Snackbar` with the same text, then nothing).
**Problem:** neither the desktop app nor the Android app has any purchase/top-up UI at all (confirmed — no "buy"/"topup"/"purchase" strings anywhere in either codebase). So the single most important call-to-action a lapsed or never-subscribed user needs — "get a plan" — literally does not exist in either real VPN client. The only way forward is to already know to go to the web dashboard.
**Fix:** at minimum, turn that text into a button that opens the web dashboard's billing/pricing section in the system browser (`shell.openExternal` on desktop, an `Intent.ACTION_VIEW` on Android). This is a two-line fix on each platform with an outsized impact on conversion.

### 8. Trial duration is never shown to the user before they activate it
**File:** `web/src/components/DashboardView.tsx:393-407` (trial card renders `Free`, quota GB, device count — no duration), `web/src/components/LandingView.tsx:211-217` (`planTrialPerk` = "No card — just try it" — also no duration), `web/src/i18n.ts` (no string anywhere mentions "3 days").
**Problem:** the actual trial length is a hardcoded `3 days` server-side (`TelegramAuthService.java:176`, `DeviceAuthService.java:95`) but is never surfaced in any tariff card, landing page copy, or dashboard banner. A user only discovers "oh, it's 3 days" by reading the fine-print expiry timestamp after already activating it.
**Fix:** add the duration to the trial perk list (`t.planTraffic`-style templated string) so it reads e.g. "3-day trial, no card needed" wherever the trial tariff is rendered.

### 9. Choosing a specific plan on the landing page loses that choice through signup
**File:** `web/src/components/LandingView.tsx:264-273` — every tariff card's "Choose Plan" button calls the same `onGetStarted` prop with no argument; `App.tsx:159` wires `onGetStarted` to just `() => setIsAuthOpen(true)`.
**Problem:** a visitor who deliberately clicks "Choose Plan" on the Pro card, not Basic, gets dropped into the exact same generic auth modal as someone who clicked the hero CTA. After registering, they land on `DashboardView` with no plan pre-selected or highlighted, and have to re-find and re-click the same plan again on the Tariffs card.
**Fix:** thread the clicked `tariffId` through `onGetStarted(tariffId)` → `AuthModal` → post-signup redirect/scroll to that tariff card (or auto-open the purchase flow for it) on `DashboardView`.

### 10. Balance/price unit labeling flips between "USDT" and "$" without a clear rule
**Files:** `web/src/components/Navbar.tsx:67` shows `$X.XX USDT`; `DashboardView.tsx:398` shows tariff price as `$X`; the top-up modal title is `"{t.topUp} (USDT)"` (`DashboardView.tsx:597`) but the amount buttons show plain `$1/$5/$10/$20` (`:634-646`). Not wrong (1 USDT ≈ $1), but inconsistent enough to read as sloppy.
**Fix:** pick one convention (probably "$X USDT" the first time a number appears in a given card, then just "$X" after) and apply it everywhere; low priority.

---

## Bigger structural ideas (need product buy-in, not just an engineering pass)

### A. The "trial" tariff silently grants a different duration depending on which code path creates it
**Files:** `server/src/main/java/com/vpn/server/service/DeviceAuthService.java:84-100` and `TelegramAuthService.java:166-180` both explicitly grant **3 days** (`Instant.now().plus(3, ChronoUnit.DAYS)`), commented as a deliberate one-time "taste." But `BillingService.purchaseOrRenewSubscription` — the path the ordinary web "Activate Free" button goes through (`DashboardView.tsx:159-171` → `api.purchaseSubscription`) — computes the period end generically as `periodStart.plus(isAnnual ? 365 : 30, ChronoUnit.DAYS)` (`BillingService.java:269`), with **no special case for the trial tariff's duration**. A regular web sign-up activating "trial" gets a full 30-day period; a Telegram or desktop-device user gets 3 days, for the identically-named/identically-priced ($0) tariff.
**Why it matters:** this isn't cosmetic — it's the same product concept ("try it free") paying out 10x more value depending on which door the user walked through, almost certainly unintentionally, since the one-time-use guard (`BillingService.java:230-239`) treats all three paths as equivalent for eligibility but not for duration.
**Open question for product/eng:** what *should* the web trial length be? If 3 days is the intended "taste," `purchaseOrRenewSubscription` needs a trial-specific branch. If 30 days is intended for web (e.g. as a stronger acquisition lever for people who bother to register with email/password vs. the frictionless device/Telegram paths), that's a legitimate but very non-obvious asymmetry that should at least be stated in the UI ("30-day trial" vs "3-day trial" depending on how you sign up), not left implicit.

### B. Desktop and Android — the actual VPN clients — have zero payment/top-up capability
Already flagged as a quick win for the dead-end message (#7), but the structural question is bigger: is this permanent (e.g. to avoid Apple/Google in-app-purchase policy friction for a VPN selling crypto-funded balance) or just not built yet? If permanent, the clients should be designed around "billing always happens on the web" as a first-class constraint (e.g. a persistent, well-labeled "Manage billing" entry point, not just a dead-end error string), rather than having it read like a missing feature.

### C. Three different first-run experiences across three clients, converging but not yet unified
- **Web:** landing page → auth modal (email/password, Google, or arrives pre-filled via `?ref=`) → dashboard.
- **Desktop:** silently auto-creates a device-bound trial account on first launch (`App.tsx:15-30`), no login screen shown at all unless device-login fails; `LoginPage` only reachable afterward via Profile → "sign in with an existing account."
- **Android:** forces email/password registration/login before any use (`LoginActivity.java`) — no device-trial, no Google sign-in yet.
Per the task brief, device-trial and Google sign-in are landing on Android soon. When they do, this is the moment to define one onboarding story across all three ("try instantly, keep your progress by signing in with Google/email whenever you want") instead of three independently-evolved flows. Right now even the *language* differs: desktop's silent trial account has no "you're on a trial" messaging anywhere in its UI (no string in `desktop/src/renderer/src/i18n.ts` mentions "trial" at all — a returning user genuinely cannot tell from the app that their account is a temporary device account rather than a real one).

### D. The synthetic device-account email is exposed to the user as their profile identity
**File:** `server/src/main/java/com/vpn/server/service/DeviceAuthService.java:71` sets `user.setEmail("device_" + deviceUuid + "@device.local")`; `desktop/src/renderer/src/pages/ProfilePage.tsx:47` renders `{profile?.email}` as the page's `<h1>` with no special-casing.
**Problem:** a trial-device desktop user's Profile tab literally reads as its `<h1>`: something like `device_3f8a1c2e-9b7a-4e21-8b4a-2b6e0b0e2d1a@device.local`. This is an internal implementation detail (needed so the backend has a unique/lookup-able identifier) leaking straight into the primary identity display of the app.
**Fix:** the admin panel already does the right thing here (`UsersSection.tsx:14`, `isTrialDeviceUser` → shows a friendly "Trial device" badge instead of relying on the email looking special). Desktop's `ProfilePage` should do the equivalent: detect the `device_*@device.local` pattern (or better, have the API return an explicit `isDeviceAccount` flag instead of relying on email shape) and render "Trial device account" with a prompt to add a real email, instead of the raw synthetic address.

### E. Users can't see their own referral earnings — only admins can
**Files:** `web/src/types.ts:1-20` (`UserProfile` has `referralCode`, `referralLink`, `referralTelegramLink` — no earnings field); `web/src/components/DashboardView.tsx:510-552` (referral card shows the link/code only); compare to `web/src/admin/sections/UsersSection.tsx:199-205`, where the admin can see `referralEarningsUsdtMicro` and `referralCount` for any user.
**Problem:** the referral program's whole pitch to a user is "invite people, earn money," but the dashboard never shows them what they've actually earned or how many people they've referred — that data exists and is computed, just not exposed via the user-facing API/UI. This weakens the program's own incentive loop.
**Fix:** add `referralCount` and `referralEarningsUsdtMicro` to the `/user/profile` response and surface them in the referral card ("3 friends joined · $4.50 earned").

### F. The crypto top-up flow is the most jargon-heavy part of a product whose landing page was just redesigned to be less jargon-heavy
**File:** `web/src/components/DashboardView.tsx:606-719` — chain picker (TRC-20/ERC-20), an exact-amount-with-tolerance-window deposit address, and a manual "paste your tx hash" fallback form, all in one modal. Contrast with `d0ccac9`'s stated goal ("redesign the landing page for broader, less jargon-heavy appeal").
**Not a bug** — this is presumably why Telegram Stars payment linking is being built in parallel (per the task brief) as a mainstream-friendly alternative. Flagging as a forward-looking structural note: once Stars linking ships, it should probably become the *default* payment option shown first, with on-chain USDT demoted to an "advanced / crypto" disclosure, rather than the two being presented as equal-weight tabs — most users have no idea what TRC-20 vs ERC-20 means or why a deposit needs to be a suspiciously specific amount like `5.037182 USDT`.

### G. Admin's payment reconciliation supports chains the user-facing deposit flow doesn't offer
**Files:** `web/src/admin/sections/PaymentsSection.tsx:60-65` (chain dropdown includes `BASE`, `ARBITRUM`, `POLYGON` in addition to `TRON`/`ETHEREUM`), vs. `web/src/components/DashboardView.tsx:610-614` (user-facing deposit only ever offers `TRON` and `ETHEREUM`).
**Open question:** is this dead surface area in the admin tool (reconciling a payment on a chain no user could have been given an address for), or is there another entry point (e.g. Telegram Stars, or a chain-agnostic deposit path) that can produce a Base/Arbitrum/Polygon payment today? Worth a quick check with whoever owns the payments backend — if those three are truly unreachable from any client, either wire them into the user-facing chain picker or trim them from the admin dropdown so operators aren't offered options that can't correspond to any real user action.

---

## Notes on features that are mid-flight (context, not asks)

- **Google sign-in (web):** implemented and working (`AuthModal.tsx:94-135`, gated behind `VITE_GOOGLE_CLIENT_ID` so it cleanly no-ops when unconfigured). The only issue found is the localization bug already covered in Quick Win #2 — the surrounding modal chrome, not the Google button itself.
- **No-signup trial (desktop, "soon Android"):** implemented on desktop (`App.tsx:15-30`, `DeviceAuthService.java`), not yet on Android (confirmed — `LoginActivity` has no device/anonymous path). See structural note C and D above for the rough edges once it's live.
- **Telegram Stars payment linking (web dashboard):** no trace of it yet in `web/src` (checked `api.ts`, `types.ts`, `DashboardView.tsx` for any `stars`/`telegram` payment reference beyond the existing referral deep-link and Telegram Mini App auth) — genuinely not landed in this tree yet, so no UX to review here. Flagging only the forward-looking integration point in structural note F.

---

## Summary of the 4 most important things

1. A raw, unformatted internal error message ("Required: 5000000, current: 0") can be shown directly to a paying customer trying to buy a plan — `BillingService.java:244` / `DashboardView.tsx:352`.
2. The signup modal — the single highest-stakes screen on the marketing site — hardcodes Russian/English literals instead of using the i18n system every other component uses, so non-Russian visitors see mixed-language text mid-signup (`AuthModal.tsx:164,244,256-258`).
3. The same $0 "trial" tariff silently grants 3 days via Telegram/desktop-device signup but 30 days via ordinary web registration, because only the former two paths special-case the trial's duration (`DeviceAuthService.java:95`, `TelegramAuthService.java:176` vs. `BillingService.java:269`).
4. Both real VPN clients (desktop, Android) have no purchase/top-up capability and no way out of a "no active subscription" dead end other than knowing to separately visit the web dashboard — worth a deliberate decision (and at minimum a "open billing in browser" button) rather than leaving it as an implicit gap.
