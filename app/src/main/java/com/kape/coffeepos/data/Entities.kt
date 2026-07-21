package com.kape.coffeepos.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

object SyncEntityType {
    const val MENU_CATEGORY = "menu_category"
    const val MENU_ITEM = "menu_item"
    const val MODIFIER_GROUP = "modifier_group"
    const val MODIFIER_OPTION = "modifier_option"
    const val MENU_ITEM_MODIFIER_GROUP = "menu_item_modifier_group"
    const val INGREDIENT = "ingredient"
    const val RECIPE_INGREDIENT = "recipe_ingredient"
    const val MODIFIER_RECIPE_INGREDIENT = "modifier_recipe_ingredient"
    const val PAYMENT_METHOD = "payment_method"
}

object SyncEntityId {
    private const val SEP = "|"

    fun menuItemModifierGroup(itemId: String, groupId: String): String = "$itemId$SEP$groupId"
    fun recipeIngredient(itemId: String, ingredientId: String): String = "$itemId$SEP$ingredientId"
    fun modifierRecipeIngredient(optionId: String, ingredientId: String): String = "$optionId$SEP$ingredientId"

    fun splitComposite(value: String): Pair<String, String>? {
        val parts = value.split(SEP, limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else null
    }
}

@Entity
data class MenuCategory(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int
)

@Entity
data class MenuItem(
    @PrimaryKey val id: String,
    val categoryId: String,
    val name: String,
    val description: String,
    val basePriceCents: Int,
    val active: Boolean = true,
    val complementaryExclusions: String = ""
)

@Entity
data class ModifierGroup(
    @PrimaryKey val id: String,
    val name: String,
    val required: Boolean,
    val maxSelections: Int
)

@Entity
data class ModifierOption(
    @PrimaryKey val id: String,
    val groupId: String,
    val name: String,
    val priceDeltaCents: Int
)

@Entity(primaryKeys = ["itemId", "groupId"])
data class MenuItemModifierGroup(
    val itemId: String,
    val groupId: String
)

@Entity
data class Ingredient(
    @PrimaryKey val id: String,
    val name: String,
    val unit: String,
    val quantityOnHand: Double,
    val lowStockThreshold: Double,
    val takeoutOnly: Boolean = false
)

@Entity(primaryKeys = ["itemId", "ingredientId"])
data class RecipeIngredient(
    val itemId: String,
    val ingredientId: String,
    val quantityUsed: Double
)

@Entity(primaryKeys = ["optionId", "ingredientId"])
data class ModifierRecipeIngredient(
    val optionId: String,
    val ingredientId: String,
    val quantityUsed: Double,
    val replacesIngredientId: String? = null
)

@Entity(indices = [Index(value = ["eventId"], unique = true)])
data class InventoryAdjustment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ingredientId: String,
    val deltaQuantity: Double,
    val reason: String,
    val createdAt: Long,
    val eventId: String = java.util.UUID.randomUUID().toString(),
    val synced: Boolean = false
)

@Entity(
    indices = [
        Index(value = ["orderId"]),
        Index(value = ["localAdjustmentId"])
    ]
)
data class OrderInventoryAddOn(
    @PrimaryKey val id: String,
    val orderId: String,
    val ingredientId: String,
    val quantity: Double,
    val createdAt: Long,
    val restoredAt: Long? = null,
    val updatedAt: Long,
    val localAdjustmentId: Long? = null
)

@Entity
data class Employee(
    @PrimaryKey val id: String,
    val name: String,
    val pin: String,
    val role: String,
    val active: Boolean = true
)

@Entity
data class Shift(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val employeeId: String,
    val openedAt: Long,
    val closedAt: Long?,
    val startingCashCents: Int,
    val endingCashCents: Int?,
    val cashAddedCents: Int = 0,
    val cashRemovedCents: Int = 0
)

@Entity
data class PosOrder(
    @PrimaryKey val id: String,
    val status: String,
    val employeeId: String,
    val shiftId: Long,
    val subtotalCents: Int,
    val discountCents: Int,
    val taxCents: Int,
    val tipCents: Int,
    val totalCents: Int,
    val createdAt: Long,
    val paidAt: Long?,
    val voidReason: String? = null,
    val customerName: String? = null,
    val tableNumber: String? = null,
    val orderType: String = "Dine-In"
)

@Entity(indices = [Index(value = ["orderId"])])
data class OrderLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: String,
    val itemId: String,
    val name: String,
    val quantity: Int,
    val unitPriceCents: Int,
    val modifiers: String,
    val notes: String,
    val discountCategory: String? = null,
    val discountCents: Int = 0
)

@Entity(indices = [Index(value = ["orderId"])])
data class Payment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: String,
    val method: String,
    val amountCents: Int,
    val amountTenderedCents: Int,
    val changeCents: Int,
    val createdAt: Long,
    val paymentCategory: String? = null
)

@Entity
data class Receipt(
    @PrimaryKey val orderId: String,
    val receiptNumber: String,
    val text: String,
    val createdAt: Long
)

@Entity
data class StoreSettings(
    @PrimaryKey val id: String = "store",
    val storeName: String,
    val taxRatePercent: Double,
    val tipPresets: String,
    val receiptFooter: String,
    val seniorDiscountPercent: Double = 20.0,
    val pwdDiscountPercent: Double = 20.0,
    val voidRefundPin: String = "1234"
)

@Entity(primaryKeys = ["shiftId", "ingredientId"])
data class StockSnapshot(
    val shiftId: Long,
    val ingredientId: String,
    val quantity: Double
)

@Entity
data class PaymentMethod(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean = true,
    val isSystem: Boolean = false,
    val paymentCategory: String? = null
)

object PaymentCategories {
    const val CASH = "CASH"
    const val ONLINE = "ONLINE"

    fun fromLegacyMethod(method: String): String? = when (method.trim().lowercase()) {
        "cash" -> CASH
        "online", "gcash" -> ONLINE
        else -> null
    }
}

internal fun normalizeIngredientId(name: String): String =
    name.lowercase(java.util.Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "ing-" }

@Entity
data class ClosedShiftAdjustment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val currentShiftId: Long,
    val originalOrderId: String,
    val originalShiftId: Long,
    val amountCents: Int,
    val type: String, // "void" or "refund"
    val reason: String,
    val staffId: String,
    val createdAt: Long
)

@Entity(primaryKeys = ["entityType", "entityId"])
data class PendingDelete(
    val entityType: String,
    val entityId: String,
    val deletedAt: Long,
    val synced: Boolean = false
)
