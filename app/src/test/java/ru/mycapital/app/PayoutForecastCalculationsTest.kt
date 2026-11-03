package ru.mycapital.app

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mycapital.app.data.PayoutEntity

class PayoutForecastCalculationsTest {
    private val today = LocalDate.of(2026, 11, 3)
    private val zone = ZoneId.of("UTC")

    private fun payout(
        date: LocalDate?,
        amount: Double,
        status: String = "EXPECTED"
    ) = PayoutEntity(
        assetId = null,
        ticker = "SBER",
        payoutType = "Дивиденд",
        amountPerUnit = 1.0,
        quantity = 1.0,
        totalAmount = amount,
        expectedDate = date?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
        status = status
    )

    @Test
    fun forecastGroupsExpectedPaymentsByMonth() {
        val result = calculatePayoutForecast(
            listOf(
                payout(LocalDate.of(2026, 11, 10), 100.0),
                payout(LocalDate.of(2026, 11, 25), 50.0),
                payout(LocalDate.of(2026, 12, 5), 75.0)
            ),
            today,
            zone
        )

        assertEquals(2, result.monthly.size)
        assertEquals(150.0, result.monthly[0].amount, 0.000001)
        assertEquals(2, result.monthly[0].paymentCount)
        assertEquals(75.0, result.monthly[1].amount, 0.000001)
    }

    @Test
    fun receivedUndatedAndOverduePayoutsAreExcluded() {
        val result = calculatePayoutForecast(
            listOf(
                payout(LocalDate.of(2026, 11, 10), 100.0),
                payout(LocalDate.of(2026, 11, 12), 200.0, "RECEIVED"),
                payout(null, 300.0),
                payout(LocalDate.of(2026, 11, 2), 400.0)
            ),
            today,
            zone
        )

        assertEquals(100.0, result.next30Days, 0.000001)
        assertEquals(1, result.monthly.size)
        assertEquals(1, result.monthly[0].paymentCount)
    }

    @Test
    fun forecastCalculatesThirtyAndNinetyDayWindows() {
        val result = calculatePayoutForecast(
            listOf(
                payout(today.plusDays(30), 10.0),
                payout(today.plusDays(31), 20.0),
                payout(today.plusDays(90), 30.0),
                payout(today.plusDays(91), 40.0)
            ),
            today,
            zone
        )

        assertEquals(10.0, result.next30Days, 0.000001)
        assertEquals(60.0, result.next90Days, 0.000001)
        assertTrue(result.monthly.size >= 2)
    }

    @Test
    fun todayPaymentsAreIncludedInForecast() {
        val result = calculatePayoutForecast(
            listOf(payout(today, 55.0)),
            today,
            zone
        )

        assertEquals(55.0, result.next30Days, 0.000001)
    }
}
