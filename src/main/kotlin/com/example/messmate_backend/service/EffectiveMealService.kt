package com.example.messmate_backend.service

import com.example.messmate_backend.dto.DailyMealStatusDto
import com.example.messmate_backend.entity.*
import com.example.messmate_backend.model.enums.MealSession
import com.example.messmate_backend.model.enums.MealStatus
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate

data class EffectiveMealStatusResult(
    val status: MealStatus,
    val unitValue: BigDecimal,
    val isAutoCarried: Boolean,
    val isExplicit: Boolean,
    val reason: String? = null
)

@Service
class EffectiveMealService {

    /**
     * Resolves the single source of truth for a member's meal status on a given date and session.
     *
     * Rules:
     * 1. date < member.joinDate or date > member.leaveDate -> OFF (0 units)
     * 2. date in cycle's paused days (whole day or matching session) -> OFF (0 units)
     * 3. forCalculation == true && date > asOfDate -> OFF (0 units, future pre-bookings do not affect rate)
     * 4. Persisted row exists in daily_meal_statuses -> use it (ON = unitValue, OFF = 0, isAutoCarried = false)
     * 5. No row exists -> resolve mess default for that meal type (isAutoCarried = true)
     */
    fun getEffectiveMealStatus(
        member: MessMembership,
        date: LocalDate,
        session: MealSession,
        cycleConfig: CycleConfiguration?,
        diningConfig: DiningConfiguration?,
        pausedDays: List<CyclePausedDay>,
        persistedRow: DailyMealStatus?,
        forCalculation: Boolean = false,
        asOfDate: LocalDate = LocalDate.now()
    ): EffectiveMealStatusResult {
        // 1. Bound to member membership tenure
        val joinLocalDate = member.joinDate.toLocalDate()
        if (date.isBefore(joinLocalDate)) {
            return EffectiveMealStatusResult(
                status = MealStatus.OFF,
                unitValue = BigDecimal.ZERO,
                isAutoCarried = false,
                isExplicit = false,
                reason = "Prior to join date ($joinLocalDate)"
            )
        }

        val leaveLocalDate = member.leaveDate?.toLocalDate()
        if (leaveLocalDate != null && date.isAfter(leaveLocalDate)) {
            return EffectiveMealStatusResult(
                status = MealStatus.OFF,
                unitValue = BigDecimal.ZERO,
                isAutoCarried = false,
                isExplicit = false,
                reason = "After leave date ($leaveLocalDate)"
            )
        }

        // 2. Bound to cycle paused days (whole day or session-specific)
        val isPaused = pausedDays.any { it.pausedDate == date && (it.session == null || it.session == session) }
        if (isPaused) {
            return EffectiveMealStatusResult(
                status = MealStatus.OFF,
                unitValue = BigDecimal.ZERO,
                isAutoCarried = false,
                isExplicit = false,
                reason = "Cycle paused for $session on $date"
            )
        }

        // 3. For rate calculations: future dates (> asOfDate) must not affect the current rate
        if (forCalculation && date.isAfter(asOfDate)) {
            return EffectiveMealStatusResult(
                status = MealStatus.OFF,
                unitValue = BigDecimal.ZERO,
                isAutoCarried = false,
                isExplicit = false,
                reason = "Future pre-booking excluded from active calculation rate"
            )
        }

        // 4. If explicit persisted row exists, use it
        if (persistedRow != null) {
            val status = persistedRow.status
            val units = if (status == MealStatus.ON) persistedRow.unitValue else BigDecimal.ZERO
            return EffectiveMealStatusResult(
                status = status,
                unitValue = units,
                isAutoCarried = persistedRow.isAutoCarried,
                isExplicit = true
            )
        }

        // 5. No explicit row: resolve default for this session
        val isEnabled = when (session) {
            MealSession.BREAKFAST -> cycleConfig?.breakfastEnabled ?: (diningConfig?.defaultBreakfastOn ?: false)
            MealSession.LUNCH -> cycleConfig?.lunchEnabled ?: true
            MealSession.DINNER -> cycleConfig?.dinnerEnabled ?: true
        }

        if (!isEnabled) {
            return EffectiveMealStatusResult(
                status = MealStatus.OFF,
                unitValue = BigDecimal.ZERO,
                isAutoCarried = true,
                isExplicit = false,
                reason = "Session $session is disabled"
            )
        }

        val defaultOn = when (session) {
            MealSession.BREAKFAST -> diningConfig?.defaultBreakfastOn ?: false
            MealSession.LUNCH -> diningConfig?.defaultLunchOn ?: true
            MealSession.DINNER -> diningConfig?.defaultDinnerOn ?: true
        }

        val multiplier = when (session) {
            MealSession.BREAKFAST -> cycleConfig?.breakfastMultiplier ?: BigDecimal("0.50")
            MealSession.LUNCH -> cycleConfig?.lunchMultiplier ?: BigDecimal("1.00")
            MealSession.DINNER -> cycleConfig?.dinnerMultiplier ?: BigDecimal("1.00")
        }

        return if (defaultOn) {
            EffectiveMealStatusResult(
                status = MealStatus.ON,
                unitValue = multiplier,
                isAutoCarried = true,
                isExplicit = false
            )
        } else {
            EffectiveMealStatusResult(
                status = MealStatus.OFF,
                unitValue = BigDecimal.ZERO,
                isAutoCarried = true,
                isExplicit = false
            )
        }
    }

