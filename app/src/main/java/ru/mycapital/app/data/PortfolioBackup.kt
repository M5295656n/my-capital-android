package ru.mycapital.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full, versioned backup of the Room database. The archive contains private
 * portfolio and broker-account information; the user chooses where to store it.
 */
object PortfolioBackup {
    private const val DATABASE_FILE = "my_capital.db"
    private const val BACKUP_ENTRY = "my_capital.db"
    private const val MANIFEST_ENTRY = "manifest.txt"
    private const val DATABASE_VERSION = 14

    data class BackupInfo(val databaseVersion: Int, val databaseBytes: Long)

    fun export(context: Context, destination: Uri): BackupInfo {
        val database = PortfolioDatabase.getInstance(context)
        database.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)")
        val source = context.getDatabasePath(DATABASE_FILE)
        check(source.isFile && source.length() > 0L) { "Локальная база данных не найдена." }
        val temp = File(context.cacheDir, "mycapital-backup-${System.currentTimeMillis()}.db")
        try {
            source.copyTo(temp, overwrite = true)
            val checkDb = SQLiteDatabase.openDatabase(temp.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val version = checkDb.version
                check(version == DATABASE_VERSION) { "Неожиданная версия базы: $version." }
                checkDb.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                        "Проверка целостности базы не пройдена."
                    }
                }
                context.contentResolver.openOutputStream(destination, "w")?.use { raw ->
                    ZipOutputStream(raw).use { zip ->
                        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                        zip.write(("MyCapital backup\nformat=1\ndatabaseVersion=$version\ncreatedAt=${System.currentTimeMillis()}\n").toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry(BACKUP_ENTRY))
                        temp.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                } ?: error("Не удалось записать файл резервной копии.")
                return BackupInfo(version, temp.length())
            } finally {
                checkDb.close()
            }
        } finally {
            temp.delete()
        }
    }

    fun restore(context: Context, sourceUri: Uri): BackupInfo {
        val temp = File(context.cacheDir, "mycapital-restore-${System.currentTimeMillis()}.db")
        var manifest: String? = null
        var foundDatabase = false
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        when (entry.name) {
                            MANIFEST_ENTRY -> manifest = zip.readBytes().toString(Charsets.UTF_8)
                            BACKUP_ENTRY -> {
                                temp.outputStream().use { output -> zip.copyTo(output) }
                                foundDatabase = true
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } ?: error("Не удалось открыть архив.")
            check(foundDatabase && manifest?.contains("format=1") == true) {
                "Файл не является резервной копией MyCapital."
            }
            val checkDb = SQLiteDatabase.openDatabase(temp.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val version: Int
            try {
                version = checkDb.version
                check(version == DATABASE_VERSION) {
                    "Версия базы в архиве ($version) не поддерживается этой версией приложения."
                }
                checkDb.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                        "Архив повреждён: проверка целостности не пройдена."
                    }
                }
            } finally {
                checkDb.close()
            }

            // Keep the current database until the replacement has been fully validated.
            val target = context.getDatabasePath(DATABASE_FILE)
            val rollback = File(context.cacheDir, "mycapital-before-restore.db")
            if (target.exists()) target.copyTo(rollback, overwrite = true)
            try {
                PortfolioDatabase.closeForRestore()
                File(target.absolutePath + "-wal").delete()
                File(target.absolutePath + "-shm").delete()
                temp.copyTo(target, overwrite = true)
            } catch (error: Exception) {
                if (rollback.exists()) rollback.copyTo(target, overwrite = true)
                throw error
            } finally {
                rollback.delete()
            }
            return BackupInfo(version, temp.length())
        } finally {
            temp.delete()
        }
    }
}
