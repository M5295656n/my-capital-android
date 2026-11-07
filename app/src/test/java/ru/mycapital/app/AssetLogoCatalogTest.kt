package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssetLogoCatalogTest {
    @Test fun lookupNormalizesTicker() {
        assertEquals("aeroflot.svg", AssetLogoCatalog.assetFor(" aflt "))
    }

    @Test fun unknownTickerUsesNeutralFallback() {
        assertNull(AssetLogoCatalog.assetFor("NEWCO"))
    }

    @Test fun catalogListsBundledLogoTickers() {
        assertEquals(setOf("AFLT"), AssetLogoCatalog.supportedTickers())
    }
}
