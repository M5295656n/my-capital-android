package ru.mycapital.app.data

import androidx.room.Entity

@Entity(tableName = "index_history", primaryKeys = ["indexCode", "dateKey"])
data class IndexHistoryEntity(
    val indexCode: String,
    val dateKey: String,
    val close: Double
)
