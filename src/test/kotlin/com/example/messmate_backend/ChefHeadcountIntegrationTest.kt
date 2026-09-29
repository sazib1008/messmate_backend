package com.example.messmate_backend

import com.example.messmate_backend.dto.*
import com.example.messmate_backend.model.enums.*
import com.example.messmate_backend.repository.*
import com.example.messmate_backend.service.AuthService
import com.example.messmate_backend.service.ChefService
import com.example.messmate_backend.service.MealService
import com.example.messmate_backend.service.MessService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@SpringBootTest
class ChefHeadcountIntegrationTest {

    @Autowired
    private lateinit var authService: AuthService

    @Autowired
    private lateinit var messService: MessService

    @Autowired
    private lateinit var mealService: MealService

    @Autowired
    private lateinit var chefService: ChefService

    @Autowired
    private lateinit var managerAndChefControllers: com.example.messmate_backend.controller.ManagerAndChefControllers

    @Autowired
    private lateinit var diningConfigRepository: DiningConfigurationRepository

    @Autowired
    private lateinit var sessionConfigRepository: MealSessionConfigRepository

    @Test
    @Transactional
    fun testHeadcountWithTwoMembersAndVariedOnOffSessions() {
        val uniqueSuffix = UUID.randomUUID().toString().take(6)
        val managerEmail = "manager_$uniqueSuffix@univ.edu"
        val studentEmail = "student_$uniqueSuffix@univ.edu"

        // 1. Register Manager and Student
        authService.register(
            RegisterRequest(
                email = managerEmail,
                password = "Password123!",
                fullName = "Manager User",
                roomNumber = "M-101"
            )
        )
        authService.register(
            RegisterRequest(
                email = studentEmail,
                password = "Password123!",
                fullName = "Student User",
                roomNumber = "S-202"
            )
        )

        // 2. Manager creates mess
        val mess = messService.createMess(
            managerEmail,
            CreateMealRequest(
                name = "Test Dining Hall $uniqueSuffix",
                address = "Campus Block A",
                targetActiveDays = 30,
                breakfastMultiplier = BigDecimal("0.50"),
                lunchMultiplier = BigDecimal("1.00"),
                dinnerMultiplier = BigDecimal("1.00")
            )
        )

        // 3. Student joins mess
        val joinReq = messService.submitJoinRequest(
            studentEmail,
            SubmitJoinRequest(code = mess.code, notes = "Joining test mess")
        )
        messService.reviewJoinRequest(mess.id, joinReq.id, ReviewJoinRequest(approved = true), managerEmail)

        // 4. Configure dining defaults: Breakfast = OFF, Lunch = ON, Dinner = ON
        val diningConfig = diningConfigRepository.findByMessId(mess.id).orElseThrow()
        diningConfig.defaultBreakfastOn = false
        diningConfig.defaultLunchOn = true
        diningConfig.defaultDinnerOn = true
        diningConfigRepository.save(diningConfig)

        // Enable all three sessions in sessionConfig so they appear on Chef terminal
        val sessionConfigs = sessionConfigRepository.findAllByDiningConfigId(diningConfig.id)
        for (cfg in sessionConfigs) {
            cfg.isEnabled = true
            sessionConfigRepository.save(cfg)
        }

        val testDate = LocalDate.now(ZoneId.of("Asia/Dhaka")).plusDays(1)

        // --- Scenario 1: Initial state before any explicit toggles ---
        // Both manager and student have no rows yet for today:
        // Breakfast: both default OFF -> expected 0
        // Lunch: both default ON -> expected 2
        // Dinner: both default ON -> expected 2
        val initialHeadcount = chefService.getDailyHeadcount(mess.id, testDate)
        val initBreakfast = initialHeadcount.sessions.first { it.session == MealSession.BREAKFAST }
        val initLunch = initialHeadcount.sessions.first { it.session == MealSession.LUNCH }
        val initDinner = initialHeadcount.sessions.first { it.session == MealSession.DINNER }

        assertEquals(0, initBreakfast.studentOnCount, "Breakfast should be 0 by default")
        assertEquals(2, initLunch.studentOnCount, "Lunch should be 2 when both members rely on default ON")
        assertEquals(2, initDinner.studentOnCount, "Dinner should be 2 when both members rely on default ON")

        // --- Scenario 2: The observed bug scenario ---
        // Lunch: manager = no row (default ON), student = explicitly OFF -> expected 1
        // Dinner: manager = explicitly ON, student = explicitly ON -> expected 2
        mealService.toggleMeal(
            studentEmail,
            ToggleMealRequest(date = testDate, session = MealSession.LUNCH, status = MealStatus.OFF)
        )
        mealService.toggleMeal(
            managerEmail,
            ToggleMealRequest(date = testDate, session = MealSession.DINNER, status = MealStatus.ON)
        )
        mealService.toggleMeal(
            studentEmail,
            ToggleMealRequest(date = testDate, session = MealSession.DINNER, status = MealStatus.ON)
        )

        val headcount = chefService.getDailyHeadcount(mess.id, testDate)
        val lunch = headcount.sessions.first { it.session == MealSession.LUNCH }
        val dinner = headcount.sessions.first { it.session == MealSession.DINNER }

        assertEquals(1, lunch.studentOnCount, "Lunch headcount must be 1 (manager default ON + student explicit OFF)")
        assertEquals(0, lunch.guestMealCount)
        assertEquals(1, lunch.totalHeadcount)

        assertEquals(2, dinner.studentOnCount, "Dinner headcount must be 2 (manager explicit ON + student explicit ON)")
        assertEquals(0, dinner.guestMealCount)
        assertEquals(2, dinner.totalHeadcount)

        // --- Scenario 3: Guest meals are additive ---
        mealService.bookGuestMeal(
            studentEmail,
            BookGuestMealRequest(
                date = testDate,
                session = MealSession.LUNCH,
                guestCount = 2,
                guestName = "Guest Visitor",
                notes = "Vegetarian guest"
            )
        )

        val headcountWithGuests = chefService.getDailyHeadcount(mess.id, testDate)
        val lunchWithGuests = headcountWithGuests.sessions.first { it.session == MealSession.LUNCH }

        assertEquals(1, lunchWithGuests.studentOnCount, "Regular headcount remains 1")
        assertEquals(2, lunchWithGuests.guestMealCount, "Guest headcount must be 2")
        assertEquals(3, lunchWithGuests.totalHeadcount, "Total headcount must be 1 + 2 = 3")
        assertTrue(lunchWithGuests.notes.contains("Vegetarian guest"))
    }

