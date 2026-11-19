package ru.mycapital.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PortfolioRepositoryAssetTypeTest {
    @Test
    fun replacesStaleGenericTypeWithRecognizedBrokerType() {
        assertEquals("Фонд", resolveImportedAssetType("Другое", "Фонд"))
        assertEquals("Облигация", resolveImportedAssetType("Акция", "Облигация"))
    }

    @Test
    fun keepsExistingTypeWhenBrokerDoesNotRecognizeSecurity() {
        assertEquals("Фонд", resolveImportedAssetType("Фонд", "Другое"))
        assertEquals("Фонд", resolveImportedAssetType("Фонд", null))
        assertEquals("Фонд", resolveImportedAssetType("Фонд", " "))
    }
}
