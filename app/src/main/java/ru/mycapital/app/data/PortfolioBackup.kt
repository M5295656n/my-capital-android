package ru.mycapital.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File

/**
 * Portable, password-protected AES-256 backup of the complete Room database.
 * The password is supplied by the user and is never persisted by this class.
 */
object PortfolioBackup {
    private const val DATABASE_FILE = "my_capital.db"
    private const val BACKUP_ENTRY = "my_capital.db"
    private const val MANIFEST_ENTRY = "manifest.txt"
    private const val MIN_SUPPORTED_DATABASE_VERSION = 14
    private const val CURRENT_DATABASE_VERSION = 19
    private const val MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L
    private const val MAX_DATABASE_ENTRY_BYTES = 256L * 1024L * 1024L
    private const val MAX_MANIFEST_BYTES = 64L * 1024L

    data class BackupInfo(val databaseVersion: Int, val databaseBytes: Long)

    class RestoreRequiresActivityRestartException(
        message: String,
        cause: Throwable
    ) : IllegalStateException(message, cause)

    fun export(context: Context, destination: Uri, password: CharArray): BackupInfo {
        require(password.size >= 9) { "Пароль должен содержать не менее 9 символов." }
        val database = PortfolioDatabase.getInstance(context)
        val source = context.getDatabasePath(DATABASE_FILE)
        val tempDb = File(context.cacheDir, "mycapital-backup-${System.currentTimeMillis()}.db")
        val tempArchive = File(context.cacheDir, "mycapital-backup-${System.currentTimeMillis()}.zip")
        try {
            // Checkpoint WAL and require SQLite to report that all frames were checkpointed.
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 0 && cursor.getInt(1) == cursor.getInt(2)) {
                    "Не удалось безопасно сохранить журнал базы данных. Повторите попытку."
                }
            }
            check(source.isFile && source.length() > 0L) { "Локальная база данных не найдена." }
            source.copyTo(tempDb, overwrite = true)
            val version = validateDatabase(tempDb)
            val zip = ZipFile(tempArchive, password)
            val manifest = File(context.cacheDir, "mycapital-manifest-${System.currentTimeMillis()}.txt")
            try {
                manifest.writeText(
                    "MyCapital backup\nformat=2\ndatabaseVersion=$version\ncreatedAt=${System.currentTimeMillis()}\nencryption=AES-256\n",
                    Charsets.UTF_8
                )
                val parameters = ZipParameters().apply {
                    isEncryptFiles = true
                    encryptionMethod = EncryptionMethod.AES
                    aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    compressionMethod = CompressionMethod.DEFLATE
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
            password.fill('\u0000')
            tempDb.delete()
            tempArchive.delete()
        }
    }

    fun restore(context: Context, sourceUri: Uri, password: CharArray): BackupInfo {
        require(password.size >= 9) { "Пароль должен содержать не менее 9 символов." }
        val archive = File(context.cacheDir, "mycapital-restore-${System.currentTimeMillis()}.zip")
        val extractDir = File(context.cacheDir, "mycapital-restore-${System.currentTimeMillis()}")
        val tempDb = File(extractDir, BACKUP_ENTRY)
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= MAX_ARCHIVE_BYTES) { "Размер архива превышает допустимый предел." }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Не удалось открыть архив.")
            check(archive.length() in 1..MAX_ARCHIVE_BYTES) { "Размер архива недопустим." }
            extractDir.mkdirs()
            val zip = ZipFile(archive, password)
            val headers = zip.fileHeaders
            check(headers.size == 2 && headers.all { it.isEncrypted } &&
                headers.map { it.fileName }.toSet() == setOf(MANIFEST_ENTRY, BACKUP_ENTRY)) {
                "Архив должен содержать только зашифрованные манифест и базу данных."
            }
            val manifestHeader = headers.single { it.fileName == MANIFEST_ENTRY }
            val databaseHeader = headers.single { it.fileName == BACKUP_ENTRY }
            check(manifestHeader.uncompressedSize in 1..MAX_MANIFEST_BYTES &&
                databaseHeader.uncompressedSize in 1..MAX_DATABASE_ENTRY_BYTES) {
                "Размер распакованных данных недопустим."
            }
            // Extract only the two expected entries; never unpack arbitrary archive paths.
            zip.extractFile(MANIFEST_ENTRY, extractDir.absolutePath)
            zip.extractFile(BACKUP_ENTRY, extractDir.absolutePath)
            check(File(extractDir, MANIFEST_ENTRY).length() in 1..MAX_MANIFEST_BYTES &&
                tempDb.length() in 1..MAX_DATABASE_ENTRY_BYTES) {
                "Фактический размер распакованных данных превышает допустимый предел."
            }
            val manifest = File(extractDir, MANIFEST_ENTRY).readText(Charsets.UTF_8)
            check(manifest.startsWith("MyCapital backup") && manifest.contains("format=2") &&
                manifest.contains("encryption=AES-256")) {
                "Файл не является зашифрованной резервной копией MyCapital."
            }
            val version = validateDatabase(tempDb)
            check(manifest.contains("databaseVersion=$version")) { "Версия базы не совпадает с манифестом." }

