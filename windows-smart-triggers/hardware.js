const { EventEmitter } = require('events');
const crypto = require('crypto');

class HardwareManager extends EventEmitter {
  constructor() {
    super();
    this.connectedDevices = new Map();
    this.failedDevices = new Set();
    this.keyboardFallbackDevices = new Map();
    this.learnMode = false;
    this.learnDeviceFilter = null;
    this.hidPollingInterval = null;
    this.serialPollingInterval = null;
    this.HID = null;
    this.SerialPort = null;
    this.midi = null;
    this.midiInputs = new Map();
    this.midiPollingInterval = null;
    this._initLibraries();
  }

  _initLibraries() {
    try {
      this.HID = require('node-hid');
      console.log('[HW] node-hid loaded successfully');
    } catch (e) {
      console.warn('[HW] node-hid not available:', e.message);
    }

    try {
      this.SerialPort = require('serialport');
      console.log('[HW] serialport loaded successfully');
    } catch (e) {
      console.warn('[HW] serialport not available:', e.message);
    }

    try {
      this.midi = require('midi');
      console.log('[HW] midi loaded successfully');
    } catch (e) {
      console.warn('[HW] midi not available — install with: npm install midi');
    }
  }

  start() {
    this._startHIDPolling();
    this._startSerialPolling();
    this._startMIDIPolling();
    console.log('[HW] Hardware manager started');
  }

  stop() {
    if (this.hidPollingInterval) {
      clearInterval(this.hidPollingInterval);
      this.hidPollingInterval = null;
    }
    if (this.serialPollingInterval) {
      clearInterval(this.serialPollingInterval);
      this.serialPollingInterval = null;
    }
    if (this.midiPollingInterval) {
      clearInterval(this.midiPollingInterval);
      this.midiPollingInterval = null;
    }
    for (const [id, input] of this.midiInputs) {
      try { input.closePort(); } catch (e) {}
    }
    this.midiInputs.clear();
    for (const [id, device] of this.connectedDevices) {
      if (device._handle) {
        try { device._handle.close(); } catch (e) {}
      }
    }
    this.connectedDevices.clear();
    this.keyboardFallbackDevices.clear();
    console.log('[HW] Hardware manager stopped');
  }

  getConnectedDevices() {
    const devices = [];
    for (const [id, device] of this.connectedDevices) {
      devices.push({
        deviceId: id,
        deviceName: device.name,
        deviceType: device.type,
        protocol: device.protocol,
        vendorId: device.vendorId,
        productId: device.productId,
        keyboardDevice: device._keyboardDevice || false,
      });
    }
    for (const [id, device] of this.keyboardFallbackDevices) {
      devices.push({
        deviceId: id,
        deviceName: device.name,
        deviceType: device.type,
        protocol: device.protocol,
        vendorId: device.vendorId,
        productId: device.productId,
        keyboardDevice: true,
        keyboardFallback: true,
      });
    }
    return devices;
  }

  hasKeyboardFallbackDevices() {
    return this.keyboardFallbackDevices.size > 0;
  }

  hasKeyboardTypeDevices() {
    for (const [, dev] of this.connectedDevices) {
      if (dev._keyboardDevice) return true;
    }
    return false;
  }

  handleKeyboardInput(keyCode, keyName) {
    if (!this.learnMode && this.keyboardFallbackDevices.size === 0 && !this.hasKeyboardTypeDevices()) return;

    const raw = `key_${keyCode}_${keyName}`;
    const rawData = Buffer.from(raw, 'utf-8');

    if (this.learnMode && this.learnDeviceFilter) {
      const fallbackDev = this.keyboardFallbackDevices.get(this.learnDeviceFilter);
      if (fallbackDev) {
        this._handleSignal(this.learnDeviceFilter, fallbackDev, rawData);
        return;
      }
      const dev = this.connectedDevices.get(this.learnDeviceFilter);
      if (dev) {
        this._handleSignal(this.learnDeviceFilter, dev, rawData);
        return;
      }
    }

    if (this.keyboardFallbackDevices.size > 0) {
      const [id, dev] = this.keyboardFallbackDevices.entries().next().value;
      this._handleSignal(id, dev, rawData);
      return;
    }

    for (const [id, dev] of this.connectedDevices) {
      if (dev._keyboardDevice) {
        this._handleSignal(id, dev, rawData);
        return;
      }
    }

    for (const [id, dev] of this.connectedDevices) {
      if (dev.type === 'hid') {
        this._handleSignal(id, dev, rawData);
        return;
      }
    }

    const fallback = {
      name: 'Keyboard',
      type: 'keyboard',
      protocol: 'usb_hid',
      vendorId: 0,
      productId: 0,
    };
    this._handleSignal('keyboard', fallback, rawData);
  }

