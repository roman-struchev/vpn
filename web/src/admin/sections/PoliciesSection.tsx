import { useEffect, useState } from 'react';
import { adminApi, AdminTransportPolicy } from '../adminApi';
import { AdminT } from '../adminI18n';

const EMPTY: AdminTransportPolicy = {
  scope: 'global',
  scopeValue: '*',
  primaryTransport: 'XHTTP',
  fallbackTransport: 'GRPC',
  fingerprint: 'firefox',
  backoffInitialSec: 15,
  maxRetriesBeforeNodeSwitch: 3,
  isActive: true,
};

export function PoliciesSection({ t }: { t: AdminT }) {
  const [policies, setPolicies] = useState<AdminTransportPolicy[]>([]);
  const [editing, setEditing] = useState<AdminTransportPolicy | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [showGuide, setShowGuide] = useState(false);

  const load = () => {
    setLoading(true);
    adminApi
      .listPolicies()
      .then(setPolicies)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const save = async () => {
    if (!editing) return;
    setError(null);
    try {
      await adminApi.savePolicy(editing);
      setEditing(null);
      load();
    } catch (e: any) {
      setError(e.message);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-4">
        <p className="text-xs text-slate-400 max-w-2xl">{t.policiesHint}</p>
        <button
          onClick={() => setShowGuide((v) => !v)}
          className="shrink-0 text-xs font-semibold text-brand-400 hover:text-brand-300 whitespace-nowrap"
        >
          {showGuide ? t.policyGuideToggleHide : t.policyGuideToggleShow}
        </button>
      </div>

      {showGuide && (
        <div className="p-5 rounded-2xl bg-dark-900 border border-dark-800 space-y-4 text-xs text-slate-300 max-w-3xl">
          <h3 className="font-bold text-sm text-slate-100">{t.policyGuideTitle}</h3>

          <div>
            <h4 className="font-semibold text-slate-200 mb-1">{t.policyWhyTitle}</h4>
            <p className="leading-relaxed text-slate-400">{t.policyWhyDesc}</p>
          </div>

          <div>
            <h4 className="font-semibold text-slate-200 mb-1">{t.policyScopeTitle}</h4>
            <p className="text-slate-400">{t.policyScopeDesc}</p>
            <p className="font-mono text-[11px] text-brand-400 mt-1">{t.policyScopePrecedence}</p>
            <ul className="list-disc list-inside text-slate-400 mt-2 space-y-0.5">
              <li>{t.policyScopeUser}</li>
              <li>{t.policyScopeOperator}</li>
              <li>{t.policyScopeRegion}</li>
              <li>{t.policyScopeGlobal}</li>
            </ul>
          </div>

          <div>
            <h4 className="font-semibold text-slate-200 mb-1">{t.policyParamsTitle}</h4>
            <ul className="list-disc list-inside text-slate-400 space-y-1">
              <li>{t.policyParamTransport}</li>
              <li>{t.policyParamFingerprint}</li>
              <li>{t.policyParamBackoff}</li>
              <li>{t.policyParamRetries}</li>
            </ul>
          </div>
        </div>
      )}

      {error && <p className="text-xs text-red-400">{t.error}: {error}</p>}

      <div className="flex items-center justify-between">
        <button
          onClick={() => setEditing({ ...EMPTY })}
          className="px-3 py-1.5 rounded-lg bg-dark-800 hover:bg-dark-700 border border-dark-700 text-xs font-semibold text-slate-200 transition-colors"
        >
          {t.newPolicy}
        </button>
      </div>

      {loading ? (
        <p className="text-xs text-slate-500">{t.loading}</p>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
          {policies.map((p) => (
            <button
              key={p.id}
              onClick={() => setEditing({ ...p })}
              className={`text-left p-4 rounded-xl bg-dark-900 border transition-colors hover:border-brand-500/40 ${
                p.isActive ? 'border-dark-800' : 'border-dark-800 opacity-50'
              }`}
            >
              <div className="flex items-center justify-between">
                <span className="text-xs font-bold text-slate-200">{p.scope} · {p.scopeValue}</span>
                <span className={`text-[10px] px-1.5 py-0.5 rounded-full font-medium ${
                  p.isActive ? 'bg-emerald-500/10 text-emerald-400' : 'bg-dark-800 text-slate-500'
                }`}>
                  {p.isActive ? t.active : 'Inactive'}
                </span>
              </div>
              <div className="text-[11px] text-slate-400 mt-2">
                <span className="text-brand-400 font-medium">{p.primaryTransport}</span> → {p.fallbackTransport} · <span className="text-slate-300">{p.fingerprint}</span>
              </div>
              <div className="text-[10px] text-slate-500 mt-1">
                backoff {p.backoffInitialSec}s · {p.maxRetriesBeforeNodeSwitch} retries
              </div>
            </button>
          ))}
        </div>
      )}

      {editing && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4 overflow-y-auto">
          <div className="bg-dark-850 border border-dark-800 rounded-3xl p-6 max-w-md w-full space-y-4 my-8">
            <div className="flex items-center justify-between">
              <div>
                <h3 className="font-bold text-sm text-slate-100">{editing.id ? `#${editing.id}` : t.newPolicy}</h3>
                <p className="text-[10px] text-slate-400 mt-0.5">{t.policiesHint}</p>
              </div>
              <button onClick={() => setEditing(null)} className="text-slate-400 hover:text-white text-xs p-1">✕</button>
            </div>

            <div className="grid grid-cols-2 gap-3 text-xs">
              <div>
                <label className="block text-slate-300 mb-1">{t.scope}</label>
                <select
                  value={editing.scope}
                  onChange={(e) => setEditing({ ...editing, scope: e.target.value })}
                  className="w-full px-2.5 py-1.5 rounded-lg bg-dark-900 border border-dark-700 text-slate-200"
                >
                  <option value="global">global</option>
                  <option value="region">region</option>
                  <option value="operator">operator</option>
                  <option value="user">user</option>
                </select>
                <span className="block text-[10px] text-slate-500 mt-1">{t.scopeHint}</span>
              </div>
              <div>
                <label className="block text-slate-300 mb-1">{t.scopeValue}</label>
                <input
                  value={editing.scopeValue}
                  onChange={(e) => setEditing({ ...editing, scopeValue: e.target.value })}
                  className="w-full px-2.5 py-1.5 rounded-lg bg-dark-900 border border-dark-700 text-slate-200"
                  placeholder="*"
                />
                <span className="block text-[10px] text-slate-500 mt-1">{t.scopeValueHint}</span>
              </div>
              <div>
                <label className="block text-slate-300 mb-1">{t.primaryTransport}</label>
                <select
                  value={editing.primaryTransport}
                  onChange={(e) => setEditing({ ...editing, primaryTransport: e.target.value })}
                  className="w-full px-2.5 py-1.5 rounded-lg bg-dark-900 border border-dark-700 text-slate-200"
                >
                  <option value="XHTTP">XHTTP</option>
                  <option value="GRPC">GRPC</option>
                </select>
                <span className="block text-[10px] text-slate-500 mt-1">{t.primaryTransportHint}</span>
              </div>
              <div>
                <label className="block text-slate-300 mb-1">{t.fallbackTransport}</label>
                <select
                  value={editing.fallbackTransport}
                  onChange={(e) => setEditing({ ...editing, fallbackTransport: e.target.value })}
                  className="w-full px-2.5 py-1.5 rounded-lg bg-dark-900 border border-dark-700 text-slate-200"
                >
                  <option value="XHTTP">XHTTP</option>
                  <option value="GRPC">GRPC</option>
                </select>
                <span className="block text-[10px] text-slate-500 mt-1">{t.fallbackTransportHint}</span>
              </div>
              <div>
                <label className="block text-slate-300 mb-1">{t.fingerprint}</label>
                <select
                  value={editing.fingerprint}
                  onChange={(e) => setEditing({ ...editing, fingerprint: e.target.value })}
                  className="w-full px-2.5 py-1.5 rounded-lg bg-dark-900 border border-dark-700 text-slate-200"
                >
                  <option value="firefox">firefox</option>
                  <option value="edge">edge</option>
                </select>
                <span className="block text-[10px] text-slate-500 mt-1">{t.fingerprintHint}</span>
              </div>
              <div>
                <label className="block text-slate-300 mb-1">{t.backoffInitialSec}</label>
                <input
                  type="number"
                  value={editing.backoffInitialSec}
                  onChange={(e) => setEditing({ ...editing, backoffInitialSec: parseInt(e.target.value || '0', 10) })}
                  className="w-full px-2.5 py-1.5 rounded-lg bg-dark-900 border border-dark-700 text-slate-200"
                />
                <span className="block text-[10px] text-slate-500 mt-1">{t.backoffHint}</span>
              </div>
              <div>
                <label className="block text-slate-300 mb-1">{t.maxRetries}</label>
                <input
                  type="number"
                  value={editing.maxRetriesBeforeNodeSwitch}
                  onChange={(e) =>
                    setEditing({ ...editing, maxRetriesBeforeNodeSwitch: parseInt(e.target.value || '0', 10) })
                  }
                  className="w-full px-2.5 py-1.5 rounded-lg bg-dark-900 border border-dark-700 text-slate-200"
                />
                <span className="block text-[10px] text-slate-500 mt-1">{t.maxRetriesHint}</span>
              </div>
              <div className="flex flex-col justify-end pb-1">
                <label className="flex items-center gap-2 cursor-pointer text-slate-300 text-xs">
                  <input
                    type="checkbox"
                    id="policy-active"
                    checked={editing.isActive}
                    onChange={(e) => setEditing({ ...editing, isActive: e.target.checked })}
                    className="rounded border-dark-700 bg-dark-900 text-brand-500 focus:ring-0"
                  />
                  <span>{t.active}</span>
                </label>
              </div>
            </div>

            <button onClick={save} className="w-full py-2.5 rounded-xl bg-brand-500 hover:bg-brand-600 text-dark-950 font-bold text-xs transition-colors">
              {t.save}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
