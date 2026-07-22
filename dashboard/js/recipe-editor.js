(function (root, factory) {
  const recipeEditor = factory();
  if (typeof module === 'object' && module.exports) module.exports = recipeEditor;
  root.RecipeEditor = recipeEditor;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  function isPositiveQuantity(value) {
    const quantity = Number(value);
    return Number.isFinite(quantity) && quantity > 0;
  }

  function visibleIngredients(ingredients, draft, options) {
    if (!options.isEditing || options.showAll) return ingredients;
    return ingredients.filter(ingredient => isPositiveQuantity(draft.get(ingredient.id)));
  }

  function buildRecipe(ingredients, draft) {
    return ingredients.map(ingredient => ({
      ingredient_id: ingredient.id,
      quantity_used: Number(draft.get(ingredient.id))
    })).filter(row => isPositiveQuantity(row.quantity_used));
  }

  return { isPositiveQuantity, visibleIngredients, buildRecipe };
});
