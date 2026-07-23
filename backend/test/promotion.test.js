const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { _test } = require('../rpc');

test('promotion QR template safely encodes the unique claim code', () => {
  assert.equal(
    _test.buildPromotionQrUrl('https://forms.example.test/view?claim={CLAIM_CODE}', 'FREE A/1'),
    'https://forms.example.test/view?claim=FREE%20A%2F1'
  );
});

test('promotion responses match the Android app snake-case contract', () => {
  const config = _test.promotionConfigResponse({
    enabled: true,
    orders_per_reward: 25,
    cycle_progress: 7,
    lifetime_order_count: 107,
    google_form_url_template: 'https://example.test/{CLAIM_CODE}',
    eligible_item_ids: []
  });
  assert.deepEqual(config, {
    available: true,
    enabled: true,
    orders_per_reward: 25,
    cycle_progress: 7,
    lifetime_order_count: 107,
    google_form_url_template: 'https://example.test/{CLAIM_CODE}',
    eligible_item_ids: [],
    message: null
  });
});

test('promotion migration preserves the established campaign, award, and reservation schema', () => {
  const migration = fs.readFileSync(
    path.join(__dirname, '..', 'migrations', '006_free_drink_promotion.sql'),
    'utf8'
  );
  assert.match(migration, /CREATE TABLE IF NOT EXISTS promotion_campaign/);
  assert.match(migration, /CREATE TABLE IF NOT EXISTS promotion_entry/);
  assert.match(migration, /order_id TEXT PRIMARY KEY/);
  assert.match(migration, /CREATE TABLE IF NOT EXISTS promotion_award/);
  assert.match(migration, /reserved_cart_token TEXT/);
  assert.match(migration, /order_id TEXT NOT NULL UNIQUE/);
});
