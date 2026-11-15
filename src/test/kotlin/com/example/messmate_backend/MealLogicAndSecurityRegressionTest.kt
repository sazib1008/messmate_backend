package com.example.messmate_backend

import com.example.messmate_backend.dto.CreateExpenseRequest
import com.example.messmate_backend.dto.DiningConfigDto
import com.example.messmate_backend.dto.ToggleMealRequest
import com.example.messmate_backend.engine.*
import com.example.messmate_backend.entity.*
import com.example.messmate_backend.model.enums.*
import com.example.messmate_backend.repository.*
import com.example.messmate_backend.service.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.*
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.*

class MealLogicAndSecurityRegressionTest {

    private val effectiveMealService = EffectiveMealService()
    private val dhakaZone = ZoneId.of("Asia/Dhaka")

    // =========================================================================
    // PART 1 REGRESSION TESTS: MEAL LOGIC BUGS
    // =========================================================================

    @Test
    fun `test 6180 BDT expense active cycle with defaults resolved on read produces correct 110_3571 meal rate`() {
        // Active cycle scenario:
        // Cycle starts Nov 3, 2026. As of Nov 16, 2026 (14 elapsed active days).
        // Mess: Lunch (1.0) and Dinner (1.0) are default ON. Breakfast is disabled.
        // Member 1: Sazib Hossain (Owner, joined Nov 3, 0 rows in daily_meal_statuses)
        // Member 2: Md Rasel Rana (Student, joined Nov 3, 2 rows: Nov 16 Lunch ON, Nov 17 Lunch ON)
        val cycleStart = LocalDate.of(2026, 11, 3)
        val asOfDate = LocalDate.of(2026, 11, 16)

        val cycle = DiningCycle(
            id = "c1",
            messId = "mess1",
            cycleNumber = 1,
            startDate = cycleStart,
            targetActiveDays = 30,
            scheduledEndDate = cycleStart.plusDays(30)
        )

        val cycleConfig = CycleConfiguration(
            cycleId = "c1",
            messId = "mess1",
            breakfastEnabled = false,
            lunchEnabled = true,
            dinnerEnabled = true,
            lunchMultiplier = BigDecimal("1.00"),
            dinnerMultiplier = BigDecimal("1.00"),
            combinedSessionRule = "SEPARATE"
        )

        val diningConfig = DiningConfiguration(
            messId = "mess1",
            defaultBreakfastOn = false,
            defaultLunchOn = true,
            defaultDinnerOn = true
        )

        val ownerUser = User(id = "u_owner", email = "sazib@messmate.com", fullName = "Sazib Hossain")
        val ownerMembership = MessMembership(
            id = "m_owner",
            messId = "mess1",
            userId = "u_owner",
            role = UserRole.OWNER,
            status = MembershipStatus.ACTIVE,
            joinDate = cycleStart.atStartOfDay()
        )

        val studentUser = User(id = "u_student", email = "rasel@messmate.com", fullName = "Md Rasel Rana")
        val studentMembership = MessMembership(
            id = "m_student",
            messId = "mess1",
            userId = "u_student",
            role = UserRole.STUDENT,
            status = MembershipStatus.ACTIVE,
            joinDate = cycleStart.atStartOfDay()
        )

        // Sazib has 0 persisted rows (cron was missed while sleeping)
        val ownerPersistedRows = emptyList<DailyMealStatus>()

        // Rasel has 2 persisted rows: Nov 16 Lunch ON, Nov 17 Lunch ON
        val studentPersistedRows = listOf(
            DailyMealStatus(
                id = "dms_1",
                cycleId = "c1",
                messId = "mess1",
                userId = "u_student",
                date = LocalDate.of(2026, 11, 16),
                session = MealSession.LUNCH,
                status = MealStatus.ON,
                unitValue = BigDecimal("1.00"),
                isAutoCarried = false
            ),
            DailyMealStatus(
                id = "dms_2",
                cycleId = "c1",
                messId = "mess1",
                userId = "u_student",
                date = LocalDate.of(2026, 11, 17), // Future pre-booking
                session = MealSession.LUNCH,
                status = MealStatus.ON,
                unitValue = BigDecimal("1.00"),
                isAutoCarried = false
            )
        )

        // 1. Calculate Sazib's effective units: 14 days * 2 sessions (Lunch + Dinner) = 28.0 units
        val ownerUnits = effectiveMealService.calculateMemberCycleMealUnits(
            member = ownerMembership,
            cycle = cycle,
            cycleConfig = cycleConfig,
            diningConfig = diningConfig,
            pausedDays = emptyList(),
            persistedRows = ownerPersistedRows,
            asOfDate = asOfDate
        )
        assertEquals(BigDecimal("28.00"), ownerUnits.setScale(2, RoundingMode.HALF_UP))

        // 2. Calculate Rasel's effective units:
        // Nov 3..Nov 15 (13 days * 2 sessions = 26 units) +
        // Nov 16 (Lunch explicit ON = 1 unit + Dinner default ON = 1 unit -> 2 units) = 28.0 units.
        // Nov 17 pre-booking must be EXCLUDED from asOfDate calculation!
        val studentUnits = effectiveMealService.calculateMemberCycleMealUnits(
            member = studentMembership,
            cycle = cycle,
            cycleConfig = cycleConfig,
            diningConfig = diningConfig,
            pausedDays = emptyList(),
            persistedRows = studentPersistedRows,
            asOfDate = asOfDate
        )
        assertEquals(BigDecimal("28.00"), studentUnits.setScale(2, RoundingMode.HALF_UP))

        // Total Mess Units = 28 + 28 = 56.0
        val totalMessUnits = ownerUnits.add(studentUnits)
        assertEquals(BigDecimal("56.00"), totalMessUnits.setScale(2, RoundingMode.HALF_UP))

        // Financial Calculation Engine Settlement
        val timeline = CycleTimeline(startDate = cycleStart, targetActiveDays = 30)
        val memberInputs = listOf(
            MemberMealInput(
                userId = "u_owner",
                fullName = "Sazib Hossain",
                joinDate = cycleStart,
                memberMealUnits = ownerUnits,
                totalDeposits = BigDecimal("480.00")
            ),
            MemberMealInput(
                userId = "u_student",
                fullName = "Md Rasel Rana",
                joinDate = cycleStart,
                memberMealUnits = studentUnits,
                totalDeposits = BigDecimal("5500.00")
            )
        )

        val expenses = listOf(
            ExpenseItemInput(
                id = "exp1",
                category = ExpenseCategory.MEAL_VARIABLE,
                amount = BigDecimal("6180.00")
            )
        )

        val settlement = FinancialCalculationEngine.calculateSettlement(
            timeline = timeline,
            asOfDate = asOfDate,
            members = memberInputs,
            expenses = expenses,
            isFinal = false
        )

        // Meal rate = 6180.00 / 56.0 = 110.3571 BDT
        assertEquals(BigDecimal("110.3571"), settlement.mealRate)
        assertEquals(BigDecimal("56.00"), settlement.totalCountedUnits)
        assertFalse(settlement.isRateAnomalous)
        assertNull(settlement.rateWarning)

        // Individual variable costs: 28 * 110.3571 = 3090.00 BDT each
        val ownerSummary = settlement.memberSummaries.first { it.userId == "u_owner" }
        assertEquals(BigDecimal("3090.00"), ownerSummary.mealCost)

        val studentSummary = settlement.memberSummaries.first { it.userId == "u_student" }
        assertEquals(BigDecimal("3090.00"), studentSummary.mealCost)

        // Rasel Net Balance = 5500 - 3090 = +2410.00 BDT surplus! (Fixes previous false -5540 debt)
        assertEquals(BigDecimal("2410.00"), studentSummary.netBalance)
        assertFalse(studentSummary.hasNegativeBalance)
    }

