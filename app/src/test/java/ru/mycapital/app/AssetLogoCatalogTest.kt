package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.AssertNull
import org.junit.Test

class AssetLogoCatalogTest {
    @Test
    fun domainLookupNormalizesTicker() {
        val catalog = mapOf("SBER" to "sberbank.ru")
        assertEquals("sberbank.ru", AssetLogoCatalog.domainFor(" sber ", catalog))
    }

    @Test
    fun unknownTickerUsesFallback() {
        assertNull(AssetLogoCatalog.domainFor("NEWCO", mapOf("SBER" to "sberbank.ru")))
    }

    @Test
    fun faviconUrlUsesConfiguredIssuerDomain() {
        assertEquals(
            "https://www.google.com/s2/favicons?domain=yandex.ru&sz=128",
            AssetLogoCatalog.faviconUrl("yandex.ru")
        )
    }
}
