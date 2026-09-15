import React from 'react';
import { ArrowLeft, AlertTriangle } from 'lucide-react';
import { Lang, translations } from '../i18n';

interface P2pRelayTermsPageProps {
  lang: Lang;
  onBack: () => void;
}

interface Section {
  heading: string;
  body: string[];
}

interface Content {
  title: string;
  updated: string;
  intro: string;
  sections: Section[];
}

// Long-form legal/risk prose deliberately lives here rather than in i18n.ts's
// flat single-line string table (see i18n.ts's own convention) — this is a
// one-off document, not repeated UI chrome, and structuring it as sections
// keeps it readable to edit/review (e.g. by actual counsel) without hunting
// through a giant single translations object.
//
// Drafted by the assistant at the repo owner's explicit request, understood
// on both sides to need real legal review before the P2P relay feature has a
// general-availability launch — see the DRAFT banner rendered below and
// docs/research/P2P_RELAY_FEASIBILITY.md, which this content builds on
// (including the Tor-relay / Psiphon Conduit comparison in its own §5).
const CONTENT: Record<Lang, Content> = {
  ru: {
    title: 'Условия и риски режима ретрансляции (P2P)',
    updated: 'Черновик от 2026-09-15',
    intro:
      'Режим ретрансляции — необязательная функция: включив её в приложении Desktop или Android, вы разрешаете вашему устройству временно выступать посредником, который помогает другим пользователям Aura VPN подключаться, когда прямое соединение с обычным сервером недоступно. За это на ваш аккаунт начисляется дополнительный трафик.',
    sections: [
      {
        heading: '1. Что именно происходит с вашим устройством',
        body: [
          'Когда режим ретрансляции включён, ваше устройство регистрируется как узел ретрансляции и устанавливает прямое P2P-соединение (WebRTC) с устройством другого пользователя. Через это соединение проходят опаковые зашифрованные байты его VPN-трафика — ваше устройство их не расшифровывает и не может прочитать содержимое.',
          'Ваше устройство никогда не становится "конечной точкой" интернета для чужого трафика: оно только передаёт зашифрованные данные дальше, к настоящему серверу Aura VPN. В этом отличие от классических residential-прокси (например, скандальной истории Hola VPN/Luminati), где чужой трафик выходил бы в открытый интернет напрямую с вашего IP-адреса как с финальной точки.',
        ],
      },
      {
        heading: '2. Защита вашей домашней сети (destination-ACL)',
        body: [
          'Приложение обязано отклонять любую попытку ретранслировать трафик на приватные, локальные или loopback-адреса (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8, 169.254.0.0/16 и аналогичные диапазоны IPv6) — то есть ваше устройство физически не может быть использовано как точка входа в ваш собственный домашний или офисный роутер, принтер, NAS и другие устройства локальной сети.',
          'Эта защита снижает, но не устраняет полностью, все возможные риски — см. раздел 4 ниже.',
        ],
      },
      {
        heading: '3. Начисление трафика',
        body: [
          '1 ГБ ретранслированного трафика → 0,5 ГБ зачисляется на лимит вашей текущей подписки (в том числе на бесплатном пробном тарифе). Зачисление происходит только после того, как оба устройства — ваше и подключающегося пользователя — независимо друг от друга подтвердят одинаковый (в пределах ±5%) объём переданных данных; это защита от накрутки в одну сторону.',
          'Действует суточный лимит начислений: не более 50 ГБ в сутки на аккаунт, независимо от количества устройств.',
          'Начисленный трафик добавляется напрямую к лимиту трафика вашей подписки и доступен сразу — отдельного "кошелька" не создаётся.',
        ],
      },
      {
        heading: '4. Риски, которые вы принимаете',
        body: [
          'Как и при запуске любого сетевого узла-ретранслятора (по духу это похоже на узел Tor или Psiphon Conduit), в теории существует нежелательный сценарий: кто-то попытается использовать чужой трафик, прошедший через ваш IP-адрес, для противоправных действий, и формально источником сетевого запроса на промежуточном шаге будет выглядеть ваше устройство/IP. Мы не можем полностью исключить такой сценарий ни для одной технологии ретрансляции.',
          'Мы существенно снижаем этот риск: (а) трафик доступен только как посредник к настоящим серверам Aura VPN, а не как открытый выход в интернет; (б) destination-ACL (раздел 2) блокирует атаки на вашу собственную сеть; (в) функция полностью добровольна и включается только зарегистрированным (не гостевым) пользователем, что оставляет возможность расследования при необходимости.',
          'Тем не менее, в зависимости от вашей юрисдикции и провайдера интернета, само по себе участие в ретрансляции стороннего сетевого трафика может нести определённые репутационные или (в редких случаях) юридические последствия. Если вы не готовы принять этот риск — просто не включайте режим ретрансляции; он не требуется для обычного использования VPN и никак на него не влияет.',
        ],
      },
      {
        heading: '5. Согласие и отзыв',
        body: [
          'Функция полностью добровольная: вы явно соглашаетесь с этими условиями перед первым включением, можете отключить режим ретрансляции в любой момент, и это не влияет на вашу подписку или доступ к обычному VPN-сервису.',
          'Режим недоступен для гостевых/пробных профилей без реального способа связи (почта, Telegram, Google) — это осознанное решение, обеспечивающее базовую подотчётность участников.',
        ],
      },
    ],
  },
  en: {
    title: 'Relay Mode (P2P) — Terms & Risks',
    updated: 'Draft as of 2026-09-15',
    intro:
      'Relay mode is an optional feature: turning it on in the Desktop or Android app lets your device temporarily act as an intermediary that helps other Aura VPN users connect when a direct connection to a regular server isn’t available. In exchange, extra traffic is credited to your account.',
    sections: [
      {
        heading: '1. What actually happens on your device',
        body: [
          'When relay mode is on, your device registers as a relay node and opens a direct peer-to-peer (WebRTC) connection to another user’s device. Opaque, encrypted bytes of that user’s VPN traffic flow through this connection — your device never decrypts it and cannot read its contents.',
          'Your device never becomes the "final" internet endpoint for someone else’s traffic: it only forwards encrypted data onward to a real Aura VPN server. This is the key difference from classic residential-proxy schemes (such as the Hola VPN/Luminati controversy), where someone else’s traffic would exit directly to the open internet from your IP address as the final hop.',
        ],
      },
      {
        heading: '2. Protecting your home network (destination ACL)',
        body: [
          'The app is required to reject any attempt to relay traffic to private, local, or loopback addresses (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8, 169.254.0.0/16, and the equivalent IPv6 ranges) — meaning your device cannot physically be used as an entry point into your own home or office router, printer, NAS, or other devices on your local network.',
          'This protection reduces, but does not fully eliminate, every conceivable risk — see section 4 below.',
        ],
      },
      {
        heading: '3. How credit is calculated',
        body: [
          '1 GB of relayed traffic → 0.5 GB is credited toward your current subscription’s traffic limit (including on the free trial tariff). Credit is only issued once both devices — yours and the connecting user’s — independently confirm a matching (within ±5%) amount of data transferred; this guards against either side padding its own number.',
          'A daily cap applies: no more than 50 GB credited per account per day, regardless of how many devices you use.',
          'Credited traffic is added directly to your subscription’s traffic limit and is usable immediately — there is no separate "wallet."',
        ],
      },
      {
        heading: '4. Risks you’re accepting',
        body: [
          'As with running any network relay node (in spirit, similar to a Tor relay or a Psiphon Conduit node), there is a theoretical scenario where someone misuses traffic that passed through your IP address at an intermediate hop for something abusive, and your device/IP could formally appear as the source of that network request at that hop. We cannot fully eliminate this risk for any relay technology.',
          'We meaningfully reduce it by: (a) your device only ever bridges traffic to real Aura VPN servers, never acting as an open exit to the wider internet; (b) the destination ACL (section 2) blocks attacks on your own network; (c) the feature is fully opt-in and only available to a real, non-guest account, which preserves the ability to investigate if something ever needs to be looked into.',
          'That said, depending on your jurisdiction and internet provider, participating in relaying someone else’s network traffic may carry some reputational or, in rare cases, legal exposure. If you’re not comfortable with that, simply don’t turn relay mode on — it isn’t required for normal VPN use and has no effect on it either way.',
        ],
      },
      {
        heading: '5. Consent and revocation',
        body: [
          'The feature is entirely opt-in: you explicitly agree to these terms before first enabling it, you can turn relay mode off at any time, and doing so has no effect on your subscription or regular VPN access.',
          'The feature isn’t available to guest/device-trial profiles with no real contact method (email, Telegram, or Google) — a deliberate choice to keep a basic level of accountability among participants.',
        ],
      },
    ],
  },
};

