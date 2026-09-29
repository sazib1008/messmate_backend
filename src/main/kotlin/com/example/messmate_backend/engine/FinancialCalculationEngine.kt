package com.example.messmate_backend.engine

import com.example.messmate_backend.model.enums.ExpenseCategory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

data class CycleTimeline(
    val startDate: LocalDate,
    val targetActiveDays: Int = 30,
    val pausedDates: Set<LocalDate> = emptySet()
) {
    /**
     * Calculates the projected calendar end date such that exactly `targetActiveDays`
     * non-paused calendar days are scheduled.
     */
    fun calculateScheduledEndDate(): LocalDate {
        var activeDaysCounted = 0
        var cursor = startDate

        while (activeDaysCounted < targetActiveDays) {
            if (!pausedDates.contains(cursor)) {
                activeDaysCounted++
            }
            if (activeDaysCounted < targetActiveDays) {
                cursor = cursor.plusDays(1)
            }
        }
        return cursor
    }

    /**
     * Calculates the number of non-paused active dining days between startDate and asOfDate (inclusive).
     */
    fun calculateCountedActiveDays(asOfDate: LocalDate): Int {
        if (asOfDate.isBefore(startDate)) return 0
        var activeCount = 0
        var cursor = startDate

        while (!cursor.isAfter(asOfDate)) {
            if (!pausedDates.contains(cursor)) {
                activeCount++
            }
            cursor = cursor.plusDays(1)
        }
        return activeCount
    }
}

data class MemberMealInput(
    val userId: String,
    val fullName: String,
    val joinDate: LocalDate,
    val leaveDate: LocalDate? = null,
    val memberMealUnits: BigDecimal = BigDecimal.ZERO,
    val guestMealUnits: BigDecimal = BigDecimal.ZERO,
    val totalDeposits: BigDecimal = BigDecimal.ZERO
)

data class ExpenseItemInput(
    val id: String = "",
    val category: ExpenseCategory = ExpenseCategory.MEAL_VARIABLE,
    val amount: BigDecimal = BigDecimal.ZERO,
    val targetMemberId: String? = null,
    val participantIds: List<String> = emptyList()
)

data class MemberSettlementSummary(
    val userId: String,
    val fullName: String,
    val activeDays: Int,
    val isProrated: Boolean,
    val proratedJoinDate: LocalDate?,
    val proratedLeaveDate: LocalDate?,
    val totalMemberUnits: BigDecimal,
    val totalGuestUnits: BigDecimal,
    val mealCost: BigDecimal,
    val guestCost: BigDecimal,
    val fixedOverheadCost: BigDecimal = BigDecimal.ZERO,
    val individualDirectCost: BigDecimal = BigDecimal.ZERO,
    val adHocSpecialCost: BigDecimal = BigDecimal.ZERO,
    val totalCost: BigDecimal,
    val totalDeposits: BigDecimal,
    val netBalance: BigDecimal,
    val hasNegativeBalance: Boolean
)

data class CycleCalculationResult(
    val totalExpenses: BigDecimal,
    val mealVariableExpenses: BigDecimal = BigDecimal.ZERO,
    val fixedOverheadExpenses: BigDecimal = BigDecimal.ZERO,
    val individualDirectExpenses: BigDecimal = BigDecimal.ZERO,
    val adHocSpecialExpenses: BigDecimal = BigDecimal.ZERO,
    val totalMemberUnits: BigDecimal,
    val totalGuestUnits: BigDecimal,
    val totalCountedUnits: BigDecimal,
    val mealRate: BigDecimal,
    val isFinal: Boolean,
    val isRateVolatile: Boolean = false,
    val volatilityReason: String? = null,
    val memberSummaries: List<MemberSettlementSummary>
)

object FinancialCalculationEngine {

