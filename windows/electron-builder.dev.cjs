'use strict';
module.exports = {
  extends: null,
  appId: 'com.digipal.player.networkdev',
  productName: 'Digipal Player Network DEV',
  extraMetadata: { name: 'digipal-player-network-dev', version: '2.5.4-dev.1' },
  directories: { output: 'release-network-dev' },
  win: { target: [{ target: 'nsis', arch: ['x64'] }], icon: 'icon.png' },
  nsis: { oneClick: false, allowToChangeInstallationDirectory: true, createDesktopShortcut: true, runAfterFinish: false },
};
