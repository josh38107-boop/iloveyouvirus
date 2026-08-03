const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../..');
const script = fs.readFileSync(path.join(root, 'dashboard', 'js', 'report-export.js'), 'utf8');
const reportsPage = fs.readFileSync(path.join(root, 'dashboard', 'reports.html'), 'utf8');

test('report export JavaScript parses', () => {
  assert.doesNotThrow(() => new vm.Script(script));
});

test('report export uses order summary instead of top selling items', () => {
  assert.match(script, /'ORDER SUMMARY'/);
  assert.doesNotMatch(script, /'TOP SELLING ITEMS'/);
  assert.doesNotMatch(script, /'Order ID'/);
  assert.doesNotMatch(script, /'Customer'/);
  assert.match(script, /'Date\/Time'[\s\S]*'Cashier'[\s\S]*'Payment Method'[\s\S]*'Items'[\s\S]*'Total'/);
  assert.match(script, /stats\.orderSummary/);
  assert.match(script, /function formatDateTime\(value\)/);
  assert.match(script, /formatDateTime\(orderItem\.created_at\)/);
  assert.doesNotMatch(script, /textCell\(`A\$\{currentRow\}`, orderItem\.created_at/);
  assert.match(script, /payment_method: 'Unavailable'/);
  assert.match(script, /<dimension ref="A1:E\$\{currentRow\}"/);
  assert.match(script, /<col min="4" max="4" width="45" customWidth="1"\/>/);
});

test('report export shows actual date range in report details', () => {
  assert.match(script, /function reportDateRangeLabel\(days, generatedAt, customRange\)/);
  assert.match(script, /const rangeContext = \{ \.\.\.\(customRange \|\| \{\}\), reportWindow: stats\?\.reportWindow \|\| customRange\?\.reportWindow \}/);
  assert.match(script, /const dateLabel = reportDateRangeLabel\(days, generatedAt, rangeContext\)/);
  assert.match(script, /customRange\?\.reportWindow/);
  assert.match(script, /customRange\?\.fromDate/);
  assert.match(script, /customRange\?\.toDate/);
  assert.doesNotMatch(script, /Number\(days\) === 1 \? 'Today' : `Last \$\{days\} Days`/);
  assert.match(script, /makeWorksheet\(stats, days, generatedAt, customRange\)/);
  assert.match(script, /build\(stats, days, now, rangeContext\)/);
  assert.match(reportsPage, /ReportWorkbook\.download\(stats, days, customRange\)/);
});
