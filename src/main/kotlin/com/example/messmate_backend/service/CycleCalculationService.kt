package com.example.messmate_backend.service

import com.example.messmate_backend.dto.*
import com.example.messmate_backend.engine.*
import com.example.messmate_backend.entity.*
import com.example.messmate_backend.model.enums.*
import com.example.messmate_backend.repository.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class ActiveCycleDto(
    val id: String,
    val messId: String,
    val cycleNumber: Int,
    val startDate: LocalDate,
    val targetActiveDays: Int,
    val countedActiveDays: Int,
    val remainingActiveDays: Int,
    val scheduledEndDate: LocalDate,
    val status: CycleStatus,
    val isExpiringSoon: Boolean,
    val isCompleted: Boolean,
    val pausedDays: List<PausedDayDto>,
    val config: CycleConfigurationDto?
)

data class PausedDayDto(
    val id: String,
    val date: LocalDate,
    val session: MealSession?,
    val reason: String
)

data class PauseDayRequest(
    val messId: String,
    val pausedDate: LocalDate,
    val session: MealSession? = null,
    val reason: String
)

data class PastCycleSummaryDto(
    val id: String,
    val cycleNumber: Int,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val targetActiveDays: Int,
    val totalExpenses: BigDecimal,
    val totalCountedUnits: BigDecimal,
    val mealRate: BigDecimal,
    val status: CycleStatus
)

