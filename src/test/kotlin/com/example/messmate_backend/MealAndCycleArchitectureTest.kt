package com.example.messmate_backend

import com.example.messmate_backend.dto.*
import com.example.messmate_backend.model.enums.*
import com.example.messmate_backend.repository.*
import com.example.messmate_backend.service.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import com.example.messmate_backend.entity.Deposit
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

@SpringBootTest
class MealAndCycleArchitectureTest {

    @Autowired
    private lateinit var authService: AuthService

    @Autowired
    private lateinit var messService: MessService

    @Autowired
    private lateinit var cycleCalculationService: CycleCalculationService

    @Autowired
    private lateinit var mealService: MealService

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var messRepository: MessRepository

    @Autowired
    private lateinit var membershipRepository: MessMembershipRepository

    @Autowired
    private lateinit var diningCycleRepository: DiningCycleRepository

    @Autowired
    private lateinit var joinRequestRepository: MealJoinRequestRepository

    @Autowired
    private lateinit var balanceLedgerRepository: BalanceLedgerRepository

    @Autowired
    private lateinit var depositRepository: DepositRepository

    @Autowired
    private lateinit var cyclePausedDayRepository: CyclePausedDayRepository

    @Autowired
    private lateinit var diningConfigRepository: DiningConfigurationRepository

    @Autowired
    private lateinit var sessionConfigRepository: MealSessionConfigRepository

    @Autowired
    private lateinit var chefService: ChefService

    @Test
    @Transactional
    fun testStudentRegistrationAndProfileSetup() {
        val uniqueEmail = "student_${UUID.randomUUID().toString().take(6)}@univ.edu"

        // 1. Register without room number -> profile should be incomplete
        val registerRes = authService.register(
            RegisterRequest(
                email = uniqueEmail,
                password = "Password123!",
                fullName = "Sadia Islam",
                phone = "01700000001"
            )
        )
        assertNotNull(registerRes.token)
        assertEquals("Sadia Islam", registerRes.user.fullName)
        assertFalse(registerRes.user.isProfileComplete, "Profile should be incomplete before room number is set")

        // 2. Profile setup with required Room Number
        val updatedUser = authService.updateProfile(
            uniqueEmail,
            UpdateProfileRequest(
                studentId = "ST-2026-09",
                department = "Computer Science & Engineering",
                university = "Dhaka University",
                roomNumber = "South-402"
            )
        )
        assertTrue(updatedUser.isProfileComplete, "Profile should be complete after setting required room number")
        assertEquals("South-402", updatedUser.roomNumber)
        assertEquals("Dhaka University", updatedUser.university)

        // 3. Login returns complete profile
        val loginRes = authService.login(LoginRequest(email = uniqueEmail, password = "Password123!"))
        assertTrue(loginRes.user.isProfileComplete)
        assertNull(loginRes.activeMembership, "New student should have no active membership")
    }

