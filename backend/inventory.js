function httpError(status, message) {
  return Object.assign(new Error(message), { status });
}

function normalizeIngredientId(name) {
  return String(name || '').toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '') || 'ing-';
}

function validateIngredientInput(input, existingId = null) {
  const name = String(input?.name || '').trim();
  const unit = String(input?.unit || '').trim();
  const quantity = Number(input?.quantity_on_hand);
  const threshold = Number(input?.low_stock_threshold);
  if (!name) throw httpError(400, 'Enter an ingredient name.');
  if (name.length > 120) throw httpError(400, 'Ingredient name must be 120 characters or fewer.');
  if (!unit) throw httpError(400, 'Enter a unit (e.g. oz, ea, ml).');
  if (unit.length > 32) throw httpError(400, 'Unit must be 32 characters or fewer.');
  if (!Number.isFinite(quantity) || quantity < 0) throw httpError(400, 'Quantity on hand must be a nonnegative number.');
  if (!Number.isFinite(threshold) || threshold < 0) throw httpError(400, 'Low-stock alert must be a nonnegative number.');
  if (typeof input?.takeout_only !== 'boolean') throw httpError(400, 'Takeout Only must be true or false.');
  const id = existingId || normalizeIngredientId(name);
  if (!id || id === 'ing-') throw httpError(400, 'Ingredient name must contain at least one letter or number.');
  return { id, name, unit, quantity, threshold, takeoutOnly: input.takeout_only };
}

