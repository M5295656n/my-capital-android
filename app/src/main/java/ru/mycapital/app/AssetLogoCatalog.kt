package ru.mycapital.app
import android.content.Context
import coil.ImageLoader
import coil.decode.SvgDecoder
internal object AssetLogoCatalog {
 private val aliases = mapOf("SBER" to "sber", "SBERP" to "sber", "GAZP" to "gazprom", "LKOH" to "lukoil", "ROSN" to "rosneft", "NVTK" to "novatek", "YDEX" to "yandex", "T" to "tbank", "TCSG" to "tbank", "X5" to "x5", "TATN" to "tatneft", "TATNP" to "tatneft", "GMKN" to "nornickel", "MOEX" to "moex", "MTSS" to "mts", "MGNT" to "magnit", "AFLT" to "aeroflot", "IRAO" to "interrao", "CHMF" to "severstal", "NLMK" to "nlmk", "ALRS" to "alrosa", "PHOR" to "phosagro", "RTKM" to "rostelecom", "SNGS" to "surgut", "SNGSP" to "surgut", "RUAL" to "rusal", "PLZL" to "polyus", "FLOT" to "sovcomflot", "FEES" to "rosseti", "HYDR" to "rushydro", "MSNG" to "mosenergo", "TRNFP" to "transneft")
 @Volatile private var cachedImageLoader: ImageLoader? = null
 fun assetPathFor(ticker: String): String? = aliases[ticker.trim().uppercase()]?.let { "logos/$it.svg" }
 fun imageLoader(context: Context): ImageLoader = cachedImageLoader ?: synchronized(this) { cachedImageLoader ?: ImageLoader.Builder(context.applicationContext).components { add(SvgDecoder.Factory()) }.build().also { cachedImageLoader = it } }
 fun supportedTickers(): Set<String> = aliases.keys
}