    @Test
    @Transactional
    fun testCreateMealAndJoinRequestFlow() {
        val ownerEmail = "owner_${UUID.randomUUID().toString().take(6)}@univ.edu"
        val applicantEmail = "applicant_${UUID.randomUUID().toString().take(6)}@univ.edu"

        authService.register(RegisterRequest(email = ownerEmail, password = "pass", fullName = "Owner Student", roomNumber = "101"))
        authService.register(RegisterRequest(email = applicantEmail, password = "pass", fullName = "Applicant Student", roomNumber = "102"))

        // 1. Owner creates a Meal
        val meal = messService.createMess(
            ownerEmail,
            CreateMealRequest(
                name = "Padma Hall Dining",
                address = "Padma Hall, Block B",
                targetActiveDays = 30,
                breakfastMultiplier = BigDecimal("0.50"),
                lunchMultiplier = BigDecimal("1.00"),
                dinnerMultiplier = BigDecimal("1.00")
            )
        )
        assertNotNull(meal.id)
        assertTrue(meal.code.startsWith("MM"), "Meal code must start with MM (human friendly): ${meal.code}")
        assertEquals(6, meal.code.length, "Meal code should be 6 characters (e.g. MM7K42)")

        // Verify Owner membership
        val owner = userRepository.findByEmail(ownerEmail).orElseThrow()
        val ownerMembership = membershipRepository.findByMessIdAndUserId(meal.id, owner.id).orElseThrow()
        assertEquals(UserRole.OWNER, ownerMembership.role, "Creator must be MEAL_OWNER")
        assertEquals(MembershipStatus.ACTIVE, ownerMembership.status)

        // Verify Cycle #1 was created
        val activeCycle = diningCycleRepository.findFirstByMessIdAndStatus(meal.id, CycleStatus.ACTIVE).orElseThrow()
        assertEquals(1, activeCycle.cycleNumber, "Initial cycle must be Cycle #1")
        assertEquals(30, activeCycle.targetActiveDays)

        // 2. Applicant verifies Meal Code
        val verifyRes = messService.verifyMealCode(meal.code)
        assertEquals(meal.name, verifyRes.name)
        assertEquals("Owner Student", verifyRes.ownerName)
        assertEquals(1, verifyRes.memberCount)
        assertEquals(1, verifyRes.currentCycleNumber)

        // 3. Applicant submits Join Request
        val joinReq = messService.submitJoinRequest(
            applicantEmail,
            SubmitJoinRequest(code = meal.code, notes = "Hi, I am living in room 102")
        )
        assertEquals(JoinRequestStatus.PENDING, joinReq.status)
        assertEquals(meal.id, joinReq.mealId)

        // Cannot submit duplicate pending join request
        assertThrows(ResponseStatusException::class.java) {
            messService.submitJoinRequest(applicantEmail, SubmitJoinRequest(code = meal.code))
        }

        // 4. Owner lists join requests
        val pendingList = messService.listJoinRequests(meal.id, ownerEmail)
        assertEquals(1, pendingList.size)
        assertEquals(joinReq.id, pendingList[0].id)

        // 5. Owner approves join request
        val approvedReq = messService.reviewJoinRequest(
            meal.id,
            joinReq.id,
            ReviewJoinRequest(approved = true),
            ownerEmail
        )
        assertEquals(JoinRequestStatus.APPROVED, approvedReq.status)

        // Applicant is now an active MEMBER
        val applicant = userRepository.findByEmail(applicantEmail).orElseThrow()
        val applicantMembership = membershipRepository.findByMessIdAndUserId(meal.id, applicant.id).orElseThrow()
        assertEquals(UserRole.MEMBER, applicantMembership.role)
        assertEquals(MembershipStatus.ACTIVE, applicantMembership.status)

        // Meal now has 2 members
        val updatedMeal = messService.getMealDetails(meal.id)
        assertEquals(2, updatedMeal.memberCount)
    }

    @Test
    @Transactional
    fun testCyclePauseDayAndActiveDiningDaysCalculation() {
        val ownerEmail = "owner_cycle_${UUID.randomUUID().toString().take(6)}@univ.edu"
        authService.register(RegisterRequest(email = ownerEmail, password = "pass", fullName = "Cycle Owner", roomNumber = "301"))

        val meal = messService.createMess(
            ownerEmail,
            CreateMealRequest(name = "Meghna Hall Meal", targetActiveDays = 30)
        )

        val activeCycle = cycleCalculationService.getActiveCycle(meal.id)
        val initialScheduledEnd = activeCycle.scheduledEndDate

        // Pause tomorrow for university convocation
        val dhakaZone = ZoneId.of("Asia/Dhaka")
        val pauseDate = LocalDate.now(dhakaZone).plusDays(5)
        val updatedCycle = cycleCalculationService.pauseDay(
            PauseDayRequest(
                messId = meal.id,
                pausedDate = pauseDate,
                reason = "University Convocation Day (Kitchen Closed)"
            )
        )

        // Scheduled end date should extend by 1 day
        assertEquals(1, updatedCycle.pausedDays.size)
        assertEquals(initialScheduledEnd.plusDays(1), updatedCycle.scheduledEndDate, "Scheduled end date must extend when a dining day is paused")
    }

