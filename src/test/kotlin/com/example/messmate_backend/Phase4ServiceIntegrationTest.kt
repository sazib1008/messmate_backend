package com.example.messmate_backend

import com.example.messmate_backend.dto.CastVoteRequest
import com.example.messmate_backend.dto.CreateDepositRequest
import com.example.messmate_backend.dto.InitiateTransferVoteRequest
import com.example.messmate_backend.dto.ReviewDepositRequest
import com.example.messmate_backend.model.enums.*
import com.example.messmate_backend.repository.*
import com.example.messmate_backend.service.ChefService
import com.example.messmate_backend.service.DepositService
import com.example.messmate_backend.service.GovernanceService
import com.example.messmate_backend.service.MealExportService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

@SpringBootTest
class Phase4ServiceIntegrationTest {

    @Autowired
    private lateinit var depositService: DepositService

    @Autowired
    private lateinit var chefService: ChefService

    @Autowired
    private lateinit var mealExportService: MealExportService

    @Autowired
    private lateinit var governanceService: GovernanceService

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var messRepository: MessRepository

    @Autowired
    private lateinit var messMembershipRepository: MessMembershipRepository

    @Autowired
    private lateinit var balanceLedgerRepository: BalanceLedgerRepository

    @Autowired
    private lateinit var depositRepository: DepositRepository

    @Autowired
    private lateinit var managerTransferVoteRepository: ManagerTransferVoteRepository

    @Test
    @Transactional
    fun testDepositSubmissionAndApprovalFlow() {
        // Find Greenfield Hall mess and student 1 (Arafat)
        val student = userRepository.findByEmail("s1.arafat@messmate.com").orElseThrow()
        val manager = userRepository.findByEmail("primary.manager@messmate.com").orElseThrow()

        // 1. Student submits deposit
        val req = CreateDepositRequest(
            amount = BigDecimal("1500.00"),
            paymentMethod = PaymentMethod.BKASH,
            transactionRef = "TRXTEST9988",
            notes = "September 1st advance"
        )
        val created = depositService.submitDeposit(student.id, req)
        assertEquals(DepositStatus.PENDING, created.status)
        assertEquals(BigDecimal("1500.00"), created.amount)
        assertEquals("TRXTEST9988", created.transactionRef)

        // 2. Manager reviews and approves deposit
        val reviewReq = ReviewDepositRequest(approved = true)
        val approved = depositService.reviewDeposit(created.id, manager.id, reviewReq)
        assertEquals(DepositStatus.APPROVED, approved.status)
        assertEquals(manager.id, approved.approvedById)

        // 3. Verify balance ledger entry was created
        val latestLedger = balanceLedgerRepository.findFirstByUserIdOrderByCreatedAtDesc(student.id).orElseThrow()
        assertEquals(LedgerEntryType.DEPOSIT, latestLedger.entryType)
        assertEquals(BigDecimal("1500.00"), latestLedger.amount)
        assertEquals(created.id, latestLedger.referenceId)
    }

    @Test
    @Transactional
    fun testChefHeadcountQuery() {
        val mess = messRepository.findByCode("GFH-2026").orElseThrow()
        // Day 14 date from seed: 2026-09-05
        val testDate = LocalDate.of(2026, 9, 5)

        val headcount = chefService.getDailyHeadcount(mess.id, testDate)
        assertNotNull(headcount)
        assertEquals(mess.id, headcount.messId)
        assertEquals(3, headcount.sessions.size)

        for (session in headcount.sessions) {
            assertTrue(session.totalHeadcount >= 0)
            assertEquals(session.studentOnCount + session.guestMealCount, session.totalHeadcount)
        }
    }

    @Test
    @Transactional
    fun testMealExportService() {
        val mess = messRepository.findByCode("GFH-2026").orElseThrow()
        val start = LocalDate.of(2026, 8, 23)
        val end = LocalDate.of(2026, 8, 25)

        val csv = mealExportService.exportToCsv(mess.id, start, end)
        assertNotNull(csv)
        assertTrue(csv.startsWith("Date,Session,Student Name,Email,Status,Guest Count,Notes"))

        val jsonRows = mealExportService.getExportRows(mess.id, start, end)
        assertFalse(jsonRows.isEmpty())
    }

    @Test
    @Transactional
    fun testManagerTransferGovernanceEndToEnd() {
        val mess = messRepository.findByCode("GFH-2026").orElseThrow()
        val currentPrimary = userRepository.findByEmail("primary.manager@messmate.com").orElseThrow()
        val proposedCandidate = userRepository.findByEmail("s2.bilal@messmate.com").orElseThrow()
        val voter1 = userRepository.findByEmail("s1.arafat@messmate.com").orElseThrow()
        val voter3 = userRepository.findByEmail("s3.farhan@messmate.com").orElseThrow()
        val voter4 = userRepository.findByEmail("s4.hasan@messmate.com").orElseThrow()
        val voter5 = userRepository.findByEmail("s5.imran@messmate.com").orElseThrow()
        val voter6 = userRepository.findByEmail("s6.kamal@messmate.com").orElseThrow()
        val voter7 = userRepository.findByEmail("s7.mehedi@messmate.com").orElseThrow()

        // Clean up any prior pending vote
        val priorPending = managerTransferVoteRepository.findFirstByMessIdAndStatus(mess.id, VoteStatus.PENDING)
        if (priorPending.isPresent) {
            val v = priorPending.get()
            v.status = VoteStatus.EXPIRED
            managerTransferVoteRepository.save(v)
        }

        // 1. Primary Manager initiates transfer vote proposing student2
        val initReq = InitiateTransferVoteRequest(
            messId = mess.id,
            proposedUserId = proposedCandidate.id,
            reason = "Passing leadership to upcoming batch representative"
        )
        val vote = governanceService.initiateTransferVote(currentPrimary.id, initReq)
        assertEquals(VoteStatus.PENDING, vote.status)
        assertEquals(1, vote.approveCount) // Initiator's ballot

        // 2. Cast APPROVE votes from active members until threshold is crossed
        val allMembers = messMembershipRepository.findAllByMessId(mess.id)
            .filter { it.status == MembershipStatus.ACTIVE && it.userId != currentPrimary.id }

        for (member in allMembers) {
            val currStatus = governanceService.getVoteStatus(vote.id)
            if (currStatus.hasPassed) break
            governanceService.castVote(vote.id, member.userId, CastVoteRequest(decision = VoteDecision.APPROVE))
        }

        val statusAfterVotes = governanceService.getVoteStatus(vote.id)
        assertTrue(statusAfterVotes.hasPassed, "Vote should have passed majority threshold (approve: ${statusAfterVotes.approveCount}, needed: ${statusAfterVotes.thresholdNeeded})")
        assertTrue(statusAfterVotes.canExecute, "Vote should be executable")

        // 3. Execute transfer vote
        val executed = governanceService.executeTransferVote(vote.id, currentPrimary.id)
        assertEquals(VoteStatus.PASSED, executed.status)
        assertNotNull(executed.resolvedAt)

        // 4. Verify roles in membership repository
        val oldPmMembership = messMembershipRepository.findByMessIdAndUserId(mess.id, currentPrimary.id).orElseThrow()
        val newPmMembership = messMembershipRepository.findByMessIdAndUserId(mess.id, proposedCandidate.id).orElseThrow()

        assertEquals(UserRole.STUDENT, oldPmMembership.role, "Old PM should be demoted to STUDENT")
        assertEquals(UserRole.PRIMARY_MANAGER, newPmMembership.role, "Proposed candidate should be promoted to PRIMARY_MANAGER")
    }
}
