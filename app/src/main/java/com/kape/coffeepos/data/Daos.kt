package com.kape.coffeepos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MenuDao {
    @Query("SELECT * FROM MenuCategory ORDER BY sortOrder")
    fun categories(): Flow<List<MenuCategory>>

    @Query("SELECT * FROM MenuItem WHERE active = 1 ORDER BY name")
    fun items(): Flow<List<MenuItem>>

    @Query("SELECT * FROM ModifierGroup ORDER BY name")
    fun modifierGroups(): Flow<List<ModifierGroup>>

    @Query("SELECT * FROM ModifierOption ORDER BY name")
    fun modifierOptions(): Flow<List<ModifierOption>>

    @Query("SELECT * FROM ModifierOption")
    suspend fun modifierOptionsNow(): List<ModifierOption>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(rows: List<MenuCategory>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategory(row: MenuCategory)

    @Query("DELETE FROM MenuCategory WHERE id = :categoryId")
    suspend fun deleteCategory(categoryId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(rows: List<MenuItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(row: MenuItem)

    @Query("DELETE FROM MenuItem WHERE id = :itemId")
    suspend fun deleteItem(itemId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroups(rows: List<ModifierGroup>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(row: ModifierGroup)

    @Query("DELETE FROM ModifierGroup WHERE id = :groupId")
    suspend fun deleteGroup(groupId: String)

    @Query("DELETE FROM ModifierOption WHERE groupId = :groupId")
    suspend fun deleteOptionsForGroup(groupId: String)

    @Query("SELECT * FROM ModifierOption WHERE groupId = :groupId")
    suspend fun optionsForGroupNow(groupId: String): List<ModifierOption>

    @Query("DELETE FROM MenuItemModifierGroup WHERE groupId = :groupId")
    suspend fun deleteItemGroupsForGroup(groupId: String)

    @Query("SELECT * FROM MenuItemModifierGroup WHERE groupId = :groupId")
    suspend fun itemGroupsForGroupNow(groupId: String): List<MenuItemModifierGroup>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOptions(rows: List<ModifierOption>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOption(row: ModifierOption)

    @Query("DELETE FROM ModifierOption WHERE id = :optionId")
    suspend fun deleteOption(optionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItemGroups(rows: List<MenuItemModifierGroup>)

    @Query("SELECT * FROM MenuItemModifierGroup")
    fun itemGroups(): Flow<List<MenuItemModifierGroup>>

    @Query("SELECT groupId FROM MenuItemModifierGroup WHERE itemId = :itemId")
    suspend fun itemGroupIds(itemId: String): List<String>

    @Query("DELETE FROM MenuItemModifierGroup WHERE itemId = :itemId")
    suspend fun deleteItemGroups(itemId: String)

    @Query("SELECT * FROM MenuItemModifierGroup WHERE itemId = :itemId")
    suspend fun itemGroupsForItemNow(itemId: String): List<MenuItemModifierGroup>

    @Query("DELETE FROM MenuItemModifierGroup WHERE itemId = :itemId AND groupId = :groupId")
    suspend fun deleteItemGroup(itemId: String, groupId: String)

    @Query("SELECT * FROM MenuCategory")
    suspend fun categoriesNow(): List<MenuCategory>

    @Query("SELECT * FROM MenuItem")
    suspend fun itemsNow(): List<MenuItem>

    @Query("SELECT * FROM ModifierGroup")
    suspend fun modifierGroupsNow(): List<ModifierGroup>

    @Query("SELECT * FROM MenuItemModifierGroup")
    suspend fun itemGroupsNow(): List<MenuItemModifierGroup>
}

@Dao
interface InventoryDao {
    @Query("SELECT * FROM Ingredient ORDER BY name")
    fun ingredients(): Flow<List<Ingredient>>

    @Query("SELECT * FROM Ingredient")
    suspend fun ingredientsNow(): List<Ingredient>

    @Query("SELECT * FROM Ingredient WHERE id = :ingredientId LIMIT 1")
    suspend fun ingredientById(ingredientId: String): Ingredient?

    @Query("SELECT * FROM Ingredient WHERE quantityOnHand <= lowStockThreshold ORDER BY name")
    fun lowStock(): Flow<List<Ingredient>>

    @Query("SELECT id FROM Ingredient WHERE takeoutOnly = 1")
    suspend fun takeoutOnlyIngredientIds(): List<String>

    @Query("SELECT * FROM RecipeIngredient")
    suspend fun recipes(): List<RecipeIngredient>

    @Query("SELECT * FROM RecipeIngredient WHERE itemId = :itemId")
    suspend fun recipesForItem(itemId: String): List<RecipeIngredient>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIngredients(rows: List<Ingredient>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecipes(rows: List<RecipeIngredient>)

    @Query("DELETE FROM RecipeIngredient WHERE itemId = :itemId")
    suspend fun deleteRecipesForItem(itemId: String)

    @Query("SELECT * FROM RecipeIngredient WHERE ingredientId = :ingredientId")
    suspend fun recipesForIngredient(ingredientId: String): List<RecipeIngredient>

    @Query("DELETE FROM RecipeIngredient WHERE itemId = :itemId AND ingredientId = :ingredientId")
    suspend fun deleteRecipe(itemId: String, ingredientId: String)

    @Query("UPDATE Ingredient SET quantityOnHand = quantityOnHand + :delta WHERE id = :ingredientId")
    suspend fun adjustQuantity(ingredientId: String, delta: Double)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIngredient(row: Ingredient)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIngredient(row: Ingredient)

    @Query("DELETE FROM Ingredient WHERE id = :ingredientId")
    suspend fun deleteIngredient(ingredientId: String)

    @Insert
    suspend fun insertAdjustment(row: InventoryAdjustment): Long

    @Query("SELECT * FROM InventoryAdjustment WHERE synced = 0 ORDER BY id")
    suspend fun unsyncedAdjustmentsNow(): List<InventoryAdjustment>

    @Query("UPDATE InventoryAdjustment SET synced = 1 WHERE eventId = :eventId")
    suspend fun markAdjustmentSynced(eventId: String)

    @Query("UPDATE Ingredient SET quantityOnHand = :quantity WHERE id = :ingredientId")
    suspend fun setQuantity(ingredientId: String, quantity: Double)

    @Query("UPDATE Ingredient SET quantityOnHand = 0")
    suspend fun resetAllQuantities()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrderInventoryAddOn(row: OrderInventoryAddOn)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrderInventoryAddOns(rows: List<OrderInventoryAddOn>)

    @Query("SELECT * FROM OrderInventoryAddOn")
    suspend fun orderInventoryAddOnsNow(): List<OrderInventoryAddOn>

    @Query("DELETE FROM OrderInventoryAddOn")
    suspend fun clearOrderInventoryAddOns()

    @Query("SELECT * FROM OrderInventoryAddOn WHERE orderId = :orderId")
    suspend fun orderInventoryAddOnsForOrder(orderId: String): List<OrderInventoryAddOn>

    @Query("UPDATE OrderInventoryAddOn SET restoredAt = :restoredAt, updatedAt = :restoredAt WHERE id = :id AND restoredAt IS NULL")
    suspend fun markOrderInventoryAddOnRestored(id: String, restoredAt: Long): Int

    @Transaction
    suspend fun recordOrderInventoryAddOn(
        row: OrderInventoryAddOn,
        adjustment: InventoryAdjustment
    ) {
        adjustQuantity(row.ingredientId, -row.quantity)
        val adjustmentId = insertAdjustment(adjustment)
        upsertOrderInventoryAddOn(row.copy(localAdjustmentId = adjustmentId))
    }

    @Query("SELECT * FROM ModifierRecipeIngredient")
    fun modifierRecipesFlow(): Flow<List<ModifierRecipeIngredient>>

    @Query("SELECT * FROM ModifierRecipeIngredient")
    suspend fun modifierRecipesNow(): List<ModifierRecipeIngredient>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModifierRecipes(rows: List<ModifierRecipeIngredient>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModifierRecipe(row: ModifierRecipeIngredient)

    @Query("DELETE FROM ModifierRecipeIngredient WHERE optionId = :optionId")
    suspend fun deleteModifierRecipe(optionId: String)

    @Query("SELECT * FROM ModifierRecipeIngredient WHERE optionId = :optionId")
    suspend fun modifierRecipesForOption(optionId: String): List<ModifierRecipeIngredient>

    @Query("SELECT * FROM ModifierRecipeIngredient WHERE ingredientId = :ingredientId")
    suspend fun modifierRecipesForIngredient(ingredientId: String): List<ModifierRecipeIngredient>

    @Query("DELETE FROM ModifierRecipeIngredient WHERE optionId = :optionId AND ingredientId = :ingredientId")
    suspend fun deleteModifierRecipe(optionId: String, ingredientId: String)
}

@Dao
interface EmployeeDao {
    @Query("SELECT * FROM Employee WHERE active = 1 ORDER BY name")
    fun employees(): Flow<List<Employee>>

    @Query("SELECT * FROM Employee ORDER BY name")
    fun allEmployees(): Flow<List<Employee>>

    @Query("SELECT * FROM Employee WHERE pin = :pin AND active = 1 LIMIT 1")
    suspend fun employeeByPin(pin: String): Employee?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmployees(rows: List<Employee>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmployee(row: Employee)

    @Query("SELECT * FROM Employee")
    suspend fun employeesNow(): List<Employee>
}

@Dao
interface ShiftDao {
    @Query("SELECT * FROM Shift WHERE closedAt IS NULL AND openedAt >= :dayStart AND openedAt < :dayEnd ORDER BY openedAt DESC LIMIT 1")
    fun activeShift(dayStart: Long, dayEnd: Long): Flow<Shift?>

    @Query("SELECT * FROM Shift WHERE closedAt IS NULL AND openedAt >= :dayStart AND openedAt < :dayEnd ORDER BY openedAt DESC LIMIT 1")
    suspend fun activeShiftNow(dayStart: Long, dayEnd: Long): Shift?

    @Query("SELECT * FROM Shift WHERE id = :id AND closedAt IS NULL LIMIT 1")
    fun activeShiftById(id: Long): Flow<Shift?>

    /** Returns any shift (open or closed) whose openedAt falls within [dayStart, dayEnd). */
    @Query("SELECT * FROM Shift WHERE openedAt >= :dayStart AND openedAt < :dayEnd LIMIT 1")
    suspend fun shiftForDay(dayStart: Long, dayEnd: Long): Shift?

    @Insert
    suspend fun openShift(row: Shift): Long

    @Query("UPDATE Shift SET closedAt = :closedAt, endingCashCents = :endingCashCents WHERE id = :id")
    suspend fun closeShift(id: Long, closedAt: Long, endingCashCents: Int)

    @Query("UPDATE Shift SET cashAddedCents = cashAddedCents + :amountCents WHERE id = :id")
    suspend fun addCash(id: Long, amountCents: Int)

    @Query("UPDATE Shift SET cashRemovedCents = cashRemovedCents + :amountCents WHERE id = :id")
    suspend fun removeCash(id: Long, amountCents: Int)

    @Query("SELECT * FROM Shift WHERE id = :id LIMIT 1")
    suspend fun getShiftByIdNow(id: Long): Shift?

    @Query("SELECT COALESCE(SUM(p.amountCents), 0) FROM Payment p INNER JOIN PosOrder o ON p.orderId = o.id WHERE o.shiftId = :shiftId AND p.paymentCategory = 'CASH' AND o.status = 'paid'")
    fun getShiftCashSales(shiftId: Long): kotlinx.coroutines.flow.Flow<Int>

    @Query("SELECT COALESCE(SUM(p.amountCents), 0) FROM Payment p INNER JOIN PosOrder o ON p.orderId = o.id WHERE o.shiftId = :shiftId AND p.paymentCategory = 'ONLINE' AND o.status = 'paid'")
    fun getShiftGCashSales(shiftId: Long): kotlinx.coroutines.flow.Flow<Int>

    @Query("SELECT * FROM Shift")
    suspend fun allShiftsNow(): List<Shift>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateShift(row: Shift)
}

@Dao
interface OrderDao {
    @Query("SELECT * FROM PosOrder ORDER BY createdAt DESC")
    fun orders(): Flow<List<PosOrder>>

    @Query("SELECT * FROM Payment ORDER BY createdAt DESC")
    fun payments(): Flow<List<Payment>>

    @Query("SELECT * FROM OrderLine")
    fun orderLines(): Flow<List<OrderLine>>

    @Query("SELECT * FROM OrderLine WHERE orderId = :orderId")
    suspend fun orderLinesForOrder(orderId: String): List<OrderLine>

    @Query("SELECT * FROM Shift ORDER BY openedAt DESC")
    fun allShifts(): Flow<List<Shift>>

    @Query("SELECT * FROM InventoryAdjustment ORDER BY createdAt DESC")
    fun adjustments(): Flow<List<InventoryAdjustment>>

    @Query("SELECT * FROM Receipt WHERE orderId = :orderId LIMIT 1")
    suspend fun receipt(orderId: String): Receipt?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(row: PosOrder)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLines(rows: List<OrderLine>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayment(row: Payment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceipt(row: Receipt)

    @Query("UPDATE PosOrder SET status = :status, voidReason = :voidReason WHERE id = :orderId")
    suspend fun updateOrderStatus(orderId: String, status: String, voidReason: String?)

    @Query("DELETE FROM OrderLine WHERE orderId = :orderId")
    suspend fun deleteOrderLinesForOrder(orderId: String)

    @Query("DELETE FROM Payment WHERE orderId = :orderId")
    suspend fun deletePaymentsForOrder(orderId: String)

    @Query("SELECT * FROM PosOrder")
    suspend fun ordersNow(): List<PosOrder>

    @Query("SELECT * FROM OrderLine")
    suspend fun orderLinesNow(): List<OrderLine>

    @Query("SELECT * FROM Payment")
    suspend fun paymentsNow(): List<Payment>

    @Query("SELECT * FROM Payment WHERE orderId = :orderId ORDER BY id")
    suspend fun paymentsForOrder(orderId: String): List<Payment>

    @Query("SELECT * FROM Receipt")
    suspend fun receiptsNow(): List<Receipt>

    @Query("SELECT * FROM InventoryAdjustment")
    suspend fun adjustmentsNow(): List<InventoryAdjustment>

    @Query("SELECT * FROM PosOrder WHERE id = :orderId LIMIT 1")
    suspend fun orderNow(orderId: String): PosOrder?

    @Query("DELETE FROM PosOrder")
    suspend fun clearOrders()

    @Query("DELETE FROM OrderLine")
    suspend fun clearOrderLines()

    @Query("DELETE FROM Payment")
    suspend fun clearPayments()

    @Query("DELETE FROM Receipt")
    suspend fun clearReceipts()

    @Query("DELETE FROM Shift")
    suspend fun clearShifts()

    @Query("DELETE FROM InventoryAdjustment")
    suspend fun clearAdjustments()

    @Query("DELETE FROM StockSnapshot")
    suspend fun clearStockSnapshots()

    @Insert
    suspend fun insertClosedShiftAdjustment(row: ClosedShiftAdjustment)

    @Query("SELECT * FROM ClosedShiftAdjustment ORDER BY createdAt DESC")
    fun closedShiftAdjustments(): Flow<List<ClosedShiftAdjustment>>

    @Query("SELECT * FROM ClosedShiftAdjustment")
    suspend fun closedShiftAdjustmentsNow(): List<ClosedShiftAdjustment>

    @Query("DELETE FROM ClosedShiftAdjustment")
    suspend fun clearClosedShiftAdjustments()
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM StoreSettings WHERE id = 'store' LIMIT 1")
    fun settings(): Flow<StoreSettings?>

    @Query("SELECT * FROM StoreSettings WHERE id = 'store' LIMIT 1")
    suspend fun settingsNow(): StoreSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: StoreSettings)

    @Query("SELECT * FROM PaymentMethod ORDER BY isSystem DESC, name ASC")
    fun paymentMethodsFlow(): Flow<List<PaymentMethod>>

    @Query("SELECT * FROM PaymentMethod ORDER BY isSystem DESC, name ASC")
    suspend fun paymentMethodsNow(): List<PaymentMethod>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPaymentMethod(method: PaymentMethod)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPaymentMethods(methods: List<PaymentMethod>)

    @Query("DELETE FROM PaymentMethod WHERE id = :id")
    suspend fun deletePaymentMethod(id: String)
}

@Dao
interface SeedDao {
    @Query("SELECT COUNT(*) > 0 FROM MenuCategory")
    suspend fun hasSeedData(): Boolean
}

@Dao
interface StockSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshots(rows: List<StockSnapshot>)

    @Query("SELECT * FROM StockSnapshot WHERE shiftId = :shiftId")
    suspend fun snapshotsForShift(shiftId: Long): List<StockSnapshot>

    @Query("SELECT * FROM StockSnapshot")
    suspend fun snapshotsNow(): List<StockSnapshot>
}

@Dao
interface PendingDeleteDao {
    @Query("SELECT * FROM PendingDelete")
    suspend fun allNow(): List<PendingDelete>

    @Query("SELECT * FROM PendingDelete WHERE synced = 0")
    suspend fun unsyncedNow(): List<PendingDelete>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PendingDelete)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<PendingDelete>)

    @Query("UPDATE PendingDelete SET synced = 1 WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun markSynced(entityType: String, entityId: String)

    @Query("DELETE FROM PendingDelete WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun delete(entityType: String, entityId: String)
}
