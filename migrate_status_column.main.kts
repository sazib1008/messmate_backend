#!/usr/bin/env kotlin

@file:DependsOn("org.postgresql:postgresql:42.7.3")

import java.sql.DriverManager

val url = "jdbc:postgresql://ep-falling-feather-b354yldv-pooler.c-4.ap-southeast-1.aws.neon.tech/neondb?sslmode=require"
val user = "neondb_owner"
val password = "npg_CdV3Nl1AqrGW"

DriverManager.getConnection(url, user, password).use { conn ->
    // Check current column type
    val checkSql = """
        SELECT column_name, data_type, udt_name 
        FROM information_schema.columns 
        WHERE table_name = 'meal_join_requests' 
        ORDER BY ordinal_position
    """.trimIndent()
    
    conn.createStatement().use { stmt ->
        val rs = stmt.executeQuery(checkSql)
        println("=== meal_join_requests columns ===")
        while (rs.next()) {
            println("  ${rs.getString("column_name")}: ${rs.getString("data_type")} (${rs.getString("udt_name")})")
        }
    }
    
    // Alter status column to varchar if it's a custom enum type
    try {
        conn.createStatement().use { stmt ->
            stmt.execute("""
                ALTER TABLE meal_join_requests 
                ALTER COLUMN status TYPE varchar(50) 
                USING status::text
            """.trimIndent())
            println("✅ Altered meal_join_requests.status to varchar(50)")
        }
    } catch (e: Exception) {
        println("Column alter result: ${e.message}")
    }
    
    println("Done!")
}
