package ru.mycapital.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BondInstrumentClassifierTest {
    @Test
    fun recognizesOfzByBrokerSecurityNameAndMoexTickerEvenWhenStoredTypeIsGeneric() {
        assertTrue(isBondInstrument("SU26212RMFS9", "ОФЗ 26212", "Другое"))
        assertTrue(isBondInstrument("SU26218RMFS6", "ОФЗ 26218", "Другое"))
    }

    @Test
    fun recognizesBondsByAssetType() {
        assertTrue(isBondInstrument("RU000A0JVW48", "Some security", "Облигация"))
        assertTrue(isBondInstrument("CORP", "Corporate bond", "Bond"))
    }

    @Test
    fun doesNotClassifyEquitiesAsBonds() {
        assertFalse(isBondInstrument("SBER", "Сбербанк", "Акция"))
        assertFalse(isBondInstrument("X5", "Корп центр ИКС 5", "Другое"))
    }
}