@Service
class CycleCalculationService(
    private val diningCycleRepository: DiningCycleRepository,
    private val cyclePausedDayRepository: CyclePausedDayRepository,
    private val cycleConfigRepository: CycleConfigurationRepository,
    private val membershipRepository: MessMembershipRepository,
    private val dailyMealStatusRepository: DailyMealStatusRepository,
    private val guestMealRepository: GuestMealRepository,
    private val expenseRepository: ExpenseRepository,
    private val depositRepository: DepositRepository,
    private val mealCalculationRepository: MealCalculationRepository,
    private val studentCycleSummaryRepository: StudentCycleSummaryRepository,
    private val balanceLedgerRepository: BalanceLedgerRepository,
    private val userRepository: UserRepository,
    private val diningConfigRepository: DiningConfigurationRepository
) {

    private val dhakaZone = ZoneId.of("Asia/Dhaka")

    @Transactional
    fun getActiveCycle(messId: String): ActiveCycleDto {
        val today = LocalDate.now(dhakaZone)

        // Find current active or expiring cycle
        var cycle = diningCycleRepository.findFirstByMessIdAndStatus(messId, CycleStatus.ACTIVE)
            .orElseGet {
                diningCycleRepository.findFirstByMessIdAndStatus(messId, CycleStatus.EXPIRING)
                    .orElseGet {
                        diningCycleRepository.findFirstByMessIdAndStatus(messId, CycleStatus.FINALIZING)
                            .orElse(null)
                    }
            }

        // If no active cycle found, retrieve the latest cycle (which could be COMPLETED)
        if (cycle == null) {
            cycle = diningCycleRepository.findAllByMessIdOrderByCycleNumberDesc(messId).firstOrNull()
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No dining cycle found for Meal $messId")
        }

        val pausedDays = cyclePausedDayRepository.findAllByCycleId(cycle.id)
        val pausedDates = pausedDays.filter { it.session == null }.map { it.pausedDate }.toSet()

        val timeline = CycleTimeline(
            startDate = cycle.startDate,
            targetActiveDays = cycle.targetActiveDays,
            pausedDates = pausedDates
        )

        val updatedScheduledEnd = timeline.calculateScheduledEndDate()
        val countedActiveDays = timeline.calculateCountedActiveDays(today)

        if (cycle.status != CycleStatus.COMPLETED && cycle.status != CycleStatus.CANCELLED) {
            var stateChanged = false
            if (cycle.scheduledEndDate != updatedScheduledEnd || cycle.countedActiveDays != countedActiveDays) {
                cycle.scheduledEndDate = updatedScheduledEnd
                cycle.countedActiveDays = countedActiveDays
                stateChanged = true
            }

            // Expiration detection: notify when remaining active days <= 3
            val remainingDays = maxOf(0, cycle.targetActiveDays - countedActiveDays)
            if (remainingDays <= 3 && remainingDays > 0 && cycle.status == CycleStatus.ACTIVE) {
                cycle.status = CycleStatus.EXPIRING
                stateChanged = true
            } else if (remainingDays == 0 && (cycle.status == CycleStatus.ACTIVE || cycle.status == CycleStatus.EXPIRING)) {
                cycle.status = CycleStatus.FINALIZING
                stateChanged = true
            }

            if (stateChanged) {
                diningCycleRepository.save(cycle)
            }
        }

        val remainingActiveDays = maxOf(0, cycle.targetActiveDays - cycle.countedActiveDays)
        val isExpiringSoon = remainingActiveDays in 1..3
        val isCompleted = cycle.status == CycleStatus.COMPLETED

        val configEntity = cycleConfigRepository.findByCycleId(cycle.id).orElse(null)
        val configDto = configEntity?.let { mapCycleConfigDto(it) }

        return ActiveCycleDto(
            id = cycle.id,
            messId = cycle.messId,
            cycleNumber = cycle.cycleNumber,
            startDate = cycle.startDate,
            targetActiveDays = cycle.targetActiveDays,
            countedActiveDays = cycle.countedActiveDays,
            remainingActiveDays = remainingActiveDays,
            scheduledEndDate = cycle.scheduledEndDate,
            status = cycle.status,
            isExpiringSoon = isExpiringSoon,
            isCompleted = isCompleted,
            pausedDays = pausedDays.map {
                PausedDayDto(
                    id = it.id,
                    date = it.pausedDate,
                    session = it.session,
                    reason = it.reason
                )
            },
            config = configDto
        )
    }

    @Transactional
    fun pauseDay(request: PauseDayRequest): ActiveCycleDto {
        val cycle = diningCycleRepository.findFirstByMessIdAndStatus(request.messId, CycleStatus.ACTIVE)
            .orElseGet {
                diningCycleRepository.findFirstByMessIdAndStatus(request.messId, CycleStatus.EXPIRING)
                    .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No active cycle found") }
            }

        val existingPauses = cyclePausedDayRepository.findAllByCycleIdAndPausedDate(cycle.id, request.pausedDate)
        if (request.session == null) {
            if (existingPauses.any { it.session == null }) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Date ${request.pausedDate} is already paused for the whole day")
            }
        } else {
            if (existingPauses.any { it.session == null || it.session == request.session }) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Date ${request.pausedDate} is already paused for session ${request.session}")
            }
        }

        val pausedDay = CyclePausedDay(
            cycleId = cycle.id,
            pausedDate = request.pausedDate,
            session = request.session,
            reason = request.reason.trim()
        )
        cyclePausedDayRepository.save(pausedDay)

        return getActiveCycle(request.messId)
    }

    @Transactional
    fun calculateAndSave(messId: String, isFinal: Boolean = false, confirmForfeitSurplus: Boolean = false): CycleCalculationResult {
        // Fetch cycle (ACTIVE, EXPIRING, or FINALIZING)
        val cycle = diningCycleRepository.findFirstByMessIdAndStatus(messId, CycleStatus.ACTIVE)
            .orElseGet {
                diningCycleRepository.findFirstByMessIdAndStatus(messId, CycleStatus.EXPIRING)
                    .orElseGet {
                        diningCycleRepository.findFirstByMessIdAndStatus(messId, CycleStatus.FINALIZING)
                            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No active or settling cycle found for Meal $messId") }
                    }
            }

        // Cycle Configuration Snapshot
        val cycleConfig = cycleConfigRepository.findByCycleId(cycle.id).orElse(null)
        val breakfastMultiplier = cycleConfig?.breakfastMultiplier ?: BigDecimal("0.50")
        val lunchMultiplier = cycleConfig?.lunchMultiplier ?: BigDecimal("1.00")
        val dinnerMultiplier = cycleConfig?.dinnerMultiplier ?: BigDecimal("1.00")
        val combinedRule = cycleConfig?.combinedSessionRule ?: "SEPARATE"

        val pausedDays = cyclePausedDayRepository.findAllByCycleId(cycle.id)
        val timeline = CycleTimeline(
            startDate = cycle.startDate,
            targetActiveDays = cycle.targetActiveDays,
            pausedDates = pausedDays.filter { it.session == null }.map { it.pausedDate }.toSet()
        )

        // 1. Gather all dining memberships (all active members or members who left during this cycle)
        val memberships = membershipRepository.findAllByMessId(messId)
            .filter { it.role != UserRole.CHEF }
            .filter { it.status == MembershipStatus.ACTIVE || (it.leaveDate != null && !it.leaveDate!!.toLocalDate().isBefore(cycle.startDate)) }

        // 2. Gather expenses
        val expenses = expenseRepository.findAllByCycleIdOrderByExpenseDateDesc(cycle.id)
        val totalExpenses = expenses.fold(BigDecimal.ZERO) { acc, exp -> acc.add(exp.amount) }

        // 3. Gather member data & calculate counted units based on CycleConfiguration rules
        val memberInputs = memberships.map { m ->
            val userId = m.userId
            val userMeals = dailyMealStatusRepository.findAllByUserIdAndCycleId(userId, cycle.id)
                .filter { it.status == MealStatus.ON }

            // Group by date for combined session calculations
            var memberUnits = BigDecimal.ZERO
            if (combinedRule == "LUNCH_DINNER_COMBINED") {
                val mealsByDate = userMeals.groupBy { it.date }
                for ((_, mealsForDay) in mealsByDate) {
                    val hasBreakfast = mealsForDay.any { it.session == MealSession.BREAKFAST }
                    val hasLunch = mealsForDay.any { it.session == MealSession.LUNCH }
                    val hasDinner = mealsForDay.any { it.session == MealSession.DINNER }

                    if (hasBreakfast) memberUnits = memberUnits.add(breakfastMultiplier)
                    if (hasLunch && hasDinner) {
                        memberUnits = memberUnits.add(BigDecimal("1.00")) // Lunch+Dinner combined = 1 meal
                    } else if (hasLunch) {
                        memberUnits = memberUnits.add(lunchMultiplier)
                    } else if (hasDinner) {
                        memberUnits = memberUnits.add(dinnerMultiplier)
                    }
                }
            } else if (combinedRule == "FULL_DAY") {
                val mealsByDate = userMeals.groupBy { it.date }
                for ((_, mealsForDay) in mealsByDate) {
                    val anyMeal = mealsForDay.isNotEmpty()
                    if (anyMeal) memberUnits = memberUnits.add(BigDecimal("1.00"))
                }
            } else {
                // Default: separate sessions using multipliers
                for (meal in userMeals) {
                    val mult = when (meal.session) {
                        MealSession.BREAKFAST -> breakfastMultiplier
                        MealSession.LUNCH -> lunchMultiplier
                        MealSession.DINNER -> dinnerMultiplier
                    }
                    memberUnits = memberUnits.add(mult)
                }
            }

            val guestMeals = guestMealRepository.findAllByCycleIdAndHostUserId(cycle.id, userId)
            val guestUnits = guestMeals.fold(BigDecimal.ZERO) { acc, gm -> acc.add(gm.mealUnits) }

            val deposits = depositRepository.findAllByCycleIdAndUserId(cycle.id, userId)
                .filter { it.status == DepositStatus.APPROVED }
            val totalDeposits = deposits.fold(BigDecimal.ZERO) { acc, dep -> acc.add(dep.amount) }

            MemberMealInput(
                userId = userId,
                fullName = m.user?.fullName ?: "Student",
                joinDate = m.joinDate.toLocalDate(),
                leaveDate = m.leaveDate?.toLocalDate(),
                memberMealUnits = memberUnits,
                guestMealUnits = guestUnits,
                totalDeposits = totalDeposits
            )
        }

        // 4. Run pure financial calculation engine
        val expenseInputs = expenses.map { exp ->
            ExpenseItemInput(
                id = exp.id,
                category = exp.category.canonicalCategory,
                amount = exp.amount,
                targetMemberId = exp.targetMemberId,
                participantIds = exp.participantIds
            )
        }

        val result = FinancialCalculationEngine.calculateSettlement(
            timeline = timeline,
            asOfDate = LocalDate.now(dhakaZone),
            members = memberInputs,
            expenses = expenseInputs,
            isFinal = isFinal
        )

        // 5. Persist or update MealCalculation record
        val existingCalc = mealCalculationRepository.findByCycleId(cycle.id)
        val calculationRecord = existingCalc.orElseGet {
            MealCalculation(cycleId = cycle.id, messId = messId)
        }
        calculationRecord.totalExpenses = result.totalExpenses
        calculationRecord.mealVariableExpenses = result.mealVariableExpenses
        calculationRecord.fixedOverheadExpenses = result.fixedOverheadExpenses
        calculationRecord.individualDirectExpenses = result.individualDirectExpenses
        calculationRecord.adHocSpecialExpenses = result.adHocSpecialExpenses
        calculationRecord.totalMemberUnits = result.totalMemberUnits
        calculationRecord.totalGuestUnits = result.totalGuestUnits
        calculationRecord.totalCountedUnits = result.totalCountedUnits
        calculationRecord.mealRate = result.mealRate
        calculationRecord.calculatedAt = LocalDateTime.now()
        calculationRecord.isFinal = isFinal
        mealCalculationRepository.save(calculationRecord)

        // 6. Persist StudentCycleSummary records
        result.memberSummaries.forEach { summary ->
            val existingSummary = studentCycleSummaryRepository.findByCycleIdAndUserId(cycle.id, summary.userId)
            val summaryRecord = existingSummary.orElseGet {
                StudentCycleSummary(cycleId = cycle.id, userId = summary.userId)
            }
            summaryRecord.activeDays = summary.activeDays
            summaryRecord.isProrated = summary.isProrated
            summaryRecord.proratedJoinDate = summary.proratedJoinDate
            summaryRecord.proratedLeaveDate = summary.proratedLeaveDate
            summaryRecord.totalMemberUnits = summary.totalMemberUnits
            summaryRecord.totalGuestUnits = summary.totalGuestUnits
            summaryRecord.totalMealCost = summary.mealCost
            summaryRecord.totalGuestCost = summary.guestCost
            summaryRecord.fixedOverheadCost = summary.fixedOverheadCost
            summaryRecord.individualDirectCost = summary.individualDirectCost
            summaryRecord.adHocSpecialCost = summary.adHocSpecialCost
            summaryRecord.totalDeposits = summary.totalDeposits
            summaryRecord.netBalance = summary.netBalance
            summaryRecord.hasNegativeBalance = summary.hasNegativeBalance
            summaryRecord.updatedAt = LocalDateTime.now()
            studentCycleSummaryRepository.save(summaryRecord)
        }

        // 7. Update guest meals rate
        for (m in memberships) {
            val guestMeals = guestMealRepository.findAllByCycleIdAndHostUserId(cycle.id, m.userId)
            for (gm in guestMeals) {
                if (isFinal) {
                    gm.finalRate = result.mealRate
                    gm.totalCost = gm.mealUnits.multiply(result.mealRate)
                } else {
                    gm.provisionalRate = result.mealRate
                    gm.totalCost = gm.mealUnits.multiply(result.mealRate)
                }
                guestMealRepository.save(gm)
            }
        }

        // 8. If finalizing: close cycle, write immutable balance ledger entries
        if (isFinal) {
            val carryForwardEnabled = cycleConfig?.defaultCarryForward ?: diningConfigRepository.findByMessId(messId).orElse(null)?.defaultCarryForward ?: true
            if (!carryForwardEnabled) {
                val surplusMembers = result.memberSummaries.filter { it.netBalance > BigDecimal.ZERO }
                if (surplusMembers.isNotEmpty() && !confirmForfeitSurplus) {
                    val totalSurplus = surplusMembers.map { it.netBalance }.fold(BigDecimal.ZERO, BigDecimal::add)
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "${surplusMembers.size} member(s) have a positive balance totaling ৳$totalSurplus that will not carry forward. Confirm you will refund this separately (cash/bKash) before proceeding."
                    )
                }
            }

            cycle.status = CycleStatus.COMPLETED
            cycle.actualEndDate = LocalDate.now(dhakaZone)
            diningCycleRepository.save(cycle)

            result.memberSummaries.forEach { summary ->
                val ledger = BalanceLedger(
                    messId = messId,
                    userId = summary.userId,
                    cycleId = cycle.id,
                    entryType = LedgerEntryType.MEAL_CHARGE,
                    amount = -(summary.totalCost),
                    balanceAfter = summary.netBalance,
                    referenceId = calculationRecord.id,
                    description = "Cycle #${cycle.cycleNumber} Final Settlement (${summary.totalMemberUnits + summary.totalGuestUnits} units @ ${result.mealRate} BDT)"
                )
                balanceLedgerRepository.save(ledger)
            }
        }

        return result
    }

    @Transactional
    fun startNewCycle(messId: String, request: StartNewCycleRequest, callerEmail: String): ActiveCycleDto {
        val caller = userRepository.findByEmail(callerEmail)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Caller not found") }

        val membership = membershipRepository.findByMessIdAndUserId(messId, caller.id)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "You do not belong to this Meal") }

        if (!membership.role.isOwnerOrManager) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only Meal Owner or Managers can start a new cycle")
        }

        // Check if there is an active cycle
        val existingActive = diningCycleRepository.findFirstByMessIdAndStatus(messId, CycleStatus.ACTIVE)
        if (existingActive.isPresent) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Cycle #${existingActive.get().cycleNumber} is still ACTIVE. Please finalize it before starting a new cycle."
            )
        }

        // Get max cycle number
        val allCycles = diningCycleRepository.findAllByMessIdOrderByCycleNumberDesc(messId)
        val nextCycleNumber = (allCycles.firstOrNull()?.cycleNumber ?: 0) + 1

        val prevCompletedCycle = allCycles.firstOrNull { it.status == CycleStatus.COMPLETED }
        val prevSummaries = if (prevCompletedCycle != null) {
            studentCycleSummaryRepository.findAllByCycleId(prevCompletedCycle.id)
        } else {
            emptyList()
        }
        val surplusMembers = prevSummaries.filter { it.netBalance > BigDecimal.ZERO }

        // If carry-forward is disabled and members have surplus, require explicit manager confirmation BEFORE creating the cycle
        if (!request.defaultCarryForward && surplusMembers.isNotEmpty() && !request.confirmForfeitSurplus) {
            val totalSurplus = surplusMembers.map { it.netBalance }.fold(BigDecimal.ZERO, BigDecimal::add)
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "${surplusMembers.size} member(s) have a positive balance totaling ৳$totalSurplus that will not carry forward. Confirm you will refund this separately (cash/bKash) before proceeding."
            )
        }

        val today = LocalDate.now(dhakaZone)
        val targetDays = if (request.targetActiveDays > 0) request.targetActiveDays else 30

        val newCycle = DiningCycle(
            messId = messId,
            cycleNumber = nextCycleNumber,
            startDate = today,
            targetActiveDays = targetDays,
            countedActiveDays = 0,
            scheduledEndDate = today.plusDays(targetDays.toLong()),
            status = CycleStatus.ACTIVE,
            notes = request.notes
        )
        val savedCycle = diningCycleRepository.save(newCycle)

        // Snapshot new configuration for this cycle
        val cycleConfig = CycleConfiguration(
            cycleId = savedCycle.id,
            messId = messId,
            currency = "BDT",
            defaultCarryForward = request.defaultCarryForward,
            breakfastEnabled = request.breakfastEnabled,
            lunchEnabled = request.lunchEnabled,
            dinnerEnabled = request.dinnerEnabled,
            breakfastCutoff = request.breakfastCutoff,
            lunchCutoff = request.lunchCutoff,
            dinnerCutoff = request.dinnerCutoff,
            breakfastMultiplier = request.breakfastMultiplier,
            lunchMultiplier = request.lunchMultiplier,
            dinnerMultiplier = request.dinnerMultiplier,
            combinedSessionRule = request.combinedSessionRule,
            targetActiveDays = targetDays
        )
        cycleConfigRepository.save(cycleConfig)

        // Carry-forward: if enabled, seed an APPROVED Deposit for each student's net balance from
        // the most recently completed cycle into this new cycle. This ensures Cycle N+1 inherits
        // each member's ending balance as an opening balance — otherwise Cycle N+1 would start at 0.
        if (request.defaultCarryForward) {
            val prevCompletedCycle = allCycles.firstOrNull { it.status == CycleStatus.COMPLETED }
            if (prevCompletedCycle != null) {
                val prevSummaries = studentCycleSummaryRepository.findAllByCycleId(prevCompletedCycle.id)
                for (summary in prevSummaries) {
                    // Only create a carry-forward if balance is non-zero
                    if (summary.netBalance.compareTo(BigDecimal.ZERO) != 0) {
                        val carryDeposit = Deposit(
                            cycleId = savedCycle.id,
                            messId = messId,
                            userId = summary.userId,
                            amount = summary.netBalance,
                            depositDate = LocalDateTime.now(),
                            paymentMethod = PaymentMethod.OTHER,
                            transactionRef = "CARRY-FWD-C${prevCompletedCycle.cycleNumber}",
                            status = DepositStatus.APPROVED,
                            notes = "Automated carry-forward balance from Cycle #${prevCompletedCycle.cycleNumber}",
                            approvedById = caller.id
                        )
                        depositRepository.save(carryDeposit)

                        // Also write the carry-forward into the ledger for audit trail
                        val latestLedger = balanceLedgerRepository.findFirstByUserIdOrderByCreatedAtDesc(summary.userId)
                        val prevBalance = latestLedger.map { it.balanceAfter }.orElse(BigDecimal.ZERO)
                        val newBalance = prevBalance.add(summary.netBalance)
                        val ledgerEntry = BalanceLedger(
                            messId = messId,
                            userId = summary.userId,
                            cycleId = savedCycle.id,
                            entryType = LedgerEntryType.ADJUSTMENT,
                            amount = summary.netBalance,
                            balanceAfter = newBalance,
                            referenceId = carryDeposit.id,
                            description = "Carry-forward from Cycle #${prevCompletedCycle.cycleNumber} (net balance: ${summary.netBalance} BDT)",
                            createdAt = LocalDateTime.now()
                        )
                        balanceLedgerRepository.save(ledgerEntry)
                    }
                }
            }
        } else {
            // Carry-forward is disabled. Write a BalanceLedger entry for any member with a positive netBalance, ensuring a permanent audit trail.
            for (summary in surplusMembers) {
                val latestLedger = balanceLedgerRepository.findFirstByUserIdOrderByCreatedAtDesc(summary.userId)
                val prevBalance = latestLedger.map { it.balanceAfter }.orElse(BigDecimal.ZERO)
                val newBalance = prevBalance.subtract(summary.netBalance)
                val ledgerEntry = BalanceLedger(
                    messId = messId,
                    userId = summary.userId,
                    cycleId = savedCycle.id,
                    entryType = LedgerEntryType.REFUND_DUE,
                    amount = -(summary.netBalance),
                    balanceAfter = newBalance,
                    referenceId = prevCompletedCycle?.id,
                    description = "Surplus of ${summary.netBalance} BDT from Cycle #${prevCompletedCycle?.cycleNumber} marked as refund due (carry-forward disabled)",
                    createdAt = LocalDateTime.now()
                )
                balanceLedgerRepository.save(ledgerEntry)
            }
        }

        return getActiveCycle(messId)
    }

    @Transactional(readOnly = true)
    fun getCycleHistory(messId: String): List<PastCycleSummaryDto> {
        val cycles = diningCycleRepository.findAllByMessIdOrderByCycleNumberDesc(messId)
        return cycles.map { cycle ->
            val calc = mealCalculationRepository.findByCycleId(cycle.id).orElse(null)
            PastCycleSummaryDto(
                id = cycle.id,
                cycleNumber = cycle.cycleNumber,
                startDate = cycle.startDate,
                endDate = cycle.actualEndDate ?: cycle.scheduledEndDate,
                targetActiveDays = cycle.targetActiveDays,
                totalExpenses = calc?.totalExpenses ?: BigDecimal.ZERO,
                totalCountedUnits = calc?.totalCountedUnits ?: BigDecimal.ZERO,
                mealRate = calc?.mealRate ?: BigDecimal.ZERO,
                status = cycle.status
            )
        }
    }

    private fun mapCycleConfigDto(cfg: CycleConfiguration): CycleConfigurationDto {
        return CycleConfigurationDto(
            id = cfg.id,
            cycleId = cfg.cycleId,
            mealId = cfg.messId,
            currency = cfg.currency,
            defaultCarryForward = cfg.defaultCarryForward,
            breakfastEnabled = cfg.breakfastEnabled,
            lunchEnabled = cfg.lunchEnabled,
            dinnerEnabled = cfg.dinnerEnabled,
            breakfastCutoff = cfg.breakfastCutoff,
            lunchCutoff = cfg.lunchCutoff,
            dinnerCutoff = cfg.dinnerCutoff,
            breakfastMultiplier = cfg.breakfastMultiplier,
            lunchMultiplier = cfg.lunchMultiplier,
            dinnerMultiplier = cfg.dinnerMultiplier,
            combinedSessionRule = cfg.combinedSessionRule,
            targetActiveDays = cfg.targetActiveDays
        )
    }
}
