package com.kape.coffeepos.data

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration19To20Test {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "migration-19-20-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationAddsOnlyThe22OzIceCoffeeMenuAndRecipes() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE MenuCategory (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, sortOrder INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE MenuItem (id TEXT NOT NULL PRIMARY KEY, categoryId TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL, basePriceCents INTEGER NOT NULL, active INTEGER NOT NULL, complementaryExclusions TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE RecipeIngredient (itemId TEXT NOT NULL, ingredientId TEXT NOT NULL, quantityUsed REAL NOT NULL, PRIMARY KEY(itemId, ingredientId))")
                        db.execSQL("INSERT INTO MenuCategory VALUES ('ice-coffee', 'ICE COFFEE', 1)")
                        db.execSQL("INSERT INTO MenuCategory VALUES ('fruit-soda', 'FRUIT SODA', 2)")
                        db.execSQL("INSERT INTO MenuItem VALUES ('existing-soda', 'fruit-soda', 'Existing Soda', '', 5000, 1, '')")
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        ).use { it.writableDatabase }

        factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(20) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        AppDatabase.MIGRATION_19_20.migrate(db)
                    }
                })
                .build()
        ).use { helper ->
            val db = helper.writableDatabase

            db.query("SELECT COUNT(*) FROM MenuItem WHERE categoryId = 'ice-coffee'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(10, cursor.getInt(0))
            }
            db.query("SELECT name, basePriceCents FROM MenuItem WHERE id = 'iced-americano-22-oz'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ICED AMERICANO (22 OZ)", cursor.getString(0))
                assertEquals(10500, cursor.getInt(1))
            }
            db.query("SELECT quantityUsed FROM RecipeIngredient WHERE itemId = 'toffee-nut-mocha-22-oz' AND ingredientId = 'da-vinci-english-toffee-syrup'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(40.0, cursor.getDouble(0), 0.0)
            }
            db.query("SELECT quantityUsed FROM RecipeIngredient WHERE itemId = 'iced-vietnamese-coffee-22-oz' AND ingredientId = 'water'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(100.0, cursor.getDouble(0), 0.0)
            }
            db.query("SELECT COUNT(*) FROM MenuItem WHERE id = 'existing-soda'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }
}
