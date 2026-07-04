package com.marnie.backend.plugins

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

object DatabaseFactory {
    lateinit var dataSource: HikariDataSource
        private set

    fun init(jdbcUrl: String, user: String, password: String) {
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = user
            this.password = password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            minimumIdle = 1
            // Neon serverless Postgres scales to zero; give connections room
            // to re-establish after cold-start instead of failing fast.
            connectionTimeout = 30_000
            idleTimeout = 60_000
            maxLifetime = 1_800_000
            validationTimeout = 5_000
            addDataSourceProperty("sslmode", "require")
        }
        dataSource = HikariDataSource(config)
        Database.connect(dataSource)
    }

    /** Single-tenant app: just runs [block] in a suspended transaction. */
    suspend fun <T> dbTransaction(block: suspend () -> T): T =
        newSuspendedTransaction { block() }
}