    @Test
    @Transactional
    fun testCycleFinalizationAndNewCycleWithMemberRetention() {
        val ownerEmail = "owner_fin_${UUID.randomUUID().toString().take(6)}@univ.edu"
        val memberEmail = "member_fin_${UUID.randomUUID().toString().take(6)}@univ.edu"

        authService.register(RegisterRequest(email = ownerEmail, password = "pass", fullName = "Finalization Owner", roomNumber = "501"))
        authService.register(RegisterRequest(email = memberEmail, password = "pass", fullName = "Finalization Member", roomNumber = "502"))

        // Create Meal
        val meal = messService.createMess(ownerEmail, CreateMealRequest(name = "Karnafuli Dining Group"))

        // Direct join member
        messService.joinMess(memberEmail, JoinMessRequest(code = meal.code))

        // Both are active members
        val owner = userRepository.findByEmail(ownerEmail).orElseThrow()
        val member = userRepository.findByEmail(memberEmail).orElseThrow()
        assertTrue(membershipRepository.existsByUserIdAndStatus(owner.id, MembershipStatus.ACTIVE))
        assertTrue(membershipRepository.existsByUserIdAndStatus(member.id, MembershipStatus.ACTIVE))

        // Finalize Cycle #1
        val calcResult = cycleCalculationService.calculateAndSave(meal.id, isFinal = true)
        assertTrue(calcResult.isFinal)

        // Cycle #1 is COMPLETED
        val cycle1 = diningCycleRepository.findAllByMessIdOrderByCycleNumberDesc(meal.id).first()
        assertEquals(CycleStatus.COMPLETED, cycle1.status)

        // Ledger entries were written for members
        val memberLedgers = balanceLedgerRepository.findAllByUserIdOrderByCreatedAtDesc(member.id)
        assertTrue(memberLedgers.any { it.cycleId == cycle1.id && it.entryType == LedgerEntryType.MEAL_CHARGE })

        // 3. Start Cycle #2
        val cycle2 = cycleCalculationService.startNewCycle(
            meal.id,
            StartNewCycleRequest(targetActiveDays = 30, notes = "Welcome to Cycle #2!"),
            ownerEmail
        )
        assertEquals(2, cycle2.cycleNumber, "New cycle number must be Cycle #2")
        assertEquals(CycleStatus.ACTIVE, cycle2.status)

        // CRITICAL PRODUCT REQUIREMENT:
        // Existing active members remain members! They do NOT need to rejoin!
        val ownerMemAfter = membershipRepository.findByMessIdAndUserId(meal.id, owner.id).orElseThrow()
        val memberMemAfter = membershipRepository.findByMessIdAndUserId(meal.id, member.id).orElseThrow()
        assertEquals(MembershipStatus.ACTIVE, ownerMemAfter.status, "Owner must remain active in Meal")
        assertEquals(MembershipStatus.ACTIVE, memberMemAfter.status, "Member must remain active in Meal without rejoining")

        // Meal Code remains unchanged
        val mealAfter = messService.getMealDetails(meal.id)
        assertEquals(meal.code, mealAfter.code, "Meal Code must remain identical across cycles")
        assertEquals(2, mealAfter.currentCycleNumber)

        // Cycle history records Cycle #1
        val history = cycleCalculationService.getCycleHistory(meal.id)
        assertTrue(history.any { it.cycleNumber == 1 && it.status == CycleStatus.COMPLETED })
    }