  startLearnMode(payload) {
    this.learnMode = true;
    this.learnDeviceFilter = payload?.deviceFilter || null;
    console.log('[HW] Learn mode activated' + (this.learnDeviceFilter ? ` (filter: ${this.learnDeviceFilter})` : ''));
    return { success: true };
  }

  stopLearnMode() {
    this.learnMode = false;
    this.learnDeviceFilter = null;
    console.log('[HW] Learn mode deactivated');
    return { success: true };
  }

  _generatePhysicalDeviceKey(device) {
    const vid = device.vendorId || 0;
    const pid = device.productId || 0;
    const serial = device.serialNumber || '';
    return `${vid}:${pid}:${serial}`;
  }

  _generateDeviceId(device) {
    const key = this._generatePhysicalDeviceKey(device);
    return crypto.createHash('md5').update(key).digest('hex').substring(0, 16);
  }

  _generateSerialDeviceId(device) {
    const key = `${device.vendorId || 0}:${device.productId || 0}:${device.path || device.serialNumber || ''}`;
    return crypto.createHash('md5').update(key).digest('hex').substring(0, 16);
  }

  _generateSignalKey(deviceId, rawData) {
    const dataStr = typeof rawData === 'string' ? rawData : Buffer.from(rawData).toString('hex');
    return crypto.createHash('sha256').update(`${deviceId}:${dataStr}`).digest('hex').substring(0, 32);
  }

  _rankHIDInterface(dev) {
    const page = dev.usagePage || 0;
    const usage = dev.usage || 0;
    if (page === 0xFF00 || page >= 0xFF00) return 0;
    if (page === 0x01 && usage !== 0x06 && usage !== 0x02) return 1;
    if (page === 0x0C) return 2;
    if (page === 0x01 && usage === 0x06) return 10;
    if (page === 0x01 && usage === 0x02) return 11;
    return 5;
  }

