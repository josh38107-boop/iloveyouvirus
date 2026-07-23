const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { _test, createPromotionService } = require('../rpc');

test('promotion QR template safely encodes the unique claim code', () => {
  assert.equal(
    _test.buildPromotionQrUrl('https://forms.example.test/view?claim={CLAIM_CODE}', 'FREE A/1'),
    'https://forms.example.test/view?claim=FREE%20A%2F1'
  );
  assert.equal(
    _test.buildPromotionQrUrl(
      'https://docs.google.com/forms/d/e/example/viewform?usp=pp_url&entry.123=SAMPLE',
      'FREE A/1'
    ),
    'https://docs.google.com/forms/d/e/example/viewform?usp=pp_url&entry.123=FREE+A%2F1'
  );
});

test('promotion responses match the Android app snake-case contract', () => {
  const config = _test.promotionConfigResponse({
    enabled: true,
    orders_per_reward: 25,
    cycle_progress: 7,
    lifetime_order_count: 107,
    google_form_url_template: 'https://example.test/{CLAIM_CODE}',
    claim_validity_days: 30,
    updated_at: 1234
  });
  assert.deepEqual(config, {
    available: true,
    enabled: true,
    orders_per_reward: 25,
    cycle_progress: 7,
    lifetime_order_count: 107,
    google_form_url_template: 'https://example.test/{CLAIM_CODE}',
    eligible_item_ids: [],
    claim_validity_days: 30,
    updated_at: 1234,
    message: null
  });
});

test('enabled promotions require a Google Forms prefilled entry for the claim code', () => {
  const valid = 'https://docs.google.com/forms/d/e/example/viewform?usp=pp_url&entry.123=SAMPLE';
  const malformed = 'https://docs.google.com/forms/d/e/example/viewform?usp=publish-editor{CLAIM_CODE}';

  assert.equal(_test.validateGoogleFormTemplate(true, valid), null);
  assert.match(_test.validateGoogleFormTemplate(true, malformed), /Prefill only/);
  assert.match(_test.validateGoogleFormTemplate(true, 'http://docs.google.com/forms/x?entry.1=SAMPLE'), /HTTPS/);
  assert.match(
    _test.validateGoogleFormTemplate(
      true,
      'https://docs.google.com/forms/d/e/example/viewform?entry.1=ONE&entry.2=TWO'
    ),
    /Prefill only/
  );
  assert.equal(_test.validateGoogleFormTemplate(false, ''), null);
});

test('stale promotion updates return a conflict', () => {
  assert.doesNotThrow(() => _test.assertExpectedPromotionVersion(200, 200));
  assert.doesNotThrow(() => _test.assertExpectedPromotionVersion(200, null));
  assert.throws(
    () => _test.assertExpectedPromotionVersion(200, 199),
    error => error.status === 409 && /another session/.test(error.message)
  );
});

test('admin updates require a complete versioned payload', async () => {
  const service = createPromotionService({});
  await assert.rejects(
    service.updateConfig({ enabled: 'yes', ordersPerReward: 10, googleFormUrlTemplate: '', expectedUpdatedAt: 1 }),
    error => error.status === 400
  );
  await assert.rejects(
    service.updateConfig({ enabled: false, ordersPerReward: 10, googleFormUrlTemplate: '', expectedUpdatedAt: null }),
    error => error.status === 400 && /Reload/.test(error.message)
  );
});

test('claim pagination validates every supported status and page boundary', () => {
  for (const status of ['all', 'issued', 'reserved', 'claimed', 'expired', 'cancelled']) {
    assert.deepEqual(
      _test.validatePromotionClaimListInput({ status, limit: 50, offset: 0 }),
      { status, limit: 50, offset: 0 }
    );
  }
  assert.throws(() => _test.validatePromotionClaimListInput({ status: 'unknown' }), /Invalid/);
  assert.throws(() => _test.validatePromotionClaimListInput({ limit: 101 }), /page size/);
  assert.throws(() => _test.validatePromotionClaimListInput({ offset: -1 }), /offset/);
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

test('interval changes reset only cycle progress and preserve established claims', () => {
  const source = fs.readFileSync(path.join(__dirname, '..', 'rpc.js'), 'utf8');
  const updateSection = source.slice(
    source.indexOf('async function update_promotion_config'),
    source.indexOf('async function list_promotion_claims')
  );
  assert.match(updateSection, /cycle_progress=CASE WHEN \$4 THEN 0 ELSE cycle_progress END/);
  assert.match(updateSection, /Math\.max\(Date\.now\(\), Number\(existing\.updated_at\) \+ 1\)/);
  assert.doesNotMatch(updateSection, /DELETE FROM promotion_(award|entry)/);
});
