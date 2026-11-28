package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.mycapital.app.data.OperationEntity

class OperationOrderingTest {
    @Test
    fun sortsEveryOperationTypeByTimestampThenInsertionId() {
        val operations = listOf(
            operation(id = 3, type = "Покупка", date = 20_000L),
            operation(id = 2, type = "Пополнение", date = 10_000L),
            operation(id = 4, type = "Продажа", date = 20_000L),
            operation(id = 1, type = "Дивиденд", date = 10_000L)
        )

        val ordered = sortOperationsChronologically(operations)

        assertEquals(listOf(1L, 2L, 3L, 4L), ordered.map { it.id })
        assertEquals(
            listOf("Дивиденд", "Пополнение", "Покупка", "Продажа"),
            ordered.map { it.operationType }
        )
    }

    private fun operation(id: Long, type: String, date: Long) = OperationEntity(
        id = id,
        assetId = null,
        operationType = type,
        ticker = null,
        quantity = 0.0,
        price = 0.0,
        operationDate = date
    )
}
