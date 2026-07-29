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
  assert.match(server, /WITH deduped_payment AS \(/);
  assert.match(server, /order_id, method, COALESCE\(payment_category, ''\), amount_cents/);
  assert.match(server, /FROM deduped_payment p/);
});

test('website report sales metrics exclude complimentary paid orders', () => {
  assert.match(server, /function nonComplimentaryOrderPredicate\(orderAlias\)/);
  assert.match(server, /LOWER\(complimentary_payment\.method\) = 'complimentary'/);
  assert.match(server, /SELECT COUNT\(\*\) as count,[\s\S]*?AND \$\{nonComplimentaryOrderPredicate\('o'\)\}/);
  assert.match(server, /SELECT name, SUM\(quantity\) as qty,[\s\S]*?AND \$\{nonComplimentaryOrderPredicate\('o'\)\}/);
  assert.match(server, /COUNT\(\*\) as orders,[\s\S]*?AND \$\{nonComplimentaryOrderPredicate\('o'\)\}/);
});
