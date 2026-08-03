const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

test('business-day migration is additive with 2 AM defaults', () => {
  const migration = fs.readFileSync(path.join(__dirname, '..', 'migrations', '011_business_day_cutoff.sql'), 'utf8');
  assert.match(migration, /business_day_cutoff_minutes INTEGER NOT NULL DEFAULT 120/);
  assert.match(migration, /business_day_settings_updated_at BIGINT NOT NULL DEFAULT 0/);
  assert.doesNotMatch(migration, /\bDROP\b|\bTRUNCATE\b|\bDELETE\b/i);
});

test('business-day admin endpoints are authenticated and conflict on open shifts', () => {
  const server = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');
  assert.match(server, /app\.get\('\/admin\/business-day-settings', adminAuthenticate/);
  assert.match(server, /app\.put\('\/admin\/business-day-settings', adminAuthenticate/);
  assert.match(server, /cutoffMinutes < 0 \|\| cutoffMinutes > 1439/);
  assert.match(server, /res\.status\(409\)/);
  assert.match(server, /WHERE closed_at IS NULL/);
});

test('reports use business-date ranges and half-open windows', () => {
  const server = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');
  assert.match(server, /reportWindowForRange\(\{ daysParam, fromDate, toDate, cutoffMinutes \}\)/);
  assert.match(server, /o\.created_at >= \$1 AND o\.created_at < \$2/);
  assert.match(server, /created_at - \$3/);
  assert.match(server, /AT TIME ZONE 'Asia\/Manila'/);
});

test('device sync cannot overwrite web-owned business-day settings', () => {
  const cloud = fs.readFileSync(path.join(__dirname, '..', 'cloud.js'), 'utf8');
  const server = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');
  assert.match(cloud, /business_day_cutoff_minutes/);
  assert.match(cloud, /business_day_settings_updated_at/);
  assert.match(cloud, /!\['void_refund_pin', 'payment_void_settings_updated_at', 'business_day_cutoff_minutes', 'business_day_settings_updated_at'\]\.includes\(key\)/);
  assert.match(server, /delete cleaned\.business_day_cutoff_minutes/);
});

test('dashboard exposes business-day settings page and API client', () => {
  const html = fs.readFileSync(path.join(__dirname, '..', '..', 'dashboard', 'business-day-settings.html'), 'utf8');
  const api = fs.readFileSync(path.join(__dirname, '..', '..', 'dashboard', 'js', 'api.js'), 'utf8');
  assert.match(html, /id="cutoffTime" type="time"/);
  assert.match(html, /id="openShiftList"/);
  assert.match(api, /getBusinessDaySettings/);
  assert.match(api, /updateBusinessDaySettings/);
});
