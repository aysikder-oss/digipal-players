const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('DigipalHubDiscovery', {
  discover: id => ipcRenderer.invoke('network:discover', id),
  cancel: id => ipcRenderer.invoke('network:cancelDiscovery', id),
  onProgress: callback => {
    const listener = (_event, data) => callback(data);
    ipcRenderer.on('network:discovery-progress', listener);
    return () => ipcRenderer.removeListener('network:discovery-progress', listener);
  },
});

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
    return ipcRenderer.sendSync('app:getDeviceInfo');
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
  scanLocalServers: () => {
    return ipcRenderer.sendSync('network:scanLocalServers');
  },
});
