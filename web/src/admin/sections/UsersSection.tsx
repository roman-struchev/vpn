import { useEffect, useState } from 'react';
import { DataTable } from 'primereact/datatable';
import { Column } from 'primereact/column';
import { Dialog } from 'primereact/dialog';
import { adminApi, AdminUser } from '../adminApi';
import { AdminT } from '../adminI18n';

export function UsersSection({ t }: { t: AdminT }) {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<AdminUser | null>(null);

  const load = () => {
    setLoading(true);
    adminApi
      .listUsers()
      .then(setUsers)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const filtered = users.filter((u) => u.email.toLowerCase().includes(search.toLowerCase()));

  return (
    <div className="space-y-4">
      {error && <p className="text-xs text-red-400">{t.error}: {error}</p>}
      <input
        type="text"
        placeholder={t.searchUsers}
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        className="w-full max-w-sm px-3 py-2 rounded-xl bg-dark-900 border border-dark-700 text-xs outline-none focus:border-brand-500"
      />

      <div className="admin-table rounded-2xl overflow-hidden border border-dark-800">
        <DataTable
          value={filtered}
          loading={loading}
          paginator
          rows={15}
          dataKey="id"
          size="small"
          sortField="createdAt"
          sortOrder={-1}
          emptyMessage={t.loading}
          onRowClick={(e) => setSelected(e.data as AdminUser)}
          selectionMode="single"
        >
          <Column field="id" header="ID" style={{ width: '4rem' }} sortable />
          <Column field="email" header={t.email} sortable />
          <Column field="role" header={t.role} style={{ width: '6rem' }} />
          <Column
            field="status"
            header={t.status}
            style={{ width: '7rem' }}
            body={(u: AdminUser) => (
              <span
                className={`text-[10px] px-2 py-0.5 rounded-full font-semibold ${
                  u.status === 'BLOCKED' ? 'bg-red-500/10 text-red-400' : 'bg-emerald-500/10 text-emerald-400'
                }`}
              >
                {u.status}
              </span>
            )}
          />
          <Column
            header={t.balance}
            style={{ width: '7rem' }}
            body={(u: AdminUser) => `$${(u.balanceUsdtMicro / 1_000_000).toFixed(2)}`}
            sortField="balanceUsdtMicro"
            sortable
          />
          <Column
            header={t.subscription}
            body={(u: AdminUser) => u.activeSubscription?.tariffId.toUpperCase() ?? t.noActiveSub}
          />
          <Column
            header={t.registered}
            body={(u: AdminUser) => new Date(u.createdAt).toLocaleDateString()}
          />
        </DataTable>
      </div>

      {selected && (
        <UserDetailDialog
          user={selected}
          t={t}
          onClose={() => setSelected(null)}
          onChanged={() => {
            load();
            setSelected(null);
          }}
        />
      )}
    </div>
  );
}

function UserDetailDialog({
  user,
  t,
  onClose,
  onChanged,
}: {
  user: AdminUser;
  t: AdminT;
  onClose: () => void;
  onChanged: () => void;
}) {
  const [amount, setAmount] = useState('0');
  const [reason, setReason] = useState('');
  const [days, setDays] = useState('30');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const run = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setErr(null);
    try {
      await fn();
      onChanged();
    } catch (e: any) {
      setErr(e.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      header={user.email}
      visible
      onHide={onClose}
      className="admin-dialog"
      style={{ width: '32rem' }}
    >
      <div className="space-y-5 text-xs">
        {err && <p className="text-red-400">{err}</p>}

        <div className="grid grid-cols-2 gap-3 text-slate-300">
          <div>{t.role}: <span className="font-semibold">{user.role}</span></div>
          <div>{t.status}: <span className="font-semibold">{user.status}</span></div>
          <div>{t.balance}: <span className="font-semibold">${(user.balanceUsdtMicro / 1_000_000).toFixed(2)}</span></div>
          <div>
            {t.subscription}:{' '}
            <span className="font-semibold">
              {user.activeSubscription
                ? `${user.activeSubscription.tariffId.toUpperCase()} · ${new Date(
                    user.activeSubscription.currentPeriodEnd
                  ).toLocaleDateString()}`
                : t.noActiveSub}
            </span>
          </div>
        </div>

        <div className="pt-3 border-t border-dark-800 space-y-2">
          <label className="block text-slate-400">{t.adjustBalance}</label>
          <div className="flex gap-2">
            <input
              type="number"
              step="0.01"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder={t.amountUsdt}
              className="w-32 px-3 py-2 rounded-xl bg-dark-900 border border-dark-700 outline-none focus:border-brand-500"
            />
            <input
              type="text"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder={t.reason}
              className="flex-1 px-3 py-2 rounded-xl bg-dark-900 border border-dark-700 outline-none focus:border-brand-500"
            />
            <button
              disabled={busy}
              onClick={() =>
                run(() =>
                  adminApi.adjustBalance(user.id, Math.round(parseFloat(amount || '0') * 1_000_000), reason || undefined)
                )
              }
              className="px-3 py-2 rounded-xl bg-brand-500 hover:bg-brand-600 text-dark-950 font-bold disabled:opacity-50"
            >
              {t.apply}
            </button>
          </div>
        </div>

        <div className="pt-3 border-t border-dark-800 space-y-2">
          <label className="block text-slate-400">{t.extendSub}</label>
          <div className="flex gap-2">
            <input
              type="number"
              value={days}
              onChange={(e) => setDays(e.target.value)}
              placeholder={t.days}
              className="w-24 px-3 py-2 rounded-xl bg-dark-900 border border-dark-700 outline-none focus:border-brand-500"
            />
            <button
              disabled={busy || !user.activeSubscription}
              onClick={() => run(() => adminApi.extendSubscription(user.id, parseInt(days || '0', 10)))}
              className="px-3 py-2 rounded-xl bg-dark-800 hover:bg-dark-700 border border-dark-700 font-semibold disabled:opacity-50"
            >
              {t.apply}
            </button>
          </div>
        </div>

        <div className="pt-3 border-t border-dark-800">
          <button
            disabled={busy}
            onClick={() =>
              run(() => adminApi.setUserStatus(user.id, user.status === 'BLOCKED' ? 'ACTIVE' : 'BLOCKED'))
            }
            className={`w-full py-2 rounded-xl font-bold disabled:opacity-50 ${
              user.status === 'BLOCKED'
                ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20'
                : 'bg-red-500/10 text-red-400 border border-red-500/20'
            }`}
          >
            {user.status === 'BLOCKED' ? t.unblock : t.block}
          </button>
        </div>
      </div>
    </Dialog>
  );
}
