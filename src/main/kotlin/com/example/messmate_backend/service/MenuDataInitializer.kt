package com.example.messmate_backend.service

import com.example.messmate_backend.repository.MenuRepository
import com.example.messmate_backend.repository.MessRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class MenuDataInitializer(
    private val messRepository: MessRepository,
    private val menuRepository: MenuRepository,
    private val menuService: MenuService,
    private val jdbcTemplate: JdbcTemplate
) : CommandLineRunner {
    private val logger = LoggerFactory.getLogger(MenuDataInitializer::class.java)

    override fun run(vararg args: String) {
        try {
            // Ensure expenses.category column is varchar(50) to support dynamic categories
            val migrationSql = """
                DO $$
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
                $$;
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
            jdbcTemplate.execute(migrationSql)
            logger.info("MenuDataInitializer: Verified 4-tier expense and serving window columns in database.")
        } catch (e: Exception) {
            logger.warn("MenuDataInitializer: DB migration note: ${e.message}")
        }
    }
}
