(function () {
  'use strict';

  var PLAYER_PATH = '/tv';
  var SERVER_URL = 'https://www.digipalsignage.com';
  var STORAGE_KEY = 'digipal_server_url';
  var RETRY_INTERVAL = 5000;
  var KEEP_ALIVE_INTERVAL = 4 * 60 * 1000;

  var setupScreen = document.getElementById('setup-screen');
  var playerFrame = document.getElementById('player-frame');
  var errorScreen = document.getElementById('error-screen');
  var urlInput = document.getElementById('url');
  var errorEl = document.getElementById('error');
  var connectBtn = document.getElementById('connect');
  var retryCountdown = document.getElementById('retry-countdown');

  var serverUrl = '';
  var retryTimer = null;
  var keepAliveTimer = null;
  var isPlaying = false;

  function loadConfig() {
    try {
      var saved = localStorage.getItem(STORAGE_KEY);
      if (saved) {
        serverUrl = saved;
        return true;
      }
    } catch (e) {
      console.error('[Digipal] Failed to load config:', e.message);
    }
    return false;
  }

  function saveConfig(url) {
    try {
      localStorage.setItem(STORAGE_KEY, url);
      serverUrl = url;
    } catch (e) {
      console.error('[Digipal] Failed to save config:', e.message);
    }
  }

  function showSetup() {
    setupScreen.style.display = 'flex';
    playerFrame.style.display = 'none';
    errorScreen.style.display = 'none';
    isPlaying = false;
    stopKeepAlive();
    urlInput.value = serverUrl || '';
    setTimeout(function () { urlInput.focus(); }, 100);
  }

  var loadTimeoutTimer = null;
  var iframeLoaded = false;

  function showPlayer() {
    setupScreen.style.display = 'none';
    errorScreen.style.display = 'none';
    playerFrame.style.display = 'block';
    isPlaying = true;
    iframeLoaded = false;

    var url = serverUrl.replace(/\/+$/, '') + PLAYER_PATH;
    playerFrame.src = url;

    if (loadTimeoutTimer) clearTimeout(loadTimeoutTimer);
    loadTimeoutTimer = setTimeout(function () {
      if (!iframeLoaded && isPlaying) {
        console.warn('[Digipal] Iframe load timed out after 15s');
        showError();
      }
    }, 15000);

    injectBridge();
    startKeepAlive();
  }

  function showError() {
    setupScreen.style.display = 'none';
    playerFrame.style.display = 'none';
    errorScreen.style.display = 'flex';
    isPlaying = false;
    scheduleRetry();
  }

  function scheduleRetry() {
    clearRetry();
    var remaining = RETRY_INTERVAL / 1000;
    retryCountdown.textContent = 'Retrying in ' + remaining + 's...';
    var countdownTimer = setInterval(function () {
      remaining--;
      if (remaining > 0) {
        retryCountdown.textContent = 'Retrying in ' + remaining + 's...';
      } else {
        clearInterval(countdownTimer);
      }
    }, 1000);
    retryTimer = setTimeout(function () {
      clearInterval(countdownTimer);
      showPlayer();
    }, RETRY_INTERVAL);
  }

  function clearRetry() {
    if (retryTimer) {
      clearTimeout(retryTimer);
      retryTimer = null;
    }
  }

  function injectBridge() {
    try {
      playerFrame.onload = function () {
        try {
          var win = playerFrame.contentWindow;
          win.LgWebOS = {
            platform: 'webos',
            version: '1.0.0',
            keepScreenOn: keepScreenOn,
            allowScreenOff: allowScreenOff,
            getDeviceInfo: getDeviceInfo
          };
        } catch (e) {
          console.warn('[Digipal] Cannot inject bridge (cross-origin):', e.message);
        }
      };
    } catch (e) {
      console.warn('[Digipal] Bridge injection error:', e.message);
    }
  }

  playerFrame.addEventListener('load', function () {
    iframeLoaded = true;
    clearRetry();
    if (loadTimeoutTimer) { clearTimeout(loadTimeoutTimer); loadTimeoutTimer = null; }
    errorScreen.style.display = 'none';
    playerFrame.style.display = 'block';
  });

  playerFrame.addEventListener('error', function () {
    showError();
  });

  function keepScreenOn() {
    if (typeof webOS === 'undefined' || !webOS.service) return;

    try {
      webOS.service.request('luna://com.webos.service.tvpower', {
        method: 'turnOnScreen',
        parameters: {},
        onSuccess: function () {
          console.log('[Digipal] Screen turned on');
        },
        onFailure: function () {}
      });
    } catch (e) {}

    try {
      webOS.service.request('luna://com.webos.service.config', {
        method: 'setConfigs',
        parameters: { configs: { 'com.webos.service.tvpower': { 'screenSaverOn': false } } },
        onSuccess: function () {
          console.log('[Digipal] Screen saver disabled via config');
        },
        onFailure: function (err) {
          console.warn('[Digipal] Could not disable screen saver:', JSON.stringify(err));
        }
      });
    } catch (e) {
      console.warn('[Digipal] Screen saver API error:', e.message);
    }
  }

  function allowScreenOff() {
    if (typeof webOS === 'undefined' || !webOS.service) return;

    try {
      webOS.service.request('luna://com.webos.service.config', {
        method: 'setConfigs',
        parameters: { configs: { 'com.webos.service.tvpower': { 'screenSaverOn': true } } },
        onSuccess: function () {
          console.log('[Digipal] Screen saver re-enabled');
        },
        onFailure: function () {}
      });
    } catch (e) {}
  }

  function startKeepAlive() {
    stopKeepAlive();
    keepScreenOn();
    keepAliveTimer = setInterval(function () {
      keepScreenOn();
    }, KEEP_ALIVE_INTERVAL);
  }

  function stopKeepAlive() {
    if (keepAliveTimer) {
      clearInterval(keepAliveTimer);
      keepAliveTimer = null;
    }
  }

  function getDeviceInfo() {
    var info = { platform: 'webos', model: 'unknown', sdkVersion: 'unknown' };
    if (typeof webOS !== 'undefined' && webOS.systemInfo) {
      try {
        info.model = webOS.systemInfo.modelName || 'unknown';
        info.sdkVersion = webOS.systemInfo.sdkVersion || 'unknown';
      } catch (e) {}
    }
    if (typeof webOS !== 'undefined' && webOS.deviceInfo) {
      try {
        webOS.deviceInfo(function (deviceInfo) {
          info.model = deviceInfo.modelName || info.model;
          info.sdkVersion = deviceInfo.sdkVersion || info.sdkVersion;
        });
      } catch (e) {}
    }
    return info;
  }

  function handleConnect() {
    var url = urlInput.value.trim().replace(/\/+$/, '');
    if (!url || (!url.startsWith('https://') && !url.startsWith('http://'))) {
      errorEl.style.display = 'block';
      urlInput.focus();
      return;
    }
    errorEl.style.display = 'none';
    saveConfig(url);
    showPlayer();
  }

  connectBtn.addEventListener('click', handleConnect);

  urlInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') {
      handleConnect();
    } else {
      errorEl.style.display = 'none';
    }
  });

  document.addEventListener('keydown', function (e) {
    var key = e.keyCode || e.which;

    if (key === 461 || key === 10009) {
      e.preventDefault();
      e.stopPropagation();

      if (!isPlaying && setupScreen.style.display !== 'none') {
        return;
      }

      if (errorScreen.style.display !== 'none') {
        clearRetry();
        showSetup();
        return;
      }

      return;
    }

    if (key === 413 || key === 415) {
      e.preventDefault();
      return;
    }
  });

  document.addEventListener('visibilitychange', function () {
    if (document.hidden) {
      stopKeepAlive();
    } else {
      if (isPlaying) {
        startKeepAlive();
        try {
          playerFrame.contentWindow.location.reload();
        } catch (e) {
          playerFrame.src = playerFrame.src;
        }
      }
    }
  });

  if (typeof webOS !== 'undefined' && webOS.platform) {
    document.addEventListener('webOSRelaunch', function (evt) {
      console.log('[Digipal] App relaunched');
      if (isPlaying) {
        startKeepAlive();
        try {
          playerFrame.contentWindow.location.reload();
        } catch (e) {
          playerFrame.src = playerFrame.src;
        }
      }
    });
  }

  window.addEventListener('online', function () {
    if (!isPlaying && serverUrl) {
      showPlayer();
    }
  });

  window.addEventListener('offline', function () {
    if (isPlaying) {
      console.warn('[Digipal] Device went offline');
      showError();
    }
  });

  serverUrl = SERVER_URL;
  showPlayer();
})();
