const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../..');
const html = fs.readFileSync(path.join(root, 'dashboard', 'data-maintenance.html'), 'utf8');
const script = fs.readFileSync(path.join(root, 'dashboard', 'js', 'data-maintenance.js'), 'utf8');
const api = fs.readFileSync(path.join(root, 'dashboard', 'js', 'api.js'), 'utf8');

test('Data Maintenance dashboard JavaScript parses', () => {
  assert.doesNotThrow(() => new vm.Script(script));
});

test('danger controls require the exact phrase, stopped sales, and ready devices', () => {
  const start = script.indexOf('function canReset');
  const end = script.indexOf('function setStatus');
  const source = script.slice(start, end);
  const canReset = new Function(
    `const CONFIRMATION_PHRASE = 'DELETE ALL ORDERS'; ${source}; return canReset;`
  )();
  const ready = { allActiveDevicesReady: true };
  assert.equal(canReset(ready, 'DELETE ALL ORDERS', true), true);
  assert.equal(canReset(ready, 'delete all orders', true), false);
  assert.equal(canReset(ready, 'DELETE ALL ORDERS', false), false);
  assert.equal(canReset({ allActiveDevicesReady: false }, 'DELETE ALL ORDERS', true), false);
});

test('Data Maintenance page is accessible, responsive, and explicit about preserved data', () => {
  for (const marker of [
    'for="confirmationPhrase"', 'id="salesStopped"', 'role="status"',
    'aria-live="polite"', '<dialog', 'Final confirmation',
    'Current stock and the Promotion QR destination URL will remain unchanged'
  ]) assert.match(html, new RegExp(marker.replace(/[?()]/g, '\\$&')));
  assert.match(html, /@media\(max-width:700px\)/);
  assert.match(html, /prefers-reduced-motion/);
  assert.match(script, /allActiveDevicesReady/);
  assert.match(script, /expectedGeneration/);
  assert.match(script, /Sync Manager Tablet first/);
});

test('dashboard uses dedicated authenticated APIs', () => {
  assert.match(api, /getDataMaintenance:\s*\(\) => apiFetch\('\/admin\/data-maintenance'\)/);
  assert.match(api, /resetOperationalData:[\s\S]*\/admin\/data-maintenance\/reset/);
});

test('Data Maintenance navigation is present after website-managed settings', () => {
  const pages = fs.readdirSync(path.join(root, 'dashboard'))
    .filter(name => name.endsWith('.html') && name !== 'login.html');
  for (const page of pages) {
    const source = fs.readFileSync(path.join(root, 'dashboard', page), 'utf8');
    if (!source.includes('payment-void-settings.html')) continue;
    assert.ok(source.includes('data-maintenance.html'), `${page} should link to Data Maintenance`);
    assert.ok(
      source.indexOf('data-maintenance.html') > source.indexOf('payment-void-settings.html'),
      `${page} should place Data Maintenance below Payment & Void Settings`
    );
  }
});
