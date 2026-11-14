package ru.mycapital.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PortfolioDatabaseRestoreGateInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun preventsOpeningSecondRoomInstanceUntilRestoreFinishes() {
        val original = PortfolioDatabase.getInstance(context)
        original.openHelper.writableDatabase

        var restoreGateAcquired = false
        try {
            assertSame(original, PortfolioDatabase.beginRestore())
            restoreGateAcquired = true
            assertThrows(IllegalStateException::class.java) {
                PortfolioDatabase.beginRestore()
            }

            PortfolioDatabase.closeForRestore()
            assertThrows(IllegalStateException::class.java) {
                PortfolioDatabase.getInstance(context)
            }
        } finally {
            if (restoreGateAcquired) PortfolioDatabase.endRestore()
        }

        val reopened = PortfolioDatabase.getInstance(context)
        assertNotNull(reopened)
        reopened.openHelper.writableDatabase
    }
}
