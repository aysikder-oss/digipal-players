const path = require('path');
const fs = require('fs');
const https = require('https');
const http = require('http');

const MANIFEST_FILE = 'media-manifest.json';
const MEDIA_DIR = 'media';
const MAX_CONCURRENT = 3;
const LOW_STORAGE_THRESHOLD = 100 * 1024 * 1024;

class MediaManager {
  constructor(userDataPath) {
    this.userDataPath = userDataPath;
    this.mediaDir = path.join(userDataPath, MEDIA_DIR);
    this.manifestPath = path.join(userDataPath, MANIFEST_FILE);
    this.manifest = {};
    this.activeDownloads = new Set();
    this.webContents = null;

    if (!fs.existsSync(this.mediaDir)) {
      fs.mkdirSync(this.mediaDir, { recursive: true });
    }

    this.loadManifest();
  }

  setWebContents(wc) {
    this.webContents = wc;
  }

  loadManifest() {
    try {
      if (fs.existsSync(this.manifestPath)) {
        this.manifest = JSON.parse(fs.readFileSync(this.manifestPath, 'utf-8'));
      }
    } catch (e) {
      console.error('[MediaManager] Failed to load manifest:', e.message);
      this.manifest = {};
    }
  }

  saveManifest() {
    try {
      fs.writeFileSync(this.manifestPath, JSON.stringify(this.manifest, null, 2), 'utf-8');
    } catch (e) {
      console.error('[MediaManager] Failed to save manifest:', e.message);
    }
  }

