'use strict';
const os = require('node:os');
const http = require('node:http');
const { isIPv4 } = require('node:net');
const { setMaxListeners } = require('node:events');

const PORTS = [8787, 8788, 8789, 8790, 8791, 8792, 8793, 8794, 8795, 8796];
const toInt = ip => ip.split('.').reduce((n, part) => ((n << 8) | Number(part)) >>> 0, 0);
const toIp = n => [24, 16, 8, 0].map(shift => (n >>> shift) & 255).join('.');
const usable = ip => isIPv4(ip) && !/^(0\.|127\.|169\.254\.)/.test(ip) && Number(ip.split('.')[0]) < 224;

function interfaceTargets(interfaces) {
  const groups = [];
  for (const [name, entries] of Object.entries(interfaces)) {
    for (const iface of entries || []) {
      if (iface.internal || iface.family !== 'IPv4' || !usable(iface.address) || !isIPv4(iface.netmask)) continue;
      const address = toInt(iface.address), mask = toInt(iface.netmask), inverse = (~mask) >>> 0;
      if (!mask || ((inverse & (inverse + 1)) >>> 0) !== 0) continue;
      const start = (address & mask) >>> 0, end = (start | inverse) >>> 0;
      const targets = new Set([iface.address]);
      // Own address first (Hub and Player can share a PC), then nearby hosts.
      // Every candidate is inside the actual netmask, including /23 and /30.
      const add = n => { if (n > start && n < end && targets.size < 1024) targets.add(toIp(n)); };
      const block = (address & 0xffffff00) >>> 0;
      for (const last of [1, 100, 200, 2, 3, 10, 50, 150, 254]) add(block + last);
      for (let i = 1; i <= 254; i++) add(block + i);
      for (let n = start + 1; n < end && targets.size < 1024; n++) add(n);
      groups.push({ name, targets: [...targets], limited: end - start - 1 > 1024 });
    }
  }
  return groups;
}

function scanTargets(groups) {
  const targets = [], seen = new Set();
  const longest = Math.max(0, ...groups.map(g => g.targets.length));
  // Round robin across adapters, not one entire subnet before the next.
  for (let i = 0; i < longest; i++) {
    for (const port of PORTS) {
      for (const group of groups) {
        const ip = group.targets[i];
        if (!ip) continue;
        const key = `${ip}:${port}`;
        if (!seen.has(key)) { seen.add(key); targets.push({ ip, port }); }
      }
    }
  }
  return targets;
}

class NetworkScanner {
  constructor(options = {}) {
    this.interfaces = options.interfaces || (() => os.networkInterfaces());
    this.request = options.request || http.get;
    this.timeoutMs = options.timeoutMs || 650;
    this.budgetMs = options.budgetMs || 25000;
    this.workers = options.workers || 96;
    this.run = null;
  }

  async readJson(url, signal) {
    return new Promise(resolve => {
      if (signal.aborted) return resolve(null);
      let req, timer, done = false;
      const finish = value => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        signal.removeEventListener('abort', abort);
        resolve(value);
        req?.destroy();
      };
      const abort = () => finish(null);
      signal.addEventListener('abort', abort, { once: true });
      timer = setTimeout(abort, this.timeoutMs);
      try {
        req = this.request(url, { timeout: this.timeoutMs }, res => {
          if (res.statusCode !== 200) return finish(null);
          let body = '';
          res.on('data', chunk => {
            body += chunk;
            if (body.length > 65536) finish(null);
          });
          res.on('error', abort);
          res.on('end', () => { try { finish(JSON.parse(body)); } catch { finish(null); } });
        });
        req.on('error', abort);
        req.on('timeout', abort);
      } catch { finish(null); }
    });
  }

  async validateHub(url, signal) {
    let parsed;
    try { parsed = new URL(url); } catch { return false; }
    if (parsed.protocol !== 'http:' || parsed.username || parsed.password || !usable(parsed.hostname)) return false;
    const health = await this.readJson(`${parsed.origin}/api/health`, signal);
    if (health?.status === 'ok' && health.service === 'digipal-local-hub' && health.discoveryVersion === 1) return true;
    if (!health || signal.aborted) return false;
    // Old Hubs lack the discovery marker. Do not trust a generic HTTP 200.
    const status = await this.readJson(`${parsed.origin}/api/status`, signal);
    return status?.isLocalServer === true && status.mode === 'local'
      && ['running', 'blocked', 'revoked'].includes(status.status);
  }

  async scan(onFound, onProgress, options = {}) {
    this.stop();
    const controller = new AbortController();
    this.run = controller;
    const { signal } = controller;
    setMaxListeners(0, signal);
    const groups = interfaceTargets(this.interfaces());
    const targets = scanTargets(groups);
    const found = [], seen = new Set();
    const report = message => onProgress?.(message, found.slice());
    const add = result => {
      if (signal.aborted || seen.has(result.url)) return;
      seen.add(result.url); found.push(result);
      onFound?.(result);
      report(`Found ${found.length} Local Hub address(es). Searching Wi-Fi and Ethernet…`);
      if (options.firstOnly !== false) controller.abort();
    };
    let cursor = 0, completed = 0;
    const timer = setTimeout(() => controller.abort(), this.budgetMs);
    report(groups.length ? `Searching ${groups.length} network interface(s), including Wi-Fi and Ethernet (up to ${Math.ceil(this.budgetMs / 1000)} seconds)…`
      : 'No usable IPv4 network interfaces. Connect to the same LAN as the Hub.');
    // Validate all advertised IPv4 addresses, in parallel with subnet scanning.
    const advertised = [...new Set((options.candidates || []).map(s => s.url))].slice(0, 32);
    try {
      await Promise.all([
        ...advertised.map(async url => { if (await this.validateHub(url, signal)) add({ url }); }),
        ...Array.from({ length: Math.min(this.workers, targets.length) }, async () => {
          while (!signal.aborted && cursor < targets.length) {
            const { ip, port } = targets[cursor++];
            const url = `http://${ip}:${port}`;
            if (await this.validateHub(url, signal)) add({ ip, port, url });
            completed++;
            if (completed % 400 === 0) report(`Searching Wi-Fi and Ethernet… ${completed} addresses/ports checked.`);
          }
        }),
      ]);
      if (!found.length && this.run === controller) report(groups.some(g => g.limited)
        ? 'No Hub found in the bounded search. Large networks may require a manual Hub address.'
        : 'No Hub found. Check the Hub is running, the same LAN is reachable, and Windows Firewall allows it.');
      return found;
    } finally {
      clearTimeout(timer);
      if (this.run === controller) this.run = null;
    }
  }

  stop() { this.run?.abort(); this.run = null; }
}

module.exports = NetworkScanner;
module.exports.interfaceTargets = interfaceTargets;
module.exports.scanTargets = scanTargets;
