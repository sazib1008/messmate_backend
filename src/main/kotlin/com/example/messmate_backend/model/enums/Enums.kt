package com.example.messmate_backend.model.enums

enum class UserRole {
    OWNER,
    MANAGER,
    MEMBER,
    CHEF,
    PRIMARY_MANAGER,
    STUDENT;

    val isOwnerOrManager: Boolean
        get() = this == OWNER || this == PRIMARY_MANAGER || this == MANAGER

    val isOwner: Boolean
        get() = this == OWNER || this == PRIMARY_MANAGER

    val canonicalRole: UserRole
        get() = when (this) {
            PRIMARY_MANAGER -> OWNER
            STUDENT -> MEMBER
            else -> this
        }
}

enum class JoinRequestStatus {
    PENDING,
    APPROVED,
    REJECTED
}

enum class MembershipStatus {
    ACTIVE,
    INACTIVE,
    SUSPENDED,
    LEFT
}

enum class MealSession {
    BREAKFAST,
    LUNCH,
    DINNER
}

enum class MealStatus {
    ON,
    OFF
}

enum class CycleStatus {
    ACTIVE,
    EXPIRING,
    FINALIZING,
    COMPLETED,
    CANCELLED
}

enum class ExpenseCategory {
    MEAL_VARIABLE,
    FIXED_OVERHEAD,
    INDIVIDUAL_DIRECT,
    AD_HOC_SPECIAL,

    // Legacy aliases mapped to the 4 canonical tiers:
    GROCERY,
    VEGETABLE,
    GAS,
    MEAT_FISH,
    UTILITY,
    CHEF_SALARY,
    SPICE,
    OTHER;

    val canonicalCategory: ExpenseCategory
        get() = when (this) {
            MEAL_VARIABLE, GROCERY, VEGETABLE, MEAT_FISH, SPICE -> MEAL_VARIABLE
            FIXED_OVERHEAD, GAS, UTILITY, CHEF_SALARY -> FIXED_OVERHEAD
            INDIVIDUAL_DIRECT -> INDIVIDUAL_DIRECT
            AD_HOC_SPECIAL, OTHER -> AD_HOC_SPECIAL
        }

    companion object {
        @JvmStatic
        @com.fasterxml.jackson.annotation.JsonCreator
        fun fromString(value: String?): ExpenseCategory {
            if (value.isNullOrBlank()) return MEAL_VARIABLE
            val normalized = value.trim().uppercase().replace(" ", "_").replace("-", "_")
            return entries.firstOrNull { it.name == normalized }?.canonicalCategory
                ?: when {
                    normalized.contains("FIXED") || normalized.contains("OVERHEAD") || normalized.contains("SALARY") || normalized.contains("GAS") || normalized.contains("UTIL") -> FIXED_OVERHEAD
                    normalized.contains("INDIVIDUAL") || normalized.contains("DIRECT") || normalized.contains("RENT") || normalized.contains("PENALTY") -> INDIVIDUAL_DIRECT
                    normalized.contains("AD_HOC") || normalized.contains("SPECIAL") || normalized.contains("FEAST") || normalized.contains("EVENT") -> AD_HOC_SPECIAL
                    else -> MEAL_VARIABLE
                }
        }
    }
}

enum class PaymentMethod {
    CASH,
    BKASH,
    NAGAD,
    BANK_TRANSFER,
    OTHER
}

enum class DepositStatus {
    PENDING,
    APPROVED,
    REJECTED
}

enum class LedgerEntryType {
    DEPOSIT,
    MEAL_CHARGE,
    GUEST_MEAL_CHARGE,
    ADJUSTMENT,
    REFUND,
    REFUND_DUE,
    SURPLUS_FORFEITED
}

enum class VoteStatus {
    PENDING,
    PASSED,
    REJECTED,
    EXPIRED
}

enum class VoteDecision {
    APPROVE,
    REJECT
}
