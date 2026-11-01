'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const { EventEmitter } = require('node:events');
const { registerNetworkDiscovery } = require('./network-discovery-ipc');

test('async IPC refuses untrusted frames; cancellation suppresses stale replies', async () => {
  const handlers = {};
  registerNetworkDiscovery({ handle: (name, fn) => { handlers[name] = fn; } }, e => e.allowed, () => []);
  await assert.rejects(handlers['network:discover']({ allowed: false }, 'x'), /unavailable/);
  const sender = new EventEmitter();
  sender.id = 1;
  sender.isDestroyed = () => false;
  const messages = [];
  sender.send = (_name, data) => messages.push(data);
  const event = { sender, allowed: true };
  const run = handlers['network:discover'](event, 'new');
  handlers['network:cancelDiscovery'](event, 'new');
  const result = await run;
  assert.deepEqual(result.servers, []);
  assert.ok(messages.every(m => m.id === 'new'));
  assert.equal(sender.listenerCount('did-start-navigation'), 0);
  assert.equal(sender.listenerCount('destroyed'), 0);
});
test('sandboxed Electron preload needs only electron and cleans progress listeners', () => {
  const preload = path.join(__dirname, 'preload.js');
  // Native build runs this against its actual packaged preload source.
  assert.ok(fs.existsSync(preload), 'Run this test in the Windows source/build staging directory.');
  const exposed = {}, events = new EventEmitter();
  vm.runInNewContext(fs.readFileSync(preload, 'utf8'), {
    require: name => {
      assert.equal(name, 'electron');
      return {
        contextBridge: { exposeInMainWorld: (name, value) => { exposed[name] = value; } },
        ipcRenderer: {
          on: (...args) => events.on(...args), removeListener: (...args) => events.removeListener(...args),
          send: () => {}, sendSync: () => '{"platform":"windows"}', invoke: async (_name, id) => ({ id, servers: [] }),
        },
      };
    },
  });
  assert.ok(exposed.DigipalHubDiscovery.discover);
  const remove = exposed.DigipalHubDiscovery.onProgress(() => {});
  assert.equal(events.listenerCount('network:discovery-progress'), 1);
  remove();
  assert.equal(events.listenerCount('network:discovery-progress'), 0);
  assert.equal(JSON.parse(exposed.Android.getDeviceInfo()).platform, 'windows');
});
