const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {
  createDiscountService,
  validatePercent,
  validateExpectedUpdatedAt,
  validateRuleInput,
  publicRule
} = require('../discounts');

function scriptedDb(handler) {
  const queries = [];
  const client = {
    async query(sql, params = []) {
      queries.push({ sql, params });
      return handler(sql, params);
    },
    release() {}
  };
  return {
    queries,
    db: { pool: { async connect() { return client; } }, query: client.query.bind(client) }
  };
}

test('discount validation accepts supported benefit and custom values', () => {
  assert.equal(validatePercent('20'), 20);
  assert.equal(validateExpectedUpdatedAt(0), 0);
  assert.deepEqual(validateRuleInput({
    name: ' Student  Discount ',
    percent: 10,
    scope: 'order',
    requiresReference: true,
    active: true,
    sortOrder: 2
  }), {
    name: 'Student Discount',
    percent: 10,
    scope: 'order',
    requiresReference: true,
    active: true,
    sortOrder: 2
  });
  assert.equal(validateRuleInput({
    name: 'Group Discount',
    percent: 15,
    scope: 'multi',
    requiresReference: false,
    active: true
  }).scope, 'multi');
});

test('discount validation rejects invalid, reserved, and stale inputs', () => {
  assert.throws(() => validatePercent(0), /greater than 0/);
  assert.throws(() => validatePercent(101), /no more than 100/);
  assert.throws(() => validateExpectedUpdatedAt(null), /Reload/);
  assert.throws(() => validateRuleInput({
    name: 'PWD', percent: 10, scope: 'item', requiresReference: false, active: true
  }), /reserved/);
  assert.throws(() => validateRuleInput({
    name: 'Student', percent: 10, scope: 'basket', requiresReference: false, active: true
  }), /item, order, or multi/);
});

test('public discount rules use the admin API shape', () => {
  assert.deepEqual(publicRule({
    id: 'student', name: 'Student', percent: '10', scope: 'item',
    requires_reference: true, active: false, sort_order: '3',
    created_at: '100', updated_at: '200'
  }), {
    id: 'student', name: 'Student', percent: 10, scope: 'item',
    requiresReference: true, active: false, sortOrder: 3,
    createdAt: 100, updatedAt: 200
  });
});

test('discount admin endpoints require the existing admin session', () => {
  const server = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');
  assert.match(server, /app\.get\('\/admin\/discount-settings', adminAuthenticate/);
  assert.match(server, /app\.put\('\/admin\/discount-settings', adminAuthenticate/);
  assert.match(server, /app\.post\('\/admin\/discount-settings\/custom', adminAuthenticate/);
  assert.match(server, /app\.put\('\/admin\/discount-settings\/custom\/:id', adminAuthenticate/);
});

test('discount migration is additive and preserves historical records', () => {
  const migration = fs.readFileSync(path.join(__dirname, '..', 'migrations', '007_discount_settings.sql'), 'utf8');
  assert.match(migration, /CREATE TABLE IF NOT EXISTS discount_rule/);
  assert.match(migration, /ADD COLUMN IF NOT EXISTS discount_reference/);
  assert.doesNotMatch(migration, /\bDROP\b|\bTRUNCATE\b|\bDELETE\b/i);
});

test('multi discount scope migration expands the existing scope check', () => {
  const migration = fs.readFileSync(path.join(__dirname, '..', 'migrations', '013_multi_discount_scope.sql'), 'utf8');
  assert.match(migration, /DROP CONSTRAINT IF EXISTS discount_rule_scope_check/);
  assert.match(migration, /'multi'/);
  assert.doesNotMatch(migration, /\bTRUNCATE\b|\bDELETE\b/i);
});

test('benefit updates reject a stale website version and roll back', async () => {
  const fixture = scriptedDb(sql => {
    if (sql === 'BEGIN' || sql === 'ROLLBACK') return { rows: [], rowCount: 0 };
    if (sql.includes('discount_settings_updated_at') && sql.includes('FOR UPDATE')) {
      return { rows: [{ discount_settings_updated_at: 20 }], rowCount: 1 };
    }
    throw new Error(`Unexpected query: ${sql}`);
  });
  const service = createDiscountService(fixture.db, { now: () => 30 });

  await assert.rejects(
    service.updateBenefits({ seniorPercent: 20, pwdPercent: 20, expectedUpdatedAt: 19 }),
    error => error.status === 409 && /another screen/.test(error.message)
  );
  assert.ok(fixture.queries.some(query => query.sql === 'ROLLBACK'));
  assert.ok(!fixture.queries.some(query => query.sql.includes('UPDATE store_settings')));
});

test('custom discounts can be softly disabled and publish a sync change', async () => {
  const existing = {
    id: 'student', name: 'Student', percent: 10, scope: 'item',
    requires_reference: true, active: true, sort_order: 0,
    created_at: 5, updated_at: 10
  };
  const fixture = scriptedDb((sql, params) => {
    if (['BEGIN', 'COMMIT'].includes(sql)) return { rows: [], rowCount: 0 };
    if (sql.includes('SELECT * FROM discount_rule') && sql.includes('FOR UPDATE')) {
      return { rows: [existing], rowCount: 1 };
    }
    if (sql.includes('UPDATE discount_rule SET')) {
      return { rows: [{ ...existing, active: false, updated_at: params[7] }], rowCount: 1 };
    }
    if (sql.includes('INSERT INTO sync_change')) return { rows: [], rowCount: 1 };
    throw new Error(`Unexpected query: ${sql}`);
  });
  const service = createDiscountService(fixture.db, { now: () => 10 });
  const result = await service.updateRule('student', {
    name: 'Student', percent: 10, scope: 'item',
    requiresReference: true, active: false, sortOrder: 0, expectedUpdatedAt: 10
  });

  assert.equal(result.active, false);
  assert.equal(result.updatedAt, 11);
  assert.ok(fixture.queries.some(query => query.sql.includes('INSERT INTO sync_change')));
});
