const { app, BrowserWindow, Menu, globalShortcut, dialog, shell, ipcMain, session, protocol, net, Tray, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');
const { HardwareManager } = require('./hardware');
const MediaManager = require('./media-manager');
const BonjourBrowser = require('./bonjour-browser');

const PLAYER_PATH = '/tv';
const CONFIG_FILE = 'config.json';

let mainWindow = null;
let loadingWindow = null;
let tray = null;
let kioskMode = false;
let hardwareManager = null;
let serverUrl = '';
let mediaManager = null;
let bonjourBrowser = null;

protocol.registerSchemesAsPrivileged([
  { scheme: 'local-media', privileges: { bypassCSP: true, stream: true, supportFetchAPI: true } }
]);

function getConfigPath() {
  return path.join(app.getPath('userData'), CONFIG_FILE);
}

function loadConfig() {
  try {
    const configPath = getConfigPath();
    if (fs.existsSync(configPath)) {
      const data = JSON.parse(fs.readFileSync(configPath, 'utf-8'));
      if (data.serverUrl) {
        serverUrl = data.serverUrl;
        return true;
      }
    }
  } catch (e) {
    console.error('Failed to load config:', e.message);
  }
  return false;
}

function saveConfig(url) {
  try {
    const configPath = getConfigPath();
    fs.writeFileSync(configPath, JSON.stringify({ serverUrl: url }, null, 2), 'utf-8');
    serverUrl = url;
  } catch (e) {
    console.error('Failed to save config:', e.message);
  }
}

function showLoadingScreen() {
  if (loadingWindow && !loadingWindow.isDestroyed()) return;
  loadingWindow = new BrowserWindow({
    width: 1920,
    height: 1080,
    fullscreen: true,
    frame: false,
    backgroundColor: '#ffffff',
    icon: path.join(__dirname, 'icon.png'),
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
    },
  });
  loadingWindow.loadFile(path.join(__dirname, 'loading.html'));
  loadingWindow.on('closed', () => { loadingWindow = null; });
}

function closeLoadingScreen() {
  if (loadingWindow && !loadingWindow.isDestroyed()) {
    loadingWindow.close();
    loadingWindow = null;
  }
}

function showSetupPrompt() {
  return new Promise((resolve) => {
    const promptWindow = new BrowserWindow({
      width: 1200,
      height: 720,
      resizable: true,
      frame: true,
      backgroundColor: '#ffffff',
      icon: path.join(__dirname, 'icon.png'),
      webPreferences: {
        nodeIntegration: true,
        contextIsolation: false,
      },
    });

    promptWindow.loadFile(path.join(__dirname, 'prompt.html'));

    const onSubmit = (_event, url) => {
      saveConfig(url);
      ipcMain.removeListener('server-cloud-selected', onCloudSelected);
      promptWindow.close();
      resolve(url);
    };

    const onCloudSelected = (_event, url) => {
      // Navigate to cloud URL without saving to disk — saved only after device:paired
      serverUrl = url;
      ipcMain.removeListener('server-url-submitted', onSubmit);
      promptWindow.close();
      resolve(url);
    };

    ipcMain.once('server-url-submitted', onSubmit);
    ipcMain.once('server-cloud-selected', onCloudSelected);

    promptWindow.on('closed', () => {
      ipcMain.removeListener('server-url-submitted', onSubmit);
      ipcMain.removeListener('server-cloud-selected', onCloudSelected);
      resolve(serverUrl || null);
      if (!serverUrl) {
        app.quit();
      }
    });
  });
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1920,
    height: 1080,
    fullscreen: true,
    autoHideMenuBar: true,
    backgroundColor: '#000000',
    icon: path.join(__dirname, 'icon.png'),
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      webSecurity: true,
      preload: path.join(__dirname, 'preload.js'),
    },
  });

  Menu.setApplicationMenu(null);

  session.defaultSession.setPermissionRequestHandler((webContents, permission, callback) => {
    const allowedPermissions = ['media', 'mediaKeySystem', 'geolocation', 'notifications', 'midi', 'midi-sysex', 'hid', 'serial'];
    callback(allowedPermissions.includes(permission));
  });

  session.defaultSession.setPermissionCheckHandler((webContents, permission) => {
    const allowedPermissions = ['media', 'mediaKeySystem', 'geolocation', 'notifications', 'midi', 'midi-sysex', 'hid', 'serial'];
    return allowedPermissions.includes(permission);
  });

  mainWindow.loadURL(serverUrl + PLAYER_PATH);

  mainWindow.webContents.on('did-fail-load', (event, errorCode, errorDescription) => {
    console.error(`Failed to load: ${errorDescription} (${errorCode})`);
    setTimeout(() => {
      console.log('Retrying connection...');
      mainWindow.loadURL(serverUrl + PLAYER_PATH);
    }, 5000);
  });

  mainWindow.on('close', (event) => {
    if (tray && !app.isQuiting) {
      event.preventDefault();
      mainWindow.hide();
      return;
    }
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    return { action: 'deny' };
  });

  if (mediaManager) {
    mediaManager.setWebContents(mainWindow.webContents);
  }

  globalShortcut.register('F11', () => {
    const isFullscreen = mainWindow.isFullScreen();
    mainWindow.setFullScreen(!isFullscreen);
  });

  globalShortcut.register('F5', () => {
    mainWindow.webContents.reload();
  });

  globalShortcut.register('CommandOrControl+Shift+I', () => {
    mainWindow.webContents.toggleDevTools();
  });

  globalShortcut.register('Escape', () => {
    if (!kioskMode && mainWindow.isFullScreen()) {
      mainWindow.setFullScreen(false);
    }
  });

  globalShortcut.register('CommandOrControl+Alt+S', async () => {
    const previousUrl = serverUrl;
    await showSetupPrompt();
    if (serverUrl !== previousUrl && mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.loadURL(serverUrl + PLAYER_PATH);
    }
  });

  hardwareManager = new HardwareManager();

  mainWindow.webContents.on('before-input-event', (event, input) => {
    if (hardwareManager && (input.type === 'keyDown' || input.type === 'rawKeyDown') && !input.isAutoRepeat) {
      if (hardwareManager.learnMode || hardwareManager.hasKeyboardFallbackDevices() || hardwareManager.hasKeyboardTypeDevices()) {
        console.log(`[HW] Keyboard input captured: type=${input.type} code=${input.code} key=${input.key}`);
        hardwareManager.handleKeyboardInput(input.code, input.key);
      }
    }
  });

  hardwareManager.on('deviceConnected', (device) => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('hw:deviceConnected', device);
    }
  });

  hardwareManager.on('deviceDisconnected', (device) => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('hw:deviceDisconnected', device);
    }
  });

  hardwareManager.on('signalCaptured', (signal) => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('hw:signalCaptured', signal);
    }
  });

  hardwareManager.on('signalEvent', (signal) => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('hw:signalEvent', signal);
    }
  });

  hardwareManager.on('bleScanRequested', () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('hw:bleScanRequested');
    }
  });

  hardwareManager.start();
}

