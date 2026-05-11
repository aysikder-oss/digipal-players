const Bonjour = require('bonjour-service');

class BonjourBrowser {
  constructor() {
    this.bonjour = null;
    this.browser = null;
    this.servers = new Map();
  }

  start() {
    try {
      this.bonjour = new Bonjour.Bonjour();
      this.browser = this.bonjour.find({ type: 'digipal', protocol: 'tcp' });

      this.browser.on('up', (service) => {
        const address = service.addresses && service.addresses.length > 0
          ? service.addresses.find(a => a.includes('.')) || service.addresses[0]
          : service.host;
        const port = service.port || 3000;
        const url = `http://${address}:${port}`;

        this.servers.set(service.name, {
          name: service.name,
          url,
          host: address,
          port,
          txt: service.txt || {},
        });

        console.log(`[Bonjour] Found Digipal Hub: ${service.name} at ${url}`);
      });

      this.browser.on('down', (service) => {
        this.servers.delete(service.name);
        console.log(`[Bonjour] Lost Digipal Hub: ${service.name}`);
      });

      console.log('[Bonjour] Browsing for _digipal._tcp services...');
    } catch (e) {
      console.error('[Bonjour] Failed to start browser:', e.message);
    }
  }

  stop() {
    try {
      if (this.browser) {
        this.browser.stop();
        this.browser = null;
      }
      if (this.bonjour) {
        this.bonjour.destroy();
        this.bonjour = null;
      }
    } catch (e) {
      console.error('[Bonjour] Failed to stop:', e.message);
    }
    this.servers.clear();
  }

  getServers() {
    return Array.from(this.servers.values());
  }
}

module.exports = BonjourBrowser;
