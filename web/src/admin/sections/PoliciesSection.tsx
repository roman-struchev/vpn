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
      <p className="text-xs text-slate-400 max-w-2xl">{t.policiesHint}</p>
      {error && <p className="text-xs text-red-400">{t.error}: {error}</p>}

      <button
        onClick={() => setEditing({ ...EMPTY })}
        className="px-3 py-1.5 rounded-lg bg-dark-800 hover:bg-dark-700 border border-dark-700 text-xs font-semibold"
      >
        {t.newPolicy}
      </button>

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
              <div className="text-xs font-bold">{p.scope} · {p.scopeValue}</div>
              <div className="text-[11px] text-slate-400 mt-1">
                {p.primaryTransport} → {p.fallbackTransport} · {p.fingerprint}
              </div>
              <div className="text-[10px] text-slate-500 mt-1">
                backoff {p.backoffInitialSec}s · {p.maxRetriesBeforeNodeSwitch} retries
              </div>
            </button>
          ))}
        </div>
      )}

      {editing && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4">
          <div className="bg-dark-850 border border-dark-800 rounded-3xl p-6 max-w-sm w-full space-y-3">
            <div className="flex items-center justify-between">
              <h3 className="font-bold text-sm">{editing.id ? `#${editing.id}` : t.newPolicy}</h3>
              <button onClick={() => setEditing(null)} className="text-slate-400 hover:text-white text-xs">✕</button>
            </div>

            <div className="grid grid-cols-2 gap-2 text-xs">
              <div>
                <label className="block text-slate-400 mb-1">{t.scope}</label>
                <select
                  value={editing.scope}
                  onChange={(e) => setEditing({ ...editing, scope: e.target.value })}
                  className="w-full px-2 py-1.5 rounded-lg bg-dark-900 border border-dark-700"
                >
                  <option value="global">global</option>
                  <option value="region">region</option>
                  <option value="operator">operator</option>
                  <option value="user">user</option>
                </select>
              </div>
              <div>
                <label className="block text-slate-400 mb-1">{t.scopeValue}</label>
                <input
                  value={editing.scopeValue}
                  onChange={(e) => setEditing({ ...editing, scopeValue: e.target.value })}
                  className="w-full px-2 py-1.5 rounded-lg bg-dark-900 border border-dark-700"
                />
              </div>
              <div>
                <label className="block text-slate-400 mb-1">{t.primaryTransport}</label>
                <select
                  value={editing.primaryTransport}
                  onChange={(e) => setEditing({ ...editing, primaryTransport: e.target.value })}
                  className="w-full px-2 py-1.5 rounded-lg bg-dark-900 border border-dark-700"
                >
                  <option value="XHTTP">XHTTP</option>
                  <option value="GRPC">GRPC</option>
                </select>
              </div>
              <div>
                <label className="block text-slate-400 mb-1">{t.fallbackTransport}</label>
                <select
                  value={editing.fallbackTransport}
                  onChange={(e) => setEditing({ ...editing, fallbackTransport: e.target.value })}
                  className="w-full px-2 py-1.5 rounded-lg bg-dark-900 border border-dark-700"
                >
                  <option value="XHTTP">XHTTP</option>
                  <option value="GRPC">GRPC</option>
                </select>
              </div>
              <div>
                <label className="block text-slate-400 mb-1">{t.fingerprint}</label>
                <select
                  value={editing.fingerprint}
                  onChange={(e) => setEditing({ ...editing, fingerprint: e.target.value })}
                  className="w-full px-2 py-1.5 rounded-lg bg-dark-900 border border-dark-700"
                >
                  <option value="firefox">firefox</option>
                  <option value="edge">edge</option>
                </select>
              </div>
              <div>
                <label className="block text-slate-400 mb-1">{t.backoffInitialSec}</label>
                <input
                  type="number"
                  value={editing.backoffInitialSec}
                  onChange={(e) => setEditing({ ...editing, backoffInitialSec: parseInt(e.target.value || '0', 10) })}
                  className="w-full px-2 py-1.5 rounded-lg bg-dark-900 border border-dark-700"
                />
              </div>
              <div>
                <label className="block text-slate-400 mb-1">{t.maxRetries}</label>
                <input
                  type="number"
                  value={editing.maxRetriesBeforeNodeSwitch}
                  onChange={(e) =>
                    setEditing({ ...editing, maxRetriesBeforeNodeSwitch: parseInt(e.target.value || '0', 10) })
                  }
                  className="w-full px-2 py-1.5 rounded-lg bg-dark-900 border border-dark-700"
                />
              </div>
              <div className="flex items-end gap-2 pb-1.5">
                <input
                  type="checkbox"
                  id="policy-active"
                  checked={editing.isActive}
                  onChange={(e) => setEditing({ ...editing, isActive: e.target.checked })}
                />
                <label htmlFor="policy-active" className="text-slate-400">{t.active}</label>
              </div>
            </div>

            <button onClick={save} className="w-full py-2.5 rounded-xl bg-brand-500 hover:bg-brand-600 text-dark-950 font-bold text-xs">
              {t.save}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
