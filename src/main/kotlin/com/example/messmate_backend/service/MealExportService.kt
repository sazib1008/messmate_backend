package com.example.messmate_backend.service

import com.example.messmate_backend.dto.MealStatusExportRow
import com.example.messmate_backend.entity.DailyMealStatus
import com.example.messmate_backend.entity.GuestMeal
import com.example.messmate_backend.model.enums.MealSession
import com.example.messmate_backend.model.enums.MealStatus
import com.example.messmate_backend.model.enums.MembershipStatus
import com.example.messmate_backend.repository.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate

@Service
class MealExportService(
    private val messMembershipRepository: MessMembershipRepository,
    private val dailyMealStatusRepository: DailyMealStatusRepository,
    private val guestMealRepository: GuestMealRepository,
    private val userRepository: UserRepository
) {

    @Transactional(readOnly = true)
    fun getExportRows(messId: String, startDate: LocalDate, endDate: LocalDate): List<MealStatusExportRow> {
        if (startDate.isAfter(endDate)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate cannot be after endDate")
        }

        val members = messMembershipRepository.findAllByMessId(messId)
            .filter { it.status == MembershipStatus.ACTIVE }

        val userIds = members.map { it.userId }
        val userMap = userRepository.findAllById(userIds).associateBy { it.id }

        val rows = mutableListOf<MealStatusExportRow>()
        var curr = startDate
        while (!curr.isAfter(endDate)) {
            val date = curr
            val guestMeals = guestMealRepository.findAllByMessIdAndDate(messId, date)

            for (session in MealSession.entries) {
                val statuses = dailyMealStatusRepository.findAllByMessIdAndDateAndSession(messId, date, session)
                    .associateBy { it.userId }

                for (member in members) {
                    val user = userMap[member.userId] ?: continue
                    val statusObj = statuses[member.userId]
                    val mealStatus = statusObj?.status ?: MealStatus.OFF

                    val guestMealsForUser = guestMeals
                        .filter { it.hostUserId == member.userId && it.session == session }
                    val guestCount = guestMealsForUser.sumOf { it.guestCount }
                    val notes = guestMealsForUser.mapNotNull { it.notes }.joinToString("; ").ifBlank { null }

                    rows.add(
                        MealStatusExportRow(
                            date = date,
                            session = session,
                            studentName = user.fullName,
                            studentEmail = user.email,
                            status = mealStatus.name,
                            guestCount = guestCount,
                            notes = notes
                        )
                    )
                }
            }
            curr = curr.plusDays(1)
        }

        return rows
    }

    @Transactional(readOnly = true)
    fun exportToCsv(messId: String, startDate: LocalDate, endDate: LocalDate): String {
        val rows = getExportRows(messId, startDate, endDate)
        val sb = StringBuilder()
        sb.append("Date,Session,Student Name,Email,Status,Guest Count,Notes\n")

        for (r in rows) {
            val escapedName = escapeCsv(r.studentName)
            val escapedNotes = escapeCsv(r.notes ?: "")
            sb.append("${r.date},${r.session},$escapedName,${r.studentEmail},${r.status},${r.guestCount},$escapedNotes\n")
        }

        return sb.toString()
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
