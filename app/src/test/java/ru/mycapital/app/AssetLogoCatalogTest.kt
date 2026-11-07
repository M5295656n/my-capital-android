package ru.mycapital.app

import org.junit.Assert.*
import org.junit.Test

class AssetLogoCatalogTest {
    @Test fun tickerLookupNormalizesInput() {
        assertEquals("logos/sber.svg", AssetLogoCatalog.assetPathFor(" sber "))
        assertEquals("logos/sber.svg", AssetLogoCatalog.assetPathFor("sberp"))
        assertEquals("logos/tbank.svg", AssetLogoCatalog.assetPathFor("tcsg"))
        assertEquals("logos/yandex.svg", AssetLogoCatalog.assetPathFor("YDEX"))
        assertEquals("logos/x5.svg", AssetLogoCatalog.assetPathFor(" x5 "))
    }

    @Test fun onlyVerifiedIssuerLogosAreMapped() {
        listOf("SBER", "SBERP", "T", "TCSG", "YDEX", "X5")
            .forEach { assertNotNull("$it should have a verified local logo", AssetLogoCatalog.assetPathFor(it)) }
    }

    @Test fun unverifiedTickersDoNotReceiveInventedLogos() {
        listOf("GAZP", "LKOH", "ROSN", "NVTK", "UNKNOWN")
            .forEach { assertNull("$it must not use an unverified logo", AssetLogoCatalog.assetPathFor(it)) }
    }

    @Test fun ofzUsesTheFullRussianStateCoatOfArmsAsset() {
        assertEquals("logos/russian_coat_of_arms.svg", AssetLogoCatalog.coatOfArmsAssetPath())
    }
}
