package ru.mycapital.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test

/**
 * Verifies the database lifecycle used by the existing restore flow.
 *
 * The current implementation exposes closeForRestore(); it does not implement
 * a separate begin/end restore gate.
 */
class PortfolioDatabaseRestoreGateInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PortfolioDatabase.closeForRestore()
        context.deleteDatabase("my_capital.db")
    }

    @After
    fun tearDown() {
        PortfolioDatabase.closeForRestore()
        context.deleteDatabase("my_capital.db")
    }

    @Test
    fun closesDatabaseBeforeRestoreAndAllowsReopenAfterward() {
        val original = PortfolioDatabase.getInstance(context)
        original.openHelper.writableDatabase

        PortfolioDatabase.closeForRestore()

        val reopened = PortfolioDatabase.getInstance(context)
        assertNotNull(reopened)
        assertNotSame(original, reopened)
        reopened.openHelper.writableDatabase
    }
}
