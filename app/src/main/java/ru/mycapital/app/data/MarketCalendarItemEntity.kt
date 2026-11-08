package ru.mycapital.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted market news and dated macro/corporate announcements.
 * Portfolio payout dates continue to live in the existing payouts table.
 */
@Entity(tableName = "market_calendar_items", indices = [Index(value = ["eventDate"])])
data class MarketCalendarItemEntity(
    @PrimaryKey val sourceKey: String,
    val itemType: String,
    val category: String,
    val eventDate: Long,
    val publishedAt: Long?,
    val title: String,
    val summary: String,
    val sourceName: String,
    val sourceUrl: String,
    val relatedTickers: String = "",
    val importance: String = "NORMAL",
    val impactAssessment: String = "",
    val impactHorizon: String = "",
    val confidence: String = "LOW",
    val updatedAt: Long = System.currentTimeMillis()
)
