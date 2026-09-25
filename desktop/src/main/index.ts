import { app, BrowserWindow, shell, powerMonitor } from 'electron';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { initAutoUpdater } from './autoUpdater';
import { ApiClient } from './api/apiClient';
import { flushDiagnostics, initDiagnostics, reportError } from './diagnostics';
import { installDohDispatcher } from './api/dohDispatcher';
import { TokenStore } from './api/tokenStore';
import { registerIpcHandlers } from './ipc';
import { RELAY_UNSUPPORTED_PREFIX, RelayManager } from './p2p/relayManager';
import { createSystemProxyManager } from './proxy/systemProxy';
import { createAppTray, type TrayHandle } from './tray';
import { VpnController } from './vpn/vpnController';
import { HTTP_PORT } from '../shared/xrayConfigFactory';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

let mainWindow: BrowserWindow | null = null;
let vpnController: VpnController | null = null;
let trayHandle: TrayHandle | null = null;
let relayManagerRef: RelayManager | null = null;


// Now that a tray icon exists, the main window's "X" (or the red traffic
// light on macOS) hides the window instead of quitting the whole app — the
// tray is meant for quick connect/disconnect *while the window is closed*
// (that's the entire point of the feature request), so closing the window
// must not tear down the VPN connection along with it. Quitting now only
// happens explicitly: the tray's Quit item, Cmd+Q / Alt+F4, or the OS
// shutting the app down. `isQuitting` distinguishes a real quit (let the
// window actually close) from the user just dismissing it (intercept and hide).
let isQuitting = false;

