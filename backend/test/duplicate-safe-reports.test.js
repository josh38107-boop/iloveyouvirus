const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const server = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');
const drawerHelper = fs.readFileSync(path.join(__dirname, '..', 'report-cash-drawer.js'), 'utf8');
const cloud = fs.readFileSync(path.join(__dirname, '..', 'cloud.js'), 'utf8');
const closedShiftAdjustmentMigration = fs.readFileSync(path.join(__dirname, '..', 'migrations', '015_closed_shift_adjustments.sql'), 'utf8');
const { computeCashDrawer } = require('../report-cash-drawer');

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

test('website reports exclude shifts deleted from Activity History by exact shift key', () => {
  const statsRoute = server.match(/app\.get\('\/admin\/stats'[\s\S]*?\/\/ GET \/admin\/sales/)[0];
  const reportOrderExclusions = statsRoute.match(/h\.shift_device_id = o\.shift_device_id AND h\.shift_id = o\.shift_id::text/g) || [];
  assert.ok(reportOrderExclusions.length >= 6);
  assert.match(statsRoute, /h\.shift_device_id = s\.device_id AND h\.shift_id = s\.id::text/);
  assert.doesNotMatch(statsRoute, /h\.event_id IN \('shift-open-' \|\|/);
  assert.doesNotMatch(statsRoute, /hideOpenCash|hideCloseCash/);
  assert.doesNotMatch(statsRoute, /if \([^)]*hide[^)]*\) return totals;/);
  assert.match(statsRoute, /SELECT s\.device_id, s\.id, s\.opened_at, s\.starting_cash_cents/);
  assert.match(statsRoute, /LEFT JOIN pos_order o ON o\.shift_id::text = s\.id::text\s+AND o\.shift_device_id = s\.device_id/);
  assert.match(statsRoute, /AND o\.created_at >= \$1 AND o\.created_at < \$2/);
  assert.match(statsRoute, /GROUP BY s\.device_id, s\.id/);
  assert.match(statsRoute, /computeCashDrawer\(\{/);
  assert.match(drawerHelper, /const starting = toCents\(shift\.starting_cash_cents\)/);
  assert.match(drawerHelper, /if \(!hasCashSales\) \{\s*totals\.latestNoCashStarting = starting;\s*return totals;\s*\}/);
  assert.match(drawerHelper, /totals\.startingCash \+= displayedStarting/);
  assert.match(drawerHelper, /totals\.expectedCashEnding \+= expected/);
});

test('website sales chart excludes shifts deleted from Activity History', () => {
  const salesRoute = server.match(/app\.get\('\/admin\/sales'[\s\S]*?\/\/ GET \/admin\/orders/)[0];
  assert.match(salesRoute, /await ensureHiddenActivityHistoryTable\(\)/);
  assert.match(salesRoute, /h\.shift_device_id = o\.shift_device_id AND h\.shift_id = o\.shift_id::text/);
  assert.doesNotMatch(salesRoute, /h\.event_id IN \('shift-open-' \|\|/);
  assert.match(salesRoute, /reportWindow: range/);
});

test('website report cash drawer balances actual ending to expected', () => {
  assert.match(drawerHelper, /const hasCashSales = shiftCashSales > 0/);
  assert.match(drawerHelper, /const expected = displayedStarting \+ shiftCashSales - closedShiftVoidsRefunds/);
  assert.match(drawerHelper, /totals\.expectedCashEnding \+= expected/);
  assert.match(drawerHelper, /totals\.actualCashEnding \+= expected/);
  assert.match(drawerHelper, /cashDrawer\.difference = 0/);
  assert.doesNotMatch(drawerHelper, /ending_cash_cents/);
  assert.doesNotMatch(drawerHelper, /cashDrawer\.actualCashEnding - cashDrawer\.expectedCashEnding/);
});

test('website report cash drawer uses latest starting cash when there are no cash sales', () => {
  assert.match(drawerHelper, /totals\.hasCashSales = true/);
  assert.match(drawerHelper, /const displayedStarting = starting \+ added - manualRemoved/);
  assert.match(drawerHelper, /latestNoCashStarting: 0/);
  assert.match(drawerHelper, /if \(!cashDrawer\.hasCashSales && cashDrawer\.latestNoCashStarting > 0\) \{/);
  assert.match(drawerHelper, /cashDrawer\.startingCash = cashDrawer\.latestNoCashStarting/);
  assert.match(drawerHelper, /cashDrawer\.expectedCashEnding = cashDrawer\.latestNoCashStarting/);
  assert.match(drawerHelper, /cashDrawer\.actualCashEnding = cashDrawer\.latestNoCashStarting/);
  assert.match(drawerHelper, /delete cashDrawer\.hasCashSales/);
  assert.match(drawerHelper, /delete cashDrawer\.latestNoCashStarting/);
  assert.match(drawerHelper, /if \(!cashDrawer\.hasActivity\) \{/);
  assert.match(drawerHelper, /cashDrawer\.startingCash = 0/);
  assert.match(drawerHelper, /cashDrawer\.expectedCashEnding = 0/);
  assert.match(drawerHelper, /cashDrawer\.actualCashEnding = 0/);
  assert.match(drawerHelper, /cashDrawer\.cashAdded = 0/);
  assert.match(drawerHelper, /cashDrawer\.cashRemoved = 0/);
  assert.doesNotMatch(drawerHelper, /hasActivity: shiftsRes\.rows\.length > 0/);
});

test('website report splits closed shift refunds from manual cash removed', () => {
  const statsRoute = server.match(/app\.get\('\/admin\/stats'[\s\S]*?\/\/ GET \/admin\/sales/)[0];
  assert.match(server, /async function optionalReportQuery/);
  assert.match(server, /Optional report query skipped/);
  assert.match(statsRoute, /optionalReportQuery\('closed shift adjustments', `[\s\S]*?FROM closed_shift_adjustment a/);
  assert.match(statsRoute, /optionalReportQuery\('void\/refund cash fallback', `[\s\S]*?FROM pos_order o/);
  assert.match(statsRoute, /JOIN shift original_shift ON original_shift\.device_id = o\.shift_device_id/);
  assert.match(statsRoute, /original_shift\.id::text = o\.shift_id::text/);
  assert.match(statsRoute, /o\.status IN \('void', 'refunded'\)/);
  assert.match(statsRoute, /original_shift\.closed_at IS NOT NULL/);
  assert.match(statsRoute, /fallbackClosedShiftVoidsRefunds: Math\.max/);
  assert.match(statsRoute, /computeCashDrawer\(\{/);
  assert.match(drawerHelper, /const assignedClosedShiftVoidsRefunds = Math\.min\(\s*removed,/);
  assert.match(drawerHelper, /const closedShiftVoidsRefunds = assignedClosedShiftVoidsRefunds \+ unassignedForShift/);
  assert.match(drawerHelper, /const manualRemoved = Math\.max\(removed - closedShiftVoidsRefunds, 0\)/);
  assert.match(drawerHelper, /totals\.cashRemoved \+= manualRemoved/);
  assert.match(drawerHelper, /totals\.closedShiftVoidsRefunds \+= closedShiftVoidsRefunds/);
  assert.match(drawerHelper, /closedShiftVoidsRefunds: 0/);
});

test('website report moves closed shift refund amount back into starting cash', () => {
  const drawer = computeCashDrawer({
    shifts: [{
      device_id: 'counter-a',
      id: '12',
      starting_cash_cents: 83500,
      cash_added_cents: 0,
      cash_removed_cents: 36000,
      cash_sales: 103000
    }],
    closedShiftAdjustments: [{
      current_shift_device_id: 'counter-a',
      current_shift_id: '12',
      amount_cents: 26000
    }],
    cashSales: 103000,
    onlinePayments: 0
  });
  assert.equal(drawer.startingCash, 73500);
  assert.equal(drawer.cashRemoved, 10000);
  assert.equal(drawer.closedShiftVoidsRefunds, 26000);
  assert.equal(drawer.expectedCashEnding, 150500);
  assert.equal(drawer.actualCashEnding, 150500);
  assert.equal(drawer.totalCashAndOnline, 150500);
});

test('website report can split cash removed from void/refund fallback rows', () => {
  const drawer = computeCashDrawer({
    shifts: [{
      device_id: 'counter-a',
      id: '12',
      starting_cash_cents: 83500,
      cash_added_cents: 0,
      cash_removed_cents: 36000,
      cash_sales: 109000
    }],
    fallbackClosedShiftVoidsRefunds: 26000,
    cashSales: 109000,
    onlinePayments: 0
  });
  assert.equal(drawer.startingCash, 73500);
  assert.equal(drawer.cashRemoved, 10000);
  assert.equal(drawer.closedShiftVoidsRefunds, 26000);
  assert.equal(drawer.expectedCashEnding, 156500);
});

test('closed shift adjustments are stored and synced for dashboard reports', () => {
  assert.match(closedShiftAdjustmentMigration, /CREATE TABLE IF NOT EXISTS closed_shift_adjustment/);
  assert.match(closedShiftAdjustmentMigration, /PRIMARY KEY \(device_id, id\)/);
  assert.match(closedShiftAdjustmentMigration, /current_shift_device_id TEXT NOT NULL/);
  assert.match(closedShiftAdjustmentMigration, /amount_cents INTEGER NOT NULL DEFAULT 0/);
  assert.match(cloud, /closed_shift_adjustment: \{ role: 'counter'/);
  assert.match(cloud, /current_shift_device_id/);
  assert.match(cloud, /const OPERATION_TABLES = \[[^\]]*'closed_shift_adjustment'/);
  assert.match(server, /'closed_shift_adjustment'/);
});

test('website report sales metrics exclude complimentary paid orders', () => {
  assert.match(server, /function nonComplimentaryOrderPredicate\(orderAlias\)/);
  assert.match(server, /LOWER\(complimentary_payment\.method\) = 'complimentary'/);
  assert.match(server, /SELECT COUNT\(\*\) as count,[\s\S]*?AND \$\{nonComplimentaryOrderPredicate\('o'\)\}/);
  assert.match(server, /item_totals AS \([\s\S]*?SELECT ol\.name, SUM\(ol\.quantity\) as qty,[\s\S]*?AND \$\{nonComplimentaryOrderPredicate\('o'\)\}/);
  assert.match(server, /COUNT\(\*\) as orders,[\s\S]*?AND \$\{nonComplimentaryOrderPredicate\('o'\)\}/);
});
