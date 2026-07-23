const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const loginPage = fs.readFileSync(path.join(__dirname, '..', '..', 'dashboard', 'login.html'), 'utf8');

test('login page provides an accessible password visibility toggle', () => {
  assert.match(loginPage, /id="passwordToggle"/);
  assert.match(loginPage, /aria-controls="password"/);
  assert.match(loginPage, /aria-pressed="false"/);
  assert.match(loginPage, /passwordInput\.type = showPassword \? 'text' : 'password'/);
});

test('login page provides password recovery guidance', () => {
  assert.match(loginPage, /id="forgotPassword">Forgot password\?<\/button>/);
  assert.match(loginPage, /<dialog[^>]+id="recoveryDialog"/);
  assert.match(loginPage, /ADMIN_PASSWORD/);
  assert.match(loginPage, /aria-labelledby="recoveryTitle"/);
});

test('login form exposes labels, autofill hints, and live errors', () => {
  assert.match(loginPage, /<label for="username">Username<\/label>/);
  assert.match(loginPage, /autocomplete="current-password"/);
  assert.match(loginPage, /id="errorMsg" role="alert" aria-live="polite"/);
});
