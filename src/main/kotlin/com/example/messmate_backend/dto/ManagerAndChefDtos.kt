package com.example.messmate_backend.dto

import com.example.messmate_backend.model.enums.DepositStatus
import com.example.messmate_backend.model.enums.MealSession
import com.example.messmate_backend.model.enums.PaymentMethod
import com.example.messmate_backend.model.enums.VoteDecision
import com.example.messmate_backend.model.enums.VoteStatus
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

// ========================== DEPOSIT DTOs ==========================

data class CreateDepositRequest(
    @field:NotNull(message = "Amount is required")
    @field:DecimalMin(value = "1.00", message = "Minimum deposit amount is 1.00")
    val amount: BigDecimal,

    @field:NotNull(message = "Payment method is required")
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,

    val transactionRef: String? = null,
    val notes: String? = null
)

data class UpdateDepositRequest(
    val paymentMethod: PaymentMethod? = null,
    val transactionRef: String? = null,
    val notes: String? = null
)

data class DepositResponse(
    val id: String,
    val cycleId: String,
    val messId: String,
    val userId: String,
    val userFullName: String,
    val roomNumber: String? = null,
    val amount: BigDecimal,
    val depositDate: LocalDateTime,
    val paymentMethod: PaymentMethod,
    val transactionRef: String?,
    val status: DepositStatus,
    val approvedById: String?,
    val approvedByName: String?,
    val notes: String?,
    val createdAt: LocalDateTime
)

data class ReviewDepositRequest(
    @field:NotNull(message = "Decision (approved: true/false) is required")
    val approved: Boolean,
    val rejectionReason: String? = null
)

// ========================== CHEF DTOs ==========================

data class ChefSessionHeadcount(
    val session: MealSession,
    val studentOnCount: Int,
    val guestMealCount: Int,
    val totalHeadcount: Int,
    val menuItemName: String?,
    val dietaryTags: List<String>,
    val notes: List<String>,
    val isEnabled: Boolean = true,
    val servingStartTime: String? = null,
    val servingEndTime: String? = null
)

data class ChefDailyHeadcountResponse(
    val messId: String,
    val messName: String,
    val date: LocalDate,
    val sessions: List<ChefSessionHeadcount>,
    val isPaused: Boolean = false,
    val pauseReason: String? = null
)

// ========================== EXPORT DTOs ==========================

data class MealStatusExportRow(
    val date: LocalDate,
    val session: MealSession,
    val studentName: String,
    val studentEmail: String,
    val status: String,
    val guestCount: Int,
    val notes: String?
)

// ========================== GOVERNANCE DTOs ==========================

data class InitiateTransferVoteRequest(
    @field:NotBlank(message = "Mess ID is required")
    val messId: String,

    @field:NotBlank(message = "Proposed User ID is required")
    val proposedUserId: String,

    @field:NotBlank(message = "Reason is required")
    val reason: String
)

data class CastVoteRequest(
    @field:NotNull(message = "Decision (APPROVE/REJECT) is required")
    val decision: VoteDecision
)

data class BallotDetail(
    val ballotId: String,
    val voterId: String,
    val voterName: String,
    val decision: VoteDecision,
    val votedAt: LocalDateTime
)

data class TransferVoteResponse(
    val id: String,
    val messId: String,
    val initiatedById: String,
    val initiatedByName: String,
    val proposedUserId: String,
    val proposedUserName: String,
    val reason: String,
    val status: VoteStatus,
    val expiresAt: LocalDateTime,
    val resolvedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val totalActiveMembers: Int,
    val approveCount: Int,
    val rejectCount: Int,
    val thresholdNeeded: Int,
    val hasPassed: Boolean,
    val canExecute: Boolean,
    val ballots: List<BallotDetail>
)