    @Test
    fun `test future pre-booked meal excluded from today settlement rate but visible in student status`() {
        val today = LocalDate.now(dhakaZone)
        val futureDate = today.plusDays(2)

        val member = MessMembership(
            id = "m1",
            messId = "mess1",
            userId = "u1",
            role = UserRole.STUDENT,
            status = MembershipStatus.ACTIVE,
            joinDate = today.minusDays(5).atStartOfDay()
        )

        val cycleConfig = CycleConfiguration(
            cycleId = "c1",
            messId = "mess1",
            lunchMultiplier = BigDecimal("1.00"),
            lunchEnabled = true
        )

        val futurePersistedRow = DailyMealStatus(
            id = "dms_future",
            cycleId = "c1",
            messId = "mess1",
            userId = "u1",
            date = futureDate,
            session = MealSession.LUNCH,
            status = MealStatus.ON,
            unitValue = BigDecimal("1.00")
        )

        // 1. When querying for rate calculation (forCalculation = true, asOfDate = today):
        val calcStatus = effectiveMealService.getEffectiveMealStatus(
            member = member,
            date = futureDate,
            session = MealSession.LUNCH,
            cycleConfig = cycleConfig,
            diningConfig = null,
            pausedDays = emptyList(),
            persistedRow = futurePersistedRow,
            forCalculation = true,
            asOfDate = today
        )
        assertEquals(MealStatus.OFF, calcStatus.status)
        assertEquals(BigDecimal.ZERO, calcStatus.unitValue)
        assertEquals("Future pre-booking excluded from active calculation rate", calcStatus.reason)

        // 2. When querying for student display / UI (forCalculation = false):
        val viewStatus = effectiveMealService.getEffectiveMealStatus(
            member = member,
            date = futureDate,
            session = MealSession.LUNCH,
            cycleConfig = cycleConfig,
            diningConfig = null,
            pausedDays = emptyList(),
            persistedRow = futurePersistedRow,
            forCalculation = false,
            asOfDate = today
        )
        assertEquals(MealStatus.ON, viewStatus.status)
        assertEquals(BigDecimal("1.00"), viewStatus.unitValue)
        assertTrue(viewStatus.isExplicit)
    }

