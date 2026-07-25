package com.kape.coffeepos.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.roundToInt

internal data class OrderActionResult(
    val changed: Boolean,
    val message: String,
    val warnings: List<String> = emptyList()
)

private val LEGACY_ORDER_ADD_ON_REASON =
    Regex("^Post-checkout add-on \\(Order ([A-F0-9]{8})\\): .+$", RegexOption.IGNORE_CASE)

internal fun legacyOrderAddOnPrefix(reason: String): String? =
    LEGACY_ORDER_ADD_ON_REASON.matchEntire(reason.trim())
        ?.groupValues
        ?.getOrNull(1)
        ?.uppercase(Locale.US)

internal fun isLegacyOrderPrefixUnique(orderId: String, allOrderIds: List<String>): Boolean {
    val prefix = orderId.take(8).uppercase(Locale.US)
    return allOrderIds.count { it.take(8).uppercase(Locale.US) == prefix } == 1
}

internal fun unrestoredOrderAddOns(rows: List<OrderInventoryAddOn>): List<OrderInventoryAddOn> =
    rows.filter { it.restoredAt == null }

internal fun isExcludedFromVoidRestoration(
    ingredientId: String,
    orderType: String,
    takeoutOnlyIngredientIds: Set<String>,
    otherExclusions: Set<String>
): Boolean = ingredientId in otherExclusions ||
    (orderType == "Dine-In" && ingredientId in takeoutOnlyIngredientIds)

private fun takeReceiptTextSegment(text: String, maxLength: Int): Pair<String, String> {
    val remainingText = text.trimStart()
    if (remainingText.length <= maxLength) return remainingText to ""

    val boundary = remainingText.lastIndexOf(' ', startIndex = maxLength)
    val splitAt = if (boundary > 0) boundary else maxLength
    return remainingText.substring(0, splitAt).trimEnd() to
        remainingText.substring(splitAt).trimStart()
}

internal fun formatReceiptItemLines(
    quantity: Int,
    itemName: String,
    price: String,
    width: Int
): List<String> {
    require(width > 0) { "Receipt width must be positive." }

    val prefix = "$quantity x "
    val normalizedName = itemName.replace(Regex("\\s+"), " ").trim()
    val firstNameWidth = (width - prefix.length - price.length - 1).coerceAtLeast(0)
    val (firstNamePart, initialRemainder) = if (firstNameWidth > 0) {
        takeReceiptTextSegment(normalizedName, firstNameWidth)
    } else {
        "" to normalizedName
    }
    val firstLeft = prefix + firstNamePart
    val firstGap = (width - firstLeft.length - price.length).coerceAtLeast(1)
    val result = mutableListOf(firstLeft + " ".repeat(firstGap) + price)

    val continuationWidth = (width - prefix.length).coerceAtLeast(1)
    var remainder = initialRemainder
    while (remainder.isNotEmpty()) {
        val (part, next) = takeReceiptTextSegment(remainder, continuationWidth)
        result += " ".repeat(prefix.length) + part
        remainder = next
    }
    return result
}

internal suspend fun clearOperationalHistoryPreservingInventory(database: AppDatabase) {
    database.withTransaction {
        val orderDao = database.orderDao()
        database.inventoryDao().clearOrderInventoryAddOns()
        orderDao.clearOrderLines()
        orderDao.clearPayments()
        orderDao.clearReceipts()
        orderDao.clearOrders()
        orderDao.clearStockSnapshots()
        orderDao.clearClosedShiftAdjustments()
        orderDao.clearShifts()
    }
}

data class MenuCatalog(
    val categories: List<MenuCategory> = emptyList(),
    val items: List<MenuItem> = emptyList(),
    val groups: List<ModifierGroup> = emptyList(),
    val options: List<ModifierOption> = emptyList(),
    val itemGroups: List<MenuItemModifierGroup> = emptyList()
)

data class CartLine(
    val id: String = java.util.UUID.randomUUID().toString(),
    val item: MenuItem,
    val quantity: Int = 1,
    val modifiers: List<ModifierOption> = emptyList(),
    val notes: String = ""
) {
    val unitPriceCents: Int get() = item.basePriceCents + modifiers.sumOf { it.priceDeltaCents }
    val lineTotalCents: Int get() = unitPriceCents * quantity
    val modifierLabel: String get() = modifiers.joinToString(", ") { it.name }
}

data class CartTotals(
    val subtotalCents: Int,
    val discountCents: Int,
    val taxCents: Int,
    val tipCents: Int,
    val totalCents: Int
)

internal data class ItemDiscountSelection(
    val cartLineId: String?,
    val category: String,
    val percent: Double,
    val discountCents: Int,
    val ruleId: String? = null,
    val scope: String = "item",
    val reference: String? = null
)

internal fun calculateSingleItemDiscountCents(
    lines: List<CartLine>,
    cartLineId: String?,
    percent: Double
): Int {
    if (cartLineId == null || percent <= 0.0) return 0
    val unitPriceCents = lines.firstOrNull { it.id == cartLineId }?.unitPriceCents ?: return 0
    return (unitPriceCents * percent / 100.0)
        .roundToInt()
        .coerceIn(0, unitPriceCents)
}

internal fun calculatePromotionBaseDiscountCents(lines: List<CartLine>, cartLineId: String?): Int {
    if (cartLineId == null) return 0
    return lines.firstOrNull { it.id == cartLineId }?.item?.basePriceCents?.coerceAtLeast(0) ?: 0
}

internal fun calculateWholeOrderDiscountCents(lines: List<CartLine>, percent: Double): Int {
    if (percent <= 0.0) return 0
    val subtotal = lines.sumOf { it.lineTotalCents }
    return (subtotal * percent / 100.0).roundToInt().coerceIn(0, subtotal)
}

private fun normalizeAppliedDiscount(
    lines: List<CartLine>,
    selection: ItemDiscountSelection?
): ItemDiscountSelection? {
    val selected = selection
        ?.takeIf {
            it.category == "Senior" || it.category == "PWD" ||
                it.category == "PROMO_FREE_DRINK" || it.ruleId != null
        }
        ?: return null
    val cents = when {
        selected.category == "PROMO_FREE_DRINK" ->
            selected.discountCents.coerceIn(0, calculatePromotionBaseDiscountCents(lines, selected.cartLineId))
        selected.scope == "order" -> calculateWholeOrderDiscountCents(lines, selected.percent)
        else -> calculateSingleItemDiscountCents(lines, selected.cartLineId, selected.percent)
    }
    return selected.copy(discountCents = cents).takeIf { cents > 0 }
}

data class ShiftOpenResult(
    val shift: Shift,
    val joinedExisting: Boolean
)

data class TopSellingItem(val name: String, val qtySold: Int, val revenueCents: Int)

data class EmployeeBreakdown(val employeeId: String, val name: String, val orderCount: Int, val salesCents: Int)

data class IngredientUsageSummary(
    val ingredientId: String,
    val name: String,
    val unit: String,
    val usedToday: Double,
    val restocked: Double,
    val endingStock: Double,
    val isLow: Boolean
)

enum class ReportDateRange { TODAY, MONTH, ALL, CUSTOM }

internal const val CUSTOM_REPORT_MISSING_DATES_ERROR = "Select both From and To dates"
internal const val CUSTOM_REPORT_REVERSED_DATES_ERROR = "From date must be on or before To date"

internal fun customReportRangeError(start: Long?, end: Long?): String? = when {
    start == null || end == null -> CUSTOM_REPORT_MISSING_DATES_ERROR
    start > end -> CUSTOM_REPORT_REVERSED_DATES_ERROR
    else -> null
}

internal fun requireValidCustomReportRange(start: Long?, end: Long?): Pair<Long, Long> {
    val error = customReportRangeError(start, end)
    require(error == null) { error ?: "Invalid Custom report range." }
    return checkNotNull(start) to checkNotNull(end)
}

