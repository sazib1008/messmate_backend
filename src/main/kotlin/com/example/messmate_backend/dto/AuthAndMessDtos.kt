package com.example.messmate_backend.dto

import com.example.messmate_backend.model.enums.JoinRequestStatus
import com.example.messmate_backend.model.enums.MembershipStatus
import com.example.messmate_backend.model.enums.UserRole
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.LocalDateTime

data class RegisterRequest(
    val email: String,
    val password: String,
    val fullName: String,
    val phone: String? = null,
    val studentId: String? = null,
    val department: String? = null,
    val university: String? = null,
    val roomNumber: String? = null
)

data class LoginRequest(
    val email: String,
    val password: String
)

data class UpdateProfileRequest(
    val fullName: String? = null,
    val phone: String? = null,
    val studentId: String? = null,
    val department: String? = null,
    val university: String? = null,
    val roomNumber: String // Room number is mandatory for profile completion
)

data class UserDto(
    val id: String,
    val email: String,
    val fullName: String,
    val phone: String?,
    val avatarUrl: String?,
    val studentId: String? = null,
    val department: String? = null,
    val university: String? = null,
    val roomNumber: String? = null,
    val isProfileComplete: Boolean = false
)

data class MembershipDto(
    val id: String,
    val mealId: String,
    val mealName: String,
    val mealCode: String = "",
    val role: UserRole,
    val status: MembershipStatus,
    val joinDate: LocalDateTime,
    // Backwards compatibility aliases
    val messId: String = mealId,
    val messName: String = mealName,
    val code: String = mealCode
)

data class PendingJoinRequestDto(
    val id: String,
    val mealId: String,
    val mealName: String,
    val mealCode: String,
    val ownerName: String,
    val status: JoinRequestStatus,
    val requestNotes: String?,
    val createdAt: LocalDateTime
)

data class AuthResponse(
    val token: String,
    val user: UserDto,
    val activeMembership: MembershipDto?,
    val pendingJoinRequest: PendingJoinRequestDto? = null
)

data class CreateMealRequest(
    val name: String,
    val address: String? = null,
    val currency: String = "BDT",
    val targetActiveDays: Int = 30,
    val breakfastEnabled: Boolean = true,
    val lunchEnabled: Boolean = true,
    val dinnerEnabled: Boolean = true,
    val breakfastMultiplier: BigDecimal = BigDecimal("0.50"),
    val lunchMultiplier: BigDecimal = BigDecimal("1.00"),
    val dinnerMultiplier: BigDecimal = BigDecimal("1.00"),
    val breakfastCutoff: String = "07:00",
    val lunchCutoff: String = "12:00",
    val dinnerCutoff: String = "19:00",
    val breakfastServingStartTime: String? = "07:30",
    val breakfastServingEndTime: String? = "09:30",
    val lunchServingStartTime: String? = "13:00",
    val lunchServingEndTime: String? = "14:30",
    val dinnerServingStartTime: String? = "20:30",
    val dinnerServingEndTime: String? = "22:00",
    val defaultCarryForward: Boolean = true,
    val combinedSessionRule: String = "SEPARATE"
)

typealias CreateMessRequest = CreateMealRequest

data class MealDto(
    val id: String,
    val name: String,
    val code: String,
    val address: String?,
    val createdById: String,
    val memberCount: Int = 1,
    val currentCycleNumber: Int = 1,
    val currentCycleStatus: String = "ACTIVE"
)

typealias MessDto = MealDto

data class VerifyMealCodeRequest(
    val code: String
)

data class MealCodeVerificationResponse(
    val mealId: String,
    val name: String,
    val code: String,
    val address: String?,
    val ownerName: String,
    val memberCount: Int,
    val currentCycleNumber: Int,
    val currentCycleStatus: String
)

data class SubmitJoinRequest(
    val code: String,
    val notes: String? = null,
    val roomNumber: String? = null
)

data class UpdateMemberRoomRequest(
    val roomNumber: String?
)

typealias JoinMessRequest = SubmitJoinRequest

data class MealJoinRequestDto(
    val id: String,
    val mealId: String,
    val mealName: String,
    val userId: String,
    val userName: String,
    val userEmail: String,
    val userPhone: String?,
    val studentId: String?,
    val department: String?,
    val roomNumber: String?,
    val status: JoinRequestStatus,
    val requestNotes: String?,
    val reviewedByName: String? = null,
    val reviewedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime
)

data class ReviewJoinRequest(
    val approved: Boolean,
    val notes: String? = null
)

data class StartNewCycleRequest(
    val targetActiveDays: Int = 30,
    val defaultCarryForward: Boolean = true,
    val breakfastEnabled: Boolean = true,
    val lunchEnabled: Boolean = true,
    val dinnerEnabled: Boolean = true,
    val breakfastCutoff: String = "07:00",
    val lunchCutoff: String = "12:00",
    val dinnerCutoff: String = "19:00",
    val breakfastMultiplier: BigDecimal = BigDecimal("0.50"),
    val lunchMultiplier: BigDecimal = BigDecimal("1.00"),
    val dinnerMultiplier: BigDecimal = BigDecimal("1.00"),
    val combinedSessionRule: String = "SEPARATE",
    val notes: String? = null,
    val confirmForfeitSurplus: Boolean = false
)

data class CycleConfigurationDto(
    val id: String,
    val cycleId: String,
    val mealId: String,
    val currency: String,
    val defaultCarryForward: Boolean,
    val breakfastEnabled: Boolean,
    val lunchEnabled: Boolean,
    val dinnerEnabled: Boolean,
    val breakfastCutoff: String,
    val lunchCutoff: String,
    val dinnerCutoff: String,
    val breakfastMultiplier: BigDecimal,
    val lunchMultiplier: BigDecimal,
    val dinnerMultiplier: BigDecimal,
    val combinedSessionRule: String,
    val targetActiveDays: Int
)
