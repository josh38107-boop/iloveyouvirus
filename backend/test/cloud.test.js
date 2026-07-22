const test = require('node:test');
const assert = require('node:assert/strict');
const { _test } = require('../cloud');

test('admin sessions are signed and reject tampering', () => {
  const session = _test.makeAdminSession('admin');
  assert.equal(_test.verifyAdminSession(session).sub, 'admin');
  assert.equal(_test.verifyAdminSession(`${session}x`), null);
  assert.equal(_test.verifyAdminSession('invalid'), null);
});

test('constant comparison safely handles unequal lengths', () => {
  assert.equal(_test.constantEqual('same', 'same'), true);
  assert.equal(_test.constantEqual('short', 'a much longer value'), false);
});

test('cookie parser ignores malformed cookie fragments', () => {
  const parsed = _test.parseCookies({ headers: { cookie: 'bad; kape_admin_session=abc.def; theme=light' } });
  assert.equal(parsed.kape_admin_session, 'abc.def');
  assert.equal(parsed.theme, 'light');
  assert.equal(parsed.bad, undefined);
});
