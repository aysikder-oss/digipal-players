const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');

const root = path.join(__dirname, '../app/src/main/assets/mediapipe/0.10.32');
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'manifest.json'), 'utf8'));

test('resources are byte-identical to the immutable 0.10.32 package', () => {
  assert.equal(manifest.version, '0.10.32');
  assert.equal(Object.keys(manifest.files).length, 4);
  for (const [name, expected] of Object.entries(manifest.files)) {
    const bytes = fs.readFileSync(path.join(root, name));
    assert.equal(bytes.length, expected.bytes);
    assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'), expected.sha256);
  }
});

for (const variant of ['wasm', 'wasm_nosimd']) {
  test(`${variant} loader initializes with its packaged binary (export-assignment regression)`, async () => {
    const prefix = `vision_${variant}_internal`;
    // Evaluate the original loader as CommonJS without adding package dependencies.
    const context = {
      module: { exports: {} }, exports: {}, require, process,
      __dirname: root, __filename: path.join(root, `${prefix}.js`),
      console, WebAssembly, Buffer, TextDecoder, TextEncoder, performance, setTimeout, clearTimeout
    };
    vm.runInNewContext(fs.readFileSync(path.join(root, `${prefix}.js`), 'utf8'), context);
    const instance = await context.module.exports({
      wasmBinary: fs.readFileSync(path.join(root, `${prefix}.wasm`))
    });
    assert.equal(instance.calledRun, true);
    assert.ok(instance.HEAPU8 instanceof Uint8Array || instance.HEAPU8?.byteLength > 0);
    assert.equal(typeof instance._malloc, 'function');
    assert.equal(typeof instance._free, 'function');
  });
}
