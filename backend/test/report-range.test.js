const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {
  MANILA_TIME_ZONE,
  reportWindowForRange
} = require('../report-range');

const cutoffMinutes = 120;

function ms(localDateTime) {
  return new Date(`${localDateTime}+08:00`).getTime();
}

test('today before 2 AM stays on the previous Manila business date', () => {
  const window = reportWindowForRange({
    daysParam: 1,
    cutoffMinutes,
    nowMs: ms('2026-08-04T00:20:00')
  });

  assert.equal(window.timezone, MANILA_TIME_ZONE);
  assert.equal(window.businessDate, '2026-08-03');
  assert.equal(window.fromMs, ms('2026-08-03T02:00:00'));
  assert.equal(window.toMs, ms('2026-08-04T00:20:00'));
  assert.ok(ms('2026-08-03T23:34:43') >= window.fromMs);
  assert.ok(ms('2026-08-03T23:34:43') < window.toMs);
});

test('today at 1:59 AM is still the previous Manila business date', () => {
  const window = reportWindowForRange({
    daysParam: 1,
    cutoffMinutes,
    nowMs: ms('2026-08-04T01:59:00')
  });

  assert.equal(window.businessDate, '2026-08-03');
  assert.equal(window.fromMs, ms('2026-08-03T02:00:00'));
  assert.equal(window.toMs, ms('2026-08-04T01:59:00'));
});

test('today at 2 AM starts the new Manila business date', () => {
  const window = reportWindowForRange({
    daysParam: 1,
    cutoffMinutes,
    nowMs: ms('2026-08-04T02:00:00')
  });

  assert.equal(window.businessDate, '2026-08-04');
  assert.equal(window.fromMs, ms('2026-08-04T02:00:00'));
  assert.equal(window.toMs, ms('2026-08-04T02:00:00'));
});

test('report endpoints expose the resolved business-day window metadata', () => {
  const server = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');
  assert.match(server, /reportWindow: range/);
  assert.match(server, /res\.json\(\{ rows: result\.rows, reportWindow: range \}\)/);
});
