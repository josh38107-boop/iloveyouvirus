const test = require('node:test');
const assert = require('node:assert/strict');
const { createEmployeeService, normalizeEmployeeId, validateEmployeeInput, publicEmployee } = require('../employees');

const valid = { name: 'Jordan Barista', pin: '4821', role: 'cashier' };

function mockDb(handler) {
  const queries = [];
  const client = {
    async query(sql, params = []) {
      const normalized = String(sql).replace(/\s+/g, ' ').trim();
      queries.push({ sql: normalized, params });
      if (['BEGIN', 'COMMIT', 'ROLLBACK', 'LOCK TABLE employee IN SHARE ROW EXCLUSIVE MODE'].includes(normalized)) {
        return { rows: [], rowCount: 0 };
      }
      return handler(normalized, params, queries);
    },
    release() {}
  };
  return { db: { pool: { connect: async () => client }, query: client.query.bind(client) }, queries };
}

test('normalizes staff IDs and validates POS-compatible employee input', () => {
  assert.equal(normalizeEmployeeId(' Jordan Barista '), 'jordan-barista');
  assert.deepEqual(validateEmployeeInput(valid), { name: valid.name, role: 'cashier', active: true, pin: '4821' });
  assert.deepEqual(validateEmployeeInput({ name: 'Legacy Manager', role: 'manager', active: true }, { editing: true }),
    { name: 'Legacy Manager', role: 'manager', active: true, pin: null });
  assert.throws(() => validateEmployeeInput({ ...valid, pin: '12' }), /4 to 6/);
  assert.throws(() => validateEmployeeInput({ ...valid, pin: '12ab' }), /4 to 6/);
  assert.throws(() => validateEmployeeInput({ ...valid, role: 'admin' }), /Cashier or Manager/);
  assert.throws(() => validateEmployeeInput({ ...valid, name: '' }), /staff name/);
});

test('public employee responses never include PINs', () => {
  assert.deepEqual(publicEmployee({ id: 'jordan', name: 'Jordan', pin: '4821', role: 'cashier', active: true }),
    { id: 'jordan', name: 'Jordan', role: 'cashier', active: true });
});

