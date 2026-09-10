import { useEffect, useState } from 'react';
import type { DeviceDto } from '../types';
import { t } from '../i18n';

export default function DevicesPage() {
  const [devices, setDevices] = useState<DeviceDto[]>([]);
  const [newName, setNewName] = useState('');
  const [error, setError] = useState<string | null>(null);

  const reload = () => {
    window.vpnApi
      .listDevices()
      .then(setDevices)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  };

  useEffect(reload, []);

  const add = async () => {
    if (!newName.trim()) return;
    try {
      // 'DESKTOP' isn't a real platform value anywhere else in the system
      // (server/web use ANDROID/WINDOWS/MACOS/THIRD_PARTY) — this manual form
      // is for reserving a slot for some other device, not necessarily this
      // machine (the running app auto-registers *this* install on connect,
      // see vpnController.registerOrTouchDevice), so there's no single
      // correct guess here; THIRD_PARTY is the closest existing "unspecified" value.
      await window.vpnApi.addDevice(newName.trim(), 'THIRD_PARTY');
      setNewName('');
      reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const revoke = async (id: number) => {
    try {
      await window.vpnApi.deleteDevice(id);
      reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <div className="flex flex-col gap-4 px-6 py-6">
      <h1 className="text-lg font-semibold">{t.devicesTitle}</h1>

      <div className="flex flex-col gap-2">
        {devices.map((d) => (
          <div key={d.id} className="flex items-center justify-between rounded-lg bg-dark-900 px-4 py-3">
            <div>
              <p className="text-sm font-medium">{d.deviceName}</p>
              <p className="text-xs text-white/50">{d.platform}</p>
            </div>
            <button className="text-xs text-state-error hover:underline" onClick={() => revoke(d.id)}>
              {t.revoke}
            </button>
          </div>
        ))}
      </div>

      <div className="flex gap-2">
        <input
          className="flex-1 rounded-lg border border-dark-800 bg-dark-900 px-3 py-2 text-sm outline-none focus:border-brand-500"
          placeholder={t.deviceName}
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && add()}
        />
        <button className="rounded-lg bg-brand-600 px-4 py-2 text-sm font-semibold hover:bg-brand-700" onClick={add}>
          {t.addDevice}
        </button>
      </div>

      {error && <p className="text-sm text-state-error">{error}</p>}
    </div>
  );
}
