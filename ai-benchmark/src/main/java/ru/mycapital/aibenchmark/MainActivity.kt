package ru.mycapital.aibenchmark

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.StatFs
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.security.MessageDigest
import java.util.Locale

/**
 * Isolated, offline-only device preparation harness.
 *
 * This first stage deliberately does not load native inference code or copy model weights.
 * It lets the tester select a GGUF file, calculate its SHA-256 incrementally, compare it
 * with a separately trusted expected digest, and inspect device memory/storage headroom.
 */
class MainActivity : Activity() {

    private lateinit var expectedHashInput: EditText
    private lateinit var modelStatus: TextView
    private lateinit var deviceStatus: TextView
    private var actualHash: String? = null
    private var selectedModelName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        content.addView(TextView(this).apply {
            text = "MyCapital AI — локальный тест"
            textSize = 22f
        })
        content.addView(TextView(this).apply {
            text = "Изолированный стенд. Не подключён к портфелю MyCapital. Сетевого разрешения у приложения нет."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        })

        content.addView(TextView(this).apply {
            text = "1. Выберите GGUF-файл вручную"
            textSize = 16f
        })
        content.addView(Button(this).apply {
            text = "Выбрать файл модели"
            setOnClickListener { openModelPicker() }
        })

        modelStatus = TextView(this).apply {
            text = "Файл ещё не выбран"
            textSize = 13f
            setPadding(0, dp(8), 0, dp(16))
        }
        content.addView(modelStatus)

        content.addView(TextView(this).apply {
            text = "2. Сверьте SHA-256 с доверенным значением из закреплённого manifest"
            textSize = 16f
        })
        expectedHashInput = EditText(this).apply {
            hint = "Ожидаемый SHA-256 (64 hex-символа)"
            setSingleLine(true)
            textSize = 13f
        }
        content.addView(expectedHashInput, matchWidth())
        content.addView(Button(this).apply {
            text = "Проверить контрольную сумму"
            setOnClickListener { verifyHash() }
        })

        content.addView(Button(this).apply {
            text = "Обновить показатели устройства"
            setOnClickListener { refreshDeviceStatus() }
        })
        deviceStatus = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
        }
        content.addView(deviceStatus)

        content.addView(TextView(this).apply {
            text = "Статус этапа: проверка файла и ресурсов устройства. Генеративный runtime ещё не подключён; запуск модели и скорость генерации здесь пока не тестируются."
            textSize = 13f
            setPadding(0, dp(12), 0, dp(8))
        })

        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        refreshDeviceStatus()
    }

    private fun openModelPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_MODEL)
    }

    @Deprecated("Uses the platform document picker to keep the harness dependency-light.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MODEL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val result = runCatching { calculateSha256(uri) }
            runOnUiThread {
                result.onSuccess { (hash, byteCount) ->
                    actualHash = hash
                    selectedModelName = queryDisplayName(uri)
                    modelStatus.text = buildString {
                        appendLine("Файл: ${selectedModelName ?: "выбранный GGUF"}")
                        appendLine("Размер: ${formatBytes(byteCount)}")
                        append("SHA-256: $hash")
                    }
                }.onFailure {
                    actualHash = null
                    modelStatus.text = "Не удалось прочитать файл: ${it.message ?: it.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun calculateSha256(uri: Uri): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Не удалось открыть выбранный файл" }
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                totalBytes += count
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return hash to totalBytes
    }

    private fun verifyHash() {
        val expected = expectedHashInput.text.toString()
            .trim()
            .lowercase(Locale.ROOT)
        val actual = actualHash
        modelStatus.text = when {
            actual == null -> "Сначала выберите и прочитайте файл."
            !expected.matches(Regex("[0-9a-f]{64}")) ->
                "Ожидаемый SHA-256 должен содержать ровно 64 шестнадцатеричных символа."
            expected == actual -> "SHA-256 совпадает. Это подтверждает целостность относительно указанного хеша, но не доказывает безопасность или качество происхождения файла."
            else -> "ОШИБКА: SHA-256 не совпадает. Не использовать этот файл."
        }
    }

    private fun refreshDeviceStatus() {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val pssKb = Debug.getPss()
        val stat = StatFs(filesDir.absolutePath)
        val availableStorage = stat.availableBytes

        deviceStatus.text = buildString {
            appendLine("Доступная RAM системы: ${formatBytes(memory.availMem)}")
            appendLine("Общая RAM, видимая Android: ${formatBytes(memory.totalMem)}")
            appendLine("Порог low-memory: ${formatBytes(memory.threshold)}")
            appendLine("Приложение сейчас использует PSS: ${formatBytes(pssKb * 1024L)}")
            appendLine("Свободное место в разделе приложения: ${formatBytes(availableStorage)}")
            appendLine("Android считает устройство low-RAM: ${if (activityManager.isLowRamDevice) "да" else "нет"}")
            append("Система сообщает low-memory: ${if (memory.lowMemory) "да" else "нет"}")
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return uri.lastPathSegment
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        do {
            value /= 1024.0
            unit++
        } while (value >= 1024.0 && unit < units.lastIndex)
        return String.format(Locale.ROOT, "%.2f %s", value, units[unit])
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    companion object {
        private const val REQUEST_MODEL = 41
    }
}
