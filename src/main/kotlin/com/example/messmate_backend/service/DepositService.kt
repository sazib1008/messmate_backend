package com.example.messmate_backend.service

import com.example.messmate_backend.dto.CreateDepositRequest
import com.example.messmate_backend.dto.DepositResponse
import com.example.messmate_backend.dto.ReviewDepositRequest
import com.example.messmate_backend.dto.UpdateDepositRequest
import com.example.messmate_backend.entity.BalanceLedger
import com.example.messmate_backend.entity.Deposit
import com.example.messmate_backend.model.enums.*
import com.example.messmate_backend.repository.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDateTime

@Service
class DepositService(
    private val depositRepository: DepositRepository,
    private val messMembershipRepository: MessMembershipRepository,
    private val diningCycleRepository: DiningCycleRepository,
    private val balanceLedgerRepository: BalanceLedgerRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService
) {

    private fun resolveUserId(identifier: String): String {
        return userRepository.findByEmail(identifier)
            .map { it.id }
            .orElse(identifier)
    }

    /**
     * Throws HTTP 409 Conflict if the deposit's parent cycle has status COMPLETED (settled).
     * Once a cycle is concluded, all its deposits are frozen and immutable.
     */
    private fun assertCycleNotCompleted(cycleId: String) {
        val cycle = diningCycleRepository.findById(cycleId).orElse(null)
        if (cycle != null && cycle.status == CycleStatus.COMPLETED) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Cannot modify a deposit from a settled cycle"
            )
        }
    }

    @Transactional
    fun submitDeposit(userId: String, req: CreateDepositRequest): DepositResponse {
        val actualUserId = resolveUserId(userId)
        val membership = messMembershipRepository.findByUserIdAndStatus(actualUserId, MembershipStatus.ACTIVE)
            .orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "User has no active Meal membership") }

        val activeCycle = diningCycleRepository.findFirstByMessIdAndStatus(membership.messId, CycleStatus.ACTIVE)
            .orElseGet {
                diningCycleRepository.findFirstByMessIdAndStatus(membership.messId, CycleStatus.EXPIRING)
                    .orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "No active dining cycle found for Meal") }
            }

        val deposit = Deposit(
            cycleId = activeCycle.id,
            messId = membership.messId,
            userId = actualUserId,
            amount = req.amount,
            depositDate = LocalDateTime.now(),
            paymentMethod = req.paymentMethod,
            transactionRef = req.transactionRef?.trim(),
            status = DepositStatus.PENDING,
            notes = req.notes?.trim()
        )

        val saved = depositRepository.save(deposit)
        return mapToResponse(saved)
    }

    @Transactional(readOnly = true)
    fun listDeposits(
        messId: String?,
        userId: String?,
        status: DepositStatus?,
        callerUserId: String
    ): List<DepositResponse> {
        val actualCallerId = resolveUserId(callerUserId)
        val callerMembership = messMembershipRepository.findByUserIdAndStatus(actualCallerId, MembershipStatus.ACTIVE)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "Caller has no active Meal membership") }

        val isManager = callerMembership.role.isOwnerOrManager

        val targetMessId = messId ?: callerMembership.messId
        if (targetMessId != callerMembership.messId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot access deposits of another Meal")
        }

        val targetUserId = userId?.let { resolveUserId(it) }

        val deposits = if (!isManager) {
            // Students can only view their own deposits
            depositRepository.findAllByMessIdAndUserIdOrderByDepositDateDesc(targetMessId, actualCallerId)
        } else {
            // Managers can view all, or filtered by user, or filtered by status
            when {
                targetUserId != null -> depositRepository.findAllByMessIdAndUserIdOrderByDepositDateDesc(targetMessId, targetUserId)
                status != null -> depositRepository.findAllByMessIdAndStatusOrderByDepositDateDesc(targetMessId, status)
                else -> depositRepository.findAllByMessIdOrderByDepositDateDesc(targetMessId)
            }
        }

        return deposits.map { mapToResponse(it) }
    }

    @Transactional
    fun reviewDeposit(depositId: String, reviewerUserId: String, req: ReviewDepositRequest): DepositResponse {
        val actualReviewerId = resolveUserId(reviewerUserId)
        val deposit = depositRepository.findById(depositId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Deposit not found with ID: $depositId") }

        // Guard: cannot review a deposit from a settled/concluded cycle
        assertCycleNotCompleted(deposit.cycleId)

        if (deposit.status != DepositStatus.PENDING) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Deposit has already been processed with status: ${deposit.status}"
            )
        }

        val reviewerMembership = messMembershipRepository.findByMessIdAndUserId(deposit.messId, actualReviewerId)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "Reviewer does not belong to this Meal") }

        if (!reviewerMembership.role.isOwnerOrManager) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only Meal Owner or Managers can review deposits")
        }

        deposit.approvedById = actualReviewerId
        deposit.updatedAt = LocalDateTime.now()

        if (req.approved) {
            deposit.status = DepositStatus.APPROVED

            // APPROVE: add the EXACT student-submitted amount to the running balance — not one paisa different
            val latestLedger = balanceLedgerRepository.findFirstByUserIdOrderByCreatedAtDesc(deposit.userId)
            val prevBalance = latestLedger.map { it.balanceAfter }.orElse(BigDecimal.ZERO)
            val newBalance = prevBalance.add(deposit.amount)

            val ledgerEntry = BalanceLedger(
                messId = deposit.messId,
                userId = deposit.userId,
                cycleId = deposit.cycleId,
                entryType = LedgerEntryType.DEPOSIT,
                amount = deposit.amount,
                balanceAfter = newBalance,
                referenceId = deposit.id,
                description = "Deposit approved via ${deposit.paymentMethod} (Ref: ${deposit.transactionRef ?: "N/A"})",
                createdAt = LocalDateTime.now()
            )
            balanceLedgerRepository.save(ledgerEntry)
        } else {
            // REJECT: status set to REJECTED, balance is completely untouched (as if it never happened)
            deposit.status = DepositStatus.REJECTED
            val reason = req.rejectionReason?.trim() ?: "Rejected by manager"
            deposit.notes = listOfNotNull(deposit.notes, "Rejection Reason: $reason").joinToString(" | ")
        }

        val updated = depositRepository.save(deposit)

        try {
            val isApproved = updated.status == DepositStatus.APPROVED
            val title = if (isApproved) "Deposit Approved" else "Deposit Rejected"
            val body = if (isApproved)
                "Your deposit of ৳${updated.amount} has been approved."
            else
                "Your deposit of ৳${updated.amount} was rejected. ${req.rejectionReason ?: ""}".trim()
            val eventType = if (isApproved) "DEPOSIT_APPROVED" else "DEPOSIT_REJECTED"

            notificationService.sendPushToUser(
                userId = updated.userId,
                title = title,
                body = body,
                data = mapOf(
                    "screen" to "wallet",
                    "type" to eventType,
                    "depositId" to updated.id,
                    "amount" to updated.amount.toString()
                )
            )
        } catch (e: Exception) {
            // Guard: notification failure must never block or roll back the business transaction
        }

        return mapToResponse(updated)
    }

    @Transactional(readOnly = true)
    fun getDepositById(depositId: String, callerUserId: String): DepositResponse {
        val actualCallerId = resolveUserId(callerUserId)
        val deposit = depositRepository.findById(depositId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Deposit not found with ID: $depositId") }

        val callerMembership = messMembershipRepository.findByMessIdAndUserId(deposit.messId, actualCallerId)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "User does not belong to this Meal") }

        if (!callerMembership.role.isOwnerOrManager && deposit.userId != actualCallerId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Students can only view their own deposit records")
        }

        return mapToResponse(deposit)
    }

    /**
     * Update non-financial deposit metadata only: paymentMethod, transactionRef, notes.
     *
     * The [amount] field is intentionally NOT part of [UpdateDepositRequest].
     * The amount is set by the student at submission time and is immutable thereafter.
     * If the amount is wrong, the manager must REJECT the deposit and ask the student
     * to resubmit with the correct amount — never edit in place.
     */
    @Transactional
    fun updateDeposit(depositId: String, managerUserId: String, req: UpdateDepositRequest): DepositResponse {
        val actualManagerId = resolveUserId(managerUserId)
        val deposit = depositRepository.findById(depositId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Deposit not found with ID: $depositId") }

        // Guard: cannot edit a deposit from a concluded cycle
        assertCycleNotCompleted(deposit.cycleId)

        val membership = messMembershipRepository.findByMessIdAndUserId(deposit.messId, actualManagerId)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "Manager does not belong to this Meal") }

        if (!membership.role.isOwnerOrManager) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only Meal Owner or Managers can edit deposits")
        }

        // Patch only non-financial fields; amount stays untouched
        req.paymentMethod?.let { deposit.paymentMethod = it }
        req.transactionRef?.let { deposit.transactionRef = it.trim() }
        req.notes?.let { deposit.notes = it.trim() }
        deposit.updatedAt = LocalDateTime.now()

        val saved = depositRepository.save(deposit)
        return mapToResponse(saved)
    }

    @Transactional
    fun deleteDeposit(depositId: String, managerUserId: String) {
        val actualManagerId = resolveUserId(managerUserId)
        val deposit = depositRepository.findById(depositId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Deposit not found with ID: $depositId") }

        // Guard: cannot delete a deposit from a concluded cycle
        assertCycleNotCompleted(deposit.cycleId)

        val membership = messMembershipRepository.findByMessIdAndUserId(deposit.messId, actualManagerId)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "Manager does not belong to this Meal") }

        if (!membership.role.isOwnerOrManager) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only Meal Owner or Managers can delete deposits")
        }

        // If the deposit was already approved, reverse its ledger impact
        if (deposit.status == DepositStatus.APPROVED) {
            val latestLedger = balanceLedgerRepository.findFirstByUserIdOrderByCreatedAtDesc(deposit.userId)
            val prevBalance = latestLedger.map { it.balanceAfter }.orElse(BigDecimal.ZERO)
            val newBalance = prevBalance.subtract(deposit.amount)

            val reversalEntry = BalanceLedger(
                messId = deposit.messId,
                userId = deposit.userId,
                cycleId = deposit.cycleId,
                entryType = LedgerEntryType.ADJUSTMENT,
                amount = deposit.amount,
                balanceAfter = newBalance,
                referenceId = deposit.id,
                description = "Reversal for deleted approved deposit #${deposit.id.take(8)}",
                createdAt = LocalDateTime.now()
            )
            balanceLedgerRepository.save(reversalEntry)
        }

        depositRepository.delete(deposit)
    }

    private fun mapToResponse(deposit: Deposit): DepositResponse {
        val user = userRepository.findById(deposit.userId).orElse(null)
        val reviewer = deposit.approvedById?.let { userRepository.findById(it).orElse(null) }

        return DepositResponse(
            id = deposit.id,
            cycleId = deposit.cycleId,
            messId = deposit.messId,
            userId = deposit.userId,
            userFullName = user?.fullName ?: "Unknown User",
            roomNumber = user?.roomNumber,
            amount = deposit.amount,
            depositDate = deposit.depositDate,
            paymentMethod = deposit.paymentMethod,
            transactionRef = deposit.transactionRef,
            status = deposit.status,
            approvedById = deposit.approvedById,
            approvedByName = reviewer?.fullName,
            notes = deposit.notes,
            createdAt = deposit.createdAt
        )
    }
}