    /**
     * Calculates the total member meal units across a dining cycle up to asOfDate,
     * deterministically resolving unpersisted defaults on read while respecting
     * joinDate, leaveDate, paused days, future date bounds, and combined session rules.
     */
    fun calculateMemberCycleMealUnits(
        member: MessMembership,
        cycle: DiningCycle,
        cycleConfig: CycleConfiguration?,
        diningConfig: DiningConfiguration?,
        pausedDays: List<CyclePausedDay>,
        persistedRows: List<DailyMealStatus>,
        asOfDate: LocalDate
    ): BigDecimal {
        val rowsByDateAndSession = persistedRows.associateBy { Pair(it.date, it.session) }
        val combinedRule = cycleConfig?.combinedSessionRule ?: "SEPARATE"
        val breakfastMultiplier = cycleConfig?.breakfastMultiplier ?: BigDecimal("0.50")
        val lunchMultiplier = cycleConfig?.lunchMultiplier ?: BigDecimal("1.00")
        val dinnerMultiplier = cycleConfig?.dinnerMultiplier ?: BigDecimal("1.00")

        var totalUnits = BigDecimal.ZERO

        // Bound cursor between cycle start and asOfDate (capped by scheduled end date)
        val cycleEnd = cycle.scheduledEndDate
        val evaluationEnd = if (asOfDate.isBefore(cycleEnd)) asOfDate else cycleEnd

        var cursor = cycle.startDate
        while (!cursor.isAfter(evaluationEnd)) {
            val date = cursor
            cursor = cursor.plusDays(1)

            // Skip if date is before member join date or after member leave date
            if (date.isBefore(member.joinDate.toLocalDate())) continue
            if (member.leaveDate != null && date.isAfter(member.leaveDate!!.toLocalDate())) continue

            // Skip if the entire day is paused
            val wholeDayPaused = pausedDays.any { it.pausedDate == date && it.session == null }
            if (wholeDayPaused) continue

            // Evaluate effective status for each session on this date
            val breakfastStatus = getEffectiveMealStatus(
                member = member,
                date = date,
                session = MealSession.BREAKFAST,
                cycleConfig = cycleConfig,
                diningConfig = diningConfig,
                pausedDays = pausedDays,
                persistedRow = rowsByDateAndSession[Pair(date, MealSession.BREAKFAST)],
                forCalculation = true,
                asOfDate = asOfDate
            )

            val lunchStatus = getEffectiveMealStatus(
                member = member,
                date = date,
                session = MealSession.LUNCH,
                cycleConfig = cycleConfig,
                diningConfig = diningConfig,
                pausedDays = pausedDays,
                persistedRow = rowsByDateAndSession[Pair(date, MealSession.LUNCH)],
                forCalculation = true,
                asOfDate = asOfDate
            )

            val dinnerStatus = getEffectiveMealStatus(
                member = member,
                date = date,
                session = MealSession.DINNER,
                cycleConfig = cycleConfig,
                diningConfig = diningConfig,
                pausedDays = pausedDays,
                persistedRow = rowsByDateAndSession[Pair(date, MealSession.DINNER)],
                forCalculation = true,
                asOfDate = asOfDate
            )

            val hasBreakfast = breakfastStatus.status == MealStatus.ON
            val hasLunch = lunchStatus.status == MealStatus.ON
            val hasDinner = dinnerStatus.status == MealStatus.ON

            when (combinedRule) {
                "LUNCH_DINNER_COMBINED" -> {
                    if (hasBreakfast) totalUnits = totalUnits.add(breakfastMultiplier)
                    if (hasLunch && hasDinner) {
                        totalUnits = totalUnits.add(BigDecimal("1.00"))
                    } else if (hasLunch) {
                        totalUnits = totalUnits.add(lunchMultiplier)
                    } else if (hasDinner) {
                        totalUnits = totalUnits.add(dinnerMultiplier)
                    }
                }
                "FULL_DAY" -> {
                    if (hasBreakfast || hasLunch || hasDinner) {
                        totalUnits = totalUnits.add(BigDecimal("1.00"))
                    }
                }
                else -> {
                    // SEPARATE
                    if (hasBreakfast) totalUnits = totalUnits.add(breakfastStatus.unitValue)
                    if (hasLunch) totalUnits = totalUnits.add(lunchStatus.unitValue)
                    if (hasDinner) totalUnits = totalUnits.add(dinnerStatus.unitValue)
                }
            }
        }

        return totalUnits
    }
}