  _startHIDPolling() {
    if (!this.HID) return;

    this.hidPollingInterval = setInterval(() => {
      try {
        const allInterfaces = this.HID.devices();
        const currentPhysicalKeys = new Set();

        const grouped = new Map();
        for (const dev of allInterfaces) {
          if (!dev.path) continue;
          const physKey = this._generatePhysicalDeviceKey(dev);
          currentPhysicalKeys.add(physKey);
          if (!grouped.has(physKey)) {
            grouped.set(physKey, []);
          }
          grouped.get(physKey).push(dev);
        }

        for (const [physKey, interfaces] of grouped) {
          const deviceId = this._generateDeviceId(interfaces[0]);

          if (this.connectedDevices.has(deviceId) || this.failedDevices.has(deviceId) || this.keyboardFallbackDevices.has(deviceId)) continue;

          interfaces.sort((a, b) => this._rankHIDInterface(a) - this._rankHIDInterface(b));

          const bestDev = interfaces[0];
          const deviceName = bestDev.product || `HID Device ${bestDev.vendorId}:${bestDev.productId}`;

          const hasKeyboardInterface = interfaces.some(i =>
            (i.usagePage === 0x01 && i.usage === 0x06)
          );

          let opened = false;
          for (const iface of interfaces) {
            const deviceInfo = {
              name: deviceName,
              type: 'hid',
              protocol: 'usb_hid',
              vendorId: iface.vendorId,
              productId: iface.productId,
              path: iface.path,
              _physKey: physKey,
              _allPaths: interfaces.map(i => i.path),
              _handle: null,
              _keyboardDevice: hasKeyboardInterface,
            };

            try {
              const handle = new this.HID.HID(iface.path);
              deviceInfo._handle = handle;

              handle.on('data', (data) => {
                this._handleSignal(deviceId, deviceInfo, data);
              });

              handle.on('error', (err) => {
                console.warn(`[HW] HID error on ${deviceId}:`, err.message);
                this._removeDevice(deviceId);
              });

              this.connectedDevices.set(deviceId, deviceInfo);
              this.emit('deviceConnected', {
                deviceId,
                deviceName: deviceInfo.name,
                deviceType: deviceInfo.type,
                protocol: deviceInfo.protocol,
                vendorId: deviceInfo.vendorId,
                productId: deviceInfo.productId,
                keyboardDevice: hasKeyboardInterface,
              });
              console.log(`[HW] HID device connected: ${deviceInfo.name} (${deviceId}) via interface ${iface.path}${hasKeyboardInterface ? ' [keyboard-type]' : ''}`);
              opened = true;
              break;
            } catch (e) {
              console.warn(`[HW] Could not open HID interface ${iface.path}: ${e.message}`);
            }
          }

          if (!opened) {
            if (hasKeyboardInterface) {
              const fallbackInfo = {
                name: deviceName,
                type: 'hid',
                protocol: 'usb_hid',
                vendorId: bestDev.vendorId,
                productId: bestDev.productId,
                _physKey: physKey,
                _keyboardDevice: true,
              };
              this.keyboardFallbackDevices.set(deviceId, fallbackInfo);
              this.emit('deviceConnected', {
                deviceId,
                deviceName,
                deviceType: 'hid',
                protocol: 'usb_hid',
                vendorId: bestDev.vendorId,
                productId: bestDev.productId,
                keyboardDevice: true,
                status: 'keyboardFallback',
              });
              console.log(`[HW] Keyboard-type HID device registered for keyboard fallback: ${deviceName} (${deviceId})`);
            } else {
              this.failedDevices.add(deviceId);
              console.warn(`[HW] All interfaces failed for HID device ${deviceName} (${deviceId})`);
              this.emit('deviceConnected', {
                deviceId,
                deviceName,
                deviceType: 'hid',
                protocol: 'usb_hid',
                vendorId: bestDev.vendorId,
                productId: bestDev.productId,
                status: 'failed',
                failReason: 'Could not open any HID interface',
              });
            }
          }
        }

        for (const [id, device] of this.connectedDevices) {
          if (device.type === 'hid' && device._physKey && !currentPhysicalKeys.has(device._physKey)) {
            this._removeDevice(id);
          }
        }

        for (const [fallbackId, fallbackDev] of this.keyboardFallbackDevices) {
          if (fallbackDev._physKey && !currentPhysicalKeys.has(fallbackDev._physKey)) {
            this.keyboardFallbackDevices.delete(fallbackId);
            this.emit('deviceDisconnected', {
              deviceId: fallbackId,
              deviceName: fallbackDev.name,
              deviceType: fallbackDev.type,
              protocol: fallbackDev.protocol,
            });
            console.log(`[HW] Keyboard fallback device disconnected: ${fallbackDev.name} (${fallbackId})`);
          }
        }

        for (const failedId of this.failedDevices) {
          const stillPresent = [...currentPhysicalKeys].some(pk => {
            const sample = grouped.get(pk);
            return sample && this._generateDeviceId(sample[0]) === failedId;
          });
          if (!stillPresent) {
            this.failedDevices.delete(failedId);
          }
        }
      } catch (e) {
        // Polling error, will retry
      }
    }, 3000);
  }

