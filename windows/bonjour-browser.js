const Bonjour = require('bonjour-service');
const NetworkScanner = require('./network-scanner');
const { isIPv4 } = require('node:net');

class BonjourBrowser {
  constructor() {
    this.bonjour = null;
    this.browser = null;
    this.servers = new Map();
    this.pending = new Map();
    this.validator = new NetworkScanner();
  }

  start() {
    try {
      this.bonjour = new Bonjour.Bonjour();
      this.browser = this.bonjour.find({ type: 'digipal', protocol: 'tcp' });
      this.browser.on('up', service => {
        this.pending.get(service.name)?.abort();
        const controller = new AbortController();
        this.pending.set(service.name, controller);
        const addresses = [...new Set([...(service.addresses || []), service.referer?.address].filter(ip => ip && isIPv4(ip)))];
        const port = service.port || 8787;
        if (!Number.isInteger(port) || port < 1 || port > 65535) return;
        // Never assume the first advertised interface is reachable.
        for (const host of addresses.slice(0, 32)) {
          const url = `http://${host}:${port}`;
          this.validator.validateHub(url, controller.signal).then(valid => {
            if (valid && !controller.signal.aborted) {
              this.servers.set(`${service.name}|${url}`, { name: service.name, url, host, port, txt: service.txt || {} });
            }
          }).catch(() => {});
        }
      });
      this.browser.on('down', service => {
        this.pending.get(service.name)?.abort();
        this.pending.delete(service.name);
        for (const key of this.servers.keys()) if (key.startsWith(`${service.name}|`)) this.servers.delete(key);
      });
    } catch (error) { console.error('[Bonjour] Discovery unavailable:', error.message); }
  }

  stop() {
    for (const controller of this.pending.values()) controller.abort();
    this.pending.clear();
    try { this.browser?.stop(); this.bonjour?.destroy(); } catch {}
    this.browser = null; this.bonjour = null; this.servers.clear();
  }
  getServers() { return Array.from(this.servers.values()); }
}
module.exports = BonjourBrowser;