    @Test
    @Transactional
    fun testCreateMessWithConfiguredActiveMealSessionsAndChefExclusion() {
        val managerEmail = "manager_${UUID.randomUUID().toString().take(6)}@campus.edu"
        authService.register(
            RegisterRequest(
                email = managerEmail,
                password = "Password123!",
                fullName = "Mess Manager",
                roomNumber = "301"
            )
        )

        // Manager configures active dining sessions during mess creation:
        // Breakfast is DISABLED. Lunch and Dinner are ENABLED with custom serving windows and cutoffs.
        val request = CreateMealRequest(
            name = "Surma Hall Dining",
            address = "Surma Hall, West Wing",
            targetActiveDays = 30,
            breakfastEnabled = false,
            lunchEnabled = true,
            dinnerEnabled = true,
            breakfastMultiplier = BigDecimal("0.50"),
            lunchMultiplier = BigDecimal("1.00"),
            dinnerMultiplier = BigDecimal("1.00"),
            breakfastCutoff = "07:00",
            lunchCutoff = "11:30",
            dinnerCutoff = "19:30",
            breakfastServingStartTime = "07:00",
            breakfastServingEndTime = "09:00",
            lunchServingStartTime = "12:30",
            lunchServingEndTime = "14:15",
            dinnerServingStartTime = "20:00",
            dinnerServingEndTime = "21:45"
        )

        val createdMess = messService.createMess(managerEmail, request)
        assertNotNull(createdMess.id)

        // 1. Verify DiningConfiguration persistence
        val diningConfig = diningConfigRepository.findByMessId(createdMess.id).orElseThrow()
        assertFalse(diningConfig.defaultBreakfastOn, "Breakfast should be disabled in DiningConfiguration")
        assertTrue(diningConfig.defaultLunchOn, "Lunch should be enabled in DiningConfiguration")
        assertTrue(diningConfig.defaultDinnerOn, "Dinner should be enabled in DiningConfiguration")

        // 2. Verify MealSessionConfig persistence in meal_session_configs table
        val sessionConfigs = sessionConfigRepository.findAllByDiningConfigId(diningConfig.id)
        assertEquals(3, sessionConfigs.size)

        val breakfastCfg = sessionConfigs.first { it.session == MealSession.BREAKFAST }
        assertFalse(breakfastCfg.isEnabled, "Breakfast session config isEnabled must be false")
        assertEquals("07:00", breakfastCfg.cutoffTime)
        assertEquals("07:00", breakfastCfg.servingStartTime)
        assertEquals("09:00", breakfastCfg.servingEndTime)

        val lunchCfg = sessionConfigs.first { it.session == MealSession.LUNCH }
        assertTrue(lunchCfg.isEnabled, "Lunch session config isEnabled must be true")
        assertEquals("11:30", lunchCfg.cutoffTime)
        assertEquals("12:30", lunchCfg.servingStartTime)
        assertEquals("14:15", lunchCfg.servingEndTime)

        val dinnerCfg = sessionConfigs.first { it.session == MealSession.DINNER }
        assertTrue(dinnerCfg.isEnabled, "Dinner session config isEnabled must be true")
        assertEquals("19:30", dinnerCfg.cutoffTime)
        assertEquals("20:00", dinnerCfg.servingStartTime)
        assertEquals("21:45", dinnerCfg.servingEndTime)

        // 3. Verify Chef Kitchen Headcount Terminal excludes the disabled session
        val today = LocalDate.now(ZoneId.of("Asia/Dhaka"))
        val chefHeadcount = chefService.getDailyHeadcount(createdMess.id, today)
        assertEquals(createdMess.id, chefHeadcount.messId)

        // Disabled session (Breakfast) MUST be hidden/excluded from Chef terminal headcount
        assertEquals(2, chefHeadcount.sessions.size, "Chef headcount should only contain the 2 active sessions")
        assertFalse(
            chefHeadcount.sessions.any { it.session == MealSession.BREAKFAST },
            "Breakfast must be excluded from Chef headcount"
        )
        assertTrue(
            chefHeadcount.sessions.any { it.session == MealSession.LUNCH },
            "Lunch must be present in Chef headcount"
        )
        assertTrue(
            chefHeadcount.sessions.any { it.session == MealSession.DINNER },
            "Dinner must be present in Chef headcount"
        )

        // Verify serving times are populated in ChefSessionHeadcount
        val chefLunch = chefHeadcount.sessions.first { it.session == MealSession.LUNCH }
        assertEquals("12:30", chefLunch.servingStartTime)
        assertEquals("14:15", chefLunch.servingEndTime)
    }

