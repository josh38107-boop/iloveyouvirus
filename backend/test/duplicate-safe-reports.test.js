const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const server = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');

test('website reports use paid orders as revenue source and dedupe child rows', () => {
  assert.match(server, /COALESCE\(SUM\(subtotal_cents\), 0\) as gross/);
  assert.match(server, /COALESCE\(SUM\(total_cents\), 0\) as net/);
  assert.match(server, /WHERE created_at >= \$1 AND created_at < \$2 AND status = 'paid'/);
  assert.match(server, /WITH deduped_order_line AS \(/);
  assert.match(server, /SELECT DISTINCT ON \(\s*order_id, item_id, name, quantity, unit_price_cents/);
  assert.match(server, /WITH deduped_payment AS \(/);
  assert.match(server, /order_id, method, COALESCE\(payment_category, ''\), amount_cents/);
  assert.match(server, /FROM deduped_payment p/);
});

