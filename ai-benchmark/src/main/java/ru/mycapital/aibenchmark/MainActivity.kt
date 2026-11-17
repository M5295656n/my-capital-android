package ru.mycapital.aibenchmark

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.StatFs
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Offline-only llama.cpp test harness. GGUF files are user-selected and never bundled. */
class MainActivity : Activity() {
    private lateinit var expectedHashInput: EditText
    private lateinit var promptInput: EditText
    private lateinit var modelStatus: TextView
    private lateinit var runtimeStatus: TextView
    private lateinit var outputStatus: TextView
    private lateinit var deviceStatus: TextView
    private lateinit var loadButton: Button
    private lateinit var generateButton: Button
    private lateinit var cancelButton: Button
    private lateinit var unloadButton: Button

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var selectedModelFile: File? = null
    private var actualHash: String? = null
    private var hashVerified = false
    private var engine: InferenceEngine? = null
    private var stateObserver: Job? = null
    private var generationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        content.addView(title("MyCapital AI — локальный тест", 22f))
        content.addView(body("Изолированный llama.cpp стенд. Не подключён к портфелю MyCapital. Сетевого разрешения у приложения нет."))

        content.addView(title("1. Выберите GGUF-файл вручную", 16f))
        content.addView(Button(this).apply {
            text = "Выбрать файл модели"
            setOnClickListener { openModelPicker() }
        })
        modelStatus = body("Файл ещё не выбран")
        content.addView(modelStatus)

        content.addView(title("2. Подтвердите SHA-256", 16f))
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

        content.addView(title("3. Загрузка модели", 16f))
        loadButton = Button(this).apply {
            text = "Загрузить проверенную модель"
            isEnabled = false
            setOnClickListener { loadModel() }
        }
        content.addView(loadButton)
        runtimeStatus = body("Нативный движок ещё не инициализирован")
        content.addView(runtimeStatus)

        content.addView(title("4. Тестовая генерация", 16f))
        promptInput = EditText(this).apply {
            hint = "Введите короткий тестовый запрос"
            minLines = 2
            maxLines = 4
            textSize = 14f
        }
        content.addView(promptInput, matchWidth())
        generateButton = Button(this).apply {
            text = "Сгенерировать ответ"
            isEnabled = false
            setOnClickListener { generateAnswer() }
        }
        content.addView(generateButton)
        cancelButton = Button(this).apply {
            text = "Отменить генерацию и выгрузить"
            isEnabled = false
            setOnClickListener { cancelAndUnload() }
        }
        content.addView(cancelButton)
        unloadButton = Button(this).apply {
            text = "Выгрузить модель"
            isEnabled = false
            setOnClickListener { unloadModel() }
        }
        content.addView(unloadButton)
        outputStatus = body("Здесь появится ответ модели.")
        content.addView(outputStatus)

        content.addView(title("5. Память и хранилище", 16f))
        content.addView(Button(this).apply {
            text = "Обновить показатели устройства"
            setOnClickListener { refreshDeviceStatus() }
        })
        deviceStatus = body("")
        content.addView(deviceStatus)
        content.addView(body("Реальные скорость, стабильность, нагрев и расход батареи необходимо отдельно проверить на физическом Samsung Galaxy A34."))