    @Test
    @Transactional
    fun testMemberEndsCycleWithSurplusAndCarryForwardDisabledRecordsRefundDueLedgerEntry() {
        val unique = UUID.randomUUID().toString().take(6)
        val managerEmail = "mgr_surplus_$unique@univ.edu"
        val memberEmail = "mem_surplus_$unique@univ.edu"

        authService.register(RegisterRequest(email = managerEmail, password = "Password123!", fullName = "Manager", roomNumber = "M1"))
        authService.register(RegisterRequest(email = memberEmail, password = "Password123!", fullName = "Member", roomNumber = "S1"))

        val mess = messService.createMess(managerEmail, CreateMessRequest(name = "Surplus Mess $unique"))
        val joinReq = messService.submitJoinRequest(memberEmail, SubmitJoinRequest(code = mess.code))
        messService.reviewJoinRequest(mess.id, joinReq.id, ReviewJoinRequest(approved = true), managerEmail)

        val member = userRepository.findByEmail(memberEmail).orElseThrow()
        val activeCycle = diningCycleRepository.findFirstByMessIdAndStatus(mess.id, CycleStatus.ACTIVE).orElseThrow()

        // Member deposits 500 BDT
        val deposit = Deposit(
            cycleId = activeCycle.id,
            messId = mess.id,
            userId = member.id,
            amount = BigDecimal("500.00"),
            depositDate = LocalDateTime.now(),
            paymentMethod = PaymentMethod.CASH,
            transactionRef = "DEP-SURPLUS-500",
            status = DepositStatus.APPROVED,
            notes = "Opening 500 BDT deposit"
        )
        depositRepository.save(deposit)

        // Finalize Cycle #1 (netBalance = +500)
        val calc = cycleCalculationService.calculateAndSave(mess.id, isFinal = true)
        assertTrue(calc.isFinal)
        val memberSummary = calc.memberSummaries.first { it.userId == member.id }
        assertEquals(0, BigDecimal("500.00").compareTo(memberSummary.netBalance))

        // Manager attempts startNewCycle with defaultCarryForward = false and confirmForfeitSurplus = false
        val ex = assertThrows(ResponseStatusException::class.java) {
            cycleCalculationService.startNewCycle(
                mess.id,
                StartNewCycleRequest(defaultCarryForward = false, confirmForfeitSurplus = false),
                managerEmail
            )
        }
        assertTrue(ex.reason?.contains("that will not carry forward") == true)

        // Manager confirms refund commitment and concludes/starts new cycle
        val cycle2 = cycleCalculationService.startNewCycle(
            mess.id,
            StartNewCycleRequest(defaultCarryForward = false, confirmForfeitSurplus = true),
            managerEmail
        )
        assertEquals(2, cycle2.cycleNumber)

        // Assert BalanceLedger entry is recorded with REFUND_DUE and retrievable later
        val ledgers = balanceLedgerRepository.findAllByUserIdOrderByCreatedAtDesc(member.id)
        val refundDueEntry = ledgers.firstOrNull { it.entryType == LedgerEntryType.REFUND_DUE }
        assertNotNull(refundDueEntry, "Must create a REFUND_DUE ledger entry when carry-forward is disabled")
        assertEquals(0, BigDecimal("-500.00").compareTo(refundDueEntry!!.amount))
        assertEquals(0, BigDecimal.ZERO.compareTo(refundDueEntry.balanceAfter))
        assertEquals(activeCycle.id, refundDueEntry.referenceId)
    }

    @Test
    @Transactional
    fun testChefHeadcountOnPausedDayReturnsZeroAndReflectsPaused() {
        val unique = UUID.randomUUID().toString().take(6)
        val managerEmail = "mgr_paused_$unique@univ.edu"
        val memberEmail = "mem_paused_$unique@univ.edu"

        authService.register(RegisterRequest(email = managerEmail, password = "Password123!", fullName = "Manager", roomNumber = "M1"))
        authService.register(RegisterRequest(email = memberEmail, password = "Password123!", fullName = "Member", roomNumber = "S1"))

        val mess = messService.createMess(managerEmail, CreateMessRequest(name = "Paused Mess $unique"))
        val joinReq = messService.submitJoinRequest(memberEmail, SubmitJoinRequest(code = mess.code))
        messService.reviewJoinRequest(mess.id, joinReq.id, ReviewJoinRequest(approved = true), managerEmail)

        val tomorrow = LocalDate.now(ZoneId.of("Asia/Dhaka")).plusDays(1)

        // Explicitly set Member ON for LUNCH and DINNER
        mealService.toggleMeal(memberEmail, ToggleMealRequest(date = tomorrow, session = MealSession.LUNCH, status = MealStatus.ON))
        mealService.toggleMeal(memberEmail, ToggleMealRequest(date = tomorrow, session = MealSession.DINNER, status = MealStatus.ON))

        // Before pause: headcount reflects ON members
        val beforePause = chefService.getDailyHeadcount(mess.id, tomorrow)
        assertFalse(beforePause.isPaused)
        assertTrue(beforePause.sessions.any { it.totalHeadcount > 0 })

        // Manager marks date as a whole-day pause
        cycleCalculationService.pauseDay(
            PauseDayRequest(
                messId = mess.id,
                pausedDate = tomorrow,
                session = null,
                reason = "National Holiday / Campus Closure"
            )
        )

        // After pause: getDailyHeadcount reflects paused state with 0 counts
        val afterPause = chefService.getDailyHeadcount(mess.id, tomorrow)
        assertTrue(afterPause.isPaused, "Chef headcount must indicate isPaused = true")
        assertEquals("National Holiday / Campus Closure", afterPause.pauseReason)
        for (sessionHeadcount in afterPause.sessions) {
            assertEquals(0, sessionHeadcount.totalHeadcount, "Session headcount must be 0 on a paused day")
            assertEquals(0, sessionHeadcount.studentOnCount, "Student count must be 0 on a paused day")
            assertEquals(0, sessionHeadcount.guestMealCount, "Guest count must be 0 on a paused day")
            assertFalse(sessionHeadcount.isEnabled, "Session must be disabled on a paused day")
            assertTrue(sessionHeadcount.menuItemName?.contains("Mess Paused / Holiday") == true)
        }
    }