    @Test
    @Transactional
    @org.springframework.security.test.context.support.WithMockUser(roles = ["MANAGER"])
    fun testDashboardMealsToPrepareEndpointMatchesChefHeadcount() {
        val uniqueSuffix = UUID.randomUUID().toString().take(6)
        val managerEmail = "manager_dash_$uniqueSuffix@univ.edu"
        val studentEmail = "student_dash_$uniqueSuffix@univ.edu"

        authService.register(
            RegisterRequest(
                email = managerEmail,
                password = "Password123!",
                fullName = "Manager Dashboard",
                roomNumber = "MD-101"
            )
        )
        authService.register(
            RegisterRequest(
                email = studentEmail,
                password = "Password123!",
                fullName = "Student Dashboard",
                roomNumber = "SD-202"
            )
        )

        val mess = messService.createMess(
            managerEmail,
            CreateMealRequest(
                name = "Dashboard Meals Sync Mess $uniqueSuffix",
                address = "Hall C",
                targetActiveDays = 30,
                breakfastMultiplier = BigDecimal("0.50"),
                lunchMultiplier = BigDecimal("1.00"),
                dinnerMultiplier = BigDecimal("1.00")
            )
        )

        val joinReq = messService.submitJoinRequest(
            studentEmail,
            SubmitJoinRequest(code = mess.code, notes = "Joining for dashboard sync test")
        )
        messService.reviewJoinRequest(mess.id, joinReq.id, ReviewJoinRequest(approved = true), managerEmail)

        val testDate = LocalDate.now(ZoneId.of("Asia/Dhaka")).plusDays(1)

        // Book 1 guest meal
        mealService.bookGuestMeal(
            studentEmail,
            BookGuestMealRequest(
                date = testDate,
                session = MealSession.DINNER,
                guestCount = 1,
                guestName = "Family Member"
            )
        )

        // 1. Call the endpoint used by the Manager Dashboard "Meals to Prepare" card
        val dashboardEndpointResponse = managerAndChefControllers.getChefHeadcount(mess.id, testDate)
        assertEquals(org.springframework.http.HttpStatus.OK, dashboardEndpointResponse.statusCode)
        val dashboardData = dashboardEndpointResponse.body
        assertNotNull(dashboardData)

        // 2. Query chefService directly (the Chef Kitchen Prep Terminal source of truth)
        val chefTerminalData = chefService.getDailyHeadcount(mess.id, testDate)

        // 3. Assert the dashboard endpoint response strictly equals the Chef headcount for all fields and sessions
        assertEquals(chefTerminalData.messId, dashboardData!!.messId)
        assertEquals(chefTerminalData.messName, dashboardData.messName)
        assertEquals(chefTerminalData.date, dashboardData.date)
        assertEquals(chefTerminalData.sessions.size, dashboardData.sessions.size)

        for (chefSession in chefTerminalData.sessions) {
            val dashSession = dashboardData.sessions.firstOrNull { it.session == chefSession.session }
            assertNotNull(dashSession, "Dashboard endpoint must include session ${chefSession.session}")
            assertEquals(
                chefSession.totalHeadcount,
                dashSession!!.totalHeadcount,
                "Total portions for session ${chefSession.session} must match Chef terminal"
            )
            assertEquals(
                chefSession.studentOnCount,
                dashSession.studentOnCount,
                "Regular student count for session ${chefSession.session} must match Chef terminal"
            )
            assertEquals(
                chefSession.guestMealCount,
                dashSession.guestMealCount,
                "Guest count for session ${chefSession.session} must match Chef terminal"
            )
            assertEquals(
                chefSession.isEnabled,
                dashSession.isEnabled,
                "isEnabled state for session ${chefSession.session} must match Chef terminal"
            )
        }
    }
}

