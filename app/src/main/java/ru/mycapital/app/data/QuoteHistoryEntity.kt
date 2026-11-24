package ru.mycapital.app.data

import androidx.room.Entity
import androidx.room.Index

/**
 * Locally retained quote snapshot. receivedAt is the device time when MyCapital
 * received the response; it is not represented as an exchange event timestamp.
 */
@Entity(
    tableName = "quote_history",
    primaryKeys = ["ticker", "source", "receivedAt"],
    indices = [Index(value = ["ticker", "receivedAt"])]
)
data class QuoteHistoryEntity(
    val ticker: String,
    val source: String,
    val receivedAt: Long,
    val price: Double,
    val board: String?
)
