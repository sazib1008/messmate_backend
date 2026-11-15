package com.example.messmate_backend

import com.example.messmate_backend.entity.*
import com.example.messmate_backend.model.enums.*
import com.example.messmate_backend.repository.*
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Component
class TestFixtureSeeder(
    private val userRepository: UserRepository,
    private val messRepository: MessRepository,
    private val membershipRepository: MessMembershipRepository,
    private val diningCycleRepository: DiningCycleRepository,
    private val cycleConfigRepository: CycleConfigurationRepository,
    private val diningConfigRepository: DiningConfigurationRepository,
    private val sessionConfigRepository: MealSessionConfigRepository,
    private val menuRepository: MenuRepository,
    private val menuItemRepository: MenuItemRepository,
    private val passwordEncoder: PasswordEncoder
) {

    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun seedTestData() {
        if (userRepository.findByEmail("s1.arafat@messmate.com").isPresent) return

        val encodedPassword = passwordEncoder.encode("password123") ?: ""

        // 1. Users
        val manager = userRepository.save(
            User(
                id = "u_mgr_karim",
                fullName = "Karim Manager",
                email = "primary.manager@messmate.com",
                passwordHash = encodedPassword,
                phone = "01700000001"
            )
        )

        val student1 = userRepository.save(
            User(
                id = "u_s1_arafat",
                fullName = "Arafat Hossain",
                email = "s1.arafat@messmate.com",
                passwordHash = encodedPassword,
                phone = "01700000002"
            )
        )

        val student2 = userRepository.save(
            User(
                id = "u_s2_bilal",
                fullName = "Bilal Ahmed",
                email = "s2.bilal@messmate.com",
                passwordHash = encodedPassword,
                phone = "01700000003"
            )
        )

        val otherStudents = listOf(
            "s3.farhan@messmate.com" to "Farhan",
            "s4.hasan@messmate.com" to "Hasan",
            "s5.imran@messmate.com" to "Imran",
            "s6.kamal@messmate.com" to "Kamal",
            "s7.mehedi@messmate.com" to "Mehedi"
        ).mapIndexed { idx, (email, name) ->
            userRepository.save(
                User(
                    id = "u_s${idx + 3}_$name",
                    fullName = name,
                    email = email,
                    passwordHash = encodedPassword,
                    phone = "017000000${10 + idx}"
                )
            )
        }

        // 2. Mess
        val mess = messRepository.save(
            Mess(
                id = "greenfield-hall",
                name = "Greenfield Hall",
                code = "GFH-2026",
                createdById = manager.id
            )
        )

        // 3. Memberships
        membershipRepository.save(
            MessMembership(
                messId = mess.id,
                userId = manager.id,
                role = UserRole.PRIMARY_MANAGER,
                status = MembershipStatus.ACTIVE,
                joinDate = LocalDateTime.now().minusDays(30)
            )
        )

        listOf(student1, student2).plus(otherStudents).forEach { student ->
            membershipRepository.save(
                MessMembership(
                    messId = mess.id,
                    userId = student.id,
                    role = UserRole.STUDENT,
                    status = MembershipStatus.ACTIVE,
                    joinDate = LocalDateTime.now().minusDays(30)
                )
            )
        }

        // 4. DiningCycle
        val cycle = diningCycleRepository.save(
            DiningCycle(
                id = "cycle_gfh_1",
                messId = mess.id,
                cycleNumber = 1,
                startDate = LocalDate.now().minusDays(15),
                targetActiveDays = 30,
                scheduledEndDate = LocalDate.now().plusDays(15),
                status = CycleStatus.ACTIVE
            )
        )

        // 5. DiningConfiguration & Session Configs
        val diningConfig = diningConfigRepository.save(
            DiningConfiguration(
                id = "cfg_gfh_1",
                messId = mess.id,
                defaultCarryForward = true,
                defaultBreakfastOn = true,
                defaultLunchOn = true,
                defaultDinnerOn = true,
                currency = "BDT"
            )
        )

        listOf(
            MealSession.BREAKFAST to "07:00",
            MealSession.LUNCH to "12:00",
            MealSession.DINNER to "19:00"
        ).forEach { (session, cutoff) ->
            sessionConfigRepository.save(
                MealSessionConfig(
                    diningConfigId = diningConfig.id,
                    session = session,
                    unitValue = if (session == MealSession.BREAKFAST) BigDecimal("0.50") else BigDecimal("1.00"),
                    cutoffTime = cutoff,
                    isEnabled = true
                )
            )
        }

        cycleConfigRepository.save(
            CycleConfiguration(
                cycleId = cycle.id,
                messId = mess.id,
                breakfastEnabled = true,
                lunchEnabled = true,
                dinnerEnabled = true,
                breakfastCutoff = "07:00",
                lunchCutoff = "12:00",
                dinnerCutoff = "19:00",
                breakfastMultiplier = BigDecimal("0.50"),
                lunchMultiplier = BigDecimal("1.00"),
                dinnerMultiplier = BigDecimal("1.00"),
                combinedSessionRule = "SEPARATE",
                targetActiveDays = 30
            )
        )

        // 6. Menu
        val menu = menuRepository.save(
            Menu(
                id = "menu_gfh_1",
                messId = mess.id,
                title = "Weekly Schedule",
                isActive = true,
                effectiveFrom = LocalDate.now().minusDays(15)
            )
        )

        for (day in 0..6) {
            menuItemRepository.save(
                MenuItem(
                    menuId = menu.id,
                    dayOfWeek = day,
                    session = MealSession.LUNCH,
                    itemName = "Steamed Rice & Lentils",
                    category = "Main"
                )
            )
            menuItemRepository.save(
                MenuItem(
                    menuId = menu.id,
                    dayOfWeek = day,
                    session = MealSession.DINNER,
                    itemName = "Chicken Curry & Flatbread",
                    category = "Main"
                )
            )
        }
    }
}
