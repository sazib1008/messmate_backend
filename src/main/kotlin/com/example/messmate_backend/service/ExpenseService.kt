package com.example.messmate_backend.service

import com.example.messmate_backend.dto.CreateExpenseRequest
import com.example.messmate_backend.dto.ExpenseDto
import com.example.messmate_backend.entity.Expense
import com.example.messmate_backend.model.enums.CycleStatus
import com.example.messmate_backend.model.enums.MembershipStatus
import com.example.messmate_backend.repository.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

@Service
class ExpenseService(
    private val expenseRepository: ExpenseRepository,
    private val diningCycleRepository: DiningCycleRepository,
    private val membershipRepository: MessMembershipRepository,
    private val userRepository: UserRepository
) {

    /**
     * Throws HTTP 409 Conflict if the expense's parent cycle has status COMPLETED (settled).
     * Once a cycle is concluded, all its expenses are frozen and immutable.
     */
    private fun assertCycleNotCompleted(cycleId: String) {
        val cycle = diningCycleRepository.findById(cycleId).orElse(null)
        if (cycle != null && cycle.status == CycleStatus.COMPLETED) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Cannot modify an expense from a settled cycle"
            )
        }
    }

    @Transactional
    fun createExpense(userEmail: String, request: CreateExpenseRequest): ExpenseDto {
        val user = userRepository.findByEmail(userEmail)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        val membership = membershipRepository.findByUserIdAndStatus(user.id, MembershipStatus.ACTIVE)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "No active Meal membership found") }

        if (!membership.role.isOwnerOrManager) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only Meal Owner or Managers can record expenses")
        }

        val messId = membership.messId
        val cycle = diningCycleRepository.findFirstByMessIdAndStatus(messId, CycleStatus.ACTIVE)
            .orElseGet {
                diningCycleRepository.findFirstByMessIdAndStatus(messId, CycleStatus.EXPIRING)
                    .orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "No active dining cycle found for Meal") }
            }

        validateExpenseInput(request.title, request.amount)

        val expense = Expense(
            cycleId = cycle.id,
            messId = messId,
            recordedById = user.id,
            category = request.category.canonicalCategory,
            title = request.title.trim(),
            amount = request.amount,
            expenseDate = request.expenseDate,
            receiptUrl = request.receiptUrl,
            notes = request.notes,
            targetMemberId = request.targetMemberId?.takeIf { it.isNotBlank() }
        ).apply {
            this.participantIds = request.participantIds ?: emptyList()
        }
        val saved = expenseRepository.save(expense)

        val targetMemberName = saved.targetMemberId?.let { targetId ->
            userRepository.findById(targetId).map { it.fullName }.orElse(null)
        }

        return ExpenseDto(
            id = saved.id,
            category = saved.category.canonicalCategory,
            title = saved.title,
            amount = saved.amount,
            expenseDate = saved.expenseDate,
            receiptUrl = saved.receiptUrl,
            recordedByName = user.fullName,
            notes = saved.notes,
            targetMemberId = saved.targetMemberId,
            targetMemberName = targetMemberName,
            participantIds = saved.participantIds
        )
    }

    @Transactional(readOnly = true)
    fun listExpenses(messId: String): List<ExpenseDto> {
        val expenses = expenseRepository.findAllByMessIdOrderByExpenseDateDesc(messId)
        return expenses.map {
            ExpenseDto(
                id = it.id,
                category = it.category.canonicalCategory,
                title = it.title,
                amount = it.amount,
                expenseDate = it.expenseDate,
                receiptUrl = it.receiptUrl,
                recordedByName = it.recordedBy?.fullName ?: "Manager",
                notes = it.notes,
                targetMemberId = it.targetMemberId,
                targetMemberName = it.targetMember?.fullName,
                participantIds = it.participantIds
            )
        }
    }

    @Transactional
    fun updateExpense(id: String, userEmail: String, request: CreateExpenseRequest): ExpenseDto {
        val user = userRepository.findByEmail(userEmail)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        val membership = membershipRepository.findByUserIdAndStatus(user.id, MembershipStatus.ACTIVE)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "No active Meal membership found") }

        if (!membership.role.isOwnerOrManager) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only Meal Owner or Managers can edit expenses")
        }

        val expense = expenseRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Expense not found") }

        if (expense.messId != membership.messId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot edit expenses of another Meal")
        }

        // Guard: cannot edit an expense from a concluded/settled cycle
        assertCycleNotCompleted(expense.cycleId)

        validateExpenseInput(request.title, request.amount)

        expense.category = request.category.canonicalCategory
        expense.title = request.title.trim()
        expense.amount = request.amount
        expense.expenseDate = request.expenseDate
        expense.receiptUrl = request.receiptUrl
        expense.notes = request.notes
        expense.targetMemberId = request.targetMemberId?.takeIf { it.isNotBlank() }
        expense.participantIds = request.participantIds ?: emptyList()
        expense.updatedAt = LocalDateTime.now()

        val saved = expenseRepository.save(expense)

        val targetMemberName = saved.targetMemberId?.let { targetId ->
            userRepository.findById(targetId).map { it.fullName }.orElse(null)
        }

        return ExpenseDto(
            id = saved.id,
            category = saved.category.canonicalCategory,
            title = saved.title,
            amount = saved.amount,
            expenseDate = saved.expenseDate,
            receiptUrl = saved.receiptUrl,
            recordedByName = saved.recordedBy?.fullName ?: user.fullName,
            notes = saved.notes,
            targetMemberId = saved.targetMemberId,
            targetMemberName = targetMemberName,
            participantIds = saved.participantIds
        )
    }

    @Transactional
    fun deleteExpense(id: String, userEmail: String) {
        val user = userRepository.findByEmail(userEmail)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        val membership = membershipRepository.findByUserIdAndStatus(user.id, MembershipStatus.ACTIVE)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "No active Meal membership found") }

        if (!membership.role.isOwnerOrManager) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only Meal Owner or Managers can delete expenses")
        }

        val expense = expenseRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Expense not found") }

        if (expense.messId != membership.messId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete expenses of another Meal")
        }

        // Guard: cannot delete an expense from a concluded/settled cycle
        assertCycleNotCompleted(expense.cycleId)

        expenseRepository.delete(expense)
    }

    private fun validateExpenseInput(title: String, amount: java.math.BigDecimal) {
        val trimmed = title.trim()
        if (trimmed.length < 3) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Expense title must be at least 3 characters long")
        }
        if (trimmed.length > 120) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Expense title cannot exceed 120 characters")
        }
        if (!trimmed.any { it.isLetter() }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Expense title must contain letters describing the item")
        }
        if (trimmed.all { it == trimmed[0] }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Expense title cannot consist of repetitive keys")
        }
        if (amount <= java.math.BigDecimal.ZERO) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Expense amount must be greater than zero")
        }
        if (amount > java.math.BigDecimal("10000000")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Expense amount exceeds maximum allowable ceiling of ৳10,000,000")
        }
    }
}
