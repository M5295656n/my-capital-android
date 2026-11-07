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
    private val assets = mapOf(
        "SBER" to "logos/sber.svg",
        "SBERP" to "logos/sber.svg",
        "T" to "logos/tbank.svg",
        "TCSG" to "logos/tbank.svg",
        "YDEX" to "logos/yandex.svg",
        "X5" to "logos/x5.svg",
        "GMKN" to "logos/nornickel.svg",
        "NVTK" to "logos/novatek.png",
        "TATN" to "logos/tatneft.svg",
        "TATNP" to "logos/tatneft.svg"
    )

    @Volatile private var cachedImageLoader: ImageLoader? = null

    fun assetPathFor(ticker: String): String? = assets[ticker.trim().uppercase()]

    fun coatOfArmsAssetPath(): String = "logos/russian_coat_of_arms.svg"

    fun imageLoader(context: Context): ImageLoader =
        cachedImageLoader ?: synchronized(this) {
            cachedImageLoader ?: ImageLoader.Builder(context.applicationContext)
                .components { add(SvgDecoder.Factory()) }
                .build()
                .also { cachedImageLoader = it }
        }

    fun supportedTickers(): Set<String> = assets.keys
}
