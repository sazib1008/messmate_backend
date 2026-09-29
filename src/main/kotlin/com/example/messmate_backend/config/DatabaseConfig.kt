package com.example.messmate_backend.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.net.URI
import javax.sql.DataSource

/**
 * Custom DataSource configuration to provide seamless compatibility with Render,
 * Heroku, Neon, and local PostgreSQL databases.
 *
 * Render automatically injects `DATABASE_URL` in standard URI format:
 * `postgres://user:password@host:port/database`
 *
 * This configuration automatically converts such URLs to JDBC format:
 * `jdbc:postgresql://host:port/database?sslmode=require&prepareThreshold=0`
 * while falling back gracefully to `DB_URL` / `spring.datasource.url`.
 */
@Configuration
class DatabaseConfig(
    @Value("\${spring.datasource.url:}") private val defaultUrl: String,
    @Value("\${spring.datasource.username:}") private val defaultUsername: String,
    @Value("\${spring.datasource.password:}") private val defaultPassword: String,
    @Value("\${spring.datasource.driver-class-name:org.postgresql.Driver}") private val driverClassName: String,
    @Value("\${spring.datasource.hikari.maximum-pool-size:10}") private val maxPoolSize: Int,
    @Value("\${spring.datasource.hikari.minimum-idle:2}") private val minIdle: Int,
    @Value("\${spring.datasource.hikari.idle-timeout:30000}") private val idleTimeout: Long,
    @Value("\${spring.datasource.hikari.max-lifetime:60000}") private val maxLifetime: Long,
    @Value("\${spring.datasource.hikari.connection-timeout:30000}") private val connectionTimeout: Long
) {
    private val logger = LoggerFactory.getLogger(DatabaseConfig::class.java)

    @Bean
    @Primary
    fun dataSource(): DataSource {
        val databaseUrlEnv = System.getenv("DATABASE_URL")
        val hikariConfig = HikariConfig()
        hikariConfig.driverClassName = driverClassName
        hikariConfig.maximumPoolSize = maxPoolSize
        hikariConfig.minimumIdle = minIdle
        hikariConfig.idleTimeout = idleTimeout
        hikariConfig.maxLifetime = maxLifetime
        hikariConfig.connectionTimeout = connectionTimeout

        if (!databaseUrlEnv.isNullOrBlank()) {
            val (resolvedUrl, user, pass) = parseDatabaseUrl(databaseUrlEnv, defaultUsername, defaultPassword)
            logger.info("Configuring DataSource from DATABASE_URL environment variable: {}", maskJdbcUrl(resolvedUrl))
            hikariConfig.jdbcUrl = resolvedUrl
            hikariConfig.username = user
            hikariConfig.password = pass
        } else {
            logger.info("Configuring DataSource from spring.datasource.url: {}", maskJdbcUrl(defaultUrl))
            hikariConfig.jdbcUrl = defaultUrl
            hikariConfig.username = defaultUsername
            hikariConfig.password = defaultPassword
        }

        return HikariDataSource(hikariConfig)
    }

    companion object {
        fun parseDatabaseUrl(
            rawUrl: String,
            fallbackUser: String = "",
            fallbackPass: String = ""
        ): Triple<String, String, String> {
            val trimmed = rawUrl.trim()
            if (trimmed.startsWith("jdbc:")) {
                return Triple(trimmed, fallbackUser, fallbackPass)
            }
            if (trimmed.startsWith("postgres://") || trimmed.startsWith("postgresql://")) {
                val normalizedUriString = trimmed
                    .replaceFirst("postgresql://", "http://")
                    .replaceFirst("postgres://", "http://")
                val uri = URI(normalizedUriString)
                val userInfo = uri.userInfo?.split(":") ?: emptyList()
                val username = if (userInfo.isNotEmpty() && userInfo[0].isNotEmpty()) userInfo[0] else fallbackUser
                val password = if (userInfo.size > 1 && userInfo[1].isNotEmpty()) userInfo[1] else fallbackPass
                val host = uri.host ?: "localhost"
                val port = if (uri.port != -1) uri.port else 5432
                val path = uri.path?.trimStart('/') ?: ""
                
                val queryParams = mutableListOf<String>()
                if (!uri.query.isNullOrBlank()) {
                    queryParams.add(uri.query)
                }
                if (queryParams.none { it.contains("sslmode=") }) {
                    queryParams.add("sslmode=require")
                }
                if (queryParams.none { it.contains("prepareThreshold=") }) {
                    queryParams.add("prepareThreshold=0")
                }
                val queryString = if (queryParams.isNotEmpty()) "?" + queryParams.joinToString("&") else ""
                val jdbcUrl = "jdbc:postgresql://$host:$port/$path$queryString"
                return Triple(jdbcUrl, username, password)
            }
            return Triple(trimmed, fallbackUser, fallbackPass)
        }

        private fun maskJdbcUrl(url: String): String {
            return url.replace(Regex("://[^@]+@"), "://***:***@")
        }
    }
}
