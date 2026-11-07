package ru.mycapital.app

import android.content.Context
import coil.ImageLoader
import coil.decode.SvgDecoder

/**
 * Curated offline issuer logo catalog.
 * Only tickers with a verified, authentic logo asset are mapped here.
 * Unverified issuers deliberately use the ticker-text fallback.
 */
internal object AssetLogoCatalog {
    private val aliases = mapOf(
        "SBER" to "sber",
        "SBERP" to "sber",
        "T" to "tbank",
        "TCSG" to "tbank",
        "YDEX" to "yandex",
        "X5" to "x5"
    )

    @Volatile private var cachedImageLoader: ImageLoader? = null

    fun assetPathFor(ticker: String): String? =
        aliases[ticker.trim().uppercase()]?.let { "logos/$it.svg" }

    fun coatOfArmsAssetPath(): String = "logos/russian_coat_of_arms.svg"

    fun imageLoader(context: Context): ImageLoader =
        cachedImageLoader ?: synchronized(this) {
            cachedImageLoader ?: ImageLoader.Builder(context.applicationContext)
                .components { add(SvgDecoder.Factory()) }
                .build()
                .also { cachedImageLoader = it }
        }

    fun supportedTickers(): Set<String> = aliases.keys
}