ipcMain.handle('hw:getConnectedDevices', () => {
  if (!hardwareManager) return [];
  return hardwareManager.getConnectedDevices();
});

ipcMain.handle('hw:startLearnMode', (_event, payload) => {
  if (!hardwareManager) return { success: false };
  return hardwareManager.startLearnMode(payload);
});

ipcMain.handle('hw:stopLearnMode', () => {
  if (!hardwareManager) return { success: false };
  return hardwareManager.stopLearnMode();
});

ipcMain.handle('hw:startBleScan', () => {
  if (!hardwareManager) return { success: false };
  return hardwareManager.startBleScan();
});

ipcMain.handle('hw:registerBleDevice', (_event, deviceId, deviceName) => {
  if (!hardwareManager) return;
  hardwareManager.registerBleDevice(deviceId, deviceName);
});

ipcMain.handle('hw:handleBleSignal', (_event, deviceId, rawDataHex) => {
  if (!hardwareManager) return;
  hardwareManager.handleBleSignal(deviceId, rawDataHex);
});

function setupMediaIPC() {
  ipcMain.on('media:download', (event, objectPath, signedUrl) => {
    if (mediaManager) mediaManager.downloadMedia(objectPath, signedUrl);
  });

  ipcMain.on('media:getLocalPath', (event, objectPath) => {
    event.returnValue = mediaManager ? mediaManager.getLocalMediaPath(objectPath) : '';
  });

  ipcMain.on('media:delete', (event, objectPath) => {
    event.returnValue = mediaManager ? mediaManager.deleteMedia(objectPath) : false;
  });

  ipcMain.on('media:deleteAll', (event) => {
    event.returnValue = mediaManager ? mediaManager.deleteAllMedia() : 0;
  });

  ipcMain.on('media:getStorageInfo', (event) => {
    event.returnValue = mediaManager ? mediaManager.getStorageInfo() : '{"usedBytes":0,"freeBytes":0,"totalSpace":0,"totalFiles":0}';
  });

  ipcMain.on('app:setAutoRelaunch', (event, enabled) => {
    app.setLoginItemSettings({ openAtLogin: enabled });
  });

  ipcMain.on('app:scheduleRelaunch', () => {
    app.relaunch();
    app.exit(0);
  });

  ipcMain.on('bonjour:getServers', (event) => {
    event.returnValue = bonjourBrowser ? JSON.stringify(bonjourBrowser.getServers()) : '[]';
  });

  ipcMain.on('network:scanLocalServers', (event) => {
    const servers = bonjourBrowser ? bonjourBrowser.getServers() : [];
    event.returnValue = JSON.stringify(servers.map(s => s.url));
  });

  ipcMain.on('device:paired', (_event, url) => {
    const pairUrl = url || serverUrl;
    if (pairUrl) {
      saveConfig(pairUrl);
      console.log('[main] Device paired — config saved:', pairUrl);
    }
  });
}

