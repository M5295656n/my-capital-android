package ru.mycapital.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Remote ticker -> issuer website catalog.
 * The catalog is deliberately independent from the APK so new tickers can be
 * added without changing the application or the Room schema.
 */
internal object AssetLogoCatalog {
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/M5295656n/my-capital-android/feat/import-and-ui-polish/logos/manifest.json"
    private const val PREFS = "asset_logo_catalog"
    private const val KEY_DOMAINS = "ticker_domains"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val MAX_AGE_MS = 6L * 60L * 60L * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile private var memoryCache: Map<String, String>? = null

    fun domainFor(ticker: String, catalog: Map<String, String>): String? =
        catalog[ticker.trim().uppercase()]

    fun faviconUrl(domain: String): String =
        "https://www.google.com/s2/favicons?domain=${domain}&sz=128"

    suspend fun load(context: Context): Map<String, String> {
        memoryCache?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = parseCatalog(prefs.getString(KEY_DOMAINS, null))
        val isFresh = System.currentTimeMillis() - prefs.getLong(KEY_UPDATED_AT, 0L) < MAX_AGE_MS
        if (cached.isNotEmpty() && isFresh) {
            memoryCache = cached
            return cached
        }

        return try {
            val request = Request.Builder().url(MANIFEST_URL).build()
            val response = client.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) error("Logo catalog HTTP ${it.code}")
                val json = JSONObject(it.body?.string() ?: error("Empty logo catalog"))
                val domains = mutableMapOf<String, String>()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val ticker = keys.next().trim().uppercase()
                    val domain = json.optString(ticker).trim()
                    if (ticker.isNotEmpty() && domain.isNotEmpty() &&
                        !domain.contains("/") && !domain.contains("://")
                    ) domains[ticker] = domain
                }
                val result = domains.toMap()
                memoryCache = result
                prefs.edit()
                    .putString(KEY_DOMAINS, JSONObject(result).toString())
                    .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                    .apply()
                result
            }
        } catch (_: Exception) {
            cached.also { if (it.isNotEmpty()) memoryCache = it }
        }
    }

    private fun parseCatalog(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val ticker = keys.next().trim().uppercase()
                    val domain = json.optString(ticker).trim()
                    if (ticker.isNotEmpty() && domain.isNotEmpty()) put(ticker, domain)
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