  objectPathToFilename(objectPath) {
    return objectPath.replace(/^\/objects\//, '').replace(/[\/\\:*?"<>|]/g, '_');
  }

  getLocalMediaPath(objectPath) {
    const entry = this.manifest[objectPath];
    if (!entry) return '';
    const filePath = entry.localPath;
    if (fs.existsSync(filePath)) {
      entry.lastAccessed = Date.now();
      this.saveManifest();
      return 'local-media://' + encodeURIComponent(filePath);
    }
    delete this.manifest[objectPath];
    this.saveManifest();
    return '';
  }

  downloadMedia(objectPath, signedUrl) {
    if (this.activeDownloads.has(objectPath)) return;
    if (this.manifest[objectPath]) {
      const entry = this.manifest[objectPath];
      if (fs.existsSync(entry.localPath)) return;
    }

    if (this.activeDownloads.size >= MAX_CONCURRENT) return;

    if (!this.hasEnoughStorage()) {
      this.evictOldest(1);
      if (!this.hasEnoughStorage()) {
        console.warn('[MediaManager] Low storage, skipping download:', objectPath);
        return;
      }
    }

    this.activeDownloads.add(objectPath);
    const filename = this.objectPathToFilename(objectPath);
    const filePath = path.join(this.mediaDir, filename);
    const tempPath = filePath + '.tmp';

    const client = signedUrl.startsWith('https') ? https : http;

    const request = client.get(signedUrl, (response) => {
      if (response.statusCode === 301 || response.statusCode === 302) {
        this.activeDownloads.delete(objectPath);
        if (response.headers.location) {
          this.downloadMedia(objectPath, response.headers.location);
        }
        return;
      }

      if (response.statusCode !== 200) {
        this.activeDownloads.delete(objectPath);
        this.notifyFailed(objectPath, `HTTP ${response.statusCode}`);
        return;
      }

      const writeStream = fs.createWriteStream(tempPath);
      response.pipe(writeStream);

      writeStream.on('finish', () => {
        writeStream.close(() => {
          try {
            fs.renameSync(tempPath, filePath);
            const stats = fs.statSync(filePath);
            this.manifest[objectPath] = {
              localPath: filePath,
              size: stats.size,
              downloadedAt: Date.now(),
              lastAccessed: Date.now(),
            };
            this.saveManifest();
            this.activeDownloads.delete(objectPath);
            this.notifyDownloaded(objectPath, filePath);
          } catch (e) {
            this.activeDownloads.delete(objectPath);
            this.notifyFailed(objectPath, e.message);
          }
        });
      });

      writeStream.on('error', (e) => {
        this.activeDownloads.delete(objectPath);
        try { fs.unlinkSync(tempPath); } catch (_) {}
        this.notifyFailed(objectPath, e.message);
      });
    });

    request.on('error', (e) => {
      this.activeDownloads.delete(objectPath);
      this.notifyFailed(objectPath, e.message);
    });

    request.setTimeout(120000, () => {
      request.destroy();
      this.activeDownloads.delete(objectPath);
      try { fs.unlinkSync(tempPath); } catch (_) {}
      this.notifyFailed(objectPath, 'Download timeout');
    });
  }

  deleteMedia(objectPath) {
    const entry = this.manifest[objectPath];
    if (!entry) return false;
    try {
      if (fs.existsSync(entry.localPath)) {
        fs.unlinkSync(entry.localPath);
      }
    } catch (e) {
      console.error('[MediaManager] Delete failed:', e.message);
    }
    delete this.manifest[objectPath];
    this.saveManifest();
    return true;
  }

  deleteAllMedia() {
    const count = Object.keys(this.manifest).length;
    for (const [key, entry] of Object.entries(this.manifest)) {
      try {
        if (fs.existsSync(entry.localPath)) {
          fs.unlinkSync(entry.localPath);
        }
      } catch (e) {
        console.error('[MediaManager] Delete failed for', key, e.message);
      }
    }
    this.manifest = {};
    this.saveManifest();
    return count;
  }

  getStorageInfo() {
    let usedBytes = 0;
    let totalFiles = 0;
    for (const entry of Object.values(this.manifest)) {
      usedBytes += entry.size || 0;
      totalFiles++;
    }

    let freeBytes = 0;
    let totalSpace = 0;
    try {
      if (fs.statfsSync) {
        const stats = fs.statfsSync(this.mediaDir);
        freeBytes = stats.bavail * stats.bsize;
        totalSpace = stats.blocks * stats.bsize;
      }
    } catch (_) {}

    return JSON.stringify({ usedBytes, freeBytes, totalSpace, totalFiles });
  }

  hasEnoughStorage() {
    try {
      if (fs.statfsSync) {
        const stats = fs.statfsSync(this.mediaDir);
        const freeBytes = stats.bavail * stats.bsize;
        return freeBytes > LOW_STORAGE_THRESHOLD;
      }
      return true;
    } catch (_) {
      return true;
    }
  }

  evictOldest(count) {
    const entries = Object.entries(this.manifest)
      .sort((a, b) => (a[1].lastAccessed || 0) - (b[1].lastAccessed || 0));
    const toEvict = entries.slice(0, count);
    for (const [key, entry] of toEvict) {
      try {
        if (fs.existsSync(entry.localPath)) {
          fs.unlinkSync(entry.localPath);
        }
      } catch (_) {}
      delete this.manifest[key];
    }
    this.saveManifest();
  }

  cleanupOrphans() {
    try {
      const files = fs.readdirSync(this.mediaDir);
      const knownPaths = new Set(Object.values(this.manifest).map(e => e.localPath));
      for (const file of files) {
        const fullPath = path.join(this.mediaDir, file);
        if (!knownPaths.has(fullPath) && !file.endsWith('.tmp')) {
          try { fs.unlinkSync(fullPath); } catch (_) {}
        }
      }

      for (const [key, entry] of Object.entries(this.manifest)) {
        if (!fs.existsSync(entry.localPath)) {
          delete this.manifest[key];
        }
      }
      this.saveManifest();
    } catch (e) {
      console.error('[MediaManager] cleanupOrphans failed:', e.message);
    }
  }

  notifyDownloaded(objectPath, localPath) {
    if (this.webContents && !this.webContents.isDestroyed()) {
      const protocolUrl = 'local-media://' + encodeURIComponent(localPath);
      this.webContents.executeJavaScript(
        `if(window.__onMediaDownloaded) window.__onMediaDownloaded(${JSON.stringify(objectPath)}, ${JSON.stringify(protocolUrl)})`
      ).catch(() => {});
    }
  }

  notifyFailed(objectPath, error) {
    if (this.webContents && !this.webContents.isDestroyed()) {
      this.webContents.executeJavaScript(
        `if(window.__onMediaDownloadFailed) window.__onMediaDownloadFailed(${JSON.stringify(objectPath)}, ${JSON.stringify(error)})`
      ).catch(() => {});
    }
  }
}

module.exports = MediaManager;
