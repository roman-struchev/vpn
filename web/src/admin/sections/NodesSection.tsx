import { useEffect, useState } from 'react';
import { DataTable } from 'primereact/datatable';
import { Column } from 'primereact/column';
import { Dropdown } from 'primereact/dropdown';
import { adminApi, AdminNode } from '../adminApi';
import { AdminT } from '../adminI18n';

const POOLS = ['trial', 'paid', 'quarantine', 'reserve'];
const STATUSES = ['ONLINE', 'OFFLINE', 'DRAINING', 'MAINTENANCE'];

export function NodesSection({ t }: { t: AdminT }) {
  const [nodes, setNodes] = useState<AdminNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [showBootstrap, setShowBootstrap] = useState(false);
  const [showLegend, setShowLegend] = useState(false);

  const load = () => {
    setLoading(true);
    adminApi
      .listNodes()
      .then(setNodes)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const withBusy = async (id: number, fn: () => Promise<unknown>) => {
    setBusyId(id);
    try {
      await fn();
      load();
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="space-y-4">
      {error && <p className="text-xs text-red-400">{t.error}: {error}</p>}

      <div className="flex items-center gap-2">
        <button
          onClick={() => setShowBootstrap(true)}
          className="px-3 py-1.5 rounded-lg bg-dark-800 hover:bg-dark-700 border border-dark-700 text-xs font-semibold"
        >
          {t.createBootstrapToken}
        </button>
        <button
          onClick={() => setShowLegend((v) => !v)}
          className="px-3 py-1.5 rounded-lg text-xs font-semibold text-brand-400 hover:text-brand-300"
        >
          {showLegend ? t.nodeLegendToggleHide : t.nodeLegendToggleShow}
        </button>
      </div>

      {showLegend && (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4 p-5 rounded-2xl bg-dark-900 border border-dark-800 text-xs text-slate-300 max-w-4xl">
          <div>
            <h4 className="font-bold text-slate-100 mb-2">{t.poolLegendTitle}</h4>
            <ul className="space-y-1.5 text-slate-400 leading-relaxed">
              <li>{t.poolTrialDesc}</li>
              <li>{t.poolPaidDesc}</li>
              <li>{t.poolQuarantineDesc}</li>
              <li>{t.poolReserveDesc}</li>
            </ul>
          </div>
          <div>
            <h4 className="font-bold text-slate-100 mb-2">{t.statusLegendTitle}</h4>
            <ul className="space-y-1.5 text-slate-400 leading-relaxed">
              <li>{t.statusOnlineDesc}</li>
              <li>{t.statusOfflineDesc}</li>
              <li>{t.statusDrainingDesc}</li>
              <li>{t.statusMaintenanceDesc}</li>
            </ul>
          </div>
          <p className="md:col-span-2 text-[11px] text-slate-500 pt-2 border-t border-dark-800">{t.poolSharingNote}</p>
        </div>
      )}

      <div className="admin-table rounded-2xl overflow-hidden border border-dark-800">
        <DataTable value={nodes} loading={loading} paginator rows={15} dataKey="id" size="small" emptyMessage={t.loading}>
          <Column field="id" header="ID" style={{ width: '3.5rem' }} />
          <Column field="hostname" header={t.hostname} sortable />
          <Column field="publicIp" header={t.publicIp} />
          <Column field="region" header={t.region} />
          <Column field="type" header={t.type} style={{ width: '5rem' }} />
          <Column
            header={t.status}
            style={{ width: '10rem' }}
            body={(n: AdminNode) => (
              <Dropdown
                value={n.status}
                options={STATUSES}
                disabled={busyId === n.id}
                onChange={(e) => withBusy(n.id, () => adminApi.setNodeStatus(n.id, e.value))}
                className="admin-dropdown text-xs"
              />
            )}
          />
          <Column
            header={t.pool}
            style={{ width: '9rem' }}
            body={(n: AdminNode) => (
              <Dropdown
                value={n.pool}
                options={POOLS}
                disabled={busyId === n.id}
                onChange={(e) => withBusy(n.id, () => adminApi.setNodePool(n.id, e.value))}
                className="admin-dropdown text-xs"
              />
            )}
          />
          <Column
            header={t.cpu}
            body={(n: AdminNode) =>
              n.cpuPercent != null
                ? `${n.cpuPercent}%${n.cpuCount ? ` (×${n.cpuCount})` : ''}`
                : '—'
            }
          />
          <Column
            header={t.memory}
            body={(n: AdminNode) =>
              n.memoryUsedBytes && n.memoryTotalBytes
                ? `${(n.memoryUsedBytes / 1024 ** 2).toFixed(0)} / ${(n.memoryTotalBytes / 1024 ** 2).toFixed(0)} MB`
                : '—'
            }
          />
          <Column
            header={<span title={t.connectionsHint} className="cursor-help border-b border-dotted border-slate-600">{t.connections}</span>}
            body={(n: AdminNode) => n.activeConnections ?? 0}
          />
          <Column
            header={t.trafficServed}
            body={(n: AdminNode) => `${(n.totalBytesServed / 1024 ** 3).toFixed(2)} GB`}
          />
          <Column
            header={t.lastHeartbeat}
            body={(n: AdminNode) => (n.lastHeartbeatAt ? new Date(n.lastHeartbeatAt).toLocaleString() : '—')}
          />
          <Column
            header={t.actions}
            body={(n: AdminNode) => (
              <button
                disabled={busyId === n.id}
                onClick={() => withBusy(n.id, () => adminApi.forceSync(n.id))}
                className="px-2 py-1 rounded-lg bg-dark-800 hover:bg-dark-700 border border-dark-700 text-[10px] font-semibold disabled:opacity-50"
              >
                {t.forceSync}
              </button>
            )}
          />
        </DataTable>
      </div>

      {showBootstrap && <BootstrapTokenDialog t={t} onClose={() => setShowBootstrap(false)} />}
    </div>
  );
}

function BootstrapTokenDialog({ t, onClose }: { t: AdminT; onClose: () => void }) {
  const [pool, setPool] = useState('paid');
  const [type, setType] = useState('direct');
  const [validHours, setValidHours] = useState('24');
  const [result, setResult] = useState<{ token: string; expiresAt: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const create = async () => {
    setError(null);
    try {
      const res = await adminApi.createBootstrapToken(pool, type, parseInt(validHours || '24', 10));
      setResult(res);
    } catch (e: any) {
      setError(e.message);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4">
      <div className="bg-dark-850 border border-dark-800 rounded-3xl p-6 max-w-sm w-full space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="font-bold text-sm">{t.createBootstrapToken}</h3>
          <button onClick={onClose} className="text-slate-400 hover:text-white text-xs">✕</button>
        </div>
        <p className="text-[11px] text-slate-400">{t.bootstrapTokenHint}</p>
        {error && <p className="text-xs text-red-400">{error}</p>}

        {!result ? (
          <div className="space-y-3 text-xs">
            <div>
              <label className="block text-slate-400 mb-1">{t.pool}</label>
              <select
                data-testid="bootstrap-pool-select"
                value={pool}
                onChange={(e) => setPool(e.target.value)}
                className="w-full px-3 py-2 rounded-xl bg-dark-900 border border-dark-700"
              >
                {POOLS.map((p) => <option key={p} value={p}>{p}</option>)}
              </select>
            </div>
            <div>
              <label className="block text-slate-400 mb-1">{t.type}</label>
              <select
                data-testid="bootstrap-type-select"
                value={type}
                onChange={(e) => setType(e.target.value)}
                className="w-full px-3 py-2 rounded-xl bg-dark-900 border border-dark-700"
              >
                <option value="direct">direct</option>
                <option value="cdn">cdn</option>
              </select>
            </div>
            <div>
              <label className="block text-slate-400 mb-1">{t.validHours}</label>
              <input
                type="number"
                value={validHours}
                onChange={(e) => setValidHours(e.target.value)}
                className="w-full px-3 py-2 rounded-xl bg-dark-900 border border-dark-700"
              />
            </div>
            <button onClick={create} className="w-full py-2.5 rounded-xl bg-brand-500 hover:bg-brand-600 text-dark-950 font-bold">
              {t.create}
            </button>
          </div>
        ) : (
          <div className="space-y-3">
            <p className="text-xs text-emerald-400 font-semibold">{t.tokenCreated}</p>
            <div
              data-testid="bootstrap-token-value"
              className="p-3 rounded-xl bg-dark-900 border border-dark-700 text-[11px] font-mono break-all text-slate-200"
            >
              {result.token}
            </div>
            <p className="text-[10px] text-slate-500">
              scripts/install-node.sh &lt;server_grpc_host:port&gt; {result.token}
            </p>
            <button
              onClick={() => {
                navigator.clipboard.writeText(result.token);
                setCopied(true);
              }}
              className="w-full py-2 rounded-xl bg-dark-800 hover:bg-dark-700 border border-dark-700 text-xs font-semibold"
            >
              {copied ? '✓' : t.copyToken}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
