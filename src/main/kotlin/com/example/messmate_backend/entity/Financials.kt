package com.example.messmate_backend.entity

import com.example.messmate_backend.model.enums.DepositStatus
import com.example.messmate_backend.model.enums.ExpenseCategory
import com.example.messmate_backend.model.enums.LedgerEntryType
import com.example.messmate_backend.model.enums.PaymentMethod
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "expenses")
data class Expense(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "\"cycleId\"", nullable = false)
    var cycleId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"cycleId\"", insertable = false, updatable = false)
    var cycle: DiningCycle? = null,

    @Column(name = "\"messId\"", nullable = false)
    var messId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"messId\"", insertable = false, updatable = false)
    var mess: Mess? = null,

    @Column(name = "\"recordedById\"", nullable = false)
    var recordedById: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"recordedById\"", insertable = false, updatable = false)
    var recordedBy: User? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, columnDefinition = "varchar(50)")
    var category: ExpenseCategory = ExpenseCategory.MEAL_VARIABLE,

    @Column(name = "title", nullable = false)
    var title: String = "",

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    var amount: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"expenseDate\"", nullable = false)
    var expenseDate: LocalDate = LocalDate.now(),

    @Column(name = "\"receiptUrl\"")
    var receiptUrl: String? = null,

    @Column(name = "notes")
    var notes: String? = null,

    @Column(name = "\"targetMemberId\"")
    var targetMemberId: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"targetMemberId\"", insertable = false, updatable = false)
    var targetMember: User? = null,

    @Column(name = "\"participantIds\"")
    var participantIdsStr: String? = null,

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"updatedAt\"", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
) {
    var participantIds: List<String>
        get() = participantIdsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        set(value) {
            participantIdsStr = if (value.isEmpty()) null else value.joinToString(",")
        }
}

@Entity
@Table(name = "deposits")
data class Deposit(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "\"cycleId\"", nullable = false)
    var cycleId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"cycleId\"", insertable = false, updatable = false)
    var cycle: DiningCycle? = null,

    @Column(name = "\"messId\"", nullable = false)
    var messId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"messId\"", insertable = false, updatable = false)
    var mess: Mess? = null,

    @Column(name = "\"userId\"", nullable = false)
    var userId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"userId\"", insertable = false, updatable = false)
    var user: User? = null,

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    var amount: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"depositDate\"", nullable = false)
    var depositDate: LocalDateTime = LocalDateTime.now(),

    @Enumerated(EnumType.STRING)
    @Column(name = "\"paymentMethod\"", nullable = false, columnDefinition = "varchar(50)")
    var paymentMethod: PaymentMethod = PaymentMethod.CASH,

    @Column(name = "\"transactionRef\"")
    var transactionRef: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "varchar(50)")
    var status: DepositStatus = DepositStatus.APPROVED,

    @Column(name = "\"approvedById\"")
    var approvedById: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"approvedById\"", insertable = false, updatable = false)
    var approvedBy: User? = null,

    @Column(name = "notes")
    var notes: String? = null,

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"updatedAt\"", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

@Entity
@Table(name = "meal_calculations")
data class MealCalculation(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "\"cycleId\"", nullable = false, unique = true)
    var cycleId: String = "",

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"cycleId\"", insertable = false, updatable = false)
    var cycle: DiningCycle? = null,

    @Column(name = "\"messId\"", nullable = false)
    var messId: String = "",

    @Column(name = "\"totalExpenses\"", nullable = false, precision = 12, scale = 2)
    var totalExpenses: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"mealVariableExpenses\"", precision = 12, scale = 2)
    var mealVariableExpenses: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"fixedOverheadExpenses\"", precision = 12, scale = 2)
    var fixedOverheadExpenses: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"individualDirectExpenses\"", precision = 12, scale = 2)
    var individualDirectExpenses: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"adHocSpecialExpenses\"", precision = 12, scale = 2)
    var adHocSpecialExpenses: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"totalMemberUnits\"", nullable = false, precision = 10, scale = 2)
    var totalMemberUnits: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"totalGuestUnits\"", nullable = false, precision = 10, scale = 2)
    var totalGuestUnits: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"totalCountedUnits\"", nullable = false, precision = 10, scale = 2)
    var totalCountedUnits: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"mealRate\"", nullable = false, precision = 10, scale = 4)
    var mealRate: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"calculatedAt\"", nullable = false)
    var calculatedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"isFinal\"", nullable = false)
    var isFinal: Boolean = false,

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"updatedAt\"", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

@Entity
@Table(name = "student_cycle_summaries")
data class StudentCycleSummary(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "\"cycleId\"", nullable = false)
    var cycleId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"cycleId\"", insertable = false, updatable = false)
    var cycle: DiningCycle? = null,

    @Column(name = "\"userId\"", nullable = false)
    var userId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"userId\"", insertable = false, updatable = false)
    var user: User? = null,

    @Column(name = "\"activeDays\"", nullable = false)
    var activeDays: Int = 0,

    @Column(name = "\"isProrated\"", nullable = false)
    var isProrated: Boolean = false,

    @Column(name = "\"proratedJoinDate\"")
    var proratedJoinDate: LocalDate? = null,

    @Column(name = "\"proratedLeaveDate\"")
    var proratedLeaveDate: LocalDate? = null,

    @Column(name = "\"totalMemberUnits\"", nullable = false, precision = 8, scale = 2)
    var totalMemberUnits: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"totalGuestUnits\"", nullable = false, precision = 8, scale = 2)
    var totalGuestUnits: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"totalMealCost\"", nullable = false, precision = 10, scale = 2)
    var totalMealCost: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"totalGuestCost\"", nullable = false, precision = 10, scale = 2)
    var totalGuestCost: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"fixedOverheadCost\"", precision = 10, scale = 2)
    var fixedOverheadCost: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"individualDirectCost\"", precision = 10, scale = 2)
    var individualDirectCost: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"adHocSpecialCost\"", precision = 10, scale = 2)
    var adHocSpecialCost: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"totalDeposits\"", nullable = false, precision = 10, scale = 2)
    var totalDeposits: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"netBalance\"", nullable = false, precision = 10, scale = 2)
    var netBalance: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"hasNegativeBalance\"", nullable = false)
    var hasNegativeBalance: Boolean = false,

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "\"updatedAt\"", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

@Entity
@Table(name = "balance_ledgers")
data class BalanceLedger(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString().replace("-", "").take(25),

    @Column(name = "\"messId\"", nullable = false)
    var messId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"messId\"", insertable = false, updatable = false)
    var mess: Mess? = null,

    @Column(name = "\"userId\"", nullable = false)
    var userId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"userId\"", insertable = false, updatable = false)
    var user: User? = null,

    @Column(name = "\"cycleId\"")
    var cycleId: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"cycleId\"", insertable = false, updatable = false)
    var cycle: DiningCycle? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "\"entryType\"", nullable = false, columnDefinition = "varchar(50)")
    var entryType: LedgerEntryType = LedgerEntryType.DEPOSIT,

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    var amount: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"balanceAfter\"", nullable = false, precision = 10, scale = 2)
    var balanceAfter: BigDecimal = BigDecimal.ZERO,

    @Column(name = "\"referenceId\"")
    var referenceId: String? = null,

    @Column(name = "description", nullable = false)
    var description: String = "",

    @Column(name = "\"createdAt\"", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
)
