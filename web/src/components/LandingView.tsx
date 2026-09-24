import React, { useEffect, useState } from 'react';
import {
  ArrowRight,
  Check,
  ChevronDown,
  EyeOff,
  Lock,
  Smartphone,
  Zap,
} from 'lucide-react';
import { Lang, translations } from '../i18n';
import { Tariff } from '../types';
import { DownloadApp } from './DownloadApp';

interface LandingViewProps {
  lang: Lang;
  tariffs: Tariff[];
  /**
   * Arrived via <origin>/#tariffs signed out — the "traffic is running out"
   * Telegram message, or a client handoff whose code had expired. The plans
   * here are #pricing, so the browser's own anchor jump never finds them.
   */
  scrollToPricing?: boolean;
  /**
   * Called with the clicked tariff's id when a visitor uses a specific plan
   * card's "Choose Plan" button, so that choice survives signup instead of
   * being dropped (see App.tsx / AuthModal's initialTariffId / DashboardView's
   * highlightTariffId). Called with no argument from the generic hero/closing
   * CTAs, which aren't tied to any one plan. See UX_REVIEW.md Quick Win #9.
   */
  onGetStarted: (tariffId?: string) => void;
}

/**
 * "1 устройство / 2 устройства / 5 устройств" — a plain `${n} устройств` reads
 * as broken Russian on the two cheapest plans, which are exactly the cards most
 * visitors look at first.
 */
const deviceWord = (lang: Lang, n: number): string => {
  if (lang === 'en') return n === 1 ? 'device' : 'devices';
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return 'устройство';
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return 'устройства';
  return 'устройств';
};

const focusRing =
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500/70 focus-visible:ring-offset-2 focus-visible:ring-offset-dark-900';

/** Small pill of evidence under the hero CTA — a claim you can read at a glance. */
const ProofChip: React.FC<{ icon: React.ReactNode; label: string }> = ({ icon, label }) => (
  <span className="inline-flex items-center gap-2 rounded-full border border-dark-800 bg-dark-850/70 px-3.5 py-1.5 text-xs font-medium text-slate-300">
    <span className="text-brand-500">{icon}</span>
    {label}
  </span>
);

/** One node of the "what the censor actually sees" strip. */
const FlowNode: React.FC<{ label: string; muted?: boolean }> = ({ label, muted }) => (
  <span
    className={`shrink-0 rounded-lg border px-3 py-2 text-center text-[11px] font-medium ${
      muted
        ? 'border-dashed border-dark-800 bg-dark-900 text-slate-500'
        : 'border-brand-500/30 bg-brand-500/[0.07] text-brand-500'
    }`}
  >
    {label}
  </span>
);

/**
 * The wire between two flow nodes. Vertical while the strip is stacked on a
 * phone, horizontal once the three nodes fit on one line — the alternative
 * (a horizontally scrolling strip) hid the third node until you dragged it.
 */
const FlowWire: React.FC = () => (
  <span
    aria-hidden="true"
    className="mx-auto h-4 w-px shrink-0 bg-brand-500/30 sm:mx-0 sm:h-px sm:w-auto sm:flex-1 sm:bg-gradient-to-r sm:from-brand-500/40 sm:via-brand-500/20 sm:to-brand-500/40"
  />
);

