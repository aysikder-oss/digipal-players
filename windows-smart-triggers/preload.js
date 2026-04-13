const { contextBridge, ipcRenderer } = require('electron');

const HW_CHANNELS = [
  'hw:deviceConnected',
  'hw:deviceDisconnected',
  'hw:signalCaptured',
  'hw:signalEvent',
  'hw:bleScanRequested',
];

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
  setAutoRelaunch: (enabled) => {
    ipcRenderer.send('app:setAutoRelaunch', enabled);
  },
  scheduleRelaunch: () => {
    ipcRenderer.send('app:scheduleRelaunch');
  },
});

contextBridge.exposeInMainWorld('smartTriggers', {
  getConnectedDevices: () => ipcRenderer.invoke('hw:getConnectedDevices'),
  startLearnMode: () => ipcRenderer.invoke('hw:startLearnMode'),
  stopLearnMode: () => ipcRenderer.invoke('hw:stopLearnMode'),
  startBleScan: () => ipcRenderer.invoke('hw:startBleScan'),
  registerBleDevice: (deviceId, deviceName) => ipcRenderer.invoke('hw:registerBleDevice', deviceId, deviceName),
  handleBleSignal: (deviceId, rawDataHex) => ipcRenderer.invoke('hw:handleBleSignal', deviceId, rawDataHex),
  onDeviceConnected: (callback) => {
    ipcRenderer.on('hw:deviceConnected', (_event, device) => callback(device));
  },
  onDeviceDisconnected: (callback) => {
    ipcRenderer.on('hw:deviceDisconnected', (_event, device) => callback(device));
  },
  onSignalCaptured: (callback) => {
    ipcRenderer.on('hw:signalCaptured', (_event, signal) => callback(signal));
  },
  onSignalEvent: (callback) => {
    ipcRenderer.on('hw:signalEvent', (_event, signal) => callback(signal));
  },
  onBleScanRequested: (callback) => {
    ipcRenderer.on('hw:bleScanRequested', (_event) => callback());
  },
  removeAllHwListeners: () => {
    for (const ch of HW_CHANNELS) {
      ipcRenderer.removeAllListeners(ch);
    }
  },
});
