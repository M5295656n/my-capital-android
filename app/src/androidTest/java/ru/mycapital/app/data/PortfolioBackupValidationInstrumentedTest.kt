package ru.mycapital.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PortfolioBackupValidationInstrumentedTest {
    @Test
    fun acceptsCurrentRoomSchemaAndRejectsOlderSchema() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "portfolio-backup-version-test.db")
        file.delete()

        try {
            val current = SQLiteDatabase.openOrCreateDatabase(file, null)
            try {
                current.execSQL("CREATE TABLE validation_probe (id INTEGER NOT NULL)")
                current.version = PORTFOLIO_DATABASE_VERSION
            } finally {
                current.close()
            }

            assertEquals(PORTFOLIO_DATABASE_VERSION, PortfolioBackup.validateDatabase(file))

            val legacy = SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
            try {
                legacy.version = PORTFOLIO_DATABASE_VERSION - 5
            } finally {
                legacy.close()
            }

            assertThrows(IllegalStateException::class.java) {
                PortfolioBackup.validateDatabase(file)
            }
        } finally {
            file.delete()
        }
    }
}
