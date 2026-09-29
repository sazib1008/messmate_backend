package com.example.messmate_backend.scheduler

import com.example.messmate_backend.model.enums.CycleStatus
import com.example.messmate_backend.model.enums.MealSession
import com.example.messmate_backend.model.enums.MembershipStatus
import com.example.messmate_backend.repository.*
import com.example.messmate_backend.service.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.HashSet

@Component
class MealCutoffScheduler(
    private val diningCycleRepository: DiningCycleRepository,
    private val cycleConfigRepository: CycleConfigurationRepository,
    private val membershipRepository: MessMembershipRepository,
    private val dailyMealStatusRepository: DailyMealStatusRepository,
    private val notificationService: NotificationService
) {
    private val logger = LoggerFactory.getLogger(MealCutoffScheduler::class.java)
    private val dhakaZone = ZoneId.of("Asia/Dhaka")

    // Thread-safe set tracking notifications dispatched today: "cycleId:date:session"
    private val notifiedCutoffs: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    /**
     * Runs every 2 minutes to inspect upcoming meal cutoff deadlines.
     */
    @Scheduled(cron = "0 */2 * * * *")
    fun checkCutoffApproaching() {
        val today = LocalDate.now(dhakaZone)
        val nowTime = LocalTime.now(dhakaZone)

        // Clear yesterday's sent markers
        synchronized(notifiedCutoffs) {
            val iterator = notifiedCutoffs.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (!item.contains(today.toString())) {
                    iterator.remove()
                }
            }
        }

        val activeCycles = diningCycleRepository.findAllByStatus(CycleStatus.ACTIVE)
        if (activeCycles.isEmpty()) return

        for (cycle in activeCycles) {
            val configOpt = cycleConfigRepository.findByCycleId(cycle.id)
            if (!configOpt.isPresent) continue
            val config = configOpt.get()

            val sessionsToCheck = listOf(
                Triple(MealSession.BREAKFAST, config.breakfastEnabled, config.breakfastCutoff),
                Triple(MealSession.LUNCH, config.lunchEnabled, config.lunchCutoff),
                Triple(MealSession.DINNER, config.dinnerEnabled, config.dinnerCutoff)
            )

            for ((session, isEnabled, cutoffStr) in sessionsToCheck) {
                if (!isEnabled || cutoffStr.isNullOrBlank()) continue

                val cutoffTime = try {
                    LocalTime.parse(cutoffStr.trim(), DateTimeFormatter.ofPattern("HH:mm"))
                } catch (e: Exception) {
                    continue
                }

                // Calculate minutes remaining until cutoff today
                val minutesUntilCutoff = Duration.between(nowTime, cutoffTime).toMinutes()

                // Trigger window: between 25 and 35 minutes before cutoff (~30 min nudge)
                if (minutesUntilCutoff in 25..35) {
                    val key = "${cycle.id}:$today:$session"
                    val shouldSend = synchronized(notifiedCutoffs) {
                        notifiedCutoffs.add(key)
                    }
                    if (shouldSend) {
                        notifyMembersWithoutChoice(cycle.messId, today, session, cutoffTime)
                    }
                }
            }
        }
    }

    private fun notifyMembersWithoutChoice(
        messId: String,
        date: LocalDate,
        session: MealSession,
        cutoffTime: LocalTime
    ) {
        val activeMembers = membershipRepository.findAllByMessIdAndStatus(messId, MembershipStatus.ACTIVE)
        if (activeMembers.isEmpty()) return

        val membersToNotify = mutableListOf<String>()

        for (member in activeMembers) {
            val statusOpt = dailyMealStatusRepository.findByUserIdAndDateAndSession(member.userId, date, session)
            // If student has not set a status for this session yet
            if (!statusOpt.isPresent) {
                membersToNotify.add(member.userId)
            }
        }

        if (membersToNotify.isEmpty()) {
            logger.debug("All active members have already chosen their $session meal for $date in mess $messId.")
            return
        }

        val sessionName = session.name.lowercase().replaceFirstChar { it.uppercase() }
        logger.info("Sending meal cutoff alert to ${membersToNotify.size} student(s) in mess $messId for $sessionName (Cutoff: $cutoffTime)")

        try {
            notificationService.sendPushToUsers(
                userIds = membersToNotify,
                title = "$sessionName Cutoff Approaching",
                body = "Only 30 minutes left before $sessionName cutoff ($cutoffTime). Update your meal choice now.",
                data = mapOf(
                    "screen" to "meals",
                    "type" to "MEAL_CUTOFF",
                    "messId" to messId,
                    "session" to session.name,
                    "cutoffTime" to cutoffTime.toString()
                )
            )
        } catch (e: Exception) {
            logger.warn("Non-fatal error sending cutoff reminder: ${e.message}")
        }
    }
}
