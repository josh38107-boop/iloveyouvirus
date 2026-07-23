package com.kape.coffeepos.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MenuCategory::class,
        MenuItem::class,
        ModifierGroup::class,
        ModifierOption::class,
        MenuItemModifierGroup::class,
        Ingredient::class,
        RecipeIngredient::class,
        InventoryAdjustment::class,
        Employee::class,
        Shift::class,
        PosOrder::class,
        OrderLine::class,
        Payment::class,
        Receipt::class,
        StoreSettings::class,
        StockSnapshot::class,
        ModifierRecipeIngredient::class,
        PaymentMethod::class,
        DiscountRule::class,
        ClosedShiftAdjustment::class,
        PendingDelete::class,
        OrderInventoryAddOn::class
    ],
    version = 21,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun menuDao(): MenuDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun employeeDao(): EmployeeDao
    abstract fun shiftDao(): ShiftDao
    abstract fun orderDao(): OrderDao
    abstract fun settingsDao(): SettingsDao
    abstract fun seedDao(): SeedDao
    abstract fun stockSnapshotDao(): StockSnapshotDao
    abstract fun pendingDeleteDao(): PendingDeleteDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Payment ADD COLUMN amountTenderedCents INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE Payment ADD COLUMN changeCents INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE Payment SET amountTenderedCents = amountCents WHERE amountTenderedCents = 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE PosOrder ADD COLUMN voidReason TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE PosOrder ADD COLUMN customerName TEXT")
                db.execSQL("ALTER TABLE PosOrder ADD COLUMN tableNumber TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS `StockSnapshot` (`shiftId` INTEGER NOT NULL, `ingredientId` TEXT NOT NULL, `quantity` REAL NOT NULL, PRIMARY KEY(`shiftId`, `ingredientId`))")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Shift ADD COLUMN cashAddedCents INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE Shift ADD COLUMN cashRemovedCents INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `ModifierRecipeIngredient` (`optionId` TEXT NOT NULL, `ingredientId` TEXT NOT NULL, `quantityUsed` REAL NOT NULL, `replacesIngredientId` TEXT, PRIMARY KEY(`optionId`, `ingredientId`))")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_OrderLine_orderId` ON `OrderLine` (`orderId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_Payment_orderId` ON `Payment` (`orderId`)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE MenuItem ADD COLUMN complementaryExclusions TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Ingredient ADD COLUMN takeoutOnly INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE PosOrder ADD COLUMN orderType TEXT NOT NULL DEFAULT 'Dine-In'")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `PaymentMethod` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `isSystem` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE StoreSettings ADD COLUMN seniorDiscountPercent REAL NOT NULL DEFAULT 20.0")
                db.execSQL("ALTER TABLE StoreSettings ADD COLUMN pwdDiscountPercent REAL NOT NULL DEFAULT 20.0")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `ClosedShiftAdjustment` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `currentShiftId` INTEGER NOT NULL, `originalOrderId` TEXT NOT NULL, `originalShiftId` INTEGER NOT NULL, `amountCents` INTEGER NOT NULL, `type` TEXT NOT NULL, `reason` TEXT NOT NULL, `staffId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE StoreSettings ADD COLUMN voidRefundPin TEXT NOT NULL DEFAULT '1234'")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `PendingDelete` (`entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, `synced` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`entityType`, `entityId`))")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `PendingDelete` (`entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, `synced` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`entityType`, `entityId`))")
                val defaultIngredientIds = """
                    'beans','milk','oat','almond','matcha-powder','chai-base','coldbrew-base',
                    'condensed-cream','caramel-sauce','chocolate-sauce','white-chocolate-sauce',
                    'lemon-tea-base','strawberry-base','vanilla-base','frappe-base',
                    'croissant-stock','chocolate-croissant-stock','muffin-stock',
                    'banana-bread-stock','cinnamon-roll-stock','cookie-stock','sandwich-stock',
                    'sugar-syrup'
                """.trimIndent().replace("\n", "")
                val now = System.currentTimeMillis()
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO PendingDelete(entityType, entityId, deletedAt, synced)
                    SELECT 'recipe_ingredient', itemId || '|' || ingredientId, $now, 0
                    FROM RecipeIngredient
                    WHERE ingredientId IN ($defaultIngredientIds)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO PendingDelete(entityType, entityId, deletedAt, synced)
                    SELECT 'modifier_recipe_ingredient', optionId || '|' || ingredientId, $now, 0
                    FROM ModifierRecipeIngredient
                    WHERE ingredientId IN ($defaultIngredientIds)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO PendingDelete(entityType, entityId, deletedAt, synced)
                    SELECT 'ingredient', id, $now, 0
                    FROM Ingredient
                    WHERE id IN ($defaultIngredientIds)
                    """.trimIndent()
                )
                db.execSQL("DELETE FROM RecipeIngredient WHERE ingredientId IN ($defaultIngredientIds)")
                db.execSQL("DELETE FROM ModifierRecipeIngredient WHERE ingredientId IN ($defaultIngredientIds)")
                db.execSQL("DELETE FROM Ingredient WHERE id IN ($defaultIngredientIds)")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE OrderLine ADD COLUMN discountCategory TEXT")
                db.execSQL("ALTER TABLE OrderLine ADD COLUMN discountCents INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `OrderInventoryAddOn` (`id` TEXT NOT NULL, `orderId` TEXT NOT NULL, `ingredientId` TEXT NOT NULL, `quantity` REAL NOT NULL, `createdAt` INTEGER NOT NULL, `restoredAt` INTEGER, `updatedAt` INTEGER NOT NULL, `localAdjustmentId` INTEGER, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_OrderInventoryAddOn_orderId` ON `OrderInventoryAddOn` (`orderId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_OrderInventoryAddOn_localAdjustmentId` ON `OrderInventoryAddOn` (`localAdjustmentId`)")
            }
        }

        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE PaymentMethod ADD COLUMN paymentCategory TEXT")
                db.execSQL("ALTER TABLE Payment ADD COLUMN paymentCategory TEXT")
                db.execSQL("UPDATE PaymentMethod SET paymentCategory = 'CASH' WHERE id = 'cash'")
                db.execSQL("UPDATE PaymentMethod SET paymentCategory = 'ONLINE' WHERE id = 'gcash'")
                db.execSQL("UPDATE Payment SET paymentCategory = 'CASH' WHERE LOWER(method) = 'cash'")
                db.execSQL("UPDATE Payment SET paymentCategory = 'ONLINE' WHERE LOWER(method) IN ('online', 'gcash')")
            }
        }

        internal val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE InventoryAdjustment ADD COLUMN eventId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE InventoryAdjustment ADD COLUMN synced INTEGER NOT NULL DEFAULT 1")
                db.execSQL("UPDATE InventoryAdjustment SET eventId = 'legacy-' || id WHERE eventId = ''")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_InventoryAdjustment_eventId` ON `InventoryAdjustment` (`eventId`)")
            }
        }

        internal val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val items = listOf(
                    arrayOf("iced-americano-22-oz", "ICED AMERICANO (22 OZ)", "Espresso, water, and ice", "10500"),
                    arrayOf("iced-latte-22-oz", "ICED LATTE (22 OZ)", "Espresso, milk, fructose, and ice", "12000"),
                    arrayOf("mocha-iced-coffee-22-oz", "MOCHA ICED COFFEE (22 OZ)", "Espresso, chocolate, milk, and ice", "15000"),
                    arrayOf("iced-tres-leches-coffee-22-oz", "ICED TRES LECHES COFFEE (22 OZ)", "Espresso with three milks, vanilla, and ice", "14500"),
                    arrayOf("iced-spanish-latte-22-oz", "ICED SPANISH LATTE (22 OZ)", "Espresso, condensed milk, full cream milk, and ice", "13500"),
                    arrayOf("salted-caramel-macchiato-22-oz", "SALTED CARAMEL MACCHIATO (22 OZ)", "Espresso, salted caramel, vanilla, milk, and ice", "15000"),
                    arrayOf("iced-caramel-macchiato-22-oz", "ICED CARAMEL MACCHIATO (22 OZ)", "Espresso, caramel, vanilla, milk, and ice", "15000"),
                    arrayOf("iced-butterscotch-coffee-22-oz", "ICED BUTTERSCOTCH COFFEE (22 OZ)", "Espresso, butterscotch, milk, and ice", "14000"),
                    arrayOf("toffee-nut-mocha-22-oz", "TOFFEE NUT MOCHA (22 OZ)", "Espresso, chocolate, English toffee, milk, and ice", "15000"),
                    arrayOf("iced-vietnamese-coffee-22-oz", "ICED VIETNAMESE COFFEE (22 OZ)", "Espresso, condensed milk, water, and ice", "12500")
                )
                items.forEach { (id, name, description, priceCents) ->
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO MenuItem(
                            id, categoryId, name, description, basePriceCents, active, complementaryExclusions
                        )
                        SELECT ?, 'ice-coffee', ?, ?, ?, 1, ''
                        WHERE EXISTS (SELECT 1 FROM MenuCategory WHERE id = 'ice-coffee')
                        """.trimIndent(),
                        arrayOf(id, name, description, priceCents.toInt())
                    )
                }

                val commonTakeoutRecipe = listOf(
                    "print-label" to 1.0,
                    "single-takeout-plastic-bag" to 1.0,
                    "pet-cup-without-lid-per-box-16oz-1000pc" to 1.0,
                    "strawless-lid-98mm-100pcs" to 1.0,
                    "thin-hard-straw-black-individually-wrapped" to 1.0
                )
                val recipes = mapOf(
                    "iced-americano-22-oz" to listOf("espresso-beans" to 27.0, "ice-cubes" to 200.0, "water" to 120.0),
                    "iced-latte-22-oz" to listOf("espresso-beans" to 27.0, "emborg-full-cream-milk" to 180.0, "z-fructose-1-2-l" to 30.0, "ice-cubes" to 200.0),
                    "mocha-iced-coffee-22-oz" to listOf("espresso-beans" to 27.0, "da-vinci-choco-sauce" to 55.0, "emborg-full-cream-milk" to 180.0, "ice-cubes" to 200.0),
                    "iced-tres-leches-coffee-22-oz" to listOf("espresso-beans" to 27.0, "jersey-condensed-milk" to 45.0, "jersey-evap" to 120.0, "emborg-full-cream-milk" to 120.0, "da-vinci-vanilla-syrup" to 35.0, "ice-cubes" to 200.0),
                    "iced-spanish-latte-22-oz" to listOf("espresso-beans" to 27.0, "jersey-condensed-milk" to 60.0, "emborg-full-cream-milk" to 180.0, "ice-cubes" to 200.0),
                    "salted-caramel-macchiato-22-oz" to listOf("espresso-beans" to 27.0, "da-vinci-salted-caramel-sauce" to 45.0, "da-vinci-vanilla-syrup" to 40.0, "emborg-full-cream-milk" to 180.0, "ice-cubes" to 200.0),
                    "iced-caramel-macchiato-22-oz" to listOf("espresso-beans" to 27.0, "da-vinci-caramel-sauce" to 45.0, "da-vinci-vanilla-syrup" to 40.0, "emborg-full-cream-milk" to 180.0, "ice-cubes" to 200.0),
                    "iced-butterscotch-coffee-22-oz" to listOf("espresso-beans" to 27.0, "da-vinci-butterscotch-sauce" to 45.0, "emborg-full-cream-milk" to 180.0, "ice-cubes" to 200.0),
                    "toffee-nut-mocha-22-oz" to listOf("espresso-beans" to 27.0, "da-vinci-choco-sauce" to 25.0, "da-vinci-english-toffee-syrup" to 40.0, "emborg-full-cream-milk" to 180.0, "ice-cubes" to 200.0),
                    "iced-vietnamese-coffee-22-oz" to listOf("espresso-beans" to 27.0, "jersey-condensed-milk" to 65.0, "water" to 100.0, "ice-cubes" to 200.0)
                )
                recipes.forEach { (itemId, itemRecipe) ->
                    (itemRecipe + commonTakeoutRecipe).forEach { (ingredientId, quantityUsed) ->
                        db.execSQL(
                            """
                            INSERT OR REPLACE INTO RecipeIngredient(itemId, ingredientId, quantityUsed)
                            SELECT ?, ?, ?
                            WHERE EXISTS (SELECT 1 FROM MenuItem WHERE id = ?)
                            """.trimIndent(),
                            arrayOf(itemId, ingredientId, quantityUsed, itemId)
                        )
                    }
                }
            }
        }

        internal val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `DiscountRule` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `percent` REAL NOT NULL, `scope` TEXT NOT NULL, `requiresReference` INTEGER NOT NULL DEFAULT 0, `active` INTEGER NOT NULL DEFAULT 1, `sortOrder` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))")
                db.execSQL("ALTER TABLE StoreSettings ADD COLUMN discountSettingsUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE PosOrder ADD COLUMN discountRuleId TEXT")
                db.execSQL("ALTER TABLE PosOrder ADD COLUMN discountCategory TEXT")
                db.execSQL("ALTER TABLE PosOrder ADD COLUMN discountPercent REAL")
                db.execSQL("ALTER TABLE PosOrder ADD COLUMN discountScope TEXT")
                db.execSQL("ALTER TABLE PosOrder ADD COLUMN discountReference TEXT")
            }
        }

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "coffee_pos.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
