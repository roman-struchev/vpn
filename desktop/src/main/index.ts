import { app, BrowserWindow, shell } from 'electron';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { initAutoUpdater } from './autoUpdater';
import { ApiClient } from './api/apiClient';
import { TokenStore } from './api/tokenStore';
import { registerIpcHandlers } from './ipc';
import { createSystemProxyManager } from './proxy/systemProxy';
import { VpnController } from './vpn/vpnController';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

let mainWindow: BrowserWindow | null = null;
let vpnController: VpnController | null = null;

function createWindow(): BrowserWindow {
  const win = new BrowserWindow({
    width: 400,
    height: 640,
    minWidth: 360,
    minHeight: 560,
    resizable: true,
    title: 'NextGen VPN',
    backgroundColor: '#0C0E12',
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

  if (process.env.ELECTRON_RENDERER_URL) {
    void win.loadURL(process.env.ELECTRON_RENDERER_URL);
  } else {
    void win.loadFile(path.join(__dirname, '../renderer/index.html'));
  }

  return win;
}

app.whenReady().then(() => {
  mainWindow = createWindow();

  const tokenStore = new TokenStore();
  const apiClient = new ApiClient(tokenStore);
  vpnController = new VpnController(apiClient, createSystemProxyManager());

  registerIpcHandlers(mainWindow, apiClient, vpnController);
  initAutoUpdater();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      mainWindow = createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

// Disconnecting on quit matters more than most apps: leaving the system
// proxy pointed at a dead local port would silently break the user's
// internet access after the app closes. Block the first quit attempt until
// teardown finishes, then let the (now no-op) second attempt through.
let quitTeardownDone = false;
app.on('before-quit', (event) => {
  if (quitTeardownDone || !vpnController) return;
  event.preventDefault();
  void vpnController.disconnect().finally(() => {
    quitTeardownDone = true;
    app.quit();
  });
});
