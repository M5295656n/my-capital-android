package ru.mycapital.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import java.io.File

/** Unencrypted ZIP backup. Legacy AES-256 backups remain supported during restore. */
object PortfolioBackup {
    private const val DATABASE_FILE = "my_capital.db"
    private const val BACKUP_ENTRY = "my_capital.db"
    private const val MANIFEST_ENTRY = "manifest.txt"
    private const val DATABASE_VERSION = PORTFOLIO_DATABASE_VERSION
    private const val MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L

    data class BackupInfo(val databaseVersion: Int, val databaseBytes: Long)

    fun export(context: Context, destination: Uri): BackupInfo {
        val database = PortfolioDatabase.getInstance(context)
        val source = context.getDatabasePath(DATABASE_FILE)
        val tempDb = File(context.cacheDir, "mycapital-backup-${System.currentTimeMillis()}.db")
        val tempArchive = File(context.cacheDir, "mycapital-backup-${System.currentTimeMillis()}.zip")
        try {
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 0 && cursor.getInt(1) == cursor.getInt(2)) {
                    "Не удалось безопасно сохранить журнал базы данных. Повторите попытку."
                }
            }
            check(source.isFile && source.length() > 0L) { "Локальная база данных не найдена." }
            source.copyTo(tempDb, overwrite = true)
            val version = validateDatabase(tempDb)
            val zip = ZipFile(tempArchive)
            val manifest = File(context.cacheDir, "mycapital-manifest-${System.currentTimeMillis()}.txt")
            try {
                manifest.writeText(
                    "MyCapital backup\nformat=3\ndatabaseVersion=$version\ncreatedAt=${System.currentTimeMillis()}\nencryption=none\n",
                    Charsets.UTF_8
                )
                val parameters = ZipParameters().apply {
                    compressionMethod = CompressionMethod.DEFLATE
                    isEncryptFiles = false
                }
                zip.addFile(manifest, parameters.apply { fileNameInZip = MANIFEST_ENTRY })
                zip.addFile(tempDb, parameters.apply { fileNameInZip = BACKUP_ENTRY })
            } finally {
                manifest.delete()
            }
            context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                tempArchive.inputStream().use { it.copyTo(output) }
            } ?: error("Не удалось записать файл резервной копии.")
            return BackupInfo(version, tempDb.length())
        } finally {
            tempDb.delete()
            tempArchive.delete()
        }
    }

    fun restore(context: Context, sourceUri: Uri, password: CharArray? = null): BackupInfo {
        val archive = File(context.cacheDir, "mycapital-restore-${System.currentTimeMillis()}.zip")
        val extractDir = File(context.cacheDir, "mycapital-restore-${System.currentTimeMillis()}")
        val tempDb = File(extractDir, BACKUP_ENTRY)
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                archive.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Не удалось открыть архив.")
            check(archive.length() in 1..MAX_ARCHIVE_BYTES) { "Размер архива недопустим." }
            extractDir.mkdirs()

            val plainZip = ZipFile(archive)
            val headers = plainZip.fileHeaders
            check(headers.isNotEmpty() && headers.all { it.fileName == MANIFEST_ENTRY || it.fileName == BACKUP_ENTRY }) {
                "Архив содержит неизвестные файлы или имеет неверный формат."
            }
            check(headers.any { it.fileName == MANIFEST_ENTRY } && headers.any { it.fileName == BACKUP_ENTRY }) {
                "В архиве отсутствует база данных или манифест."
            }
            val encrypted = headers.any { it.isEncrypted }
            check(headers.all { it.isEncrypted == encrypted }) { "Формат архива некорректен." }
            val zip = if (encrypted) {
                require(password != null && password.isNotEmpty()) { "Это старая зашифрованная копия. Введите пароль AES-256." }
                ZipFile(archive, password)
            } else {
                ZipFile(archive)
            }

            // Extract only the two expected entries; never unpack arbitrary archive paths.
            zip.extractFile(MANIFEST_ENTRY, extractDir.absolutePath)
            zip.extractFile(BACKUP_ENTRY, extractDir.absolutePath)
            val manifest = File(extractDir, MANIFEST_ENTRY).readText(Charsets.UTF_8)
            val validPlain = manifest.startsWith("MyCapital backup") &&
                manifest.contains("format=3") && manifest.contains("encryption=none")
            val validLegacy = manifest.startsWith("MyCapital backup") &&
                manifest.contains("format=2") && manifest.contains("encryption=AES-256") && encrypted
            check(validPlain || validLegacy) {
                "Файл не является резервной копией MyCapital поддерживаемого формата."
            }

            val version = validateDatabase(tempDb)
            check(manifest.contains("databaseVersion=$version")) { "Версия базы не совпадает с манифестом." }

            val target = context.getDatabasePath(DATABASE_FILE)
            val rollback = File(context.cacheDir, "mycapital-before-restore.db")
            if (target.exists()) target.copyTo(rollback, overwrite = true)
            try {
                PortfolioDatabase.closeForRestore()
                File(target.absolutePath + "-wal").delete()
                File(target.absolutePath + "-shm").delete()
                tempDb.copyTo(target, overwrite = true)
                validateDatabase(target)
            } catch (error: Exception) {
                if (rollback.exists()) rollback.copyTo(target, overwrite = true)
                File(target.absolutePath + "-wal").delete()
                File(target.absolutePath + "-shm").delete()
                throw error
            } finally {
                rollback.delete()
            }
            return BackupInfo(version, tempDb.length())
        } finally {
            password?.fill('\u0000')
            archive.delete()
            extractDir.deleteRecursively()
        }
    }

    internal fun validateDatabase(file: File): Int {
        check(file.isFile && file.length() > 0L) { "В архиве отсутствует база данных." }
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val version = db.version
            check(version == DATABASE_VERSION) {
                "Версия базы в архиве ($version) не поддерживается этой версией приложения."
            }
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "Проверка целостности базы не пройдена."
                }
            }
            return version
        } finally {
            db.close()
        }
    }
}
