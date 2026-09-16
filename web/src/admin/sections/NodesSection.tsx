import { useEffect, useState } from 'react';
import { DataTable } from 'primereact/datatable';
import { Column } from 'primereact/column';
import { Dropdown } from 'primereact/dropdown';
import { adminApi, AdminNode } from '../adminApi';
import { AdminT } from '../adminI18n';
import { copyToClipboard } from '../../utils/clipboard';

// "both" (docs/research/P2P_RELAY_FEASIBILITY.md §8.3) is the single lever
// for dual trial+paid access now — see NodeManagementService#applyTariffAccessFlags.
// A p2p node's bootstrap token defaults to it (createP2pBootstrapTokenForUser),
// but any node can be switched to it from this same dropdown.
const POOLS = ['trial', 'paid', 'both', 'quarantine', 'reserve'];
const STATUSES = ['ONLINE', 'OFFLINE', 'DRAINING', 'MAINTENANCE'];

// ~3x the server's default vpn.stats-interval-sec (30s, see NodeManagementService)
// — past this, recentBytesPerSec is a leftover from before the node went quiet
// (idle or offline) rather than a real current rate, so it's shown as "—".
const STATS_STALE_AFTER_MS = 90_000;
const REFRESH_INTERVAL_MS = 20_000;

function formatNodeSpeed(n: AdminNode): string {
  if (n.recentBytesPerSec == null || !n.lastTrafficStatsAt) return '—';
  if (Date.now() - new Date(n.lastTrafficStatsAt).getTime() > STATS_STALE_AFTER_MS) return '—';
  const mbps = (n.recentBytesPerSec * 8) / 1_000_000;
  return `${mbps.toFixed(mbps < 10 ? 2 : 1)} Mbps`;
}

