const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../..');
const html = fs.readFileSync(path.join(root, 'dashboard', 'discounts.html'), 'utf8');
const script = fs.readFileSync(path.join(root, 'dashboard', 'js', 'discounts.js'), 'utf8');
const api = fs.readFileSync(path.join(root, 'dashboard', 'js', 'api.js'), 'utf8');

test('discount dashboard JavaScript parses', () => {
  assert.doesNotThrow(() => new vm.Script(script));
});

test('discount dashboard exposes accessible benefit and custom controls', () => {
  for (const marker of [
    'for="seniorPercent"', 'for="pwdPercent"', 'for="discountName"',
    'for="discountPercent"', 'for="discountScope"', 'role="status"',
    'aria-live="polite"', '<dialog', 'Disable this discount?'
  ]) assert.match(html, new RegExp(marker.replace(/[?()]/g, '\\$&')));
  assert.match(html, /@media\(max-width:700px\)/);
  assert.match(html, /prefers-reduced-motion/);
  assert.match(script, /requestAnimationFrame\(\(\) => document\.getElementById\('discountName'\)\.focus/);
  assert.match(script, /Historical orders will not change/);
  assert.doesNotMatch(script, /innerHTML\s*=\s*`/);
});

test('discount dashboard uses authenticated dedicated APIs', () => {
  for (const method of [
    'getDiscountSettings', 'updateDiscountBenefits',
    'createCustomDiscount', 'updateCustomDiscount'
  ]) assert.match(api, new RegExp(method));
});
