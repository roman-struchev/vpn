import React, { useEffect, useState } from 'react';
import { Apple, Smartphone, ArrowRight, ExternalLink, Copy, Check, Monitor } from 'lucide-react';
import { Lang, translations } from '../i18n';
import { fetchLatestRelease, releasesPageUrl, LatestRelease } from '../utils/githubRelease';

const MAC_INSTALL_COMMAND =
  'curl -fsSL https://raw.githubusercontent.com/roman-struchev/vpn/main/scripts/install-mac.sh | sh';

interface DownloadAppProps {
  lang: Lang;
  /** Compact: one row of platform buttons, no headline/subtitle — for the dashboard. */
  compact?: boolean;
}

const focusRing =
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500/70 focus-visible:ring-offset-2 focus-visible:ring-offset-dark-950';

/** One platform's download button, or a muted "coming soon" placeholder when no asset exists yet. */
const PlatformButton: React.FC<{
  icon: React.ReactNode;
  label: string;
  sublabel: string;
  href?: string;
  comingSoonLabel: string;
}> = ({ icon, label, sublabel, href, comingSoonLabel }) => {
  if (!href) {
    return (
      <div className="flex items-center gap-3 rounded-xl border border-dashed border-dark-800 bg-dark-900/40 px-4 py-3 text-slate-500">
        <span className="shrink-0">{icon}</span>
        <div className="min-w-0">
          <p className="text-sm font-semibold">{label}</p>
          <p className="text-[11px]">{comingSoonLabel}</p>
        </div>
      </div>
    );
  }
  return (
    <a
      href={href}
      className={`flex items-center gap-3 rounded-xl border border-dark-800 bg-dark-900 px-4 py-3 transition-colors hover:border-brand-500/40 hover:bg-dark-850 ${focusRing}`}
    >
      <span className="shrink-0 text-brand-500">{icon}</span>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-semibold text-white">{label}</p>
        <p className="text-[11px] text-slate-500">{sublabel}</p>
      </div>
      <ArrowRight className="h-4 w-4 shrink-0 text-slate-600" />
    </a>
  );
};

const CopyCommand: React.FC<{ command: string; label: string; copiedLabel: string }> = ({
  command,
  label,
  copiedLabel,
}) => {
  const [copied, setCopied] = useState(false);
  return (
    <button
      type="button"
      onClick={() => {
        navigator.clipboard.writeText(command).then(() => {
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        });
      }}
      className={`flex w-full items-center gap-2 rounded-lg bg-dark-950 border border-dark-800 px-3 py-2 text-left font-mono text-[11px] text-slate-300 transition-colors hover:border-brand-500/40 ${focusRing}`}
    >
      <span className="min-w-0 flex-1 truncate">{command}</span>
      <span className="flex shrink-0 items-center gap-1 text-slate-500">
        {copied ? (
          <>
            <Check className="h-3.5 w-3.5 text-brand-500" />
            {copiedLabel}
          </>
        ) : (
          <>
            <Copy className="h-3.5 w-3.5" />
            {label}
          </>
        )}
      </span>
    </button>
  );
};

export const DownloadApp: React.FC<DownloadAppProps> = ({ lang, compact }) => {
  const t = translations[lang];
  const [release, setRelease] = useState<LatestRelease | null | undefined>(undefined);

  useEffect(() => {
    let cancelled = false;
    fetchLatestRelease().then((r) => {
      if (!cancelled) setRelease(r);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const loaded = release !== undefined;
  const failed = loaded && release === null;

  const installNotes = (
    <>
      <div className="mt-3">
        <p className="mb-1.5 text-[11px] font-semibold text-slate-400">{t.downloadMacTerminalLabel}</p>
        <CopyCommand command={MAC_INSTALL_COMMAND} label={t.downloadCopyCommand} copiedLabel={t.copied} />
        <p className="mt-1.5 text-[11px] leading-relaxed text-slate-500">{t.downloadMacTerminalHint}</p>
      </div>

      <p className="mt-3 text-[11px] leading-relaxed text-slate-500">
        {t.downloadUnsignedHint}
      </p>
    </>
  );

  const body = (
    <>
      <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-3">
        <PlatformButton
          icon={<Apple className="h-5 w-5" />}
          label={t.downloadMacLabel}
          sublabel={release?.macArm64 || release?.macIntel ? t.downloadMacSublabel : ''}
          href={release?.macArm64?.browser_download_url ?? release?.macIntel?.browser_download_url}
          comingSoonLabel={t.downloadComingSoon}
        />
        <PlatformButton
          icon={<Smartphone className="h-5 w-5" />}
          label={t.downloadAndroidLabel}
          sublabel={t.downloadAndroidSublabel}
          href={release?.apk?.browser_download_url}
          comingSoonLabel={t.downloadComingSoon}
        />
        <PlatformButton
          icon={<Monitor className="h-5 w-5" />}
          label={t.downloadWindowsLabel}
          sublabel={t.downloadWindowsSublabel}
          href={release?.windows?.browser_download_url}
          comingSoonLabel={t.downloadComingSoon}
        />
      </div>

      {release?.macIntel && release?.macArm64 && (
        <p className="mt-2 text-[11px] text-slate-500">
          {t.downloadMacIntelHint}{' '}
          <a
            href={release.macIntel.browser_download_url}
            className={`text-brand-500 hover:underline ${focusRing}`}
          >
            {t.downloadMacIntelLink}
          </a>
        </p>
      )}

      {compact ? (
        // A signed-in user has usually installed the app already, so on the
        // dashboard the install notes stay folded instead of taking half the card.
        <details className="mt-3 group">
          <summary className={`cursor-pointer select-none text-[11px] font-semibold text-slate-400 hover:text-slate-300 ${focusRing}`}>
            {t.downloadInstallHelp}
          </summary>
          {installNotes}
        </details>
      ) : (
        installNotes
      )}

      {!compact && (
        <p className="mt-4 text-[11px] leading-relaxed text-slate-500">
          {t.downloadOtherClientsHint}
        </p>
      )}

      {(failed || !compact) && (
        <a
          href={releasesPageUrl()}
          target="_blank"
          rel="noreferrer"
          className={`mt-3 inline-flex items-center gap-1.5 text-xs font-semibold text-brand-500 hover:underline ${focusRing}`}
        >
          {t.downloadAllReleasesLink}
          <ExternalLink className="h-3 w-3" />
        </a>
      )}
    </>
  );

  if (compact) {
    return (
      <div className="p-6 rounded-2xl bg-dark-850 border border-dark-800">
        <h2 className="mb-1 flex items-center gap-2 text-lg font-bold tracking-tight">
          <Smartphone className="h-5 w-5 text-brand-500" />
          <span>{t.downloadTitle}</span>
        </h2>
        <p className="mb-4 text-xs text-slate-500">{t.downloadHint}</p>
        {body}
      </div>
    );
  }

  return (
    <section className="w-full px-5 py-12 sm:py-16">
      <div className="mx-auto max-w-3xl">
        <div className="mb-8 text-center">
          <h2 className="text-2xl font-bold tracking-tight sm:text-3xl">{t.downloadTitle}</h2>
          <p className="mx-auto mt-3 max-w-md text-sm leading-relaxed text-slate-400">{t.downloadHint}</p>
        </div>
        <div className="rounded-2xl border border-dark-800 bg-dark-850/50 p-6 sm:p-7">{body}</div>
      </div>
    </section>
  );
};
