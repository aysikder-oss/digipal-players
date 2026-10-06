const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync(`${__dirname}/../app/src/main/assets/smart-triggers.js`, 'utf8');

function setup() {
  const listeners = new Map(), calls = [], events = [], timers = [];
  const native = new Proxy({}, { get: (_, name) => (...args) => {
    calls.push([name, ...args]);
    if (name === 'getSmartTriggerPairingCode') return 'ABCD12';
    if (name === 'getConnectedDevices') return '[]';
  } });
  const window = {
    Android: native, __digipalBridgeToken: 'page-token',
    addEventListener(name, cb) { listeners.set(name, [...(listeners.get(name) || []), cb]); },
    dispatchEvent(event) {
      events.push(event);
      for (const cb of listeners.get(event.type) || []) cb(event);
    },
  };
  window.top = window;
  const storage = new Map();
  vm.runInNewContext(script, {
    window, TextEncoder, CustomEvent: class { constructor(type, data) { this.type = type; this.detail = data.detail; } },
    localStorage: { getItem: key => storage.get(key) || null },
    setInterval: fn => { timers.push(fn); return 1; }, clearInterval: () => {},
  });
  return { window, calls, events, timers, storage };
}

test('capability exists and every privileged operation supplies the current token', () => {
  const x = setup();
  assert.equal(x.window.smartTriggers.version, 1);
  x.window.__digipalBridgeToken = 'new-page-token';
  x.window.smartTriggers.startBleScan();
  assert.deepEqual(x.calls.at(-1), ['startBleScan', 'new-page-token']);
  assert.equal(x.calls.some(c => c[0] === 'getSmartTriggerToken'), false);
});
test('keyboard learn emits one capture, ignores repeated keys, and stops native learn', () => {
  const x = setup();
  x.window.smartTriggers.startLearnMode({});
  x.window.dispatchEvent({ type: 'keydown', repeat: true, code: 'KeyA', key: 'a' });
  x.window.dispatchEvent({ type: 'keydown', repeat: false, code: 'KeyA', key: 'a' });
  x.window.dispatchEvent({ type: 'keydown', repeat: false, code: 'KeyB', key: 'b' });
  assert.equal(x.events.filter(e => e.type === 'hw:signalCaptured').length, 1);
  assert.equal(x.events.find(e => e.type === 'hw:signalCaptured').detail.signalKey,
    Buffer.from('key_KeyA_a').toString('hex'));
});
test('native capture ends keyboard learn without a duplicate event transport', () => {
  const x = setup();
  x.window.smartTriggers.startLearnMode({});
  x.window.dispatchEvent({ type: 'hw:signalCaptured', detail: { deviceId: 'usb' } });
  x.window.dispatchEvent({ type: 'keydown', repeat: false, code: 'KeyA', key: 'a' });
  assert.equal(x.events.filter(e => e.type === 'hw:signalCaptured').length, 1);
  assert.equal(x.window.smartTriggers.onSignalCaptured, undefined);
});
test('reads only paired cached config, updates changed config once, and never starts sensors', () => {
  const x = setup();
  x.storage.set('tv_trigger_config:OTHER', '[{"triggerType":"sound"}]');
  x.storage.set('tv_trigger_config:ABCD12', '[{"triggerType":"sensor"}]');
  x.timers[0](); x.timers[0]();
  assert.equal(x.calls.filter(c => c[0] === 'setSmartTriggerConfig').length, 2);
  assert.equal(x.calls.some(c => c[0] === 'enableSmartTriggers' || c[0] === 'startBleScan'), false);
});
test('filtered hardware learn cannot accidentally capture a keyboard key', () => {
  const x = setup();
  x.window.smartTriggers.startLearnMode({ deviceId: 'usb-device' });
  x.window.dispatchEvent({ type: 'keydown', repeat: false, code: 'KeyA', key: 'a' });
  assert.equal(x.events.filter(e => e.type === 'hw:signalCaptured').length, 0);
});
test('adapter waits for the injected token instead of requesting a native token', () => {
  const x = setup();
  const before = x.calls.length;
  x.window.__digipalBridgeToken = '';
  x.timers[0]();
  assert.equal(x.calls.length, before);
});
