import { useState, useEffect, useRef } from 'react';
import { Lang } from './i18n';
import { UserProfile, Tariff } from './types';
import { api, getToken, removeToken } from './api';
import { Navbar } from './components/Navbar';
import { LandingView } from './components/LandingView';
import { DashboardView } from './components/DashboardView';
import { AuthModal } from './components/AuthModal';
import { AdminPanel } from './admin/AdminPanel';
import { P2pRelayTermsPage } from './components/P2pRelayTermsPage';
import { LegalDoc, LegalPage } from './components/LegalPage';
import { SUPPORT_HANDLE, SUPPORT_URL } from './support';

declare global {
  interface Window {
    Telegram?: {
      WebApp?: {
        initData: string;
        initDataUnsafe?: any;
        expand: () => void;
        ready: () => void;
      };
    };
  }
}

/**
 * Same-origin relative path guard for a handoff link's `next` param
 * (WEB_HANDOFF_RESEARCH.md §4.4) — must start with a single `/` and must not
 * be protocol-relative (`//host/...`) or carry a scheme (`https://...`),
 * either of which would turn this into an open-redirect primitive for a
 * hand-crafted `?handoff_code=...&next=https://evil.example` link.
 */
function isSafeRelativePath(path: string | null): path is string {
  if (!path) return false;
  if (!path.startsWith('/')) return false;
  if (path.startsWith('//')) return false;
  if (path.includes('://')) return false;
  return true;
}

