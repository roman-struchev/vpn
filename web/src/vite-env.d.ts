/// <reference types="vite/client" />

interface ImportMetaEnv {
  // Google OAuth Client ID (Google Cloud Console -> APIs & Services ->
  // Credentials -> OAuth 2.0 Client ID, type "Web application"). Used by
  // AuthModal.tsx to render the "Sign in with Google" button. Left empty in
  // dev/CI on purpose — set it in the environment used for the `vite build`
  // that produces the bundled web frontend.
  readonly VITE_GOOGLE_CLIENT_ID?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
