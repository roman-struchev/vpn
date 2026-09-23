import { useEffect, useState } from 'react';
import { DataTable } from 'primereact/datatable';
import { Column } from 'primereact/column';
import { Dialog } from 'primereact/dialog';
import { adminApi, AdminUser, userLabel } from '../adminApi';
import { AdminT } from '../adminI18n';
import { api } from '../../api';
import type { Tariff } from '../../types';

/**
 * True for accounts auto-created by the desktop app's no-signup trial flow
 * (POST /api/v1/auth/device, see DeviceAuthService) rather than an actual
 * registration/login — a deviceUuid with no telegramId means nobody ever
 * went through /register, /login or /telegram for this account.
 */
const isTrialDeviceUser = (u: AdminUser) => !!u.deviceUuid && !u.telegramId;

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

  const filtered = users.filter((u) => userLabel(u).toLowerCase().includes(search.toLowerCase()));

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
          <Column
            field="email"
            header={t.email}
            sortable
            body={(u: AdminUser) => (
              <span className="flex items-center gap-2">
                {userLabel(u)}
                {isTrialDeviceUser(u) && (
                  <span className="text-[10px] px-2 py-0.5 rounded-full font-semibold bg-amber-500/10 text-amber-400 whitespace-nowrap">
                    {t.trialDeviceLabel}
                  </span>
                )}
              </span>
            )}
          />
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
            header={t.traffic}
            style={{ width: '9rem' }}
            body={(u: AdminUser) =>
              u.activeSubscription
                ? `${(u.activeSubscription.trafficUsedBytes / 1024 ** 3).toFixed(2)} / ${(
                    u.activeSubscription.trafficLimitBytes /
                    1024 ** 3
                  ).toFixed(0)} GB`
                : t.noActiveSub
            }
          />
          <Column field="deviceCount" header={t.devices} style={{ width: '6rem' }} sortable />
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
  const [tariffs, setTariffs] = useState<Tariff[]>([]);
  const [tariffId, setTariffId] = useState('');
  const [tariffDays, setTariffDays] = useState('7');

  useEffect(() => {
    api
      .getTariffs()
      .then((list) => {
        setTariffs(list);
        if (list.length > 0) setTariffId(list[0].id);
      })
      .catch(() => undefined);
  }, []);

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
      header={userLabel(user)}
      visible
      onHide={onClose}
      className="admin-dialog"
      style={{ width: '32rem' }}
    >
      <div className="space-y-5 text-xs">
        {err && <p className="text-red-400">{err}</p>}
        {isTrialDeviceUser(user) && (
          <span className="inline-block text-[10px] px-2 py-0.5 rounded-full font-semibold bg-amber-500/10 text-amber-400">
            {t.trialDeviceLabel}
          </span>
        )}

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
          <div>{t.devices}: <span className="font-semibold">{user.deviceCount}</span></div>
          <div>{t.referrals}: <span className="font-semibold">{user.referralCount}</span></div>
          <div>
            {t.referralEarnings}:{' '}
            <span className="font-semibold">
              ${((user.referralEarningsUsdtMicro ?? 0) / 1_000_000).toFixed(2)}
            </span>
          </div>
        </div>

        {user.activeSubscription && (
          <div>
            <div className="flex items-center justify-between text-slate-400 mb-1">
              <span>{t.traffic}</span>
              <span>
                {(user.activeSubscription.trafficUsedBytes / 1024 ** 3).toFixed(2)} /{' '}
                {(user.activeSubscription.trafficLimitBytes / 1024 ** 3).toFixed(0)} GB
              </span>
            </div>
            <div className="w-full h-1.5 rounded-full bg-dark-900 overflow-hidden">
              <div
                className="h-full bg-brand-500 rounded-full"
                style={{
                  width: `${Math.min(
                    100,
                    (user.activeSubscription.trafficUsedBytes / Math.max(1, user.activeSubscription.trafficLimitBytes)) * 100
                  )}%`,
                }}
              />
            </div>
          </div>
        )}

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
              onClick={() => {
                const amountMicro = Math.round(parseFloat(amount || '0') * 1_000_000);
                // Confirm any non-zero adjustment — crediting/debiting arbitrary
                // USDT is higher-stakes than the xray-restart action on the Nodes
                // tab, which already gets a window.confirm guard. See
                // UX_REVIEW.md Quick Win #3.
                if (amountMicro !== 0) {
                  const formatted = `${amountMicro > 0 ? '+' : ''}$${(amountMicro / 1_000_000).toFixed(2)}`;
                  const msg = t.adjustBalanceConfirm.replace('{email}', userLabel(user)).replace('{amount}', formatted);
                  if (!window.confirm(msg)) return;
                }
                run(() => adminApi.adjustBalance(user.id, amountMicro, reason || undefined));
              }}
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

        <div className="pt-3 border-t border-dark-800 space-y-2">
          <label className="block text-slate-400" title={t.changeTariffHint}>
            {t.changeTariff}
          </label>
          {user.activeSubscription?.overrideTariffId && (
            <div className="flex items-center justify-between gap-2 text-amber-400">
              <span>
                {t.temporaryTariffActive
                  .replace('{tariff}', user.activeSubscription.overrideTariffId.toUpperCase())
                  .replace(
                    '{date}',
                    user.activeSubscription.overrideExpiresAt
                      ? new Date(user.activeSubscription.overrideExpiresAt).toLocaleString()
                      : ''
                  )}
              </span>
              <button
                disabled={busy}
                onClick={() => {
                  const msg = t.cancelTemporaryTariffConfirm.replace('{email}', userLabel(user));
                  if (!window.confirm(msg)) return;
                  run(() => adminApi.cancelTemporaryTariff(user.id));
                }}
                className="shrink-0 px-3 py-1.5 rounded-xl bg-dark-800 hover:bg-dark-700 border border-dark-700 font-semibold disabled:opacity-50"
              >
                {t.cancelTemporaryTariff}
              </button>
            </div>
          )}
          <div className="flex gap-2">
            <select
              value={tariffId}
              onChange={(e) => setTariffId(e.target.value)}
              className="flex-1 px-3 py-2 rounded-xl bg-dark-900 border border-dark-700 outline-none focus:border-brand-500"
            >
              {tariffs.map((tf) => (
                <option key={tf.id} value={tf.id}>
                  {tf.name} ({tf.id})
                </option>
              ))}
            </select>
            <input
              type="number"
              value={tariffDays}
              onChange={(e) => setTariffDays(e.target.value)}
              placeholder={t.days}
              className="w-24 px-3 py-2 rounded-xl bg-dark-900 border border-dark-700 outline-none focus:border-brand-500"
            />
            <button
              disabled={busy || !user.activeSubscription || !tariffId}
              onClick={() => {
                const tf = tariffs.find((x) => x.id === tariffId);
                const msg = t.changeTariffConfirm
                  .replace('{email}', userLabel(user))
                  .replace('{tariff}', (tf?.name ?? tariffId).toString())
                  .replace('{days}', tariffDays || '0');
                if (!window.confirm(msg)) return;
                run(() => adminApi.grantTemporaryTariff(user.id, tariffId, parseInt(tariffDays || '0', 10)));
              }}
              className="px-3 py-2 rounded-xl bg-dark-800 hover:bg-dark-700 border border-dark-700 font-semibold disabled:opacity-50"
            >
              {t.apply}
            </button>
          </div>
        </div>

        <div className="pt-3 border-t border-dark-800">
          <button
            disabled={busy}
            onClick={() => {
              const isBlocking = user.status !== 'BLOCKED';
              const msg = (isBlocking ? t.blockConfirm : t.unblockConfirm).replace('{email}', userLabel(user));
              if (!window.confirm(msg)) return;
              run(() => adminApi.setUserStatus(user.id, isBlocking ? 'BLOCKED' : 'ACTIVE'));
            }}
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
