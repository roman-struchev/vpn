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
    updated: 'Черновик от 2026-09-21',
    intro:
      'Режим ретрансляции — необязательная функция: включив её в приложении Desktop или Android, вы разрешаете вашему устройству временно передавать трафик других пользователей Aura VPN. За это на ваш аккаунт начисляется дополнительный трафик. Включая режим, вы соглашаетесь на оба сценария использования, описанных в разделе 1, — отдельного переключателя между ними нет.',
    sections: [
      {
        heading: '1. Что именно происходит с вашим устройством',
        body: [
          'Когда режим включён, ваше устройство регистрируется как узел сети и устанавливает прямое P2P-соединение (WebRTC) с устройством другого пользователя. Оно не расшифровывает проходящие данные и не может прочитать их содержимое: ваше устройство открывает TCP-соединение по адресу, который называет подключающийся клиент, и передаёт байты в обе стороны.',
          'Ваше устройство используется в двух сценариях. Первый — путь до сервера: адрес, который называет клиент, это обычный сервер Aura VPN, туннель остаётся сквозным между клиентом и этим сервером, а в интернет трафик выходит с IP-адреса сервера. Так работает автоматический обход блокировок, когда клиент не может дотянуться до сервера напрямую.',
          'Второй — точка выхода: пользователь платного тарифа может выбрать вашу страну в списке подключения с пометкой P2P, и тогда ваше устройство открывает соединения напрямую к сайтам, которые он посещает. В этом случае для этих сайтов и для вашего интернет-провайдера источником запросов выглядит ваш IP-адрес. Содержимое вы по-прежнему не видите (для HTTPS-сайтов оно зашифровано между пользователем и сайтом), но адреса назначения проходят через ваше подключение. Это тот же принцип, что у residential-прокси (вспомните историю Hola VPN/Luminati), с двумя отличиями: функция включается только вами и явно, и за неё начисляется трафик.',
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
          'Главный риск — сценарий точки выхода из раздела 1. Когда ваше устройство выступает выходом, запросы к сайтам приходят с вашего IP-адреса, и если кто-то использует его для противоправных или просто нежелательных действий, для стороннего наблюдателя источником будете выглядеть вы. По духу это близко к выходному узлу Tor, и это известный, реальный риск, а не теоретический. Мы не можем его полностью исключить ни для одной технологии такого рода.',
          'Практические последствия, с которыми чаще всего сталкиваются: провайдер может прислать уведомление о жалобе; ваш IP-адрес может попасть в списки подозрительных и начать получать капчи или блокировки на обычных сайтах — в том числе когда вы пользуетесь интернетом сами.',
          'Что мы делаем, чтобы снизить риск: (а) destination-ACL (раздел 2) блокирует доступ к вашей собственной локальной сети; (б) функция полностью добровольна и доступна только зарегистрированному (не гостевому) аккаунту, что оставляет возможность разобраться в ситуации; (в) режим выхода доступен только пользователям платных тарифов, то есть аккаунтам с историей оплаты, а не анонимным.',
          'Если вы не готовы принять этот риск — просто не включайте режим. Он не требуется для обычного использования VPN и никак на него не влияет. Если вам нужна только помощь другим в обходе блокировок без роли точки выхода, напишите нам: отдельного переключателя пока нет, и мы не будем делать вид, что он есть.',
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
    updated: 'Draft as of 2026-09-21',
    intro:
      'Relay mode is an optional feature: turning it on in the Desktop or Android app lets your device temporarily carry other Aura VPN users’ traffic. In exchange, extra traffic is credited to your account. Turning it on covers both of the uses described in section 1 — there is no separate switch between them.',
    sections: [
      {
        heading: '1. What actually happens on your device',
        body: [
          'When the mode is on, your device registers as a node in the network and opens a direct peer-to-peer (WebRTC) connection to another user’s device. It does not decrypt what passes through and cannot read the contents: your device opens a TCP connection to the address the connecting client names, and forwards bytes in both directions.',
          'Your device is used in two ways. The first is as a path to a server: the address the client names is an ordinary Aura VPN server, the tunnel stays end-to-end between that client and that server, and the traffic reaches the internet from the server’s IP. This is how the automatic censorship workaround works when a client cannot reach a server directly.',
          'The second is as an exit: a paid-plan user can pick your country in their connection list, marked P2P, and your device then opens connections directly to the sites they visit. In that case your IP address is what those sites, and your internet provider, see as the source of the requests. You still cannot see the contents (for HTTPS sites it is encrypted between the user and the site), but the destinations pass through your connection. This is the same principle as a residential proxy (recall the Hola VPN/Luminati story), with two differences: it is only ever switched on by you, deliberately, and you are credited traffic for it.',
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
          'The main risk is the exit case in section 1. When your device is the exit, requests to websites come from your IP address, and if someone uses it for something abusive — or merely unwelcome — you are what an outside observer sees as the source. In spirit this is close to running a Tor exit node: a known, real risk rather than a theoretical one. We cannot fully eliminate it for any technology of this kind.',
          'The practical consequences people actually run into: your provider may forward an abuse complaint; your IP may end up on reputation lists and start drawing CAPTCHAs or blocks on ordinary websites — including when you are using the internet yourself.',
          'What we do to reduce it: (a) the destination ACL (section 2) blocks access to your own local network; (b) the feature is fully opt-in and only available to a real, non-guest account, which preserves the ability to look into anything that needs looking into; (c) exit use is limited to paid-plan users, i.e. accounts with a payment history rather than anonymous ones.',
          'If you are not comfortable with that, simply don’t turn the mode on — it isn’t required for normal VPN use and has no effect on it either way. If you would only like to help others get around censorship without being an exit, write to us: there is no separate switch for that yet, and we are not going to pretend otherwise.',
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
