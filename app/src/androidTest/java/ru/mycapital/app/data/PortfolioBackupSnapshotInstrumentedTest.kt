package ru.mycapital.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PortfolioBackupSnapshotInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun createsConsistentSnapshotWithoutClosingLiveRoomDatabase() {
        val database = PortfolioDatabase.getInstance(context)
        database.openHelper.writableDatabase
        val snapshot = File(context.cacheDir, "mycapital-snapshot-test.db")
        snapshot.delete()

        try {
            val version = PortfolioBackup.createConsistentSnapshot(database, snapshot)

            assertTrue(snapshot.isFile)
            assertTrue(snapshot.length() > 0L)
            assertEquals(19, version)
            assertSame(database, PortfolioDatabase.getInstance(context))
            assertTrue(database.openHelper.writableDatabase.isOpen)

            val copy = SQLiteDatabase.openDatabase(
                snapshot.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            try {
                copy.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("ok", cursor.getString(0))
                }
            } finally {
                copy.close()
            }
        } finally {
            snapshot.delete()
        }
    }
}
