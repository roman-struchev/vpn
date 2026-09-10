import { useEffect, useState } from 'react';
import { adminApi, DashboardMetrics } from '../adminApi';
import { AdminT } from '../adminI18n';

const StatCard = ({ label, value }: { label: string; value: string | number }) => (
  <div className="p-4 rounded-xl bg-dark-900 border border-dark-800">
    <div className="text-[11px] text-slate-400">{label}</div>
    <div className="text-xl font-bold mt-1">{value}</div>
  </div>
);

export function DashboardSection({ t }: { t: AdminT }) {
  const [metrics, setMetrics] = useState<DashboardMetrics | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    adminApi
      .getDashboard()
      .then(setMetrics)
      .catch((e) => setError(e.message));
  }, []);

  if (error) return <p className="text-xs text-red-400">{t.error}: {error}</p>;
  if (!metrics) return <p className="text-xs text-slate-500">{t.loading}</p>;

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3">
        <StatCard label={t.totalUsers} value={metrics.totalUsers} />
        <StatCard label={t.activeSubscriptions} value={metrics.activeSubscriptions} />
        <StatCard label={t.totalBalance} value={`$${metrics.totalBalanceUsdt.toFixed(2)}`} />
        <StatCard label={t.totalTraffic} value={`${(metrics.totalTrafficUsedBytes / 1024 ** 3).toFixed(1)} GB`} />
        <StatCard label={t.onlineNodes} value={`${metrics.onlineNodes} / ${metrics.totalNodes}`} />
      </div>

      <div className="p-6 rounded-2xl bg-dark-850 border border-dark-800">
        <h3 className="font-bold text-sm mb-4">{t.degradationTitle}</h3>
        {metrics.telemetryDegradation.length === 0 ? (
          <p className="text-xs text-slate-500 text-center py-4">{t.degradationEmpty}</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="text-left text-slate-400 border-b border-dark-800">
                  <th className="py-2 pr-4 font-medium">{t.operator}</th>
                  <th className="py-2 pr-4 font-medium">{t.region}</th>
                  <th className="py-2 pr-4 font-medium">{t.transport}</th>
                  <th className="py-2 pr-4 font-medium text-right">{t.reports}</th>
                  <th className="py-2 font-medium text-right">{t.whitelistSuspected}</th>
                </tr>
              </thead>
              <tbody>
                {metrics.telemetryDegradation.map((row, i) => (
                  <tr key={i} className="border-b border-dark-800/60">
                    <td className="py-2 pr-4">{row.operator}</td>
                    <td className="py-2 pr-4">{row.region}</td>
                    <td className="py-2 pr-4">{row.transport}</td>
                    <td className="py-2 pr-4 text-right">{row.totalReports}</td>
                    <td className="py-2 text-right">{row.whitelistSuspected}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
