const { contextBridge, ipcRenderer } = require('electron');
const os = require('os');
const fs = require('fs');

let mouseMoveThrottle = 0;
window.addEventListener('mousemove', () => {
  const now = Date.now();
  if (now - mouseMoveThrottle > 500) {
    mouseMoveThrottle = now;
    ipcRenderer.send('cursor:activity');
  }
}, { passive: true });

function getDistroName() {
  try {
    if (fs.existsSync('/etc/os-release')) {
      const content = fs.readFileSync('/etc/os-release', 'utf-8');
      const match = content.match(/^PRETTY_NAME="?([^"\n]+)"?/m);
      if (match) return match[1];
    }
  } catch (_) {}
  return 'Linux';
}

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
      platform: 'linux',
      version: '1.0.0',
      osVersion: os.release(),
      arch: os.arch(),
      hostname: os.hostname(),
      distro: getDistroName(),
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
});
