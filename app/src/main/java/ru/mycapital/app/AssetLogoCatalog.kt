package ru.mycapital.app

/**
 * Offline issuer-logo catalog.
 * Values are file names in app/src/main/assets/logos.
 * Preferred shares resolve to the same issuer logo as ordinary shares.
 */
internal object AssetLogoCatalog {
    private val issuerLogos = mapOf(
        "AFLT" to "aeroflot.svg"
    )

    fun assetFor(ticker: String): String? = issuerLogos[ticker.trim().uppercase()]
    fun supportedTickers(): Set<String> = issuerLogos.keys
}
