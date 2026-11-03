package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.BrokerAccountAssetEntity
import ru.mycapital.app.data.BrokerAccountCashEntity
import ru.mycapital.app.data.BrokerAccountEntity
import ru.mycapital.app.data.OperationEntity
import ru.mycapital.app.data.PayoutEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class PortfolioCsvExportTest {
    @Test
    fun csvFieldEscapesQuotesAndSeparators() {
        assertEquals("\"АО; \"\"Компания\"\"\"", escapeCsvField("АО; \"Компания\""))
    }

    @Test
    fun exportContainsAllDataFilesAndMasksBrokerIdentifiers() {
        val output = ByteArrayOutputStream()
        writePortfolioExportZip(
            output = output,
            assets = listOf(AssetEntity(ticker = "SBER", name = "Сбер", assetType = "Акция",
                quantity = 2.0, averagePrice = 100.0, currentPrice = 110.0)),
            operations = listOf(OperationEntity(operationType = "Покупка", ticker = "SBER",
                quantity = 2.0, price = 100.0, sourceKey = "SBER:1234567890:TRADE:1")),
            payouts = listOf(PayoutEntity(ticker = "SBER", payoutType = "Дивиденд",
                amountPerUnit = 10.0, quantity = 2.0, totalAmount = 20.0,
                sourceKey = "SBER:1234567890:PAYOUT:2")),
            accounts = listOf(BrokerAccountEntity("1234567890", "Основной", "Брокерский счёт")),
            accountCash = listOf(BrokerAccountCashEntity("1234567890", 50.0)),
            accountAssets = listOf(BrokerAccountAssetEntity("1234567890", "SBER", 2.0)),
            cashBalance = 50.0
        )

        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }

        assertEquals(
            setOf("assets.csv", "broker_accounts.csv", "broker_account_positions.csv", "operations.csv", "payouts.csv"),
            entries.keys
        )
        assertTrue(entries.getValue("assets.csv").contains("SBER"))
        assertTrue(entries.getValue("operations.csv").contains("Основной"))
        assertTrue(entries.getValue("broker_accounts.csv").contains("••••7890"))
        assertTrue(entries.values.none { it.contains("1234567890") })
        assertTrue(entries.getValue("payouts.csv").contains("Дивиденд"))
    }
}
