const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../..');
const server = fs.readFileSync(path.join(root, 'backend', 'server.js'), 'utf8');
const html = fs.readFileSync(path.join(root, 'dashboard', 'history.html'), 'utf8');
const api = fs.readFileSync(path.join(root, 'dashboard', 'js', 'api.js'), 'utf8');
const migration = fs.readFileSync(path.join(root, 'backend', 'migrations', '012_hidden_activity_history.sql'), 'utf8');
const androidMain = fs.readFileSync(path.join(root, 'app', 'src', 'main', 'java', 'com', 'kape', 'coffeepos', 'MainActivity.kt'), 'utf8');
const androidViewModel = fs.readFileSync(path.join(root, 'app', 'src', 'main', 'java', 'com', 'kape', 'coffeepos', 'viewmodel', 'PosViewModel.kt'), 'utf8');
const androidRepositories = fs.readFileSync(path.join(root, 'app', 'src', 'main', 'java', 'com', 'kape', 'coffeepos', 'data', 'Repositories.kt'), 'utf8');

test('Android Activity History delete feature is not present', () => {
  assert.doesNotMatch(androidMain, /Delete History Entry/);
  assert.doesNotMatch(androidMain, /canHideActivityHistoryEvent/);
  assert.doesNotMatch(androidViewModel, /historyDeleteTarget/);
  assert.doesNotMatch(androidViewModel, /confirmDeleteHistoryEntry/);
  assert.doesNotMatch(androidRepositories, /hidden_history_event_ids/);
});

test('website Activity History hides only shift open and close events', () => {
  assert.match(migration, /CREATE TABLE IF NOT EXISTS hidden_activity_history/);
  assert.match(server, /HIDEABLE_HAPPENING_ID_PATTERN = \/\^shift-\(open\|close\)-\\d\+\$\//);
  assert.match(server, /app\.delete\('\/admin\/happenings\/:id', adminAuthenticate/);
  assert.match(server, /Only shift activity history entries can be deleted\./);
  assert.match(server, /INSERT INTO hidden_activity_history/);
  assert.doesNotMatch(server, /DELETE FROM shift/);
  assert.match(server, /SELECT event_id FROM hidden_activity_history/);
  assert.match(server, /visibleList = list\.filter/);
});

test('history dashboard exposes confirmed delete for shift rows only', () => {
  assert.match(api, /deleteHappening: \(id\) => apiFetch\(`\/admin\/happenings\/\$\{encodeURIComponent\(id\)\}`/);
  assert.match(api, /method: 'DELETE'/);
  assert.match(html, /function canDeleteHappening\(h\)/);
  assert.match(html, /\^\(SHIFT_OPENED\|SHIFT_CLOSED\)\$/);
  assert.match(html, /function deleteHappening\(id\)/);
  assert.match(html, /confirm\(`Delete "\$\{target\.title\}" from Activity History\?/);
  assert.match(html, /api\.deleteHappening\(id\)/);
  assert.match(html, /class="btn btn-delete-outline history-delete"/);
});

test('history dashboard JavaScript parses', () => {
  const script = html.match(/<script>\s*([\s\S]*?)\s*<\/script>\s*<\/body>/)[1];
  assert.doesNotThrow(() => new vm.Script(script));
});
