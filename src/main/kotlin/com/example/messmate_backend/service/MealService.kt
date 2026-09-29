package com.example.messmate_backend.service

import com.example.messmate_backend.dto.BookGuestMealRequest
import com.example.messmate_backend.dto.DailyMealStatusDto
import com.example.messmate_backend.dto.ToggleMealRequest
import com.example.messmate_backend.entity.DailyMealStatus
import com.example.messmate_backend.entity.GuestMeal
import com.example.messmate_backend.model.enums.CycleStatus
import com.example.messmate_backend.model.enums.MealSession
import com.example.messmate_backend.model.enums.MembershipStatus
import com.example.messmate_backend.repository.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class GuestMealDto(
    val id: String,
    val date: LocalDate,
    val session: MealSession,
    val guestCount: Int,
    val mealUnits: BigDecimal,
    val guestName: String?,
    val notes: String?,
    val totalCost: BigDecimal?
)

@Service
class MealService(
    private val dailyMealStatusRepository: DailyMealStatusRepository,
    private val guestMealRepository: GuestMealRepository,
    private val membershipRepository: MessMembershipRepository,
    private val diningConfigRepository: DiningConfigurationRepository,
    private val sessionConfigRepository: MealSessionConfigRepository,
    private val diningCycleRepository: DiningCycleRepository,
    private val cycleConfigRepository: CycleConfigurationRepository,
    private val cyclePausedDayRepository: CyclePausedDayRepository,
    private val userRepository: UserRepository
) {

    private val dhakaZone = ZoneId.of("Asia/Dhaka")

    @Transactional
    fun toggleMeal(userEmail: String, request: ToggleMealRequest): DailyMealStatusDto {
        val user = userRepository.findByEmail(userEmail)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        val membership = membershipRepository.findByUserIdAndStatus(user.id, MembershipStatus.ACTIVE)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "No active Meal membership found") }

        val messId = membership.messId
        val mealDate = request.date
        val session = request.session
        val today = LocalDate.now(dhakaZone)

        // 1. Cannot alter meals in the past
        if (mealDate.isBefore(today)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot alter meal status for past dates")
        }

        // 2. Fetch active or expiring cycle
        val cycle = diningCycleRepository.findFirstByMessIdAndStatus(messId, CycleStatus.ACTIVE)
            .orElseGet {
                diningCycleRepository.findFirstByMessIdAndStatus(messId, CycleStatus.EXPIRING)
                    .orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "No active dining cycle in this Meal. Wait for owner to start next cycle.") }
            }

        // 3. Check if date or session is paused
        val pausesForDate = cyclePausedDayRepository.findAllByCycleIdAndPausedDate(cycle.id, mealDate)
        if (pausesForDate.any { it.session == null || it.session == session }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Dining is paused for $session on $mealDate (holiday/campus break)")
        }

        // 4. Check cutoff time and session settings from cycle configuration
        val cycleConfig = cycleConfigRepository.findByCycleId(cycle.id).orElse(null)
        val isEnabled: Boolean
        val cutoffTimeStr: String
        val unitVal: BigDecimal

        if (cycleConfig != null) {
            when (session) {
                MealSession.BREAKFAST -> {
                    isEnabled = cycleConfig.breakfastEnabled
                    cutoffTimeStr = cycleConfig.breakfastCutoff
                    unitVal = cycleConfig.breakfastMultiplier
                }
                MealSession.LUNCH -> {
                    isEnabled = cycleConfig.lunchEnabled
                    cutoffTimeStr = cycleConfig.lunchCutoff
                    unitVal = cycleConfig.lunchMultiplier
                }
                MealSession.DINNER -> {
                    isEnabled = cycleConfig.dinnerEnabled
                    cutoffTimeStr = cycleConfig.dinnerCutoff
                    unitVal = cycleConfig.dinnerMultiplier
                }
            }
        } else {
            val diningConfig = diningConfigRepository.findByMessId(messId)
                .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Dining configuration not found") }
            val sessionConfig = sessionConfigRepository.findByDiningConfigIdAndSession(diningConfig.id, session)
                .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Session config not found") }
            isEnabled = sessionConfig.isEnabled
            cutoffTimeStr = sessionConfig.cutoffTime
            unitVal = sessionConfig.unitValue
        }

        if (!isEnabled) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$session is currently disabled for this Meal")
        }

        // Cutoff time check in Asia/Dhaka
        if (mealDate.isEqual(today)) {
            val cutoff = LocalTime.parse(cutoffTimeStr, DateTimeFormatter.ofPattern("HH:mm"))
            val now = LocalTime.now(dhakaZone)
            if (now.isAfter(cutoff)) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Cutoff time ($cutoffTimeStr Asia/Dhaka) for $session has already passed for today."
                )
            }
        }

        // 5. Update or insert daily meal status
        val existing = dailyMealStatusRepository.findByUserIdAndDateAndSession(user.id, mealDate, session)
        val statusRecord: DailyMealStatus = if (existing.isPresent) {
            val current = existing.get()
            current.status = request.status
            current.unitValue = unitVal
            current.isAutoCarried = false
            dailyMealStatusRepository.save(current)
        } else {
            val newStatus = DailyMealStatus(
                cycleId = cycle.id,
                messId = messId,
                userId = user.id,
                date = mealDate,
                session = session,
                status = request.status,
                unitValue = unitVal,
                isAutoCarried = false
            )
            dailyMealStatusRepository.save(newStatus)
        }

        return DailyMealStatusDto(
            id = statusRecord.id,
            date = statusRecord.date,
            session = statusRecord.session,
            status = statusRecord.status,
            unitValue = statusRecord.unitValue,
            isAutoCarried = statusRecord.isAutoCarried
        )
    }

    @Transactional
    fun bookGuestMeal(userEmail: String, request: BookGuestMealRequest): GuestMealDto {
        val user = userRepository.findByEmail(userEmail)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        val membership = membershipRepository.findByUserIdAndStatus(user.id, MembershipStatus.ACTIVE)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "No active Meal membership found") }

        val messId = membership.messId
        val mealDate = request.date
        val session = request.session
        val today = LocalDate.now(dhakaZone)

        if (mealDate.isBefore(today)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot book guest meals for past dates")
        }

        if (request.guestCount <= 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Guest count must be at least 1")
        }

        val cycle = diningCycleRepository.findFirstByMessIdAndStatus(messId, CycleStatus.ACTIVE)
            .orElseGet {
                diningCycleRepository.findFirstByMessIdAndStatus(messId, CycleStatus.EXPIRING)
                    .orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "No active dining cycle in this Meal") }
            }

        val pausesForDate = cyclePausedDayRepository.findAllByCycleIdAndPausedDate(cycle.id, mealDate)
        if (pausesForDate.any { it.session == null || it.session == session }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Dining is paused for $session on $mealDate (holiday/break)")
        }

        val cycleConfig = cycleConfigRepository.findByCycleId(cycle.id).orElse(null)
        val cutoffTimeStr = when (session) {
            MealSession.BREAKFAST -> cycleConfig?.breakfastCutoff ?: "07:00"
            MealSession.LUNCH -> cycleConfig?.lunchCutoff ?: "12:00"
            MealSession.DINNER -> cycleConfig?.dinnerCutoff ?: "19:00"
        }
        val unitMultiplier = when (session) {
            MealSession.BREAKFAST -> cycleConfig?.breakfastMultiplier ?: BigDecimal("0.50")
            MealSession.LUNCH -> cycleConfig?.lunchMultiplier ?: BigDecimal("1.00")
            MealSession.DINNER -> cycleConfig?.dinnerMultiplier ?: BigDecimal("1.00")
        }

        if (mealDate.isEqual(today)) {
            val cutoff = LocalTime.parse(cutoffTimeStr, DateTimeFormatter.ofPattern("HH:mm"))
            val now = LocalTime.now(dhakaZone)
            if (now.isAfter(cutoff)) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Cutoff time ($cutoffTimeStr Asia/Dhaka) for $session has already passed for today."
                )
            }
        }

        val mealUnits = unitMultiplier.multiply(BigDecimal(request.guestCount))

        val guestMeal = GuestMeal(
            cycleId = cycle.id,
            messId = messId,
            hostUserId = user.id,
            date = mealDate,
            session = session,
            guestCount = request.guestCount,
            mealUnits = mealUnits,
            guestName = request.guestName?.trim(),
            notes = request.notes?.trim()
        )
        val saved = guestMealRepository.save(guestMeal)

        return GuestMealDto(
            id = saved.id,
            date = saved.date,
            session = saved.session,
            guestCount = saved.guestCount,
            mealUnits = saved.mealUnits,
            guestName = saved.guestName,
            notes = saved.notes,
            totalCost = saved.totalCost
        )
    }

    @Transactional(readOnly = true)
    fun getMyGuestMeals(userEmail: String): List<GuestMealDto> {
        val user = userRepository.findByEmail(userEmail)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        val membership = membershipRepository.findByUserIdAndStatus(user.id, MembershipStatus.ACTIVE)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "No active Meal membership found") }

        val cycle = diningCycleRepository.findFirstByMessIdAndStatus(membership.messId, CycleStatus.ACTIVE)
            .orElseGet {
                diningCycleRepository.findAllByMessIdOrderByCycleNumberDesc(membership.messId).firstOrNull()
            } ?: return emptyList()

        val list = guestMealRepository.findAllByCycleIdAndHostUserId(cycle.id, user.id)
        return list.map {
            GuestMealDto(
                id = it.id,
                date = it.date,
                session = it.session,
                guestCount = it.guestCount,
                mealUnits = it.mealUnits,
                guestName = it.guestName,
                notes = it.notes,
                totalCost = it.totalCost
            )
        }
    }

    @Transactional(readOnly = true)
    fun getStudentMealStatuses(userEmail: String, startDate: LocalDate, endDate: LocalDate): List<DailyMealStatusDto> {
        val user = userRepository.findByEmail(userEmail)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        val list = dailyMealStatusRepository.findAllByUserIdAndDateBetweenOrderByDateAsc(user.id, startDate, endDate)
        return list.map {
            DailyMealStatusDto(
                id = it.id,
                date = it.date,
                session = it.session,
                status = it.status,
                unitValue = it.unitValue,
                isAutoCarried = it.isAutoCarried
            )
        }
    }
}
