const test = require('node:test');
const assert = require('node:assert/strict');
const { createInventoryService, normalizeIngredientId, validateIngredientInput } = require('../inventory');

const valid = { name: 'Bobba Straw 21cm', unit: 'pcs', quantity_on_hand: 10, low_stock_threshold: 2, takeout_only: true };

function mockDb(handler) {
  const queries = [];
  const client = {
    async query(sql, params = []) {
      const normalized = String(sql).replace(/\s+/g, ' ').trim();
      queries.push({ sql: normalized, params });
      if (normalized === 'BEGIN' || normalized === 'COMMIT' || normalized === 'ROLLBACK') return { rows: [], rowCount: 0 };
      return handler(normalized, params, queries);
    },
    release() {}
  };
  return { db: { pool: { connect: async () => client }, query: client.query.bind(client) }, queries };
}

test('normalizes ingredient IDs exactly like the POS', () => {
  assert.equal(normalizeIngredientId(' Bobba Straw 21cm Black Ind. '), 'bobba-straw-21cm-black-ind');
  assert.equal(normalizeIngredientId('***'), 'ing-');
});

test('validates required, nonnegative, and boolean fields', () => {
  assert.deepEqual(validateIngredientInput(valid), {
    id: 'bobba-straw-21cm', name: valid.name, unit: 'pcs', quantity: 10,
    threshold: 2, takeoutOnly: true
  });
  assert.throws(() => validateIngredientInput({ ...valid, quantity_on_hand: -1 }), /nonnegative/);
  assert.throws(() => validateIngredientInput({ ...valid, takeout_only: 'true' }), /true or false/);
  assert.throws(() => validateIngredientInput({ ...valid, unit: '' }), /Enter a unit/);
});