    /**
     * 4-Tier Streamlined Expense Settlement Engine.
     *
     * 1. MEAL_VARIABLE: Groceries, meat, fish, vegetables, spices, oil, dal.
     *    Meal Rate = sum(MEAL_VARIABLE) / sum(All Consumed Meals including guest meals).
     *    Individual variable cost = member.total_meals * Meal Rate.
     *
     * 2. FIXED_OVERHEAD: Cook salary, LPG gas, utilities, Wi-Fi, cleaning.
     *    Shared equally (1/N) across all active members.
     *    Per member share = sum(FIXED_OVERHEAD) / N.
     *
     * 3. INDIVIDUAL_DIRECT: Seat rent, private penalty, direct member charge.
     *    Charged 100% to target_member_id.
     *
     * 4. AD_HOC_SPECIAL: Feasts, barbecues, one-off events, kitchen repair.
     *    Participant opt-in split: Event Cost / Number of Selected Participants.
     *    Non-participants charged 0. If empty participants, shared equally across all members.
     *
     * 5. Final Member Bill:
     *    totalCost = variableCost + fixedOverheadCost + individualDirectCost + adHocSpecialCost
     *    netBalance = totalDeposits - totalCost
     */
    fun calculateSettlement(
        timeline: CycleTimeline,
        asOfDate: LocalDate,
        members: List<MemberMealInput>,
        expenses: List<ExpenseItemInput>,
        isFinal: Boolean = false
    ): CycleCalculationResult {
        // 1. Group / Partition Expenses by 4 Canonical Tiers
        val mealVariableExpenses = expenses
            .filter { it.category.canonicalCategory == ExpenseCategory.MEAL_VARIABLE }
            .fold(BigDecimal.ZERO) { acc, e -> acc.add(e.amount) }

        val fixedOverheadExpenses = expenses
            .filter { it.category.canonicalCategory == ExpenseCategory.FIXED_OVERHEAD }
            .fold(BigDecimal.ZERO) { acc, e -> acc.add(e.amount) }

        val individualExpenses = expenses
            .filter { it.category.canonicalCategory == ExpenseCategory.INDIVIDUAL_DIRECT }

        val individualDirectTotal = individualExpenses
            .fold(BigDecimal.ZERO) { acc, e -> acc.add(e.amount) }

        val adHocExpenses = expenses
            .filter { it.category.canonicalCategory == ExpenseCategory.AD_HOC_SPECIAL }

        val adHocSpecialTotal = adHocExpenses
            .fold(BigDecimal.ZERO) { acc, e -> acc.add(e.amount) }

        val totalExpenses = expenses.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.amount) }

        // 2. Units (Consumed Meals)
        // Rule 1: Guest meals add directly to the host member's total meal count: host.total_meals += guest_meals
        val totalMemberUnits = members.fold(BigDecimal.ZERO) { acc, m -> acc.add(m.memberMealUnits) }
        val totalGuestUnits = members.fold(BigDecimal.ZERO) { acc, m -> acc.add(m.guestMealUnits) }
        val totalCountedUnits = totalMemberUnits.add(totalGuestUnits)

        // 3. Rule 1: Meal Rate Engine
        // Meal Rate = (sum of MEAL_VARIABLE Expenses) / (sum of All Consumed Meals including guest meals)
        val mealRate = if (totalCountedUnits > BigDecimal.ZERO) {
            mealVariableExpenses.divide(totalCountedUnits, 4, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }

        // 4. Rule 2: Fixed Overhead Engine
        // Regardless of days present or meals eaten, all active members share fixed costs equally (1/N)
        val activeMemberCount = members.size
        val fixedOverheadPerMember = if (activeMemberCount > 0) {
            fixedOverheadExpenses.divide(BigDecimal(activeMemberCount), 2, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }

        val cycleEnd = timeline.calculateScheduledEndDate()
        val effectiveEvaluationEnd = if (asOfDate.isBefore(cycleEnd)) asOfDate else cycleEnd

        // 5. Calculate settlement per member
        val summaries = members.map { member ->
            val effectiveStart = if (member.joinDate.isAfter(timeline.startDate)) member.joinDate else timeline.startDate
            val effectiveEnd = if (member.leaveDate != null && member.leaveDate.isBefore(effectiveEvaluationEnd)) {
                member.leaveDate
            } else {
                effectiveEvaluationEnd
            }

            val isProrated = member.joinDate.isAfter(timeline.startDate) ||
                    (member.leaveDate != null && member.leaveDate.isBefore(cycleEnd))

            var memberActiveDays = 0
            if (!effectiveStart.isAfter(effectiveEnd)) {
                var c = effectiveStart
                while (!c.isAfter(effectiveEnd)) {
                    if (!timeline.pausedDates.contains(c)) {
                        memberActiveDays++
                    }
                    c = c.plusDays(1)
                }
            }

            // Rule 1: Individual variable cost: member.total_meals * Meal Rate
            val mealCost = member.memberMealUnits.multiply(mealRate).setScale(2, RoundingMode.HALF_UP)
            val guestCost = member.guestMealUnits.multiply(mealRate).setScale(2, RoundingMode.HALF_UP)
            val variableCost = mealCost.add(guestCost)

            // Rule 2: Fixed Overhead Share (1/N)
            val fixedOverheadCost = fixedOverheadPerMember

            // Rule 3: Individual Charges Engine (100% to target_member_id)
            val individualDirectCost = individualExpenses
                .filter { it.targetMemberId == member.userId }
                .fold(BigDecimal.ZERO) { acc, e -> acc.add(e.amount) }
                .setScale(2, RoundingMode.HALF_UP)

            // Rule 4: Ad-Hoc / Special Event Engine (Participant Split)
            var memberAdHocCost = BigDecimal.ZERO
            for (adHoc in adHocExpenses) {
                if (adHoc.participantIds.isNotEmpty()) {
                    if (adHoc.participantIds.contains(member.userId)) {
                        val pCount = adHoc.participantIds.size
                        val perParticipant = adHoc.amount.divide(BigDecimal(pCount), 2, RoundingMode.HALF_UP)
                        memberAdHocCost = memberAdHocCost.add(perParticipant)
                    }
                } else {
                    if (activeMemberCount > 0) {
                        val perMember = adHoc.amount.divide(BigDecimal(activeMemberCount), 2, RoundingMode.HALF_UP)
                        memberAdHocCost = memberAdHocCost.add(perMember)
                    }
                }
            }
            val adHocSpecialCost = memberAdHocCost.setScale(2, RoundingMode.HALF_UP)

            // Rule 5: Final Member Bill Settlement
            val totalCost = variableCost.add(fixedOverheadCost).add(individualDirectCost).add(adHocSpecialCost)
            val netBalance = member.totalDeposits.subtract(totalCost).setScale(2, RoundingMode.HALF_UP)
            val hasNegativeBalance = netBalance < BigDecimal.ZERO

            MemberSettlementSummary(
                userId = member.userId,
                fullName = member.fullName,
                activeDays = memberActiveDays,
                isProrated = isProrated,
                proratedJoinDate = if (member.joinDate.isAfter(timeline.startDate)) member.joinDate else null,
                proratedLeaveDate = if (member.leaveDate != null && member.leaveDate.isBefore(cycleEnd)) member.leaveDate else null,
                totalMemberUnits = member.memberMealUnits.setScale(2, RoundingMode.HALF_UP),
                totalGuestUnits = member.guestMealUnits.setScale(2, RoundingMode.HALF_UP),
                mealCost = mealCost,
                guestCost = guestCost,
                fixedOverheadCost = fixedOverheadCost,
                individualDirectCost = individualDirectCost,
                adHocSpecialCost = adHocSpecialCost,
                totalCost = totalCost,
                totalDeposits = member.totalDeposits.setScale(2, RoundingMode.HALF_UP),
                netBalance = netBalance,
                hasNegativeBalance = hasNegativeBalance
            )
        }

        val isRateVolatile = !isFinal && totalCountedUnits < BigDecimal("15.0")
        val volatilityReason = if (isRateVolatile) {
            "Provisional rate — early cycle, rate will stabilize as more meals are logged (${totalCountedUnits.stripTrailingZeros().toPlainString()} units so far)"
        } else null

        return CycleCalculationResult(
            totalExpenses = totalExpenses.setScale(2, RoundingMode.HALF_UP),
            mealVariableExpenses = mealVariableExpenses.setScale(2, RoundingMode.HALF_UP),
            fixedOverheadExpenses = fixedOverheadExpenses.setScale(2, RoundingMode.HALF_UP),
            individualDirectExpenses = individualDirectTotal.setScale(2, RoundingMode.HALF_UP),
            adHocSpecialExpenses = adHocSpecialTotal.setScale(2, RoundingMode.HALF_UP),
            totalMemberUnits = totalMemberUnits.setScale(2, RoundingMode.HALF_UP),
            totalGuestUnits = totalGuestUnits.setScale(2, RoundingMode.HALF_UP),
            totalCountedUnits = totalCountedUnits.setScale(2, RoundingMode.HALF_UP),
            mealRate = mealRate,
            isFinal = isFinal,
            isRateVolatile = isRateVolatile,
            volatilityReason = volatilityReason,
            memberSummaries = summaries
        )
    }

    /**
     * Backward-compatible overload treating legacy total expenses as MEAL_VARIABLE.
     */
    fun calculateSettlement(
        timeline: CycleTimeline,
        asOfDate: LocalDate,
        members: List<MemberMealInput>,
        expensesTotal: BigDecimal,
        isFinal: Boolean = false
    ): CycleCalculationResult = calculateSettlement(
        timeline = timeline,
        asOfDate = asOfDate,
        members = members,
        expenses = listOf(ExpenseItemInput(id = "legacy", category = ExpenseCategory.MEAL_VARIABLE, amount = expensesTotal)),
        isFinal = isFinal
    )
}