function setupMediaIPC() {
  ipcMain.on('media:download', (event, objectPath, signedUrl) => {
    if (mediaManager) mediaManager.downloadMedia(objectPath, signedUrl);
  });

  ipcMain.on('media:getLocalPath', (event, objectPath) => {
    event.returnValue = mediaManager ? mediaManager.getLocalMediaPath(objectPath) : '';
  });

  ipcMain.on('media:delete', (event, objectPath) => {
    event.returnValue = mediaManager ? mediaManager.deleteMedia(objectPath) : false;
  });

  ipcMain.on('media:deleteAll', (event) => {
    event.returnValue = mediaManager ? mediaManager.deleteAllMedia() : 0;
  });

  ipcMain.on('media:getStorageInfo', (event) => {
    event.returnValue = mediaManager ? mediaManager.getStorageInfo() : '{"usedBytes":0,"freeBytes":0,"totalSpace":0,"totalFiles":0}';
  });

  ipcMain.on('media:clearCache', () => {
    if (mediaManager) mediaManager.deleteAllMedia();
  });

  ipcMain.on('app:setAutoRelaunch', (event, enabled) => {
    app.setLoginItemSettings({ openAtLogin: enabled, openAsHidden: false });
  });

  ipcMain.on('app:setKioskMode', (event, enabled) => {
    kioskMode = enabled;
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.setKiosk(enabled);
    }
  });

  ipcMain.on('app:scheduleRelaunch', () => {
    app.relaunch();
    app.exit(0);
  });

  ipcMain.on('device:paired', (_event, url) => {
    const pairUrl = url || serverUrl;
    if (pairUrl) {
      saveConfig(pairUrl);
      console.log('[main] Device paired — config saved:', pairUrl);
      // Auto-enable launch at login on first pairing
      app.setLoginItemSettings({ openAtLogin: true, openAsHidden: false });
    }
  });

  ipcMain.on('bonjour:getServers', (event) => {
    event.returnValue = bonjourBrowser ? JSON.stringify(bonjourBrowser.getServers()) : '[]';
  });
}

function createTray() {
  try {
    const iconPath = path.join(__dirname, 'icon.png');
    if (!fs.existsSync(iconPath)) return;
    const icon = nativeImage.createFromPath(iconPath).resize({ width: 22, height: 22 });
    tray = new Tray(icon);
    tray.on('click', () => {
      if (mainWindow) {
        if (mainWindow.isVisible()) {
          mainWindow.focus();
        } else {
          mainWindow.show();
          mainWindow.focus();
        }
      }
    });
    const contextMenu = Menu.buildFromTemplate([
      { label: `Digipal Player (Smart Triggers) v${app.getVersion()}`, enabled: false },
      { type: 'separator' },
      {
        label: 'Show Window',
        click: () => {
          if (mainWindow) { mainWindow.show(); mainWindow.focus(); }
        }
      },
      { type: 'separator' },
      { label: `Server: ${serverUrl || 'Not configured'}`, enabled: false },
      { type: 'separator' },
      {
        label: 'Reload (F5)',
        click: () => {
          if (mainWindow) mainWindow.webContents.reload();
        }
      },
      { type: 'separator' },
      {
        label: 'Quit',
        click: () => {
          app.isQuiting = true;
          if (hardwareManager) hardwareManager.stop();
          if (bonjourBrowser) bonjourBrowser.stop();
          app.quit();
        }
      }
    ]);
    tray.setContextMenu(contextMenu);
    tray.setToolTip('Digipal Player (Smart Triggers)');
  } catch (e) {
    console.error('Failed to create tray:', e.message);
  }
}

app.on('ready', async () => {
  protocol.handle('local-media', (request) => {
    const filePath = decodeURIComponent(request.url.replace('local-media://', ''));
    return net.fetch('file://' + filePath);
  });

  mediaManager = new MediaManager(app.getPath('userData'));
  mediaManager.cleanupOrphans();

  bonjourBrowser = new BonjourBrowser();
  bonjourBrowser.start();

  setupMediaIPC();
  createTray();

  // Stage 1: always show loading screen first
  showLoadingScreen();

  const hasConfig = loadConfig();
  if (hasConfig) {
    // Configured: brief loading then open player
    setTimeout(() => {
      closeLoadingScreen();
      if (serverUrl) createWindow();
    }, 1500);
  } else {
    // Unconfigured: hold loading 3s then show setup
    setTimeout(async () => {
      closeLoadingScreen();
      await showSetupPrompt();
      if (serverUrl) createWindow();
    }, 3000);
  }
});

app.on('window-all-closed', () => {
  // Keep running in system tray — quit only via tray menu
  if (!tray) {
    if (hardwareManager) {
      hardwareManager.stop();
    }
    if (bonjourBrowser) bonjourBrowser.stop();
    globalShortcut.unregisterAll();
    app.quit();
  }
});

app.on('activate', () => {
  if (mainWindow === null) {
    createWindow();
  }
});

