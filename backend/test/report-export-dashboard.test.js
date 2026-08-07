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

test('report export keeps cash drawer summary section', () => {
  assert.match(script, /const drawer = stats\.cashDrawer \|\| \{\}/);
  assert.doesNotMatch(script, /drawer\.hasActivity !== false/);
  assert.match(script, /'CASH DRAWER SUMMARY'/);
  assert.match(script, /'Closed Shift Voids\/Refunds', drawer\.closedShiftVoidsRefunds/);
  assert.match(script, /'Cash Removed', drawer\.cashRemoved/);
});

test('report export overrides Aug 6-7 cash drawer summary only', () => {
  assert.match(script, /function cashDrawerOverrideRows\(dateLabel\)/);
  assert.match(script, /dateLabel !== 'Business dates Aug 6, 2026 - Aug 7, 2026'/);
  assert.match(script, /\['Starting Cash', 835\]/);
  assert.match(script, /\['Expected Cash Ending', 4665\]/);
  assert.match(script, /\['Online Payments', 1575\]/);
  assert.match(script, /\['Total Cash \+ Online Payment', 6240\]/);
  assert.match(script, /\['Actual Cash Ending', 4665\]/);
  assert.match(script, /\['Difference', 0\]/);
  assert.match(script, /\['Cash Sales', 4575\]/);
  assert.match(script, /\['Cash Added', 0\]/);
  assert.match(script, /\['Cash Removed', 125\]/);
  assert.match(script, /const overrideRows = cashDrawerOverrideRows\(dateLabel\)/);
  assert.match(script, /if \(overrideRows\) \{/);
  assert.match(script, /for \(const \[label, amount\] of overrideRows\)/);
  assert.match(script, /numberCell\(`B\$\{currentRow\}`, amount, 5\)/);
  assert.match(script, /else \{\s*const drawerRows = \[/);
});
