const { EventEmitter } = require('events');
const crypto = require('crypto');

const DEFAULT_CLOUD_URL = 'https://app.digipal.app';
const DISCOVERY_TIMEOUT_MS = 4000;
const RECONNECT_BASE_MS = 5000;
const RECONNECT_MAX_MS = 60000;
const HEALTH_CHECK_INTERVAL_MS = 30000;
const REACHABILITY_TIMEOUT_MS = 5000;
const NEITHER_REACHABLE_RETRY_BASE_MS = 5000;
const NEITHER_REACHABLE_RETRY_MAX_MS = 60000;

class ConnectionManager extends EventEmitter {
  constructor(options = {}) {
    super();
    this.bonjourBrowser = options.bonjourBrowser || null;
    this.defaultCloudUrl = options.cloudUrl || DEFAULT_CLOUD_URL;
    this.platform = options.platform || 'electron';
    this.getDeviceInfo = options.getDeviceInfo || (() => ({}));
    this.captureScreenshot = options.captureScreenshot || (() => Promise.resolve(null));

    this.primaryUrl = null;
    this.primaryMode = null;
    this.cloudWs = null;
    this.cloudReconnectTimer = null;
    this.cloudReconnectAttempts = 0;
    this.localHealthTimer = null;
    this.localHealthAttempts = 0;
    this.localRediscoveryTimer = null;
    this.localRediscoveryAttempts = 0;
    this.neitherReachableTimer = null;
    this.neitherReachableAttempts = 0;
    this.isRunning = false;
    this.deviceId = options.deviceId || crypto.randomUUID();
  }

