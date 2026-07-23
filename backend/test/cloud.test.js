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

test('reset protocol headers accept only non-negative safe integers', () => {
  assert.equal(_test.nonNegativeHeader('3'), 3);
  assert.equal(_test.nonNegativeHeader(0), 0);
  assert.equal(_test.nonNegativeHeader('-1'), 0);
  assert.equal(_test.nonNegativeHeader('not-a-number'), 0);
  assert.equal(_test.nonNegativeHeader(Number.MAX_SAFE_INTEGER + 1), 0);
});
