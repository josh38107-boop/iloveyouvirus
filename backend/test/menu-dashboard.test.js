const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const dashboard = fs.readFileSync(path.resolve(__dirname, '../../dashboard/menu.html'), 'utf8');
const api = fs.readFileSync(path.resolve(__dirname, '../../dashboard/js/api.js'), 'utf8');
const recipeEditor = require('../../dashboard/js/recipe-editor.js');

test('menu dashboard inline JavaScript parses', () => {
  const scripts = [...dashboard.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)]
    .map(match => match[1]).filter(source => source.trim());
  for (const source of scripts) assert.doesNotThrow(() => new vm.Script(source));
});

test('menu dashboard exposes full POS item, category, and modifier controls', () => {
  for (const marker of ['Add Menu Item', 'Manage Categories', 'Manage Modifiers', 'Recipe Deduction', 'Modifiers',
    'Complementary (Do Not Deduct)', 'Active on POS', 'data-edit-item', 'data-delete-item']) {
    assert.match(dashboard, new RegExp(marker.replace(/[()]/g, '\\$&')));
  }
  assert.match(dashboard, /role="dialog"/);
  assert.match(dashboard, /role="alertdialog"/);
  assert.match(dashboard, /aria-live="polite"/);
  assert.match(dashboard, /event\.key === 'Escape'/);
  assert.match(dashboard, /event\.key !== 'Tab'/);
  assert.match(dashboard, /@media\(max-width:700px\)/);
  assert.match(dashboard, /id="recipeVisibilityButton"/);
  assert.match(dashboard, /aria-controls="recipeList"/);
  assert.match(dashboard, /aria-expanded="false"/);
  assert.match(dashboard, /Show Recipe Only/);
  assert.match(dashboard, /id="modifierGroupForm"/);
  assert.match(dashboard, /id="modifierOptionArea"/);
  assert.match(dashboard, /Inventory deduction \(optional\)/);
  assert.match(dashboard, /aria-live="polite"/);
});

test('recipe editor progressively reveals ingredients without losing the draft', () => {
  const ingredients = [
    { id: 'beans', name: 'Beans' },
    { id: 'milk', name: 'Milk' },
    { id: 'syrup', name: 'Syrup' }
  ];
  const draft = new Map([['beans', '18'], ['milk', '']]);

  assert.deepEqual(
    recipeEditor.visibleIngredients(ingredients, draft, { isEditing: true, showAll: false }).map(row => row.id),
    ['beans']
  );
  assert.deepEqual(
    recipeEditor.visibleIngredients(ingredients, draft, { isEditing: true, showAll: true }).map(row => row.id),
    ['beans', 'milk', 'syrup']
  );
  assert.deepEqual(
    recipeEditor.visibleIngredients(ingredients, draft, { isEditing: false, showAll: false }).map(row => row.id),
    ['beans', 'milk', 'syrup']
  );

  draft.set('syrup', '1.5');
  draft.set('beans', '0');
  assert.deepEqual(
    recipeEditor.visibleIngredients(ingredients, draft, { isEditing: true, showAll: false }).map(row => row.id),
    ['syrup']
  );
  assert.deepEqual(recipeEditor.buildRecipe(ingredients, draft), [
    { ingredient_id: 'syrup', quantity_used: 1.5 }
  ]);
});

test('menu dashboard uses only dedicated mutation endpoints and safe rendering', () => {
  for (const method of ['getMenuManagement', 'createMenuCategory', 'updateMenuCategory',
    'deleteMenuCategory', 'createMenuItem', 'updateMenuItem', 'deleteMenuItem',
    'createModifierGroup', 'updateModifierGroup', 'deleteModifierGroup',
    'createModifierOption', 'updateModifierOption', 'deleteModifierOption']) {
    assert.match(api, new RegExp(method));
  }
  assert.match(dashboard, /escapeHtml\(item\.name\)/);
  assert.doesNotMatch(dashboard, /api\.upsertMenuItem/);
});
