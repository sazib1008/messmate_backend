package com.example.messmate_backend

import com.example.messmate_backend.config.DatabaseConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DatabaseConfigTest {

    @Test
    fun testParseStandardRenderPostgresUrl() {
        val renderUrl = "postgres://render_user:secret_pass@dpg-c1234567-a.singapore-postgres.render.com:5432/messmate_db"
        val (jdbcUrl, user, pass) = DatabaseConfig.parseDatabaseUrl(renderUrl)

        assertEquals("render_user", user)
        assertEquals("secret_pass", pass)
        assertTrue(jdbcUrl.startsWith("jdbc:postgresql://dpg-c1234567-a.singapore-postgres.render.com:5432/messmate_db"))
        assertTrue(jdbcUrl.contains("sslmode=require"))
        assertTrue(jdbcUrl.contains("prepareThreshold=0"))
    }

    @Test
    fun testParsePostgresqlSchemeWithoutPort() {
        val renderUrl = "postgresql://myuser:mypass@oregon-postgres.render.com/prod_db"
        val (jdbcUrl, user, pass) = DatabaseConfig.parseDatabaseUrl(renderUrl)

        assertEquals("myuser", user)
        assertEquals("mypass", pass)
        assertTrue(jdbcUrl.startsWith("jdbc:postgresql://oregon-postgres.render.com:5432/prod_db"))
        assertTrue(jdbcUrl.contains("sslmode=require"))
    }

    @Test
    fun testPassThroughJdbcUrl() {
        val jdbcUrlInput = "jdbc:postgresql://ep-neon.aws.neon.tech/neondb?sslmode=require"
        val (jdbcUrl, user, pass) = DatabaseConfig.parseDatabaseUrl(jdbcUrlInput, "defaultUser", "defaultPass")

        assertEquals(jdbcUrlInput, jdbcUrl)
        assertEquals("defaultUser", user)
        assertEquals("defaultPass", pass)
    }
}
