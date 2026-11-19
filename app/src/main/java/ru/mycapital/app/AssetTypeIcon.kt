package ru.mycapital.app

import coil.compose.AsyncImage
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale

@Composable
internal fun DoubleHeadedEagleIcon() {
    val context = LocalContext.current
    AsyncImage(
        model = "file:///android_asset/logos/russian_coat_of_arms_white.svg",
        contentDescription = "Белый герб для ОФЗ",
        modifier = Modifier.size(40.dp),
        imageLoader = AssetLogoCatalog.imageLoader(context),
        contentScale = ContentScale.Fit
    )
}


@Composable
internal fun GoldBullionLogo(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun ingot(left: Float, top: Float, width: Float, height: Float, shade: Color) {
            val path = Path().apply {
                moveTo(left + width * .18f, top)
                lineTo(left + width * .82f, top)
                lineTo(left + width, top + height)
                lineTo(left, top + height)
                close()
            }
            drawPath(path, shade)
            drawLine(
                Color(0xFFFFE9A6),
                Offset(left + width * .2f, top + height * .28f),
                Offset(left + width * .8f, top + height * .28f),
                strokeWidth = size.minDimension * .035f
            )
        }
        ingot(w * .03f, h * .12f, w * .43f, h * .35f, Color(0xFFD99B24))
        ingot(w * .54f, h * .12f, w * .43f, h * .35f, Color(0xFFF0C65A))
        ingot(w * .27f, h * .52f, w * .46f, h * .38f, Color(0xFFFFD76A))
    }
}

@Composable
internal fun AssetTypeIcon(
    assetType: String,
    ticker: String
) {
    val type = assetType.lowercase()
    val normalizedTicker = ticker.trim().uppercase()
    val isBond = type.contains("облигац") || type.contains("офз") || normalizedTicker.startsWith("SU")
    val isGoldFund = normalizedTicker == "GOLD"
    val logoPath = if (isBond || isGoldFund) null else AssetLogoCatalog.assetPathFor(normalizedTicker)
    val context = LocalContext.current

    if (isGoldFund) {
        GoldBullionLogo(Modifier.size(42.dp))
    } else if (isBond) {
        // Match the supplied reference: plain dark medallion with a large
        // white double-headed eagle. No light outline or frame.
        Surface(
            shape = CircleShape,
            color = Color(0xFF29354F),
            modifier = Modifier.size(46.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                DoubleHeadedEagleIcon()
            }
        }
    } else {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(46.dp)) {
            when {
                logoPath != null -> AsyncImage(
                    model = "file:///android_asset/$logoPath",
                    contentDescription = "Логотип $ticker",
                    modifier = Modifier.size(40.dp),
                    imageLoader = AssetLogoCatalog.imageLoader(context),
                    contentScale = ContentScale.Fit
                )
                else -> Text(
                    normalizedTicker.take(4).ifBlank { "?" },
                    color = androidx.compose.ui.graphics.Color(0xFF334155),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
    }
}
