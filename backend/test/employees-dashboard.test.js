const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const dashboard = fs.readFileSync(path.resolve(__dirname, '../../dashboard/employees.html'), 'utf8');
const api = fs.readFileSync(path.resolve(__dirname, '../../dashboard/js/api.js'), 'utf8');
const server = fs.readFileSync(path.resolve(__dirname, '../server.js'), 'utf8');

test('employees dashboard inline JavaScript parses', () => {
  const scripts = [...dashboard.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)]
    .map(match => match[1]).filter(source => source.trim());
  for (const source of scripts) assert.doesNotThrow(() => new vm.Script(source));
});

test('staff manager exposes accessible add, edit, role, PIN, and deactivation controls', () => {
  for (const marker of ['Add Staff', 'Edit Staff', 'Cashier', 'Manager', 'Confirm PIN', 'Show PIN', 'Deactivate Staff']) {
    assert.match(dashboard, new RegExp(marker));
  }
  assert.match(dashboard, /role="dialog"/);
  assert.match(dashboard, /role="alertdialog"/);
  assert.match(dashboard, /aria-live="polite"/);
  assert.match(dashboard, /inputmode="numeric"/);
  assert.match(dashboard, /event\.key === 'Escape'/);
  assert.match(dashboard, /event\.key !== 'Tab'/);
  assert.match(dashboard, /@media\(max-width:700px\)/);
});

test('employees dashboard uses safe rendering and dedicated PIN-sanitized endpoints', () => {
  for (const method of ['getEmployees', 'createEmployee', 'updateEmployee', 'deactivateEmployee']) {
    assert.match(api, new RegExp(method));
  }
  assert.doesNotMatch(api, /admin\/data\/employee/);
  assert.doesNotMatch(api, /upsertEmployee/);
  assert.match(dashboard, /escapeHtml\(employee\.name\)/);
  assert.match(dashboard, /type="password"/);
  assert.match(server, /table === 'employee'/);
  assert.match(server, /app\.get\('\/admin\/employees'/);
});
