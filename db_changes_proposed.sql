-- ==============================================================================
-- MessMate Database Proposed Changes
-- File: db_changes_proposed.sql
-- IMPORTANT: READ-ONLY AUDIT - DO NOT EXECUTE AUTOMATICALLY ON PRODUCTION NEON DB
-- ==============================================================================

-- 1. Formalize Unique Constraint on Daily Meal Statuses
-- Currently enforced via an index ("daily_meal_statuses_userId_date_session_key").
-- Adding an explicit named table constraint ensures cross-database consistency
-- (e.g. between PostgreSQL production and H2 in-memory test environments).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_daily_meal_statuses_user_date_session'
    ) THEN
        ALTER TABLE daily_meal_statuses
            ADD CONSTRAINT uq_daily_meal_statuses_user_date_session
            UNIQUE ("userId", date, session);
    END IF;
END $$;

-- 2. Performance Indexes for Query Optimization
-- Speeds up EffectiveMealService range queries and cycle settlements:
CREATE INDEX IF NOT EXISTS idx_daily_meal_statuses_user_cycle
    ON daily_meal_statuses ("userId", "cycleId");

CREATE INDEX IF NOT EXISTS idx_daily_meal_statuses_user_date
    ON daily_meal_statuses ("userId", date);

CREATE INDEX IF NOT EXISTS idx_expenses_cycle_date
    ON expenses ("cycleId", "expenseDate");

CREATE INDEX IF NOT EXISTS idx_deposits_cycle_status
    ON deposits ("cycleId", status);
