const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const server = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');

test('website reports use paid orders as revenue source and dedupe child rows', () => {
  assert.match(server, /COALESCE\(SUM\(subtotal_cents\), 0\) as gross/);
  assert.match(server, /COALESCE\(SUM\(total_cents\), 0\) as net/);
  assert.match(server, /WHERE o\.created_at >= \$1 AND o\.created_at < \$2 AND o\.status = 'paid'/);
  assert.match(server, /WITH deduped_order_line AS \(/);
  assert.match(server, /SELECT DISTINCT ON \(\s*order_id, item_id, name, quantity, unit_price_cents/);
  assert.doesNotMatch(server, /deduped_order_line AS \([\s\S]*?\n\s*\)\s*\)\s*,\s*deduped_payment AS \(/);
  assert.match(server, /WITH deduped_payment AS \(/);
  assert.match(server, /order_id, method, COALESCE\(payment_category, ''\), amount_cents/);
  assert.match(server, /FROM deduped_payment p/);
});

test('website top selling items expose payment mode without multiplying revenue', () => {
  assert.match(server, /item_totals AS \(/);
  assert.match(server, /item_payment_modes AS \(/);
  assert.match(server, /LEFT JOIN deduped_payment p ON p\.order_id = o\.id/);
  assert.match(server, /BOOL_OR\(UPPER\(COALESCE\(p\.payment_category, ''\)\) = 'CASH'/);
  assert.match(server, /LOWER\(p\.method\) IN \('online', 'gcash'\)/);
  assert.match(server, /WHEN COALESCE\(m\.has_cash, false\) AND COALESCE\(m\.has_online, false\) THEN 'Cash \+ Online'/);
  assert.match(server, /END as payment_method/);
  assert.match(server, /FROM item_totals t\s+LEFT JOIN item_payment_modes m ON m\.name = t\.name/);
});

test('website stats expose duplicate-safe order summary rows', () => {
  assert.match(server, /orderSummary: orderSummaryRes\.rows/);
  assert.match(server, /order_items AS \(/);
  assert.match(server, /STRING_AGG\(quantity \|\| 'x ' \|\| name, ', ' ORDER BY name\) as items/);
  assert.match(server, /order_payment_modes AS \(/);
  assert.match(server, /SELECT o\.id,[\s\S]*?o\.created_at,[\s\S]*?COALESCE\(e\.name, '-'\) as employee_name/);
  assert.match(server, /COALESCE\(NULLIF\(o\.customer_name, ''\), '-'\) as customer_name/);
  assert.match(server, /COALESCE\(oi\.items, '-'\) as items,[\s\S]*?o\.total_cents/);
  assert.match(server, /LEFT JOIN employee e ON e\.id = o\.employee_id/);
  assert.match(server, /LEFT JOIN order_items oi ON oi\.order_id = o\.id/);
  assert.match(server, /LEFT JOIN order_payment_modes pm ON pm\.order_id = o\.id/);
  assert.match(server, /WHEN COALESCE\(pm\.has_cash, false\) AND COALESCE\(pm\.has_online, false\) THEN 'Cash \+ Online'/);
  assert.match(server, /WHERE o\.created_at >= \$1 AND o\.created_at < \$2 AND o\.status = 'paid'[\s\S]*?AND \$\{nonComplimentaryOrderPredicate\('o'\)\}/);
});

test('website report cash drawer includes shifts hidden from Activity History', () => {
  const statsRoute = server.match(/app\.get\('\/admin\/stats'[\s\S]*?\/\/ GET \/admin\/sales/)[0];
  assert.doesNotMatch(statsRoute, /hiddenShiftRes/);
  assert.doesNotMatch(statsRoute, /hiddenShiftEventIds/);
  assert.doesNotMatch(statsRoute, /FROM hidden_activity_history/);
  assert.doesNotMatch(statsRoute, /hideOpenCash|hideCloseCash/);
  assert.doesNotMatch(statsRoute, /if \([^)]*hide[^)]*\) return totals;/);
  assert.match(statsRoute, /const starting = parseInt\(shift\.starting_cash_cents \|\| 0\)/);
  assert.match(statsRoute, /totals\.expectedCashEnding \+= expected/);
});

test('website report cash drawer balances actual ending to expected', () => {
  const statsRoute = server.match(/app\.get\('\/admin\/stats'[\s\S]*?\/\/ GET \/admin\/sales/)[0];
  assert.match(statsRoute, /totals\.expectedCashEnding \+= expected/);
  assert.match(statsRoute, /totals\.actualCashEnding \+= expected/);
  assert.match(statsRoute, /cashDrawer\.difference = 0/);
  assert.doesNotMatch(statsRoute, /parseInt\(shift\.ending_cash_cents \|\| 0\)/);
  assert.doesNotMatch(statsRoute, /cashDrawer\.actualCashEnding - cashDrawer\.expectedCashEnding/);
});

test('website report sales metrics exclude complimentary paid orders', () => {
  assert.match(server, /function nonComplimentaryOrderPredicate\(orderAlias\)/);
  assert.match(server, /LOWER\(complimentary_payment\.method\) = 'complimentary'/);
  assert.match(server, /SELECT COUNT\(\*\) as count,[\s\S]*?AND \$\{nonComplimentaryOrderPredicate\('o'\)\}/);
  assert.match(server, /item_totals AS \([\s\S]*?SELECT ol\.name, SUM\(ol\.quantity\) as qty,[\s\S]*?AND \$\{nonComplimentaryOrderPredicate\('o'\)\}/);
  assert.match(server, /COUNT\(\*\) as orders,[\s\S]*?AND \$\{nonComplimentaryOrderPredicate\('o'\)\}/);
});
