const { contextBridge, ipcRenderer } = require('electron');
const os = require('os');

contextBridge.exposeInMainWorld('Android', {
  downloadMedia: (objectPath, signedUrl) => {
    ipcRenderer.send('media:download', objectPath, signedUrl);
  },
  getLocalMediaPath: (objectPath) => {
    return ipcRenderer.sendSync('media:getLocalPath', objectPath);
  },
  deleteMedia: (objectPath) => {
    return ipcRenderer.sendSync('media:delete', objectPath);
  },
  deleteAllMedia: () => {
    return ipcRenderer.sendSync('media:deleteAll');
  },
  getStorageInfo: () => {
    return ipcRenderer.sendSync('media:getStorageInfo');
  },
  getDeviceInfo: () => {
    return JSON.stringify({
      platform: 'windows',
      version: '2.0.0',
      osVersion: os.release(),
      arch: os.arch(),
      hostname: os.hostname(),
      totalMemory: os.totalmem(),
      cpus: os.cpus().length,
    });
  },
  setAutoRelaunch: (enabled) => {
    ipcRenderer.send('app:setAutoRelaunch', enabled);
  },
  scheduleRelaunch: () => {
    ipcRenderer.send('app:scheduleRelaunch');
  },
  restartApp: () => {
    ipcRenderer.send('app:scheduleRelaunch');
  },
  clearCache: () => {
    ipcRenderer.send('media:clearCache');
  },
  notifyPaired: (url) => {
    ipcRenderer.send('device:paired', url || '');
  },
});
