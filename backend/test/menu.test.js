const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {
  createMenuService, normalizeMenuId, validateCategoryInput, validateItemInput
} = require('../menu');

const validItem = {
  name: 'Iced Latte', description: '', category_id: 'ice-coffee', base_price_cents: 15000,
  active: true, modifier_group_ids: ['size'],
  recipe: [{ ingredient_id: 'milk', quantity_used: 10 }],
  complementary_exclusion_ids: ['straw']
};

function mockDb(handler) {
  const queries = [];
  const client = {
    async query(sql, params = []) {
      const normalized = String(sql).replace(/\s+/g, ' ').trim();
      queries.push({ sql: normalized, params });
      if (['BEGIN', 'COMMIT', 'ROLLBACK'].includes(normalized)) return { rows: [], rowCount: 0 };
      return handler(normalized, params, queries);
    },
    release() {}
  };
  return { db: { pool: { connect: async () => client }, query: client.query.bind(client) }, queries };
}

test('normalizes category IDs like the POS and validates category names', () => {
  assert.equal(normalizeMenuId(' Tea & Non-Coffee '), 'tea-non-coffee');
  assert.deepEqual(validateCategoryInput({ name: '  Fruit Tea ' }), { id: 'fruit-tea', name: 'Fruit Tea' });
  assert.throws(() => validateCategoryInput({ name: '***' }), /letter or number/);
});

test('validates the complete POS-style item payload', () => {
  const result = validateItemInput(validItem);
  assert.equal(result.description, 'Custom menu item');
  assert.deepEqual(result.modifierGroupIds, ['size']);
  assert.throws(() => validateItemInput({ ...validItem, base_price_cents: 0 }), /greater than/);
  assert.throws(() => validateItemInput({ ...validItem, recipe: [] }), /at least one/);
  assert.throws(() => validateItemInput({ ...validItem, active: 'true' }), /true or false/);
});

