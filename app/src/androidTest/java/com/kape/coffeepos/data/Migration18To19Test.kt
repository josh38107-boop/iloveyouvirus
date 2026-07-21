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
class Migration18To19Test {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "migration-18-19-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationAddsInventoryEventIdentityAndSyncState() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(18) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE InventoryAdjustment (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ingredientId TEXT NOT NULL, deltaQuantity REAL NOT NULL, reason TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO InventoryAdjustment(ingredientId, deltaQuantity, reason, createdAt) VALUES ('beans', -1, 'sale', 1)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        ).use { it.writableDatabase }

        factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        AppDatabase.MIGRATION_18_19.migrate(db)
                    }
                })
                .build()
        ).use { helper ->
            val db = helper.writableDatabase
            db.query("SELECT eventId, synced FROM InventoryAdjustment").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0).startsWith("legacy-"))
                assertTrue(cursor.getInt(1) == 1)
            }
            val indexes = mutableSetOf<String>()
            db.query("PRAGMA index_list(`InventoryAdjustment`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) indexes += cursor.getString(nameIndex)
            }
            assertTrue(indexes.contains("index_InventoryAdjustment_eventId"))
        }
    }
}
