const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {
  createPaymentVoidService,
  validateExpectedUpdatedAt,
  validatePin,
  validateMethodInput,
  publicMethod
} = require('../payment-void');

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

test('payment and void validation accepts supported values', () => {
  assert.equal(validatePin('0427'), '0427');
  assert.equal(validateExpectedUpdatedAt(0), 0);
  assert.deepEqual(validateMethodInput({
    name: '  Maya   QR ',
    paymentCategory: 'online',
    enabled: true
  }), {
    name: 'Maya QR',
    paymentCategory: 'ONLINE',
    enabled: true
  });
});

test('payment and void validation rejects invalid PINs, categories, and reserved names', () => {
  assert.throws(() => validatePin('123'), /exactly 4 digits/);
  assert.throws(() => validatePin('12a4'), /exactly 4 digits/);
  assert.throws(() => validateExpectedUpdatedAt(null), /Reload/);
  assert.throws(() => validateMethodInput({
    name: 'Cash', paymentCategory: 'CASH', enabled: true
  }), /reserved/);
  assert.throws(() => validateMethodInput({
    name: 'Maya', paymentCategory: 'CARD', enabled: true
  }), /Cash or Online/);
});

test('public payment methods use the admin API shape', () => {
  assert.deepEqual(publicMethod({
    id: 'maya', name: 'Maya', enabled: false, is_system: false,
    payment_category: 'ONLINE', created_at: '10', updated_at: '20'
  }), {
    id: 'maya', name: 'Maya', enabled: false, isSystem: false,
    paymentCategory: 'ONLINE', createdAt: 10, updatedAt: 20
  });
});

test('payment and void admin endpoints require the existing admin session', () => {
  const server = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');
  for (const route of [
    /app\.get\('\/admin\/payment-void-settings', adminAuthenticate/,
    /app\.put\('\/admin\/payment-void-settings\/pin', adminAuthenticate/,
    /app\.post\('\/admin\/payment-void-settings\/methods', adminAuthenticate/,
    /app\.put\('\/admin\/payment-void-settings\/methods\/:id', adminAuthenticate/,
    /app\.delete\('\/admin\/payment-void-settings\/methods\/:id', adminAuthenticate/
  ]) assert.match(server, route);
});

test('payment and void migration is additive', () => {
  const migration = fs.readFileSync(path.join(__dirname, '..', 'migrations', '008_payment_void_settings.sql'), 'utf8');
  assert.match(migration, /ADD COLUMN IF NOT EXISTS created_at/);
  assert.match(migration, /payment_void_settings_updated_at/);
  assert.doesNotMatch(migration, /\bDROP\b|\bTRUNCATE\b|\bDELETE\b/i);
});

test('PIN updates reject stale website versions', async () => {
  const fixture = scriptedDb(sql => {
    if (sql === 'BEGIN' || sql === 'ROLLBACK') return { rows: [], rowCount: 0 };
    if (sql.includes('payment_void_settings_updated_at') && sql.includes('FOR UPDATE')) {
      return { rows: [{ payment_void_settings_updated_at: 8 }], rowCount: 1 };
    }
    throw new Error(`Unexpected query: ${sql}`);
  });
  const service = createPaymentVoidService(fixture.db, { now: () => 10 });
  await assert.rejects(
    service.updatePin({ voidRefundPin: '1234', expectedUpdatedAt: 7 }),
    error => error.status === 409 && /another screen/.test(error.message)
  );
  assert.ok(fixture.queries.some(query => query.sql === 'ROLLBACK'));
});

test('system payment methods cannot be edited', async () => {
  const fixture = scriptedDb(sql => {
    if (['BEGIN', 'ROLLBACK'].includes(sql) || sql.startsWith('LOCK TABLE')) return { rows: [], rowCount: 0 };
    if (sql.includes('SELECT * FROM payment_method') && sql.includes('FOR UPDATE')) {
      return { rows: [{ id: 'cash', is_system: true, updated_at: 0 }], rowCount: 1 };
    }
    throw new Error(`Unexpected query: ${sql}`);
  });
  const service = createPaymentVoidService(fixture.db);
  await assert.rejects(
    service.updateMethod('cash', {
      name: 'Register Cash', paymentCategory: 'CASH', enabled: true, expectedUpdatedAt: 0
    }),
    error => error.status === 403 && /System/.test(error.message)
  );
});

test('duplicate custom payment method names are rejected case-insensitively', async () => {
  const fixture = scriptedDb(sql => {
    if (['BEGIN', 'ROLLBACK'].includes(sql) || sql.startsWith('LOCK TABLE')) return { rows: [], rowCount: 0 };
    if (sql.includes('WHERE LOWER(name)=LOWER($1)')) return { rows: [{ '?column?': 1 }], rowCount: 1 };
    throw new Error(`Unexpected query: ${sql}`);
  });
  const service = createPaymentVoidService(fixture.db);
  await assert.rejects(
    service.createMethod({ name: 'maya', paymentCategory: 'ONLINE', enabled: true }),
    error => error.status === 409 && /already exists/.test(error.message)
  );
  assert.ok(fixture.queries.some(query => query.sql === 'ROLLBACK'));
});

test('custom payment method deletion publishes a tombstone and delete change', async () => {
  const method = {
    id: 'maya', name: 'Maya', enabled: true, is_system: false,
    payment_category: 'ONLINE', created_at: 5, updated_at: 10
  };
  const fixture = scriptedDb(sql => {
    if (['BEGIN', 'COMMIT'].includes(sql)) return { rows: [], rowCount: 0 };
    if (sql.includes('SELECT * FROM payment_method') && sql.includes('FOR UPDATE')) {
      return { rows: [method], rowCount: 1 };
    }
    if (sql.includes('INSERT INTO sync_tombstone')) return { rows: [], rowCount: 1 };
    if (sql.includes('DELETE FROM payment_method')) return { rows: [], rowCount: 1 };
    if (sql.includes('INSERT INTO sync_change')) return { rows: [], rowCount: 1 };
    throw new Error(`Unexpected query: ${sql}`);
  });
  const service = createPaymentVoidService(fixture.db, { now: () => 20 });
  assert.deepEqual(await service.deleteMethod('maya', { expectedUpdatedAt: 10 }), {
    id: 'maya', deleted: true
  });
  assert.ok(fixture.queries.some(query => query.sql.includes('INSERT INTO sync_tombstone')));
  const change = fixture.queries.find(query => query.sql.includes('INSERT INTO sync_change'));
  assert.equal(change.params[3], 'delete');
});

test('device synchronization cannot write website-managed payment settings', () => {
  const cloud = fs.readFileSync(path.join(__dirname, '..', 'cloud.js'), 'utf8');
  const server = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');
  assert.match(cloud, /entity === 'payment_method'/);
  assert.match(cloud, /void_refund_pin.*payment_void_settings_updated_at/s);
  assert.match(server, /Manage payment methods in the admin website/);
});