        setContentView(ScrollView(this).apply { addView(content) })
        refreshDeviceStatus()
    }

    private fun openModelPicker() {
        if (engine?.state?.value is InferenceEngine.State.ModelReady) {
            runtimeStatus.text = "Сначала выгрузите текущую модель."
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_MODEL)
    }

    @Deprecated("Uses platform document picker to keep the harness dependency-light.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MODEL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        hashVerified = false
        loadButton.isEnabled = false
        modelStatus.text = "Копирование в приватный кэш и расчёт SHA-256…"
        activityScope.launch {
            runCatching { withContext(Dispatchers.IO) { copyAndHashModel(uri) } }
                .onSuccess { result ->
                    selectedModelFile?.takeIf { it != result.first }?.delete()
                    selectedModelFile = result.first
                    actualHash = result.second
                    modelStatus.text = "Файл: " + result.first.name + "\nРазмер: " +
                        formatBytes(result.third) + "\nSHA-256: " + result.second
                }
                .onFailure {
                    selectedModelFile = null
                    actualHash = null
                    modelStatus.text = "Не удалось подготовить файл: " + (it.message ?: it.javaClass.simpleName)
                }
        }
    }

    private fun copyAndHashModel(uri: Uri): Triple<File, String, Long> {
        val name = queryDisplayName(uri) ?: "selected-model.gguf"
        require(name.lowercase(Locale.ROOT).endsWith(".gguf")) { "Поддерживаются только файлы .gguf" }
        val directory = File(cacheDir, "models").apply { mkdirs() }
        val temporary = File(directory, "model-" + System.currentTimeMillis() + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        try {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Не удалось открыть файл" }
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        bytes += count
                    }
                }
            }
            require(bytes > 0L) { "Файл пуст" }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val target = File(directory, "model-" + hash + ".gguf")
            if (target.exists()) temporary.delete()
            else check(temporary.renameTo(target)) { "Не удалось сохранить файл в кэш" }
            return Triple(target, hash, bytes)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun verifyHash() {
        val expected = expectedHashInput.text.toString().trim().lowercase(Locale.ROOT)
        val actual = actualHash
        hashVerified = actual != null && expected.matches(Regex("[0-9a-f]{64}")) && expected == actual
        loadButton.isEnabled = hashVerified
        modelStatus.text = when {
            actual == null -> "Сначала выберите GGUF-файл."
            !expected.matches(Regex("[0-9a-f]{64}")) -> "SHA-256 должен содержать 64 hex-символа."
            hashVerified -> "SHA-256 совпадает. Можно загружать модель."
            else -> "ОШИБКА: SHA-256 не совпадает. Загрузка заблокирована."
        }
    }

    private fun loadModel() {
        val file = selectedModelFile
        if (file == null || !hashVerified || !file.isFile) {
            runtimeStatus.text = "Сначала выберите GGUF и подтвердите SHA-256."
            return
        }
        val memoryManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(memoryManager::getMemoryInfo)
        val requiredHeadroom = (file.length() * MIN_FREE_RAM_FACTOR).toLong() + MIN_FREE_RAM_RESERVE_BYTES
        if (memoryInfo.lowMemory || memoryInfo.availMem < requiredHeadroom) {
            runtimeStatus.text = "Загрузка заблокирована: доступно " + formatBytes(memoryInfo.availMem) +
                ", ориентировочно требуется не менее " + formatBytes(requiredHeadroom) +
                ". Это предварительная оценка, а не гарантия отсутствия OOM."
            refreshDeviceStatus()
            return
        }
        loadButton.isEnabled = false
        runtimeStatus.text = "Проверка памяти пройдена. Инициализация нативного движка…"
        activityScope.launch {
            try {
                val activeEngine = engine ?: AiChat.getInferenceEngine(applicationContext).also {
                    engine = it
                    stateObserver?.cancel()
                    stateObserver = activityScope.launch {
                        it.state.collect { state -> renderState(state) }
                    }
                }
                val ready = withTimeout(120_000L) {
                    activeEngine.state.first {
                        it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
                    }
                }
                if (ready is InferenceEngine.State.Error) throw ready.exception
                check(ready is InferenceEngine.State.Initialized) { "Runtime не инициализирован" }
                runtimeStatus.text = "Загрузка модели " + file.name + "…"
                activeEngine.loadModel(file.absolutePath)
                activeEngine.setSystemPrompt(
                    "Ты — локальный тестовый помощник. Отвечай по-русски кратко и по существу. " +
                        "Не утверждай, что имеешь доступ к интернету, новостям или портфелю пользователя."
                )
                runtimeStatus.text = "Модель загружена. Можно запускать генерацию."
                generateButton.isEnabled = true
                unloadButton.isEnabled = true
                refreshDeviceStatus()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                runtimeStatus.text = "Ошибка загрузки: " + (error.message ?: error.javaClass.simpleName)
                loadButton.isEnabled = engine?.state?.value is InferenceEngine.State.Initialized
                refreshDeviceStatus()
            }
        }
    }

    private fun generateAnswer() {
        val activeEngine = engine ?: return
        val prompt = promptInput.text.toString().trim()
        if (prompt.isEmpty() || prompt.length > MAX_PROMPT_LENGTH) {
            runtimeStatus.text = "Введите запрос длиной от 1 до " + MAX_PROMPT_LENGTH + " символов."
            return
        }
        if (activeEngine.state.value !is InferenceEngine.State.ModelReady || generationJob?.isActive == true) {
            runtimeStatus.text = "Движок занят или модель не загружена."
            return
        }
        outputStatus.text = ""
        generateButton.isEnabled = false
        cancelButton.isEnabled = true
        unloadButton.isEnabled = false
        runtimeStatus.text = "Генерация…"
        generationJob = activityScope.launch {
            val started = SystemClock.elapsedRealtime()
            var firstChunk = 0L
            var chunkCount = 0
            try {
                activeEngine.sendUserPrompt(prompt, MAX_GENERATED_TOKENS).collect { chunk ->
                    if (firstChunk == 0L) firstChunk = SystemClock.elapsedRealtime()
                    chunkCount++
                    outputStatus.append(chunk)
                }
                val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
                val ttft = if (firstChunk == 0L) "нет" else (firstChunk - started).toString() + " мс"
                val speed = chunkCount * 1000.0 / elapsed
                runtimeStatus.text = String.format(
                    Locale.ROOT, "Готово · фрагментов потока: %d · время до первого фрагмента: %s · скорость потока: %.2f фрагм./с · время: %.2f с",
                    chunkCount, ttft, speed, elapsed / 1000.0
                )
                refreshDeviceStatus()
            } catch (error: CancellationException) {
                runtimeStatus.text = "Генерация отменена."
                throw error
            } catch (error: Exception) {
                runtimeStatus.text = "Ошибка генерации: " + (error.message ?: error.javaClass.simpleName)
            } finally {
                cancelButton.isEnabled = false
                generateButton.isEnabled = activeEngine.state.value is InferenceEngine.State.ModelReady
                unloadButton.isEnabled = activeEngine.state.value is InferenceEngine.State.ModelReady
            }
        }
    }

    private fun cancelAndUnload() {
        val job = generationJob ?: return
        val activeEngine = engine ?: return
        cancelButton.isEnabled = false
        runtimeStatus.text = "Остановка генерации и выгрузка…"
        activityScope.launch {
            job.cancelAndJoin()
            runCatching {
                withContext(Dispatchers.IO) {
                    if (activeEngine.state.value is InferenceEngine.State.ModelReady ||
                        activeEngine.state.value is InferenceEngine.State.Error
                    ) activeEngine.cleanUp()
                }
            }.onSuccess {
                runtimeStatus.text = "Генерация остановлена, модель выгружена."
            }.onFailure {
                runtimeStatus.text = "Ошибка остановки: " + (it.message ?: it.javaClass.simpleName)
            }
            generateButton.isEnabled = false
            unloadButton.isEnabled = false
            loadButton.isEnabled = hashVerified
            refreshDeviceStatus()
        }
    }

    private fun unloadModel() {
        val activeEngine = engine ?: return
        if (generationJob?.isActive == true) {
            runtimeStatus.text = "Сначала отмените текущую генерацию."
            return
        }
        unloadButton.isEnabled = false
        generateButton.isEnabled = false
        activityScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (activeEngine.state.value is InferenceEngine.State.ModelReady ||
                        activeEngine.state.value is InferenceEngine.State.Error
                    ) activeEngine.cleanUp()
                }
            }.onSuccess {
                runtimeStatus.text = "Модель выгружена, runtime оставлен инициализированным."
                loadButton.isEnabled = hashVerified
            }.onFailure {
                runtimeStatus.text = "Ошибка выгрузки: " + (it.message ?: it.javaClass.simpleName)
                unloadButton.isEnabled = activeEngine.state.value is InferenceEngine.State.ModelReady
            }
            refreshDeviceStatus()
        }
    }

    private fun renderState(state: InferenceEngine.State) {
        runtimeStatus.text = when (state) {
            is InferenceEngine.State.Uninitialized -> "Движок: не инициализирован"
            is InferenceEngine.State.Initializing -> "Движок: инициализация"
            is InferenceEngine.State.Initialized -> "Движок готов, модель выгружена"
            is InferenceEngine.State.LoadingModel -> "Движок: загрузка модели"
            is InferenceEngine.State.UnloadingModel -> "Движок: выгрузка модели"
            is InferenceEngine.State.ModelReady -> "Модель загружена и готова"
            is InferenceEngine.State.Benchmarking -> "Движок: benchmark"
            is InferenceEngine.State.ProcessingSystemPrompt -> "Движок: подготовка system prompt"
            is InferenceEngine.State.ProcessingUserPrompt -> "Движок: обработка запроса"
            is InferenceEngine.State.Generating -> "Движок: генерация токенов"
            is InferenceEngine.State.Error -> "Ошибка runtime: " + (state.exception.message ?: "unknown")
        }
    }

    private fun refreshDeviceStatus() {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val pssKb = Debug.getPss()
        val stat = StatFs(filesDir.absolutePath)
        deviceStatus.text = "Доступная RAM: " + formatBytes(memory.availMem) +
            "\nОбщая RAM Android: " + formatBytes(memory.totalMem) +
            "\nПорог low-memory: " + formatBytes(memory.threshold) +
            "\nPSS приложения: " + formatBytes(pssKb * 1024L) +
            "\nСвободное место: " + formatBytes(stat.availableBytes) +
            "\nLow-RAM device: " + if (manager.isLowRamDevice) "да" else "нет"
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

    private fun title(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun body(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setPadding(0, dp(8), 0, dp(12))
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return bytes.toString() + " B"
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

    override fun onDestroy() {
        val activeEngine = engine
        generationJob?.cancel()
        if (activeEngine != null) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching {
                    withTimeout(30_000L) {
                        activeEngine.state.first {
                            it is InferenceEngine.State.ModelReady ||
                                it is InferenceEngine.State.Initialized ||
                                it is InferenceEngine.State.Error
                        }
                    }
                    if (activeEngine.state.value is InferenceEngine.State.ModelReady ||
                        activeEngine.state.value is InferenceEngine.State.Error
                    ) activeEngine.cleanUp()
                }
            }
        }
        stateObserver?.cancel()
        activityScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_MODEL = 41
        private const val MAX_PROMPT_LENGTH = 6_000
        private const val MAX_GENERATED_TOKENS = 256
        // Conservative preflight heuristic only; native runtime overhead varies by model.
        private const val MIN_FREE_RAM_FACTOR = 1.5
        private const val MIN_FREE_RAM_RESERVE_BYTES = 512L * 1024L * 1024L
    }
}
