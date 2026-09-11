import { useEffect, useState } from 'react';
import type { DeviceDto } from '../types';
import { t } from '../i18n';

export default function DevicesPage() {
  const [devices, setDevices] = useState<DeviceDto[]>([]);
  const [error, setError] = useState<string | null>(null);

  const reload = () => {
    window.vpnApi
      .listDevices()
      .then(setDevices)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  };

  useEffect(reload, []);

  const revoke = async (id: number, deviceName: string) => {
    if (!window.confirm(t.confirmRevokeDevice.replace('%s', deviceName))) return;
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
      <p className="text-xs text-white/50 -mt-2">{t.thisDeviceAutoAdded}</p>

      <div className="flex flex-col gap-2">
        {devices.map((d) => (
          <div key={d.id} className="flex items-center justify-between rounded-lg bg-dark-900 px-4 py-3">
            <div>
              <p className="text-sm font-medium">{d.deviceName}</p>
              <p className="text-xs text-white/50">
                {d.platform} · {t.deviceAddedOn} {new Date(d.createdAt).toLocaleDateString()}
              </p>
            </div>
            <button className="text-xs text-state-error hover:underline" onClick={() => revoke(d.id, d.deviceName)}>
              {t.revoke}
            </button>
          </div>
        ))}
      </div>

      {error && <p className="text-sm text-state-error">{error}</p>}
    </div>
  );
}
