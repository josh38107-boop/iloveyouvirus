const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../..');
const server = fs.readFileSync(path.join(root, 'backend', 'server.js'), 'utf8');
const html = fs.readFileSync(path.join(root, 'dashboard', 'orders.html'), 'utf8');
const api = fs.readFileSync(path.join(root, 'dashboard', 'js', 'api.js'), 'utf8');

test('orders detail endpoint uses authenticated route and child records', () => {
  assert.match(server, /app\.get\('\/admin\/orders\/:id', adminAuthenticate/);
  assert.match(server, /WHERE o\.id = \$1/);
  assert.match(server, /FROM pos_order o/);
  assert.match(server, /WITH deduped_order_line AS \(/);
  assert.match(server, /FROM order_line/);
  assert.match(server, /WITH deduped_payment AS \(/);
  assert.match(server, /FROM payment/);
  assert.match(server, /payment_category: paymentCategory\(row\)/);
  assert.match(server, /res\.status\(404\)\.json\(\{ error: 'Order not found' \}\)/);
});

test('orders dashboard exposes accessible order detail modal', () => {
  for (const marker of [
    'id="orderModal"', 'role="dialog"', 'aria-modal="true"',
    'aria-labelledby="orderModalTitle"', 'aria-live="polite"',
    'class="order-row"', 'tabindex="0"', 'role="button"',
    'openOrderModal(row.dataset.orderId, row)', "event.key === 'Escape'"
  ]) assert.match(html, new RegExp(marker.replace(/[?()]/g, '\\$&')));
});

test('orders dashboard uses dedicated detail API', () => {
  assert.match(api, /getOrder: \(id\) => apiFetch\(`\/admin\/orders\/\$\{encodeURIComponent\(id\)\}`\)/);
});

test('orders dashboard JavaScript parses', () => {
  const script = html.match(/<script>\s*([\s\S]*?)\s*<\/script>\s*<\/body>/)[1];
  assert.doesNotThrow(() => new vm.Script(script));
});
