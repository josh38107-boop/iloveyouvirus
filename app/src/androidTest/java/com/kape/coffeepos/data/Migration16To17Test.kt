package com.kape.coffeepos.data

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration16To17Test {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "migration-16-17-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationCreatesOrderInventoryAddOnTableAndIndexes() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(16) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        ).use { it.writableDatabase }

        factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(17) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        AppDatabase.MIGRATION_16_17.migrate(db)
                    }
                })
                .build()
        ).use { helper ->
            val db = helper.writableDatabase
            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(`OrderInventoryAddOn`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.containsAll(setOf("id", "orderId", "ingredientId", "quantity", "restoredAt", "updatedAt", "localAdjustmentId")))

            val indexes = mutableSetOf<String>()
            db.query("PRAGMA index_list(`OrderInventoryAddOn`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) indexes += cursor.getString(nameIndex)
            }
            assertTrue(indexes.contains("index_OrderInventoryAddOn_orderId"))
            assertTrue(indexes.contains("index_OrderInventoryAddOn_localAdjustmentId"))
        }
    }
}
