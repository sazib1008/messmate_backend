package com.example.messmate_backend.engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class FinancialCalculationEngineTest {

    private val startDate = LocalDate.of(2026, 8, 1)

    @Test
    fun `test normal 30-day cycle without paused days ends on day 30`() {
        val timeline = CycleTimeline(startDate = startDate, targetActiveDays = 30)

        val scheduledEnd = timeline.calculateScheduledEndDate()
        // 2026-08-01 + 29 days = 2026-08-30 (inclusive: 30 days)
        assertEquals(LocalDate.of(2026, 8, 30), scheduledEnd)

        val countedDays = timeline.calculateCountedActiveDays(asOfDate = LocalDate.of(2026, 8, 15))
        assertEquals(15, countedDays)
    }

    @Test
    fun `test cycle auto-extension when days are paused`() {
        // Pause 3 days: Aug 5, Aug 10, Aug 15
        val paused = setOf(
            LocalDate.of(2026, 8, 5),
            LocalDate.of(2026, 8, 10),
            LocalDate.of(2026, 8, 15)
        )
        val timeline = CycleTimeline(startDate = startDate, targetActiveDays = 30, pausedDates = paused)

        val scheduledEnd = timeline.calculateScheduledEndDate()
        // Standard 30 days ends Aug 30. With 3 paused days, it must end on Sep 2!
        assertEquals(LocalDate.of(2026, 9, 2), scheduledEnd)

        // Counted active days up to Aug 15: 15 calendar days minus 3 paused = 12 active days
        val countedDays = timeline.calculateCountedActiveDays(asOfDate = LocalDate.of(2026, 8, 15))
        assertEquals(12, countedDays)
    }

    @Test
    fun `test deterministic meal rate and normal cycle settlement`() {
        val timeline = CycleTimeline(startDate = startDate, targetActiveDays = 30)

        // 3 students:
        // Student A: 60 units, 4000 deposit
        // Student B: 50 units, 3500 deposit
        // Student C: 40 units, 3000 deposit
        // Total units = 150
        // Total expenses = 12,000 BDT
        // Expected Meal Rate = 12,000 / 150 = 80.0000 BDT
        val members = listOf(
            MemberMealInput("1", "Alice", startDate, null, BigDecimal("60.0"), BigDecimal.ZERO, BigDecimal("4000.0")),
            MemberMealInput("2", "Bob", startDate, null, BigDecimal("50.0"), BigDecimal.ZERO, BigDecimal("3500.0")),
            MemberMealInput("3", "Charlie", startDate, null, BigDecimal("40.0"), BigDecimal.ZERO, BigDecimal("3000.0"))
        )

        val result = FinancialCalculationEngine.calculateSettlement(
            timeline = timeline,
            asOfDate = LocalDate.of(2026, 8, 30),
            members = members,
            expensesTotal = BigDecimal("12000.00"),
            isFinal = true
        )

        assertEquals(BigDecimal("12000.00"), result.totalExpenses)
        assertEquals(BigDecimal("150.00"), result.totalCountedUnits)
        assertEquals(BigDecimal("80.0000"), result.mealRate)

        // Alice: 60 * 80 = 4800 cost, deposit 4000 -> net -800 (Negative!)
        val alice = result.memberSummaries.first { it.userId == "1" }
        assertEquals(BigDecimal("4800.00"), alice.mealCost)
        assertEquals(BigDecimal("-800.00"), alice.netBalance)
        assertTrue(alice.hasNegativeBalance)

        // Bob: 50 * 80 = 4000 cost, deposit 3500 -> net -500 (Negative!)
        val bob = result.memberSummaries.first { it.userId == "2" }
        assertEquals(BigDecimal("4000.00"), bob.mealCost)
        assertEquals(BigDecimal("-500.00"), bob.netBalance)
        assertTrue(bob.hasNegativeBalance)

        // Charlie: 40 * 80 = 3200 cost, deposit 3000 -> net -200 (Negative!)
        val charlie = result.memberSummaries.first { it.userId == "3" }
        assertEquals(BigDecimal("3200.00"), charlie.mealCost)
        assertEquals(BigDecimal("-200.00"), charlie.netBalance)
        assertTrue(charlie.hasNegativeBalance)
    }

    @Test
    fun `test mid-cycle join proration`() {
        val timeline = CycleTimeline(startDate = startDate, targetActiveDays = 30)

        // Student joins on Aug 11 (day 11 of cycle)
        val joinDate = LocalDate.of(2026, 8, 11)
        val member = MemberMealInput(
            userId = "joiner",
            fullName = "Mid Joiner",
            joinDate = joinDate,
            leaveDate = null,
            memberMealUnits = BigDecimal("35.0"),
            guestMealUnits = BigDecimal.ZERO,
            totalDeposits = BigDecimal("3500.0")
        )

        val result = FinancialCalculationEngine.calculateSettlement(
            timeline = timeline,
            asOfDate = LocalDate.of(2026, 8, 30),
            members = listOf(member),
            expensesTotal = BigDecimal("2800.00")
        )

        val summary = result.memberSummaries.first()
        assertTrue(summary.isProrated)
        assertEquals(joinDate, summary.proratedJoinDate)
        assertNull(summary.proratedLeaveDate)
        assertEquals(20, summary.activeDays) // Aug 11 to Aug 30 = 20 days
        assertEquals(BigDecimal("2800.00"), summary.mealCost)
        assertEquals(BigDecimal("700.00"), summary.netBalance)
        assertFalse(summary.hasNegativeBalance)
    }

    @Test
    fun `test mid-cycle leave proration`() {
        val timeline = CycleTimeline(startDate = startDate, targetActiveDays = 30)

        // Student leaves on Aug 20 (day 20 of cycle)
        val leaveDate = LocalDate.of(2026, 8, 20)
        val member = MemberMealInput(
            userId = "leaver",
            fullName = "Early Leaver",
            joinDate = startDate,
            leaveDate = leaveDate,
            memberMealUnits = BigDecimal("38.0"),
            guestMealUnits = BigDecimal.ZERO,
            totalDeposits = BigDecimal("4000.0")
        )

        val result = FinancialCalculationEngine.calculateSettlement(
            timeline = timeline,
            asOfDate = LocalDate.of(2026, 8, 30),
            members = listOf(member),
            expensesTotal = BigDecimal("3800.00")
        )

        val summary = result.memberSummaries.first()
        assertTrue(summary.isProrated)
        assertNull(summary.proratedJoinDate)
        assertEquals(leaveDate, summary.proratedLeaveDate)
        assertEquals(20, summary.activeDays) // Aug 1 to Aug 20 = 20 days
        assertEquals(BigDecimal("3800.00"), summary.mealCost)
        assertEquals(BigDecimal("200.00"), summary.netBalance)
        assertFalse(summary.hasNegativeBalance)
    }

    @Test
    fun `test guest meals charged to host student account`() {
        val timeline = CycleTimeline(startDate = startDate, targetActiveDays = 30)

        // Student hosts 5 guest meal units
        val host = MemberMealInput(
            userId = "host1",
            fullName = "Host Student",
            joinDate = startDate,
            memberMealUnits = BigDecimal("45.0"),
            guestMealUnits = BigDecimal("5.0"),
            totalDeposits = BigDecimal("5000.0")
        )

        // Total units = 50. Total expense = 4,000. Rate = 80.00
        val result = FinancialCalculationEngine.calculateSettlement(
            timeline = timeline,
            asOfDate = LocalDate.of(2026, 8, 30),
            members = listOf(host),
            expensesTotal = BigDecimal("4000.00")
        )

        val summary = result.memberSummaries.first()
        assertEquals(BigDecimal("80.0000"), result.mealRate)
        assertEquals(BigDecimal("3600.00"), summary.mealCost)   // 45 * 80
        assertEquals(BigDecimal("400.00"), summary.guestCost)   // 5 * 80
        assertEquals(BigDecimal("4000.00"), summary.totalCost)  // 3600 + 400
        assertEquals(BigDecimal("1000.00"), summary.netBalance) // 5000 - 4000
        assertFalse(summary.hasNegativeBalance)
    }

    @Test
    fun `test negative balance is detected and flagged`() {
        val timeline = CycleTimeline(startDate = startDate, targetActiveDays = 30)

        val studentWithLowDeposit = MemberMealInput(
            userId = "delinquent",
            fullName = "Low Deposit Diner",
            joinDate = startDate,
            memberMealUnits = BigDecimal("50.0"),
            guestMealUnits = BigDecimal.ZERO,
            totalDeposits = BigDecimal("1000.0") // Low deposit
        )

        // Rate = 100.00 -> Cost = 5000.00. Net = 1000 - 5000 = -4000.00
        val result = FinancialCalculationEngine.calculateSettlement(
            timeline = timeline,
            asOfDate = LocalDate.of(2026, 8, 30),
            members = listOf(studentWithLowDeposit),
            expensesTotal = BigDecimal("5000.00")
        )

        val summary = result.memberSummaries.first()
        assertEquals(BigDecimal("-4000.00"), summary.netBalance)
        assertTrue(summary.hasNegativeBalance, "Negative balance must be flagged true")
    }

    @Test
    fun `test 4-tier expense engine separates meal variable from fixed overhead`() {
        val timeline = CycleTimeline(startDate = startDate, targetActiveDays = 30)

        // 2 members: A (60 meals), B (40 meals). Total meals = 100
        val members = listOf(
            MemberMealInput("A", "Alice", startDate, null, BigDecimal("60.0"), BigDecimal.ZERO, BigDecimal("10000.0")),
            MemberMealInput("B", "Bob", startDate, null, BigDecimal("40.0"), BigDecimal.ZERO, BigDecimal("10000.0"))
        )

        // Expenses:
        // MEAL_VARIABLE: 6,000 (Grocery)
        // FIXED_OVERHEAD: 4,000 (Cook salary 3000 + Gas 1000)
        val expenses = listOf(
            ExpenseItemInput(id = "e1", category = com.example.messmate_backend.model.enums.ExpenseCategory.MEAL_VARIABLE, amount = BigDecimal("6000.00")),
            ExpenseItemInput(id = "e2", category = com.example.messmate_backend.model.enums.ExpenseCategory.FIXED_OVERHEAD, amount = BigDecimal("3000.00")),
            ExpenseItemInput(id = "e3", category = com.example.messmate_backend.model.enums.ExpenseCategory.FIXED_OVERHEAD, amount = BigDecimal("1000.00"))
        )

        val result = FinancialCalculationEngine.calculateSettlement(
            timeline = timeline,
            asOfDate = LocalDate.of(2026, 8, 30),
            members = members,
            expenses = expenses,
            isFinal = true
        )

        // Rule 1: Meal Rate = 6000 / 100 = 60.0000 (Fixed overhead does NOT inflate meal rate!)
        assertEquals(BigDecimal("60.0000"), result.mealRate)
        assertEquals(BigDecimal("6000.00"), result.mealVariableExpenses)
        assertEquals(BigDecimal("4000.00"), result.fixedOverheadExpenses)
        assertEquals(BigDecimal("10000.00"), result.totalExpenses)

        // Rule 2: Fixed Overhead per member = 4000 / 2 = 2000.00
        val alice = result.memberSummaries.first { it.userId == "A" }
        val bob = result.memberSummaries.first { it.userId == "B" }

        assertEquals(BigDecimal("3600.00"), alice.mealCost) // 60 * 60
        assertEquals(BigDecimal("2000.00"), alice.fixedOverheadCost)
        assertEquals(BigDecimal("5600.00"), alice.totalCost) // 3600 + 2000
        assertEquals(BigDecimal("4400.00"), alice.netBalance) // 10000 - 5600

        assertEquals(BigDecimal("2400.00"), bob.mealCost) // 40 * 60
        assertEquals(BigDecimal("2000.00"), bob.fixedOverheadCost)
        assertEquals(BigDecimal("4400.00"), bob.totalCost) // 2400 + 2000
        assertEquals(BigDecimal("5600.00"), bob.netBalance) // 10000 - 4400
    }

    @Test
    fun `test individual direct charges charged 100 percent to target member`() {
        val timeline = CycleTimeline(startDate = startDate, targetActiveDays = 30)

        val members = listOf(
            MemberMealInput("A", "Alice", startDate, null, BigDecimal("10.0"), BigDecimal.ZERO, BigDecimal("5000.0")),
            MemberMealInput("B", "Bob", startDate, null, BigDecimal("10.0"), BigDecimal.ZERO, BigDecimal("5000.0"))
        )

        // Meal Rate = 0. Individual Direct charge for Bob = 1200 (seat rent)
        val expenses = listOf(
            ExpenseItemInput(
                id = "e_rent",
                category = com.example.messmate_backend.model.enums.ExpenseCategory.INDIVIDUAL_DIRECT,
                amount = BigDecimal("1200.00"),
                targetMemberId = "B"
            )
        )

        val result = FinancialCalculationEngine.calculateSettlement(
            timeline = timeline,
            asOfDate = LocalDate.of(2026, 8, 30),
            members = members,
            expenses = expenses
        )

        val alice = result.memberSummaries.first { it.userId == "A" }
        val bob = result.memberSummaries.first { it.userId == "B" }

        assertEquals(BigDecimal.ZERO.setScale(2), alice.individualDirectCost)
        assertEquals(BigDecimal("1200.00"), bob.individualDirectCost)
        assertEquals(BigDecimal("3800.00"), bob.netBalance) // 5000 - 1200
    }

    @Test
    fun `test ad hoc special event opt in split among selected participants only`() {
        val timeline = CycleTimeline(startDate = startDate, targetActiveDays = 30)

        // 3 members: A, B, C
        val members = listOf(
            MemberMealInput("A", "Alice", startDate, null, BigDecimal("10.0"), BigDecimal.ZERO, BigDecimal("5000.0")),
            MemberMealInput("B", "Bob", startDate, null, BigDecimal("10.0"), BigDecimal.ZERO, BigDecimal("5000.0")),
            MemberMealInput("C", "Charlie", startDate, null, BigDecimal("10.0"), BigDecimal.ZERO, BigDecimal("5000.0"))
        )

        // BBQ Feast of 1,500 BDT attended ONLY by Alice and Bob
        val expenses = listOf(
            ExpenseItemInput(
                id = "bbq",
                category = com.example.messmate_backend.model.enums.ExpenseCategory.AD_HOC_SPECIAL,
                amount = BigDecimal("1500.00"),
                participantIds = listOf("A", "B")
            )
        )

        val result = FinancialCalculationEngine.calculateSettlement(
            timeline = timeline,
            asOfDate = LocalDate.of(2026, 8, 30),
            members = members,
            expenses = expenses
        )

        val alice = result.memberSummaries.first { it.userId == "A" }
        val bob = result.memberSummaries.first { it.userId == "B" }
        val charlie = result.memberSummaries.first { it.userId == "C" }

        // 1500 / 2 = 750 each for Alice and Bob; Charlie paid 0
        assertEquals(BigDecimal("750.00"), alice.adHocSpecialCost)
        assertEquals(BigDecimal("750.00"), bob.adHocSpecialCost)
        assertEquals(BigDecimal.ZERO.setScale(2), charlie.adHocSpecialCost)
    }

    @Test
    fun `test complete final member bill settlement with all 4 tiers`() {
        val timeline = CycleTimeline(startDate = startDate, targetActiveDays = 30)

        // 2 members:
        // Host (Alice): 30 meals + 10 guest meals = 40 meals. Deposit: 8,000
        // Bob: 20 meals. Deposit: 6,000
        // Total meals = 60
        val members = listOf(
            MemberMealInput("A", "Alice", startDate, null, BigDecimal("30.0"), BigDecimal("10.0"), BigDecimal("8000.0")),
            MemberMealInput("B", "Bob", startDate, null, BigDecimal("20.0"), BigDecimal.ZERO, BigDecimal("6000.0"))
        )

        val expenses = listOf(
            // 1. Variable groceries: 3,000 -> Meal rate = 3000 / 60 = 50.0000
            ExpenseItemInput("v1", com.example.messmate_backend.model.enums.ExpenseCategory.MEAL_VARIABLE, BigDecimal("3000.00")),
            // 2. Fixed overhead: 2,000 -> 1,000 per member (N=2)
            ExpenseItemInput("f1", com.example.messmate_backend.model.enums.ExpenseCategory.FIXED_OVERHEAD, BigDecimal("2000.00")),
            // 3. Individual charge: 500 for Alice (private room heater electric)
            ExpenseItemInput("i1", com.example.messmate_backend.model.enums.ExpenseCategory.INDIVIDUAL_DIRECT, BigDecimal("500.00"), targetMemberId = "A"),
            // 4. Ad-hoc feast: 800 attended by Alice & Bob (400 each)
            ExpenseItemInput("a1", com.example.messmate_backend.model.enums.ExpenseCategory.AD_HOC_SPECIAL, BigDecimal("800.00"), participantIds = listOf("A", "B"))
        )

        val result = FinancialCalculationEngine.calculateSettlement(
            timeline = timeline,
            asOfDate = LocalDate.of(2026, 8, 30),
            members = members,
            expenses = expenses,
            isFinal = true
        )

        assertEquals(BigDecimal("50.0000"), result.mealRate)

        val alice = result.memberSummaries.first { it.userId == "A" }
        // Alice variable: (30 + 10) * 50 = 2000
        assertEquals(BigDecimal("1500.00"), alice.mealCost)
        assertEquals(BigDecimal("500.00"), alice.guestCost)
        assertEquals(BigDecimal("1000.00"), alice.fixedOverheadCost)
        assertEquals(BigDecimal("500.00"), alice.individualDirectCost)
        assertEquals(BigDecimal("400.00"), alice.adHocSpecialCost)
        // Alice total = 2000 + 1000 + 500 + 400 = 3900.00
        assertEquals(BigDecimal("3900.00"), alice.totalCost)
        // Alice net balance = 8000 - 3900 = 4100.00
        assertEquals(BigDecimal("4100.00"), alice.netBalance)

        val bob = result.memberSummaries.first { it.userId == "B" }
        // Bob variable: 20 * 50 = 1000
        assertEquals(BigDecimal("1000.00"), bob.mealCost)
        assertEquals(BigDecimal("1000.00"), bob.fixedOverheadCost)
        assertEquals(BigDecimal.ZERO.setScale(2), bob.individualDirectCost)
        assertEquals(BigDecimal("400.00"), bob.adHocSpecialCost)
        // Bob total = 1000 + 1000 + 0 + 400 = 2400.00
        assertEquals(BigDecimal("2400.00"), bob.totalCost)
        // Bob net balance = 6000 - 2400 = 3600.00
        assertEquals(BigDecimal("3600.00"), bob.netBalance)
    }

    @Test
    fun `test early-cycle low denominator volatility detection and bad expense recovery`() {
        val timeline = CycleTimeline(startDate = startDate, targetActiveDays = 30)

        // Early cycle scenario: Only 1 total meal unit consumed so far across 2 members
        // Member 1 (Manager/Sazib): 1 meal unit, 8520 deposit
        // Member 2 (Student/Shakil): 0 meal units, 10560 deposit
        val members = listOf(
            MemberMealInput("sazib", "Sazib Hossain", startDate, null, BigDecimal("1.0"), BigDecimal.ZERO, BigDecimal("8520.0")),
            MemberMealInput("shakil", "Shakil Hossain", startDate, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("10560.0"))
        )

        val legitimateExpense = ExpenseItemInput(
            id = "exp-legit",
            category = com.example.messmate_backend.model.enums.ExpenseCategory.MEAL_VARIABLE,
            amount = BigDecimal("1000.00")
        )

        // Mistyped / absurd garbage test expense
        val badExpense = ExpenseItemInput(
            id = "exp-bad",
            category = com.example.messmate_backend.model.enums.ExpenseCategory.MEAL_VARIABLE,
            amount = BigDecimal("222322.00")
        )

        // 1. Calculate with the bad expense included
        val volatileResult = FinancialCalculationEngine.calculateSettlement(
            timeline = timeline,
            asOfDate = startDate.plusDays(1),
            members = members,
            expenses = listOf(legitimateExpense, badExpense),
            isFinal = false
        )

        // Volatility flag should be raised because counted units (1.0) < 15.0
        assertTrue(volatileResult.isRateVolatile)
        assertNotNull(volatileResult.volatilityReason)
        assertEquals(BigDecimal("223322.00"), volatileResult.totalExpenses)
        assertEquals(BigDecimal("1.00"), volatileResult.totalCountedUnits)
        assertEquals(BigDecimal("223322.0000"), volatileResult.mealRate)

        val sazibVolatile = volatileResult.memberSummaries.first { it.userId == "sazib" }
        // 1 unit * 223,322 = 223,322 cost, deposit 8,520 -> -214,802 deficit
        assertEquals(BigDecimal("-214802.00"), sazibVolatile.netBalance)
        assertTrue(sazibVolatile.hasNegativeBalance)

        // 2. Now simulate DELETING the bad expense (only legitimate expense remains)
        val correctedResult = FinancialCalculationEngine.calculateSettlement(
            timeline = timeline,
            asOfDate = startDate.plusDays(1),
            members = members,
            expenses = listOf(legitimateExpense),
            isFinal = false
        )

        // Still volatile because units < 15, but meal rate is sane
        assertTrue(correctedResult.isRateVolatile)
        assertEquals(BigDecimal("1000.00"), correctedResult.totalExpenses)
        assertEquals(BigDecimal("1.00"), correctedResult.totalCountedUnits)
        assertEquals(BigDecimal("1000.0000"), correctedResult.mealRate)

        val sazibCorrected = correctedResult.memberSummaries.first { it.userId == "sazib" }
        // 1 unit * 1,000 = 1,000 cost, deposit 8,520 -> +7,520 positive balance!
        assertEquals(BigDecimal("7520.00"), sazibCorrected.netBalance)
        assertFalse(sazibCorrected.hasNegativeBalance)

        val shakilCorrected = correctedResult.memberSummaries.first { it.userId == "shakil" }
        assertEquals(BigDecimal("10560.00"), shakilCorrected.netBalance)
    }
}

