package ru.mycapital.app

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.mycapital.app.data.PortfolioRepository
import ru.mycapital.app.data.SberBrokerReport
import ru.mycapital.app.data.SberBrokerReportParser

/**
 * Coordinates broker-report I/O while keeping reconciliation and import rules
 * inside [PortfolioRepository].
 */
internal class BrokerReportImportCoordinator(
    private val contentResolver: ContentResolver,
    private val repository: PortfolioRepository
) {
    suspend fun parseBrokerReport(uri: Uri): SberBrokerReport = withContext(Dispatchers.IO) {
        contentResolver.openInputStream(uri)?.use { input ->
            SberBrokerReportParser.parse(input)
        } ?: error("Не удалось открыть выбранный файл.")
    }

    suspend fun importAndReconcile(report: SberBrokerReport) = withContext(Dispatchers.IO) {
        repository.importAndReconcileBrokerReport(report)
    }
}
