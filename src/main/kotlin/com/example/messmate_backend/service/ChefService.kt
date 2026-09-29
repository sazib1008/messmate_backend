package com.example.messmate_backend.service

import com.example.messmate_backend.dto.ChefDailyHeadcountResponse
import com.example.messmate_backend.dto.ChefSessionHeadcount
import com.example.messmate_backend.model.enums.CycleStatus
import com.example.messmate_backend.model.enums.MealSession
import com.example.messmate_backend.model.enums.MealStatus
import com.example.messmate_backend.model.enums.MembershipStatus
import com.example.messmate_backend.model.enums.UserRole
import com.example.messmate_backend.repository.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate

@Service
class ChefService(
    private val messRepository: MessRepository,
    private val dailyMealStatusRepository: DailyMealStatusRepository,
    private val guestMealRepository: GuestMealRepository,
    private val menuRepository: MenuRepository,
    private val menuItemRepository: MenuItemRepository,
    private val diningConfigRepository: DiningConfigurationRepository,
    private val sessionConfigRepository: MealSessionConfigRepository,
    private val membershipRepository: MessMembershipRepository,
    private val diningCycleRepository: DiningCycleRepository,
    private val cyclePausedDayRepository: CyclePausedDayRepository
) {

    @Transactional(readOnly = true)
    fun getDailyHeadcount(messId: String, date: LocalDate): ChefDailyHeadcountResponse {
        val mess = messRepository.findById(messId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Mess not found with ID: $messId") }

        // Fetch active cycle and check for registered paused days / holidays
        val activeCycle = diningCycleRepository.findFirstByMessIdAndStatus(messId, CycleStatus.ACTIVE)
            .orElseGet {
                diningCycleRepository.findFirstByMessIdAndStatus(messId, CycleStatus.EXPIRING).orElse(null)
            }

        val pausedDays = if (activeCycle != null) cyclePausedDayRepository.findAllByCycleId(activeCycle.id) else emptyList()
        val fullDayPause = pausedDays.firstOrNull { it.pausedDate == date && it.session == null }

        val diningConfigOpt = diningConfigRepository.findByMessId(messId)
        val diningConfig = diningConfigOpt.orElse(null)
        val sessionConfigs = diningConfigOpt.map {
            sessionConfigRepository.findAllByDiningConfigId(it.id)
        }.orElse(emptyList())

        // Exclude/hide disabled sessions from Chef Kitchen headcount
        val enabledSessions = MealSession.entries.filter { session ->
            val cfg = sessionConfigs.find { it.session == session }
            cfg?.isEnabled ?: true
        }

        // If the entire day is registered as paused / holiday, return 0 headcounts immediately
        if (fullDayPause != null) {
            val pausedSessions = enabledSessions.map { session ->
                val cfg = sessionConfigs.find { it.session == session }
                ChefSessionHeadcount(
                    session = session,
                    studentOnCount = 0,
                    guestMealCount = 0,
                    totalHeadcount = 0,
                    menuItemName = "Mess Paused / Holiday (${fullDayPause.reason.ifBlank { "No Service Scheduled" }})",
                    dietaryTags = emptyList(),
                    notes = emptyList(),
                    isEnabled = false,
                    servingStartTime = cfg?.servingStartTime,
                    servingEndTime = cfg?.servingEndTime
                )
            }
            return ChefDailyHeadcountResponse(
                messId = mess.id,
                messName = mess.name,
                date = date,
                sessions = pausedSessions,
                isPaused = true,
                pauseReason = fullDayPause.reason
            )
        }

        // Day of week: 0 = Sunday, 1 = Monday ... 6 = Saturday
        val dayOfWeekIndex = date.dayOfWeek.value % 7 // Java DayOfWeek: 1=Mon...7=Sun => Sun=0, Mon=1...
        val activeMenu = menuRepository.findFirstByMessIdAndIsActiveTrue(messId)
        val todayMenuItems = activeMenu.map {
            menuItemRepository.findAllByMenuIdAndDayOfWeek(it.id, dayOfWeekIndex)
        }.orElse(emptyList())

        val guestMealsForDay = guestMealRepository.findAllByMessIdAndDate(messId, date)

        // Active dining members of the mess (exclude CHEF role; include OWNER, PRIMARY_MANAGER, MANAGER, STUDENT/MEMBER)
        val activeDiningMembers = membershipRepository.findAllByMessId(messId)
            .filter { it.role != UserRole.CHEF }
            .filter {
                it.status == MembershipStatus.ACTIVE &&
                (it.role.isOwnerOrManager || !it.joinDate.toLocalDate().isAfter(date)) &&
                (it.leaveDate == null || !it.leaveDate!!.toLocalDate().isBefore(date))
            }

        val sessionHeadcounts = enabledSessions.map { session ->
            val cfg = sessionConfigs.find { it.session == session }
            val sessionPause = pausedDays.firstOrNull { it.pausedDate == date && it.session == session }

            if (sessionPause != null) {
                ChefSessionHeadcount(
                    session = session,
                    studentOnCount = 0,
                    guestMealCount = 0,
                    totalHeadcount = 0,
                    menuItemName = "Session Paused (${sessionPause.reason.ifBlank { "No Service Scheduled" }})",
                    dietaryTags = emptyList(),
                    notes = emptyList(),
                    isEnabled = false,
                    servingStartTime = cfg?.servingStartTime,
                    servingEndTime = cfg?.servingEndTime
                )
            } else {
                // Default ON/OFF status from DiningConfiguration for this session
                val defaultSessionOn = when (session) {
                    MealSession.BREAKFAST -> diningConfig?.defaultBreakfastOn ?: false
                    MealSession.LUNCH -> diningConfig?.defaultLunchOn ?: true
                    MealSession.DINNER -> diningConfig?.defaultDinnerOn ?: true
                }

                // Explicit meal status records in DB for this mess, date, session
                val explicitStatuses = dailyMealStatusRepository.findAllByMessIdAndDateAndSession(messId, date, session)
                val explicitMap = explicitStatuses.associateBy { it.userId }

                // Count members effectively ON:
                // 1) Active members: explicit status if exists, otherwise mess default for this session
                // 2) Any additional member who explicitly booked ON (safeguard)
                val activeMemberUserIds = activeDiningMembers.map { it.userId }.toSet()
                val activeMembersOnCount = activeDiningMembers.count { member ->
                    val record = explicitMap[member.userId]
                    if (record != null) {
                        record.status == MealStatus.ON
                    } else {
                        defaultSessionOn
                    }
                }

                val additionalExplicitOnCount = explicitStatuses
                    .filter { it.status == MealStatus.ON && it.userId !in activeMemberUserIds }
                    .size

                val studentOnCount = activeMembersOnCount + additionalExplicitOnCount

                // Guest notes for this session (e.g. dietary or special requirements)
                val sessionGuests = guestMealsForDay.filter { it.session == session }
                val guestCount = sessionGuests.sumOf { it.guestCount }
                val guestNotes = sessionGuests.mapNotNull { it.notes }.filter { it.isNotBlank() }

                // Menu item for this session
                val menuItem = todayMenuItems.firstOrNull { it.session == session }

                ChefSessionHeadcount(
                    session = session,
                    studentOnCount = studentOnCount,
                    guestMealCount = guestCount,
                    totalHeadcount = studentOnCount + guestCount,
                    menuItemName = menuItem?.itemName,
                    dietaryTags = menuItem?.dietaryTags ?: emptyList(),
                    notes = guestNotes,
                    isEnabled = true,
                    servingStartTime = cfg?.servingStartTime,
                    servingEndTime = cfg?.servingEndTime
                )
            }
        }

        return ChefDailyHeadcountResponse(
            messId = mess.id,
            messName = mess.name,
            date = date,
            sessions = sessionHeadcounts
        )
    }
}
