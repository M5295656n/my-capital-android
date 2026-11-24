package ru.mycapital.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PortfolioDatabaseVersionTest {
    @Test
    fun currentRoomSchemaVersionIsDeclaredOnceForDatabaseAndBackup() {
        // Room and backup validation share this version; v20 adds durable local market quote snapshots.
        assertEquals(20, PORTFOLIO_DATABASE_VERSION)
    }
}
