require('dotenv').config();
const fs = require('fs');
const path = require('path');
const db = require('./db');

const BRANCH_ID = process.env.DEFAULT_BRANCH_ID || 'main';
const TARGET_CATEGORIES = ['espresso', 'signature', 'cold', 'tea-non-coffee', 'pastry', 'food', 'combos', 'a'];
const EXPECTED = { categories: 8, items: 35, recipes: 4, modifierLinks: 97, categoryTombstones: 8 };

async function recordChange(client, entityType, entityId, payload, timestamp) {
  await client.query(`INSERT INTO sync_change
    (branch_id,entity_type,entity_id,operation,payload,device_id,created_at)
    VALUES($1,'sync_tombstone',$2,'delete',$3,NULL,$4)`,
    [BRANCH_ID, `${entityType}:${entityId}`, payload, timestamp]);
}

async function upsertTombstone(client, entityType, entityId, timestamp) {
  const row = (await client.query(`INSERT INTO sync_tombstone
    (branch_id,entity_type,entity_id,deleted_by_device,deleted_at)
    VALUES($1,$2,$3,'admin-dashboard-cleanup',$4)
    ON CONFLICT(branch_id,entity_type,entity_id) DO UPDATE SET
      deleted_by_device=EXCLUDED.deleted_by_device,
      deleted_at=GREATEST(sync_tombstone.deleted_at,EXCLUDED.deleted_at)
    RETURNING *`, [BRANCH_ID, entityType, entityId, timestamp])).rows[0];
  await recordChange(client, entityType, entityId, row, timestamp);
}

async function cleanup() {
  if (!process.argv.includes('--execute')) {
    throw new Error('Safety stop: rerun with --execute after reviewing the expected counts.');
  }
  const client = await db.pool.connect();
  let backupPath;
  try {
    await client.query('BEGIN');
    const categories = await client.query('SELECT * FROM menu_category WHERE id=ANY($1::text[]) ORDER BY id FOR UPDATE', [TARGET_CATEGORIES]);
    const items = await client.query('SELECT * FROM menu_item WHERE category_id=ANY($1::text[]) ORDER BY id FOR UPDATE', [TARGET_CATEGORIES]);
    const itemIds = items.rows.map(row => row.id);
    const recipes = await client.query('SELECT * FROM recipe_ingredient WHERE item_id=ANY($1::text[]) ORDER BY item_id,ingredient_id', [itemIds]);
    const modifierLinks = await client.query('SELECT * FROM menu_item_modifier_group WHERE item_id=ANY($1::text[]) ORDER BY item_id,group_id', [itemIds]);
    const categoryTombstones = await client.query(`SELECT * FROM sync_tombstone WHERE branch_id=$1
      AND entity_type='menu_category' AND entity_id=ANY($2::text[]) ORDER BY entity_id`, [BRANCH_ID, TARGET_CATEGORIES]);
    const actual = {
      categories: categories.rowCount, items: items.rowCount, recipes: recipes.rowCount,
      modifierLinks: modifierLinks.rowCount, categoryTombstones: categoryTombstones.rowCount
    };
    for (const [key, expected] of Object.entries(EXPECTED)) {
      if (actual[key] !== expected) throw new Error(`Preflight mismatch for ${key}: expected ${expected}, found ${actual[key]}.`);
    }

    const timestamp = Date.now();
    const stamp = new Date(timestamp).toISOString().replace(/[:.]/g, '-');
    const backupDirectory = path.resolve(__dirname, '../backups');
    fs.mkdirSync(backupDirectory, { recursive: true });
    backupPath = path.join(backupDirectory, `render-legacy-menu-${stamp}.json`);
    fs.writeFileSync(backupPath, JSON.stringify({
      exportedAt: new Date(timestamp).toISOString(), branchId: BRANCH_ID,
      targetCategories: TARGET_CATEGORIES, counts: actual,
      categories: categories.rows, items: items.rows, recipes: recipes.rows,
      menuItemModifierGroups: modifierLinks.rows, categoryTombstones: categoryTombstones.rows
    }, null, 2));

    for (const row of modifierLinks.rows) await upsertTombstone(client, 'menu_item_modifier_group', `${row.item_id}|${row.group_id}`, timestamp);
    for (const row of recipes.rows) await upsertTombstone(client, 'recipe_ingredient', `${row.item_id}|${row.ingredient_id}`, timestamp);
    for (const row of items.rows) await upsertTombstone(client, 'menu_item', row.id, timestamp);
    for (const row of categories.rows) await upsertTombstone(client, 'menu_category', row.id, timestamp);

    const deletedModifierLinks = await client.query('DELETE FROM menu_item_modifier_group WHERE item_id=ANY($1::text[])', [itemIds]);
    const deletedRecipes = await client.query('DELETE FROM recipe_ingredient WHERE item_id=ANY($1::text[])', [itemIds]);
    const deletedItems = await client.query('DELETE FROM menu_item WHERE id=ANY($1::text[])', [itemIds]);
    const deletedCategories = await client.query('DELETE FROM menu_category WHERE id=ANY($1::text[])', [TARGET_CATEGORIES]);
    const deleted = {
      categories: deletedCategories.rowCount, items: deletedItems.rowCount,
      recipes: deletedRecipes.rowCount, modifierLinks: deletedModifierLinks.rowCount
    };
    for (const key of ['categories', 'items', 'recipes', 'modifierLinks']) {
      if (deleted[key] !== EXPECTED[key]) throw new Error(`Delete mismatch for ${key}: expected ${EXPECTED[key]}, deleted ${deleted[key]}.`);
    }
    const remaining = await client.query(`SELECT
      (SELECT COUNT(*) FROM menu_category WHERE id=ANY($1::text[])) AS categories,
      (SELECT COUNT(*) FROM menu_item WHERE id=ANY($2::text[])) AS items,
      (SELECT COUNT(*) FROM recipe_ingredient WHERE item_id=ANY($2::text[])) AS recipes,
      (SELECT COUNT(*) FROM menu_item_modifier_group WHERE item_id=ANY($2::text[])) AS modifier_links`,
      [TARGET_CATEGORIES, itemIds]);
    if (Object.values(remaining.rows[0]).some(value => Number(value) !== 0)) throw new Error('Post-delete verification found remaining target rows.');
    const tombstones = await client.query(`SELECT entity_type,COUNT(*) AS count FROM sync_tombstone
      WHERE branch_id=$1 AND ((entity_type='menu_category' AND entity_id=ANY($2::text[]))
        OR (entity_type='menu_item' AND entity_id=ANY($3::text[]))) GROUP BY entity_type`,
      [BRANCH_ID, TARGET_CATEGORIES, itemIds]);
    const tombstoneCounts = Object.fromEntries(tombstones.rows.map(row => [row.entity_type, Number(row.count)]));
    if (tombstoneCounts.menu_category !== EXPECTED.categories || tombstoneCounts.menu_item !== EXPECTED.items) {
      throw new Error(`Tombstone verification failed: ${JSON.stringify(tombstoneCounts)}.`);
    }
    await client.query('COMMIT');
    console.log(JSON.stringify({ success: true, backupPath, deleted, tombstoneCounts }, null, 2));
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {});
    if (backupPath) console.error(`Database rolled back. Backup retained at ${backupPath}`);
    throw error;
  } finally {
    client.release();
  }
}

cleanup().then(() => db.pool.end()).catch(async error => {
  console.error(error);
  await db.pool.end().catch(() => {});
  process.exit(1);
});
