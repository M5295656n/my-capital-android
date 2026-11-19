package ru.mycapital.app

import androidx.compose.runtime.Composable
import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.PayoutEntity

@Composable
internal fun AppTransactionDialogs(
    assets: List<AssetEntity>,
    payoutToConfirm: PayoutEntity?,
    showAddPayout: Boolean,
    showCashFlow: Boolean,
    showAddOperation: Boolean,
    onDismissPayoutConfirmation: () -> Unit,
    onConfirmPayout: (Double) -> Unit,
    onDismissAddPayout: () -> Unit,
    onSavePayout: (AssetEntity?, String, Double, Double, Double, Boolean, Long?) -> Unit,
    onDismissCashFlow: () -> Unit,
    onSaveCashFlow: (Boolean, Double) -> Unit,
    onDismissAddOperation: () -> Unit,
    onSaveOperation: (AssetEntity, Boolean, Double, Double, Double) -> Unit
) {
    if (payoutToConfirm != null) {
        ReceivePayoutDialog(
            payout = payoutToConfirm,
            onDismiss = onDismissPayoutConfirmation,
            onConfirm = onConfirmPayout
        )
    }
    if (showAddPayout) {
        AddPayoutDialog(
            assets = assets,
            onDismiss = onDismissAddPayout,
            onSave = onSavePayout
        )
    }
    if (showCashFlow) {
        CashFlowDialog(
            onDismiss = onDismissCashFlow,
            onSave = onSaveCashFlow
        )
    }
    if (showAddOperation) {
        AddOperationDialog(
            assets = assets,
            onDismiss = onDismissAddOperation,
            onSave = onSaveOperation
        )
    }
}