// Deliberately abbreviated units, not full pluralized words — Russian plural
// agreement (1 минута / 2 минуты / 5 минут) would need a proper i18n
// pluralization helper this admin panel doesn't have anywhere else; the
// exact timestamp is still one hover away via the `title` attribute.
function formatRelativeTime(t: AdminT, iso: string | null): string {
  if (!iso) return '—';
  const diffMs = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diffMs / 60_000);
  if (mins < 1) return t.justNowShort;
  if (mins < 60) return `${mins} ${t.minutesShort}`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours} ${t.hoursShort}`;
  return `${Math.floor(hours / 24)} ${t.daysShort}`;
}

export function NodesSection({ t }: { t: AdminT }) {
  const [nodes, setNodes] = useState<AdminNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [showBootstrap, setShowBootstrap] = useState(false);
  const [showLegend, setShowLegend] = useState(false);

  // silent=true is used for the background poll below: it refreshes live metrics
  // (speed, CPU, connections...) without flashing the table's loading spinner.
  const load = (opts?: { silent?: boolean }) => {
    if (!opts?.silent) setLoading(true);
    adminApi
      .listNodes()
      .then(setNodes)
      .catch((e) => setError(e.message))
      .finally(() => {
        if (!opts?.silent) setLoading(false);
      });
  };

  useEffect(() => {
    load();
    const id = setInterval(() => load({ silent: true }), REFRESH_INTERVAL_MS);
    return () => clearInterval(id);
  }, []);

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
              <li>{t.poolBothDesc}</li>
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
          <Column
            header={t.hostname}
            sortable
            sortField="hostname"
            style={{ minWidth: '10rem' }}
            body={(n: AdminNode) => (
              <div className="flex flex-col">
                <span className="font-medium">{n.hostname}</span>
                {/* A p2p node's publicIp is always the placeholder 0.0.0.0
                    (docs §8.4) — showing it as a real value invites "why is
                    this IP wrong" questions, so it's suppressed there. */}
                <span className="text-[10px] text-slate-500">{n.type === 'p2p' ? '—' : n.publicIp}</span>
              </div>
            )}
          />
          <Column field="region" header={t.region} />
          <Column
            header={t.type}
            style={{ minWidth: '7rem' }}
            body={(n: AdminNode) => (
              <div className="flex flex-col">
                <span>{n.type}</span>
                {n.type === 'p2p' && (
                  <span className="text-[10px] text-amber-400" title={n.relayExpiresAt ?? undefined}>
                    {n.relayMode}
                    {n.ownerUserId != null ? ` · #${n.ownerUserId}` : ''}
                  </span>
                )}
              </div>
            )}
          />
          <Column
            header={t.status}
            style={{ width: '8.5rem' }}
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
            // Pool is the ONLY lever for tariff access now (docs §8.3) — a
            // separate "Доступ по тарифу" column used to sit next to this
            // one, independently editable, which the repo owner found
            // confusing ("зачем доступ по тарифу и пул разными столбцами,
            // какой смысл это разделять"). "both" grants trial+paid at once;
            // see the legend below for the full breakdown.
            header={<span title={t.poolHint} className="cursor-help border-b border-dotted border-slate-600">{t.pool}</span>}
            style={{ width: '8.5rem' }}
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
            header={t.resources}
            body={(n: AdminNode) => (
              <div className="flex flex-col text-[11px]">
                <span>
                  {t.cpu}: {n.cpuPercent != null ? `${n.cpuPercent}%${n.cpuCount ? ` (×${n.cpuCount})` : ''}` : '—'}
                </span>
                <span className="text-slate-500">
                  {t.memory}:{' '}
                  {n.memoryUsedBytes && n.memoryTotalBytes
                    ? `${(n.memoryUsedBytes / 1024 ** 2).toFixed(0)}/${(n.memoryTotalBytes / 1024 ** 2).toFixed(0)} MB`
                    : '—'}
                </span>
              </div>
            )}
          />
          <Column
            header={<span title={`${t.connectionsHint} ${t.speedHint}`} className="cursor-help border-b border-dotted border-slate-600">{t.load}</span>}
            body={(n: AdminNode) => (
              <div className="flex flex-col text-[11px]">
                <span>{t.connections}: {n.activeConnections ?? 0}</span>
                <span className="text-slate-500">{formatNodeSpeed(n)}</span>
              </div>
            )}
          />
          <Column
            header={t.trafficServed}
            body={(n: AdminNode) => `${(n.totalBytesServed / 1024 ** 3).toFixed(2)} GB`}
          />
          <Column
            header={t.lastHeartbeat}
            body={(n: AdminNode) => (
              <span title={n.lastHeartbeatAt ? new Date(n.lastHeartbeatAt).toLocaleString() : undefined}>
                {formatRelativeTime(t, n.lastHeartbeatAt)}
              </span>
            )}
          />
          <Column
            header={t.actions}
            body={(n: AdminNode) => (
              <div className="flex items-center gap-1.5">
                <button
                  data-testid={`node-force-sync-${n.id}`}
                  disabled={busyId === n.id}
                  onClick={() => withBusy(n.id, () => adminApi.forceSync(n.id))}
                  className="px-2 py-1 rounded-lg bg-dark-800 hover:bg-dark-700 border border-dark-700 text-[10px] font-semibold disabled:opacity-50"
                >
                  {t.forceSync}
                </button>
                {/* Bounces only the xray-core child process the agent supervises
                    (XraySupervisor.restart() in agent/src/xray/xray-supervisor.ts) —
                    it does NOT touch the agent process itself, so the gRPC command
                    stream this button relies on stays up throughout, and there is
                    nothing here to "un-brick" if it goes wrong. Deliberately NOT
                    exposing a "restart/kill the agent" or "power off the node"
                    action next to this one: those need real infra access (systemd,
                    SSH, a cloud provider API) that this gRPC command channel was
                    never designed to provide, and could strand a node with no
                    recovery path from the admin panel if the target host's agent
                    isn't supervised the way scripts/install-node.sh sets one up. */}
                <button
                  data-testid={`node-restart-xray-${n.id}`}
                  disabled={busyId === n.id}
                  title={t.restartXrayHint}
                  onClick={() => {
                    if (!window.confirm(t.restartXrayConfirm)) return;
                    withBusy(n.id, () => adminApi.sendNodeCommand(n.id, 'COMMAND_TYPE_RESTART_XRAY'));
                  }}
                  className="px-2 py-1 rounded-lg bg-dark-800 hover:bg-dark-700 border border-dark-700 text-[10px] font-semibold disabled:opacity-50"
                >
                  {t.restartXray}
                </button>
              </div>
            )}
          />
        </DataTable>
      </div>

      {showBootstrap && <BootstrapTokenDialog t={t} onClose={() => setShowBootstrap(false)} />}
    </div>
  );
}