private fun manilaBusinessDayBounds(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila"))
    cal.timeInMillis = now
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val dayStart = cal.timeInMillis
    return Pair(dayStart, dayStart + 24L * 60L * 60L * 1000L)
}

data class DailyReport(
    val orderCount: Int = 0,
    val grossSalesCents: Int = 0,
    val discountsCents: Int = 0,
    val netSalesCents: Int = 0,
    val avgOrderValueCents: Int = 0,
    val paymentTotals: Map<String, Int> = emptyMap(),
    val onlinePaymentSalesCents: Int = 0,
    val topItems: List<TopSellingItem> = emptyList(),
    val hourlySales: Map<Int, Int> = emptyMap(),
    val cashDrawerExpected: Int = 0,
    val cashDrawerActual: Int = 0,
    val cashDrawerDifference: Int = 0,
    val employeeBreakdowns: List<EmployeeBreakdown> = emptyList(),
    val ingredientUsage: List<IngredientUsageSummary> = emptyList(),
    val cashDrawerStarting: Int = 0,
    val cashDrawerSales: Int = 0,
    val cashDrawerAdded: Int = 0,
    val cashDrawerRemoved: Int = 0,
    val closedShiftAdjustments: List<ClosedShiftAdjustment> = emptyList(),
    val shifts: List<Shift> = emptyList()
)

class MenuRepository(
    private val dao: MenuDao,
    private val pendingDeleteDao: PendingDeleteDao? = null
) {
    val catalog: Flow<MenuCatalog> = combine(
        dao.categories(),
        dao.items(),
        dao.modifierGroups(),
        dao.modifierOptions(),
        dao.itemGroups()
    ) { categories, items, groups, options, itemGroups ->
        MenuCatalog(categories, items, groups, options, itemGroups)
    }

    suspend fun saveMenuItem(
        item: MenuItem,
        modifierGroupIds: List<String>
    ) = withContext(Dispatchers.IO) {
        val existingGroups = dao.itemGroupsForItemNow(item.id)
        val nextGroupIds = modifierGroupIds.toSet()
        existingGroups
            .filter { it.groupId !in nextGroupIds }
            .forEach {
                recordDelete(
                    SyncEntityType.MENU_ITEM_MODIFIER_GROUP,
                    SyncEntityId.menuItemModifierGroup(it.itemId, it.groupId)
                )
            }
        dao.upsertItem(item)
        dao.deleteItemGroups(item.id)
        dao.upsertItemGroups(modifierGroupIds.map { MenuItemModifierGroup(item.id, it) })
    }

    suspend fun saveMenuItems(items: List<MenuItem>) = withContext(Dispatchers.IO) {
        if (items.isNotEmpty()) {
            dao.upsertItems(items)
        }
    }

    suspend fun modifierGroupIdsForItem(itemId: String): List<String> = withContext(Dispatchers.IO) {
        dao.itemGroupIds(itemId)
    }

    suspend fun saveCategory(category: MenuCategory) = withContext(Dispatchers.IO) {
        dao.upsertCategory(category)
    }

    suspend fun deleteCategory(categoryId: String) = withContext(Dispatchers.IO) {
        recordDelete(SyncEntityType.MENU_CATEGORY, categoryId)
        dao.deleteCategory(categoryId)
    }

    suspend fun saveModifierGroup(group: ModifierGroup) = withContext(Dispatchers.IO) {
        dao.upsertGroup(group)
    }

    suspend fun saveModifierOption(option: ModifierOption) = withContext(Dispatchers.IO) {
        dao.upsertOption(option)
    }

    suspend fun deleteModifierOption(optionId: String) = withContext(Dispatchers.IO) {
        recordDelete(SyncEntityType.MODIFIER_OPTION, optionId)
        dao.deleteOption(optionId)
    }

    suspend fun deleteModifierGroup(groupId: String) = withContext(Dispatchers.IO) {
        dao.itemGroupsForGroupNow(groupId).forEach {
            recordDelete(
                SyncEntityType.MENU_ITEM_MODIFIER_GROUP,
                SyncEntityId.menuItemModifierGroup(it.itemId, it.groupId)
            )
        }
        dao.optionsForGroupNow(groupId).forEach {
            recordDelete(SyncEntityType.MODIFIER_OPTION, it.id)
        }
        recordDelete(SyncEntityType.MODIFIER_GROUP, groupId)
        dao.deleteItemGroupsForGroup(groupId)
        dao.deleteOptionsForGroup(groupId)
        dao.deleteGroup(groupId)
    }

    suspend fun modifierOptionsNow(): List<ModifierOption> = withContext(Dispatchers.IO) {
        dao.modifierOptionsNow()
    }

    private suspend fun recordDelete(entityType: String, entityId: String) {
        pendingDeleteDao?.upsert(
            PendingDelete(
                entityType = entityType,
                entityId = entityId,
                deletedAt = System.currentTimeMillis(),
                synced = false
            )
        )
    }
}

