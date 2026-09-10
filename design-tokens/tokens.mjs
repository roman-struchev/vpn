/**
 * Shared design tokens — docs/PLAN.md §1/§9: "общий пакет ui/ ... Android — Material 3
 * с теми же токенами". A full shared `ui/` component package is out of scope (web/desktop
 * are React+Tailwind, Android is native Java/Material3 — no shared component runtime
 * across them), but the token *values* were previously hand-duplicated verbatim across
 * web/tailwind.config.js, desktop/tailwind.config.js and android/.../res/values/colors.xml.
 * This file is now the single source of truth for those values.
 *
 * Consumers:
 * - web/tailwind.config.js, desktop/tailwind.config.js: `import tokens from '../design-tokens/tokens.mjs'`.
 * - android/app/src/main/res/values/colors.xml: not machine-generated from this file (a
 *   Gradle-time XML codegen step is disproportionate engineering for ~15 color constants).
 *   Kept in sync manually — colors.xml points back here in a comment. If you change a
 *   value below, update colors.xml (and its android:color hex, uppercase, no leading `#`
 *   stripped) in the same change.
 *
 * `brand` and `dark` are shared across all three platforms. `state` (connection-state
 * indicator colors) is shared between Android and Desktop only — the website has no
 * connection-state screen, so it isn't imported there.
 */
export default {
  brand: {
    50: '#f0fdf4',
    100: '#dcfce7',
    500: '#22c55e',
    600: '#16a34a',
    700: '#15803d',
  },
  dark: {
    800: '#181b20',
    850: '#131519',
    900: '#0c0e12',
    950: '#060709',
  },
  state: {
    connected: '#22c55e',
    connecting: '#f59e0b',
    reconnecting: '#f59e0b',
    error: '#ef4444',
    blocked: '#ef4444',
    disconnected: '#9ca3af',
  },
};
