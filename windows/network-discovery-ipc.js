'use strict';
const NetworkScanner = require('./network-scanner');

function registerNetworkDiscovery(ipcMain, allowed, candidates) {
  const active = new Map();
  ipcMain.handle('network:discover', async (event, id) => {
    if (!allowed(event) || typeof id !== 'string' || id.length > 100) throw new Error('Discovery unavailable');
    const sender = event.sender;
    active.get(sender.id)?.scanner.stop();
    const scanner = new NetworkScanner();
    const run = { id, scanner };
    active.set(sender.id, run);
    const cancel = () => {
      if (active.get(sender.id) === run) { scanner.stop(); active.delete(sender.id); }
    };
    const navigation = (_event, _url, _inPlace, mainFrame) => { if (mainFrame) cancel(); };
    sender.once('destroyed', cancel);
    sender.on('did-start-navigation', navigation);
    try {
      const results = await scanner.scan(undefined, (message, found) => {
        if (!sender.isDestroyed() && active.get(sender.id) === run) {
          sender.send('network:discovery-progress', { id, message, servers: found.map(s => s.url) });
        }
      }, { firstOnly: false, candidates: candidates() });
      return { id, servers: active.get(sender.id) === run ? results.map(s => s.url) : [] };
    } finally {
      sender.removeListener('destroyed', cancel);
      sender.removeListener('did-start-navigation', navigation);
      cancel();
    }
  });
  ipcMain.handle('network:cancelDiscovery', (event, id) => {
    if (!allowed(event)) return;
    const run = active.get(event.sender.id);
    if (run?.id === id) { run.scanner.stop(); active.delete(event.sender.id); }
  });
}
module.exports = { registerNetworkDiscovery };
