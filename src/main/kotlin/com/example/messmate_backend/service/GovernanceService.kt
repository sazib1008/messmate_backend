package com.example.messmate_backend.service

import com.example.messmate_backend.dto.BallotDetail
import com.example.messmate_backend.dto.CastVoteRequest
import com.example.messmate_backend.dto.InitiateTransferVoteRequest
import com.example.messmate_backend.dto.TransferVoteResponse
import com.example.messmate_backend.entity.ManagerTransferVote
import com.example.messmate_backend.entity.TransferVoteBallot
import com.example.messmate_backend.model.enums.MembershipStatus
import com.example.messmate_backend.model.enums.UserRole
import com.example.messmate_backend.model.enums.VoteDecision
import com.example.messmate_backend.model.enums.VoteStatus
import com.example.messmate_backend.repository.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

@Service
class GovernanceService(
    private val managerTransferVoteRepository: ManagerTransferVoteRepository,
    private val transferVoteBallotRepository: TransferVoteBallotRepository,
    private val messMembershipRepository: MessMembershipRepository,
    private val userRepository: UserRepository
) {

    private fun resolveUserId(identifier: String): String {
        return userRepository.findByEmail(identifier)
            .map { it.id }
            .orElse(identifier)
    }

    @Transactional
    fun initiateTransferVote(callerUserId: String, req: InitiateTransferVoteRequest): TransferVoteResponse {
        val actualCallerId = resolveUserId(callerUserId)
        val actualProposedUserId = resolveUserId(req.proposedUserId)

        val callerMembership = messMembershipRepository.findByMessIdAndUserId(req.messId, actualCallerId)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "Caller is not a member of this mess") }

        if (callerMembership.status != MembershipStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only active members can initiate governance votes")
        }

        val proposedMembership = messMembershipRepository.findByMessIdAndUserId(req.messId, actualProposedUserId)
            .orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "Proposed user is not a member of this mess") }

        if (proposedMembership.status != MembershipStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Proposed user is not an active member")
        }

        if (proposedMembership.role == UserRole.PRIMARY_MANAGER) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Proposed user is already the Primary Manager")
        }

        // Check for existing pending vote
        val existingPending = managerTransferVoteRepository.findFirstByMessIdAndStatus(req.messId, VoteStatus.PENDING)
        if (existingPending.isPresent) {
            val vote = existingPending.get()
            if (LocalDateTime.now().isBefore(vote.expiresAt)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "There is already an active transfer vote in progress (ID: ${vote.id})")
            } else {
                vote.status = VoteStatus.EXPIRED
                vote.resolvedAt = LocalDateTime.now()
                managerTransferVoteRepository.save(vote)
            }
        }

        val newVote = ManagerTransferVote(
            messId = req.messId,
            initiatedById = actualCallerId,
            proposedUserId = actualProposedUserId,
            reason = req.reason.trim(),
            status = VoteStatus.PENDING,
            expiresAt = LocalDateTime.now().plusDays(3)
        )
        val savedVote = managerTransferVoteRepository.save(newVote)

        // Automatically record initiator's APPROVE ballot
        val initialBallot = TransferVoteBallot(
            voteId = savedVote.id,
            managerId = actualCallerId,
            decision = VoteDecision.APPROVE,
            votedAt = LocalDateTime.now()
        )
        transferVoteBallotRepository.save(initialBallot)

        return buildResponse(savedVote)
    }

    @Transactional
    fun castVote(voteId: String, voterUserId: String, req: CastVoteRequest): TransferVoteResponse {
        val actualVoterId = resolveUserId(voterUserId)
        val vote = managerTransferVoteRepository.findById(voteId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer vote not found with ID: $voteId") }

        if (vote.status != VoteStatus.PENDING) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Vote is not active (Status: ${vote.status})")
        }

        if (LocalDateTime.now().isAfter(vote.expiresAt)) {
            vote.status = VoteStatus.EXPIRED
            vote.resolvedAt = LocalDateTime.now()
            managerTransferVoteRepository.save(vote)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Voting period has expired")
        }

        // Check voter active membership
        val voterMembership = messMembershipRepository.findByMessIdAndUserId(vote.messId, actualVoterId)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "Voter is not an active member of this mess") }

        if (voterMembership.status != MembershipStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only active members can vote")
        }

        if (transferVoteBallotRepository.existsByVoteIdAndManagerId(voteId, actualVoterId)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "User has already cast a ballot for this vote")
        }

        val ballot = TransferVoteBallot(
            voteId = voteId,
            managerId = actualVoterId,
            decision = req.decision,
            votedAt = LocalDateTime.now()
        )
        transferVoteBallotRepository.save(ballot)

        return buildResponse(vote)
    }

    @Transactional(readOnly = true)
    fun getVoteStatus(voteId: String): TransferVoteResponse {
        val vote = managerTransferVoteRepository.findById(voteId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer vote not found with ID: $voteId") }
        return buildResponse(vote)
    }

    @Transactional(readOnly = true)
    fun getActiveVote(messId: String): TransferVoteResponse? {
        val vote = managerTransferVoteRepository.findFirstByMessIdAndStatus(messId, VoteStatus.PENDING)
        return vote.map { buildResponse(it) }.orElse(null)
    }

    @Transactional
    fun executeTransferVote(voteId: String, executorUserId: String): TransferVoteResponse {
        val vote = managerTransferVoteRepository.findById(voteId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer vote not found with ID: $voteId") }

        if (vote.status != VoteStatus.PENDING) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Vote has already been resolved with status: ${vote.status}")
        }

        val activeMembers = messMembershipRepository.findAllByMessId(vote.messId)
            .filter { it.status == MembershipStatus.ACTIVE }

        val ballots = transferVoteBallotRepository.findAllByVoteId(voteId)
        val approveCount = ballots.count { it.decision == VoteDecision.APPROVE }
        val thresholdNeeded = (activeMembers.size / 2) + 1

        if (approveCount < thresholdNeeded) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Transfer vote does not meet majority threshold: needed $thresholdNeeded votes, got $approveCount"
            )
        }

        // Execute role swap within transaction:
        // 1. Demote current PRIMARY_MANAGER to STUDENT
        val currentPrimaryManagers = messMembershipRepository.findAllByMessIdAndRole(vote.messId, UserRole.PRIMARY_MANAGER)
        for (pm in currentPrimaryManagers) {
            pm.role = UserRole.STUDENT
            pm.updatedAt = LocalDateTime.now()
            messMembershipRepository.save(pm)
        }

        // 2. Promote proposed user to PRIMARY_MANAGER
        val proposedMembership = messMembershipRepository.findByMessIdAndUserId(vote.messId, vote.proposedUserId)
            .orElseThrow { ResponseStatusException(HttpStatus.BAD_REQUEST, "Proposed user membership not found") }
        proposedMembership.role = UserRole.PRIMARY_MANAGER
        proposedMembership.updatedAt = LocalDateTime.now()
        messMembershipRepository.save(proposedMembership)

        // 3. Mark vote as PASSED
        vote.status = VoteStatus.PASSED
        vote.resolvedAt = LocalDateTime.now()
        val updatedVote = managerTransferVoteRepository.save(vote)

        return buildResponse(updatedVote)
    }

    private fun buildResponse(vote: ManagerTransferVote): TransferVoteResponse {
        val initiatedBy = userRepository.findById(vote.initiatedById).orElse(null)
        val proposedUser = userRepository.findById(vote.proposedUserId).orElse(null)

        val activeMembers = messMembershipRepository.findAllByMessId(vote.messId)
            .filter { it.status == MembershipStatus.ACTIVE }

        val ballots = transferVoteBallotRepository.findAllByVoteId(vote.id)
        val voterIds = ballots.map { it.managerId }
        val voterMap = userRepository.findAllById(voterIds).associateBy { it.id }

        val approveCount = ballots.count { it.decision == VoteDecision.APPROVE }
        val rejectCount = ballots.count { it.decision == VoteDecision.REJECT }
        val thresholdNeeded = (activeMembers.size / 2) + 1
        val hasPassed = approveCount >= thresholdNeeded
        val canExecute = vote.status == VoteStatus.PENDING && hasPassed

        val ballotDetails = ballots.map {
            BallotDetail(
                ballotId = it.id,
                voterId = it.managerId,
                voterName = voterMap[it.managerId]?.fullName ?: "Member",
                decision = it.decision,
                votedAt = it.votedAt
            )
        }

        return TransferVoteResponse(
            id = vote.id,
            messId = vote.messId,
            initiatedById = vote.initiatedById,
            initiatedByName = initiatedBy?.fullName ?: "Unknown",
            proposedUserId = vote.proposedUserId,
            proposedUserName = proposedUser?.fullName ?: "Unknown",
            reason = vote.reason,
            status = vote.status,
            expiresAt = vote.expiresAt,
            resolvedAt = vote.resolvedAt,
            createdAt = vote.createdAt,
            totalActiveMembers = activeMembers.size,
            approveCount = approveCount,
            rejectCount = rejectCount,
            thresholdNeeded = thresholdNeeded,
            hasPassed = hasPassed,
            canExecute = canExecute,
            ballots = ballotDetails
        )
    }
}
