import { useState } from 'react';
import { LayoutDashboard, Users, Server, Radio, Wallet, ArrowLeft } from 'lucide-react';
import { Lang } from '../i18n';
import { adminTranslations } from './adminI18n';
import { DashboardSection } from './sections/DashboardSection';
import { UsersSection } from './sections/UsersSection';
import { NodesSection } from './sections/NodesSection';
import { PoliciesSection } from './sections/PoliciesSection';
import { PaymentsSection } from './sections/PaymentsSection';
// PrimeReact is only pulled in for the admin panel (DataTable/Column/Dropdown/
// Dialog in the section files) — imported here, not in main.tsx/App.tsx, so
// its CSS only enters the bundle graph through this component, not globally
// for every visitor. PrimeReact's own class-scoped selectors (.p-datatable
// etc.) don't leak into unrelated site markup either way.
import 'primereact/resources/themes/lara-dark-green/theme.css';
import 'primereact/resources/primereact.min.css';
import 'primeicons/primeicons.css';
import './admin.css';

type Tab = 'dashboard' | 'users' | 'nodes' | 'policies' | 'payments';

export function AdminPanel({ lang, onBack }: { lang: Lang; onBack: () => void }) {
  const t = adminTranslations[lang];
  const [tab, setTab] = useState<Tab>('dashboard');

  const tabs: Array<{ id: Tab; label: string; icon: typeof LayoutDashboard }> = [
    { id: 'dashboard', label: t.tabDashboard, icon: LayoutDashboard },
    { id: 'users', label: t.tabUsers, icon: Users },
    { id: 'nodes', label: t.tabNodes, icon: Server },
    { id: 'policies', label: t.tabPolicies, icon: Radio },
    { id: 'payments', label: t.tabPayments, icon: Wallet },
  ];

  return (
    <div className="w-full max-w-7xl mx-auto px-4 py-8" data-testid="admin-panel">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-xl font-bold tracking-tight">{t.adminPanel}</h1>
        <button
          onClick={onBack}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-dark-800 hover:bg-dark-700 border border-dark-700 text-xs font-semibold text-slate-300"
        >
          <ArrowLeft className="w-3.5 h-3.5" />
          <span>{t.backToSite}</span>
        </button>
      </div>

      <div className="flex items-center gap-1 mb-6 border-b border-dark-800 overflow-x-auto">
        {tabs.map(({ id, label, icon: Icon }) => (
          <button
            key={id}
            onClick={() => setTab(id)}
            data-testid={`admin-tab-${id}`}
            className={`flex items-center gap-1.5 px-4 py-2.5 text-xs font-semibold whitespace-nowrap border-b-2 transition-colors ${
              tab === id
                ? 'border-brand-500 text-brand-500'
                : 'border-transparent text-slate-400 hover:text-slate-200'
            }`}
          >
            <Icon className="w-3.5 h-3.5" />
            <span>{label}</span>
          </button>
        ))}
      </div>

      {tab === 'dashboard' && <DashboardSection t={t} />}
      {tab === 'users' && <UsersSection t={t} />}
      {tab === 'nodes' && <NodesSection t={t} />}
      {tab === 'policies' && <PoliciesSection t={t} />}
      {tab === 'payments' && <PaymentsSection t={t} />}
    </div>
  );
}
