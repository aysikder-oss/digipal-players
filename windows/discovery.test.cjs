'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const NetworkScanner = require('./network-scanner');
const { interfaceTargets, scanTargets } = NetworkScanner;
const nic = (address, netmask = '255.255.255.0') => ({ address, netmask, family: 'IPv4', internal: false });
const interfaces = { Ethernet: [nic('192.168.56.1')], WiFi: [nic('192.168.0.158')] };

test('round robin probes both interfaces, includes own IP and alternate ports', () => {
  const targets = scanTargets(interfaceTargets(interfaces));
  assert.deepEqual(targets.slice(0, 4), [
    { ip: '192.168.56.1', port: 8787 }, { ip: '192.168.0.158', port: 8787 },
    { ip: '192.168.56.1', port: 8788 }, { ip: '192.168.0.158', port: 8788 },
  ]);
  assert.equal(new Set(targets.map(t => `${t.ip}:${t.port}`)).size, targets.length);
});
test('actual masks support /23, /30, Wi-Fi-only, Ethernet-only and large networks bounded', () => {
  const [wide] = interfaceTargets({ WiFi: [nic('10.1.0.8', '255.255.254.0')] });
  assert.ok(wide.targets.includes('10.1.1.254'));
  const [small] = interfaceTargets({ Ethernet: [nic('10.1.0.1', '255.255.255.252')] });
  assert.deepEqual(new Set(small.targets), new Set(['10.1.0.1', '10.1.0.2']));
  assert.equal(interfaceTargets({ WiFi: interfaces.WiFi }).length, 1);
  assert.equal(interfaceTargets({ Ethernet: interfaces.Ethernet }).length, 1);
  assert.equal(interfaceTargets({ VPN: [nic('10.0.0.1', '255.0.0.0')] })[0].targets.length, 1024);
  assert.equal(interfaceTargets({ Loopback: [{ ...nic('127.0.0.1'), internal: true }] }).length, 0);
});
function requestFor(responses, calls = []) {
  return (url, _options, callback) => {
    calls.push(url);
    const req = new EventEmitter();
    req.destroy = () => {};
    queueMicrotask(() => {
      const response = responses[url];
      if (response === undefined) return req.emit('error', new Error('unreachable'));
      const res = new EventEmitter();
      res.statusCode = 200;
      callback(res);
      res.emit('data', Buffer.from(JSON.stringify(response)));
      res.emit('end');
    });
    return req;
  };
}
test('requires Hub identity, supports older Hub status, rejects generic 200', async () => {
  const origin = 'http://192.168.0.2:8788';
  const scanner = new NetworkScanner({ request: requestFor({
    [`${origin}/api/health`]: { status: 'ok' },
    [`${origin}/api/status`]: { isLocalServer: true, mode: 'local', status: 'running' },
  }) });
  assert.equal(await scanner.validateHub(origin, new AbortController().signal), true);
  const unrelated = new NetworkScanner({ request: requestFor({
    [`${origin}/api/health`]: { status: 'ok' }, [`${origin}/api/status`]: { status: 'running' },
  }) });
  assert.equal(await unrelated.validateHub(origin, new AbortController().signal), false);
  assert.equal(await unrelated.validateHub('http://user:pass@192.168.0.2', new AbortController().signal), false);
});
test('dead Ethernet does not block reachable Wi-Fi; no cloud calls; all addresses returned', async () => {
  const calls = [];
  const scanner = new NetworkScanner({ interfaces: () => interfaces, request: requestFor({
    'http://192.168.0.158:8788/api/health': { status: 'ok', service: 'digipal-local-hub', discoveryVersion: 1 },
    'http://192.168.0.2:8787/api/health': { status: 'ok', service: 'digipal-local-hub', discoveryVersion: 1 },
  }, calls) });
  const found = await scanner.scan(undefined, undefined, { firstOnly: false });
  assert.deepEqual(new Set(found.map(s => s.url)), new Set(['http://192.168.0.158:8788', 'http://192.168.0.2:8787']));
  assert.ok(calls.every(url => /^http:\/\/192\.168\./.test(url)));
});
test('search deadline destroys outstanding requests and manual cancellation stops work', async () => {
  let destroyed = 0;
  const request = () => { const req = new EventEmitter(); req.destroy = () => destroyed++; return req; };
  const scanner = new NetworkScanner({ interfaces: () => interfaces, request, budgetMs: 30 });
  assert.deepEqual(await scanner.scan(), []);
  assert.ok(destroyed > 0);
  const scan = scanner.scan();
  scanner.stop();
  assert.deepEqual(await scan, []);
});
test('all Bonjour candidates checked; bad first advertisement does not hide alternate reachable address', async () => {
  const scanner = new NetworkScanner({ interfaces: () => ({}), request: requestFor({
    'http://192.168.0.5:8790/api/health': { status: 'ok', service: 'digipal-local-hub', discoveryVersion: 1 },
  }) });
  const found = await scanner.scan(undefined, undefined, { firstOnly: false, candidates: [
    { url: 'http://192.168.56.1:8790' }, { url: 'http://192.168.0.5:8790' },
  ] });
  assert.deepEqual(found.map(s => s.url), ['http://192.168.0.5:8790']);
});
