import React, { useState, useEffect, useRef } from 'react';
import {
  ShieldCheck,
  Smartphone,
  Copy,
  Check,
  Trash2,
  AlertCircle,
  HelpCircle,
  Share2,
  QrCode,
  Sparkles,
  Receipt,
  Globe,
} from 'lucide-react';
import { QRCodeSVG } from 'qrcode.react';
import { Lang, translations } from '../i18n';
import { UserProfile, Tariff, Device, CryptoInvoice, InvoiceHistoryEntry, BalanceHistoryEntry, RegionInfo } from '../types';
import { api } from '../api';
import { copyToClipboard } from '../utils/clipboard';
import { DownloadApp } from './DownloadApp';
import { P2pRelaySection } from './P2pRelaySection';

const REGION_LOAD_DOT: Record<RegionInfo['loadLevel'], string> = {
  LOW: 'bg-emerald-400',
  MEDIUM: 'bg-amber-400',
  HIGH: 'bg-red-400',
};

// Subscriptions actually expire at an exact instant, not "sometime that day" —
// a bare date ("04.12.2027") reads as if it's good until midnight/end-of-day,
// when it might really lapse at 09:14. Showing the time removes that
// ambiguity for every client that renders this same expiresAt value.
function formatExpiresAt(iso: string): string {
  const d = new Date(iso);
  return `${d.toLocaleDateString()} ${d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
}

interface DashboardViewProps {
  lang: Lang;
  user: UserProfile;
  tariffs: Tariff[];
  /** Land on the plans instead of the top of the dashboard — see App.tsx. */
  scrollToTariffs?: boolean;
  onRefreshUser: () => void;
  openTopUp: boolean;
  setOpenTopUp: (open: boolean) => void;
  /**
   * Tariff id the visitor picked on the landing page before signing up (see
   * LandingView's onGetStarted(tariffId) / App.tsx / AuthModal's
   * initialTariffId) — scrolled to and briefly highlighted on mount so that
   * choice is visibly carried through instead of silently dropped. See
   * UX_REVIEW.md Quick Win #9.
   */
  highlightTariffId?: string | null;
}

export const DashboardView: React.FC<DashboardViewProps> = ({
  lang,
  user,
  tariffs,
  scrollToTariffs,
  onRefreshUser,
  openTopUp,
  setOpenTopUp,
  highlightTariffId,
}) => {
  const t = translations[lang];

  const [devices, setDevices] = useState<Device[]>([]);
  const [links, setLinks] = useState<string[]>([]);
  const [regions, setRegions] = useState<RegionInfo[]>([]);
  const [copiedLink, setCopiedLink] = useState(false);
  const [copiedReferral, setCopiedReferral] = useState(false);
  const [showQr, setShowQr] = useState(false);

  const [invoiceHistory, setInvoiceHistory] = useState<InvoiceHistoryEntry[]>([]);
  const [balanceHistory, setBalanceHistory] = useState<BalanceHistoryEntry[]>([]);

  // Top-up modal states
  const [invoice, setInvoice] = useState<CryptoInvoice | null>(null);
  const [invoiceAmount, setInvoiceAmount] = useState('5');
  const [creatingInvoice, setCreatingInvoice] = useState(false);
  const [depositChain, setDepositChain] = useState<'TRON' | 'BASE' | 'ARBITRUM' | 'POLYGON' | 'ETHEREUM'>('TRON');
  const [claimTxHash, setClaimTxHash] = useState('');
  const [claimAmount, setClaimAmount] = useState('5');
  const [claimStatus, setClaimStatus] = useState<string | null>(null);
  const [claimError, setClaimError] = useState<string | null>(null);

  // Promo code states
  const [promoInput, setPromoInput] = useState('');
  const [promoSuccess, setPromoSuccess] = useState<string | null>(null);
  const [promoError, setPromoError] = useState<string | null>(null);
  const [applyingPromo, setApplyingPromo] = useState(false);

  // Telegram Stars top-up: a Stars payment can only actually be completed
  // inside Telegram (the Bot API can't push an invoice into an arbitrary web
  // session), so this method needs an extra "connect Telegram" step before
  // the usual amount buttons — see api.createTelegramLink / UserController's
  // POST /telegram-link and TelegramBotService#handleAccountLinkStart.
  const [topUpMethod, setTopUpMethod] = useState<'crypto' | 'stars' | 'promo'>('crypto');
  const [telegramLinkDeepLink, setTelegramLinkDeepLink] = useState<string | null>(null);
  const [telegramLinkLoading, setTelegramLinkLoading] = useState(false);

  const [telegramLinkError, setTelegramLinkError] = useState<string | null>(null);

  // Selected tariff purchase
  const [purchasingTariffId, setPurchasingTariffId] = useState<string | null>(null);
  const [isAnnual, setIsAnnual] = useState(false);
  const [purchaseError, setPurchaseError] = useState<string | null>(null);
  // Set alongside purchaseError only for an INSUFFICIENT_BALANCE failure, so the
  // error banner can offer a one-click "top up the shortfall" action instead of
  // just showing text. See handlePurchase / UX_REVIEW.md Quick Win #1.
  const [purchaseShortfallMicro, setPurchaseShortfallMicro] = useState<number | null>(null);

  // Tariff the visitor picked on the landing page pre-signup — scrolled to and
  // briefly highlighted once it's actually on screen (UX_REVIEW.md Quick Win #9).
  const [highlightedTariffId, setHighlightedTariffId] = useState<string | null>(null);
  const tariffCardRefs = useRef<Record<string, HTMLDivElement | null>>({});

  useEffect(() => {
    loadData();
  }, []);

  // Deep link straight to the plans (<origin>/#tariffs), used by the mobile
  // apps' "Change plan" button: plans are bought here, so landing the user at
  // the top of the dashboard leaves them hunting for the section they were
  // just sent to. Waits for `tariffs` because the section does not exist until
  // they have loaded.
  useEffect(() => {
    if (tariffs.length === 0 || !scrollToTariffs) return;
    document.getElementById('tariffs')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }, [tariffs, scrollToTariffs]);

  useEffect(() => {
    if (!highlightTariffId || !tariffs.some((tf) => tf.id === highlightTariffId)) return;
    setHighlightedTariffId(highlightTariffId);
    tariffCardRefs.current[highlightTariffId]?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    const timeout = setTimeout(() => setHighlightedTariffId(null), 4000);
    return () => clearTimeout(timeout);
  }, [highlightTariffId, tariffs]);

  const loadData = async () => {
    try {
      const [devs, vlessLinks, invoices, balanceEntries, regionList] = await Promise.all([
        api.getDevices(),
        api.getSubscriptionLinks(),
        api.getInvoiceHistory(),
        api.getBalanceHistory(),
        api.getRegions(),
      ]);
      setDevices(devs);
      setLinks(vlessLinks);
      setInvoiceHistory(invoices);
      setBalanceHistory(balanceEntries);
      setRegions(regionList);
    } catch (err) {
      console.error('Failed to load dashboard data', err);
    }
  };

  const handleCopyLink = () => {
    if (links.length > 0) {
      copyToClipboard(links[0]);
      setCopiedLink(true);
      setTimeout(() => setCopiedLink(false), 2000);
    }
  };

  // The referral link has to work for *anyone* the user invites, not just people
  // on Telegram — so the primary shareable link is a plain https URL carrying
  // ?ref=CODE (App.tsx reads that param and prefills the signup form). The server
  // builds it from its configured public web origin; falling back to the current
  // origin keeps the card working on preview/dev hosts and against older servers.
  // The Telegram deep link stays available as a secondary channel.
  const referralWebLink =
    user.referralLink || `${window.location.origin}/?ref=${user.referralCode}`;
  const referralTelegramLink = user.referralTelegramLink || '';

  const handleCopyReferral = () => {
    copyToClipboard(referralWebLink);
    setCopiedReferral(true);
    setTimeout(() => setCopiedReferral(false), 2000);
  };

  const handleRevokeDevice = async (id: number) => {
    if (!confirm(t.confirmRevokeDevice)) return;
    try {
      await api.deleteDevice(id);
      loadData();
      onRefreshUser();
    } catch (err: any) {
      alert(err.message || t.revokeDeviceError);
    }
  };

  // Poll the profile while the modal is open on the Stars method and the
  // account isn't linked yet, so the view flips to the linked state on its
  // own once the user taps Start in Telegram — no manual "check again" step.
  useEffect(() => {
    if (!openTopUp || topUpMethod !== 'stars' || user.telegramLinked) return;
    const interval = setInterval(() => onRefreshUser(), 4000);
    return () => clearInterval(interval);
  }, [openTopUp, topUpMethod, user.telegramLinked, onRefreshUser]);

  // Reset the connect-Telegram flow each time the modal is (re)opened, so a
  // stale deep link/error from a previous visit never lingers.
  useEffect(() => {
    if (!openTopUp) {
      setTelegramLinkDeepLink(null);
      setTelegramLinkError(null);
    }
  }, [openTopUp]);

  const handleConnectTelegram = async () => {
    setTelegramLinkError(null);
    setTelegramLinkLoading(true);
    try {
      const { deepLink } = await api.createTelegramLink();
      setTelegramLinkDeepLink(deepLink);
    } catch (err: any) {
      setTelegramLinkError(err.message || t.starsConnectError);
    } finally {
      setTelegramLinkLoading(false);
    }
  };

  // The bot username isn't otherwise sent to the client — rather than plumb a
  // brand-new profile field, derive it from the Telegram referral link the
  // profile already carries (both are built from the same
  // vpn.telegram.bot-username server property, see UserController).
  const telegramBotUsername = (() => {
    if (!referralTelegramLink) return null;
    try {
      const url = new URL(referralTelegramLink);
      return url.hostname === 't.me' ? url.pathname.replace(/^\//, '') : null;
    } catch {
      return null;
    }
  })();

  // Same denominations/prices as TelegramBotService's /balance inline
  // keyboard (sendBalanceMenu) — kept in sync manually since the bot doesn't
  // expose them over the API.
  const STAR_DENOMINATIONS: { stars: number; usd: number }[] = [
    { stars: 50, usd: 1 },
    { stars: 250, usd: 5 },
    { stars: 500, usd: 10 },
    { stars: 1000, usd: 20 },
  ];

  // Guarded against a second click while the first request is still out: each
  // call mints a real invoice server-side, so an impatient double-click used to
  // leave the user with two addresses and no hint which one to pay.
  const handleCreateInvoice = async () => {
    if (creatingInvoice) return;
    setCreatingInvoice(true);
    try {
      const amountMicro = Math.round(parseFloat(invoiceAmount) * 1_000_000);
      const inv = await api.createCryptoInvoice(depositChain, amountMicro);
      setInvoice(inv);
    } catch (err: any) {
      alert(err.message || t.depositError);
    } finally {
      setCreatingInvoice(false);
    }
  };

  const handleClaimTx = async (e: React.FormEvent) => {
    e.preventDefault();
    setClaimError(null);
    setClaimStatus(null);
    try {
      const amountMicro = Math.round(parseFloat(claimAmount) * 1_000_000);
      const res = await api.claimTx(depositChain, claimTxHash, amountMicro);
      setClaimStatus(t.claimSuccess.replace('{amount}', (res.amountMicro / 1_000_000).toFixed(2)));
      setClaimTxHash('');
      onRefreshUser();
    } catch (err: any) {
      setClaimError(err.message || t.claimFailed);
    }
  };

  const handleApplyPromo = async () => {
    if (!promoInput.trim()) return;
    setPromoError(null);
    setPromoSuccess(null);
    setApplyingPromo(true);
    try {
      const res = await api.applyPromoCode(promoInput.trim());
      const bonus = (res.bonusUsdtMicro / 1_000_000).toFixed(2);
      setPromoSuccess(lang === 'ru' ? `Промокод активирован! +$${bonus} USDT` : `Promo code applied! +$${bonus} USDT credited`);
      setPromoInput('');
      onRefreshUser();
      loadData();
    } catch (e: any) {
      setPromoError(e.message || 'Failed to apply promo code');
    } finally {
      setApplyingPromo(false);
    }
  };

  const handlePurchase = async (tariffId: string) => {
    setPurchaseError(null);
    setPurchaseShortfallMicro(null);
    setPurchasingTariffId(tariffId);
    try {
      await api.purchaseSubscription(tariffId, isAnnual);
      onRefreshUser();
      loadData();
    } catch (err: any) {
      // A raw "Required: 5000000, current: 0" string used to reach this banner
      // verbatim — api.ts now surfaces this failure as structured micro-USDT
      // fields instead, so it can be rendered as a proper localized message
      // with a one-click way to close the gap. See UX_REVIEW.md Quick Win #1.
      if (err.code === 'INSUFFICIENT_BALANCE' && typeof err.shortfallUsdtMicro === 'number') {
        const shortfall = Math.max(0, err.shortfallUsdtMicro) / 1_000_000;
        setPurchaseError(t.insufficientBalanceAmount.replace('{amount}', shortfall.toFixed(2)));
        setPurchaseShortfallMicro(err.shortfallUsdtMicro);
      } else {
        setPurchaseError(err.message);
      }
    } finally {
      setPurchasingTariffId(null);
    }
  };

  const handleScheduleSwitch = async (tariffId: string | null) => {
    setPurchaseError(null);
    setPurchaseShortfallMicro(null);
    setPurchasingTariffId(tariffId ?? sub?.nextTariffId ?? null);
    try {
      await api.scheduleNextTariff(tariffId);
      onRefreshUser();
    } catch (err: any) {
      setPurchaseError(err.message);
    } finally {
      setPurchasingTariffId(null);
    }
  };

  // Defensive: the server sends null when there's no active subscription, but
  // don't trust a bare truthiness check on `user.subscription` alone — an
  // object present without a tariffId crashed this whole view with
  // "Cannot read properties of undefined (reading 'toUpperCase')" below.
  const sub = user.subscription && user.subscription.tariffId ? user.subscription : null;
  const currentTariff = sub ? tariffs.find((tf) => tf.id === sub.tariffId) ?? null : null;
  // A paid plan with a real end date: other plans are a switch, not a second
  // purchase. Cheaper ones wait for the end of the period (buying one now
  // would throw away what is left of this one), pricier ones start at once.
  const paidCurrentTariff =
    sub && currentTariff && currentTariff.monthlyPriceUsdtMicro > 0 && !sub.noExpiry ? currentTariff : null;
  const periodEndDate = sub ? new Date(sub.expiresAt).toLocaleDateString() : '';
  // Renewal is paid from the balance; when it's short the VPN just stops at
  // the end of the period, so say so while there's still time to top up.
  const renewalPriceMicro = sub?.renewalPriceUsdtMicro ?? 0;
  const renewalShortfallMicro =
    paidCurrentTariff && sub?.autoRenew ? Math.max(0, renewalPriceMicro - user.balanceUsdtMicro) : 0;
  const affordableCheaperTariff =
    renewalShortfallMicro > 0 && !sub?.nextTariffId
      ? [...tariffs]
          .filter(
            (tf) =>
              tf.id.toLowerCase() !== 'trial' &&
              tf.monthlyPriceUsdtMicro > 0 &&
              tf.monthlyPriceUsdtMicro < paidCurrentTariff!.monthlyPriceUsdtMicro &&
              tf.monthlyPriceUsdtMicro <= user.balanceUsdtMicro,
          )
          .sort((a, b) => b.monthlyPriceUsdtMicro - a.monthlyPriceUsdtMicro)[0] ?? null
      : null;
  const usd = (micro: number) => (micro / 1_000_000).toFixed(2);
  const nextTariffName = sub?.nextTariffId
    ? tariffs.find((tf) => tf.id === sub.nextTariffId)?.name ?? sub.nextTariffId
    : null;
  const usedGb = sub ? sub.trafficUsedBytes / (1024 * 1024 * 1024) : 0;
  const limitGb = sub ? sub.trafficLimitBytes / (1024 * 1024 * 1024) : 0;
  const trafficPercent = limitGb > 0 ? Math.min(100, Math.round((usedGb / limitGb) * 100)) : 0;

  // Merged, chronologically sorted "fund movements" for the Billing History
  // card -- real balance-ledger rows (deposits, subscription debits, referral
  // bonuses, refunds, manual adjustments) plus crypto invoices that haven't
  // resolved into a ledger row yet (pending/expired/cancelled). A PAID
  // invoice is deliberately left out here: BillingService.creditInvoicePayment
  // writes both a PAID CryptoInvoice *and* a DEPOSIT BalanceEntry for the same
  // real-world deposit, so showing both would list the same event twice --
  // the DEPOSIT ledger row already says "money arrived" and carries the same
  // (friendlier) description text.
  type HistoryRow =
    | { kind: 'invoice'; createdAt: string; data: InvoiceHistoryEntry }
    | { kind: 'ledger'; createdAt: string; data: BalanceHistoryEntry };

  const mergedHistory: HistoryRow[] = [
    ...invoiceHistory
      .filter((inv) => inv.status !== 'PAID')
      .map((inv): HistoryRow => ({ kind: 'invoice', createdAt: inv.createdAt, data: inv })),
    ...balanceHistory.map((entry): HistoryRow => ({ kind: 'ledger', createdAt: entry.createdAt, data: entry })),
  ].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());

  return (
    <div className="w-full max-w-5xl mx-auto px-4 py-8 space-y-8">
      {/* Traffic + Devices — the two things a returning user actually checks
          first, side by side as the opening row rather than stacked full-width
          sections with Tariffs sandwiched between them. */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
      {/* Subscription Banner */}
      <div className="p-6 rounded-3xl bg-gradient-to-br from-dark-850 to-dark-800 border border-dark-800 shadow-xl relative overflow-hidden">
        {/* Always stacked, never a row: this card sits in a 2-column grid from
            `lg:` up, so it only ever gets ~half the viewport — a row layout
            that only flips at `xl:`/`2xl:` judges its own width off the full
            viewport, not the space it actually has, and squeezes the title +
            date + buttons into each other at exactly the width this card
            renders at on real (non-ultrawide) monitors. */}
        <div className="flex flex-col items-start gap-4 relative z-10">
          <div className="w-full">
            <div className="flex items-center justify-between gap-2">
              <h1 className="text-2xl font-bold tracking-tight">
                {sub ? `${t.traffic}: ${usedGb.toFixed(2)} / ${limitGb.toFixed(0)} GB` : t.noActiveSub}
              </h1>
              {sub && (
                <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 text-xs font-semibold shrink-0">
                  <ShieldCheck className="w-3.5 h-3.5" />
                  <span>{`${t.subscriptionActive} · ${sub.tariffId.toUpperCase()}`}</span>
                </div>
              )}
            </div>
            {sub && (
              <p className="text-xs text-slate-400 mt-1">
                {t.expiresAt}: {sub.noExpiry ? t.expiresNever : formatExpiresAt(sub.expiresAt)}
              </p>
            )}
            {renewalShortfallMicro > 0 && (
              <div
                data-testid="renewal-shortfall"
                className="mt-3 p-3 rounded-xl bg-amber-500/10 border border-amber-500/20 text-amber-300 text-xs flex items-start gap-2 flex-wrap"
              >
                <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
                <div className="flex-1 min-w-[12rem] space-y-1">
                  <p>
                    {t.renewalShortfall
                      .replace('{date}', periodEndDate)
                      .replace('{price}', usd(renewalPriceMicro))
                      .replace('{balance}', usd(user.balanceUsdtMicro))
                      .replace('{shortfall}', usd(renewalShortfallMicro))}
                  </p>
                  {affordableCheaperTariff && (
                    <p className="text-amber-200/80">
                      {t.renewalShortfallCheaper
                        .replace('{plan}', affordableCheaperTariff.name)
                        .replace('{date}', periodEndDate)}
                    </p>
                  )}
                </div>
                <button
                  onClick={() => {
                    setInvoiceAmount(String(Math.max(1, Math.ceil(renewalShortfallMicro / 1_000_000))));
                    setOpenTopUp(true);
                  }}
                  className="px-3 py-1 rounded-lg bg-amber-500/20 hover:bg-amber-500/30 text-amber-200 font-bold whitespace-nowrap"
                >
                  {t.topUp}
                </button>
              </div>
            )}
          </div>

          {/* Quick Actions */}
          <div className="flex flex-wrap items-center gap-2 w-full">
            {links.length > 0 && (
              <>
                <button
                  onClick={handleCopyLink}
                  className="flex-1 xl:flex-none flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl bg-brand-500 hover:bg-brand-600 text-dark-950 font-bold text-xs transition-all shadow-md shadow-brand-500/10"
                >
                  {copiedLink ? <Check className="w-4 h-4" /> : <Copy className="w-4 h-4" />}
                  <span>{copiedLink ? t.copied : t.copyLink}</span>
                </button>
                <button
                  onClick={() => setShowQr(true)}
                  className="p-2.5 rounded-xl bg-dark-800 hover:bg-dark-700 text-slate-200 transition-colors border border-dark-700"
                  title={t.qrCode}
                >
                  <QrCode className="w-4 h-4" />
                </button>
              </>
            )}
          </div>
        </div>

        {/* Progress Bar */}
        {sub && (
          <div className="mt-6 pt-6 border-t border-dark-800/80">
            <div className="w-full h-2 rounded-full bg-dark-900 overflow-hidden">
              <div
                className="h-full bg-gradient-to-r from-brand-500 to-emerald-400 rounded-full transition-all duration-500"
                style={{ width: `${trafficPercent}%` }}
              />
            </div>
            {/* Out-of-traffic P2P pitch (docs/research/P2P_RELAY_FEASIBILITY.md
                §8, repo owner's explicit request) — only shown once the plan
                is actually exhausted, not as a constant upsell nag. */}
            {trafficPercent >= 100 && (
              <p className="mt-3 text-[11px] text-slate-400">
                {t.p2pOutOfTrafficPitch}{' '}
                <a href="#p2p-terms" className="text-brand-500 hover:underline whitespace-nowrap">
                  {t.p2pOutOfTrafficLink}
                </a>
              </p>
            )}
          </div>
        )}
      </div>

      {/* Devices Section */}
      <div className="p-6 rounded-2xl bg-dark-850 border border-dark-800">
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-lg font-bold tracking-tight flex items-center gap-2">
            <Smartphone className="w-5 h-5 text-brand-500" />
            <span>{t.devices}</span>
          </h2>
          <p className="text-xs text-slate-400" data-testid="device-count">
            {devices.length}
            {currentTariff ? ` / ${currentTariff.maxDevices}` : ''}
          </p>
        </div>

        <p className="text-[11px] text-slate-500 mb-3 leading-snug">{t.deviceAutoAddedHint}</p>

        {devices.length === 0 ? (
          <p className="text-xs text-slate-500 py-4 text-center">No devices added yet.</p>
        ) : (
          <div className="divide-y divide-dark-800">
            {devices.map((d) => (
              <div key={d.id} className="py-1.5 flex items-center justify-between gap-2">
                <div className="flex items-baseline gap-2 min-w-0 truncate">
                  <h4 className="text-xs font-semibold truncate">{d.deviceName}</h4>
                  <span className="text-[10px] uppercase tracking-wider text-slate-500 whitespace-nowrap">
                    {d.platform} · {new Date(d.createdAt).toLocaleDateString()}
                  </span>
                </div>
                <button
                  onClick={() => handleRevokeDevice(d.id)}
                  className="p-1 rounded-lg text-slate-400 hover:text-red-400 hover:bg-red-500/10 transition-colors shrink-0"
                  title={t.revoke}
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </div>
            ))}
          </div>
        )}
      </div>
      </div>

      {/* Get the App — right after subscription status/devices, since "how do I
          actually connect" is the natural next question at this point in the page. */}
      <DownloadApp lang={lang} compact />

      {/* Available Regions — read-only/informational: the web dashboard never
          establishes a tunnel itself, so it has no reason to let a user pin a
          region here (that choice lives in the Desktop/Android region picker,
          which actually feeds it into node selection). Just a glance at where
          nodes are and how busy they are. */}
      {regions.length > 0 && (
        <div className="p-6 rounded-2xl bg-dark-850 border border-dark-800">
          <h2 className="text-lg font-bold tracking-tight flex items-center gap-2 mb-1">
            <Globe className="w-5 h-5 text-brand-500" />
            <span>{t.regionsTitle}</span>
          </h2>
          <p className="text-xs text-slate-500 mb-4">{t.regionsHint}</p>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
            {regions.map((r) => (
              // Keyed on the row's own key, not the region: the same country
              // appears twice when it has both our servers and P2P exits.
              <div key={r.key ?? r.region} className="flex items-center justify-between px-4 py-3 rounded-xl bg-dark-900 border border-dark-800">
                <div>
                  <p className="text-sm font-semibold">
                    {r.region}
                    {r.p2p && (
                      <span className="ml-1.5 align-middle text-[10px] font-semibold text-brand-400">{t.regionP2pBadge}</span>
                    )}
                  </p>
                  <p className="text-[11px] text-slate-500">
                    {r.nodeCount} {r.p2p ? t.regionPeerCountSuffix : t.regionNodeCountSuffix}
                  </p>
                </div>
                <span className="flex items-center gap-1.5 text-[11px] text-slate-400">
                  <span className={`w-2 h-2 rounded-full ${REGION_LOAD_DOT[r.loadLevel]}`} />
                  {r.loadLevel === 'HIGH' ? t.regionLoadHigh : r.loadLevel === 'MEDIUM' ? t.regionLoadMedium : t.regionLoadLow}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Tariffs Selection / Change — kept right under the subscription banner
          (was much further down, past the entire Devices section): this is
          the natural next question right after "what's my plan status". */}
      <div className="p-6 rounded-2xl bg-dark-850 border border-dark-800">
        <div className="flex items-center justify-between mb-6">
          <h2 className="text-lg font-bold tracking-tight flex items-center gap-2">
            <Sparkles className="w-5 h-5 text-brand-500" />
            <span>{t.tariffs}</span>
          </h2>
          <div className="flex items-center gap-1 p-0.5 rounded-lg bg-dark-800 border border-dark-700 text-xs">
            <button
              onClick={() => setIsAnnual(false)}
              className={`px-3 py-1 rounded-md transition-all ${
                !isAnnual ? 'bg-brand-500 text-dark-950 font-bold' : 'text-slate-400'
              }`}
            >
              {t.monthly}
            </button>
            <button
              onClick={() => setIsAnnual(true)}
              className={`px-3 py-1 rounded-md transition-all ${
                isAnnual ? 'bg-brand-500 text-dark-950 font-bold' : 'text-slate-400'
              }`}
            >
              {t.annual}
            </button>
          </div>
        </div>

        {purchaseError && (
          <div className="mb-4 p-3 rounded-xl bg-red-500/10 border border-red-500/20 text-red-400 text-xs flex items-center gap-2 flex-wrap">
            <AlertCircle className="w-4 h-4 shrink-0" />
            <span className="flex-1 min-w-[12rem]">{purchaseError}</span>
            {purchaseShortfallMicro != null && (
              <button
                onClick={() => {
                  setInvoiceAmount(String(Math.max(1, Math.ceil(purchaseShortfallMicro / 1_000_000))));
                  setOpenTopUp(true);
                }}
                className="px-3 py-1 rounded-lg bg-red-500/20 hover:bg-red-500/30 text-red-300 font-bold whitespace-nowrap"
              >
                {t.topUp}
              </button>
            )}
          </div>
        )}

        <div id="tariffs" className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {tariffs.map((tariff) => {
            const priceMicro = isAnnual
              ? tariff.annualPriceUsdtMicro
              : tariff.monthlyPriceUsdtMicro;
            const price = priceMicro / 1_000_000;
            const isCurrent = sub?.tariffId === tariff.id;
            const isTrial = tariff.id.toLowerCase() === 'trial';
            // Trial is one-shot server-side (BillingService.purchaseOrRenewSubscription
            // rejects any repeat activation) — hasUsedTrial stays true forever once any
            // trial subscription row has ever existed, whether that trial is currently
            // ACTIVE (with quota left) or long expired. Only the "expired, and it can
            // never be activated again" case should read as "already used" — while the
            // trial is the user's current plan it's simply active, not "used up", so a
            // stale "Активировать бесплатно" button never invites a click that can only
            // ever fail, without implying anything is wrong with the plan they're on.
            const isExpiredTrial = isTrial && !isCurrent && user.hasUsedTrial;
            const isSwitch = !!paidCurrentTariff && !isCurrent && !isTrial;
            const isDowngrade =
              isSwitch && tariff.monthlyPriceUsdtMicro < paidCurrentTariff!.monthlyPriceUsdtMicro;
            const isScheduled = isDowngrade && sub?.nextTariffId === tariff.id;
            const busy = purchasingTariffId === tariff.id;

            return (
              <div
                key={tariff.id}
                ref={(el) => {
                  tariffCardRefs.current[tariff.id] = el;
                }}
                className={`p-4 rounded-xl bg-dark-900 border flex flex-col justify-between transition-shadow ${
                  isCurrent ? 'border-emerald-500/40' : 'border-dark-800'
                } ${
                  highlightedTariffId === tariff.id
                    ? 'ring-2 ring-brand-500 ring-offset-2 ring-offset-dark-950'
                    : ''
                }`}
              >
                <div>
                  <div className="flex items-center justify-between">
                    <h4 className="font-bold text-sm">{tariff.name}</h4>
                    {isCurrent && (
                      <span className="text-[10px] px-2 py-0.5 rounded-full bg-emerald-500/10 text-emerald-400 font-bold">
                        {t.currentPlanBadge}
                      </span>
                    )}
                  </div>
                  <div className="mt-2 text-xl font-extrabold">
                    {tariff.id.toLowerCase() === 'trial' ? (
                      t.freeLabel
                    ) : (
                      <>
                        ${price.toFixed(price % 1 === 0 ? 0 : 2)}
                        <span className="text-xs font-normal text-slate-400">
                          {isAnnual ? '/yr' : '/mo'}
                        </span>
                      </>
                    )}
                  </div>
                  <p className="text-xs text-slate-400 mt-2">
                    {isTrial && `${t.trialDurationLabel} · `}
                    {Math.round(tariff.trafficQuotaBytes / (1024 * 1024 * 1024))} GB{isTrial ? '' : '/mo'} · {tariff.maxDevices} devices
                  </p>
                </div>

                {isTrial && isCurrent ? (
                  // Currently active trial with quota left — this is just their
                  // current plan (matches the emerald "Current" treatment above),
                  // not a disabled/broken control. Renewal isn't offered since the
                  // trial can't be reactivated once it ends.
                  <div className="mt-4 w-full py-2 rounded-lg text-center text-xs font-semibold text-emerald-400 border border-emerald-500/30 bg-emerald-500/5">
                    {t.trialActiveLabel}
                  </div>
                ) : isExpiredTrial || (isTrial && paidCurrentTariff) ? (
                  // Activating the trial over a running paid plan would replace
                  // it on the spot (the server refuses that too).
                  <div className="mt-4 w-full py-2 rounded-lg text-center text-xs font-semibold text-slate-500 border border-dark-800">
                    {isExpiredTrial ? t.trialAlreadyUsed : t.trialNotWithPaid}
                  </div>
                ) : isScheduled ? (
                  <div className="mt-4">
                    <div className="w-full py-2 rounded-lg text-center text-xs font-semibold text-brand-500 border border-brand-500/30">
                      {t.switchScheduled.replace('{date}', periodEndDate)}
                    </div>
                    <button
                      onClick={() => handleScheduleSwitch(null)}
                      disabled={busy}
                      className="mt-2 w-full text-[11px] text-slate-400 hover:text-slate-200 underline"
                    >
                      {busy ? 'Processing...' : t.cancelSwitch}
                    </button>
                  </div>
                ) : (
                  <div className="mt-4">
                    <button
                      onClick={() => (isDowngrade ? handleScheduleSwitch(tariff.id) : handlePurchase(tariff.id))}
                      disabled={busy}
                      className="w-full py-2 rounded-lg bg-dark-800 hover:bg-brand-500 hover:text-dark-950 text-xs font-semibold text-slate-200 transition-colors border border-dark-700"
                    >
                      {busy
                        ? 'Processing...'
                        : isCurrent
                          ? paidCurrentTariff
                            ? t.renewNow
                            : t.renewPlan
                          : isDowngrade
                            ? t.switchFrom.replace('{date}', periodEndDate)
                            : isSwitch
                              ? t.switchNow
                              : price === 0
                                ? t.activateFree
                                : t.buyWithBalance}
                    </button>
                    {isCurrent && paidCurrentTariff && sub?.autoRenew && renewalShortfallMicro === 0 && (
                      <p className="mt-2 text-[11px] text-slate-400 text-center">
                        {nextTariffName
                          ? t.autoRenewInto.replace('{date}', periodEndDate).replace('{plan}', nextTariffName)
                          : t.autoRenewOn.replace('{date}', periodEndDate)}
                      </p>
                    )}
                    {isSwitch && (
                      <p className="mt-2 text-[11px] text-slate-500 text-center">
                        {isDowngrade ? t.switchFromHint : t.switchNowHint}
                      </p>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>

      {/* Referral & Diagnostics Row */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        {/* Referral */}
        <div className="p-6 rounded-2xl bg-dark-850 border border-dark-800">
          <h3 className="font-bold text-base mb-2 flex items-center gap-2">
            <Share2 className="w-4 h-4 text-brand-500" />
            <span>{t.referralProgram}</span>
          </h3>
          <p className="text-xs text-slate-400 leading-relaxed mb-4">
            {t.referralDesc}
          </p>
          <div className="flex items-center gap-2 p-2 rounded-xl bg-dark-900 border border-dark-800 text-xs">
            <input
              type="text"
              readOnly
              value={referralWebLink}
              onFocus={(e) => e.currentTarget.select()}
              className="bg-transparent flex-1 outline-none text-slate-300 select-all"
            />
            <button
              type="button"
              onClick={handleCopyReferral}
              title={t.copyLink}
              className="p-1.5 rounded-lg bg-dark-800 hover:bg-dark-700 text-slate-300"
            >
              {copiedReferral ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
            </button>
          </div>
          <div className="mt-2 flex items-center justify-between gap-2 text-[11px]">
            <span className="text-slate-500">
              {t.referralCodeLabel}: <span className="font-mono text-slate-300">{user.referralCode}</span>
            </span>
            {referralTelegramLink && (
              <a
                href={referralTelegramLink}
                target="_blank"
                rel="noreferrer"
                className="text-brand-500 hover:underline whitespace-nowrap"
              >
                {t.referralTelegramLink}
              </a>
            )}
          </div>
          {user.referralCount !== undefined && (
            <div className="mt-3 grid grid-cols-2 gap-2 text-center">
              <div className="p-2.5 rounded-xl bg-dark-900 border border-dark-800">
                <span className="block text-[10px] text-slate-500 uppercase tracking-wider font-semibold">
                  {lang === 'ru' ? 'Друзей' : 'Friends'}
                </span>
                <span className="text-base font-bold text-white mt-0.5 block">
                  {user.referralCount}
                </span>
              </div>
              <div className="p-2.5 rounded-xl bg-dark-900 border border-dark-800">
                <span className="block text-[10px] text-slate-500 uppercase tracking-wider font-semibold">
                  {lang === 'ru' ? 'Заработано' : 'Earned'}
                </span>
                <span className="text-base font-bold text-brand-400 mt-0.5 block">
                  ${((user.referralEarningsUsdtMicro || 0) / 1_000_000).toFixed(2)}
                </span>
              </div>
            </div>
          )}
        </div>


        {/* Diagnostics */}
        <div className="p-6 rounded-2xl bg-dark-850 border border-dark-800">
          <h3 className="font-bold text-base mb-2 flex items-center gap-2">
            <HelpCircle className="w-4 h-4 text-brand-500" />
            <span>{t.diagnostics}</span>
          </h3>
          <p className="text-xs text-slate-400 leading-relaxed">
            {t.diagnosticsText}
          </p>
        </div>
      </div>

      <P2pRelaySection lang={lang} />

      {/* Billing History — moved to the bottom of the page: it's a reference
          list people scroll to occasionally, not something that needs to
          compete with plan/device management for top-of-page attention. */}
      <div className="p-6 rounded-2xl bg-dark-850 border border-dark-800">
        <h3 className="font-bold text-base mb-4 flex items-center gap-2">
          <Receipt className="w-4 h-4 text-brand-500" />
          <span>{t.billingHistory}</span>
        </h3>
        {mergedHistory.length === 0 ? (
          <p className="text-xs text-slate-500 text-center py-4">{t.noInvoicesYet}</p>
        ) : (
          <div className="divide-y divide-dark-800 max-h-[28rem] overflow-y-auto">
            {mergedHistory.map((row) => {
              if (row.kind === 'ledger') {
                const entry = row.data;
                const amount = entry.amountUsdtMicro / 1_000_000;
                const isCredit = entry.amountUsdtMicro >= 0;
                return (
                  <div key={`ledger-${entry.id}`} className="py-2.5 flex items-center justify-between text-xs gap-3">
                    <div className="min-w-0">
                      <span className={`font-semibold ${isCredit ? 'text-emerald-400' : 'text-red-400'}`}>
                        {isCredit ? '+' : ''}${amount.toFixed(2)}
                      </span>
                      <span className="text-slate-500 ml-2 truncate">{entry.description}</span>
                    </div>
                    <span className="text-slate-500 shrink-0">{new Date(entry.createdAt).toLocaleDateString()}</span>
                  </div>
                );
              }

              const inv = row.data;
              const amount = (inv.actualAmountUsdtMicro ?? inv.expectedAmountUsdtMicro) / 1_000_000;
              const statusKey = `invoiceStatus_${inv.status}` as keyof typeof t;
              const statusLabel = t[statusKey] ?? inv.status;
              const statusClass =
                inv.status === 'PENDING'
                  ? 'bg-amber-500/10 text-amber-400'
                  : 'bg-dark-800 text-slate-500';
              const isPending = inv.status === 'PENDING';
              return (
                <div
                  key={`invoice-${inv.id}`}
                  onClick={
                    isPending
                      ? () => {
                          setDepositChain(inv.chain === 'ETHEREUM' ? 'ETHEREUM' : 'TRON');
                          setInvoice({
                            id: inv.id,
                            chain: inv.chain,
                            token: 'USDT',
                            expectedAmountUsdtMicro: inv.expectedAmountUsdtMicro,
                            toleranceMinMicro: inv.toleranceMinMicro,
                            toleranceMaxMicro: inv.toleranceMaxMicro,
                            recipientAddress: inv.recipientAddress,
                            status: inv.status,
                            expiresAt: inv.expiresAt,
                          });
                          setOpenTopUp(true);
                        }
                      : undefined
                  }
                  className={`py-2.5 flex items-center justify-between text-xs ${
                    isPending ? 'cursor-pointer hover:bg-dark-800/50 -mx-2 px-2 rounded-lg' : ''
                  }`}
                  title={isPending ? t.viewPaymentDetails : undefined}
                >
                  <div>
                    <span className="font-semibold text-slate-200">${amount.toFixed(2)}</span>
                    <span className="text-slate-500 ml-2">{inv.chain}</span>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className="text-slate-500">{new Date(inv.createdAt).toLocaleDateString()}</span>
                    <span className={`px-2 py-0.5 rounded-full text-[10px] font-semibold ${statusClass}`}>
                      {statusLabel}
                    </span>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* QR Code Modal */}
      {showQr && links.length > 0 && (
        // !mt-0: this div is a sibling of the page's content sections inside
        // the parent's `space-y-8`, which puts a 2rem margin-top on every
        // non-first child — including this one, despite it being `fixed`.
        // That margin offsets the "fixed inset-0" backdrop 32px down from
        // the real viewport top, leaving a sliver of the navbar undimmed
        // (looked like the backdrop "half-covered" the navbar). Force it back to 0.
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4 !mt-0">
          <div className="bg-dark-850 border border-dark-800 rounded-3xl p-6 max-w-sm w-full flex flex-col items-center">
            <h3 className="font-bold text-base mb-4">{t.qrCode}</h3>
            <div className="p-4 bg-white rounded-2xl mb-4">
              <QRCodeSVG value={links[0]} size={200} />
            </div>
            <p className="text-[11px] text-slate-400 text-center mb-6">
              Scan with v2rayTun, Hiddify, or Happ camera to connect.
            </p>
            <button
              onClick={() => setShowQr(false)}
              className="w-full py-2.5 rounded-xl bg-dark-800 hover:bg-dark-700 text-xs font-bold text-white transition-colors"
            >
              {t.close}
            </button>
          </div>
        </div>
      )}

      {/* Top-up Modal */}
      {openTopUp && (
        // items-start (not items-center) + a real py margin: this card can grow
        // taller than the viewport (invoice details + the claim-tx form below
        // it), and centering a too-tall flex child clips its top equally off
        // both ends of the screen — the close button used to scroll out of
        // reach above the viewport with no way back to it without ESC/reload.
        // !mt-0: see the QR modal comment above — this overlay is also a
        // sibling inside the parent's `space-y-8` and inherits an unwanted
        // 2rem margin-top otherwise, which is what caused the backdrop to
        // not fully cover the navbar.
        <div className="fixed inset-0 z-50 flex items-start justify-center bg-black/70 backdrop-blur-sm p-4 py-10 overflow-y-auto !mt-0">
          <div className="bg-dark-850 border border-dark-800 rounded-3xl p-6 max-w-md w-full space-y-6">
            <div className="sticky top-0 -mt-6 -mx-6 px-6 pt-6 pb-3 bg-dark-850 rounded-t-3xl flex items-center justify-between z-10">
              <h3 className="font-bold text-base">{t.topUp}</h3>
              <button
                onClick={() => setOpenTopUp(false)}
                className="text-slate-400 hover:text-white text-xs"
              >
                ✕
              </button>
            </div>

            {/* Payment method */}
            <div>
              <label className="block text-xs text-slate-400 mb-1">{t.topUpMethodLabel}</label>
              <div className="flex gap-2">
                {(
                  [
                    { value: 'crypto' as const, label: t.topUpMethodCrypto },
                    { value: 'stars' as const, label: t.topUpMethodStars },
                    { value: 'promo' as const, label: lang === 'ru' ? 'Промокод' : 'Promo Code' },
                  ]
                ).map((opt) => (
                  <button
                    key={opt.value}
                    onClick={() => setTopUpMethod(opt.value)}
                    className={`flex-1 py-1.5 rounded-lg text-xs font-semibold border ${
                      topUpMethod === opt.value
                        ? 'bg-brand-500 text-dark-950 border-brand-500'
                        : 'bg-dark-900 border-dark-700 text-slate-300'
                    }`}
                  >
                    {opt.label}
                  </button>
                ))}
              </div>
            </div>

            {topUpMethod === 'crypto' && (
              <>
                {/* Invoice Generator */}
                <div>
                  <label className="block text-xs text-slate-400 mb-1">{t.depositNetworkLabel}</label>
                  <div className="flex flex-wrap gap-2 mb-3">
                    {(
                      [
                        { value: 'TRON' as const, label: 'TRC-20 (Tron)' },
                        { value: 'BASE' as const, label: 'Base' },
                        { value: 'ARBITRUM' as const, label: 'Arbitrum' },
                        { value: 'POLYGON' as const, label: 'Polygon' },
                        { value: 'ETHEREUM' as const, label: 'Ethereum' },
                      ]
                    ).map((opt) => (
                      <button
                        key={opt.value}
                        onClick={() => {
                          setDepositChain(opt.value);
                          setInvoice(null);
                        }}
                        className={`flex-1 min-w-[70px] py-1.5 px-2 rounded-lg text-xs font-semibold border text-center ${
                          depositChain === opt.value
                            ? 'bg-brand-500 text-dark-950 border-brand-500'
                            : 'bg-dark-900 border-dark-700 text-slate-300'
                        }`}
                      >
                        {opt.label}
                      </button>
                    ))}
                  </div>
                  <label className="block text-xs text-slate-400 mb-1">{t.depositAmountLabel}</label>
                  <div className="flex gap-2 mb-3">
                    {['1', '5', '10', '20'].map((amt) => (
                      <button
                        key={amt}
                        onClick={() => setInvoiceAmount(amt)}
                        className={`flex-1 py-1.5 rounded-lg text-xs font-semibold border ${
                          invoiceAmount === amt
                            ? 'bg-brand-500 text-dark-950 border-brand-500'
                            : 'bg-dark-900 border-dark-700 text-slate-300'
                        }`}
                      >
                        ${amt}
                      </button>
                    ))}
                  </div>
                  <button
                    onClick={handleCreateInvoice}
                    disabled={creatingInvoice}
                    className="w-full py-2.5 rounded-xl bg-brand-500 hover:bg-brand-600 disabled:opacity-40 disabled:cursor-not-allowed text-dark-950 font-bold text-xs"
                  >
                    {creatingInvoice ? t.depositGenerating : t.depositGetAddress}
                  </button>
                </div>

                {invoice && (
                  <div className="p-4 rounded-xl bg-dark-900 border border-dark-700 space-y-3">
                    <div className="flex justify-center p-2 bg-white rounded-lg">
                      <QRCodeSVG value={invoice.recipientAddress} size={140} />
                    </div>
                    <div>
                      <span className="text-[11px] text-slate-400">{t.depositExactAmount}</span>
                      <div className="text-base font-bold text-brand-400">
                        {(invoice.expectedAmountUsdtMicro / 1_000_000).toFixed(6)} USDT
                      </div>
                      <p className="text-[10px] text-slate-500 mt-1 leading-relaxed">{t.depositAmountHint}</p>
                      <span className="text-[10px] text-slate-500">
                        {t.depositAcceptableWindow} {(invoice.toleranceMinMicro / 1_000_000).toFixed(6)} - {(invoice.toleranceMaxMicro / 1_000_000).toFixed(6)}
                      </span>
                    </div>
                    <div>
                      <span className="text-[11px] text-slate-400">
                        {t.depositAddressFor.replace(
                          '{network}',
                          invoice.chain === 'TRON' ? 'TRC-20' : invoice.chain === 'ETHEREUM' ? 'ERC-20' : `${invoice.chain} (EVM)`
                        )}
                      </span>
                      <div className="text-xs font-mono break-all text-slate-200 mt-0.5">
                        {invoice.recipientAddress}
                      </div>
                    </div>
                  </div>
                )}

                {/* "I paid, here is the hash" form */}
                <form onSubmit={handleClaimTx} className="pt-4 border-t border-dark-800 space-y-3">
                  <h4 className="font-bold text-xs text-slate-300">{t.claimTxTitle}</h4>
                  <p className="text-[11px] text-slate-400 leading-relaxed">{t.claimTxDesc}</p>

                  {claimStatus && (
                    <p className="text-xs text-emerald-400">{claimStatus}</p>
                  )}
                  {claimError && (
                    <p className="text-xs text-red-400">{claimError}</p>
                  )}

                  <input
                    type="text"
                    required
                    placeholder={t.claimTxHashPlaceholder}
                    value={claimTxHash}
                    onChange={(e) => setClaimTxHash(e.target.value)}
                    className="w-full px-3 py-2 rounded-xl bg-dark-900 border border-dark-700 text-xs outline-none focus:border-brand-500"
                  />
                  <div className="flex gap-2">
                    <input
                      type="number"
                      step="0.01"
                      required
                      placeholder={t.claimAmountPlaceholder}
                      value={claimAmount}
                      onChange={(e) => setClaimAmount(e.target.value)}
                      className="w-32 px-3 py-2 rounded-xl bg-dark-900 border border-dark-700 text-xs outline-none focus:border-brand-500"
                    />
                    <button
                      type="submit"
                      className="flex-1 py-2 rounded-xl bg-dark-800 hover:bg-dark-700 text-xs font-semibold text-slate-200 border border-dark-700"
                    >
                      {t.claimBtn}
                    </button>
                  </div>
                </form>
              </>
            )}

            {topUpMethod === 'stars' && (
              <div className="space-y-4">
                <p className="text-[11px] text-slate-400 leading-relaxed">{t.starsIntro}</p>

                {!user.telegramLinked ? (
                  <div className="p-4 rounded-xl bg-dark-900 border border-dark-700 space-y-3">
                    {!telegramLinkDeepLink ? (
                      <button
                        onClick={handleConnectTelegram}
                        disabled={telegramLinkLoading}
                        className="w-full py-2.5 rounded-xl bg-brand-500 hover:bg-brand-600 text-dark-950 font-bold text-xs disabled:opacity-60"
                      >
                        {telegramLinkLoading ? '…' : t.starsConnectBtn}
                      </button>
                    ) : (
                      <>
                        <div className="flex justify-center p-2 bg-white rounded-lg">
                          <QRCodeSVG value={telegramLinkDeepLink} size={140} />
                        </div>
                        <a
                          href={telegramLinkDeepLink}
                          target="_blank"
                          rel="noreferrer"
                          className="block w-full text-center py-2.5 rounded-xl bg-brand-500 hover:bg-brand-600 text-dark-950 font-bold text-xs"
                        >
                          {t.starsOpenTelegramBtn}
                        </a>
                        <p className="text-[11px] text-slate-500 text-center">{t.starsWaitingConfirm}</p>
                      </>
                    )}
                    {telegramLinkError && (
                      <p className="text-xs text-red-400">{telegramLinkError}</p>
                    )}
                  </div>
                ) : (
                  <div className="space-y-3">
                    <p className="text-[11px] text-emerald-400">{t.starsLinkedHint}</p>
                    {telegramBotUsername ? (
                      <div className="grid grid-cols-2 gap-2">
                        {STAR_DENOMINATIONS.map((d) => (
                          <a
                            key={d.stars}
                            href={`https://t.me/${telegramBotUsername}?start=stars_${d.stars}`}
                            target="_blank"
                            rel="noreferrer"
                            className="py-2.5 rounded-lg text-xs font-semibold border bg-dark-900 border-dark-700 text-slate-200 hover:border-brand-500 text-center transition-colors"
                          >
                            {`⭐️ ${d.stars} Stars ($${d.usd.toFixed(2)})`}
                          </a>
                        ))}
                      </div>
                    ) : (
                      <p className="text-xs text-red-400">{t.starsNoBotUsername}</p>
                    )}
                  </div>
                )}
              </div>
            )}

            {topUpMethod === 'promo' && (
              <div className="space-y-4">
                <p className="text-[11px] text-slate-400 leading-relaxed">
                  {lang === 'ru'
                    ? 'Введите промокод для мгновенного начисления бонусных средств на баланс.'
                    : 'Enter your promo code to instantly credit bonus funds to your balance.'}
                </p>
                <div className="flex gap-2">
                  <input
                    type="text"
                    value={promoInput}
                    onChange={(e) => setPromoInput(e.target.value.toUpperCase())}
                    placeholder="PROMOCODE"
                    className="flex-1 px-3 py-2 rounded-xl bg-dark-900 border border-dark-700 text-xs font-mono uppercase text-white outline-none focus:border-brand-500"
                  />
                  <button
                    onClick={handleApplyPromo}
                    disabled={!promoInput.trim() || applyingPromo}
                    className="px-4 py-2 rounded-xl bg-brand-500 hover:bg-brand-600 disabled:opacity-50 text-dark-950 font-bold text-xs"
                  >
                    {applyingPromo ? '…' : (lang === 'ru' ? 'Применить' : 'Apply')}
                  </button>
                </div>
                {promoSuccess && (
                  <p className="text-xs text-emerald-400 font-medium">{promoSuccess}</p>
                )}
                {promoError && (
                  <p className="text-xs text-red-400 font-medium">{promoError}</p>
                )}
              </div>
            )}
          </div>
        </div>

      )}
    </div>
  );
};
