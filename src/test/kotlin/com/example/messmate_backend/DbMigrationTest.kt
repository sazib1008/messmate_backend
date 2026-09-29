package com.example.messmate_backend

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

/**
 * One-time DB migration: ensure status columns that may be native PG enum types
 * are altered to varchar(50) for Spring Data JPQL compatibility.
 * Run this test once and then it's safe to ignore.
 */
@SpringBootTest
class DbMigrationTest {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun migrateStatusColumnsToVarchar() {
        val migrations = listOf(
            // meal_join_requests.status: may be a PG enum type "JoinRequestStatus"
            """
            DO ${'$'}${'$'}
            BEGIN
                IF EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_name = 'meal_join_requests'
                    AND column_name = 'status'
                    AND udt_name != 'varchar'
                ) THEN
                    ALTER TABLE meal_join_requests
                    ALTER COLUMN status TYPE varchar(50) USING status::text;
                END IF;
            END;
            ${'$'}${'$'};
            """.trimIndent(),
            // expenses.category: ensure altered to varchar(50) for dynamic category support
            """
            DO ${'$'}${'$'}
            BEGIN
                IF EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_name = 'expenses'
                    AND column_name = 'category'
                    AND udt_name != 'varchar'
                ) THEN
                    ALTER TABLE expenses
                    ALTER COLUMN category TYPE varchar(50) USING category::text;
                END IF;
            END;
            ${'$'}${'$'};
            ALTER TABLE expenses ADD COLUMN IF NOT EXISTS "targetMemberId" varchar(255);
            ALTER TABLE expenses ADD COLUMN IF NOT EXISTS "participantIds" text;
            ALTER TABLE meal_calculations ADD COLUMN IF NOT EXISTS "mealVariableExpenses" numeric(12, 2) DEFAULT 0;
            ALTER TABLE meal_calculations ADD COLUMN IF NOT EXISTS "fixedOverheadExpenses" numeric(12, 2) DEFAULT 0;
            ALTER TABLE meal_calculations ADD COLUMN IF NOT EXISTS "individualDirectExpenses" numeric(12, 2) DEFAULT 0;
            ALTER TABLE meal_calculations ADD COLUMN IF NOT EXISTS "adHocSpecialExpenses" numeric(12, 2) DEFAULT 0;
            ALTER TABLE student_cycle_summaries ADD COLUMN IF NOT EXISTS "fixedOverheadCost" numeric(10, 2) DEFAULT 0;
            ALTER TABLE student_cycle_summaries ADD COLUMN IF NOT EXISTS "individualDirectCost" numeric(10, 2) DEFAULT 0;
            ALTER TABLE student_cycle_summaries ADD COLUMN IF NOT EXISTS "adHocSpecialCost" numeric(10, 2) DEFAULT 0;
            ALTER TABLE meal_session_configs ADD COLUMN IF NOT EXISTS "servingStartTime" varchar(20);
            ALTER TABLE meal_session_configs ADD COLUMN IF NOT EXISTS "servingEndTime" varchar(20);
            UPDATE meal_session_configs SET "servingStartTime" = '07:30', "servingEndTime" = '09:30' WHERE session = 'BREAKFAST' AND "servingStartTime" IS NULL;
            UPDATE meal_session_configs SET "servingStartTime" = '13:00', "servingEndTime" = '14:30' WHERE session = 'LUNCH' AND "servingStartTime" IS NULL;
            UPDATE meal_session_configs SET "servingStartTime" = '20:30', "servingEndTime" = '22:00' WHERE session = 'DINNER' AND "servingStartTime" IS NULL;
            """.trimIndent()
        )

        for (sql in migrations) {
            try {
                jdbcTemplate.execute(sql)
                println("✅ Migration executed successfully")
            } catch (e: Exception) {
                println("Migration note: ${e.message}")
            }
        }
        println("DB migration check complete.")
    }

    @Test
    fun testInspectConfigs() {
        val configs = jdbcTemplate.queryForList("SELECT id, \"messId\", \"defaultBreakfastOn\", \"defaultLunchOn\", \"defaultDinnerOn\" FROM dining_configurations")
        println("=== DINING CONFIGURATIONS ===")
        configs.forEach { println(it) }

        val sessions = jdbcTemplate.queryForList("SELECT id, \"diningConfigId\", session, \"isEnabled\", \"cutoffTime\", \"servingStartTime\", \"servingEndTime\" FROM meal_session_configs")
        println("=== MEAL SESSION CONFIGS ===")
        sessions.forEach { println(it) }
    }
}