  _startSerialPolling() {
    if (!this.SerialPort) return;

    this.serialPollingInterval = setInterval(async () => {
      try {
        const ports = await this.SerialPort.SerialPort.list();
        const currentPaths = new Set();

        for (const port of ports) {
          if (!port.path) continue;
          currentPaths.add(port.path);
          const deviceId = this._generateSerialDeviceId({
            vendorId: port.vendorId,
            productId: port.productId,
            path: port.path,
          });

          if (!this.connectedDevices.has(deviceId) && !this.failedDevices.has(deviceId)) {
            const deviceInfo = {
              name: port.manufacturer || `Serial Device ${port.path}`,
              type: 'serial',
              protocol: 'usb_serial',
              vendorId: port.vendorId,
              productId: port.productId,
              path: port.path,
              _handle: null,
            };

            try {
              const sp = new this.SerialPort.SerialPort({
                path: port.path,
                baudRate: 9600,
                autoOpen: false,
              });

              sp.open((err) => {
                if (err) {
                  this.failedDevices.add(deviceId);
                  this._removeDevice(deviceId);
                  return;
                }
                deviceInfo._handle = sp;

                sp.on('data', (data) => {
                  this._handleSignal(deviceId, deviceInfo, data);
                });

                sp.on('error', () => {
                  this.failedDevices.add(deviceId);
                  this._removeDevice(deviceId);
                });

                sp.on('close', () => {
                  this._removeDevice(deviceId);
                });
              });

              this.connectedDevices.set(deviceId, deviceInfo);
              this.emit('deviceConnected', {
                deviceId,
                deviceName: deviceInfo.name,
                deviceType: deviceInfo.type,
                protocol: deviceInfo.protocol,
                vendorId: deviceInfo.vendorId,
                productId: deviceInfo.productId,
              });
              console.log(`[HW] Serial device connected: ${deviceInfo.name} (${deviceId})`);
            } catch (e) {
              this.failedDevices.add(deviceId);
            }
          }
        }

        for (const [id, device] of this.connectedDevices) {
          if (device.type === 'serial' && device.path && !currentPaths.has(device.path)) {
            this._removeDevice(id);
          }
        }

        const currentSerialIds = new Set(ports.map(port => port.path ? this._generateSerialDeviceId({ vendorId: port.vendorId, productId: port.productId, path: port.path }) : null).filter(Boolean));
        for (const failedId of this.failedDevices) {
          if (!currentSerialIds.has(failedId)) {
            this.failedDevices.delete(failedId);
          }
        }
      } catch (e) {
        // Polling error
      }
    }, 3000);
  }

