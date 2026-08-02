package com.kape.coffeepos.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CheckoutTransactionSafetyTest {
    @Test
    fun checkoutPathsUseRoomTransactions() {
        val source = repositorySource()

        assertTrue(source.functionBody("checkout").contains("database.withTransaction"))
        assertTrue(source.functionBody("checkoutSplit").contains("database.withTransaction"))
    }

    private fun repositorySource(): String {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .first { File(it, "app/src/main/java/com/kape/coffeepos/data/Repositories.kt").exists() }
        return File(root, "app/src/main/java/com/kape/coffeepos/data/Repositories.kt").readText()
    }

    private fun String.functionBody(name: String): String {
        val start = indexOf("internal suspend fun $name(")
        require(start >= 0) { "Missing $name" }
        val nextFunction = indexOf("\n    internal suspend fun", start + 1).let { if (it == -1) length else it }
        return substring(start, nextFunction)
    }
}