    @Test
    fun `test mid-cycle joiner gets 0 meals before joinDate`() {
        val cycleStart = LocalDate.of(2026, 11, 1)
        val joinDate = LocalDate.of(2026, 11, 10)
        val asOfDate = LocalDate.of(2026, 11, 15)

        val member = MessMembership(
            id = "m_mid",
            messId = "mess1",
            userId = "u_mid",
            role = UserRole.STUDENT,
            status = MembershipStatus.ACTIVE,
            joinDate = joinDate.atStartOfDay()
        )

        val cycle = DiningCycle(
            id = "c1",
            messId = "mess1",
            startDate = cycleStart,
            targetActiveDays = 30,
            scheduledEndDate = cycleStart.plusDays(30)
        )

        val cycleConfig = CycleConfiguration(
            cycleId = "c1",
            messId = "mess1",
            breakfastEnabled = false,
            lunchEnabled = true,
            dinnerEnabled = true,
            lunchMultiplier = BigDecimal("1.00"),
            dinnerMultiplier = BigDecimal("1.00"),
            combinedSessionRule = "SEPARATE"
        )

        val diningConfig = DiningConfiguration(
            messId = "mess1",
            defaultBreakfastOn = false,
            defaultLunchOn = true,
            defaultDinnerOn = true
        )

        // Days active: Nov 10 to Nov 15 = 6 days.
        // Units: 6 days * 2 sessions = 12.0 units.
        // Before Nov 10 (Nov 1..Nov 9): 0 units.
        val totalUnits = effectiveMealService.calculateMemberCycleMealUnits(
            member = member,
            cycle = cycle,
            cycleConfig = cycleConfig,
            diningConfig = diningConfig,
            pausedDays = emptyList(),
            persistedRows = emptyList(),
            asOfDate = asOfDate
        )

        assertEquals(BigDecimal("12.00"), totalUnits.setScale(2, RoundingMode.HALF_UP))
    }