export function App() {
  const [lang, setLang] = useState<Lang>('ru');
  const [user, setUser] = useState<UserProfile | null>(null);
  const [tariffs, setTariffs] = useState<Tariff[]>([]);
  const [loading, setLoading] = useState(true);

  const [isAuthOpen, setIsAuthOpen] = useState(false);
  const [isTopUpOpen, setIsTopUpOpen] = useState(false);
  // Tariff a visitor picked on the landing page before signing up (LandingView's
  // "Choose Plan" button on a specific card, as opposed to the generic hero/
  // closing CTAs) — carried through the auth modal and on to the dashboard so
  // that choice isn't silently dropped. See UX_REVIEW.md Quick Win #9.
  const [selectedTariffId, setSelectedTariffId] = useState<string | null>(null);
  // A referral link (?ref=CODE, or a forwarded Telegram ?start=CODE) opened
  // directly in a browser — not inside Telegram — should still land on a
  // pre-filled register form instead of silently dropping the code.
  const [referralCode] = useState<string | null>(() => {
    const params = new URLSearchParams(window.location.search);
    return params.get('ref') || params.get('start');
  });
  // A client -> web SSO handoff code (?handoff_code=...&next=...), opened by
  // a desktop/Android client's system browser so the user doesn't have to
  // log in again just to reach a specific page (e.g. billing). See
  // WEB_HANDOFF_RESEARCH.md.
  const [handoff] = useState<{ code: string; next: string } | null>(() => {
    const params = new URLSearchParams(window.location.search);
    const code = params.get('handoff_code');
    if (!code) return null;
    const rawNext = params.get('next');
    return { code, next: isSafeRelativePath(rawNext) ? rawNext! : '/' };
  });
  // Backed by the URL hash (#admin), not just React state: a plain useState
  // resets to false on every reload (F5 while in the admin panel bounced you
  // back to the dashboard with no way to tell you'd been in admin at all).
  // #admin, or #admin/<tab> once AdminPanel starts tracking its own tab.
  const [showAdmin, setShowAdmin] = useState(() => window.location.hash.startsWith('#admin'));
  // Public P2P relay terms page (docs/research/P2P_RELAY_FEASIBILITY.md §8.6)
  // — reachable via a stable #p2p-terms hash link regardless of login state,
  // same hash-routing convention as #admin above (this SPA has no server-side
  // routing for arbitrary paths, so a hash fragment is the zero-backend-
  // changes way to give the feature a stable, linkable URL: <origin>/#p2p-terms).
  const [showP2pTerms, setShowP2pTerms] = useState(() => window.location.hash.startsWith('#p2p-terms'));
  // #privacy / #terms: linkable pages (the store listings need a privacy URL).
  const legalFromHash = (): LegalDoc | null =>
    window.location.hash.startsWith('#privacy') ? 'privacy' : window.location.hash.startsWith('#terms') ? 'terms' : null;
  const [legalDoc, setLegalDoc] = useState<LegalDoc | null>(legalFromHash);
  const closeLegal = () => {
    history.replaceState(null, '', window.location.pathname + window.location.search);
    setLegalDoc(null);
  };
  // "Take me to the plans" — the destination the mobile apps' "Change plan"
  // button hands over (next=/#tariffs). Resolved once, here, rather than read
  // off location inside the dashboard: the handoff rewrites the URL only after
  // its code is exchanged, which may land before or after the tariffs load.
  const [scrollToTariffs] = useState(() =>
    window.location.hash.startsWith('#tariffs') ||
    (new URLSearchParams(window.location.search).get('next') ?? '').startsWith('/#tariffs')
  );

  const openAdmin = () => {
    if (!window.location.hash.startsWith('#admin')) window.location.hash = 'admin';
    setShowAdmin(true);
  };

  const closeAdmin = () => {
    if (window.location.hash.startsWith('#admin')) {
      history.replaceState(null, '', window.location.pathname + window.location.search);
    }
    setShowAdmin(false);
  };

  const closeP2pTerms = () => {
    if (window.location.hash.startsWith('#p2p-terms')) {
      history.replaceState(null, '', window.location.pathname + window.location.search);
    }
    setShowP2pTerms(false);
  };

  useEffect(() => {
    const onHashChange = () => {
      setShowAdmin(window.location.hash.startsWith('#admin'));
      setShowP2pTerms(window.location.hash.startsWith('#p2p-terms'));
      setLegalDoc(legalFromHash());
      if (legalFromHash()) window.scrollTo(0, 0);
    };
    (async () => {
      if (handoff) {
        try {
          // setToken happens inside exchangeWebHandoff, same as every other
          // auth method in api.ts — initApp() below then picks it up.
          await api.exchangeWebHandoff(handoff.code);
        } catch (err) {
          // Expired/invalid/already-used code: fail silently into the
          // normal logged-out landing page rather than blocking the user
          // with an error state (WEB_HANDOFF_RESEARCH.md §4.1).
          console.warn('Web handoff exchange failed', err);
        }
        // Strip the code from the URL/history immediately regardless of
        // outcome — it's single-use, so leaving it there just risks a
        // confusing "expired code" retry on refresh.
        history.replaceState(null, '', handoff.next);
        // replaceState fires no hashchange, and the pages above were picked
        // from the URL as it was before (no hash) — so next=/#privacy used to
        // land on the dashboard instead of the policy.
        onHashChange();
      }
      // `loading` is already true (initial state) at this point, so the
      // loading screen below covers the handoff exchange too — no separate
      // spinner needed to avoid flashing the logged-out landing page first.
      await initApp();
    })();

    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  const initApp = async () => {
    setLoading(true);

    // 1. Check Telegram Mini App environment
    if (window.Telegram?.WebApp?.initData) {
      window.Telegram.WebApp.ready();
      window.Telegram.WebApp.expand();
      try {
        await api.telegramAuth(window.Telegram.WebApp.initData);
      } catch (err) {
        console.warn('Telegram auth failed', err);
      }
    }

    // 2. Fetch tariffs
    try {
      const tariffList = await api.getTariffs();
      setTariffs(tariffList);
    } catch (err) {
      console.error('Failed to load tariffs', err);
    }

    // 3. If token present, fetch user profile
    if (getToken()) {
      await refreshUser();
    }

    setLoading(false);
  };

  const hasAutoOpenedAuthRef = useRef(false);
  useEffect(() => {
    if (!loading && referralCode && !user && !hasAutoOpenedAuthRef.current) {
      hasAutoOpenedAuthRef.current = true;
      setIsAuthOpen(true);
    }
  }, [loading, referralCode, user]);

  const refreshUser = async () => {
    try {
      await api.renewSessionIfNeeded();
      const profile = await api.getProfile();
      setUser(profile);
      // A stale #admin hash (e.g. bookmarked, or role changed server-side)
      // shouldn't strand a non-admin viewer on a blank/guarded route.
      if (showAdmin && profile.role !== 'ADMIN') closeAdmin();
    } catch (err) {
      // Signed out only when the server says the session is gone — not on a
      // network blip or a 5xx, which used to log people out as well.
      if (err instanceof Error && err.message === 'Unauthorized') {
        removeToken();
        setUser(null);
      }
    }
  };

  const handleLogout = () => {
    removeToken();
    setUser(null);
    closeAdmin();
  };

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-dark-900 text-slate-400">
        <div className="animate-pulse font-medium text-sm">Loading Aura VPN...</div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-dark-900 text-slate-100 flex flex-col justify-between">
      <div>
        <Navbar
          lang={lang}
          setLang={setLang}
          user={user}
          onOpenAuth={() => setIsAuthOpen(true)}
          onLogout={handleLogout}
          onOpenTopUp={() => setIsTopUpOpen(true)}
          onOpenAdmin={user?.role === 'ADMIN' ? openAdmin : undefined}
        />

        <main className="pb-16">
          {legalDoc ? (
            <LegalPage doc={legalDoc} lang={lang} onBack={closeLegal} />
          ) : showP2pTerms ? (
            <P2pRelayTermsPage lang={lang} onBack={closeP2pTerms} />
          ) : user && showAdmin && user.role === 'ADMIN' ? (
            <AdminPanel lang={lang} onBack={closeAdmin} />
          ) : user ? (
            <DashboardView
              lang={lang}
              user={user}
              tariffs={tariffs}
              onRefreshUser={refreshUser}
              onLogout={handleLogout}
              openTopUp={isTopUpOpen}
              setOpenTopUp={setIsTopUpOpen}
              highlightTariffId={selectedTariffId}
              scrollToTariffs={scrollToTariffs}
            />
          ) : (
            <LandingView
              lang={lang}
              tariffs={tariffs}
              onGetStarted={(tariffId) => {
                setSelectedTariffId(tariffId ?? null);
                setIsAuthOpen(true);
              }}
            />
          )}
        </main>
      </div>

      <footer className="border-t border-dark-800 py-6 px-4 text-xs text-slate-500">
        <div className="max-w-5xl mx-auto flex flex-wrap items-center justify-center gap-x-5 gap-y-2">
          <span>Aura VPN · 2026</span>
          <a href={SUPPORT_URL} target="_blank" rel="noreferrer" className="hover:text-slate-300">
            {lang === 'ru' ? 'Поддержка' : 'Support'}: {SUPPORT_HANDLE}
          </a>
          <a href="#privacy" className="hover:text-slate-300">
            {lang === 'ru' ? 'Политика конфиденциальности' : 'Privacy Policy'}
          </a>
          <a href="#terms" className="hover:text-slate-300">
            {lang === 'ru' ? 'Условия использования' : 'Terms of Use'}
          </a>
        </div>
      </footer>

      <AuthModal
        lang={lang}
        isOpen={isAuthOpen}
        onClose={() => setIsAuthOpen(false)}
        onSuccess={refreshUser}
        initialReferralCode={referralCode}
        initialTariffId={selectedTariffId}
      />
    </div>
  );
}

export default App;
