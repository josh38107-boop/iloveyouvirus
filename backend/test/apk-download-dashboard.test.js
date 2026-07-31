const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../..');
const server = fs.readFileSync(path.join(root, 'backend', 'server.js'), 'utf8');
const devicesHtml = fs.readFileSync(path.join(root, 'dashboard', 'devices.html'), 'utf8');
const devicesScript = fs.readFileSync(path.join(root, 'dashboard', 'js', 'devices.js'), 'utf8');
const api = fs.readFileSync(path.join(root, 'dashboard', 'js', 'api.js'), 'utf8');
const envExample = fs.readFileSync(path.join(root, 'backend', '.env.example'), 'utf8');

test('APK download backend route is admin protected and sends an APK attachment', () => {
  assert.match(server, /app\.get\('\/admin\/apk\/latest', adminAuthenticate/);
  assert.match(server, /process\.env\.APK_DOWNLOAD_URL/);
  assert.match(server, /Latest APK is not configured/);
  assert.match(server, /new URL\(apkUrl\)/);
  assert.match(server, /Latest APK could not be downloaded\. Check APK_DOWNLOAD_URL in Render\./);
  assert.match(server, /Content-Type', 'application\/vnd\.android\.package-archive'/);
  assert.match(server, /Content-Disposition', 'attachment; filename="coffeepos-latest\.apk"'/);
  assert.match(server, /res\.send\(apk\)/);
});

test('APK metadata endpoint exposes optional Render env configuration', () => {
  assert.match(server, /app\.get\('\/admin\/apk\/latest\/info', adminAuthenticate/);
  assert.match(server, /APK_VERSION_NAME/);
  assert.match(server, /APK_VERSION_CODE/);
  assert.match(envExample, /APK_DOWNLOAD_URL=/);
  assert.match(envExample, /APK_VERSION_NAME=/);
  assert.match(envExample, /APK_VERSION_CODE=/);
});

test('Cloud Devices dashboard exposes latest APK download controls', () => {
  assert.doesNotThrow(() => new vm.Script(devicesScript));
  assert.match(devicesHtml, /id="downloadLatestApk"/);
  assert.match(devicesHtml, /Download latest APK/);
  assert.match(devicesHtml, /id="openEnrollment"/);
  assert.match(api, /getLatestApkInfo:\s*\(\) => apiFetch\('\/admin\/apk\/latest\/info'\)/);
  assert.match(api, /latestApkUrl:\s*\(\) => `\$\{API_BASE\}\/admin\/apk\/latest`/);
  assert.match(devicesScript, /downloadLatestApk/);
  assert.match(devicesScript, /Latest APK is not configured yet/);
  assert.match(devicesScript, /api\.latestApkUrl\(\)/);
  assert.match(devicesScript, /openEnrollment/);
});
