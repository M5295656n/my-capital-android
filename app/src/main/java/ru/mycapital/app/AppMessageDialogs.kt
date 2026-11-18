package ru.mycapital.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
internal fun AppMessageDialogs(
    backupResultMessage: String?,
    exportResultMessage: String?,
    importResultMessage: String?,
    importError: String?,
    errorMessage: String?,
    onDismissBackup: () -> Unit,
    onDismissExport: () -> Unit,
    onDismissImport: () -> Unit,
    onDismissImportError: () -> Unit,
    onDismissError: () -> Unit
) {
    if (backupResultMessage != null) {
        AlertDialog(
            onDismissRequest = { onDismissBackup() },
            title = { Text("Резервная копия") },
            text = { Text(backupResultMessage.orEmpty()) },
            confirmButton = { TextButton(onClick = { onDismissBackup() }) { Text("Готово") } }
        )
    }
    if (exportResultMessage != null) {
        AlertDialog(
            onDismissRequest = { onDismissExport() },
            title = { Text("Экспорт завершён") },
            text = { Text(exportResultMessage.orEmpty()) },
            confirmButton = { TextButton(onClick = { onDismissExport() }) { Text("Готово") } }
        )
    }
    if (importResultMessage != null) {
        AlertDialog(
            onDismissRequest = { onDismissImport() },
            title = { Text("Импорт завершён") },
            text = { Text(importResultMessage.orEmpty()) },
            confirmButton = { TextButton(onClick = { onDismissImport() }) { Text("Готово") } }
        )
    }
    if (importError != null) {
        AlertDialog(
            onDismissRequest = { onDismissImportError() },
            title = { Text("Не удалось прочитать отчёт") },
            text = { Text(importError.orEmpty()) },
            confirmButton = { TextButton(onClick = { onDismissImportError() }) { Text("Понятно") } }
        )
    }

}
