'use strict';
const cloud = new URL(require('./dev-build-target.json').cloudUrl);
if (cloud.protocol !== 'https:' || cloud.username || cloud.password || ['digipalsignage.com', 'www.digipalsignage.com'].includes(cloud.hostname)) {
  throw new Error('Windows DEV requires a development origin, not production.');
}
module.exports = {
  extends: null,
  appId: 'com.digipal.player.networkdev',
  productName: 'Digipal Player Network DEV',
  extraMetadata: { name: 'digipal-player-network-dev', version: '2.5.4-dev.2', developmentCloudUrl: cloud.origin },
  directories: { output: 'release-network-dev' },
  win: { target: [{ target: 'nsis', arch: ['x64'] }], icon: 'icon.png' },
  nsis: { oneClick: false, allowToChangeInstallationDirectory: true, createDesktopShortcut: true, runAfterFinish: false },
};