test('creates a category with the next sort order and a sync change', async () => {
  const { db, queries } = mockDb(sql => {
    if (sql.startsWith('SELECT id FROM menu_category') || sql.startsWith('SELECT 1 FROM sync_tombstone')) return { rows: [], rowCount: 0 };
    if (sql.startsWith('SELECT COALESCE(MAX(sort_order)')) return { rows: [{ value: 6 }], rowCount: 1 };
    if (sql.startsWith('INSERT INTO menu_category')) return { rows: [{ id: 'desserts', name: 'Desserts', sort_order: 6 }], rowCount: 1 };
    if (sql.startsWith('INSERT INTO sync_change')) return { rows: [], rowCount: 1 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  const result = await createMenuService(db).createCategory({ name: 'Desserts' });
  assert.equal(result.sort_order, 6);
  assert.equal(queries.filter(query => query.sql.startsWith('INSERT INTO sync_change')).length, 1);
  assert.equal(queries.at(-1).sql, 'COMMIT');
});

test('creates an item and all relationships in one transaction', async () => {
  const { db, queries } = mockDb((sql, params) => {
    if (sql.startsWith('SELECT 1 FROM menu_category')) return { rows: [{}], rowCount: 1 };
    if (sql.startsWith('SELECT id FROM modifier_group')) return { rows: [{ id: 'size' }], rowCount: 1 };
    if (sql.startsWith('SELECT id FROM ingredient')) return { rows: [{ id: 'milk' }, { id: 'straw' }], rowCount: 2 };
    if (sql.startsWith('SELECT 1 FROM menu_item')) return { rows: [], rowCount: 0 };
    if (sql.startsWith('INSERT INTO menu_item ')) return { rows: [{ id: 'iced-latte-abc123', name: 'Iced Latte' }], rowCount: 1 };
    if (sql.startsWith('SELECT * FROM menu_item_modifier_group') || sql.startsWith('SELECT * FROM recipe_ingredient')) return { rows: [], rowCount: 0 };
    if (sql.startsWith('DELETE FROM')) return { rows: [], rowCount: 0 };
    if (sql.startsWith('INSERT INTO menu_item_modifier_group')) return { rows: [{ item_id: 'iced-latte-abc123', group_id: 'size' }], rowCount: 1 };
    if (sql.startsWith('INSERT INTO recipe_ingredient')) return { rows: [{ item_id: 'iced-latte-abc123', ingredient_id: 'milk', quantity_used: 10 }], rowCount: 1 };
    if (sql.startsWith('INSERT INTO sync_change')) return { rows: [], rowCount: 1 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  const result = await createMenuService(db, { randomId: () => 'abc123', now: () => 10 }).createItem(validItem);
  assert.equal(result.id, 'iced-latte-abc123');
  assert.equal(result.complementary_exclusion_ids[0], 'straw');
  assert.equal(queries.at(-1).sql, 'COMMIT');
  assert.equal(queries.filter(query => query.sql.startsWith('INSERT INTO sync_change')).length, 3);
});

test('rejects a generated duplicate item ID and rolls back', async () => {
  const { db, queries } = mockDb(sql => {
    if (sql.startsWith('SELECT 1 FROM menu_category')) return { rows: [{}], rowCount: 1 };
    if (sql.startsWith('SELECT id FROM modifier_group')) return { rows: [{ id: 'size' }], rowCount: 1 };
    if (sql.startsWith('SELECT id FROM ingredient')) return { rows: [{ id: 'milk' }, { id: 'straw' }], rowCount: 2 };
    if (sql.startsWith('SELECT 1 FROM menu_item')) return { rows: [{}], rowCount: 1 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  await assert.rejects(() => createMenuService(db, { randomId: () => 'abc123' }).createItem(validItem), error => error.status === 409);
  assert.equal(queries.at(-1).sql, 'ROLLBACK');
});

test('editing tombstones removed relationships and saves replacements', async () => {
  let tombstones = 0;
  const replacement = { ...validItem, modifier_group_ids: [], recipe: [{ ingredient_id: 'beans', quantity_used: 0.65 }], complementary_exclusion_ids: [] };
  const { db, queries } = mockDb(sql => {
    if (sql.startsWith('SELECT 1 FROM menu_category')) return { rows: [{}], rowCount: 1 };
    if (sql.startsWith('SELECT id FROM ingredient')) return { rows: [{ id: 'beans' }], rowCount: 1 };
    if (sql.startsWith('SELECT 1 FROM menu_item')) return { rows: [{}], rowCount: 1 };
    if (sql.startsWith('UPDATE menu_item')) return { rows: [{ id: 'latte', active: true }], rowCount: 1 };
    if (sql.startsWith('SELECT * FROM menu_item_modifier_group')) return { rows: [{ item_id: 'latte', group_id: 'size' }], rowCount: 1 };
    if (sql.startsWith('SELECT * FROM recipe_ingredient')) return { rows: [{ item_id: 'latte', ingredient_id: 'milk' }], rowCount: 1 };
    if (sql.startsWith('INSERT INTO sync_tombstone')) { tombstones++; return { rows: [{}], rowCount: 1 }; }
    if (sql.startsWith('DELETE FROM') || sql.startsWith('INSERT INTO sync_change')) return { rows: [], rowCount: 1 };
    if (sql.startsWith('INSERT INTO recipe_ingredient')) return { rows: [{ item_id: 'latte', ingredient_id: 'beans' }], rowCount: 1 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  await createMenuService(db).updateItem('latte', replacement);
  assert.equal(tombstones, 2);
  assert.equal(queries.at(-1).sql, 'COMMIT');
});

test('blocks deletion of a category that contains items and rolls back', async () => {
  const { db, queries } = mockDb(sql => {
    if (sql.startsWith('SELECT * FROM menu_category')) return { rows: [{ id: 'coffee' }], rowCount: 1 };
    if (sql.startsWith('SELECT COUNT(*)')) return { rows: [{ count: '2' }], rowCount: 1 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  await assert.rejects(() => createMenuService(db).deleteCategory('coffee'), error => error.status === 409);
  assert.equal(queries.at(-1).sql, 'ROLLBACK');
});

test('deletes an item after tombstoning its dependencies', async () => {
  let tombstones = 0;
  const { db, queries } = mockDb(sql => {
    if (sql.startsWith('SELECT * FROM menu_item WHERE')) return { rows: [{ id: 'latte', name: 'Latte' }], rowCount: 1 };
    if (sql.startsWith('SELECT * FROM menu_item_modifier_group')) return { rows: [{ group_id: 'size' }], rowCount: 1 };
    if (sql.startsWith('SELECT * FROM recipe_ingredient')) return { rows: [{ ingredient_id: 'milk' }], rowCount: 1 };
    if (sql.startsWith('INSERT INTO sync_tombstone')) { tombstones++; return { rows: [{}], rowCount: 1 }; }
    if (sql.startsWith('INSERT INTO sync_change') || sql.startsWith('DELETE FROM')) return { rows: [], rowCount: 1 };
    throw new Error(`Unexpected SQL: ${sql}`);
  });
  const result = await createMenuService(db).deleteItem('latte');
  assert.equal(result.deleted, true);
  assert.equal(tombstones, 3);
  assert.equal(queries.some(query => /order_line|pos_order|receipt/.test(query.sql)), false);
  assert.equal(queries.at(-1).sql, 'COMMIT');
});

test('legacy cleanup is guarded by exact counts and an execute flag', () => {
  const source = fs.readFileSync(path.resolve(__dirname, '../cleanup-legacy-menu.js'), 'utf8');
  assert.match(source, /EXPECTED = \{ categories: 8, items: 35, recipes: 4, modifierLinks: 97, categoryTombstones: 8 \}/);
  assert.match(source, /process\.argv\.includes\('--execute'\)/);
  assert.match(source, /ROLLBACK/);
  assert.match(source, /render-legacy-menu-/);
});
