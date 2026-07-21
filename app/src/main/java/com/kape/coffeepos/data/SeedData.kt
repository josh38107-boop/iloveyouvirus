package com.kape.coffeepos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SeedData(private val db: AppDatabase) {
    suspend fun ensureSeeded() = withContext(Dispatchers.IO) {
        val settingsDao = db.settingsDao()
        val currentMethods = settingsDao.paymentMethodsNow()
        val standardMethods = listOf(
            PaymentMethod("cash", "Cash", enabled = true, isSystem = true, paymentCategory = PaymentCategories.CASH),
            PaymentMethod("gcash", "Online", enabled = true, isSystem = true, paymentCategory = PaymentCategories.ONLINE),
            PaymentMethod("split", "Split", enabled = true, isSystem = true),
            PaymentMethod("complimentary", "Complimentary", enabled = true, isSystem = true)
        )
        standardMethods.forEach { standard ->
            val existing = currentMethods.firstOrNull { it.id == standard.id }
            if (existing == null || !existing.isSystem || !existing.enabled || existing.name != standard.name || existing.paymentCategory != standard.paymentCategory) {
                settingsDao.upsertPaymentMethod(standard)
            }
        }
        keepDefaultIngredientsDeleted()
        val hasSeedData = db.seedDao().hasSeedData()
        if (hasSeedData) {
            val settings = db.settingsDao().settingsNow()
            if (settings != null && (settings.storeName == "Kape Coffee" || settings.storeName == "AVEn' You" || settings.storeName == "AVE n' You" || settings.storeName == "Kanlungan")) {
                db.settingsDao().upsert(settings.copy(
                    storeName = "Kanlungan",
                    receiptFooter = settings.receiptFooter
                        .replace("Kape Coffee", "Kanlungan")
                        .replace("AVEn' You", "Kanlungan")
                        .replace("AVE n' You", "Kanlungan")
                ))
            }
            val manager = db.employeeDao().employeeByPin("1111")
            if (manager != null) {
                db.employeeDao().upsertEmployee(manager.copy(pin = "1"))
            }
            val cashier = db.employeeDao().employeeByPin("2222")
            if (cashier != null) {
                db.employeeDao().upsertEmployee(cashier.copy(pin = "2"))
            }
            // Disabled to keep ingredient inventory empty as requested
            /*
            if (db.inventoryDao().modifierRecipesNow().isEmpty()) {
                db.inventoryDao().upsertModifierRecipes(
                    listOf(
                        ModifierRecipeIngredient("extra-shot", "beans", 0.65, null),
                        ModifierRecipeIngredient("oat", "oat", 0.0, "milk"),
                        ModifierRecipeIngredient("almond", "almond", 0.0, "milk"),
                        ModifierRecipeIngredient("caramel", "caramel-sauce", 1.0, null)
                    )
                )
            }
            val currentIngs = db.inventoryDao().ingredientsNow()
            if (currentIngs.none { it.id == "sugar-syrup" }) {
                db.inventoryDao().upsertIngredient(
                    Ingredient("sugar-syrup", "Sugar Syrup", "oz", 200.0, 30.0)
                )
            }
            */
            return@withContext
        }

        db.menuDao().upsertCategories(
            listOf(
                MenuCategory("espresso", "Espresso", 1),
                MenuCategory("signature", "Signature Coffee", 2),
                MenuCategory("cold", "Cold Drinks", 3),
                MenuCategory("tea-non-coffee", "Tea & Non-Coffee", 4),
                MenuCategory("pastry", "Pastries", 5),
                MenuCategory("food", "Food", 6),
                MenuCategory("combos", "Combos", 7)
            )
        )

        db.menuDao().upsertItems(
            listOf(
                MenuItem("latte", "espresso", "Cafe Latte", "Espresso with steamed milk", 525),
                MenuItem("cappuccino", "espresso", "Cappuccino", "Espresso, foam, and steamed milk", 500),
                MenuItem("americano", "espresso", "Americano", "Espresso topped with hot water", 375),
                MenuItem("drip", "espresso", "House Drip", "Rotating single-origin drip coffee", 325),
                MenuItem("spanish-latte", "signature", "Spanish Latte", "Espresso with milk and sweet condensed cream", 595),
                MenuItem("salted-caramel-latte", "signature", "Salted Caramel Latte", "Espresso, milk, caramel, and sea salt", 625),
                MenuItem("mocha", "signature", "Mocha", "Espresso, chocolate, and steamed milk", 595),
                MenuItem("white-chocolate-mocha", "signature", "White Chocolate Mocha", "Espresso, white chocolate, and milk", 625),
                MenuItem("coldbrew", "cold", "Cold Brew", "Slow-steeped coffee over ice", 475),
                MenuItem("iced-americano", "cold", "Iced Americano", "Espresso chilled over ice and water", 395),
                MenuItem("iced-latte", "cold", "Iced Latte", "Espresso, cold milk, and ice", 545),
                MenuItem("iced-spanish-latte", "cold", "Iced Spanish Latte", "Sweet cream latte served over ice", 625),
                MenuItem("coffee-frappe", "cold", "Coffee Frappe", "Blended coffee with milk and frappe base", 675),
                MenuItem("matcha-frappe", "cold", "Matcha Frappe", "Blended matcha with milk and frappe base", 695),
                MenuItem("matcha", "tea-non-coffee", "Matcha Latte", "Ceremonial matcha with milk", 550),
                MenuItem("chai", "tea-non-coffee", "Chai Latte", "Spiced black tea with steamed milk", 500),
                MenuItem("hot-chocolate", "tea-non-coffee", "Hot Chocolate", "Rich chocolate steamed with milk", 475),
                MenuItem("lemon-iced-tea", "tea-non-coffee", "Lemon Iced Tea", "Bright black tea with lemon over ice", 395),
                MenuItem("strawberry-milk", "tea-non-coffee", "Strawberry Milk", "Cold milk with strawberry blend", 445),
                MenuItem("vanilla-milkshake", "tea-non-coffee", "Vanilla Milkshake", "Creamy vanilla shake", 525),
                MenuItem("croissant", "pastry", "Butter Croissant", "Flaky baked pastry", 425),
                MenuItem("chocolate-croissant", "pastry", "Chocolate Croissant", "Buttery pastry filled with chocolate", 475),
                MenuItem("muffin", "pastry", "Blueberry Muffin", "Baked daily", 395),
                MenuItem("banana-bread", "pastry", "Banana Bread", "Moist banana loaf slice", 325),
                MenuItem("cinnamon-roll", "pastry", "Cinnamon Roll", "Soft roll with cinnamon glaze", 425),
                MenuItem("cookies", "pastry", "Cookies", "Pair of house-baked cookies", 275),
                MenuItem("ham-cheese-sandwich", "food", "Ham & Cheese Sandwich", "Toasted sandwich with ham and cheese", 595),
                MenuItem("coffee-croissant-combo", "combos", "Coffee + Croissant", "House drip coffee with a butter croissant", 695),
                MenuItem("morning-set", "combos", "Morning Set", "Cafe latte with banana bread", 795),
                MenuItem("student-snack-combo", "combos", "Student Snack Combo", "Iced latte with cookies", 725)
            )
        )

        db.menuDao().upsertGroups(
            listOf(
                ModifierGroup("size", "Size", true, 1),
                ModifierGroup("milk", "Milk", false, 1),
                ModifierGroup("shots", "Extra Shots", false, 1),
                ModifierGroup("syrup", "Syrup", false, 2),
                ModifierGroup("temp", "Temperature", false, 1)
            )
        )
        db.menuDao().upsertOptions(
            listOf(
                ModifierOption("small", "size", "Small", 0),
                ModifierOption("medium", "size", "Medium", 75),
                ModifierOption("large", "size", "Large", 125),
                ModifierOption("whole", "milk", "Whole Milk", 0),
                ModifierOption("oat", "milk", "Oat Milk", 85),
                ModifierOption("almond", "milk", "Almond Milk", 85),
                ModifierOption("extra-shot", "shots", "Extra Shot", 125),
                ModifierOption("vanilla", "syrup", "Vanilla", 65),
                ModifierOption("caramel", "syrup", "Caramel", 65),
                ModifierOption("hot", "temp", "Hot", 0),
                ModifierOption("iced", "temp", "Iced", 0)
            )
        )

        val espressoDrinkIds = listOf(
            "latte",
            "cappuccino",
            "americano",
            "spanish-latte",
            "salted-caramel-latte",
            "mocha",
            "white-chocolate-mocha",
            "coldbrew",
            "iced-americano",
            "iced-latte",
            "iced-spanish-latte",
            "coffee-frappe",
            "matcha-frappe",
            "matcha",
            "chai",
            "hot-chocolate",
            "lemon-iced-tea",
            "strawberry-milk",
            "vanilla-milkshake"
        )
        db.menuDao().upsertItemGroups(
            espressoDrinkIds.flatMap { item ->
                listOf("size", "milk", "shots", "syrup", "temp").map { MenuItemModifierGroup(item, it) }
            }
        )

        db.employeeDao().upsertEmployees(
            listOf(
                Employee("manager", "Avery Manager", "1", "manager"),
                Employee("cashier", "Riley Cashier", "2", "cashier")
            )
        )
        db.settingsDao().upsert(
            StoreSettings(
                storeName = "Kanlungan",
                taxRatePercent = 0.0,
                tipPresets = "",
                receiptFooter = "Thanks for visiting Kanlungan."
            )
        )

    }

    private suspend fun keepDefaultIngredientsDeleted() {
        val existingDeletes = db.pendingDeleteDao().allNow()
            .filter { it.entityType == SyncEntityType.INGREDIENT }
            .map { it.entityId }
            .toSet()
        val missingDeletes = DEFAULT_INGREDIENT_IDS
            .filterNot { it in existingDeletes }
            .map {
                PendingDelete(
                    entityType = SyncEntityType.INGREDIENT,
                    entityId = it,
                    deletedAt = System.currentTimeMillis(),
                    synced = false
                )
            }
        if (missingDeletes.isNotEmpty()) {
            db.pendingDeleteDao().upsertAll(missingDeletes)
        }
    }

    private companion object {
        val DEFAULT_INGREDIENT_IDS = listOf(
            "beans",
            "milk",
            "oat",
            "almond",
            "matcha-powder",
            "chai-base",
            "coldbrew-base",
            "condensed-cream",
            "caramel-sauce",
            "chocolate-sauce",
            "white-chocolate-sauce",
            "lemon-tea-base",
            "strawberry-base",
            "vanilla-base",
            "frappe-base",
            "croissant-stock",
            "chocolate-croissant-stock",
            "muffin-stock",
            "banana-bread-stock",
            "cinnamon-roll-stock",
            "cookie-stock",
            "sandwich-stock",
            "sugar-syrup"
        )
    }
}
