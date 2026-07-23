const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../..');
const html = fs.readFileSync(path.join(root, 'dashboard', 'payment-void-settings.html'), 'utf8');
const script = fs.readFileSync(path.join(root, 'dashboard', 'js', 'payment-void-settings.js'), 'utf8');
const api = fs.readFileSync(path.join(root, 'dashboard', 'js', 'api.js'), 'utf8');

test('payment and void dashboard JavaScript parses', () => {
  assert.doesNotThrow(() => new vm.Script(script));
});

test('payment and void dashboard exposes accessible responsive controls', () => {
  for (const marker of [
    'for="voidPin"', 'aria-pressed="false"', 'for="methodName"',
    'for="methodCategory"', 'role="status"', 'aria-live="polite"',
    '<dialog', 'Delete payment method?'
  ]) assert.match(html, new RegExp(marker.replace(/[?()]/g, '\\$&')));
  assert.match(html, /@media\(max-width:640px\)/);
  assert.match(html, /prefers-reduced-motion/);
  assert.match(script, /requestAnimationFrame\(\(\) => document\.getElementById\('methodName'\)\.focus/);
  assert.match(script, /Historical payments will remain unchanged/);
  assert.doesNotMatch(script, /innerHTML\s*=/);
});

test('payment and void dashboard uses dedicated authenticated APIs', () => {
  for (const method of [
    'getPaymentVoidSettings', 'updateVoidRefundPin', 'createPaymentMethod',
    'updatePaymentMethod', 'deletePaymentMethod'
  ]) assert.match(api, new RegExp(method));
});

test('Payment & Void Settings navigation follows Discount Settings on every admin page', () => {
  const pages = fs.readdirSync(path.join(root, 'dashboard')).filter(name => name.endsWith('.html') && !['login.html'].includes(name));
  for (const page of pages) {
    const source = fs.readFileSync(path.join(root, 'dashboard', page), 'utf8');
    if (!source.includes('discounts.html')) continue;
    assert.ok(
      source.indexOf('payment-void-settings.html') > source.indexOf('discounts.html'),
      `${page} should place Payment & Void Settings below Discount Settings`
    );
  }
});

