package ru.mycapital.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PortfolioDatabaseRestoreGateInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun releaseDatabaseGate() {
        runCatching {
            PortfolioDatabase.beginRestore()
            PortfolioDatabase.closeForRestore()
        }
        PortfolioDatabase.endRestore()
    }

    @Test
    fun preventsOpeningSecondRoomInstanceUntilRestoreFinishes() {
        val original = PortfolioDatabase.getInstance(context)
        original.openHelper.writableDatabase

        assertSame(original, PortfolioDatabase.beginRestore())
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