            val target = context.getDatabasePath(DATABASE_FILE)
            val rollback = File(context.cacheDir, "mycapital-before-restore.db")
            val candidate = File(target.parentFile, "mycapital-restore-candidate.db")
            val currentDatabase = PortfolioDatabase.beginRestore()
            var databaseCloseAttempted = false
            try {
                currentDatabase.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                    check(cursor.moveToFirst() && cursor.getInt(0) == 0 && cursor.getInt(1) == cursor.getInt(2)) {
                        "Не удалось подготовить текущую базу к восстановлению."
                    }
                }
                databaseCloseAttempted = true
                PortfolioDatabase.closeForRestore()
                try {
                    val wal = File(target.absolutePath + "-wal")
                    check(!wal.exists() || wal.length() == 0L) {
                        "Журнал базы данных остался активным. Повторите восстановление."
                    }
                    if (target.exists()) target.copyTo(rollback, overwrite = true)
                    candidate.delete()
                    tempDb.copyTo(candidate, overwrite = true)
                    validateDatabase(candidate)
                    java.nio.file.Files.move(
                        candidate.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                    File(target.absolutePath + "-wal").delete()
                    File(target.absolutePath + "-shm").delete()
                } catch (error: Exception) {
                    if (rollback.exists()) {
                        val rollbackCandidate = File(target.parentFile, "mycapital-rollback-candidate.db")
                        rollbackCandidate.delete()
                        rollback.copyTo(rollbackCandidate, overwrite = true)
                        java.nio.file.Files.move(
                            rollbackCandidate.toPath(),
                            target.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                    File(target.absolutePath + "-wal").delete()
                    File(target.absolutePath + "-shm").delete()
                    throw error
                } finally {
                    candidate.delete()
                    rollback.delete()
                }
            } catch (error: Exception) {
                if (databaseCloseAttempted) {
                    throw RestoreRequiresActivityRestartException(
                        error.message ?: "Восстановление не завершилось. База возвращена к прежнему состоянию; перезапустите экран приложения.",
                        error
                    )
                }
                throw error
            } finally {
                PortfolioDatabase.endRestore()
            }
            return BackupInfo(version, tempDb.length())
        } finally {
            password.fill('\u0000')
            archive.delete()
            extractDir.deleteRecursively()
        }
    }

    private fun validateDatabase(file: File): Int {
        check(file.isFile && file.length() > 0L) { "В архиве отсутствует база данных." }
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val version = db.version
            check(version in MIN_SUPPORTED_DATABASE_VERSION..CURRENT_DATABASE_VERSION) {
                "Версия базы в архиве ($version) не поддерживается. Допустимый диапазон: " +
                    "$MIN_SUPPORTED_DATABASE_VERSION–$CURRENT_DATABASE_VERSION."
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