function createWindow(): BrowserWindow {
  const isMac = process.platform === 'darwin';
  const win = new BrowserWindow({
    width: 400,
    height: 640,
    minWidth: 360,
    minHeight: 560,
    resizable: true,
    title: 'Aura VPN',
    backgroundColor: '#0C0E12',
    titleBarStyle: isMac ? 'hiddenInset' : 'default',
    trafficLightPosition: isMac ? { x: 16, y: 16 } : undefined,
    webPreferences: {
      preload: path.join(__dirname, '../preload/index.mjs'),
      sandbox: false,
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  // Open non-app links (e.g. a future "get help" link) in the system browser
  // instead of navigating the app window to them.
  win.webContents.setWindowOpenHandler(({ url }) => {
    void shell.openExternal(url);
    return { action: 'deny' };
  });

  // Minimize-to-tray instead of destroying the window (see `isQuitting`
  // comment above for why). Only intercept when this isn't an actual quit.
  win.on('close', (event) => {
    if (isQuitting) return;
    event.preventDefault();
    win.hide();
  });

  if (process.env.ELECTRON_RENDERER_URL) {
    void win.loadURL(process.env.ELECTRON_RENDERER_URL);
  } else {
    void win.loadFile(path.join(__dirname, '../renderer/index.html'));
  }

  return win;
}

/** Shows the main window, creating one if it was actually destroyed (only happens during quit/on macOS reactivation). */
function showMainWindow(): void {
  if (!mainWindow || mainWindow.isDestroyed()) {
    mainWindow = createWindow();
    return;
  }
  if (mainWindow.isMinimized()) mainWindow.restore();
  mainWindow.show();
  mainWindow.focus();
}

app.whenReady().then(() => {
  installDohDispatcher();

  mainWindow = createWindow();

  const tokenStore = new TokenStore();
  const apiClient = new ApiClient(tokenStore);
  const systemProxyManager = createSystemProxyManager();

  // Before anything that can fail: from here on, an uncaught exception or a
  // rejected promise anywhere in the main process is collected and shipped
  // instead of only reaching a console nobody is reading. Keyed by this
  // install's device UUID so the server can tell one machine looping from a
  // problem everybody has — it identifies the install, not the person.
  initDiagnostics(apiClient, apiClient.getOrCreateDeviceUuid());

  // Clean up any stale system proxy setting from previous crashes/force quits (Stability 2.5)
  void systemProxyManager.disable().catch((err) => {
    console.warn('Failed to clean up stale system proxy on startup:', err);
    reportError('proxy', 'STALE_PROXY_CLEANUP_FAILED', 'Failed to clean up a stale system proxy setting on startup', err);
  });

  vpnController = new VpnController(apiClient, systemProxyManager);
  // Persisted since the choice used to reset to bypassRu on every launch.
  vpnController.setRussianRoutingMode(tokenStore.getRussianRoutingMode() ?? 'bypassRu');

  const relayManager = new RelayManager(apiClient, tokenStore);
  relayManagerRef = relayManager;
  // Silently resumes ALWAYS (survives a reboot: this app is itself a login
  // item while ALWAYS is active — see RelayManager#syncLoginItem) or a still
  // -unexpired TIMED window from before this launch (survives a plain app
  // restart). Only meaningful once actually signed in — a device-trial guest
  // or logged-out user has no p2p bootstrap token to mint in the first place,
  // and ApiClient's own auth header handling already no-ops such calls
  // safely, but there is deliberately nothing to resume before login anyway
  // since setP2pRelayMode is never reachable from LoginPage's UI.
  if (tokenStore.getToken()) {
    void relayManager.resumeIfNeeded().catch((err) => {
      // Refused because of the network: expected, shown in the relay section.
      if (err instanceof Error && err.message.startsWith(RELAY_UNSUPPORTED_PREFIX)) return;
      console.warn('[p2p relay] resume failed:', err);
      reportError('p2p-relay', 'RELAY_RESUME_FAILED', 'Could not resume the P2P relay window after launch', err);
    });
  }

  registerIpcHandlers(mainWindow, apiClient, vpnController, tokenStore, relayManager);
  initAutoUpdater({
    send: (channel, payload) => {
      if (mainWindow && !mainWindow.isDestroyed()) mainWindow.webContents.send(channel, payload);
    },
    tunnelProxyPort: () => (vpnController?.getState() === 'CONNECTED' ? HTTP_PORT : null),
  });
  trayHandle = createAppTray(vpnController, showMainWindow, relayManager, apiClient);

  powerMonitor.on('suspend', () => {
    console.log('System is suspending. Proxy safety active.');
  });
  powerMonitor.on('resume', () => {
    console.log('System resumed. Verifying connection state...');
    if (vpnController && vpnController.getState() === 'CONNECTED') {
      // Re-verify port or refresh connection if network interface changed during sleep
      void vpnController.checkLiveness();
    }
  });

  app.on('activate', () => {
    showMainWindow();
  });
});

// Windows/Linux previously quit the whole app here when the last window
// closed; now that closing the window hides it to the tray instead of
// destroying it (see the win.on('close', ...) handler above), this only
// fires in edge cases (e.g. a window destroyed some other way) — the tray's
// Quit item / before-quit below own normal app lifecycle now.
app.on('window-all-closed', () => {
  // Intentionally a no-op: do not quit here. See comment above.
});

// Disconnecting on quit matters more than most apps: leaving the system
// proxy pointed at a dead local port would silently break the user's
// internet access after the app closes. Block the first quit attempt until
// teardown finishes, then let the (now no-op) second attempt through.
let quitTeardownDone = false;
app.on('before-quit', (event) => {
  // Set unconditionally (not just on the branch below) and before the early
  // return: this is also what tells the window's 'close' handler to let a
  // real quit proceed instead of hiding to the tray, and that must be true
  // for every before-quit firing, including the immediate second one below.
  isQuitting = true;
  if (quitTeardownDone || !vpnController) return;
  event.preventDefault();
  // Relay-agent teardown (closing any live DataChannels/sockets cleanly)
  // matters for the same reason vpnController's does — best-effort, must
  // never block the actual quit if it hangs or errors.
  // flushDiagnostics alongside the teardown, not after it: a failure hit
  // during this last disconnect is exactly the kind that would otherwise
  // never be reported, since the process is about to be gone.
  void Promise.allSettled([vpnController.disconnect(), relayManagerRef?.shutdown(), flushDiagnostics()]).finally(() => {
    trayHandle?.destroy();
    quitTeardownDone = true;
    app.quit();
  });
});
