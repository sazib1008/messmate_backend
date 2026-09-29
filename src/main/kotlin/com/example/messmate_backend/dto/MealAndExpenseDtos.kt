package com.example.messmate_backend.dto

import com.example.messmate_backend.model.enums.ExpenseCategory
import com.example.messmate_backend.model.enums.MealSession
import com.example.messmate_backend.model.enums.MealStatus
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.LocalDate

data class ToggleMealRequest(
    val date: LocalDate,
    val session: MealSession,
    val status: MealStatus
)

data class DailyMealStatusDto(
    val id: String,
    val date: LocalDate,
    val session: MealSession,
    val status: MealStatus,
    val unitValue: BigDecimal,
    val isAutoCarried: Boolean
)

data class BookGuestMealRequest(
    val date: LocalDate,
    val session: MealSession,
    val guestCount: Int,
    val guestName: String? = null,
    val notes: String? = null
)

data class CreateExpenseRequest(
    val category: ExpenseCategory,
    val title: String,
    val amount: BigDecimal,
    val expenseDate: LocalDate,
    val receiptUrl: String? = null,
    val notes: String? = null,
    val targetMemberId: String? = null,
    val participantIds: List<String>? = emptyList()
)

data class ExpenseDto(
    val id: String,
    val category: ExpenseCategory,
    val title: String,
    val amount: BigDecimal,
    val expenseDate: LocalDate,
    val receiptUrl: String?,
    val recordedByName: String,
    val notes: String?,
    val targetMemberId: String? = null,
    val targetMemberName: String? = null,
    val participantIds: List<String> = emptyList()
)

data class SessionConfigDto(
    val session: MealSession,
    val unitValue: BigDecimal,
    val cutoffTime: String,
    @get:JsonProperty("isEnabled")
    @param:JsonProperty("isEnabled")
    val isEnabled: Boolean,
    val servingStartTime: String? = null,
    val servingEndTime: String? = null
)

data class DiningConfigDto(
    val defaultCarryForward: Boolean,
    val defaultBreakfastOn: Boolean,
    val defaultLunchOn: Boolean,
    val defaultDinnerOn: Boolean,
    val currency: String,
    val sessions: List<SessionConfigDto>
)

data class MenuItemDto(
    val id: String,
    val dayOfWeek: Int,
    val session: MealSession,
    val itemName: String,
    val description: String?,
    val category: String?,
    val dietaryTags: List<String>
)

data class WeeklyMenuDto(
    val id: String,
    val title: String,
    val effectiveFrom: LocalDate,
    val items: List<MenuItemDto>,
    val activeSessions: List<MealSession> = emptyList()
)
