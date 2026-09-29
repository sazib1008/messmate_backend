package com.example.messmate_backend.repository

import com.example.messmate_backend.entity.*
import com.example.messmate_backend.model.enums.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.Optional

@Repository
interface UserRepository : JpaRepository<User, String> {
    fun findByEmail(email: String): Optional<User>
    fun existsByEmail(email: String): Boolean
}

@Repository
interface MessRepository : JpaRepository<Mess, String> {
    @Query("SELECT m FROM Mess m LEFT JOIN FETCH m.creator WHERE m.code = :code")
    fun findByCode(code: String): Optional<Mess>
    fun existsByCode(code: String): Boolean
}

@Repository
interface MessMembershipRepository : JpaRepository<MessMembership, String> {
    fun findByUserIdAndStatus(userId: String, status: MembershipStatus): Optional<MessMembership>
    fun findByMessIdAndUserId(messId: String, userId: String): Optional<MessMembership>
    fun findAllByMessId(messId: String): List<MessMembership>
    fun findAllByMessIdAndStatus(messId: String, status: MembershipStatus): List<MessMembership>
    fun findAllByMessIdAndRole(messId: String, role: UserRole): List<MessMembership>
    fun existsByUserIdAndStatus(userId: String, status: MembershipStatus): Boolean
}

@Repository
interface DiningConfigurationRepository : JpaRepository<DiningConfiguration, String> {
    fun findByMessId(messId: String): Optional<DiningConfiguration>
}

@Repository
interface MealSessionConfigRepository : JpaRepository<MealSessionConfig, String> {
    fun findAllByDiningConfigId(diningConfigId: String): List<MealSessionConfig>
    fun findByDiningConfigIdAndSession(diningConfigId: String, session: MealSession): Optional<MealSessionConfig>
}

@Repository
interface DiningCycleRepository : JpaRepository<DiningCycle, String> {
    fun findFirstByMessIdAndStatus(messId: String, status: com.example.messmate_backend.model.enums.CycleStatus): Optional<DiningCycle>
    fun findAllByMessIdOrderByCycleNumberDesc(messId: String): List<DiningCycle>
    fun findAllByStatus(status: com.example.messmate_backend.model.enums.CycleStatus): List<DiningCycle>
}

@Repository
interface CyclePausedDayRepository : JpaRepository<CyclePausedDay, String> {
    fun findAllByCycleId(cycleId: String): List<CyclePausedDay>
    fun findAllByCycleIdAndPausedDate(cycleId: String, pausedDate: LocalDate): List<CyclePausedDay>
    fun existsByCycleIdAndPausedDate(cycleId: String, pausedDate: LocalDate): Boolean
}

@Repository
interface DailyMealStatusRepository : JpaRepository<DailyMealStatus, String> {
    fun findByUserIdAndDateAndSession(userId: String, date: LocalDate, session: MealSession): Optional<DailyMealStatus>
    fun findAllByUserIdAndCycleId(userId: String, cycleId: String): List<DailyMealStatus>
    fun findAllByMessIdAndDateAndSession(messId: String, date: LocalDate, session: MealSession): List<DailyMealStatus>
    fun findAllByUserIdAndDateBetweenOrderByDateAsc(userId: String, startDate: LocalDate, endDate: LocalDate): List<DailyMealStatus>
}

@Repository
interface GuestMealRepository : JpaRepository<GuestMeal, String> {
    fun findAllByCycleIdAndHostUserId(cycleId: String, hostUserId: String): List<GuestMeal>
    fun findAllByMessIdAndDate(messId: String, date: LocalDate): List<GuestMeal>
}

@Repository
interface ExpenseRepository : JpaRepository<Expense, String> {
    fun findAllByCycleIdOrderByExpenseDateDesc(cycleId: String): List<Expense>
    fun findAllByMessIdOrderByExpenseDateDesc(messId: String): List<Expense>
}