function installCommandFor(token: string): string {
  return `curl -fsSL https://raw.githubusercontent.com/roman-struchev/vpn/main/scripts/install-node.sh | bash -s -- 217.216.79.46:9090 ${token}`;
}

/** --p2p variant (docs/research/P2P_RELAY_FEASIBILITY.md §8) — see scripts/install-node.sh's own usage text for the optional [region] [relay_mode] [relay_duration_hours] positional args this leaves at their defaults. */
function p2pInstallSnippetFor(token: string): string {
  return `curl -fsSL https://raw.githubusercontent.com/roman-struchev/vpn/main/scripts/install-node.sh | bash -s -- --p2p 217.216.79.46:9090 ${token}\n# Optional positional args: [region] [relay_mode: always|timed] [relay_duration_hours]\n# e.g. ... ${token} "Germany, Frankfurt" timed 8`;
}

function BootstrapTokenDialog({ t, onClose }: { t: AdminT; onClose: () => void }) {
  const [pool, setPool] = useState('paid');
  const [type, setType] = useState('direct');
  const [validHours, setValidHours] = useState('24');
  const [result, setResult] = useState<{ token: string; assignedType: string; expiresAt: string } | null>(null);
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
              <label className="block text-slate-400 mb-1">{t.type}</label>
              <select
                data-testid="bootstrap-type-select"
                value={type}
                onChange={(e) => {
                  const nextType = e.target.value;
                  setType(nextType);
                  // "both" is the normal default for a p2p node (docs §8.3)
                  // — the admin can still change it via the pool select
                  // right below, which is no longer hidden now that pool is
                  // the single lever for tariff access on every node type.
                  if (nextType === 'p2p') setPool('both');
                }}
                className="w-full px-3 py-2 rounded-xl bg-dark-900 border border-dark-700"
              >
                <option value="direct">direct</option>
                <option value="cdn">cdn</option>
                <option value="p2p">p2p</option>
              </select>
              {type === 'p2p' && <p className="mt-1 text-[10px] text-slate-500">{t.p2pBootstrapHint}</p>}
            </div>
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
            {/* Repo is public, so the node can pull the script straight from GitHub —
                no need to scp it from a machine that has the repo checked out. Run
                this ON the new node itself (already SSH'd in), not from your machine.
                SERVER_GRPC_URL (217.216.79.46:9090) is this same server's gRPC port
                from docker-compose.yml's GRPC_PORT — known and stable, unlike the new
                node's own IP, which isn't needed here at all (no ssh wrapper).
                --p2p (docs/research/P2P_RELAY_FEASIBILITY.md §8): install-node.sh
                skips the public-IP requirement and uses default (not host) Docker
                networking for this mode — see scripts/install-node.sh's own --p2p
                usage text for the optional [region] [relay_mode] [relay_duration_hours]
                positional args this snippet leaves at their defaults. */}
            <pre className="p-3 rounded-xl bg-dark-900 border border-dark-700 text-[10px] font-mono whitespace-pre-wrap break-all text-slate-300">
              {result.assignedType === 'p2p' ? p2pInstallSnippetFor(result.token) : installCommandFor(result.token)}
            </pre>
            <button
              onClick={() => {
                copyToClipboard(
                  result.assignedType === 'p2p' ? p2pInstallSnippetFor(result.token) : installCommandFor(result.token),
                );
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
