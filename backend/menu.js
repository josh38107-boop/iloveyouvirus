const crypto = require('crypto');

function httpError(status, message) {
  return Object.assign(new Error(message), { status });
}

function normalizeMenuId(value) {
  return String(value || '').toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

function uniqueStrings(value, field) {
  if (!Array.isArray(value)) throw httpError(400, `${field} must be an array.`);
  const result = [...new Set(value.map(item => String(item || '').trim()).filter(Boolean))];
  if (result.length !== value.length) throw httpError(400, `${field} contains empty or duplicate values.`);
  return result;
}

function validateCategoryInput(input) {
  const name = String(input?.name || '').trim();
  if (!name) throw httpError(400, 'Enter a category name.');
  if (name.length > 80) throw httpError(400, 'Category name must be 80 characters or fewer.');
  const id = normalizeMenuId(name);
  if (!id) throw httpError(400, 'Category name must contain at least one letter or number.');
  return { id, name };
}

function validateItemInput(input) {
  const name = String(input?.name || '').trim();
  const description = String(input?.description || '').trim();
  const categoryId = String(input?.category_id || '').trim();
  const price = Number(input?.base_price_cents);
  if (!name) throw httpError(400, 'Enter an item name.');
  if (name.length > 120) throw httpError(400, 'Item name must be 120 characters or fewer.');
  if (description.length > 500) throw httpError(400, 'Description must be 500 characters or fewer.');
  if (!categoryId) throw httpError(400, 'Choose a category.');
  if (!Number.isSafeInteger(price) || price <= 0) throw httpError(400, 'Enter a price greater than ₱0.');
  if (typeof input?.active !== 'boolean') throw httpError(400, 'Active must be true or false.');
  const modifierGroupIds = uniqueStrings(input?.modifier_group_ids, 'Modifier groups');
  const exclusionIds = uniqueStrings(input?.complementary_exclusion_ids, 'Complementary exclusions');
  if (!Array.isArray(input?.recipe)) throw httpError(400, 'Recipe must be an array.');
  const recipe = input.recipe.map(row => ({
    ingredient_id: String(row?.ingredient_id || '').trim(),
    quantity_used: Number(row?.quantity_used)
  }));
  if (!recipe.length) throw httpError(400, 'Add at least one ingredient quantity for inventory deduction.');
  if (recipe.some(row => !row.ingredient_id || !Number.isFinite(row.quantity_used) || row.quantity_used <= 0)) {
    throw httpError(400, 'Recipe quantities must be positive numbers.');
  }
  if (new Set(recipe.map(row => row.ingredient_id)).size !== recipe.length) {
    throw httpError(400, 'Recipe contains duplicate ingredients.');
  }
  return {
    name, description: description || 'Custom menu item', categoryId, price, active: input.active,
    modifierGroupIds, exclusionIds, recipe
  };
}

function validateModifierGroupInput(input) {
  const name = String(input?.name || '').trim();
  const required = input?.required;
  const maxSelections = Number(input?.max_selections);
  if (!name) throw httpError(400, 'Enter a modifier group name.');
  if (name.length > 80) throw httpError(400, 'Modifier group name must be 80 characters or fewer.');
  if (typeof required !== 'boolean') throw httpError(400, 'Required must be true or false.');
  if (!Number.isSafeInteger(maxSelections) || maxSelections < 1 || maxSelections > 20) {
    throw httpError(400, 'Maximum selections must be a whole number from 1 to 20.');
  }
  const id = normalizeMenuId(name);
  if (!id) throw httpError(400, 'Modifier group name must contain at least one letter or number.');
  return { id, name, required, maxSelections };
}

function validateModifierOptionInput(input) {
  const name = String(input?.name || '').trim();
  const price = Number(input?.price_delta_cents);
  const ingredientId = String(input?.ingredient_id || '').trim();
  const replacementId = String(input?.replaces_ingredient_id || '').trim();
  const quantity = ingredientId ? Number(input?.quantity_used) : 0;
  if (!name) throw httpError(400, 'Enter an option name.');
  if (name.length > 80) throw httpError(400, 'Option name must be 80 characters or fewer.');
  if (!Number.isSafeInteger(price) || price < 0) throw httpError(400, 'Price add-on must be zero or greater.');
  if (!ingredientId && (replacementId || Number(input?.quantity_used) > 0)) {
    throw httpError(400, 'Choose an ingredient before entering an inventory deduction.');
  }
  if (ingredientId && (!Number.isFinite(quantity) || quantity <= 0)) {
    throw httpError(400, 'Inventory deduction must be greater than zero.');
  }
  return {
    name, price, ingredientId: ingredientId || null, quantity,
    replacementId: replacementId || null
  };
}

function createMenuService(db, options = {}) {
  const branchId = options.branchId || 'main';
  const clock = options.now || Date.now;
  const randomId = options.randomId || (() => crypto.randomUUID().slice(0, 6));

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
    const result = await client.query(`INSERT INTO sync_tombstone
      (branch_id, entity_type, entity_id, deleted_by_device, deleted_at)
      VALUES ($1,$2,$3,'admin-dashboard',$4)
      ON CONFLICT (branch_id, entity_type, entity_id) DO UPDATE SET
        deleted_by_device=EXCLUDED.deleted_by_device,
        deleted_at=GREATEST(sync_tombstone.deleted_at, EXCLUDED.deleted_at)
      RETURNING *`, [branchId, entityType, entityId, clock()]);
    await recordChange(client, 'sync_tombstone', `${entityType}:${entityId}`, 'delete', result.rows[0]);
  }

  async function list() {
    const hidden = `NOT EXISTS (SELECT 1 FROM sync_tombstone tombstone
      WHERE tombstone.branch_id=$1 AND tombstone.entity_type=$2 AND tombstone.entity_id=`;
    const [categories, items, groups, options, assignments, ingredients, recipes, modifierRecipes] = await Promise.all([
      db.query(`SELECT * FROM menu_category category WHERE ${hidden}category.id) ORDER BY sort_order,name`, [branchId, 'menu_category']),
      db.query(`SELECT item.* FROM menu_item item JOIN menu_category category ON category.id=item.category_id
        WHERE ${hidden}item.id) AND NOT EXISTS (SELECT 1 FROM sync_tombstone t
          WHERE t.branch_id=$1 AND t.entity_type='menu_category' AND t.entity_id=category.id)
        ORDER BY item.name`, [branchId, 'menu_item']),
      db.query(`SELECT * FROM modifier_group group_row WHERE ${hidden}group_row.id) ORDER BY name`, [branchId, 'modifier_group']),
      db.query(`SELECT option.* FROM modifier_option option JOIN modifier_group group_row ON group_row.id=option.group_id
        WHERE ${hidden}option.id) AND NOT EXISTS (SELECT 1 FROM sync_tombstone tombstone
          WHERE tombstone.branch_id=$1 AND tombstone.entity_type='modifier_group' AND tombstone.entity_id=group_row.id)
        ORDER BY option.group_id,option.name`, [branchId, 'modifier_option']),
      db.query(`SELECT link.* FROM menu_item_modifier_group link JOIN menu_item item ON item.id=link.item_id
        WHERE ${hidden}(link.item_id || '|' || link.group_id))`, [branchId, 'menu_item_modifier_group']),
      db.query(`SELECT ingredient.id,ingredient.name,ingredient.unit FROM ingredient
        WHERE ${hidden}ingredient.id) ORDER BY ingredient.name`, [branchId, 'ingredient']),
      db.query(`SELECT recipe.* FROM recipe_ingredient recipe JOIN menu_item item ON item.id=recipe.item_id
        WHERE ${hidden}(recipe.item_id || '|' || recipe.ingredient_id))`, [branchId, 'recipe_ingredient']),
      db.query(`SELECT recipe.* FROM modifier_recipe_ingredient recipe JOIN modifier_option option ON option.id=recipe.option_id
        WHERE ${hidden}(recipe.option_id || '|' || recipe.ingredient_id))`, [branchId, 'modifier_recipe_ingredient'])
    ]);
    return {
      categories: categories.rows, items: items.rows, modifierGroups: groups.rows,
      modifierOptions: options.rows, itemModifierGroups: assignments.rows,
      ingredients: ingredients.rows, recipes: recipes.rows, modifierRecipes: modifierRecipes.rows
    };
  }

  async function validateModifierRecipeReferences(client, data) {
    const ingredientIds = [...new Set([data.ingredientId, data.replacementId].filter(Boolean))];
    if (!ingredientIds.length) return;
    const result = await client.query('SELECT id FROM ingredient WHERE id=ANY($1::text[])', [ingredientIds]);
    if (result.rowCount !== ingredientIds.length) throw httpError(400, 'Choose an available inventory ingredient.');
  }

  async function replaceModifierRecipe(client, optionId, data) {
    const oldRecipes = await client.query('SELECT * FROM modifier_recipe_ingredient WHERE option_id=$1', [optionId]);
    for (const row of oldRecipes.rows) {
      if (row.ingredient_id !== data.ingredientId) {
        await upsertTombstone(client, 'modifier_recipe_ingredient', `${optionId}|${row.ingredient_id}`);
      }
    }
    await client.query('DELETE FROM modifier_recipe_ingredient WHERE option_id=$1', [optionId]);
    if (!data.ingredientId) return null;
    const recipe = (await client.query(`INSERT INTO modifier_recipe_ingredient
      (option_id,ingredient_id,quantity_used,replaces_ingredient_id) VALUES($1,$2,$3,$4) RETURNING *`,
      [optionId, data.ingredientId, data.quantity, data.replacementId])).rows[0];
    await recordChange(client, 'modifier_recipe_ingredient', `${optionId}|${data.ingredientId}`, 'upsert', recipe);
    return recipe;
  }

  async function createModifierGroup(input) {
    const data = validateModifierGroupInput(input);
    return transaction(async client => {
      const existing = await client.query('SELECT id FROM modifier_group WHERE id=$1 FOR UPDATE', [data.id]);
      if (existing.rowCount) throw httpError(409, 'A modifier group with that name already exists.');
      const deleted = await client.query(`SELECT 1 FROM sync_tombstone WHERE branch_id=$1
        AND entity_type='modifier_group' AND entity_id=$2 LIMIT 1`, [branchId, data.id]);
      if (deleted.rowCount) throw httpError(409, 'That modifier group name was previously deleted. Use a different name.');
      const group = (await client.query(`INSERT INTO modifier_group(id,name,required,max_selections)
        VALUES($1,$2,$3,$4) RETURNING *`, [data.id, data.name, data.required, data.maxSelections])).rows[0];
      await recordChange(client, 'modifier_group', group.id, 'upsert', group);
      return group;
    });
  }

  async function updateModifierGroup(id, input) {
    const groupId = String(id || '').trim();
    const data = validateModifierGroupInput(input);
    return transaction(async client => {
      const existing = await client.query('SELECT 1 FROM modifier_group WHERE id=$1 FOR UPDATE', [groupId]);
      if (!existing.rowCount) throw httpError(404, 'Modifier group not found. Refresh the page and try again.');
      const group = (await client.query(`UPDATE modifier_group SET name=$2,required=$3,max_selections=$4
        WHERE id=$1 RETURNING *`, [groupId, data.name, data.required, data.maxSelections])).rows[0];
      await recordChange(client, 'modifier_group', groupId, 'upsert', group);
      return group;
    });
  }

  async function deleteModifierGroup(id) {
    const groupId = String(id || '').trim();
    return transaction(async client => {
      const group = await client.query('SELECT * FROM modifier_group WHERE id=$1 FOR UPDATE', [groupId]);
      if (!group.rowCount) throw httpError(404, 'Modifier group not found. Refresh the page and try again.');
      const assignments = await client.query('SELECT * FROM menu_item_modifier_group WHERE group_id=$1', [groupId]);
      const options = await client.query('SELECT * FROM modifier_option WHERE group_id=$1', [groupId]);
      const recipes = await client.query(`SELECT recipe.* FROM modifier_recipe_ingredient recipe
        JOIN modifier_option option ON option.id=recipe.option_id WHERE option.group_id=$1`, [groupId]);
      for (const row of assignments.rows) await upsertTombstone(client, 'menu_item_modifier_group', `${row.item_id}|${groupId}`);
      for (const row of recipes.rows) await upsertTombstone(client, 'modifier_recipe_ingredient', `${row.option_id}|${row.ingredient_id}`);
      for (const row of options.rows) await upsertTombstone(client, 'modifier_option', row.id);
      await upsertTombstone(client, 'modifier_group', groupId);
      await client.query('DELETE FROM menu_item_modifier_group WHERE group_id=$1', [groupId]);
      await client.query('DELETE FROM modifier_recipe_ingredient WHERE option_id IN (SELECT id FROM modifier_option WHERE group_id=$1)', [groupId]);
      await client.query('DELETE FROM modifier_option WHERE group_id=$1', [groupId]);
      await client.query('DELETE FROM modifier_group WHERE id=$1', [groupId]);
      return { id: groupId, name: group.rows[0].name, deleted: true,
        itemLinks: assignments.rowCount, options: options.rowCount, recipeLinks: recipes.rowCount };
    });
  }

  async function createModifierOption(groupId, input) {
    const modifierGroupId = String(groupId || '').trim();
    const data = validateModifierOptionInput(input);
    return transaction(async client => {
      const group = await client.query('SELECT 1 FROM modifier_group WHERE id=$1 FOR UPDATE', [modifierGroupId]);
      if (!group.rowCount) throw httpError(404, 'Modifier group not found. Refresh the page and try again.');
      await validateModifierRecipeReferences(client, data);
      const stem = `${normalizeMenuId(modifierGroupId)}-${normalizeMenuId(data.name)}` || 'modifier-option';
      const optionId = `${stem}-${randomId()}`;
      const existing = await client.query('SELECT 1 FROM modifier_option WHERE id=$1', [optionId]);
      if (existing.rowCount) throw httpError(409, 'Could not generate a unique option ID. Try saving again.');
      const option = (await client.query(`INSERT INTO modifier_option(id,group_id,name,price_delta_cents)
        VALUES($1,$2,$3,$4) RETURNING *`, [optionId, modifierGroupId, data.name, data.price])).rows[0];
      await recordChange(client, 'modifier_option', optionId, 'upsert', option);
      const inventoryRecipe = await replaceModifierRecipe(client, optionId, data);
      return { ...option, inventory_recipe: inventoryRecipe };
    });
  }

  async function updateModifierOption(id, input) {
    const optionId = String(id || '').trim();
    const data = validateModifierOptionInput(input);
    return transaction(async client => {
      const existing = await client.query('SELECT * FROM modifier_option WHERE id=$1 FOR UPDATE', [optionId]);
      if (!existing.rowCount) throw httpError(404, 'Modifier option not found. Refresh the page and try again.');
      await validateModifierRecipeReferences(client, data);
      const option = (await client.query(`UPDATE modifier_option SET name=$2,price_delta_cents=$3
        WHERE id=$1 RETURNING *`, [optionId, data.name, data.price])).rows[0];
      await recordChange(client, 'modifier_option', optionId, 'upsert', option);
      const inventoryRecipe = await replaceModifierRecipe(client, optionId, data);
      return { ...option, inventory_recipe: inventoryRecipe };
    });
  }

  async function deleteModifierOption(id) {
    const optionId = String(id || '').trim();
    return transaction(async client => {
      const option = await client.query('SELECT * FROM modifier_option WHERE id=$1 FOR UPDATE', [optionId]);
      if (!option.rowCount) throw httpError(404, 'Modifier option not found. Refresh the page and try again.');
      const recipes = await client.query('SELECT * FROM modifier_recipe_ingredient WHERE option_id=$1', [optionId]);
      for (const row of recipes.rows) await upsertTombstone(client, 'modifier_recipe_ingredient', `${optionId}|${row.ingredient_id}`);
      await upsertTombstone(client, 'modifier_option', optionId);
      await client.query('DELETE FROM modifier_recipe_ingredient WHERE option_id=$1', [optionId]);
      await client.query('DELETE FROM modifier_option WHERE id=$1', [optionId]);
      return { id: optionId, name: option.rows[0].name, deleted: true, recipeLinks: recipes.rowCount };
    });
  }

  async function createCategory(input) {
    const data = validateCategoryInput(input);
    return transaction(async client => {
      const existing = await client.query('SELECT id FROM menu_category WHERE id=$1 FOR UPDATE', [data.id]);
      if (existing.rowCount) throw httpError(409, `A category with ID '${data.id}' already exists. Edit it instead.`);
      const deleted = await client.query(`SELECT 1 FROM sync_tombstone WHERE branch_id=$1
        AND entity_type='menu_category' AND entity_id=$2 LIMIT 1`, [branchId, data.id]);
      if (deleted.rowCount) throw httpError(409, 'That category name was previously deleted. Use a different name.');
      const nextOrder = Number((await client.query('SELECT COALESCE(MAX(sort_order),0)+1 AS value FROM menu_category')).rows[0].value);
      const category = (await client.query(`INSERT INTO menu_category(id,name,sort_order)
        VALUES($1,$2,$3) RETURNING *`, [data.id, data.name, nextOrder])).rows[0];
      await recordChange(client, 'menu_category', category.id, 'upsert', category);
      return category;
    });
  }

  async function updateCategory(id, input) {
    const categoryId = String(id || '').trim();
    const data = validateCategoryInput(input);
    return transaction(async client => {
      const existing = await client.query('SELECT * FROM menu_category WHERE id=$1 FOR UPDATE', [categoryId]);
      if (!existing.rowCount) throw httpError(404, 'Category not found. Refresh the page and try again.');
      const category = (await client.query('UPDATE menu_category SET name=$2 WHERE id=$1 RETURNING *', [categoryId, data.name])).rows[0];
      await recordChange(client, 'menu_category', categoryId, 'upsert', category);
      return category;
    });
  }

  async function deleteCategory(id) {
    const categoryId = String(id || '').trim();
    return transaction(async client => {
      const category = await client.query('SELECT * FROM menu_category WHERE id=$1 FOR UPDATE', [categoryId]);
      if (!category.rowCount) throw httpError(404, 'Category not found. Refresh the page and try again.');
      const itemCount = Number((await client.query('SELECT COUNT(*) AS count FROM menu_item WHERE category_id=$1', [categoryId])).rows[0].count);
      if (itemCount) throw httpError(409, `Move or delete the ${itemCount} item${itemCount === 1 ? '' : 's'} in this category first.`);
      await upsertTombstone(client, 'menu_category', categoryId);
      await client.query('DELETE FROM menu_category WHERE id=$1', [categoryId]);
      return { id: categoryId, name: category.rows[0].name, deleted: true };
    });
  }

  async function validateReferences(client, data) {
    const category = await client.query(`SELECT 1 FROM menu_category WHERE id=$1 AND NOT EXISTS
      (SELECT 1 FROM sync_tombstone WHERE branch_id=$2 AND entity_type='menu_category' AND entity_id=$1)`,
      [data.categoryId, branchId]);
    if (!category.rowCount) throw httpError(400, 'Choose an available category.');
    if (data.modifierGroupIds.length) {
      const result = await client.query('SELECT id FROM modifier_group WHERE id=ANY($1::text[])', [data.modifierGroupIds]);
      if (result.rowCount !== data.modifierGroupIds.length) throw httpError(400, 'One or more modifier groups no longer exist.');
    }
    const ingredientIds = [...new Set([...data.recipe.map(row => row.ingredient_id), ...data.exclusionIds])];
    if (ingredientIds.length) {
      const result = await client.query('SELECT id FROM ingredient WHERE id=ANY($1::text[])', [ingredientIds]);
      if (result.rowCount !== ingredientIds.length) throw httpError(400, 'One or more selected ingredients no longer exist.');
    }
  }

  async function replaceRelationships(client, itemId, data) {
    const oldGroups = await client.query('SELECT * FROM menu_item_modifier_group WHERE item_id=$1', [itemId]);
    const oldRecipes = await client.query('SELECT * FROM recipe_ingredient WHERE item_id=$1', [itemId]);
    const nextGroups = new Set(data.modifierGroupIds);
    const nextRecipes = new Set(data.recipe.map(row => row.ingredient_id));
    for (const row of oldGroups.rows) {
      if (!nextGroups.has(row.group_id)) await upsertTombstone(client, 'menu_item_modifier_group', `${itemId}|${row.group_id}`);
    }
    for (const row of oldRecipes.rows) {
      if (!nextRecipes.has(row.ingredient_id)) await upsertTombstone(client, 'recipe_ingredient', `${itemId}|${row.ingredient_id}`);
    }
    await client.query('DELETE FROM menu_item_modifier_group WHERE item_id=$1', [itemId]);
    await client.query('DELETE FROM recipe_ingredient WHERE item_id=$1', [itemId]);
    for (const groupId of data.modifierGroupIds) {
      const row = (await client.query(`INSERT INTO menu_item_modifier_group(item_id,group_id)
        VALUES($1,$2) RETURNING *`, [itemId, groupId])).rows[0];
      await recordChange(client, 'menu_item_modifier_group', `${itemId}|${groupId}`, 'upsert', row);
    }
    for (const recipe of data.recipe) {
      const row = (await client.query(`INSERT INTO recipe_ingredient(item_id,ingredient_id,quantity_used)
        VALUES($1,$2,$3) RETURNING *`, [itemId, recipe.ingredient_id, recipe.quantity_used])).rows[0];
      await recordChange(client, 'recipe_ingredient', `${itemId}|${recipe.ingredient_id}`, 'upsert', row);
    }
  }

  async function saveItem(id, input, creating) {
    const data = validateItemInput(input);
    return transaction(async client => {
      await validateReferences(client, data);
      let itemId = String(id || '').trim();
      if (creating) {
        const stem = normalizeMenuId(data.name) || 'menu-item';
        itemId = `${stem}-${randomId()}`;
        const duplicate = await client.query('SELECT 1 FROM menu_item WHERE id=$1', [itemId]);
        if (duplicate.rowCount) throw httpError(409, 'Could not generate a unique item ID. Try saving again.');
      } else {
        const existing = await client.query('SELECT 1 FROM menu_item WHERE id=$1 FOR UPDATE', [itemId]);
        if (!existing.rowCount) throw httpError(404, 'Menu item not found. Refresh the page and try again.');
      }
      const exclusions = data.exclusionIds.join(',');
      const item = creating
        ? (await client.query(`INSERT INTO menu_item
            (id,category_id,name,description,base_price_cents,active,complementary_exclusions)
            VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING *`,
            [itemId, data.categoryId, data.name, data.description, data.price, data.active, exclusions])).rows[0]
        : (await client.query(`UPDATE menu_item SET category_id=$2,name=$3,description=$4,
            base_price_cents=$5,active=$6,complementary_exclusions=$7 WHERE id=$1 RETURNING *`,
            [itemId, data.categoryId, data.name, data.description, data.price, data.active, exclusions])).rows[0];
      await recordChange(client, 'menu_item', itemId, 'upsert', item);
      await replaceRelationships(client, itemId, data);
      return { ...item, modifier_group_ids: data.modifierGroupIds, recipe: data.recipe,
        complementary_exclusion_ids: data.exclusionIds };
    });
  }

  async function deleteItem(id) {
    const itemId = String(id || '').trim();
    return transaction(async client => {
      const item = await client.query('SELECT * FROM menu_item WHERE id=$1 FOR UPDATE', [itemId]);
      if (!item.rowCount) throw httpError(404, 'Menu item not found. Refresh the page and try again.');
      const groups = await client.query('SELECT * FROM menu_item_modifier_group WHERE item_id=$1', [itemId]);
      const recipes = await client.query('SELECT * FROM recipe_ingredient WHERE item_id=$1', [itemId]);
      for (const row of groups.rows) await upsertTombstone(client, 'menu_item_modifier_group', `${itemId}|${row.group_id}`);
      for (const row of recipes.rows) await upsertTombstone(client, 'recipe_ingredient', `${itemId}|${row.ingredient_id}`);
      await upsertTombstone(client, 'menu_item', itemId);
      await client.query('DELETE FROM menu_item_modifier_group WHERE item_id=$1', [itemId]);
      await client.query('DELETE FROM recipe_ingredient WHERE item_id=$1', [itemId]);
      await client.query('DELETE FROM menu_item WHERE id=$1', [itemId]);
      return { id: itemId, name: item.rows[0].name, deleted: true,
        modifierLinks: groups.rowCount, recipeLinks: recipes.rowCount };
    });
  }

  return {
    list, createCategory, updateCategory, deleteCategory,
    createItem: input => saveItem(null, input, true),
    updateItem: (id, input) => saveItem(id, input, false),
    deleteItem, createModifierGroup, updateModifierGroup, deleteModifierGroup,
    createModifierOption, updateModifierOption, deleteModifierOption
  };
}

module.exports = {
  createMenuService, normalizeMenuId, validateCategoryInput, validateItemInput,
  validateModifierGroupInput, validateModifierOptionInput
};