@Repository
interface DepositRepository : JpaRepository<Deposit, String> {
    fun findAllByCycleIdAndUserId(cycleId: String, userId: String): List<Deposit>
    fun findAllByMessIdOrderByDepositDateDesc(messId: String): List<Deposit>
    fun findAllByMessIdAndStatusOrderByDepositDateDesc(messId: String, status: DepositStatus): List<Deposit>
    fun findAllByMessIdAndUserIdOrderByDepositDateDesc(messId: String, userId: String): List<Deposit>
    fun findAllByUserIdOrderByDepositDateDesc(userId: String): List<Deposit>
}

@Repository
interface MealCalculationRepository : JpaRepository<MealCalculation, String> {
    fun findByCycleId(cycleId: String): Optional<MealCalculation>
}

@Repository
interface StudentCycleSummaryRepository : JpaRepository<StudentCycleSummary, String> {
    fun findByCycleIdAndUserId(cycleId: String, userId: String): Optional<StudentCycleSummary>
    fun findAllByCycleId(cycleId: String): List<StudentCycleSummary>
}

@Repository
interface BalanceLedgerRepository : JpaRepository<BalanceLedger, String> {
    fun findAllByUserIdOrderByCreatedAtDesc(userId: String): List<BalanceLedger>
    fun findAllByMessIdOrderByCreatedAtDesc(messId: String): List<BalanceLedger>
    fun findFirstByUserIdOrderByCreatedAtDesc(userId: String): Optional<BalanceLedger>
}

@Repository
interface MenuRepository : JpaRepository<Menu, String> {
    fun findFirstByMessIdAndIsActiveTrue(messId: String): Optional<Menu>
}

@Repository
interface MenuItemRepository : JpaRepository<MenuItem, String> {
    fun findAllByMenuIdOrderByDayOfWeekAsc(menuId: String): List<MenuItem>
    fun findAllByMenuIdAndDayOfWeek(menuId: String, dayOfWeek: Int): List<MenuItem>
}

@Repository
interface ManagerTransferVoteRepository : JpaRepository<ManagerTransferVote, String> {
    fun findAllByMessIdOrderByCreatedAtDesc(messId: String): List<ManagerTransferVote>
    fun findFirstByMessIdAndStatus(messId: String, status: VoteStatus): Optional<ManagerTransferVote>
}

@Repository
interface TransferVoteBallotRepository : JpaRepository<TransferVoteBallot, String> {
    fun findAllByVoteId(voteId: String): List<TransferVoteBallot>
    fun findByVoteIdAndManagerId(voteId: String, managerId: String): Optional<TransferVoteBallot>
    fun existsByVoteIdAndManagerId(voteId: String, managerId: String): Boolean
}

@Repository
interface MealJoinRequestRepository : JpaRepository<MealJoinRequest, String> {
    fun findAllByMessIdAndStatusOrderByCreatedAtDesc(messId: String, status: JoinRequestStatus): List<MealJoinRequest>
    fun findAllByMessIdOrderByCreatedAtDesc(messId: String): List<MealJoinRequest>
    fun findFirstByUserIdAndStatus(userId: String, status: JoinRequestStatus): Optional<MealJoinRequest>
    fun existsByMessIdAndUserIdAndStatus(messId: String, userId: String, status: JoinRequestStatus): Boolean
    fun findAllByUserIdOrderByCreatedAtDesc(userId: String): List<MealJoinRequest>
}

@Repository
interface CycleConfigurationRepository : JpaRepository<CycleConfiguration, String> {
    fun findByCycleId(cycleId: String): Optional<CycleConfiguration>
    fun findFirstByMessIdOrderByCreatedAtDesc(messId: String): Optional<CycleConfiguration>
}

@Repository
interface DeviceTokenRepository : JpaRepository<com.example.messmate_backend.entity.DeviceToken, String> {
    fun findByToken(token: String): Optional<com.example.messmate_backend.entity.DeviceToken>
    fun findAllByUserId(userId: String): List<com.example.messmate_backend.entity.DeviceToken>
    fun deleteByToken(token: String)
}

typealias MealRepository = MessRepository
typealias MealMembershipRepository = MessMembershipRepository
typealias MealCycleRepository = DiningCycleRepository


