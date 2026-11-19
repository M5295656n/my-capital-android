package ru.mycapital.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import ru.mycapital.app.data.AssetEntity

@Composable
internal fun AppAssetDialogs(
    assetToDelete: AssetEntity?,
    assetToEdit: AssetEntity?,
    showAddAsset: Boolean,
    onDismissDelete: () -> Unit,
    onDismissEdit: () -> Unit,
    onDismissAdd: () -> Unit,
    onDelete: (AssetEntity) -> Unit,
    onSaveEdit: (AssetEntity) -> Unit,
    onSaveAdd: (String, String, String, Double, Double, Double) -> Unit
) {
    if (assetToDelete != null) {
        val target = assetToDelete
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("Удалить ${target.ticker}?") },
            text = {
                Text("Будет удалена только карточка актива и её текущая позиция. История операций, дивиденды и купоны не удаляются. Если актив импортируется повторно из брокерского отчёта, он может появиться снова.")
            },
            confirmButton = {
                TextButton(onClick = { onDelete(target) }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete) { Text("Отмена") }
            }
        )
    }
    if (assetToEdit != null) {
        EditAssetDialog(
            asset = assetToEdit,
            onDismiss = onDismissEdit,
            onSave = onSaveEdit
        )
    }
    if (showAddAsset) {
        AddAssetDialog(
            onDismiss = onDismissAdd,
            onSave = onSaveAdd
        )
    }
}