export const LandingView: React.FC<LandingViewProps> = ({
  lang,
  tariffs,
  scrollToPricing,
  onGetStarted,
}) => {
  const t = translations[lang];
  const [isAnnual, setIsAnnual] = useState(false);

  useEffect(() => {
    if (!scrollToPricing) return;
    document.getElementById('pricing')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }, [scrollToPricing]);

  const steps = [
    { title: t.step1Title, desc: t.step1Desc },
    { title: t.step2Title, desc: t.step2Desc },
    { title: t.step3Title, desc: t.step3Desc },
  ];

  const techRows = [
    { label: t.techProtocolLabel, text: t.techProtocolText },
    { label: t.techLogsLabel, text: t.techLogsText },
    { label: t.techResilienceLabel, text: t.techResilienceText },
    { label: t.techClientsLabel, text: t.techClientsText },
  ];

  return (
    <div className="flex flex-col items-center">
      {/* ---------------------------------------------------------------- Hero */}
      <section className="relative w-full overflow-hidden px-5 pt-16 pb-10 sm:pt-24 sm:pb-12">
        {/* The page's single decorative flourish: one soft brand-coloured dawn
            behind the headline. Everything else on the page stays quiet so the
            CTA is the brightest thing on screen. */}
        <div
          aria-hidden="true"
          className="pointer-events-none absolute inset-x-0 top-0 h-[420px] bg-[radial-gradient(ellipse_55%_60%_at_50%_-10%,rgba(34,197,94,0.16),transparent_70%)]"
        />

        <div className="relative mx-auto flex max-w-3xl flex-col items-center text-center">
          <span className="mb-7 inline-flex items-center gap-2 rounded-full border border-brand-500/25 bg-brand-500/10 px-3.5 py-1.5 text-[11px] font-semibold uppercase tracking-[0.16em] text-brand-500">
            {t.heroEyebrow}
          </span>

          <h1 className="bg-gradient-to-b from-white via-white to-slate-400 bg-clip-text text-[2.6rem] font-extrabold leading-[0.98] tracking-[-0.035em] text-transparent [text-wrap:balance] sm:text-6xl lg:text-[4.5rem]">
            {t.tagline}
          </h1>

          <p className="mt-6 max-w-xl text-base leading-relaxed text-slate-400 sm:text-lg">
            {t.subtagline}
          </p>

          <div className="mt-9 flex w-full flex-col items-center gap-3 sm:w-auto sm:flex-row">
            <button
              onClick={() => onGetStarted()}
              className={`flex w-full items-center justify-center gap-2 rounded-xl bg-brand-500 px-7 py-3.5 text-sm font-bold text-dark-950 shadow-xl shadow-brand-500/20 transition-all hover:bg-brand-600 active:scale-[0.98] sm:w-auto ${focusRing}`}
            >
              <span>{t.getStarted}</span>
              <ArrowRight className="h-4 w-4" />
            </button>
            <a
              href="#pricing"
              className={`flex w-full items-center justify-center rounded-xl border border-dark-800 bg-dark-850/60 px-7 py-3.5 text-sm font-semibold text-slate-300 transition-colors hover:border-brand-500/40 hover:text-white sm:w-auto ${focusRing}`}
            >
              {t.seePricing}
            </a>
          </div>

          <div className="mt-8 flex flex-wrap items-center justify-center gap-2">
            <ProofChip icon={<EyeOff className="h-3.5 w-3.5" />} label={t.heroProof1} />
            <ProofChip icon={<Zap className="h-3.5 w-3.5" />} label={t.heroProof2} />
            <ProofChip icon={<Smartphone className="h-3.5 w-3.5" />} label={t.heroProof3} />
          </div>
        </div>
      </section>

      {/* ------------------------------------------------------- How it works */}
      <section className="w-full px-5 py-12 sm:py-16">
        <div className="mx-auto max-w-5xl">
          <h2 className="mb-10 text-center text-2xl font-bold tracking-tight sm:text-3xl">
            {t.stepsTitle}
          </h2>

          {/* Numbered because this genuinely is a sequence — you cannot connect
              before you have an account. No rules-and-cards chrome: a hairline
              per column is enough structure. */}
          <ol className="grid grid-cols-1 gap-8 sm:grid-cols-3 sm:gap-10">
            {steps.map((step, i) => (
              <li key={step.title} className="border-t border-dark-800 pt-5">
                <span className="block text-3xl font-extrabold tabular-nums tracking-tight text-brand-500/35">
                  {i + 1}
                </span>
                <h3 className="mt-3 text-base font-semibold text-white">{step.title}</h3>
                <p className="mt-2 max-w-[34ch] text-sm leading-relaxed text-slate-400">
                  {step.desc}
                </p>
              </li>
            ))}
          </ol>
        </div>
      </section>

      {/* --------------------------------------------------- Download the app */}
      <DownloadApp lang={lang} />

      {/* ------------------------------------------------------------ Pricing */}
      <section id="pricing" className="w-full scroll-mt-20 px-5 py-12 sm:py-16">
        <div className="mx-auto max-w-5xl">
          <div className="mb-10 text-center">
            <h2 className="text-2xl font-bold tracking-tight sm:text-3xl">{t.pricingTitle}</h2>
            <p className="mx-auto mt-3 max-w-md text-sm leading-relaxed text-slate-400">
              {t.pricingSubtitle}
            </p>

            <div
              role="group"
              className="mt-6 inline-flex items-center gap-1 rounded-xl border border-dark-800 bg-dark-850 p-1"
            >
              <button
                onClick={() => setIsAnnual(false)}
                aria-pressed={!isAnnual}
                className={`rounded-lg px-4 py-1.5 text-xs font-semibold transition-all ${focusRing} ${
                  !isAnnual ? 'bg-brand-500 text-dark-950 shadow-sm' : 'text-slate-400 hover:text-white'
                }`}
              >
                {t.monthly}
              </button>
              <button
                onClick={() => setIsAnnual(true)}
                aria-pressed={isAnnual}
                className={`rounded-lg px-4 py-1.5 text-xs font-semibold transition-all ${focusRing} ${
                  isAnnual ? 'bg-brand-500 text-dark-950 shadow-sm' : 'text-slate-400 hover:text-white'
                }`}
              >
                {t.annual}
              </button>
            </div>
          </div>

          <div className="grid grid-cols-1 gap-5 md:grid-cols-3">
            {tariffs.map((tariff) => {
              const isTrial = tariff.id.toLowerCase() === 'trial';
              const isFeatured = tariff.id === 'pro';
              const priceMicro = isAnnual
                ? tariff.annualPriceUsdtMicro
                : tariff.monthlyPriceUsdtMicro;
              const priceUsdt = priceMicro / 1_000_000;
              const quotaGb = Math.round(tariff.trafficQuotaBytes / (1024 * 1024 * 1024));
              const devices = tariff.maxDevices;

              const perks = [
                t.planTraffic.replace('{n}', String(quotaGb)),
                devices === 1
                  ? `1 ${deviceWord(lang, 1)}`
                  : `${devices} ${deviceWord(lang, devices)} ${t.planDevicesSuffix}`,
                isTrial ? t.planTrialPerk : t.planPaidPerk,
              ];

              return (
                <div
                  key={tariff.id}
                  className={`relative flex flex-col rounded-2xl p-6 sm:p-7 ${
                    isFeatured
                      ? 'border border-brand-500/40 bg-dark-850 shadow-[0_0_70px_-25px_rgba(34,197,94,0.55)]'
                      : 'border border-dark-800 bg-dark-850/50'
                  }`}
                >
                  {isFeatured && (
                    <span className="absolute -top-2.5 left-6 rounded-full bg-brand-500 px-2.5 py-0.5 text-[10px] font-bold uppercase tracking-wider text-dark-950">
                      {t.popularBadge}
                    </span>
                  )}

                  <h3 className="text-sm font-semibold uppercase tracking-[0.14em] text-slate-400">
                    {tariff.name}
                  </h3>

                  <div className="mt-4 flex items-baseline gap-1.5">
                    {isTrial ? (
                      <span className="text-4xl font-extrabold tracking-tight text-white">
                        {t.freeLabel}
                      </span>
                    ) : (
                      <>
                        <span className="text-4xl font-extrabold tabular-nums tracking-tight text-white">
                          ${priceUsdt.toFixed(priceUsdt % 1 === 0 ? 0 : 2)}
                        </span>
                        <span className="text-sm text-slate-500">
                          {isAnnual ? t.perYear : t.perMonth}
                        </span>
                      </>
                    )}
                  </div>

                  <ul className="mt-6 space-y-3 text-sm text-slate-300">
                    {perks.map((perk) => (
                      <li key={perk} className="flex items-start gap-2.5">
                        <Check className="mt-0.5 h-4 w-4 shrink-0 text-brand-500" />
                        <span>{perk}</span>
                      </li>
                    ))}
                  </ul>

                  <button
                    onClick={() => onGetStarted(tariff.id)}
                    className={`mt-8 w-full rounded-xl py-3 text-sm font-bold transition-all active:scale-[0.99] ${focusRing} ${
                      isFeatured
                        ? 'bg-brand-500 text-dark-950 shadow-md shadow-brand-500/10 hover:bg-brand-600'
                        : 'bg-dark-800 text-white hover:bg-brand-500/10 hover:text-brand-500'
                    }`}
                  >
                    {t.choosePlan}
                  </button>
                </div>
              );
            })}
          </div>
        </div>
      </section>

      {/* -------------------------------------------- For the technically curious */}
      <section className="w-full px-5 py-4 sm:py-8">
        <details className="group mx-auto max-w-3xl rounded-2xl border border-dark-800 bg-dark-850/40 transition-colors open:bg-dark-850/70">
          <summary
            className={`flex cursor-pointer list-none items-center justify-between gap-4 rounded-2xl p-5 sm:p-6 [&::-webkit-details-marker]:hidden ${focusRing}`}
          >
            <span className="flex items-center gap-3">
              <Lock className="h-4 w-4 shrink-0 text-brand-500" />
              <span>
                <span className="block text-sm font-semibold text-white sm:text-base">
                  {t.techTitle}
                </span>
                <span className="mt-0.5 block text-xs text-slate-500">{t.techHint}</span>
              </span>
            </span>
            <ChevronDown className="h-4 w-4 shrink-0 text-slate-500 transition-transform duration-200 group-open:rotate-180" />
          </summary>

          <div className="px-5 pb-6 sm:px-6 sm:pb-7">
            {/* The one diagram on the page: what the filtering box actually sees.
                Says in a glance what a paragraph of protocol names cannot. */}
            <div className="rounded-xl border border-dark-800 bg-dark-900/60 p-4">
              <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:gap-3">
                <FlowNode label={t.techFlowYou} />
                <FlowWire />
                <FlowNode label={t.techFlowDpi} muted />
                <FlowWire />
                <FlowNode label={t.techFlowNet} />
              </div>
              <p className="mt-3 text-center text-[11px] leading-relaxed text-slate-500">
                {t.techFlowCaption}
              </p>
            </div>

            <dl className="mt-6 grid grid-cols-1 gap-x-8 gap-y-5 sm:grid-cols-[130px_1fr]">
              {techRows.map((row) => (
                <React.Fragment key={row.label}>
                  <dt className="text-[11px] font-semibold uppercase tracking-[0.16em] text-slate-500 sm:pt-0.5">
                    {row.label}
                  </dt>
                  <dd className="max-w-[62ch] text-sm leading-relaxed text-slate-400">
                    {row.text}
                  </dd>
                </React.Fragment>
              ))}
            </dl>
          </div>
        </details>
      </section>

      {/* ----------------------------------------------------------- Closing CTA */}
      <section className="w-full px-5 py-12 sm:py-16">
        <div className="relative mx-auto max-w-3xl overflow-hidden rounded-3xl border border-dark-800 bg-dark-850/50 px-6 py-12 text-center sm:px-12">
          <div
            aria-hidden="true"
            className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_60%_80%_at_50%_120%,rgba(34,197,94,0.14),transparent_70%)]"
          />
          <div className="relative">
            <h2 className="text-2xl font-bold tracking-tight [text-wrap:balance] sm:text-3xl">
              {t.finalCtaTitle}
            </h2>
            <p className="mx-auto mt-3 max-w-sm text-sm leading-relaxed text-slate-400">
              {t.finalCtaText}
            </p>
            <button
              onClick={() => onGetStarted()}
              className={`mt-7 inline-flex items-center justify-center gap-2 rounded-xl bg-brand-500 px-7 py-3.5 text-sm font-bold text-dark-950 shadow-xl shadow-brand-500/20 transition-all hover:bg-brand-600 active:scale-[0.98] ${focusRing}`}
            >
              <span>{t.getStarted}</span>
              <ArrowRight className="h-4 w-4" />
            </button>
          </div>
        </div>
      </section>
    </div>
  );
};