    @Test
    fun `test paused days count as 0 meals`() {
        val cycleStart = LocalDate.of(2026, 11, 1)
        val asOfDate = LocalDate.of(2026, 11, 5)

        val member = MessMembership(
            id = "m1",
            messId = "mess1",
            userId = "u1",
            role = UserRole.STUDENT,
            status = MembershipStatus.ACTIVE,
            joinDate = cycleStart.atStartOfDay()
        )

        val cycle = DiningCycle(
            id = "c1",
            messId = "mess1",
            startDate = cycleStart,
            targetActiveDays = 30,
            scheduledEndDate = cycleStart.plusDays(30)
        )

        val cycleConfig = CycleConfiguration(
            cycleId = "c1",
            messId = "mess1",
            breakfastEnabled = false,
            lunchEnabled = true,
            dinnerEnabled = true,
            lunchMultiplier = BigDecimal("1.00"),
            dinnerMultiplier = BigDecimal("1.00"),
            combinedSessionRule = "SEPARATE"
        )

        val diningConfig = DiningConfiguration(
            messId = "mess1",
            defaultBreakfastOn = false,
            defaultLunchOn = true,
            defaultDinnerOn = true
        )

        // Pause Nov 3 completely, and pause Nov 4 Dinner
        val pausedDays = listOf(
            CyclePausedDay(id = "p1", cycleId = "c1", pausedDate = LocalDate.of(2026, 11, 3), session = null, reason = "Holiday"),
            CyclePausedDay(id = "p2", cycleId = "c1", pausedDate = LocalDate.of(2026, 11, 4), session = MealSession.DINNER, reason = "Maintenance")
        )

        // 5 calendar days:
        // Nov 1: 2 units
        // Nov 2: 2 units
        // Nov 3: 0 units (whole day paused)
        // Nov 4: 1 unit (Lunch on, Dinner paused)
        // Nov 5: 2 units
        // Total = 7 units
        val totalUnits = effectiveMealService.calculateMemberCycleMealUnits(
            member = member,
            cycle = cycle,
            cycleConfig = cycleConfig,
            diningConfig = diningConfig,
            pausedDays = pausedDays,
            persistedRows = emptyList(),
            asOfDate = asOfDate
        )

        assertEquals(BigDecimal("7.00"), totalUnits.setScale(2, RoundingMode.HALF_UP))
    }

    @Test
    fun `test default ON vs OFF when row is missing`() {
        val date = LocalDate.of(2026, 11, 15)
        val member = MessMembership(
            id = "m1",
            messId = "mess1",
            userId = "u1",
            role = UserRole.STUDENT,
            status = MembershipStatus.ACTIVE,
            joinDate = date.minusDays(5).atStartOfDay()
        )

        val cycleConfig = CycleConfiguration(
            cycleId = "c1",
            messId = "mess1",
            breakfastEnabled = true,
            lunchEnabled = true,
            dinnerEnabled = true
        )

        val diningConfig = DiningConfiguration(
            messId = "mess1",
            defaultBreakfastOn = false, // Default OFF
            defaultLunchOn = true,      // Default ON
            defaultDinnerOn = true      // Default ON
        )

        // Breakfast: missing row -> resolved as OFF (0 units, isAutoCarried = true)
        val breakfast = effectiveMealService.getEffectiveMealStatus(
            member = member,
            date = date,
            session = MealSession.BREAKFAST,
            cycleConfig = cycleConfig,
            diningConfig = diningConfig,
            pausedDays = emptyList(),
            persistedRow = null
        )
        assertEquals(MealStatus.OFF, breakfast.status)
        assertEquals(BigDecimal.ZERO, breakfast.unitValue)
        assertTrue(breakfast.isAutoCarried)

        // Lunch: missing row -> resolved as ON (1.00 units, isAutoCarried = true)
        val lunch = effectiveMealService.getEffectiveMealStatus(
            member = member,
            date = date,
            session = MealSession.LUNCH,
            cycleConfig = cycleConfig,
            diningConfig = diningConfig,
            pausedDays = emptyList(),
            persistedRow = null
        )
        assertEquals(MealStatus.ON, lunch.status)
        assertEquals(BigDecimal("1.00"), lunch.unitValue)
        assertTrue(lunch.isAutoCarried)
    }

