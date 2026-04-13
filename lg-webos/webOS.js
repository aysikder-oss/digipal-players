if (typeof window.webOS === 'undefined') {
  window.webOS = {
    platform: { tv: false },
    systemInfo: { modelName: 'Browser', sdkVersion: 'dev' },
    deviceInfo: function (callback) {
      if (callback) {
        callback({ modelName: 'Browser', sdkVersion: 'dev' });
      }
    },
    service: {
      request: function (uri, params) {
        console.log('[webOS stub] service.request:', uri, params && params.method);
        if (params && params.onSuccess) {
          params.onSuccess({});
        }
        return { cancel: function () {} };
      }
    },
    fetchAppInfo: function (callback) {
      if (callback) {
        callback({
          id: 'com.digipal.player.webos',
          version: '1.0.0',
          title: 'Digipal Player'
        });
      }
    }
  };
}