  async checkReachable(url) {
    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), REACHABILITY_TIMEOUT_MS);
      const res = await fetch(`${url}/api/health`, { signal: controller.signal });
      clearTimeout(timeoutId);
      return res.ok;
    } catch {
      return false;
    }
  }

  async autoConnect(config) {
    if (config && config.manualOverride && config.serverUrl) {
      this.primaryUrl = config.serverUrl;
      this.primaryMode = this.isLocalUrl(config.serverUrl) ? 'local' : 'cloud';
      this.emit('connected', { url: this.primaryUrl, mode: this.primaryMode });
      this.startDualMode();
      return { url: this.primaryUrl, mode: this.primaryMode };
    }

    this.emit('searching');

    if (this.bonjourBrowser) {
      const localServer = await this.discoverLocal(DISCOVERY_TIMEOUT_MS);
      if (localServer) {
        this.primaryUrl = localServer.url;
        this.primaryMode = 'local';
        this.emit('connected', { url: this.primaryUrl, mode: 'local' });
        this.startDualMode();
        return { url: this.primaryUrl, mode: 'local' };
      }
    }

    const cloudReachable = await this.checkReachable(this.defaultCloudUrl);
    if (cloudReachable) {
      this.primaryUrl = this.defaultCloudUrl;
      this.primaryMode = 'cloud';
      this.emit('connected', { url: this.primaryUrl, mode: 'cloud' });
      this.startDualMode();
      return { url: this.primaryUrl, mode: 'cloud' };
    }

    this.startNeitherReachableRetry(config);
    return null;
  }

  startNeitherReachableRetry(config) {
    this.neitherReachableAttempts++;
    const delay = Math.min(
      NEITHER_REACHABLE_RETRY_BASE_MS * Math.pow(2, this.neitherReachableAttempts - 1),
      NEITHER_REACHABLE_RETRY_MAX_MS
    );

    console.log(`[connection-manager] Neither local nor cloud reachable, retrying in ${Math.round(delay / 1000)}s (attempt ${this.neitherReachableAttempts})`);
    this.emit('retrying', { attempt: this.neitherReachableAttempts, delayMs: delay });

    this.neitherReachableTimer = setTimeout(async () => {
      if (this.bonjourBrowser) {
        const servers = this.bonjourBrowser.getServers();
        if (servers.length > 0) {
          this.neitherReachableAttempts = 0;
          this.primaryUrl = servers[0].url;
          this.primaryMode = 'local';
          this.emit('connected', { url: this.primaryUrl, mode: 'local' });
          this.startDualMode();
          return;
        }
      }

      const cloudReachable = await this.checkReachable(this.defaultCloudUrl);
      if (cloudReachable) {
        this.neitherReachableAttempts = 0;
        this.primaryUrl = this.defaultCloudUrl;
        this.primaryMode = 'cloud';
        this.emit('connected', { url: this.primaryUrl, mode: 'cloud' });
        this.startDualMode();
        return;
      }

      this.startNeitherReachableRetry(config);
    }, delay);
  }

  discoverLocal(timeoutMs) {
    return new Promise((resolve) => {
      if (!this.bonjourBrowser) {
        resolve(null);
        return;
      }

      const servers = this.bonjourBrowser.getServers();
      if (servers.length > 0) {
        resolve(servers[0]);
        return;
      }

      const checkInterval = setInterval(() => {
        const found = this.bonjourBrowser.getServers();
        if (found.length > 0) {
          clearInterval(checkInterval);
          clearTimeout(timeout);
          resolve(found[0]);
        }
      }, 500);

      const timeout = setTimeout(() => {
        clearInterval(checkInterval);
        resolve(null);
      }, timeoutMs);
    });
  }

  isLocalUrl(url) {
    try {
      const u = new URL(url);
      const host = u.hostname;
      return host === 'localhost' ||
        host === '127.0.0.1' ||
        host.startsWith('192.168.') ||
        host.startsWith('10.') ||
        host.startsWith('172.') ||
        host.endsWith('.local');
    } catch {
      return false;
    }
  }

  startDualMode() {
    this.isRunning = true;

    if (this.primaryMode === 'local') {
      this.connectCloudChannel();
      this.startLocalHealthCheck();
    } else {
      this.connectCloudChannel();
      this.startLocalRediscovery();
    }
  }

  connectCloudChannel() {
    if (this.cloudWs) {
      try { this.cloudWs.close(); } catch {}
    }

    try {
      const WebSocket = require('ws');
      const wsUrl = this.defaultCloudUrl.replace(/^http/, 'ws').replace(/\/$/, '') + '/ws/player';
      this.cloudWs = new WebSocket(wsUrl);

      this.cloudWs.on('open', () => {
        console.log('[connection-manager] Cloud channel connected');
        this.cloudReconnectAttempts = 0;
        this.sendCloud({
          type: 'playerIdentify',
          payload: {
            deviceId: this.deviceId,
            platform: this.platform,
            primaryMode: this.primaryMode,
            primaryUrl: this.primaryUrl,
          }
        });
        this.emit('cloudChannelConnected');
      });

      this.cloudWs.on('message', (raw) => {
        try {
          const data = JSON.parse(raw.toString());
          this.handleCloudMessage(data);
        } catch (e) {
          console.error('[connection-manager] Cloud message parse error:', e);
        }
      });

      this.cloudWs.on('close', () => {
        console.log('[connection-manager] Cloud channel disconnected');
        this.emit('cloudChannelDisconnected');
        this.scheduleCloudReconnect();
      });

      this.cloudWs.on('error', (err) => {
        console.error('[connection-manager] Cloud channel error:', err.message);
      });
    } catch (e) {
      console.error('[connection-manager] Failed to connect cloud channel:', e.message);
      this.scheduleCloudReconnect();
    }
  }

  sendCloud(message) {
    if (this.cloudWs && this.cloudWs.readyState === 1) {
      this.cloudWs.send(JSON.stringify(message));
    }
  }

  scheduleCloudReconnect() {
    if (!this.isRunning) return;
    if (this.cloudReconnectTimer) clearTimeout(this.cloudReconnectTimer);

    this.cloudReconnectAttempts++;
    const delay = Math.min(
      RECONNECT_BASE_MS * Math.pow(2, this.cloudReconnectAttempts - 1),
      RECONNECT_MAX_MS
    );

    console.log(`[connection-manager] Cloud reconnect in ${Math.round(delay / 1000)}s (attempt ${this.cloudReconnectAttempts})`);
    this.cloudReconnectTimer = setTimeout(() => this.connectCloudChannel(), delay);
  }

  handleCloudMessage(data) {
    switch (data.type) {
      case 'switchServer': {
        const newUrl = data.payload?.url;
        if (newUrl) {
          console.log(`[connection-manager] Cloud command: switch server to ${newUrl}`);
          this.switchPrimary(newUrl);
        }
        break;
      }
      case 'forceReload':
        console.log('[connection-manager] Cloud command: force reload');
        this.emit('forceReload');
        break;
      case 'remoteRestart':
        console.log('[connection-manager] Cloud command: remote restart');
        this.emit('remoteRestart');
        break;
      case 'pushContent':
        console.log('[connection-manager] Cloud command: push content');
        this.emit('forceReload');
        break;
      case 'requestScreenshot': {
        console.log('[connection-manager] Cloud request: screenshot');
        const requestId = data.requestId || data.payload?.requestId;
        this.captureScreenshot().then((imageData) => {
          this.sendCloud({
            type: 'screenshotResponse',
            requestId,
            payload: { imageData }
          });
        }).catch((err) => {
          console.error('[connection-manager] Screenshot capture failed:', err);
          this.sendCloud({
            type: 'screenshotResponse',
            requestId,
            payload: { error: err.message }
          });
        });
        break;
      }
      case 'requestDeviceStatus': {
        console.log('[connection-manager] Cloud request: device status');
        const reqId = data.requestId || data.payload?.requestId;
        const info = this.getDeviceInfo();
        this.sendCloud({
          type: 'deviceStatusResponse',
          requestId: reqId,
          payload: {
            ...info,
            primaryMode: this.primaryMode,
            primaryUrl: this.primaryUrl,
            cloudConnected: !!(this.cloudWs && this.cloudWs.readyState === 1),
          }
        });
        break;
      }
      default:
        console.log(`[connection-manager] Unknown cloud message type: ${data.type}`);
    }
  }

  switchPrimary(newUrl) {
    const wasLocal = this.primaryMode === 'local';
    this.primaryUrl = newUrl;
    this.primaryMode = this.isLocalUrl(newUrl) ? 'local' : 'cloud';

    this.emit('switchServer', { url: newUrl, mode: this.primaryMode });

    this.sendCloud({
      type: 'playerStateUpdate',
      payload: {
        deviceId: this.deviceId,
        platform: this.platform,
        primaryMode: this.primaryMode,
        primaryUrl: this.primaryUrl,
      }
    });

    if (this.primaryMode === 'local' && !wasLocal) {
      this.stopLocalRediscovery();
      this.startLocalHealthCheck();
    } else if (this.primaryMode === 'cloud' && wasLocal) {
      this.stopLocalHealthCheck();
      this.startLocalRediscovery();
    }
  }

  startLocalHealthCheck() {
    if (this.localHealthTimer) clearInterval(this.localHealthTimer);
    this.localHealthAttempts = 0;
    this.localHealthTimer = setInterval(async () => {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 5000);
        const res = await fetch(`${this.primaryUrl}/api/health`, {
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        this.localHealthAttempts = 0;
      } catch {
        this.localHealthAttempts++;
        if (this.localHealthAttempts >= 2) {
          console.log('[connection-manager] Local server unreachable, falling back to cloud');
          this.fallbackToCloud();
        } else {
          console.log(`[connection-manager] Local server health check failed (attempt ${this.localHealthAttempts}), will retry`);
        }
      }
    }, HEALTH_CHECK_INTERVAL_MS);
  }

  stopLocalHealthCheck() {
    if (this.localHealthTimer) {
      clearInterval(this.localHealthTimer);
      this.localHealthTimer = null;
    }
    this.localHealthAttempts = 0;
  }

  fallbackToCloud() {
    this.stopLocalHealthCheck();
    this.primaryUrl = this.defaultCloudUrl;
    this.primaryMode = 'cloud';

    this.emit('switchServer', { url: this.primaryUrl, mode: 'cloud', reason: 'fallback' });

    this.startLocalRediscovery();
  }

  startLocalRediscovery() {
    if (!this.bonjourBrowser) return;
    if (this.localRediscoveryTimer) clearInterval(this.localRediscoveryTimer);
    this.localRediscoveryAttempts = 0;

    const checkForLocal = () => {
      const servers = this.bonjourBrowser.getServers();
      if (servers.length > 0 && this.primaryMode === 'cloud') {
        console.log(`[connection-manager] Local server rediscovered: ${servers[0].url}`);
        this.switchPrimary(servers[0].url);
        return true;
      }
      return false;
    };

    const scheduleNext = () => {
      this.localRediscoveryAttempts++;
      const delay = Math.min(
        RECONNECT_BASE_MS * Math.pow(2, Math.min(this.localRediscoveryAttempts - 1, 4)),
        RECONNECT_MAX_MS
      );
      this.localRediscoveryTimer = setTimeout(() => {
        if (!checkForLocal()) {
          scheduleNext();
        }
      }, delay);
    };

    if (!checkForLocal()) {
      scheduleNext();
    }
  }

  stopLocalRediscovery() {
    if (this.localRediscoveryTimer) {
      clearTimeout(this.localRediscoveryTimer);
      this.localRediscoveryTimer = null;
    }
    this.localRediscoveryAttempts = 0;
  }

  disconnectCloudChannel() {
    if (this.cloudReconnectTimer) {
      clearTimeout(this.cloudReconnectTimer);
      this.cloudReconnectTimer = null;
    }
    if (this.cloudWs) {
      try { this.cloudWs.close(); } catch {}
      this.cloudWs = null;
    }
  }

  stop() {
    this.isRunning = false;
    this.stopLocalHealthCheck();
    this.stopLocalRediscovery();
    this.disconnectCloudChannel();
    if (this.neitherReachableTimer) {
      clearTimeout(this.neitherReachableTimer);
      this.neitherReachableTimer = null;
    }
  }

  getState() {
    return {
      primaryUrl: this.primaryUrl,
      primaryMode: this.primaryMode,
      cloudConnected: !!(this.cloudWs && this.cloudWs.readyState === 1),
      isRunning: this.isRunning,
      deviceId: this.deviceId,
    };
  }
}

module.exports = ConnectionManager;