    @Test
    @Transactional
    fun testWholeDayPauseVersusSessionSpecificPause() {
        val unique = UUID.randomUUID().toString().take(6)
        val managerEmail = "mgr_session_pause_$unique@univ.edu"
        val memberEmail = "mem_session_pause_$unique@univ.edu"

        authService.register(RegisterRequest(email = managerEmail, password = "Password123!", fullName = "Manager", roomNumber = "M1"))
        authService.register(RegisterRequest(email = memberEmail, password = "Password123!", fullName = "Member", roomNumber = "S1"))

        val mess = messService.createMess(managerEmail, CreateMessRequest(name = "Session Pause Mess $unique"))
        val joinReq = messService.submitJoinRequest(memberEmail, SubmitJoinRequest(code = mess.code))
        messService.reviewJoinRequest(mess.id, joinReq.id, ReviewJoinRequest(approved = true), managerEmail)

        val cycleBefore = cycleCalculationService.getActiveCycle(mess.id)
        val originalEndDate = cycleBefore.scheduledEndDate

        val date1 = LocalDate.now(ZoneId.of("Asia/Dhaka")).plusDays(2)
        val date2 = LocalDate.now(ZoneId.of("Asia/Dhaka")).plusDays(3)

        // 1. Session-specific pause on date1 (only LUNCH paused due to gas outage)
        val afterSessionPause = cycleCalculationService.pauseDay(
            PauseDayRequest(
                messId = mess.id,
                pausedDate = date1,
                session = MealSession.LUNCH,
                reason = "Gas outage during lunch"
            )
        )
        // Day count is NOT reduced and scheduled end date is NOT extended for a single-session pause
        assertEquals(originalEndDate, afterSessionPause.scheduledEndDate, "Session-specific pause must NOT extend scheduled end date")

        // Member cannot book LUNCH on date1
        val ex = assertThrows(ResponseStatusException::class.java) {
            mealService.toggleMeal(memberEmail, ToggleMealRequest(date = date1, session = MealSession.LUNCH, status = MealStatus.ON))
        }
        assertTrue(ex.reason?.contains("Dining is paused for LUNCH") == true)

        // Member CAN book DINNER on date1 (other session operates normally)
        assertDoesNotThrow {
            mealService.toggleMeal(memberEmail, ToggleMealRequest(date = date1, session = MealSession.DINNER, status = MealStatus.ON))
        }

        // 2. Whole-day pause on date2 (session = null)
        val afterFullDayPause = cycleCalculationService.pauseDay(
            PauseDayRequest(
                messId = mess.id,
                pausedDate = date2,
                session = null,
                reason = "Full day holiday"
            )
        )
        // Whole-day pause DOES extend scheduled end date by 1 day
        assertEquals(originalEndDate.plusDays(1), afterFullDayPause.scheduledEndDate, "Whole-day pause MUST extend scheduled end date")
    }
}
