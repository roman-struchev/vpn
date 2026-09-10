import { useState } from 'react';
import { adminApi } from '../adminApi';
import { AdminT } from '../adminI18n';

export function PaymentsSection({ t }: { t: AdminT }) {
  const [chain, setChain] = useState('TRON');
  const [amount, setAmount] = useState('');
  const [txHash, setTxHash] = useState('');
  const [depositAddress, setDepositAddress] = useState('');
  const [result, setResult] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const reconcile = async () => {
    setError(null);
    setResult(null);
    setBusy(true);
    try {
      const amountMicro = Math.round(parseFloat(amount || '0') * 1_000_000);
      const res = await adminApi.reconcileDeposit(chain, amountMicro, txHash || undefined, depositAddress || undefined);
      if (res.status === 'MATCHED_AND_CREDITED') {
        setResult(t.matched.replace('{id}', String(res.invoiceId)).replace('{userId}', String(res.userId)));
      } else {
        setResult(t.unmatched);
      }
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const [quotaMsg, setQuotaMsg] = useState<string | null>(null);
  const [quotaBusy, setQuotaBusy] = useState(false);
  const runQuotas = async () => {
    setQuotaBusy(true);
    setQuotaMsg(null);
    try {
      const res = await adminApi.enforceQuotas();
      setQuotaMsg(res.message);
    } catch (e: any) {
      setQuotaMsg(e.message);
    } finally {
      setQuotaBusy(false);
    }
  };

  return (
    <div className="space-y-6 max-w-xl">
      <div className="p-6 rounded-2xl bg-dark-850 border border-dark-800 space-y-3">
        <h3 className="font-bold text-sm">{t.reconcileTitle}</h3>
        <p className="text-[11px] text-slate-400">{t.reconcileHint}</p>
        {error && <p className="text-xs text-red-400">{error}</p>}
        {result && <p className="text-xs text-emerald-400">{result}</p>}

        <div className="grid grid-cols-2 gap-2 text-xs">
          <div>
            <label className="block text-slate-400 mb-1">{t.chain}</label>
            <select value={chain} onChange={(e) => setChain(e.target.value)} className="w-full px-2 py-1.5 rounded-lg bg-dark-900 border border-dark-700">
              <option value="TRON">TRON</option>
              <option value="ETHEREUM">ETHEREUM</option>
              <option value="BASE">BASE</option>
              <option value="ARBITRUM">ARBITRUM</option>
              <option value="POLYGON">POLYGON</option>
            </select>
          </div>
          <div>
            <label className="block text-slate-400 mb-1">{t.amountUsdt}</label>
            <input
              type="number"
              step="0.000001"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              className="w-full px-2 py-1.5 rounded-lg bg-dark-900 border border-dark-700"
            />
          </div>
          <div className="col-span-2">
            <label className="block text-slate-400 mb-1">{t.txHash}</label>
            <input
              value={txHash}
              onChange={(e) => setTxHash(e.target.value)}
              className="w-full px-2 py-1.5 rounded-lg bg-dark-900 border border-dark-700"
            />
          </div>
          <div className="col-span-2">
            <label className="block text-slate-400 mb-1">{t.depositAddress}</label>
            <input
              value={depositAddress}
              onChange={(e) => setDepositAddress(e.target.value)}
              className="w-full px-2 py-1.5 rounded-lg bg-dark-900 border border-dark-700"
            />
          </div>
        </div>

        <button
          disabled={busy || !amount}
          onClick={reconcile}
          className="w-full py-2.5 rounded-xl bg-brand-500 hover:bg-brand-600 text-dark-950 font-bold text-xs disabled:opacity-50"
        >
          {t.reconcile}
        </button>
      </div>

      <div className="p-6 rounded-2xl bg-dark-850 border border-dark-800 space-y-3">
        <h3 className="font-bold text-sm">{t.enforceQuotas}</h3>
        <p className="text-[11px] text-slate-400">{t.enforceQuotasHint}</p>
        {quotaMsg && <p className="text-xs text-emerald-400">{quotaMsg}</p>}
        <button
          disabled={quotaBusy}
          onClick={runQuotas}
          className="px-4 py-2 rounded-xl bg-dark-800 hover:bg-dark-700 border border-dark-700 text-xs font-semibold disabled:opacity-50"
        >
          {t.run}
        </button>
      </div>
    </div>
  );
}