class InventoryRepository(
    private val dao: InventoryDao,
    private val pendingDeleteDao: PendingDeleteDao? = null
) {
    val ingredients: Flow<List<Ingredient>> = dao.ingredients()
    val lowStock: Flow<List<Ingredient>> = dao.lowStock()

    suspend fun adjust(ingredientId: String, delta: Double, reason: String) = withContext(Dispatchers.IO) {
        dao.adjustQuantity(ingredientId, delta)
        dao.insertAdjustment(
            InventoryAdjustment(
                ingredientId = ingredientId,
                deltaQuantity = delta,
                reason = reason,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun recordOrderAddOn(
        orderId: String,
        ingredientId: String,
        quantity: Double,
        reason: String
    ) = withContext(Dispatchers.IO) {
        require(quantity > 0) { "Add-on quantity must be positive" }
        val now = System.currentTimeMillis()
        dao.recordOrderInventoryAddOn(
            row = OrderInventoryAddOn(
                id = UUID.randomUUID().toString(),
                orderId = orderId,
                ingredientId = ingredientId,
                quantity = quantity,
                createdAt = now,
                updatedAt = now
            ),
            adjustment = InventoryAdjustment(
                ingredientId = ingredientId,
                deltaQuantity = -quantity,
                reason = reason,
                createdAt = now
            )
        )
    }

    suspend fun replaceRecipe(itemId: String, recipeRows: List<RecipeIngredient>) = withContext(Dispatchers.IO) {
        val nextIngredientIds = recipeRows.map { it.ingredientId }.toSet()
        dao.recipesForItem(itemId)
            .filter { it.ingredientId !in nextIngredientIds }
            .forEach {
                recordDelete(
                    SyncEntityType.RECIPE_INGREDIENT,
                    SyncEntityId.recipeIngredient(it.itemId, it.ingredientId)
                )
            }
        dao.deleteRecipesForItem(itemId)
        if (recipeRows.isNotEmpty()) {
            dao.upsertRecipes(recipeRows)
        }
    }

    suspend fun recipeForItem(itemId: String): List<RecipeIngredient> = withContext(Dispatchers.IO) {
        dao.recipesForItem(itemId)
    }

    suspend fun saveIngredient(ingredient: Ingredient) = withContext(Dispatchers.IO) {
        dao.upsertIngredient(ingredient)
    }

    suspend fun createIngredient(ingredient: Ingredient): Boolean = withContext(Dispatchers.IO) {
        try {
            dao.insertIngredient(ingredient)
            true
        } catch (_: SQLiteConstraintException) {
            false
        }
    }

    suspend fun ingredientById(ingredientId: String): Ingredient? = withContext(Dispatchers.IO) {
        dao.ingredientById(ingredientId)
    }

    suspend fun deleteIngredient(ingredientId: String) = withContext(Dispatchers.IO) {
        dao.recipesForIngredient(ingredientId).forEach {
            recordDelete(
                SyncEntityType.RECIPE_INGREDIENT,
                SyncEntityId.recipeIngredient(it.itemId, it.ingredientId)
            )
        }
        dao.modifierRecipesForIngredient(ingredientId).forEach {
            recordDelete(
                SyncEntityType.MODIFIER_RECIPE_INGREDIENT,
                SyncEntityId.modifierRecipeIngredient(it.optionId, it.ingredientId)
            )
        }
        recordDelete(SyncEntityType.INGREDIENT, ingredientId)
        dao.deleteIngredient(ingredientId)
    }

    val modifierRecipes: Flow<List<ModifierRecipeIngredient>> = dao.modifierRecipesFlow()

    suspend fun modifierRecipesNow(): List<ModifierRecipeIngredient> = withContext(Dispatchers.IO) {
        dao.modifierRecipesNow()
    }

    suspend fun saveModifierRecipe(recipe: ModifierRecipeIngredient) = withContext(Dispatchers.IO) {
        dao.modifierRecipesForOption(recipe.optionId)
            .filter { it.ingredientId != recipe.ingredientId }
            .forEach {
                recordDelete(
                    SyncEntityType.MODIFIER_RECIPE_INGREDIENT,
                    SyncEntityId.modifierRecipeIngredient(it.optionId, it.ingredientId)
                )
            }
        dao.upsertModifierRecipe(recipe)
    }

    suspend fun deleteModifierRecipe(optionId: String) = withContext(Dispatchers.IO) {
        dao.modifierRecipesForOption(optionId).forEach {
            recordDelete(
                SyncEntityType.MODIFIER_RECIPE_INGREDIENT,
                SyncEntityId.modifierRecipeIngredient(it.optionId, it.ingredientId)
            )
        }
        dao.deleteModifierRecipe(optionId)
    }

    suspend fun allRecipes(): List<RecipeIngredient> = withContext(Dispatchers.IO) {
        dao.recipes()
    }

    private suspend fun recordDelete(entityType: String, entityId: String) {
        pendingDeleteDao?.upsert(
            PendingDelete(
                entityType = entityType,
                entityId = entityId,
                deletedAt = System.currentTimeMillis(),
                synced = false
            )
        )
    }

    suspend fun deductFor(
        lines: List<CartLine>,
        isComplimentary: Boolean = false,
        exclusions: Set<String> = emptySet(),
        exclusionsByItemId: Map<String, Set<String>> = emptyMap(),
        isDineIn: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val recipes = dao.recipes().groupBy { it.itemId }
        val modRecipes = dao.modifierRecipesNow().groupBy { it.optionId }
        val takeoutOnlyExclusions = if (isDineIn) dao.takeoutOnlyIngredientIds().toSet() else emptySet()
        val saleTypeLabel = if (isComplimentary) "Complimentary sale" else "Order sale"
        
        lines.forEach { line ->
            val baseRecipe = recipes[line.item.id].orEmpty()
            val activeModifiers = line.modifiers
            val modifierDeductions = activeModifiers.flatMap { modRecipes[it.id].orEmpty() }
            val replacedIngredientIds = modifierDeductions.mapNotNull { it.replacesIngredientId }.toSet()
            val finalExclusions = exclusions + exclusionsByItemId[line.item.id].orEmpty() + takeoutOnlyExclusions
            
            // 1. Deduct base ingredients (only those not replaced by a modifier)
            baseRecipe.forEach { recipe ->
                if (recipe.ingredientId !in replacedIngredientIds) {
                    if (recipe.ingredientId !in finalExclusions) {
                        val baseQty = recipe.quantityUsed * line.quantity
                        dao.adjustQuantity(recipe.ingredientId, -baseQty)
                        dao.insertAdjustment(
                            InventoryAdjustment(
                                ingredientId = recipe.ingredientId,
                                deltaQuantity = -baseQty,
                                reason = "$saleTypeLabel: ${line.item.name}",
                                createdAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
            
            // 2. Deduct modifier ingredients (e.g. extra shot, alternative milk)
            modifierDeductions.forEach { modRecipe ->
                if (modRecipe.ingredientId !in finalExclusions) {
                    val qtyPerItem = if (modRecipe.replacesIngredientId != null) {
                        // Substitution: use the quantity of the ingredient being replaced
                        baseRecipe.firstOrNull { it.ingredientId == modRecipe.replacesIngredientId }?.quantityUsed
                            ?: modRecipe.quantityUsed
                    } else {
                        modRecipe.quantityUsed
                    }
                    val totalQty = qtyPerItem * line.quantity
                    if (totalQty > 0) {
                        dao.adjustQuantity(modRecipe.ingredientId, -totalQty)
                        dao.insertAdjustment(
                            InventoryAdjustment(
                                ingredientId = modRecipe.ingredientId,
                                deltaQuantity = -totalQty,
                                reason = "$saleTypeLabel (modifier): ${line.item.name} + ${activeModifiers.firstOrNull { it.id == modRecipe.optionId }?.name ?: modRecipe.optionId}",
                                createdAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        }
    }
}

class EmployeeRepository(private val dao: EmployeeDao) {
    val employees: Flow<List<Employee>> = dao.employees()
    val allEmployees: Flow<List<Employee>> = dao.allEmployees()
    suspend fun login(pin: String): Employee? = withContext(Dispatchers.IO) { dao.employeeByPin(pin) }
    suspend fun saveEmployee(employee: Employee) = withContext(Dispatchers.IO) {
        dao.upsertEmployee(employee)
    }
}

class ShiftRepository(
    private val dao: ShiftDao,
    private val stockSnapshotDao: StockSnapshotDao,
    private val inventoryDao: InventoryDao,
    context: Context
) {
    private val prefs = context.getSharedPreferences("local_shift_prefs", Context.MODE_PRIVATE)

    val activeShift: Flow<Shift?> = manilaBusinessDayBounds().let { (dayStart, dayEnd) ->
        dao.activeShift(dayStart, dayEnd)
    }

    suspend fun recoverLocalActiveShiftIfNeeded() = withContext(Dispatchers.IO) {
        prefs.edit()
            .putBoolean(KEY_SHIFT_TRACKING_INITIALIZED, true)
            .remove(KEY_ACTIVE_SHIFT_ID)
            .apply()
    }

    /**
     * Closes a stale shared shift from a previous day so the next day starts cleanly.
     */
    suspend fun ensureTodayShift() = withContext(Dispatchers.IO) {
        val (dayStart, dayEnd) = manilaBusinessDayBounds()
        val active = dao.allShiftsNow()
            .filter { it.closedAt == null }
            .maxByOrNull { it.openedAt }
            ?: return@withContext

        if (active.openedAt < dayStart || active.openedAt >= dayEnd) {
            dao.closeShift(active.id, System.currentTimeMillis(), active.endingCashCents ?: 0)
        }
    }

    suspend fun openShift(employeeId: String, startingCashCents: Int): ShiftOpenResult = withContext(Dispatchers.IO) {
        val (dayStart, dayEnd) = manilaBusinessDayBounds()
        val existing = dao.activeShiftNow(dayStart, dayEnd)
        if (existing != null) {
            prefs.edit()
                .putBoolean(KEY_SHIFT_TRACKING_INITIALIZED, true)
                .remove(KEY_ACTIVE_SHIFT_ID)
                .apply()
            return@withContext ShiftOpenResult(existing, joinedExisting = true)
        }

        val shiftId = dao.openShift(
            Shift(
                employeeId = employeeId,
                openedAt = System.currentTimeMillis(),
                closedAt = null,
                startingCashCents = startingCashCents,
                endingCashCents = null,
                cashAddedCents = 0,
                cashRemovedCents = 0
            )
        )

        // Take opening stock snapshot
        val ingredients = inventoryDao.ingredientsNow()
        val snapshots = ingredients.map { ing ->
            StockSnapshot(
                shiftId = shiftId,
                ingredientId = ing.id,
                quantity = ing.quantityOnHand
            )
        }
        if (snapshots.isNotEmpty()) {
            stockSnapshotDao.insertSnapshots(snapshots)
        }
        prefs.edit()
            .putBoolean(KEY_SHIFT_TRACKING_INITIALIZED, true)
            .remove(KEY_ACTIVE_SHIFT_ID)
            .apply()
        val shift = dao.getShiftByIdNow(shiftId) ?: Shift(
            id = shiftId,
            employeeId = employeeId,
            openedAt = System.currentTimeMillis(),
            closedAt = null,
            startingCashCents = startingCashCents,
            endingCashCents = null
        )
        ShiftOpenResult(shift, joinedExisting = false)
    }

    suspend fun closeShift(shiftId: Long, endingCashCents: Int) = withContext(Dispatchers.IO) {
        dao.closeShift(shiftId, System.currentTimeMillis(), endingCashCents)
        prefs.edit()
            .putBoolean(KEY_SHIFT_TRACKING_INITIALIZED, true)
            .remove(KEY_ACTIVE_SHIFT_ID)
            .apply()
    }

    suspend fun addCash(shiftId: Long, amountCents: Int) = withContext(Dispatchers.IO) {
        dao.addCash(shiftId, amountCents)
    }

    suspend fun removeCash(shiftId: Long, amountCents: Int) = withContext(Dispatchers.IO) {
        dao.removeCash(shiftId, amountCents)
    }

    fun getShiftCashSales(shiftId: Long): Flow<Int> {
        return dao.getShiftCashSales(shiftId)
    }

    fun getShiftGCashSales(shiftId: Long): Flow<Int> {
        return dao.getShiftGCashSales(shiftId)
    }

    private companion object {
        const val KEY_ACTIVE_SHIFT_ID = "active_shift_id"
        const val KEY_SHIFT_TRACKING_INITIALIZED = "shift_tracking_initialized"
    }
}

class SettingsRepository(
    private val dao: SettingsDao,
    private val pendingDeleteDao: PendingDeleteDao? = null
) {
    val settings: Flow<StoreSettings?> = dao.settings()
    suspend fun settingsNow(): StoreSettings = withContext(Dispatchers.IO) {
        dao.settingsNow() ?: StoreSettings(
            storeName = "Kanlungan",
            taxRatePercent = 8.25,
            tipPresets = "10,15,20",
            receiptFooter = "Thanks for visiting Kanlungan.",
            seniorDiscountPercent = 20.0,
            pwdDiscountPercent = 20.0,
            voidRefundPin = "1234"
        )
    }
    suspend fun saveSettings(settings: StoreSettings) = withContext(Dispatchers.IO) {
        dao.upsert(settings)
    }
    val paymentMethods: Flow<List<PaymentMethod>> = dao.paymentMethodsFlow()
    val discountRules: Flow<List<DiscountRule>> = dao.discountRulesFlow()
    suspend fun paymentMethodsNow(): List<PaymentMethod> = withContext(Dispatchers.IO) {
        dao.paymentMethodsNow()
    }
    suspend fun savePaymentMethod(method: PaymentMethod) = withContext(Dispatchers.IO) {
        dao.upsertPaymentMethod(method)
    }
    suspend fun deletePaymentMethod(id: String) = withContext(Dispatchers.IO) {
        pendingDeleteDao?.upsert(
            PendingDelete(
                entityType = SyncEntityType.PAYMENT_METHOD,
                entityId = id,
                deletedAt = System.currentTimeMillis(),
                synced = false
            )
        )
        dao.deletePaymentMethod(id)
    }
}

class OrderRepository(
    private val database: AppDatabase,
    private val orderDao: OrderDao,
    private val shiftDao: ShiftDao,
    private val settingsRepository: SettingsRepository,
    private val inventoryRepository: InventoryRepository,
    private val menuDao: MenuDao,
    private val syncManager: SupabaseSyncManager? = null
) {
    val orders: Flow<List<PosOrder>> = orderDao.orders()
    val payments: Flow<List<Payment>> = orderDao.payments()

    internal suspend fun checkout(
        employee: Employee,
        shift: Shift,
        lines: List<CartLine>,
        itemDiscount: ItemDiscountSelection?,
        tipCents: Int,
        paymentMethod: String,
        paymentCategory: String?,
        amountTenderedCents: Int,
        customerName: String?,
        tableNumber: String?,
        orderType: String = "Dine-In",
        lineCharacters: Int = 32
    ): PosOrder = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settingsNow()
        val isComp = paymentMethod.lowercase(Locale.US) == "complimentary"
        val appliedItemDiscount = normalizeAppliedDiscount(lines, itemDiscount)
            ?.takeIf { !isComp }
        val totals = calculateTotals(lines, appliedItemDiscount?.discountCents ?: 0, tipCents, settings.taxRatePercent)
        val changeCents = (amountTenderedCents - totals.totalCents).coerceAtLeast(0)
        val orderId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val order = PosOrder(
            id = orderId,
            status = "paid",
            employeeId = employee.id,
            shiftId = shift.id,
            subtotalCents = if (isComp) 0 else totals.subtotalCents,
            discountCents = if (isComp) 0 else totals.discountCents,
            discountRuleId = if (isComp) null else appliedItemDiscount?.ruleId,
            discountCategory = if (isComp) null else appliedItemDiscount?.let {
                if (it.category == "PROMO_FREE_DRINK") "Free Drink Reward" else it.category
            },
            discountPercent = if (isComp || appliedItemDiscount?.category == "PROMO_FREE_DRINK") null else appliedItemDiscount?.percent,
            discountScope = if (isComp) null else appliedItemDiscount?.scope,
            discountReference = if (isComp) null else appliedItemDiscount?.reference?.ifBlank { null },
            taxCents = if (isComp) 0 else totals.taxCents,
            tipCents = if (isComp) 0 else totals.tipCents,
            totalCents = if (isComp) 0 else totals.totalCents,
            createdAt = now,
            paidAt = now,
            customerName = customerName?.ifBlank { null },
            tableNumber = tableNumber?.ifBlank { null },
            orderType = orderType
        )
        orderDao.insertOrder(order)
        orderDao.insertLines(
            lines.map {
                val lineDiscount = appliedItemDiscount?.takeIf { selection ->
                    selection.scope == "item" && selection.cartLineId == it.id
                }
                OrderLine(
                    orderId = orderId,
                    itemId = it.item.id,
                    name = it.item.name,
                    quantity = it.quantity,
                    unitPriceCents = it.unitPriceCents,
                    modifiers = it.modifierLabel,
                    notes = it.notes,
                    discountCategory = lineDiscount?.category,
                    discountCents = lineDiscount?.discountCents ?: 0
                )
            }
        )
        orderDao.insertPayment(
            Payment(
                orderId = orderId,
                method = paymentMethod,
                amountCents = if (isComp) 0 else totals.totalCents,
                amountTenderedCents = if (isComp) 0 else amountTenderedCents,
                changeCents = if (isComp) 0 else changeCents,
                createdAt = now,
                paymentCategory = paymentCategory
            )
        )
        orderDao.insertReceipt(
            Receipt(
                orderId = orderId,
                receiptNumber = orderId.take(8).uppercase(Locale.US),
                text = buildReceipt(settings, order, lines, appliedItemDiscount, paymentMethod, amountTenderedCents, changeCents, lineCharacters),
                createdAt = now
            )
        )
        val exclusionsByItemId = if (paymentMethod == "Complimentary") {
            menuDao.itemsNow().associate { item ->
                item.id to item.complementaryExclusions.split(",").filter { it.isNotBlank() }.toSet()
            }
        } else {
            emptyMap()
        }
        inventoryRepository.deductFor(
            lines = lines,
            isComplimentary = (paymentMethod == "Complimentary"),
            exclusionsByItemId = exclusionsByItemId,
            isDineIn = (orderType == "Dine-In")
        )
        syncManager?.let { sm ->
            CoroutineScope(Dispatchers.IO).launch {
                sm.syncNow()
            }
        }
        order
    }

    internal suspend fun checkoutSplit(
        employee: Employee,
        shift: Shift,
        lines: List<CartLine>,
        itemDiscount: ItemDiscountSelection?,
        tipCents: Int,
        cashAmountCents: Int,
        gcashAmountCents: Int,
        customerName: String?,
        tableNumber: String?,
        orderType: String = "Dine-In",
        lineCharacters: Int = 32
    ): PosOrder = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settingsNow()
        val appliedItemDiscount = normalizeAppliedDiscount(lines, itemDiscount)
        val totals = calculateTotals(lines, appliedItemDiscount?.discountCents ?: 0, tipCents, settings.taxRatePercent)
        val orderId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        
        val gcashPaymentAmount = gcashAmountCents.coerceAtMost(totals.totalCents)
        val cashPaymentAmount = totals.totalCents - gcashPaymentAmount
        val cashChange = (cashAmountCents - cashPaymentAmount).coerceAtLeast(0)

        val order = PosOrder(
            id = orderId,
            status = "paid",
            employeeId = employee.id,
            shiftId = shift.id,
            subtotalCents = totals.subtotalCents,
            discountCents = totals.discountCents,
            discountRuleId = appliedItemDiscount?.ruleId,
            discountCategory = appliedItemDiscount?.let {
                if (it.category == "PROMO_FREE_DRINK") "Free Drink Reward" else it.category
            },
            discountPercent = appliedItemDiscount
                ?.takeUnless { it.category == "PROMO_FREE_DRINK" }
                ?.percent,
            discountScope = appliedItemDiscount?.scope,
            discountReference = appliedItemDiscount?.reference?.ifBlank { null },
            taxCents = totals.taxCents,
            tipCents = totals.tipCents,
            totalCents = totals.totalCents,
            createdAt = now,
            paidAt = now,
            customerName = customerName?.ifBlank { null },
            tableNumber = tableNumber?.ifBlank { null },
            orderType = orderType
        )
        orderDao.insertOrder(order)
        orderDao.insertLines(
            lines.map {
                val lineDiscount = appliedItemDiscount?.takeIf { selection ->
                    selection.scope == "item" && selection.cartLineId == it.id
                }
                OrderLine(
                    orderId = orderId,
                    itemId = it.item.id,
                    name = it.item.name,
                    quantity = it.quantity,
                    unitPriceCents = it.unitPriceCents,
                    modifiers = it.modifierLabel,
                    notes = it.notes,
                    discountCategory = lineDiscount?.category,
                    discountCents = lineDiscount?.discountCents ?: 0
                )
            }
        )
        if (gcashPaymentAmount > 0) {
            orderDao.insertPayment(
                Payment(
                    orderId = orderId,
                    method = "Online",
                    amountCents = gcashPaymentAmount,
                    amountTenderedCents = gcashAmountCents,
                    changeCents = 0,
                    createdAt = now,
                    paymentCategory = PaymentCategories.ONLINE
                )
            )
        }
        if (cashPaymentAmount > 0 || (gcashPaymentAmount == 0 && cashAmountCents > 0)) {
            orderDao.insertPayment(
                Payment(
                    orderId = orderId,
                    method = "Cash",
                    amountCents = cashPaymentAmount,
                    amountTenderedCents = cashAmountCents,
                    changeCents = cashChange,
                    createdAt = now,
                    paymentCategory = PaymentCategories.CASH
                )
            )
        }
        orderDao.insertReceipt(
            Receipt(
                orderId = orderId,
                receiptNumber = orderId.take(8).uppercase(Locale.US),
            text = buildReceiptSplit(settings, order, lines, appliedItemDiscount, cashAmountCents, cashChange, gcashAmountCents, now, lineCharacters),
                createdAt = now
            )
        )
        inventoryRepository.deductFor(
            lines = lines,
            isDineIn = (orderType == "Dine-In")
        )
        syncManager?.let { sm ->
            CoroutineScope(Dispatchers.IO).launch {
                sm.syncNow()
            }
        }
        order
    }

    suspend fun receipt(orderId: String): Receipt? = withContext(Dispatchers.IO) {
        orderDao.receipt(orderId)
    }

    internal suspend fun voidOrder(orderId: String): OrderActionResult = withContext(Dispatchers.IO) {
        val result = database.withTransaction {
            val order = orderDao.orderNow(orderId)
                ?: return@withTransaction OrderActionResult(false, "Order was not found.")
            if (order.status != "paid") {
                return@withTransaction OrderActionResult(false, "Order is already ${order.status}.")
            }
            val originalShift = shiftDao.getShiftByIdNow(order.shiftId)
                ?: return@withTransaction OrderActionResult(false, "The order's shift was not found; nothing was changed.")

            val inventoryDao = database.inventoryDao()
            val now = System.currentTimeMillis()
            val orderPrefix = order.id.take(8).uppercase(Locale.US)
            val orderPayments = orderDao.paymentsForOrder(orderId)
            val structuredAddOns = inventoryDao.orderInventoryAddOnsForOrder(orderId)
            val trackedAdjustmentIds = structuredAddOns.mapNotNull { it.localAdjustmentId }.toSet()
            val legacyCandidates = orderDao.adjustmentsNow().filter { adjustment ->
                adjustment.id !in trackedAdjustmentIds &&
                    adjustment.deltaQuantity < 0 &&
                    legacyOrderAddOnPrefix(adjustment.reason) == orderPrefix
            }
            val prefixIsUnique = isLegacyOrderPrefixUnique(
                order.id,
                orderDao.ordersNow().map { it.id }
            )
            val warnings = mutableListOf<String>()

            orderDao.updateOrderStatus(orderId, "void", null)

            if (originalShift.closedAt != null) {
                val (dayStart, dayEnd) = manilaBusinessDayBounds()
                val activeShift = shiftDao.activeShiftNow(dayStart, dayEnd)
                if (activeShift != null) {
                    val cashAmountCents = orderPayments
                        .filter { it.method.lowercase(Locale.US) == "cash" }
                        .sumOf { it.amountCents }
                    if (cashAmountCents > 0) shiftDao.removeCash(activeShift.id, cashAmountCents)
                    orderDao.insertClosedShiftAdjustment(
                        ClosedShiftAdjustment(
                            currentShiftId = activeShift.id,
                            originalOrderId = orderId,
                            originalShiftId = order.shiftId,
                            amountCents = order.totalCents,
                            type = "void",
                            reason = "",
                            staffId = order.employeeId,
                            createdAt = now
                        )
                    )
                }
            }

            val recipesByItem = inventoryDao.recipes().groupBy { it.itemId }
            val modifierRecipesByOption = inventoryDao.modifierRecipesNow().groupBy { it.optionId }
            val optionByName = menuDao.modifierOptionsNow().associateBy { it.name.lowercase(Locale.US) }
            val takeoutOnlyIngredientIds = if (order.orderType == "Dine-In") {
                inventoryDao.takeoutOnlyIngredientIds().toSet()
            } else {
                emptySet()
            }
            val isComplimentary = orderPayments.any { it.method.equals("Complimentary", ignoreCase = true) }
            val complimentaryExclusions = if (isComplimentary) {
                menuDao.itemsNow().associate { item ->
                    item.id to item.complementaryExclusions.split(",").filter { it.isNotBlank() }.toSet()
                }
            } else {
                emptyMap()
            }

            suspend fun restoreIngredient(ingredientId: String, quantity: Double, adjustmentReason: String) {
                if (quantity <= 0) return
                inventoryDao.adjustQuantity(ingredientId, quantity)
                inventoryDao.insertAdjustment(
                    InventoryAdjustment(
                        ingredientId = ingredientId,
                        deltaQuantity = quantity,
                        reason = adjustmentReason,
                        createdAt = now
                    )
                )
            }

            orderDao.orderLinesForOrder(orderId).forEach { line ->
                val baseRecipe = recipesByItem[line.itemId].orEmpty()
                val activeOptions = line.modifiers.split(",")
                    .map { it.trim().lowercase(Locale.US) }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { optionByName[it] }
                val modifierDeductions = activeOptions.flatMap { modifierRecipesByOption[it.id].orEmpty() }
                val replacedIngredientIds = modifierDeductions.mapNotNull { it.replacesIngredientId }.toSet()
                val otherExclusions = complimentaryExclusions[line.itemId].orEmpty()

                baseRecipe.forEach { recipe ->
                    if (recipe.ingredientId !in replacedIngredientIds &&
                        !isExcludedFromVoidRestoration(
                            recipe.ingredientId,
                            order.orderType,
                            takeoutOnlyIngredientIds,
                            otherExclusions
                        )
                    ) {
                        restoreIngredient(
                            recipe.ingredientId,
                            recipe.quantityUsed * line.quantity,
                            "Void order: $orderPrefix"
                        )
                    }
                }

                modifierDeductions.forEach { modifierRecipe ->
                    if (!isExcludedFromVoidRestoration(
                            modifierRecipe.ingredientId,
                            order.orderType,
                            takeoutOnlyIngredientIds,
                            otherExclusions
                        )
                    ) {
                        val quantityPerItem = if (modifierRecipe.replacesIngredientId != null) {
                            baseRecipe.firstOrNull { it.ingredientId == modifierRecipe.replacesIngredientId }
                                ?.quantityUsed ?: modifierRecipe.quantityUsed
                        } else {
                            modifierRecipe.quantityUsed
                        }
                        restoreIngredient(
                            modifierRecipe.ingredientId,
                            quantityPerItem * line.quantity,
                            "Void order (modifier): $orderPrefix"
                        )
                    }
                }
            }

            unrestoredOrderAddOns(structuredAddOns).forEach { addOn ->
                if (inventoryDao.markOrderInventoryAddOnRestored(addOn.id, now) == 1) {
                    restoreIngredient(
                        addOn.ingredientId,
                        addOn.quantity,
                        "Void post-checkout add-on: $orderPrefix"
                    )
                }
            }

            if (legacyCandidates.isNotEmpty()) {
                if (prefixIsUnique) {
                    legacyCandidates.forEach { adjustment ->
                        restoreIngredient(
                            adjustment.ingredientId,
                            -adjustment.deltaQuantity,
                            "Void legacy post-checkout add-on: $orderPrefix"
                        )
                    }
                } else {
                    warnings += "Some older add-ons could not be matched safely. Adjust their stock manually."
                }
            }

            OrderActionResult(true, "Order $orderPrefix voided.", warnings)
        }
        if (result.changed) triggerSync()
        result
    }

    internal suspend fun refundOrder(orderId: String): OrderActionResult = withContext(Dispatchers.IO) {
        val result = database.withTransaction {
            val order = orderDao.orderNow(orderId)
                ?: return@withTransaction OrderActionResult(false, "Order was not found.")
            if (order.status != "paid") {
                return@withTransaction OrderActionResult(false, "Order is already ${order.status}.")
            }
            val originalShift = shiftDao.getShiftByIdNow(order.shiftId)
                ?: return@withTransaction OrderActionResult(false, "The order's shift was not found; nothing was changed.")

            val now = System.currentTimeMillis()
            orderDao.updateOrderStatus(orderId, "refunded", null)
            if (originalShift.closedAt != null) {
                val (dayStart, dayEnd) = manilaBusinessDayBounds()
                val activeShift = shiftDao.activeShiftNow(dayStart, dayEnd)
                if (activeShift != null) {
                    val cashAmountCents = orderDao.paymentsForOrder(orderId)
                        .filter { it.method.lowercase(Locale.US) == "cash" }
                        .sumOf { it.amountCents }
                    if (cashAmountCents > 0) shiftDao.removeCash(activeShift.id, cashAmountCents)
                    orderDao.insertClosedShiftAdjustment(
                        ClosedShiftAdjustment(
                            currentShiftId = activeShift.id,
                            originalOrderId = orderId,
                            originalShiftId = order.shiftId,
                            amountCents = order.totalCents,
                            type = "refund",
                            reason = "",
                            staffId = order.employeeId,
                            createdAt = now
                        )
                    )
                }
            }
            OrderActionResult(true, "Order ${orderId.take(8).uppercase(Locale.US)} refunded.")
        }
        if (result.changed) triggerSync()
        result
    }

    private fun triggerSync() {
        syncManager?.let { manager ->
            CoroutineScope(Dispatchers.IO).launch { manager.syncNow() }
        }
    }

    companion object {
        fun calculateTotals(
            lines: List<CartLine>,
            discountCents: Int,
            tipCents: Int,
            taxRatePercent: Double
        ): CartTotals {
            val subtotal = lines.sumOf { it.lineTotalCents }
            val discount = discountCents.coerceAtMost(subtotal)
            val netTotal = (subtotal - discount + tipCents).coerceAtLeast(0)
            return CartTotals(subtotal, discount, 0, tipCents, netTotal)
        }

        private fun buildReceipt(
            settings: StoreSettings,
            order: PosOrder,
            lines: List<CartLine>,
            itemDiscount: ItemDiscountSelection?,
            paymentMethod: String,
            amountTenderedCents: Int,
            changeCents: Int,
            W: Int
        ): String {
            val div = "-".repeat(W)

            fun center(text: String): String {
                val pad = ((W - text.length) / 2).coerceAtLeast(0)
                return " ".repeat(pad) + text
            }

            fun row(left: String, right: String): String {
                val space = (W - left.length - right.length).coerceAtLeast(1)
                return left + " ".repeat(space) + right
            }

            val sdf = java.text.SimpleDateFormat("MM/dd/yyyy  h:mm a", Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Manila")
            val dateStr = sdf.format(java.util.Date(order.createdAt))

            val sb = StringBuilder()
            sb.appendLine(center(settings.storeName))
            sb.appendLine(center("Receipt"))
            sb.appendLine(center("#${order.id.take(8).uppercase(Locale.US)}"))
            sb.appendLine(center(dateStr))
            sb.appendLine(center(order.orderType.uppercase(Locale.US)))
            if (!order.customerName.isNullOrBlank()) {
                sb.appendLine(center("Cust: ${order.customerName}"))
            }
            if (!order.tableNumber.isNullOrBlank()) {
                sb.appendLine(center("Table: ${order.tableNumber}"))
            }
            sb.appendLine(div)
            // Items
            for (line in lines) {
                val price = formatPeso(line.lineTotalCents)
                formatReceiptItemLines(line.quantity, line.item.name, price, W)
                    .forEach(sb::appendLine)
                if (line.modifierLabel.isNotBlank()) {
                    sb.appendLine("  + ${line.modifierLabel}")
                }
                if (itemDiscount?.cartLineId == line.id && itemDiscount.discountCents > 0) {
                    sb.appendLine(row("  ${itemDiscount.category} discount", "-${formatPeso(itemDiscount.discountCents)}"))
                }
            }
            sb.appendLine(div)
            // Totals breakdown
            if (order.discountCents > 0) {
                sb.appendLine(row("Subtotal", formatPeso(order.subtotalCents)))
                val discountLabel = order.discountCategory?.let { "$it discount" } ?: "Discount"
                sb.appendLine(row(discountLabel, "-${formatPeso(order.discountCents)}"))
                order.discountPercent?.let {
                    sb.appendLine("  ${formatDiscountPercent(it)}% · ${order.discountScope ?: "item"}")
                }
                order.discountReference?.let { sb.appendLine("  Reference: $it") }
            }
            sb.appendLine(row("TOTAL", formatPeso(order.totalCents)))
            sb.appendLine(div)
            sb.appendLine(row("Paid by", paymentMethod))
            sb.appendLine(row("Amount paid", formatPeso(amountTenderedCents)))
            sb.appendLine(row("Change", formatPeso(changeCents)))
            sb.appendLine(div)
            sb.appendLine(center(settings.receiptFooter))
            return sb.toString().trimEnd()
        }

        private fun buildReceiptSplit(
            settings: StoreSettings,
            order: PosOrder,
            lines: List<CartLine>,
            itemDiscount: ItemDiscountSelection?,
            cashTenderedCents: Int,
            cashChangeCents: Int,
            gcashTenderedCents: Int,
            now: Long,
            W: Int
        ): String {
            val div = "-".repeat(W)

            fun center(text: String): String {
                val pad = ((W - text.length) / 2).coerceAtLeast(0)
                return " ".repeat(pad) + text
            }

            fun row(left: String, right: String): String {
                val space = (W - left.length - right.length).coerceAtLeast(1)
                return left + " ".repeat(space) + right
            }

            val sdf = java.text.SimpleDateFormat("MM/dd/yyyy  h:mm a", Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Manila")
            val dateStr = sdf.format(java.util.Date(now))

            val sb = StringBuilder()
            sb.appendLine(center(settings.storeName))
            sb.appendLine(center("Receipt"))
            sb.appendLine(center("#${order.id.take(8).uppercase(Locale.US)}"))
            sb.appendLine(center(dateStr))
            sb.appendLine(center(order.orderType.uppercase(Locale.US)))
            if (!order.customerName.isNullOrBlank()) {
                sb.appendLine(center("Cust: ${order.customerName}"))
            }
            if (!order.tableNumber.isNullOrBlank()) {
                sb.appendLine(center("Table: ${order.tableNumber}"))
            }
            sb.appendLine(div)
            // Items
            for (line in lines) {
                val price = formatPeso(line.lineTotalCents)
                formatReceiptItemLines(line.quantity, line.item.name, price, W)
                    .forEach(sb::appendLine)
                if (line.modifierLabel.isNotBlank()) {
                    sb.appendLine("  + ${line.modifierLabel}")
                }
                if (itemDiscount?.cartLineId == line.id && itemDiscount.discountCents > 0) {
                    sb.appendLine(row("  ${itemDiscount.category} discount", "-${formatPeso(itemDiscount.discountCents)}"))
                }
            }
            sb.appendLine(div)
            if (order.discountCents > 0) {
                sb.appendLine(row("Subtotal", formatPeso(order.subtotalCents)))
                val discountLabel = order.discountCategory?.let { "$it discount" } ?: "Discount"
                sb.appendLine(row(discountLabel, "-${formatPeso(order.discountCents)}"))
                order.discountPercent?.let {
                    sb.appendLine("  ${formatDiscountPercent(it)}% · ${order.discountScope ?: "item"}")
                }
                order.discountReference?.let { sb.appendLine("  Reference: $it") }
            }
            sb.appendLine(row("TOTAL", formatPeso(order.totalCents)))
            sb.appendLine(div)
            sb.appendLine(center("SPLIT PAYMENT"))
            if (gcashTenderedCents > 0) {
                sb.appendLine(row("Online Paid", formatPeso(gcashTenderedCents)))
            }
            if (cashTenderedCents > 0) {
                sb.appendLine(row("Cash Paid", formatPeso(cashTenderedCents)))
                sb.appendLine(row("Cash Change", formatPeso(cashChangeCents)))
            }
            sb.appendLine(div)
            sb.appendLine(center(settings.receiptFooter))
            return sb.toString().trimEnd()
        }

        private fun formatPeso(cents: Int): String = "₱" + String.format(Locale.US, "%,.2f", cents / 100.0)
        private fun formatDiscountPercent(percent: Double): String =
            if (percent % 1.0 == 0.0) percent.toInt().toString()
            else String.format(Locale.US, "%.2f", percent).trimEnd('0').trimEnd('.')
    }
}

class ReportsRepository(
    private val orderRepository: OrderRepository,
    private val inventoryRepository: InventoryRepository,
    private val orderDao: OrderDao,
    private val employeeDao: EmployeeDao
) {
    val lowStock: Flow<List<Ingredient>> = inventoryRepository.lowStock

    fun getClosedShiftAdjustmentsForShift(shiftId: Long): Flow<List<ClosedShiftAdjustment>> {
        return orderDao.closedShiftAdjustments().map { list ->
            list.filter { it.currentShiftId == shiftId }
        }
    }

    fun reportFlow(
        dateRange: ReportDateRange,
        customStart: Long? = null,
        customEnd: Long? = null,
        cashierEmployeeId: String? = null
    ): Flow<DailyReport> {
        val customWindow = if (dateRange == ReportDateRange.CUSTOM) {
            requireValidCustomReportRange(customStart, customEnd)
        } else {
            null
        }
        return combine(
        orderRepository.orders,
        orderRepository.payments,
        orderDao.orderLines(),
        orderDao.allShifts(),
        employeeDao.allEmployees(),
        inventoryRepository.ingredients,
        orderDao.adjustments(),
        orderDao.closedShiftAdjustments()
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val orders   = args[0] as List<PosOrder>
        @Suppress("UNCHECKED_CAST")
        val payments = args[1] as List<Payment>
        @Suppress("UNCHECKED_CAST")
        val lines    = args[2] as List<OrderLine>
        @Suppress("UNCHECKED_CAST")
        val shifts   = args[3] as List<Shift>
        @Suppress("UNCHECKED_CAST")
        val employees = args[4] as List<Employee>
        @Suppress("UNCHECKED_CAST")
        val ingredients = args[5] as List<Ingredient>
        @Suppress("UNCHECKED_CAST")
        val adjustments = args[6] as List<InventoryAdjustment>
        @Suppress("UNCHECKED_CAST")
        val closedShiftAdjustments = args[7] as List<ClosedShiftAdjustment>

        // Date window
        val now = System.currentTimeMillis()
        var windowEnd = Long.MAX_VALUE
        val windowStart: Long = when (dateRange) {
            ReportDateRange.TODAY -> {
                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Manila"))
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            ReportDateRange.MONTH -> now - 30L * 24 * 60 * 60 * 1000
            ReportDateRange.ALL   -> 0L
            ReportDateRange.CUSTOM -> {
                windowEnd = checkNotNull(customWindow).second
                customWindow.first
            }
        }

        val complimentaryOrderIds = payments.filter { it.method.lowercase(Locale.US) == "complimentary" }.map { it.orderId }.toSet()
        fun reportTime(order: PosOrder): Long = order.paidAt ?: order.createdAt
        val orderMap = orders.associateBy { it.id }
        val selectedCashierShiftIds = cashierEmployeeId?.let { employeeId ->
            shifts.filter { it.employeeId == employeeId }.map { it.id }.toSet()
        }
        fun matchesSelectedCashier(shiftId: Long): Boolean =
            selectedCashierShiftIds == null || shiftId in selectedCashierShiftIds

        val paid = orders.filter { o ->
            o.status == "paid" &&
                reportTime(o) in windowStart..windowEnd &&
                matchesSelectedCashier(o.shiftId)
        }
        val paidNonComplimentary = paid.filter { it.id !in complimentaryOrderIds }
        val paymentsInWindow = payments.filter { p ->
            val order = orderMap[p.orderId]
            p.createdAt in windowStart..windowEnd && order?.status == "paid"
        }
        val filteredPayments = paymentsInWindow.filter { payment ->
            orderMap[payment.orderId]?.shiftId?.let(::matchesSelectedCashier) == true
        }
        val orderCount = paidNonComplimentary.size
        val grossSales = paidNonComplimentary.sumOf { it.subtotalCents }
        val discounts = paidNonComplimentary.sumOf { it.discountCents }
        val netSales = paidNonComplimentary.sumOf { it.totalCents }
        val avgOrderValue = if (orderCount > 0) netSales / orderCount else 0

        val paymentTotals = filteredPayments.groupBy { it.method }
            .mapValues { row -> row.value.sumOf { it.amountCents } }
        val onlinePaymentSales = filteredPayments
            .filter { it.paymentCategory == PaymentCategories.ONLINE }
            .sumOf { it.amountCents }

        val paidOrderIds = paid.map { it.id }.toSet()
        val paidLines = lines.filter { it.orderId in paidOrderIds }
        val topItems = paidLines
            .groupBy { it.name }
            .map { (name, ls) ->
                val nonComplimentaryLines = ls.filter { it.orderId !in complimentaryOrderIds }
                TopSellingItem(
                    name = name,
                    qtySold = nonComplimentaryLines.sumOf { it.quantity },
                    revenueCents = nonComplimentaryLines.sumOf { it.quantity * it.unitPriceCents }
                )
            }
            .filter { it.qtySold > 0 }
            .sortedByDescending { it.qtySold }

        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Manila"))
        val hourlySales = paidNonComplimentary
            .groupBy { order ->
                cal.timeInMillis = order.paidAt ?: order.createdAt
                cal.get(java.util.Calendar.HOUR_OF_DAY)
            }
            .mapValues { (_, os) -> os.sumOf { it.totalCents } }

        fun shiftBusinessDay(shift: Shift): Long {
            cal.timeInMillis = shift.openedAt
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        val paidOrderIdsInWindow = paid.map { it.id }.toSet()
        val shiftIdsWithPaymentsInWindow = paymentsInWindow
            .mapNotNull { payment -> orderMap[payment.orderId]?.shiftId }
            .toSet()
        val allShiftsInWindow = if (dateRange == ReportDateRange.ALL) {
            shifts
        } else {
            shifts.filter { shift ->
                val overlapsWindow = shift.openedAt <= windowEnd &&
                    (shift.closedAt ?: Long.MAX_VALUE) >= windowStart
                overlapsWindow || shift.id in shiftIdsWithPaymentsInWindow
            }
        }
        val filteredShifts = if (cashierEmployeeId != null) {
            allShiftsInWindow.filter { it.employeeId == cashierEmployeeId }
        } else {
            allShiftsInWindow
        }
        val filteredShiftIds = filteredShifts.map { it.id }.toSet()

        val paymentsByShift = filteredPayments.filter { p ->
            val order = orderMap[p.orderId]
            p.paymentCategory == PaymentCategories.CASH && order?.status == "paid" && order.id in paidOrderIdsInWindow
        }.groupBy { pay -> orderMap[pay.orderId]?.shiftId }

        var expected = 0
        var totalEnding = 0
        var totalStarting = 0
        var totalSales = 0
        var totalAdded = 0
        var totalRemoved = 0

        if (cashierEmployeeId != null) {
            filteredShifts.forEach { shift ->
                val cashSales = paymentsByShift[shift.id]?.sumOf { it.amountCents } ?: 0
                val shiftExpected = shift.startingCashCents + cashSales +
                    shift.cashAddedCents - shift.cashRemovedCents
                val shiftActual = shift.endingCashCents ?: shiftExpected

                expected += shiftExpected
                totalEnding += shiftActual
                totalStarting += shift.startingCashCents
                totalSales += cashSales
                totalAdded += shift.cashAddedCents
                totalRemoved += shift.cashRemovedCents
            }
        } else {
            filteredShifts
                .groupBy { shiftBusinessDay(it) }
                .values
                .forEach { dayShifts ->
                    val sortedShifts = dayShifts.sortedBy { it.openedAt }

                    var startingCash = 0
                    sortedShifts.forEachIndexed { index, shift ->
                        if (index == 0) {
                            startingCash += shift.startingCashCents
                        } else {
                            val prevShift = sortedShifts[index - 1]
                            val prevEnding = prevShift.endingCashCents ?: (prevShift.startingCashCents + (paymentsByShift[prevShift.id]?.sumOf { it.amountCents } ?: 0) + prevShift.cashAddedCents - prevShift.cashRemovedCents)
                            startingCash += (shift.startingCashCents - prevEnding)
                        }
                    }

                    val cashSales = sortedShifts.sumOf { s -> paymentsByShift[s.id]?.sumOf { it.amountCents } ?: 0 }
                    val cashAdded = sortedShifts.sumOf { it.cashAddedCents }
                    val cashRemoved = sortedShifts.sumOf { it.cashRemovedCents }
                    val dayExpected = startingCash + cashSales + cashAdded - cashRemoved

                    val latestShift = sortedShifts.lastOrNull()
                    val latestActual = latestShift?.let { s ->
                        s.endingCashCents ?: (s.startingCashCents + (paymentsByShift[s.id]?.sumOf { it.amountCents } ?: 0) + s.cashAddedCents - s.cashRemovedCents)
                    } ?: dayExpected

                    expected += dayExpected
                    totalEnding += latestActual
                    totalStarting += startingCash
                    totalSales += cashSales
                    totalAdded += cashAdded
                    totalRemoved += cashRemoved
                }
        }

        val diff = totalEnding - expected

        val empMap = employees.associateBy { it.id }
        val employeeBreakdowns = paid
            .groupBy { it.employeeId }
            .map { (empId, os) ->
                val nonComp = os.filter { it.id !in complimentaryOrderIds }
                EmployeeBreakdown(
                    employeeId = empId,
                    name = empMap[empId]?.name ?: empId,
                    orderCount = nonComp.size,
                    salesCents = nonComp.sumOf { it.totalCents }
                )
            }
            .sortedByDescending { it.salesCents }

        // Ingredient usage from adjustments in window
        val filteredAdj = adjustments.filter { it.createdAt in windowStart..windowEnd }
        val adjByIngredient = filteredAdj.groupBy { it.ingredientId }
        val ingredientUsage = ingredients.map { ingredient ->
            val adjs = adjByIngredient[ingredient.id].orEmpty()
            val used = adjs.filter { it.deltaQuantity < 0 }.sumOf { -it.deltaQuantity }
            val restocked = adjs.filter { it.deltaQuantity > 0 }.sumOf { it.deltaQuantity }
            IngredientUsageSummary(
                ingredientId = ingredient.id,
                name = ingredient.name,
                unit = ingredient.unit,
                usedToday = used,
                restocked = restocked,
                endingStock = ingredient.quantityOnHand,
                isLow = ingredient.quantityOnHand <= ingredient.lowStockThreshold
            )
        }.sortedBy { it.name }

        DailyReport(
            orderCount = orderCount,
            grossSalesCents = grossSales,
            discountsCents = discounts,
            netSalesCents = netSales,
            avgOrderValueCents = avgOrderValue,
            paymentTotals = paymentTotals,
            onlinePaymentSalesCents = onlinePaymentSales,
            topItems = topItems,
            hourlySales = hourlySales,
            cashDrawerExpected = expected,
            cashDrawerActual = totalEnding,
            cashDrawerDifference = diff,
            employeeBreakdowns = employeeBreakdowns,
            ingredientUsage = ingredientUsage,
            cashDrawerStarting = totalStarting,
            cashDrawerSales = totalSales,
            cashDrawerAdded = totalAdded,
            cashDrawerRemoved = totalRemoved,
            closedShiftAdjustments = closedShiftAdjustments.filter {
                it.createdAt in windowStart..windowEnd &&
                    (cashierEmployeeId == null || it.currentShiftId in filteredShiftIds)
            },
            shifts = allShiftsInWindow
        )
        }.flowOn(Dispatchers.Default)
    }

    // Keep a default all-time flow for backward compat
    val dailyReport: Flow<DailyReport> get() = reportFlow(ReportDateRange.TODAY)
}
