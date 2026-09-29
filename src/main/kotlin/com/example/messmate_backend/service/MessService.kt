package com.example.messmate_backend.service

import com.example.messmate_backend.dto.*
import com.example.messmate_backend.entity.*
import com.example.messmate_backend.model.enums.*
import com.example.messmate_backend.repository.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.security.SecureRandom
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@Service
class MessService(
    private val messRepository: MessRepository,
    private val membershipRepository: MessMembershipRepository,
    private val userRepository: UserRepository,
    private val diningConfigRepository: DiningConfigurationRepository,
    private val sessionConfigRepository: MealSessionConfigRepository,
    private val diningCycleRepository: DiningCycleRepository,
    private val cycleConfigRepository: CycleConfigurationRepository,
    private val joinRequestRepository: MealJoinRequestRepository,
    private val menuService: MenuService,
    private val notificationService: NotificationService
) {

    private val dhakaZone = ZoneId.of("Asia/Dhaka")
    private val random = SecureRandom()
    private val codeAlphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    private fun generateUniqueMealCode(): String {
        for (i in 0..20) {
            val suffix = (1..4).map { codeAlphabet[random.nextInt(codeAlphabet.length)] }.joinToString("")
            val candidate = "MM$suffix"
            if (!messRepository.existsByCode(candidate)) {
                return candidate
            }
        }
        return "MM" + System.currentTimeMillis().toString().takeLast(4)
    }

    @Transactional
    fun createMess(userEmail: String, request: CreateMealRequest): MealDto {
        val user = userRepository.findByEmail(userEmail)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        // Domain Rule: Single active meal membership per student
        if (membershipRepository.existsByUserIdAndStatus(user.id, MembershipStatus.ACTIVE)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "You already have an active Meal membership. You cannot create a new Meal while active in another."
            )
        }

        val code = generateUniqueMealCode()

        val meal = Mess(
            name = request.name.trim(),
            code = code,
            address = request.address?.trim(),
            createdById = user.id
        )
        val savedMeal = messRepository.save(meal)

        // 1. Assign creator as MEAL_OWNER (UserRole.OWNER)
        val membership = MessMembership(
            messId = savedMeal.id,
            userId = user.id,
            role = UserRole.OWNER,
            status = MembershipStatus.ACTIVE
        )
        membershipRepository.save(membership)

        // 2. Initialize default dining configuration
        val diningConfig = DiningConfiguration(
            messId = savedMeal.id,
            defaultCarryForward = request.defaultCarryForward,
            defaultBreakfastOn = request.breakfastEnabled,
            defaultLunchOn = request.lunchEnabled,
            defaultDinnerOn = request.dinnerEnabled,
            currency = request.currency
        )
        val savedConfig = diningConfigRepository.save(diningConfig)

        // 3. Initialize default session configs
        sessionConfigRepository.saveAll(
            listOf(
                MealSessionConfig(
                    diningConfigId = savedConfig.id,
                    session = MealSession.BREAKFAST,
                    unitValue = request.breakfastMultiplier,
                    cutoffTime = request.breakfastCutoff,
                    isEnabled = request.breakfastEnabled,
                    servingStartTime = request.breakfastServingStartTime ?: "07:30",
                    servingEndTime = request.breakfastServingEndTime ?: "09:30"
                ),
                MealSessionConfig(
                    diningConfigId = savedConfig.id,
                    session = MealSession.LUNCH,
                    unitValue = request.lunchMultiplier,
                    cutoffTime = request.lunchCutoff,
                    isEnabled = request.lunchEnabled,
                    servingStartTime = request.lunchServingStartTime ?: "13:00",
                    servingEndTime = request.lunchServingEndTime ?: "14:30"
                ),
                MealSessionConfig(
                    diningConfigId = savedConfig.id,
                    session = MealSession.DINNER,
                    unitValue = request.dinnerMultiplier,
                    cutoffTime = request.dinnerCutoff,
                    isEnabled = request.dinnerEnabled,
                    servingStartTime = request.dinnerServingStartTime ?: "20:30",
                    servingEndTime = request.dinnerServingEndTime ?: "22:00"
                )
            )
        )

        // 4. Initialize Initial Meal Cycle (Cycle #1)
        val today = LocalDate.now(dhakaZone)
        val cycle = DiningCycle(
            messId = savedMeal.id,
            cycleNumber = 1,
            startDate = today,
            targetActiveDays = request.targetActiveDays,
            countedActiveDays = 0,
            scheduledEndDate = today.plusDays(request.targetActiveDays.toLong()),
            status = CycleStatus.ACTIVE
        )
        val savedCycle = diningCycleRepository.save(cycle)

        // 5. Snapshot Cycle Configuration for Cycle #1
        val cycleConfig = CycleConfiguration(
            cycleId = savedCycle.id,
            messId = savedMeal.id,
            currency = request.currency,
            defaultCarryForward = request.defaultCarryForward,
            breakfastEnabled = request.breakfastEnabled,
            lunchEnabled = request.lunchEnabled,
            dinnerEnabled = request.dinnerEnabled,
            breakfastCutoff = request.breakfastCutoff,
            lunchCutoff = request.lunchCutoff,
            dinnerCutoff = request.dinnerCutoff,
            breakfastMultiplier = request.breakfastMultiplier,
            lunchMultiplier = request.lunchMultiplier,
            dinnerMultiplier = request.dinnerMultiplier,
            combinedSessionRule = request.combinedSessionRule,
            targetActiveDays = request.targetActiveDays
        )
        cycleConfigRepository.save(cycleConfig)

        return MealDto(
            id = savedMeal.id,
            name = savedMeal.name,
            code = savedMeal.code,
            address = savedMeal.address,
            createdById = savedMeal.createdById,
            memberCount = 1,
            currentCycleNumber = 1,
            currentCycleStatus = CycleStatus.ACTIVE.name
        )
    }

    @Transactional(readOnly = true)
    fun verifyMealCode(code: String): MealCodeVerificationResponse {
        val cleanCode = code.trim().uppercase()
        val meal = messRepository.findByCode(cleanCode)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No Meal found with code: $cleanCode") }

        val activeMembers = membershipRepository.findAllByMessId(meal.id)
            .filter { it.status == MembershipStatus.ACTIVE }

        val latestCycle = diningCycleRepository.findAllByMessIdOrderByCycleNumberDesc(meal.id).firstOrNull()

        return MealCodeVerificationResponse(
            mealId = meal.id,
            name = meal.name,
            code = meal.code,
            address = meal.address,
            ownerName = userRepository.findById(meal.createdById).map { it.fullName }.orElse("Meal Owner"),
            memberCount = activeMembers.size,
            currentCycleNumber = latestCycle?.cycleNumber ?: 1,
            currentCycleStatus = latestCycle?.status?.name ?: "ACTIVE"
        )
    }

    @Transactional
    fun submitJoinRequest(userEmail: String, request: SubmitJoinRequest): MealJoinRequestDto {
        val user = userRepository.findByEmail(userEmail)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        // Domain Rule: Single active meal membership per student
        if (membershipRepository.existsByUserIdAndStatus(user.id, MembershipStatus.ACTIVE)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "A student can belong to only ONE active Meal at a time. You already have an active membership."
            )
        }

        val cleanCode = request.code.trim().uppercase()
        val meal = messRepository.findByCode(cleanCode)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid Meal join code: $cleanCode") }

        // Check if student already has a pending join request for this meal
        if (joinRequestRepository.existsByMessIdAndUserIdAndStatus(meal.id, user.id, JoinRequestStatus.PENDING)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "You already have a pending join request for ${meal.name}. Please wait for owner approval."
            )
        }

        if (!request.roomNumber.isNullOrBlank()) {
            user.roomNumber = request.roomNumber.trim()
            userRepository.save(user)
        }

        // Create new pending join request
        val joinRequest = MealJoinRequest(
            messId = meal.id,
            userId = user.id,
            status = JoinRequestStatus.PENDING,
            requestNotes = request.notes?.trim()
        )
        joinRequest.user = user
        joinRequest.mess = meal
        val saved = joinRequestRepository.save(joinRequest)

        try {
            val managerMemberships = membershipRepository.findAllByMessIdAndStatus(meal.id, MembershipStatus.ACTIVE)
                .filter { it.role.isOwnerOrManager }
            val managerUserIds = managerMemberships.map { it.userId }

            if (managerUserIds.isNotEmpty()) {
                notificationService.sendPushToUsers(
                    userIds = managerUserIds,
                    title = "New Join Request",
                    body = "${user.fullName} requested to join ${meal.name}.",
                    data = mapOf(
                        "screen" to "join_requests",
                        "type" to "NEW_JOIN_REQUEST",
                        "messId" to meal.id,
                        "requestId" to saved.id
                    )
                )
            }
        } catch (e: Exception) {
            // Guard: notifications must never fail the join request creation
        }

        return mapJoinRequestDto(saved)
    }

    @Transactional(readOnly = true)
    fun getMyPendingJoinRequest(userEmail: String): MealJoinRequestDto? {
        val user = userRepository.findByEmail(userEmail)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        return joinRequestRepository.findFirstByUserIdAndStatus(user.id, JoinRequestStatus.PENDING)
            .map { mapJoinRequestDto(it) }
            .orElse(null)
    }

    @Transactional
    fun cancelMyJoinRequest(userEmail: String, requestId: String): Map<String, String> {
        val user = userRepository.findByEmail(userEmail)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        val request = joinRequestRepository.findById(requestId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Join request not found") }

        if (request.userId != user.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot cancel another student's request")
        }

        if (request.status != JoinRequestStatus.PENDING) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Only pending requests can be cancelled")
        }

        joinRequestRepository.delete(request)
        return mapOf("message" to "Join request cancelled successfully")
    }

    @Transactional(readOnly = true)
    fun listJoinRequests(mealId: String, callerEmail: String): List<MealJoinRequestDto> {
        val caller = userRepository.findByEmail(callerEmail)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Caller not found") }

        val membership = membershipRepository.findByMessIdAndUserId(mealId, caller.id)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "You do not belong to this Meal") }

        if (!membership.role.isOwnerOrManager) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only Meal Owner or Managers can review join requests")
        }

        val requests = joinRequestRepository.findAllByMessIdOrderByCreatedAtDesc(mealId)
        return requests.map { mapJoinRequestDto(it) }
    }

    @Transactional
    fun reviewJoinRequest(mealId: String, requestId: String, review: ReviewJoinRequest, callerEmail: String): MealJoinRequestDto {
        val caller = userRepository.findByEmail(callerEmail)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Caller not found") }

        val membership = membershipRepository.findByMessIdAndUserId(mealId, caller.id)
            .orElseThrow { ResponseStatusException(HttpStatus.FORBIDDEN, "You do not belong to this Meal") }

        if (!membership.role.isOwnerOrManager) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only Meal Owner or Managers can review join requests")
        }

        val joinReq = joinRequestRepository.findById(requestId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Join request not found with ID: $requestId") }

        if (joinReq.messId != mealId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Join request does not belong to this Meal")
        }

        if (joinReq.status != JoinRequestStatus.PENDING) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Join request has already been processed with status: ${joinReq.status}")
        }

        joinReq.reviewedById = caller.id
        joinReq.reviewedAt = LocalDateTime.now()

        if (review.approved) {
            // Check if applicant is already active in another meal
            if (membershipRepository.existsByUserIdAndStatus(joinReq.userId, MembershipStatus.ACTIVE)) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Student already joined another Meal while this request was pending."
                )
            }

            joinReq.status = JoinRequestStatus.APPROVED

            // Check if existing membership record in this meal
            val existingMembership = membershipRepository.findByMessIdAndUserId(mealId, joinReq.userId)
            if (existingMembership.isPresent) {
                val m = existingMembership.get()
                m.status = MembershipStatus.ACTIVE
                m.role = UserRole.MEMBER
                m.joinDate = LocalDateTime.now()
                m.leaveDate = null
                membershipRepository.save(m)
            } else {
                val newMembership = MessMembership(
                    messId = mealId,
                    userId = joinReq.userId,
                    role = UserRole.MEMBER,
                    status = MembershipStatus.ACTIVE
                )
                membershipRepository.save(newMembership)
            }
        } else {
            joinReq.status = JoinRequestStatus.REJECTED
            if (!review.notes.isNullOrBlank()) {
                joinReq.requestNotes = listOfNotNull(joinReq.requestNotes, "Rejection: ${review.notes}").joinToString(" | ")
            }
        }

        val updated = joinRequestRepository.save(joinReq)

        try {
            val isApproved = updated.status == JoinRequestStatus.APPROVED
            val title = if (isApproved) "Join Request Approved" else "Join Request Declined"
            val body = if (isApproved)
                "Your request to join has been approved! Welcome to the mess."
            else
                "Your request to join was declined. ${review.notes ?: ""}".trim()
            val eventType = if (isApproved) "JOIN_REQUEST_APPROVED" else "JOIN_REQUEST_REJECTED"

            notificationService.sendPushToUser(
                userId = updated.userId,
                title = title,
                body = body,
                data = mapOf(
                    "screen" to (if (isApproved) "student_home" else "join_mess"),
                    "type" to eventType,
                    "messId" to mealId,
                    "requestId" to updated.id
                )
            )
        } catch (e: Exception) {
            // Guard: notifications must never fail the review
        }

        return mapJoinRequestDto(updated)
    }

    @Transactional
    fun joinMess(userEmail: String, request: JoinMessRequest): MembershipDto {
        // Direct join fallback (if auto-accept is enabled or legacy direct join)
        val user = userRepository.findByEmail(userEmail)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        if (membershipRepository.existsByUserIdAndStatus(user.id, MembershipStatus.ACTIVE)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "A student can belong to only ONE active Meal at a time. You already have an active membership."
            )
        }

        val meal = messRepository.findByCode(request.code.trim().uppercase())
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid Meal invite code") }

        val membership = MessMembership(
            messId = meal.id,
            userId = user.id,
            role = UserRole.MEMBER,
            status = MembershipStatus.ACTIVE
        )
        val saved = membershipRepository.save(membership)

        return MembershipDto(
            id = saved.id,
            mealId = meal.id,
            mealName = meal.name,
            role = saved.role,
            status = saved.status,
            joinDate = saved.joinDate
        )
    }

    @Transactional(readOnly = true)
    fun getMealDetails(mealId: String): MealDto {
        val meal = messRepository.findById(mealId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Meal not found") }

        val activeMembers = membershipRepository.findAllByMessId(mealId)
            .filter { it.status == MembershipStatus.ACTIVE }

        val latestCycle = diningCycleRepository.findAllByMessIdOrderByCycleNumberDesc(mealId).firstOrNull()

        return MealDto(
            id = meal.id,
            name = meal.name,
            code = meal.code,
            address = meal.address,
            createdById = meal.createdById,
            memberCount = activeMembers.size,
            currentCycleNumber = latestCycle?.cycleNumber ?: 1,
            currentCycleStatus = latestCycle?.status?.name ?: "ACTIVE"
        )
    }

    @Transactional(readOnly = true)
    fun getMembers(messId: String): List<Map<String, Any?>> {
        val memberships = membershipRepository.findAllByMessId(messId)
        return memberships.map { m ->
            mapOf(
                "id" to m.id,
                "userId" to m.userId,
                "fullName" to m.user?.fullName,
                "email" to m.user?.email,
                "phone" to m.user?.phone,
                "studentId" to m.user?.studentId,
                "department" to m.user?.department,
                "university" to m.user?.university,
                "roomNumber" to m.user?.roomNumber,
                "role" to m.role.name,
                "canonicalRole" to m.role.canonicalRole.name,
                "status" to m.status.name,
                "joinDate" to m.joinDate
            )
        }
    }

    @Transactional(readOnly = true)
    fun getDiningConfig(messId: String): DiningConfigDto {
        val config = diningConfigRepository.findByMessId(messId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Dining configuration not found") }

        val sessions = sessionConfigRepository.findAllByDiningConfigId(config.id)
            .map {
                val defaultStart = when (it.session) {
                    MealSession.BREAKFAST -> "07:30"
                    MealSession.LUNCH -> "13:00"
                    MealSession.DINNER -> "20:30"
                }
                val defaultEnd = when (it.session) {
                    MealSession.BREAKFAST -> "09:30"
                    MealSession.LUNCH -> "14:30"
                    MealSession.DINNER -> "22:00"
                }
                SessionConfigDto(
                    session = it.session,
                    unitValue = it.unitValue,
                    cutoffTime = it.cutoffTime,
                    isEnabled = it.isEnabled,
                    servingStartTime = it.servingStartTime ?: defaultStart,
                    servingEndTime = it.servingEndTime ?: defaultEnd
                )
            }

        return DiningConfigDto(
            defaultCarryForward = config.defaultCarryForward,
            defaultBreakfastOn = config.defaultBreakfastOn,
            defaultLunchOn = config.defaultLunchOn,
            defaultDinnerOn = config.defaultDinnerOn,
            currency = config.currency,
            sessions = sessions
        )
    }

    @Transactional
    fun updateDiningConfig(messId: String, newCarryForward: Boolean?, sessionUpdates: List<SessionConfigDto>?): DiningConfigDto {
        val config = diningConfigRepository.findByMessId(messId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Dining configuration not found") }

        if (newCarryForward != null) {
            config.defaultCarryForward = newCarryForward
        }

        if (!sessionUpdates.isNullOrEmpty()) {
            for (su in sessionUpdates) {
                when (su.session) {
                    MealSession.BREAKFAST -> config.defaultBreakfastOn = su.isEnabled
                    MealSession.LUNCH -> config.defaultLunchOn = su.isEnabled
                    MealSession.DINNER -> config.defaultDinnerOn = su.isEnabled
                }
                val existing = sessionConfigRepository.findByDiningConfigIdAndSession(config.id, su.session)
                if (existing.isPresent) {
                    val s = existing.get()
                    s.unitValue = su.unitValue
                    s.cutoffTime = su.cutoffTime
                    s.isEnabled = su.isEnabled
                    if (!su.servingStartTime.isNullOrBlank()) s.servingStartTime = su.servingStartTime.trim()
                    if (!su.servingEndTime.isNullOrBlank()) s.servingEndTime = su.servingEndTime.trim()
                    sessionConfigRepository.save(s)
                }
            }
        }
        diningConfigRepository.save(config)

        return getDiningConfig(messId)
    }

    @Transactional
    fun updateMemberRole(messId: String, membershipId: String, newRole: UserRole, callerEmail: String): Map<String, Any?> {
        val membership = membershipRepository.findById(membershipId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Member record not found") }

        if (membership.messId != messId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Membership does not belong to this Meal")
        }

        if (membership.role.isOwner) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot modify Meal Owner role directly.")
        }

        if (newRole.isOwner) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Meal Owner role cannot be assigned directly. Use ownership transfer.")
        }

        membership.role = newRole
        val saved = membershipRepository.save(membership)

        return mapOf(
            "id" to saved.id,
            "userId" to saved.userId,
            "fullName" to saved.user?.fullName,
            "email" to saved.user?.email,
            "phone" to saved.user?.phone,
            "studentId" to saved.user?.studentId,
            "department" to saved.user?.department,
            "roomNumber" to saved.user?.roomNumber,
            "role" to saved.role.name,
            "status" to saved.status.name,
            "joinDate" to saved.joinDate
        )
    }

    @Transactional
    fun updateMemberRoom(messId: String, membershipId: String, newRoomNumber: String?, callerEmail: String): Map<String, Any?> {
        val membership = membershipRepository.findById(membershipId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Member record not found") }

        if (membership.messId != messId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Membership does not belong to this Meal")
        }

        val user = membership.user
            ?: userRepository.findById(membership.userId).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "User record not found")
            }

        user.roomNumber = newRoomNumber?.trim()?.ifBlank { null }
        val savedUser = userRepository.save(user)

        return mapOf(
            "id" to membership.id,
            "userId" to savedUser.id,
            "fullName" to savedUser.fullName,
            "email" to savedUser.email,
            "phone" to savedUser.phone,
            "studentId" to savedUser.studentId,
            "department" to savedUser.department,
            "roomNumber" to savedUser.roomNumber,
            "role" to membership.role.name,
            "status" to membership.status.name,
            "joinDate" to membership.joinDate
        )
    }

    @Transactional
    fun removeMember(messId: String, membershipId: String, callerEmail: String): Map<String, Any?> {
        val membership = membershipRepository.findById(membershipId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Member record not found") }

        if (membership.messId != messId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Membership does not belong to this Meal")
        }

        if (membership.role.isOwner) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot remove Meal Owner.")
        }

        membership.status = MembershipStatus.LEFT
        membership.leaveDate = LocalDateTime.now()
        val saved = membershipRepository.save(membership)

        return mapOf(
            "id" to saved.id,
            "userId" to saved.userId,
            "fullName" to saved.user?.fullName,
            "email" to saved.user?.email,
            "status" to saved.status.name,
            "leaveDate" to saved.leaveDate
        )
    }

    private fun mapJoinRequestDto(req: MealJoinRequest): MealJoinRequestDto {
        return MealJoinRequestDto(
            id = req.id,
            mealId = req.messId,
            mealName = req.mess?.name ?: "Meal Group",
            userId = req.userId,
            userName = req.user?.fullName ?: "Student",
            userEmail = req.user?.email ?: "",
            userPhone = req.user?.phone,
            studentId = req.user?.studentId,
            department = req.user?.department,
            roomNumber = req.user?.roomNumber,
            status = req.status,
            requestNotes = req.requestNotes,
            reviewedByName = req.reviewedBy?.fullName,
            reviewedAt = req.reviewedAt,
            createdAt = req.createdAt
        )
    }
}