test('lists sanitized employees in database order', async () => {
  const { db } = mockDb(sql => {
    if (sql.startsWith('SELECT id,name,role,active FROM employee')) return { rows: [
      { id: 'a', name: 'Avery', role: 'manager', active: true },
      { id: 'r', name: 'Riley', role: 'cashier', active: false }
    ], rowCount: 2 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  assert.deepEqual(await createEmployeeService(db).list(), [
    { id: 'a', name: 'Avery', role: 'manager', active: true },
    { id: 'r', name: 'Riley', role: 'cashier', active: false }
  ]);
});

test('creates staff and records a PIN-bearing synchronization payload atomically', async () => {
  const { db, queries } = mockDb((sql, params) => {
    if (sql.startsWith('SELECT id FROM employee WHERE pin=')) return { rows: [], rowCount: 0 };
    if (sql === 'SELECT id FROM employee WHERE id=$1') return { rows: [], rowCount: 0 };
    if (sql.startsWith('INSERT INTO employee')) return { rows: [{ id: 'jordan-barista-abc12345', ...valid, active: true }], rowCount: 1 };
    if (sql.startsWith('INSERT INTO sync_change')) return { rows: [], rowCount: 1 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  const result = await createEmployeeService(db, { randomId: () => 'abc12345', now: () => 123 }).create(valid);
  assert.deepEqual(result, { id: 'jordan-barista-abc12345', name: valid.name, role: 'cashier', active: true });
  assert.equal(result.pin, undefined);
  const sync = queries.find(query => query.sql.startsWith('INSERT INTO sync_change'));
  assert.equal(sync.params[2].pin, '4821');
  assert.equal(sync.params[3], 123);
  assert.equal(queries.at(-1).sql, 'COMMIT');
});

test('rejects a PIN used by another active staff member and rolls back', async () => {
  const { db, queries } = mockDb(sql => {
    if (sql.startsWith('SELECT id FROM employee WHERE pin=')) return { rows: [{ id: 'existing' }], rowCount: 1 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  await assert.rejects(() => createEmployeeService(db).create(valid), error => error.status === 409 && /PIN already/.test(error.message));
  assert.equal(queries.at(-1).sql, 'ROLLBACK');
});

test('updates role and active status while preserving an omitted legacy PIN', async () => {
  const current = { id: 'legacy', name: 'Legacy', pin: '1', role: 'cashier', active: false };
  const { db, queries } = mockDb((sql, params) => {
    if (sql.startsWith('SELECT * FROM employee WHERE id=')) return { rows: [current], rowCount: 1 };
    if (sql.startsWith('SELECT id FROM employee WHERE pin=')) return { rows: [], rowCount: 0 };
    if (sql.startsWith('UPDATE employee SET')) return { rows: [{ ...current, name: 'Legacy Lead', role: 'manager', active: true }], rowCount: 1 };
    if (sql.startsWith('INSERT INTO sync_change')) return { rows: [], rowCount: 1 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  const result = await createEmployeeService(db).update('legacy', { name: 'Legacy Lead', role: 'manager', active: true });
  assert.deepEqual(result, { id: 'legacy', name: 'Legacy Lead', role: 'manager', active: true });
  const update = queries.find(query => query.sql.startsWith('UPDATE employee SET'));
  assert.equal(update.params[1], '1');
});

test('changes a PIN without exposing it in the response', async () => {
  const current = { id: 'cashier', name: 'Riley', pin: '2222', role: 'cashier', active: true };
  const { db } = mockDb((sql, params) => {
    if (sql.startsWith('SELECT * FROM employee WHERE id=')) return { rows: [current], rowCount: 1 };
    if (sql.startsWith('SELECT id FROM employee WHERE pin=')) return { rows: [], rowCount: 0 };
    if (sql.startsWith('UPDATE employee SET')) return { rows: [{ ...current, pin: params[1] }], rowCount: 1 };
    if (sql.startsWith('INSERT INTO sync_change')) return { rows: [], rowCount: 1 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  const result = await createEmployeeService(db).update('cashier', { name: 'Riley', role: 'cashier', active: true, pin: '9834' });
  assert.equal(result.pin, undefined);
});

test('deactivates staff instead of deleting and records synchronization', async () => {
  const current = { id: 'cashier', name: 'Riley', pin: '2222', role: 'cashier', active: true };
  const { db, queries } = mockDb(sql => {
    if (sql.startsWith('SELECT * FROM employee WHERE id=')) return { rows: [current], rowCount: 1 };
    if (sql.startsWith('UPDATE employee SET active=FALSE')) return { rows: [{ ...current, active: false }], rowCount: 1 };
    if (sql.startsWith('INSERT INTO sync_change')) return { rows: [], rowCount: 1 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  assert.deepEqual(await createEmployeeService(db).deactivate('cashier'),
    { id: 'cashier', name: 'Riley', role: 'cashier', active: false });
  assert.equal(queries.some(query => query.sql.startsWith('DELETE FROM employee')), false);
  assert.equal(queries.filter(query => query.sql.startsWith('INSERT INTO sync_change')).length, 1);
});

test('returns not found for update and deactivation of unknown staff', async () => {
  const updateDb = mockDb(sql => {
    if (sql.startsWith('SELECT * FROM employee WHERE id=')) return { rows: [], rowCount: 0 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  await assert.rejects(() => createEmployeeService(updateDb.db).update('missing', { name: 'Missing', role: 'cashier', active: true }), error => error.status === 404);

  const deactivateDb = mockDb(sql => {
    if (sql.startsWith('SELECT * FROM employee WHERE id=')) return { rows: [], rowCount: 0 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  await assert.rejects(() => createEmployeeService(deactivateDb.db).deactivate('missing'), error => error.status === 404);
});
