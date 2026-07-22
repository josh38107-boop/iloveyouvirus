const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const dashboardPath = path.resolve(__dirname, '../../dashboard/inventory.html');
const apiPath = path.resolve(__dirname, '../../dashboard/js/api.js');
const dashboard = fs.readFileSync(dashboardPath, 'utf8');
const api = fs.readFileSync(apiPath, 'utf8');

test('inventory dashboard inline JavaScript parses', () => {
  const scripts = [...dashboard.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)]
    .map(match => match[1]).filter(source => source.trim());
  assert.ok(scripts.length > 0);
  for (const source of scripts) assert.doesNotThrow(() => new vm.Script(source));
});

test('ingredient editor exposes POS-aligned controls and accessible dialogs', () => {
  for (const unit of ['oz', 'ml', 'g', 'kg', 'L', 'ea', 'pcs', 'tsp', 'tbsp', 'cup', 'pack', 'box', 'can', 'bottle']) {
    assert.match(dashboard, new RegExp(`['\"]${unit}['\"]`));
  }
  assert.match(dashboard, /Takeout Only \(do not deduct for Dine-In\)/);
  assert.match(dashboard, /role="dialog"/);
  assert.match(dashboard, /role="alertdialog"/);
  assert.match(dashboard, /aria-live="polite"/);
  assert.match(dashboard, /event\.key === 'Escape'/);
  assert.match(dashboard, /event\.key !== 'Tab'/);
});

test('dashboard uses dedicated inventory mutations and safe rendering', () => {
  assert.match(api, /createInventoryIngredient/);
  assert.match(api, /updateInventoryIngredient/);
  assert.match(api, /deleteInventoryIngredient/);
  assert.doesNotMatch(api, /upsertIngredient/);
  assert.match(dashboard, /escapeHtml\(i\.name\)/);
  assert.match(dashboard, /data-action="delete"/);
  assert.match(dashboard, /btn-delete-outline/);
});
