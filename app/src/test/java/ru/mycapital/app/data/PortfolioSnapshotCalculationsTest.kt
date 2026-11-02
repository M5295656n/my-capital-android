package ru.mycapital.app.data

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class PortfolioSnapshotCalculationsTest {
    @Test
    fun snapshotDateUsesMoscowTimezoneAcrossUtcDayBoundary() {
        val timestamp = Instant.parse("2026-11-02T21:30:00Z").toEpochMilli()

        assertEquals("2026-11-03", portfolioSnapshotDateKey(timestamp))
    }

    @Test
    fun snapshotDateCanUseExplicitTimezoneForDeterministicTests() {
        val timestamp = Instant.parse("2026-11-02T21:30:00Z").toEpochMilli()

        assertEquals(
            "2026-11-02",
            portfolioSnapshotDateKey(timestamp, ZoneId.of("UTC"))
        )
    }

    @Test
    fun externalFlowAddsDepositsAndSubtractsWithdrawals() {
        val operations = listOf(
            OperationEntity(
                operationType = "Пополнение",
                price = 500.0,
                assetId = null,
                ticker = null,
                quantity = 0.0
            ),
            OperationEntity(
                operationType = "Вывод",
                price = 125.0,
                assetId = null,
                ticker = null,
                quantity = 0.0
            )
        )

        assertEquals(375.0, calculateExternalFlow(operations), 0.000001)
    }

    @Test
    fun externalFlowIgnoresNonCashOperations() {
        val operation = OperationEntity(
            operationType = "Покупка",
            price = 250.0,
            assetId = null,
            ticker = "SBER",
            quantity = 1.0
        )

        assertEquals(0.0, calculateExternalFlow(listOf(operation)), 0.000001)
    }
}