export const P2pRelayTermsPage: React.FC<P2pRelayTermsPageProps> = ({ lang, onBack }) => {
  const t = translations[lang];
  const content = CONTENT[lang];

  return (
    <div className="w-full max-w-3xl mx-auto px-4 py-8">
      <button
        onClick={onBack}
        className="flex items-center gap-1.5 text-xs font-medium text-slate-400 hover:text-slate-200 transition-colors mb-6"
      >
        <ArrowLeft className="w-3.5 h-3.5" />
        <span>{t.p2pBackToDashboard}</span>
      </button>

      <div className="mb-6 p-3 rounded-xl bg-amber-500/10 border border-amber-500/20 flex items-start gap-2 text-amber-300 text-xs">
        <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5" />
        <span>{t.p2pDraftNotice}</span>
      </div>

      <h1 className="text-2xl font-bold tracking-tight mb-1">{content.title}</h1>
      <p className="text-[11px] text-slate-500 mb-6">{content.updated}</p>

      <p className="text-sm text-slate-300 leading-relaxed mb-8">{content.intro}</p>

      <div className="space-y-6">
        {content.sections.map((section) => (
          <div key={section.heading}>
            <h2 className="text-base font-bold mb-2">{section.heading}</h2>
            {section.body.map((para, i) => (
              <p key={i} className="text-sm text-slate-400 leading-relaxed mb-2 last:mb-0">
                {para}
              </p>
            ))}
          </div>
        ))}
      </div>
    </div>
  );
};
