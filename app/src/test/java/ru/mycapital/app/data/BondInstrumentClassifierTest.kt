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
    fun parsesMoexShareClassesAndRejectsFundsAndBonds() {
        val ordinary = """{"description":{"columns":["name","value"],"data":[["TYPENAME","Акция обыкновенная"],["GROUPNAME","Акции"]]}}"""
        val fund = """{"description":{"columns":["name","value"],"data":[["TYPENAME","Пай инвестиционного фонда"],["GROUPNAME","Паи"]]}}"""
        val bond = """{"description":{"columns":["name","value"],"data":[["TYPENAME","Облигация"],["GROUPNAME","Облигации"]]}}"""
        assertTrue(parseMoexEquityClassification(ordinary) == true)
        assertTrue(parseMoexEquityClassification(fund) == false)
        assertTrue(parseMoexEquityClassification(bond) == false)
        assertTrue(parseMoexEquityClassification("{}") == null)
    }
    @Test
    fun doesNotClassifyEquitiesAsBonds() {
        assertFalse(isBondInstrument("SBER", "Сбербанк", "Акция"))
        assertFalse(isBondInstrument("X5", "Корп центр ИКС 5", "Другое"))
    }
}
