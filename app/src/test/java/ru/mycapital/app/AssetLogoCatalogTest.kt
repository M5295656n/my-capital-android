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
        assertEquals("logos/nornickel.svg", AssetLogoCatalog.assetPathFor("gmkn"))
        assertEquals("logos/novatek.png", AssetLogoCatalog.assetPathFor(" NVTK "))
        assertEquals("logos/tatneft.svg", AssetLogoCatalog.assetPathFor("TATN"))
        assertEquals("logos/tatneft.svg", AssetLogoCatalog.assetPathFor("TATNP"))
        assertEquals("logos/gazprom.svg", AssetLogoCatalog.assetPathFor("GAZP"))
        assertEquals("logos/lukoil.svg", AssetLogoCatalog.assetPathFor("LKOH"))
        assertEquals("logos/rosneft.svg", AssetLogoCatalog.assetPathFor("ROSN"))
    }

    @Test fun onlyVerifiedIssuerLogosAreMapped() {
        listOf("SBER", "SBERP", "T", "TCSG", "YDEX", "X5", "GMKN", "NVTK", "TATN", "TATNP", "GAZP", "LKOH", "ROSN")
            .forEach { assertNotNull("$it should have a verified local logo", AssetLogoCatalog.assetPathFor(it)) }
    }

    @Test fun unverifiedTickersDoNotReceiveInventedLogos() {
        listOf("MTSS", "MGNT", "UNKNOWN")
            .forEach { assertNull("$it must not use an unverified logo", AssetLogoCatalog.assetPathFor(it)) }
    }

    @Test fun ofzUsesTheFullRussianStateCoatOfArmsAsset() {
        assertEquals("logos/russian_coat_of_arms.svg", AssetLogoCatalog.coatOfArmsAssetPath())
    }
}
