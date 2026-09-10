import React, { useState } from 'react';
import { Shield, Zap, Lock, Smartphone, Check, ArrowRight } from 'lucide-react';
import { Lang, translations } from '../i18n';
import { Tariff } from '../types';

interface LandingViewProps {
  lang: Lang;
  tariffs: Tariff[];
  onGetStarted: () => void;
}

export const LandingView: React.FC<LandingViewProps> = ({
  lang,
  tariffs,
  onGetStarted,
}) => {
  const t = translations[lang];
  const [isAnnual, setIsAnnual] = useState(false);

  return (
    <div className="flex flex-col items-center">
      {/* Hero Section */}
      <section className="relative w-full max-w-5xl mx-auto px-4 pt-20 pb-16 text-center flex flex-col items-center">
        <div className="inline-flex items-center gap-2 px-3.5 py-1.5 rounded-full bg-brand-500/10 border border-brand-500/20 text-brand-500 text-xs font-semibold mb-6">
          <Shield className="w-3.5 h-3.5" />
          <span>XHTTP + Reality · Anti-DPI Protocol</span>
        </div>

        <h1 className="text-4xl sm:text-6xl font-extrabold tracking-tight max-w-3xl leading-[1.15] bg-gradient-to-b from-white via-slate-100 to-slate-400 bg-clip-text text-transparent">
          {t.tagline}
        </h1>

        <p className="mt-6 text-base sm:text-lg text-slate-400 max-w-2xl leading-relaxed">
          {t.subtagline}
        </p>

        <div className="mt-8 flex flex-col sm:flex-row gap-3">
          <button
            onClick={onGetStarted}
            className="flex items-center justify-center gap-2 px-6 py-3 rounded-xl bg-brand-500 hover:bg-brand-600 text-dark-950 font-bold text-sm transition-all shadow-xl shadow-brand-500/20 active:scale-[0.98]"
          >
            <span>{t.getStarted}</span>
            <ArrowRight className="w-4 h-4" />
          </button>
        </div>
      </section>

      {/* Features Grid */}
      <section className="w-full max-w-6xl mx-auto px-4 py-16 border-t border-dark-800">
        <h2 className="text-2xl font-bold text-center mb-12 tracking-tight">
          {t.featuresTitle}
        </h2>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
          <div className="p-6 rounded-2xl bg-dark-850 border border-dark-800/80 flex flex-col">
            <div className="w-10 h-10 rounded-xl bg-emerald-500/10 border border-emerald-500/20 flex items-center justify-center text-emerald-400 mb-4">
              <Zap className="w-5 h-5" />
            </div>
            <h3 className="font-semibold text-base mb-2">{t.feature1Title}</h3>
            <p className="text-xs text-slate-400 leading-relaxed">{t.feature1Desc}</p>
          </div>

          <div className="p-6 rounded-2xl bg-dark-850 border border-dark-800/80 flex flex-col">
            <div className="w-10 h-10 rounded-xl bg-blue-500/10 border border-blue-500/20 flex items-center justify-center text-blue-400 mb-4">
              <Shield className="w-5 h-5" />
            </div>
            <h3 className="font-semibold text-base mb-2">{t.feature2Title}</h3>
            <p className="text-xs text-slate-400 leading-relaxed">{t.feature2Desc}</p>
          </div>

          <div className="p-6 rounded-2xl bg-dark-850 border border-dark-800/80 flex flex-col">
            <div className="w-10 h-10 rounded-xl bg-amber-500/10 border border-amber-500/20 flex items-center justify-center text-amber-400 mb-4">
              <Lock className="w-5 h-5" />
            </div>
            <h3 className="font-semibold text-base mb-2">{t.feature3Title}</h3>
            <p className="text-xs text-slate-400 leading-relaxed">{t.feature3Desc}</p>
          </div>

          <div className="p-6 rounded-2xl bg-dark-850 border border-dark-800/80 flex flex-col">
            <div className="w-10 h-10 rounded-xl bg-purple-500/10 border border-purple-500/20 flex items-center justify-center text-purple-400 mb-4">
              <Smartphone className="w-5 h-5" />
            </div>
            <h3 className="font-semibold text-base mb-2">{t.feature4Title}</h3>
            <p className="text-xs text-slate-400 leading-relaxed">{t.feature4Desc}</p>
          </div>
        </div>
      </section>

      {/* Pricing / Tariffs */}
      <section className="w-full max-w-5xl mx-auto px-4 py-16 border-t border-dark-800">
        <div className="text-center mb-10">
          <h2 className="text-2xl font-bold tracking-tight">{t.tariffs}</h2>
          {/* Period Toggle */}
          <div className="inline-flex items-center gap-1 mt-5 p-1 rounded-xl bg-dark-850 border border-dark-800">
            <button
              onClick={() => setIsAnnual(false)}
              className={`px-4 py-1.5 rounded-lg text-xs font-semibold transition-all ${
                !isAnnual
                  ? 'bg-brand-500 text-dark-950 shadow-sm'
                  : 'text-slate-400 hover:text-white'
              }`}
            >
              {t.monthly}
            </button>
            <button
              onClick={() => setIsAnnual(true)}
              className={`px-4 py-1.5 rounded-lg text-xs font-semibold transition-all ${
                isAnnual
                  ? 'bg-brand-500 text-dark-950 shadow-sm'
                  : 'text-slate-400 hover:text-white'
              }`}
            >
              {t.annual}
            </button>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          {tariffs.map((tariff) => {
            const priceMicro = isAnnual
              ? tariff.annualPriceUsdtMicro
              : tariff.monthlyPriceUsdtMicro;
            const priceUsdt = priceMicro / 1_000_000;
            const quotaGb = Math.round(tariff.trafficQuotaBytes / (1024 * 1024 * 1024));

            return (
              <div
                key={tariff.id}
                className={`relative p-6 rounded-2xl bg-dark-850 border flex flex-col justify-between ${
                  tariff.id === 'pro'
                    ? 'border-brand-500/50 shadow-xl shadow-brand-500/5'
                    : 'border-dark-800'
                }`}
              >
                {tariff.id === 'pro' && (
                  <span className="absolute -top-3 left-1/2 -translate-x-1/2 px-3 py-0.5 rounded-full bg-brand-500 text-dark-950 text-[10px] font-bold uppercase tracking-wider">
                    Popular
                  </span>
                )}

                <div>
                  <h3 className="font-bold text-lg">{tariff.name}</h3>
                  <div className="mt-4 flex items-baseline gap-1">
                    <span className="text-3xl font-extrabold tracking-tight">
                      ${priceUsdt.toFixed(priceUsdt % 1 === 0 ? 0 : 2)}
                    </span>
                    <span className="text-xs text-slate-400">
                      {isAnnual ? '/ year' : '/ month'}
                    </span>
                  </div>

                  <ul className="mt-6 space-y-3 text-xs text-slate-300">
                    <li className="flex items-center gap-2">
                      <Check className="w-4 h-4 text-brand-500 shrink-0" />
                      <span>{quotaGb} GB high-speed traffic</span>
                    </li>
                    <li className="flex items-center gap-2">
                      <Check className="w-4 h-4 text-brand-500 shrink-0" />
                      <span>Up to {tariff.maxDevices} simultaneous devices</span>
                    </li>
                    <li className="flex items-center gap-2">
                      <Check className="w-4 h-4 text-brand-500 shrink-0" />
                      <span>Priority pool: {tariff.serverPool}</span>
                    </li>
                    <li className="flex items-center gap-2">
                      <Check className="w-4 h-4 text-brand-500 shrink-0" />
                      <span>Auto-renewal from balance</span>
                    </li>
                  </ul>
                </div>

                <button
                  onClick={onGetStarted}
                  className={`mt-8 w-full py-2.5 rounded-xl text-xs font-bold transition-all ${
                    tariff.id === 'pro'
                      ? 'bg-brand-500 hover:bg-brand-600 text-dark-950 shadow-md shadow-brand-500/10'
                      : 'bg-dark-800 hover:bg-dark-700 text-white'
                  }`}
                >
                  {t.choosePlan}
                </button>
              </div>
            );
          })}
        </div>
      </section>
    </div>
  );
};