    // =========================================================================
    // PART 2 REGRESSION TESTS: SANITY GUARDS & SECURITY
    // =========================================================================

    @Test
    fun `test sanity guard triggers alert on 0 total meals with positive expenses`() {
        val timeline = CycleTimeline(startDate = LocalDate.of(2026, 11, 1), targetActiveDays = 30)
        val members = listOf(
            MemberMealInput("u1", "Student 1", LocalDate.of(2026, 11, 1), null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("2000.00"))
        )
        val expenses = listOf(
            ExpenseItemInput(id = "e1", category = ExpenseCategory.MEAL_VARIABLE, amount = BigDecimal("5000.00"))
        )

        val result = FinancialCalculationEngine.calculateSettlement(
            timeline = timeline,
            asOfDate = LocalDate.of(2026, 11, 5),
            members = members,
            expenses = expenses,
            isFinal = false
        )

        assertTrue(result.isRateAnomalous)
        assertNotNull(result.rateWarning)
        assertTrue(result.rateWarning!!.contains("Sanity Alert: 0 total meal units recorded"))
        assertEquals(BigDecimal.ZERO, result.mealRate)
    }

    @Test
    fun `test sanity guard triggers alert on unusually high meal rate`() {
        val timeline = CycleTimeline(startDate = LocalDate.of(2026, 11, 1), targetActiveDays = 30)
        val members = listOf(
            // Only 1 meal eaten over 5 active days with 10,000 BDT in groceries
            MemberMealInput("u1", "Student 1", LocalDate.of(2026, 11, 1), null, BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal("2000.00"))
        )
        val expenses = listOf(
            ExpenseItemInput(id = "e1", category = ExpenseCategory.MEAL_VARIABLE, amount = BigDecimal("10000.00"))
        )

        val result = FinancialCalculationEngine.calculateSettlement(
            timeline = timeline,
            asOfDate = LocalDate.of(2026, 11, 5),
            members = members,
            expenses = expenses,
            isFinal = false
        )

        assertTrue(result.isRateAnomalous)
        assertNotNull(result.rateWarning)
        assertTrue(result.rateWarning!!.contains("unusually high"))
        assertEquals(BigDecimal("10000.0000"), result.mealRate)
    }

