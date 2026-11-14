package ru.mycapital.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class PortfolioDatabaseRestoreGateInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PortfolioDatabase.endRestore()
        PortfolioDatabase.closeForRestoreIfOpenForTest()
        context.deleteDatabase("my_capital.db")
    }

    @After
    fun tearDown() {
        PortfolioDatabase.endRestore()
        PortfolioDatabase.closeForRestoreIfOpenForTest()
        context.deleteDatabase("my_capital.db")
    }

    @Test
    fun blocksReopenDuringRestoreAndAllowsOpenAfterGateRelease() {
        val original = PortfolioDatabase.getInstance(context)
        original.openHelper.writableDatabase

        val restoreDatabase = PortfolioDatabase.beginRestore()
        assertNotNull(restoreDatabase)
        assertThrows(IllegalStateException::class.java) {
            PortfolioDatabase.beginRestore()
        }
        PortfolioDatabase.closeForRestore()
        assertThrows(IllegalStateException::class.java) {
            PortfolioDatabase.getInstance(context)
        }

        PortfolioDatabase.endRestore()
        val reopened = PortfolioDatabase.getInstance(context)
        assertNotNull(reopened)
        reopened.openHelper.writableDatabase
    }
}
