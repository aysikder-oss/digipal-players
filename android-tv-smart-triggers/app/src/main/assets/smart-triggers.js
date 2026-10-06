(function () {
  'use strict';
  if (window.top !== window || window.smartTriggers) return;
  const native = window.Android;
  if (!native || typeof native.setSmartTriggerConfig !== 'function') return;
  // Never expose a native token getter to iframes. The trusted top-level shell
  // receives the existing per-navigation token through native injection only.
  const token = () => window.__digipalBridgeToken || '';
  let learning = null;
  const emit = (name, detail) => window.dispatchEvent(new CustomEvent(name, { detail }));
  const hex = value => Array.from(new TextEncoder().encode(value), b => b.toString(16).padStart(2, '0')).join('');
  const stopLearnMode = () => {
    learning = null;
    native.stopLearnMode(token());
  };
  // Native hardware uses the existing hw:* DOM channel ONLY. Do not also invoke
  // callback subscriptions: the shared player subscribes to both transports.
  window.smartTriggers = Object.freeze({
    version: 1,
    startLearnMode(payload) {
      learning = payload || {};
      native.startLearnMode(token(), JSON.stringify(learning));
    },
    stopLearnMode,
    startBleScan: () => native.startBleScan(token()),
    getConnectedDevices: () => JSON.parse(native.getConnectedDevices(token())),
    enable: () => native.enableSmartTriggers(token()),
    disable: () => native.disableSmartTriggers(token()),
    removeAllHwListeners() {},
  });
  window.addEventListener('hw:signalCaptured', stopLearnMode);
  window.addEventListener('keydown', event => {
    if (!learning || event.repeat) return;
    const filter = learning.deviceId || learning.deviceFilter;
    if (filter && filter !== 'web_keyboard') return;
    // Consume a learned key before the player's normal keyboard-trigger
    // listener can use it to fire an existing queue/action trigger.
    if (typeof event.preventDefault === 'function') event.preventDefault();
    if (typeof event.stopImmediatePropagation === 'function') event.stopImmediatePropagation();
    const detail = {
      deviceId: 'web_keyboard', deviceName: 'Keyboard', protocol: 'usb_hid',
      deviceType: 'keyboard', signalKey: hex(`key_${event.code}_${event.key}`),
    };
    stopLearnMode();
    emit('hw:signalCaptured', detail);
  });
  // The cloud player owns trigger evaluation, cooldowns, ML pressure throttling
  // and revert timers. This adapter only reflects its existing cached config to
  // the native resource/hardware lifecycle; it does not evaluate triggers twice.
  let lastConfig = null;
  const syncConfig = () => {
    try {
      if (!token()) return;
      const code = native.getSmartTriggerPairingCode(token());
      if (!code) return;
      const value = localStorage.getItem(`tv_trigger_config:${code}`) || '[]';
      if (value !== lastConfig) {
        native.setSmartTriggerConfig(token(), value);
        lastConfig = value;
      }
    } catch (_) { /* localStorage may be unavailable before the first document */ }
  };
  syncConfig();
  const timer = setInterval(syncConfig, 2000);
  window.addEventListener('pagehide', () => { clearInterval(timer); stopLearnMode(); }, { once: true });
})();
