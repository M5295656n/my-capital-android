package ru.mycapital.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal enum class Screen(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Overview("Обзор", Icons.Default.Home),
    Assets("Портфель", Icons.Default.AccountBalanceWallet),
    Payouts("Выплаты", Icons.Default.Payments),
    Analytics("Аналитика", Icons.Default.ShowChart),
    TargetPortfolio("План", Icons.Default.PieChart),
    Events("События", Icons.Default.EventNote),
    More("Ещё", Icons.Default.MoreHoriz),
    Accounts("Счета", Icons.Default.AccountBalance),
    Operations("Операции", Icons.Default.SwapHoriz)
}

@Composable

@Composable
internal fun AnalyticsNavigationIcon() {
    val tint = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val barColor = tint.copy(alpha = 0.48f)
        val barWidth = size.width * 0.13f
        val bottom = size.height * 0.94f
        val bars = listOf(
            0.12f to 0.20f,
            0.34f to 0.34f,
            0.56f to 0.48f,
            0.78f to 0.29f
        )
        bars.forEach { (xFraction, heightFraction) ->
            val barHeight = size.height * heightFraction
            drawRoundRect(
                color = barColor,
                topLeft = Offset(size.width * xFraction, bottom - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.035f)
            )
        }
        val trend = Path().apply {
            moveTo(size.width * 0.08f, size.height * 0.43f)
            lineTo(size.width * 0.34f, size.height * 0.27f)
            lineTo(size.width * 0.59f, size.height * 0.35f)
            lineTo(size.width * 0.91f, size.height * 0.10f)
        }
        drawPath(
            path = trend,
            color = tint,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
