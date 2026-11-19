package ru.mycapital.app.data

import androidx.room.Database
import org.junit.Assert.assertEquals
import org.junit.Test

class PortfolioDatabaseVersionTest {
    @Test
    fun roomAnnotationUsesTheSingleDeclaredSchemaVersion() {
        val annotation = PortfolioDatabase::class.java.getAnnotation(Database::class.java)
        assertEquals(PORTFOLIO_DATABASE_VERSION, annotation.version)
    }
}
