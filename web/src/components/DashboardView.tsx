import React, { useState, useEffect } from 'react';
import {
  ShieldCheck,
  Smartphone,
  Copy,
  Check,
  Plus,
  Trash2,
  AlertCircle,
  HelpCircle,
  Share2,
  QrCode,
  Sparkles,
} from 'lucide-react';
import { QRCodeSVG } from 'qrcode.react';
import { Lang, translations } from '../i18n';
import { UserProfile, Tariff, Device, CryptoInvoice } from '../types';
import { api } from '../api';

interface DashboardViewProps {
  lang: Lang;
  user: UserProfile;
  tariffs: Tariff[];
  onRefreshUser: () => void;
  openTopUp: boolean;
  setOpenTopUp: (open: boolean) => void;
}

export const DashboardView: React.FC<DashboardViewProps> = ({
  lang,
  user,
  tariffs,
  onRefreshUser,
  openTopUp,
  setOpenTopUp,
}) => {
  const t = translations[lang];

  const [devices, setDevices] = useState<Device[]>([]);
  const [links, setLinks] = useState<string[]>([]);
  const [copiedLink, setCopiedLink] = useState(false);
  const [showQr, setShowQr] = useState(false);

  // Add device form
  const [showAddDevice, setShowAddDevice] = useState(false);
  const [newDeviceName, setNewDeviceName] = useState('');
  const [newDevicePlatform, setNewDevicePlatform] = useState('ANDROID');
  const [deviceError, setDeviceError] = useState('');

  // Top-up modal states
  const [invoice, setInvoice] = useState<CryptoInvoice | null>(null);
  const [invoiceAmount, setInvoiceAmount] = useState('5');
  const [claimTxHash, setClaimTxHash] = useState('');
  const [claimAmount, setClaimAmount] = useState('5');
  const [claimStatus, setClaimStatus] = useState<string | null>(null);
  const [claimError, setClaimError] = useState<string | null>(null);

  // Selected tariff purchase
  const [purchasingTariffId, setPurchasingTariffId] = useState<string | null>(null);
  const [isAnnual, setIsAnnual] = useState(false);
  const [purchaseError, setPurchaseError] = useState<string | null>(null);

  useEffect(() => {
    loadData();
  }, []);

  const loadData = async () => {
    try {
      const [devs, vlessLinks] = await Promise.all([
        api.getDevices(),
        api.getSubscriptionLinks(),
      ]);
      setDevices(devs);
      setLinks(vlessLinks);
    } catch (err) {
      console.error('Failed to load dashboard data', err);
    }
  };

  const handleCopyLink = () => {
    if (links.length > 0) {
      navigator.clipboard.writeText(links[0]);
      setCopiedLink(true);
      setTimeout(() => setCopiedLink(false), 2000);
    }
  };

  const handleAddDevice = async (e: React.FormEvent) => {
    e.preventDefault();
    setDeviceError('');
    try {
      await api.addDevice(newDeviceName, newDevicePlatform);
      setShowAddDevice(false);
      setNewDeviceName('');
      loadData();
      onRefreshUser();
    } catch (err: any) {
      setDeviceError(err.message || 'Error adding device');
    }
  };

  const handleRevokeDevice = async (id: number) => {
    if (!confirm('Are you sure you want to revoke this device?')) return;
    try {
      await api.deleteDevice(id);
      loadData();
      onRefreshUser();
    } catch (err: any) {
      alert(err.message || 'Error revoking device');
    }
  };

  const handleCreateInvoice = async () => {
    try {
      const amountMicro = Math.round(parseFloat(invoiceAmount) * 1_000_000);
      const inv = await api.createCryptoInvoice('TRON', amountMicro);
      setInvoice(inv);
    } catch (err: any) {
      alert(err.message || 'Error generating invoice');
    }
  };

  const handleClaimTx = async (e: React.FormEvent) => {
    e.preventDefault();
    setClaimError(null);
    setClaimStatus(null);
    try {
      const amountMicro = Math.round(parseFloat(claimAmount) * 1_000_000);
      const res = await api.claimTx('TRON', claimTxHash, amountMicro);
      setClaimStatus(`Successfully credited $${(res.amountMicro / 1_000_000).toFixed(2)} USDT!`);
      setClaimTxHash('');
      onRefreshUser();
    } catch (err: any) {
      setClaimError(err.message || 'Failed to claim transaction');
    }
  };

  const handlePurchase = async (tariffId: string) => {
    setPurchaseError(null);
    setPurchasingTariffId(tariffId);
    try {
      await api.purchaseSubscription(tariffId, isAnnual);
      onRefreshUser();
      loadData();
    } catch (err: any) {
      setPurchaseError(err.message);
    } finally {
      setPurchasingTariffId(null);
    }
  };

  const sub = user.subscription;
  const usedGb = sub ? sub.trafficUsedBytes / (1024 * 1024 * 1024) : 0;
  const limitGb = sub ? sub.trafficLimitBytes / (1024 * 1024 * 1024) : 0;
  const trafficPercent = limitGb > 0 ? Math.min(100, Math.round((usedGb / limitGb) * 100)) : 0;

  return (
    <div className="w-full max-w-5xl mx-auto px-4 py-8 space-y-8">
      {/* Subscription Banner */}
      <div className="p-6 rounded-3xl bg-gradient-to-br from-dark-850 to-dark-800 border border-dark-800 shadow-xl relative overflow-hidden">
        <div className="flex flex-col md:flex-row items-start md:items-center justify-between gap-6 relative z-10">
          <div>
            <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 text-xs font-semibold mb-3">
              <ShieldCheck className="w-3.5 h-3.5" />
              <span>{sub ? `Active · ${sub.tariffId.toUpperCase()}` : t.noActiveSub}</span>
            </div>
            <h1 className="text-2xl font-bold tracking-tight">
              {sub ? `${t.traffic}: ${usedGb.toFixed(2)} / ${limitGb.toFixed(0)} GB` : t.noActiveSub}
            </h1>
            {sub && (
              <p className="text-xs text-slate-400 mt-1">
                {t.expiresAt}: {new Date(sub.expiresAt).toLocaleDateString()}
              </p>
            )}
          </div>

          {/* Quick Actions */}
          <div className="flex items-center gap-2 w-full md:w-auto">
            {links.length > 0 && (
              <>
                <button
                  onClick={handleCopyLink}
                  className="flex-1 md:flex-none flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl bg-brand-500 hover:bg-brand-600 text-dark-950 font-bold text-xs transition-all shadow-md shadow-brand-500/10"
                >
                  {copiedLink ? <Check className="w-4 h-4" /> : <Copy className="w-4 h-4" />}
                  <span>{copiedLink ? t.copied : t.copyLink}</span>
                </button>
                <button
                  onClick={() => setShowQr(true)}
                  className="p-2.5 rounded-xl bg-dark-800 hover:bg-dark-700 text-slate-200 transition-colors border border-dark-700"
                  title={t.qrCode}
                >
                  <QrCode className="w-4 h-4" />
                </button>
              </>
            )}
          </div>
        </div>

        {/* Progress Bar */}
        {sub && (
          <div className="mt-6 pt-6 border-t border-dark-800/80">
            <div className="w-full h-2 rounded-full bg-dark-900 overflow-hidden">
              <div
                className="h-full bg-gradient-to-r from-brand-500 to-emerald-400 rounded-full transition-all duration-500"
                style={{ width: `${trafficPercent}%` }}
              />
            </div>
          </div>
        )}
      </div>

      {/* Devices Section */}
      <div className="p-6 rounded-2xl bg-dark-850 border border-dark-800">
        <div className="flex items-center justify-between mb-6">
          <div>
            <h2 className="text-lg font-bold tracking-tight flex items-center gap-2">
              <Smartphone className="w-5 h-5 text-brand-500" />
              <span>{t.devices}</span>
            </h2>
            <p className="text-xs text-slate-400 mt-1">
              Active connections: {devices.length}
            </p>
          </div>
          <button
            onClick={() => setShowAddDevice(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-dark-800 hover:bg-dark-700 text-xs font-semibold text-slate-200 transition-colors border border-dark-700"
          >
            <Plus className="w-3.5 h-3.5" />
            <span>{t.addDevice}</span>
          </button>
        </div>

        {devices.length === 0 ? (
          <p className="text-xs text-slate-500 py-4 text-center">No devices added yet.</p>
        ) : (
          <div className="divide-y divide-dark-800">
            {devices.map((d) => (
              <div key={d.id} className="py-3 flex items-center justify-between">
                <div>
                  <h4 className="text-sm font-semibold">{d.deviceName}</h4>
                  <span className="text-[10px] uppercase tracking-wider text-slate-400">
                    {d.platform} · Added {new Date(d.createdAt).toLocaleDateString()}
                  </span>
                </div>
                <button
                  onClick={() => handleRevokeDevice(d.id)}
                  className="p-1.5 rounded-lg text-slate-400 hover:text-red-400 hover:bg-red-500/10 transition-colors"
                  title={t.revoke}
                >
                  <Trash2 className="w-4 h-4" />
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Tariffs Selection / Change */}
      <div className="p-6 rounded-2xl bg-dark-850 border border-dark-800">
        <div className="flex items-center justify-between mb-6">
          <h2 className="text-lg font-bold tracking-tight flex items-center gap-2">
            <Sparkles className="w-5 h-5 text-brand-500" />
            <span>{t.tariffs}</span>
          </h2>
          <div className="flex items-center gap-1 p-0.5 rounded-lg bg-dark-800 border border-dark-700 text-xs">
            <button
              onClick={() => setIsAnnual(false)}
              className={`px-3 py-1 rounded-md transition-all ${
                !isAnnual ? 'bg-brand-500 text-dark-950 font-bold' : 'text-slate-400'
              }`}
            >
              {t.monthly}
            </button>
            <button
              onClick={() => setIsAnnual(true)}
              className={`px-3 py-1 rounded-md transition-all ${
                isAnnual ? 'bg-brand-500 text-dark-950 font-bold' : 'text-slate-400'
              }`}
            >
              {t.annual}
            </button>
          </div>
        </div>

        {purchaseError && (
          <div className="mb-4 p-3 rounded-xl bg-red-500/10 border border-red-500/20 text-red-400 text-xs flex items-center gap-2">
            <AlertCircle className="w-4 h-4 shrink-0" />
            <span>{purchaseError}</span>
          </div>
        )}

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {tariffs.map((tariff) => {
            const priceMicro = isAnnual
              ? tariff.annualPriceUsdtMicro
              : tariff.monthlyPriceUsdtMicro;
            const price = priceMicro / 1_000_000;
            const isCurrent = sub?.tariffId === tariff.id;

            return (
              <div
                key={tariff.id}
                className="p-4 rounded-xl bg-dark-900 border border-dark-800 flex flex-col justify-between"
              >
                <div>
                  <div className="flex items-center justify-between">
                    <h4 className="font-bold text-sm">{tariff.name}</h4>
                    {isCurrent && (
                      <span className="text-[10px] px-2 py-0.5 rounded-full bg-emerald-500/10 text-emerald-400 font-bold">
                        Current
                      </span>
                    )}
                  </div>
                  <div className="mt-2 text-xl font-extrabold">
                    ${price.toFixed(price % 1 === 0 ? 0 : 2)}
                    <span className="text-xs font-normal text-slate-400">
                      {isAnnual ? '/yr' : '/mo'}
                    </span>
                  </div>
                  <p className="text-xs text-slate-400 mt-2">
                    {Math.round(tariff.trafficQuotaBytes / (1024 * 1024 * 1024))} GB · {tariff.maxDevices} devices
                  </p>
                </div>

                <button
                  onClick={() => handlePurchase(tariff.id)}
                  disabled={purchasingTariffId === tariff.id}
                  className="mt-4 w-full py-2 rounded-lg bg-dark-800 hover:bg-brand-500 hover:text-dark-950 text-xs font-semibold text-slate-200 transition-colors border border-dark-700"
                >
                  {purchasingTariffId === tariff.id ? 'Processing...' : t.buyWithBalance}
                </button>
              </div>
            );
          })}
        </div>
      </div>

      {/* Referral & Diagnostics Row */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        {/* Referral */}
        <div className="p-6 rounded-2xl bg-dark-850 border border-dark-800">
          <h3 className="font-bold text-base mb-2 flex items-center gap-2">
            <Share2 className="w-4 h-4 text-brand-500" />
            <span>{t.referralProgram}</span>
          </h3>
          <p className="text-xs text-slate-400 leading-relaxed mb-4">
            {t.referralDesc}
          </p>
          <div className="flex items-center gap-2 p-2 rounded-xl bg-dark-900 border border-dark-800 text-xs">
            <input
              type="text"
              readOnly
              value={`https://t.me/MyVpnBot?start=${user.referralCode}`}
              className="bg-transparent flex-1 outline-none text-slate-300 select-all"
            />
            <button
              onClick={() => {
                navigator.clipboard.writeText(`https://t.me/MyVpnBot?start=${user.referralCode}`);
                alert('Invite link copied!');
              }}
              className="p-1.5 rounded-lg bg-dark-800 hover:bg-dark-700 text-slate-300"
            >
              <Copy className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>

        {/* Diagnostics */}
        <div className="p-6 rounded-2xl bg-dark-850 border border-dark-800">
          <h3 className="font-bold text-base mb-2 flex items-center gap-2">
            <HelpCircle className="w-4 h-4 text-brand-500" />
            <span>{t.diagnostics}</span>
          </h3>
          <p className="text-xs text-slate-400 leading-relaxed">
            {t.diagnosticsText}
          </p>
        </div>
      </div>

      {/* QR Code Modal */}
      {showQr && links.length > 0 && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4">
          <div className="bg-dark-850 border border-dark-800 rounded-3xl p-6 max-w-sm w-full flex flex-col items-center">
            <h3 className="font-bold text-base mb-4">{t.qrCode}</h3>
            <div className="p-4 bg-white rounded-2xl mb-4">
              <QRCodeSVG value={links[0]} size={200} />
            </div>
            <p className="text-[11px] text-slate-400 text-center mb-6">
              Scan with v2rayTun, Hiddify, or Happ camera to connect.
            </p>
            <button
              onClick={() => setShowQr(false)}
              className="w-full py-2.5 rounded-xl bg-dark-800 hover:bg-dark-700 text-xs font-bold text-white transition-colors"
            >
              {t.close}
            </button>
          </div>
        </div>
      )}

      {/* Add Device Modal */}
      {showAddDevice && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4">
          <form
            onSubmit={handleAddDevice}
            className="bg-dark-850 border border-dark-800 rounded-3xl p-6 max-w-sm w-full"
          >
            <h3 className="font-bold text-base mb-4">{t.addDevice}</h3>
            {deviceError && (
              <p className="text-xs text-red-400 mb-3">{deviceError}</p>
            )}
            <div className="space-y-4">
              <div>
                <label className="block text-xs text-slate-400 mb-1">{t.deviceName}</label>
                <input
                  type="text"
                  required
                  placeholder="e.g. Phone, Laptop"
                  value={newDeviceName}
                  onChange={(e) => setNewDeviceName(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl bg-dark-900 border border-dark-700 text-xs outline-none focus:border-brand-500"
                />
              </div>
              <div>
                <label className="block text-xs text-slate-400 mb-1">{t.platform}</label>
                <select
                  value={newDevicePlatform}
                  onChange={(e) => setNewDevicePlatform(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl bg-dark-900 border border-dark-700 text-xs outline-none focus:border-brand-500"
                >
                  <option value="ANDROID">Android</option>
                  <option value="WINDOWS">Windows</option>
                  <option value="MACOS">macOS</option>
                  <option value="THIRD_PARTY">Universal / Other</option>
                </select>
              </div>
            </div>

            <div className="mt-6 flex gap-2">
              <button
                type="button"
                onClick={() => setShowAddDevice(false)}
                className="flex-1 py-2 rounded-xl bg-dark-800 hover:bg-dark-700 text-xs font-semibold text-slate-300"
              >
                {t.close}
              </button>
              <button
                type="submit"
                className="flex-1 py-2 rounded-xl bg-brand-500 hover:bg-brand-600 text-dark-950 text-xs font-bold"
              >
                {t.addDevice}
              </button>
            </div>
          </form>
        </div>
      )}

      {/* Top-up Modal */}
      {openTopUp && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4 overflow-y-auto">
          <div className="bg-dark-850 border border-dark-800 rounded-3xl p-6 max-w-md w-full space-y-6">
            <div className="flex items-center justify-between">
              <h3 className="font-bold text-base">{t.topUp} (TRC-20 USDT)</h3>
              <button
                onClick={() => setOpenTopUp(false)}
                className="text-slate-400 hover:text-white text-xs"
              >
                ✕
              </button>
            </div>

            {/* Invoice Generator */}
            <div>
              <label className="block text-xs text-slate-400 mb-1">Select Amount (USDT)</label>
              <div className="flex gap-2 mb-3">
                {['1', '5', '10', '20'].map((amt) => (
                  <button
                    key={amt}
                    onClick={() => setInvoiceAmount(amt)}
                    className={`flex-1 py-1.5 rounded-lg text-xs font-semibold border ${
                      invoiceAmount === amt
                        ? 'bg-brand-500 text-dark-950 border-brand-500'
                        : 'bg-dark-900 border-dark-700 text-slate-300'
                    }`}
                  >
                    ${amt}
                  </button>
                ))}
              </div>
              <button
                onClick={handleCreateInvoice}
                className="w-full py-2.5 rounded-xl bg-brand-500 hover:bg-brand-600 text-dark-950 font-bold text-xs"
              >
                Get Deposit Address
              </button>
            </div>

            {invoice && (
              <div className="p-4 rounded-xl bg-dark-900 border border-dark-700 space-y-3">
                <div className="flex justify-center p-2 bg-white rounded-lg">
                  <QRCodeSVG value={invoice.recipientAddress} size={140} />
                </div>
                <div>
                  <span className="text-[11px] text-slate-400">Exact amount to send:</span>
                  <div className="text-base font-bold text-brand-400">
                    {(invoice.expectedAmountUsdtMicro / 1_000_000).toFixed(6)} USDT
                  </div>
                  <span className="text-[10px] text-slate-500">
                    Acceptable window: {(invoice.toleranceMinMicro / 1_000_000).toFixed(6)} - {(invoice.toleranceMaxMicro / 1_000_000).toFixed(6)}
                  </span>
                </div>
                <div>
                  <span className="text-[11px] text-slate-400">TRC-20 Address:</span>
                  <div className="text-xs font-mono break-all text-slate-200 mt-0.5">
                    {invoice.recipientAddress}
                  </div>
                </div>
              </div>
            )}

            {/* "I paid, here is the hash" form */}
            <form onSubmit={handleClaimTx} className="pt-4 border-t border-dark-800 space-y-3">
              <h4 className="font-bold text-xs text-slate-300">{t.claimTxTitle}</h4>
              <p className="text-[11px] text-slate-400 leading-relaxed">{t.claimTxDesc}</p>

              {claimStatus && (
                <p className="text-xs text-emerald-400">{claimStatus}</p>
              )}
              {claimError && (
                <p className="text-xs text-red-400">{claimError}</p>
              )}

              <input
                type="text"
                required
                placeholder="Transaction Hash (TxID)"
                value={claimTxHash}
                onChange={(e) => setClaimTxHash(e.target.value)}
                className="w-full px-3 py-2 rounded-xl bg-dark-900 border border-dark-700 text-xs outline-none focus:border-brand-500"
              />
              <div className="flex gap-2">
                <input
                  type="number"
                  step="0.01"
                  required
                  placeholder="Amount in USDT"
                  value={claimAmount}
                  onChange={(e) => setClaimAmount(e.target.value)}
                  className="w-32 px-3 py-2 rounded-xl bg-dark-900 border border-dark-700 text-xs outline-none focus:border-brand-500"
                />
                <button
                  type="submit"
                  className="flex-1 py-2 rounded-xl bg-dark-800 hover:bg-dark-700 text-xs font-semibold text-slate-200 border border-dark-700"
                >
                  {t.claimBtn}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