test('creates an ingredient, branch balance, and synchronization changes atomically', async () => {
  const { db, queries } = mockDb((sql, params) => {
    if (sql.startsWith('SELECT id FROM ingredient')) return { rows: [], rowCount: 0 };
    if (sql.startsWith('SELECT 1 FROM sync_tombstone')) return { rows: [], rowCount: 0 };
    if (sql.startsWith('INSERT INTO ingredient')) return { rows: [{ id: 'bobba-straw-21cm', name: valid.name, unit: 'pcs', low_stock_threshold: 2, takeout_only: true }], rowCount: 1 };
    if (sql.startsWith('INSERT INTO inventory_balance')) return { rows: [{ branch_id: 'main', ingredient_id: 'bobba-straw-21cm', quantity: 10 }], rowCount: 1 };
    if (sql.startsWith('INSERT INTO sync_change')) return { rows: [], rowCount: 1 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  const result = await createInventoryService(db, { now: () => 123 }).create(valid);
  assert.equal(result.quantity_on_hand, 10);
  assert.equal(result.low_stock, false);
  assert.equal(queries.filter(query => query.sql.startsWith('INSERT INTO sync_change')).length, 2);
  assert.equal(queries.at(-1).sql, 'COMMIT');
});

test('rejects duplicate and tombstoned ingredient IDs', async () => {
  const duplicate = mockDb(sql => {
    if (sql.startsWith('SELECT id FROM ingredient')) return { rows: [{ id: 'bobba-straw-21cm' }], rowCount: 1 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  await assert.rejects(() => createInventoryService(duplicate.db).create(valid), error => error.status === 409);
  assert.equal(duplicate.queries.at(-1).sql, 'ROLLBACK');

  const tombstoned = mockDb(sql => {
    if (sql.startsWith('SELECT id FROM ingredient')) return { rows: [], rowCount: 0 };
    if (sql.startsWith('SELECT 1 FROM sync_tombstone')) return { rows: [{}], rowCount: 1 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  await assert.rejects(() => createInventoryService(tombstoned.db).create(valid), error => error.status === 409);
});

test('maps a concurrent duplicate insert to conflict and rolls back', async () => {
  const { db, queries } = mockDb(sql => {
    if (sql.startsWith('SELECT id FROM ingredient') || sql.startsWith('SELECT 1 FROM sync_tombstone')) {
      return { rows: [], rowCount: 0 };
    }
    if (sql.startsWith('INSERT INTO ingredient')) throw Object.assign(new Error('duplicate key'), { code: '23505' });
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  await assert.rejects(() => createInventoryService(db).create(valid), error => error.status === 409);
  assert.equal(queries.at(-1).sql, 'ROLLBACK');
});

test('rolls back when a create step fails', async () => {
  const { db, queries } = mockDb(sql => {
    if (sql.startsWith('SELECT id FROM ingredient') || sql.startsWith('SELECT 1 FROM sync_tombstone')) return { rows: [], rowCount: 0 };
    if (sql.startsWith('INSERT INTO ingredient')) return { rows: [{ id: 'bobba-straw-21cm' }], rowCount: 1 };
    if (sql.startsWith('INSERT INTO inventory_balance')) throw new Error('balance unavailable');
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  await assert.rejects(() => createInventoryService(db).create(valid), /balance unavailable/);
  assert.equal(queries.at(-1).sql, 'ROLLBACK');
});

test('updates metadata, takeout state, balance, and sync changes', async () => {
  const { db, queries } = mockDb(sql => {
    if (sql.startsWith('SELECT * FROM ingredient')) return { rows: [{ id: 'straw' }], rowCount: 1 };
    if (sql.startsWith('UPDATE ingredient')) return { rows: [{ id: 'straw', name: valid.name, low_stock_threshold: 2, takeout_only: true }], rowCount: 1 };
    if (sql.startsWith('INSERT INTO inventory_balance')) return { rows: [{ branch_id: 'main', ingredient_id: 'straw', quantity: 10 }], rowCount: 1 };
    if (sql.startsWith('INSERT INTO sync_change')) return { rows: [], rowCount: 1 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  const result = await createInventoryService(db).update('straw', valid);
  assert.equal(result.takeout_only, true);
  assert.equal(queries.filter(query => query.sql.startsWith('INSERT INTO sync_change')).length, 2);
  assert.equal(queries.at(-1).sql, 'COMMIT');
});

test('delete tombstones ingredient dependencies and preserves transaction integrity', async () => {
  let tombstones = 0;
  const { db, queries } = mockDb(sql => {
    if (sql.startsWith('SELECT * FROM ingredient')) return { rows: [{ id: 'straw', name: 'Straw' }], rowCount: 1 };
    if (sql.startsWith('SELECT * FROM recipe_ingredient')) return { rows: [{ item_id: 'drink', ingredient_id: 'straw' }], rowCount: 1 };
    if (sql.startsWith('SELECT * FROM modifier_recipe_ingredient')) return { rows: [{ option_id: 'large', ingredient_id: 'straw' }], rowCount: 1 };
    if (sql.startsWith('INSERT INTO sync_tombstone')) { tombstones++; return { rows: [{ entity_type: 'ingredient' }], rowCount: 1 }; }
    if (sql.startsWith('INSERT INTO sync_change') || sql.startsWith('DELETE FROM')) return { rows: [], rowCount: 1 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  const result = await createInventoryService(db).remove('straw');
  assert.deepEqual({ deleted: result.deleted, recipeLinks: result.recipeLinks, modifierRecipeLinks: result.modifierRecipeLinks },
    { deleted: true, recipeLinks: 1, modifierRecipeLinks: 1 });
  assert.equal(tombstones, 3);
  assert.equal(queries.at(-1).sql, 'COMMIT');
});

test('inventory listing uses branch balance and excludes tombstones', async () => {
  const { db, queries } = mockDb(sql => {
    if (sql.startsWith('SELECT ingredient.id')) return { rows: [{ id: 'one', quantity_on_hand: 4 }], rowCount: 1 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  const rows = await createInventoryService(db).list();
  assert.equal(rows.length, 1);
  assert.match(queries[0].sql, /COALESCE\(balance.quantity/);
  assert.match(queries[0].sql, /NOT EXISTS/);
});