  _startMIDIPolling() {
    if (!this.midi) return;

    this.midiPollingInterval = setInterval(() => {
      try {
        const probe = new this.midi.Input();
        const portCount = probe.getPortCount();
        const currentPortNames = new Set();

        for (let i = 0; i < portCount; i++) {
          const portName = probe.getPortName(i);
          currentPortNames.add(portName);
          const deviceId = `midi_${crypto.createHash('md5').update(portName).digest('hex').substring(0, 16)}`;

          if (!this.connectedDevices.has(deviceId)) {
            try {
              const input = new this.midi.Input();
              input.openPort(i);

              input.on('message', (deltaTime, message) => {
                if (!message || message.length < 2) return;
                const [status, note, velocity] = message;
                const isNoteOn = (status & 0xf0) === 0x90 && (velocity || 0) > 0;
                const isCC = (status & 0xf0) === 0xb0;
                if (!isNoteOn && !isCC) return;

                const rawData = Buffer.from(message);
                const signalHex = rawData.toString('hex');

                const signal = {
                  deviceId,
                  deviceName: portName,
                  deviceType: 'midi',
                  protocol: 'midi',
                  signalKey: signalHex,
                  rawData: signalHex,
                  timestamp: Date.now(),
                };

                if (this.learnMode) {
                  if (this.learnDeviceFilter && this.learnDeviceFilter !== deviceId) return;
                  this.learnMode = false;
                  this.learnDeviceFilter = null;
                  this.emit('signalCaptured', signal);
                  console.log(`[HW] MIDI signal captured in learn mode: ${signalHex} from "${portName}"`);
                } else {
                  this.emit('signalEvent', signal);
                }
              });

              this.midiInputs.set(deviceId, input);

              const deviceInfo = {
                name: portName,
                type: 'midi',
                protocol: 'midi',
                vendorId: 0,
                productId: 0,
                path: portName,
                _handle: null,
              };
              this.connectedDevices.set(deviceId, deviceInfo);
              this.emit('deviceConnected', {
                deviceId,
                deviceName: portName,
                deviceType: 'midi',
                protocol: 'midi',
              });
              console.log(`[HW] MIDI device connected: "${portName}" (${deviceId})`);
            } catch (e) {
              console.warn(`[HW] Could not open MIDI port "${portName}":`, e.message);
            }
          }
        }

        probe.closePort();

        for (const [id, device] of this.connectedDevices) {
          if (device.type === 'midi' && !currentPortNames.has(device.name)) {
            const midiInput = this.midiInputs.get(id);
            if (midiInput) {
              try { midiInput.closePort(); } catch (e) {}
              this.midiInputs.delete(id);
            }
            this.connectedDevices.delete(id);
            this.emit('deviceDisconnected', {
              deviceId: id,
              deviceName: device.name,
              deviceType: 'midi',
              protocol: 'midi',
            });
            console.log(`[HW] MIDI device disconnected: "${device.name}" (${id})`);
          }
        }
      } catch (e) {
        // MIDI polling error, will retry
      }
    }, 3000);
  }

  startBleScan() {
    console.log('[HW] BLE scan requested (handled via Web Bluetooth in renderer)');
    this.emit('bleScanRequested');
    return { success: true };
  }

  registerBleDevice(deviceId, deviceName) {
    if (this.connectedDevices.has(deviceId)) return;
    const deviceInfo = {
      name: deviceName,
      type: 'ble',
      protocol: 'bluetooth_le',
      vendorId: 0,
      productId: 0,
      path: deviceId,
      _handle: null,
    };
    this.connectedDevices.set(deviceId, deviceInfo);
    this.emit('deviceConnected', {
      deviceId,
      deviceName: deviceInfo.name,
      deviceType: deviceInfo.type,
      protocol: deviceInfo.protocol,
    });
    console.log(`[HW] BLE device registered: ${deviceName} (${deviceId})`);
  }

  handleBleSignal(deviceId, rawDataHex) {
    const device = this.connectedDevices.get(deviceId);
    if (!device) return;
    const rawData = Buffer.from(rawDataHex, 'hex');
    this._handleSignal(deviceId, device, rawData);
  }

  _handleSignal(deviceId, deviceInfo, rawData) {
    const signalKey = this._generateSignalKey(deviceId, rawData);
    const signal = {
      deviceId,
      deviceName: deviceInfo.name,
      deviceType: deviceInfo.type,
      protocol: deviceInfo.protocol,
      signalKey,
      rawData: Buffer.from(rawData).toString('hex'),
      timestamp: Date.now(),
    };

    if (this.learnMode) {
      if (this.learnDeviceFilter && this.learnDeviceFilter !== deviceId) {
        return;
      }
      this.learnMode = false;
      this.learnDeviceFilter = null;
      this.emit('signalCaptured', signal);
      console.log(`[HW] Signal captured in learn mode: ${signalKey}`);
    } else {
      this.emit('signalEvent', signal);
    }
  }

  _removeDevice(deviceId) {
    const device = this.connectedDevices.get(deviceId);
    if (!device) return;

    if (device._handle) {
      try { device._handle.close(); } catch (e) {}
    }

    this.connectedDevices.delete(deviceId);
    this.emit('deviceDisconnected', {
      deviceId,
      deviceName: device.name,
      deviceType: device.type,
      protocol: device.protocol,
    });
    console.log(`[HW] Device disconnected: ${device.name} (${deviceId})`);
  }
}

module.exports = { HardwareManager };