function createInventoryService(db, options = {}) {
  const branchId = options.branchId || 'main';
  const clock = options.now || Date.now;

  async function transaction(work) {
    const client = await db.pool.connect();
    try {
      await client.query('BEGIN');
      const result = await work(client);
      await client.query('COMMIT');
      return result;
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {});
      throw error;
    } finally {
      client.release();
    }
  }

  async function recordChange(client, entityType, entityId, operation, payload) {
    await client.query(`INSERT INTO sync_change
      (branch_id, entity_type, entity_id, operation, payload, device_id, created_at)
      VALUES ($1,$2,$3,$4,$5,NULL,$6)`,
      [branchId, entityType, entityId, operation, payload || null, clock()]);
  }

  async function upsertTombstone(client, entityType, entityId) {
    const timestamp = clock();
    const result = await client.query(`INSERT INTO sync_tombstone
      (branch_id, entity_type, entity_id, deleted_by_device, deleted_at)
      VALUES ($1,$2,$3,'admin-dashboard',$4)
      ON CONFLICT (branch_id, entity_type, entity_id) DO UPDATE SET
        deleted_by_device = EXCLUDED.deleted_by_device,
        deleted_at = GREATEST(sync_tombstone.deleted_at, EXCLUDED.deleted_at)
      RETURNING *`, [branchId, entityType, entityId, timestamp]);
    await recordChange(client, 'sync_tombstone', `${entityType}:${entityId}`, 'delete', result.rows[0]);
  }

  async function list() {
    const result = await db.query(`SELECT ingredient.id, ingredient.name, ingredient.unit,
        COALESCE(balance.quantity, ingredient.quantity_on_hand) AS quantity_on_hand,
        ingredient.low_stock_threshold, ingredient.takeout_only,
        (COALESCE(balance.quantity, ingredient.quantity_on_hand) <= ingredient.low_stock_threshold) AS low_stock
      FROM ingredient
      LEFT JOIN inventory_balance balance
        ON balance.ingredient_id = ingredient.id AND balance.branch_id = $1
      WHERE NOT EXISTS (
        SELECT 1 FROM sync_tombstone tombstone
        WHERE tombstone.branch_id = $1 AND tombstone.entity_type = 'ingredient'
          AND tombstone.entity_id = ingredient.id
      )
      ORDER BY ingredient.name`, [branchId]);
    return result.rows;
  }

  async function create(input) {
    const data = validateIngredientInput(input);
    return transaction(async client => {
      const existing = await client.query('SELECT id FROM ingredient WHERE id=$1 FOR UPDATE', [data.id]);
      if (existing.rowCount) throw httpError(409, `An ingredient with ID '${data.id}' already exists. Edit it instead.`);
      const deleted = await client.query(`SELECT 1 FROM sync_tombstone
        WHERE branch_id=$1 AND entity_type='ingredient' AND entity_id=$2 LIMIT 1`, [branchId, data.id]);
      if (deleted.rowCount) throw httpError(409, 'That ingredient name was previously deleted. Use a different name.');
      let ingredient;
      try {
        ingredient = (await client.query(`INSERT INTO ingredient
          (id, name, unit, quantity_on_hand, low_stock_threshold, takeout_only)
          VALUES ($1,$2,$3,$4,$5,$6) RETURNING *`,
          [data.id, data.name, data.unit, data.quantity, data.threshold, data.takeoutOnly])).rows[0];
      } catch (error) {
        if (error.code === '23505') throw httpError(409, `An ingredient with ID '${data.id}' already exists. Edit it instead.`);
        throw error;
      }
      const balance = (await client.query(`INSERT INTO inventory_balance(branch_id, ingredient_id, quantity)
        VALUES ($1,$2,$3)
        ON CONFLICT (branch_id, ingredient_id) DO UPDATE SET quantity=EXCLUDED.quantity
        RETURNING branch_id, ingredient_id, quantity`, [branchId, data.id, data.quantity])).rows[0];
      await recordChange(client, 'ingredient', data.id, 'upsert', ingredient);
      await recordChange(client, 'inventory_balance', data.id, 'upsert', balance);
      return { ...ingredient, quantity_on_hand: balance.quantity,
        low_stock: Number(balance.quantity) <= Number(ingredient.low_stock_threshold) };
    });
  }

  async function update(id, input) {
    const ingredientId = String(id || '').trim();
    if (!ingredientId) throw httpError(400, 'Ingredient ID is required.');
    const data = validateIngredientInput(input, ingredientId);
    return transaction(async client => {
      const existing = await client.query('SELECT * FROM ingredient WHERE id=$1 FOR UPDATE', [ingredientId]);
      if (!existing.rowCount) throw httpError(404, 'Ingredient not found.');
      const ingredient = (await client.query(`UPDATE ingredient SET
          name=$2, unit=$3, quantity_on_hand=$4, low_stock_threshold=$5, takeout_only=$6
        WHERE id=$1 RETURNING *`,
        [ingredientId, data.name, data.unit, data.quantity, data.threshold, data.takeoutOnly])).rows[0];
      const balance = (await client.query(`INSERT INTO inventory_balance(branch_id, ingredient_id, quantity)
        VALUES ($1,$2,$3)
        ON CONFLICT (branch_id, ingredient_id) DO UPDATE SET quantity=EXCLUDED.quantity
        RETURNING branch_id, ingredient_id, quantity`, [branchId, ingredientId, data.quantity])).rows[0];
      await recordChange(client, 'ingredient', ingredientId, 'upsert', ingredient);
      await recordChange(client, 'inventory_balance', ingredientId, 'upsert', balance);
      return { ...ingredient, quantity_on_hand: balance.quantity,
        low_stock: Number(balance.quantity) <= Number(ingredient.low_stock_threshold) };
    });
  }

  async function remove(id) {
    const ingredientId = String(id || '').trim();
    if (!ingredientId) throw httpError(400, 'Ingredient ID is required.');
    return transaction(async client => {
      const ingredient = await client.query('SELECT * FROM ingredient WHERE id=$1 FOR UPDATE', [ingredientId]);
      if (!ingredient.rowCount) throw httpError(404, 'Ingredient not found.');
      const recipes = await client.query('SELECT * FROM recipe_ingredient WHERE ingredient_id=$1', [ingredientId]);
      const modifierRecipes = await client.query(`SELECT * FROM modifier_recipe_ingredient
        WHERE ingredient_id=$1 OR replaces_ingredient_id=$1`, [ingredientId]);
      for (const row of recipes.rows) await upsertTombstone(client, 'recipe_ingredient', `${row.item_id}|${row.ingredient_id}`);
      for (const row of modifierRecipes.rows) await upsertTombstone(client, 'modifier_recipe_ingredient', `${row.option_id}|${row.ingredient_id}`);
      await upsertTombstone(client, 'ingredient', ingredientId);
      await client.query('DELETE FROM modifier_recipe_ingredient WHERE ingredient_id=$1 OR replaces_ingredient_id=$1', [ingredientId]);
      await client.query('DELETE FROM recipe_ingredient WHERE ingredient_id=$1', [ingredientId]);
      await client.query('DELETE FROM inventory_balance WHERE ingredient_id=$1', [ingredientId]);
      await client.query('DELETE FROM ingredient WHERE id=$1', [ingredientId]);
      return { id: ingredientId, name: ingredient.rows[0].name, deleted: true,
        recipeLinks: recipes.rowCount, modifierRecipeLinks: modifierRecipes.rowCount };
    });
  }

  return { list, create, update, remove };
}

module.exports = { createInventoryService, normalizeIngredientId, validateIngredientInput };
