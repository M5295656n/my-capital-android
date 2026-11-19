package ru.mycapital.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PortfolioDatabaseVersionTest {
    @Test
    fun currentRoomSchemaVersionIsDeclaredOnceForDatabaseAndBackup() {
        // PortfolioDatabase @Database and PortfolioBackup validation both consume this constant.
        assertEquals(19, PORTFOLIO_DATABASE_VERSION)
    }
}