    @Test
    fun `test cutoff enforcement rejects edits of past dates`() {
        val dailyMealStatusRepo = mock(DailyMealStatusRepository::class.java)
        val guestMealRepo = mock(GuestMealRepository::class.java)
        val membershipRepo = mock(MessMembershipRepository::class.java)
        val diningConfigRepo = mock(DiningConfigurationRepository::class.java)
        val sessionConfigRepo = mock(MealSessionConfigRepository::class.java)
        val diningCycleRepo = mock(DiningCycleRepository::class.java)
        val cycleConfigRepo = mock(CycleConfigurationRepository::class.java)
        val cyclePausedDayRepo = mock(CyclePausedDayRepository::class.java)
        val userRepo = mock(UserRepository::class.java)

        val mealService = MealService(
            dailyMealStatusRepo, guestMealRepo, membershipRepo, diningConfigRepo,
            sessionConfigRepo, diningCycleRepo, cycleConfigRepo, cyclePausedDayRepo,
            userRepo, effectiveMealService
        )

        val user = User(id = "u1", email = "test@messmate.com", fullName = "Test User")
        val membership = MessMembership(id = "m1", messId = "mess1", userId = "u1", status = MembershipStatus.ACTIVE)

        `when`(userRepo.findByEmail("test@messmate.com")).thenReturn(Optional.of(user))
        `when`(membershipRepo.findByUserIdAndStatus("u1", MembershipStatus.ACTIVE)).thenReturn(Optional.of(membership))

        val yesterday = LocalDate.now(dhakaZone).minusDays(1)
        val request = ToggleMealRequest(date = yesterday, session = MealSession.LUNCH, status = MealStatus.OFF)

        val ex = assertThrows<ResponseStatusException> {
            mealService.toggleMeal("test@messmate.com", request)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        assertTrue(ex.reason!!.contains("Cannot alter meal status for past dates"))
    }

    @Test
    fun `test cross-mess IDOR rejection when updating dining config`() {
        val messRepo = mock(MessRepository::class.java)
        val membershipRepo = mock(MessMembershipRepository::class.java)
        val userRepo = mock(UserRepository::class.java)
        val diningConfigRepo = mock(DiningConfigurationRepository::class.java)
        val sessionConfigRepo = mock(MealSessionConfigRepository::class.java)
        val diningCycleRepo = mock(DiningCycleRepository::class.java)
        val cycleConfigRepo = mock(CycleConfigurationRepository::class.java)
        val joinRequestRepo = mock(MealJoinRequestRepository::class.java)
        val menuService = mock(MenuService::class.java)
        val notificationService = mock(NotificationService::class.java)

        val messService = MessService(
            messRepo, membershipRepo, userRepo, diningConfigRepo, sessionConfigRepo,
            diningCycleRepo, cycleConfigRepo, joinRequestRepo, menuService, notificationService
        )

        val attacker = User(id = "u_attacker", email = "attacker@othermess.com")
        `when`(userRepo.findByEmail("attacker@othermess.com")).thenReturn(Optional.of(attacker))

        // Attacker is NOT a member of victim mess "victim-mess-123"
        `when`(membershipRepo.findByMessIdAndUserId("victim-mess-123", "u_attacker")).thenReturn(Optional.empty())

        val ex = assertThrows<ResponseStatusException> {
            messService.updateDiningConfig(
                messId = "victim-mess-123",
                newCarryForward = true,
                sessionUpdates = emptyList(),
                callerEmail = "attacker@othermess.com"
            )
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
        assertTrue(ex.reason!!.contains("You do not belong to this Mess"))
    }

    @Test
    fun `test cross-mess IDOR rejection when expense target member belongs to different mess`() {
        val expenseRepo = mock(ExpenseRepository::class.java)
        val diningCycleRepo = mock(DiningCycleRepository::class.java)
        val membershipRepo = mock(MessMembershipRepository::class.java)
        val userRepo = mock(UserRepository::class.java)

        val expenseService = ExpenseService(expenseRepo, diningCycleRepo, membershipRepo, userRepo)

        val manager = User(id = "u_mgr", email = "mgr@messmate.com")
        val membership = MessMembership(id = "m1", messId = "mess1", userId = "u_mgr", role = UserRole.PRIMARY_MANAGER, status = MembershipStatus.ACTIVE)
        val cycle = DiningCycle(id = "c1", messId = "mess1", status = CycleStatus.ACTIVE)

        `when`(userRepo.findByEmail("mgr@messmate.com")).thenReturn(Optional.of(manager))
        `when`(membershipRepo.findByUserIdAndStatus("u_mgr", MembershipStatus.ACTIVE)).thenReturn(Optional.of(membership))
        `when`(diningCycleRepo.findFirstByMessIdAndStatus("mess1", CycleStatus.ACTIVE)).thenReturn(Optional.of(cycle))

        // Foreign victim user from another mess
        `when`(membershipRepo.findByMessIdAndUserId("mess1", "u_foreign_victim")).thenReturn(Optional.empty())

        val req = CreateExpenseRequest(
            category = ExpenseCategory.INDIVIDUAL_DIRECT,
            title = "Personal Utility Charge",
            amount = BigDecimal("500.00"),
            expenseDate = LocalDate.now(dhakaZone),
            targetMemberId = "u_foreign_victim"
        )

        val ex = assertThrows<ResponseStatusException> {
            expenseService.createExpense("mgr@messmate.com", req)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        assertTrue(ex.reason!!.contains("Target member u_foreign_victim does not belong to this Mess"))
    }
}
