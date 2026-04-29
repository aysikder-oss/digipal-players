const os = require('os');
const http = require('http');

const SCAN_PORTS = [8787, 8788, 8789, 8790, 8791, 8792, 8793, 8794, 8795, 8796];
const PROBE_TIMEOUT_MS = 2000;
const MAX_PARALLEL = 20;

class NetworkScanner {
  constructor() {
    this.scanning = false;
  }

  getLocalSubnets() {
    const subnets = new Set();
    const interfaces = os.networkInterfaces();
    for (const name of Object.keys(interfaces)) {
      for (const iface of interfaces[name]) {
        if (iface.family === 'IPv4' && !iface.internal) {
          const parts = iface.address.split('.');
          if (parts.length === 4) {
            subnets.add(`${parts[0]}.${parts[1]}.${parts[2]}`);
          }
        }
      }
    }
    return Array.from(subnets);
  }

  generateScanTargets(subnet) {
    const targets = [];
    const priorityIPs = [1, 100, 200, 2, 3, 10, 50, 150, 254];
    for (const last of priorityIPs) {
      targets.push(`${subnet}.${last}`);
    }
    for (let i = 1; i <= 254; i++) {
      const ip = `${subnet}.${i}`;
      if (!targets.includes(ip)) {
        targets.push(ip);
      }
    }
    return targets;
  }

  probeHost(ip, port) {
    return new Promise((resolve) => {
      const url = `http://${ip}:${port}/api/health`;
      const req = http.get(url, { timeout: PROBE_TIMEOUT_MS }, (res) => {
        let body = '';
        res.on('data', (chunk) => { body += chunk; });
        res.on('end', () => {
          if (res.statusCode === 200) {
            resolve({ ip, port, url: `http://${ip}:${port}` });
          } else {
            resolve(null);
          }
        });
      });
      req.on('error', () => resolve(null));
      req.on('timeout', () => { req.destroy(); resolve(null); });
    });
  }

  async scan(onFound, onProgress) {
    if (this.scanning) return [];
    this.scanning = true;

    const subnets = this.getLocalSubnets();
    if (subnets.length === 0) {
      console.log('[network-scanner] No local network interfaces found');
      this.scanning = false;
      return [];
    }

    console.log(`[network-scanner] Scanning subnets: ${subnets.join(', ')}`);
    if (onProgress) onProgress('Scanning local network...');

    const found = [];

    for (const subnet of subnets) {
      const targets = this.generateScanTargets(subnet);
      
      for (let i = 0; i < targets.length; i += MAX_PARALLEL) {
        if (!this.scanning) break;
        
        const batch = targets.slice(i, i + MAX_PARALLEL);
        const probes = [];
        for (const ip of batch) {
          for (const port of SCAN_PORTS) {
            probes.push(this.probeHost(ip, port));
          }
        }

        const results = await Promise.all(probes);
        for (const result of results) {
          if (result) {
            console.log(`[network-scanner] Found Digipal Hub at ${result.url}`);
            found.push(result);
            if (onFound) onFound(result);
            this.scanning = false;
            return found;
          }
        }
      }
    }

    if (found.length === 0) {
      console.log('[network-scanner] No Digipal Hub found on local network');
    }
    this.scanning = false;
    return found;
  }

  stop() {
    this.scanning = false;
  }
}

module.exports = NetworkScanner;
